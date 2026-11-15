package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.DriverAuditService;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.domain.value.OwnerIdentity;

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
}
