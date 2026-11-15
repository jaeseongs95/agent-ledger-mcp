package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.DriverAuditService;
import org.pgledger.application.admin.DriverAuditWriter;
import org.pgledger.application.admin.OwnerAuditWriter;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Scenario;
import org.pgledger.database.repository.OwnerAuditRepository;
import org.pgledger.domain.model.OwnerAuditEntry;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.dao.DuplicateKeyException;

/**
 * TXD-001 .. TXD-002 (principle 4.4): the audit rows of a driver registration are written by a service method that opens and
 * commits its own transaction. The caller (the admin web driver page, the command line tool) holds no transaction and the file
 * of the registration is replaced after the call has returned.
 */
@LedgerServiceTest
class DriverAuditServiceTest {

    private static final OwnerIdentity OWNER = OwnerIdentity.of("token:owner");
    private static final Instant WHEN = Instant.parse("2026-11-05T10:00:00Z");

    private static String newDriverClass() {
        return "org.example.Driver" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static List<String> audit(Scenario s, String subject) {
        return s.ctx().jdbc().sql("SELECT operation || ' ' || actor || ' ' || COALESCE(detail, '') FROM owner_audit "
                + "WHERE subject = :subject ORDER BY id").param("subject", subject).query(String.class).list();
    }

    @Test
    @DisplayName("TXD-001: recordRegistered called with no transaction of the caller writes one committed DRIVER_REGISTERED row")
    void txd001_theRowIsCommittedByTheServiceItself(Scenario s) {
        String driver = newDriverClass();

        s.bean(DriverAuditService.class).recordRegistered(OWNER, driver, "jar=/drivers/a.jar sha256=aa");

        assertThat(audit(s, driver)).containsExactly("DRIVER_REGISTERED token:owner jar=/drivers/a.jar sha256=aa");
    }

    @Test
    @DisplayName("TXD-002: recordIfMissing writes the row of a registration once, and only when no row starts with its jar and digest")
    void txd002_aMissingRowIsWrittenOnce(Scenario s) {
        DriverAuditService service = s.bean(DriverAuditService.class);
        String driver = newDriverClass();
        service.recordRegistered(OWNER, driver, "jar=/drivers/a.jar sha256=aa");

        assertThat(service.recordIfMissing(OWNER, driver, "jar=/drivers/a.jar sha256=aa", WHEN))
                .as("the row of exactly this registration is there").isFalse();
        assertThat(service.recordIfMissing(OWNER, driver, "jar=/drivers/b.jar sha256=bb", WHEN)).isTrue();
        assertThat(service.recordIfMissing(OWNER, driver, "jar=/drivers/b.jar sha256=bb", WHEN))
                .as("a second call writes nothing").isFalse();

        assertThat(audit(s, driver)).containsExactly("DRIVER_REGISTERED token:owner jar=/drivers/a.jar sha256=aa",
                "DRIVER_REGISTERED token:owner jar=/drivers/b.jar sha256=bb registered_at=2026-11-05T10:00:00Z");
    }

    /** A trail that never shows a row: what a run sees that looked before the other run committed. */
    private static final OwnerAuditRepository BLIND_TRAIL = new OwnerAuditRepository() {
        @Override
        public OwnerAuditEntry insert(OwnerAuditEntry aggregate) {
            throw new UnsupportedOperationException("the blind trail is read only");
        }

        @Override
        public List<OwnerAuditEntry> findBySubjectOrderById(String subject) {
            return List.of();
        }

        @Override
        public List<OwnerAuditEntry> findByActorOrderById(String actor) {
            return List.of();
        }
    };

    @Test
    @DisplayName("TXF-004 (TX-03): a run that looked at the trail before the other run committed (it sees no row) still writes nothing: the key of the registration is unique, the second claim is refused by the database and recordIfMissing answers false")
    void txf004_theUniqueKeyDecidesWhenTheReadMissedTheRow(Scenario s) {
        DriverAuditService first = s.bean(DriverAuditService.class);
        DriverAuditService blind = new DriverAuditService(s.bean(OwnerAuditWriter.class), BLIND_TRAIL,
                s.bean(DriverAuditWriter.class));
        String driver = newDriverClass();
        String known = "jar=/drivers/c.jar sha256=cc";

        assertThat(first.recordIfMissing(OWNER, driver, known, WHEN)).isTrue();
        assertThat(blind.recordIfMissing(OWNER, driver, known, WHEN)).as("the key was claimed by the first run").isFalse();

        assertThat(audit(s, driver)).containsExactly(
                "DRIVER_REGISTERED token:owner jar=/drivers/c.jar sha256=cc registered_at=2026-11-05T10:00:00Z");
        assertThat(s.count("SELECT COUNT(*) FROM driver_audit_key")).isPositive();
        // another time of registration is another registration: its row is written
        assertThat(blind.recordIfMissing(OWNER, driver, known, WHEN.plusSeconds(1))).isTrue();
        assertThat(audit(s, driver)).hasSize(2);
    }

    @Test
    @DisplayName("TXF-004 (TX-03): 8 calls of recordIfMissing for the same registration at the same time write exactly one row, and exactly one of them answers true")
    void txf004_concurrentRunsWriteOneRow(Scenario s) {
        DriverAuditService service = s.bean(DriverAuditService.class);
        String driver = newDriverClass();
        List<Callable<Boolean>> calls = IntStream.range(0, 8).<Callable<Boolean>>mapToObj(i -> () -> service
                .recordIfMissing(OWNER, driver, "jar=/drivers/d.jar sha256=dd", WHEN)).toList();

        List<Parallel.Outcome<Boolean>> outcomes = Parallel.all(calls);

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        assertThat(outcomes.stream().filter(outcome -> Boolean.TRUE.equals(outcome.value()))).hasSize(1);
        assertThat(audit(s, driver)).hasSize(1);
    }

    @Test
    @DisplayName("TXF-004 (TX-03): the claim of a registration is a nested write (DriverAuditWriter): a second claim of the key is a DuplicateKeyException that takes back that call only, so the transaction it ran in stays usable and the next claim of another key commits")
    void txf004_aRefusedClaimLeavesTheTransactionUsable(Scenario s) {
        DriverAuditWriter writer = s.bean(DriverAuditWriter.class);
        String driver = newDriverClass();
        String key = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String other = UUID.randomUUID().toString().replace("-", "").repeat(2);

        s.ctx().tx(() -> {
            writer.recordOnce(key, OWNER, driver, "first");
            assertThatThrownBy(() -> writer.recordOnce(key, OWNER, driver, "again"))
                    .isInstanceOf(DuplicateKeyException.class);
            writer.recordOnce(other, OWNER, driver, "second");
            return null;
        });

        assertThat(audit(s, driver)).containsExactly("DRIVER_REGISTERED token:owner first",
                "DRIVER_REGISTERED token:owner second");
    }
}
