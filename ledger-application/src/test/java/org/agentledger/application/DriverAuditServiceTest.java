// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.admin.DriverAuditService;
import org.agentledger.application.admin.OwnerAuditWriter;
import org.agentledger.application.support.Expect;
import org.agentledger.application.support.LedgerServiceTest;
import org.agentledger.application.support.Parallel;
import org.agentledger.application.support.Scenario;
import org.agentledger.database.repository.DriverAuditKeyRepository;
import org.agentledger.database.repository.OwnerAuditRepository;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.domain.model.OwnerAuditEntry;
import org.agentledger.domain.value.OwnerIdentity;

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
    @DisplayName("TXF-004 (TX-03, principle 4.4): a run that looked at the trail before the other run committed (it sees no row) writes nothing: the key of the registration is unique, the claim breaks it and recordIfMissing ends with the translated DUPLICATE_KEY (not a raw exception, not 'already recorded, continue'); a registration with another time is another registration")
    void txf004_theUniqueKeyDecidesWhenTheReadMissedTheRow(Scenario s) {
        DriverAuditService first = s.bean(DriverAuditService.class);
        DriverAuditService blind = new DriverAuditService(s.bean(OwnerAuditWriter.class), BLIND_TRAIL,
                s.bean(DriverAuditKeyRepository.class));
        String driver = newDriverClass();
        String known = "jar=/drivers/c.jar sha256=cc";

        assertThat(first.recordIfMissing(OWNER, driver, known, WHEN)).isTrue();
        Expect.failure(() -> s.ctx().tx(() -> blind.recordIfMissing(OWNER, driver, known, WHEN)),
                ConflictCode.DUPLICATE_KEY);

        assertThat(audit(s, driver)).containsExactly(
                "DRIVER_REGISTERED token:owner jar=/drivers/c.jar sha256=cc registered_at=2026-11-05T10:00:00Z");
        assertThat(s.count("SELECT COUNT(*) FROM driver_audit_key")).isPositive();
        // another time of registration is another registration: its row is written
        assertThat(s.ctx().tx(() -> blind.recordIfMissing(OWNER, driver, known, WHEN.plusSeconds(1)))).isTrue();
        assertThat(audit(s, driver)).hasSize(2);
    }

    @Test
    @DisplayName("TXF-004 (TX-03, principle 4.4): 8 calls of recordIfMissing for the same registration at the same time write exactly one row and exactly one of them answers true; each other call answers false (it read the committed row) or ends with the translated DUPLICATE_KEY, never a raw exception")
    void txf004_concurrentRunsWriteOneRow(Scenario s) {
        DriverAuditService service = s.bean(DriverAuditService.class);
        String driver = newDriverClass();
        List<Callable<Boolean>> calls = IntStream.range(0, 8).<Callable<Boolean>>mapToObj(i -> () -> service
                .recordIfMissing(OWNER, driver, "jar=/drivers/d.jar sha256=dd", WHEN)).toList();

        List<Parallel.Outcome<Boolean>> outcomes = Parallel.all(calls);

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).as("only the translated DUPLICATE_KEY")
                .satisfiesAnyOf(failure -> assertThat(failure).isNull(),
                        failure -> assertThat(failure).isInstanceOfSatisfying(LedgerException.class,
                                refused -> assertThat(refused.code()).isEqualTo(ConflictCode.DUPLICATE_KEY))));
        assertThat(outcomes.stream().filter(outcome -> Boolean.TRUE.equals(outcome.value()))).hasSize(1);
        assertThat(audit(s, driver)).hasSize(1);
    }
}
