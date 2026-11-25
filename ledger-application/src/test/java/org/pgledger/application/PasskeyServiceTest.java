// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.admin.PasskeyService;
import org.pgledger.application.admin.PasskeyService.IssuedCode;
import org.pgledger.application.admin.PasskeyService.NewPasskey;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.TestClock;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.model.OwnerPasskey;
import org.pgledger.domain.model.PasskeyUser;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.domain.value.Sha256;

/**
 * PSK-SVC-01 .. PSK-SVC-09 (TEST_CASES section 39): the one-time code, its spending, the reset from the owner's shell, the
 * storage of a passkey (append-only history), the closing of a passkey by a revocation row, the one owner entity and the audit
 * rows, on the PostgreSQL of the test PC. The passkeys are one set per database: every test starts from the reset of that set (a
 * code issued with the removal of the existing passkeys), which is itself the operation under test in PSK-SVC-04.
 */
@LedgerServiceTest
class PasskeyServiceTest {

    private static final OwnerIdentity CLI = OwnerIdentity.of("cli:owner");
    private static final OwnerIdentity WEB = OwnerIdentity.of("passkey:owner");
    private static final String USER = "dXNlci1oYW5kbGU";

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static NewPasskey passkey(String label) {
        String id = UUID.randomUUID().toString().replace("-", "");
        return new NewPasskey(id, USER, b64("cose-key-" + id), 0, 1, 0, 0, "internal,hybrid", b64("attestation-" + id),
                label);
    }

    /** An empty set of passkeys and a fresh code (the reset the owner runs from the shell). */
    private static IssuedCode reset(Scenario s) {
        return s.bean(PasskeyService.class).issueCode(CLI, true);
    }

    private static List<String> audit(Scenario s, String operation) {
        return s.ctx().jdbc().sql("SELECT actor || ' ' || subject || ' ' || COALESCE(detail, '') FROM owner_audit "
                + "WHERE operation = :operation ORDER BY id DESC").param("operation", operation).query(String.class)
                .list();
    }

    @Test
    @DisplayName("PSK-SVC-01: a code is 20 characters of the alphabet in groups of four, lives 15 minutes and is stored only as its SHA-256")
    void svc01_codeShape(Scenario s) {
        IssuedCode issued = reset(s);

        assertThat(issued.code()).matches("[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){4}");
        assertThat(issued.expiresAt()).isEqualTo(TestClock.START.plus(Duration.ofMinutes(15)));
        String hash = Sha256.ofText(issued.code().replace("-", "")).hex();
        assertThat(s.ctx().jdbc().sql("SELECT COUNT(*) FROM owner_passkey_code WHERE code_hash = :hash")
                .param("hash", hash).query(Integer.class).single()).isEqualTo(1);
        assertThat(s.ctx().jdbc().sql("SELECT COUNT(*) FROM owner_passkey_code WHERE code_hash = :code")
                .param("code", issued.code()).query(Integer.class).single()).as("the code itself is never stored")
                .isZero();
        assertThat(reset(s).code()).as("every code is another one").isNotEqualTo(issued.code());
    }

    @Test
    @DisplayName("PSK-SVC-02: a code is spent once (any letter case, with or without dashes); a missing, unknown, expired or used code is PASSKEY_CODE_INVALID")
    void svc02_spendCode(Scenario s) {
        PasskeyService service = s.bean(PasskeyService.class);
        IssuedCode issued = reset(s);

        for (String code : new String[] {null, "", "AAAA-AAAA-AAAA-AAAA-AAAA"}) {
            Expect.failure(() -> service.spendCode(code), AdminCode.PASSKEY_CODE_INVALID);
        }
        service.spendCode(issued.code().replace("-", "").toLowerCase(java.util.Locale.ROOT));
        Expect.failure(() -> service.spendCode(issued.code()), AdminCode.PASSKEY_CODE_INVALID);

        IssuedCode late = reset(s);
        s.ctx().clock().set(TestClock.START.plus(Duration.ofMinutes(15)).plusSeconds(1));
        Expect.failure(() -> service.spendCode(late.code()), AdminCode.PASSKEY_CODE_INVALID);
        s.ctx().clock().set(TestClock.START);
    }

    @Test
    @DisplayName("PSK-SVC-03: asking for a new code ends the earlier one that was not used")
    void svc03_aNewCodeReplacesTheEarlierOne(Scenario s) {
        PasskeyService service = s.bean(PasskeyService.class);
        IssuedCode first = reset(s);
        IssuedCode second = service.issueCode(CLI, false);

        Expect.failure(() -> service.spendCode(first.code()), AdminCode.PASSKEY_CODE_INVALID);
        service.spendCode(second.code());
    }

    @Test
    @DisplayName("PSK-SVC-04: a code without the flag keeps the live passkeys; with it every live passkey gets a revocation row (PASSKEY_RESET and PASSKEY_CODE_ISSUED rows with the count) and the credential rows stay")
    void svc04_resetNeedsTheFlag(Scenario s) {
        PasskeyService service = s.bean(PasskeyService.class);
        reset(s);
        service.save(passkey("one"), WEB);
        service.save(passkey("two"), WEB);
        int resets = audit(s, "PASSKEY_RESET").size();
        int rows = s.ctx().jdbc().sql("SELECT COUNT(*) FROM owner_passkey").query(Integer.class).single();

        IssuedCode keep = service.issueCode(CLI, false);

        assertThat(keep.closedPasskeys()).isZero();
        assertThat(service.live()).hasSize(2);
        assertThat(audit(s, "PASSKEY_RESET")).as("no reset row without the flag").hasSize(resets);

        IssuedCode removed = service.issueCode(CLI, true);

        assertThat(removed.closedPasskeys()).isEqualTo(2);
        assertThat(service.live()).isEmpty();
        assertThat(audit(s, "PASSKEY_RESET")).hasSize(resets + 1).first().isEqualTo(CLI.value() + " passkey closed=2");
        assertThat(audit(s, "PASSKEY_CODE_ISSUED")).first().asString().startsWith(CLI.value() + " passkey expires=");
        assertThat(s.ctx().jdbc().sql("SELECT COUNT(*) FROM owner_passkey").query(Integer.class).single())
                .as("the credential rows are never deleted or changed").isEqualTo(rows);
    }

    @Test
    @DisplayName("PSK-SVC-05: a passkey is stored with its fingerprint and an audit row that says who and which key; a second save of the same credential changes nothing")
    void svc05_save(Scenario s) {
        PasskeyService service = s.bean(PasskeyService.class);
        reset(s);
        NewPasskey stored = passkey(" phone ");

        service.save(stored, WEB);
        service.save(new NewPasskey(stored.credentialId(), USER, stored.publicKey(), 9, 0, 1, 1, "", stored.attestationObject(),
                "renamed"), WEB);

        assertThat(service.live()).singleElement().satisfies(live -> {
            assertThat(live.credentialId()).isEqualTo(stored.credentialId());
            assertThat(live.label()).isEqualTo("phone");
            assertThat(live.signatureCount()).isZero();
            assertThat(live.uvInitialized()).isEqualTo(1);
            assertThat(live.transports()).isEqualTo("internal,hybrid");
            assertThat(live.registeredBy()).isEqualTo(WEB.value());
            assertThat(live.fingerprint()).isEqualTo(PasskeyService.fingerprint(stored.publicKey()))
                    .matches("[0-9A-F]{4}(-[0-9A-F]{4}){3}");
        });
        assertThat(service.findLive(stored.credentialId())).isPresent();
        assertThat(audit(s, "PASSKEY_REGISTERED")).first()
                .isEqualTo(WEB.value() + " " + PasskeyService.fingerprint(stored.publicKey()) + " label=phone");
        assertThat(audit(s, "PASSKEY_REGISTERED").stream().filter(row -> row.contains("renamed")).count()).isZero();
    }

    @Test
    @DisplayName("PSK-SVC-06: the last live passkey is not closed (PASSKEY_LAST_KEY); with two, one closes by a revocation row and the audit row says which; an unknown or closed id is NOT_FOUND")
    void svc06_revoke(Scenario s) {
        PasskeyService service = s.bean(PasskeyService.class);
        reset(s);
        NewPasskey one = passkey("one");
        NewPasskey two = passkey("two");
        service.save(one, WEB);

        Expect.failure(() -> service.revoke(one.credentialId(), WEB), AdminCode.PASSKEY_LAST_KEY);
        service.save(two, WEB);
        service.revoke(one.credentialId(), WEB);

        assertThat(service.live()).singleElement().satisfies(live -> assertThat(live.credentialId())
                .isEqualTo(two.credentialId()));
        assertThat(service.findLive(one.credentialId())).isEmpty();
        assertThat(s.ctx().jdbc().sql("SELECT revoked_by FROM owner_passkey_revocation WHERE credential_id = :id")
                .param("id", one.credentialId()).query(String.class).single()).isEqualTo(WEB.value());
        assertThat(s.ctx().jdbc().sql("SELECT COUNT(*) FROM owner_passkey WHERE credential_id = :id")
                .param("id", one.credentialId()).query(Integer.class).single()).as("the credential row stays").isEqualTo(1);
        assertThat(audit(s, "PASSKEY_REVOKED")).first().isEqualTo(WEB.value() + " "
                + PasskeyService.fingerprint(one.publicKey()) + " label=one");
        Expect.failure(() -> service.revoke(one.credentialId(), WEB), LookupCode.NOT_FOUND);
        Expect.failure(() -> service.revoke("unknown", WEB), LookupCode.NOT_FOUND);
    }

    @Test
    @DisplayName("PSK-SVC-07: there is one owner entity: the second one is left out, and it is found by its handle")
    void svc07_oneOwner(Scenario s) {
        PasskeyService service = s.bean(PasskeyService.class);
        if (service.owner().isEmpty()) {
            service.saveOwner(new PasskeyUser(USER, "owner", "Owner"));
        }
        PasskeyUser first = service.owner().orElseThrow();

        service.saveOwner(new PasskeyUser("b3RoZXI", "second", null));

        assertThat(service.owner()).contains(first);
        assertThat(service.ownerById(first.userId())).contains(first);
        assertThat(service.ownerById("b3RoZXI")).isEmpty();
    }

    @Test
    @DisplayName("PSK-SVC-08: too long a label, credential id or key is refused and stores nothing; a blank label is passkey")
    void svc08_limits(Scenario s) {
        PasskeyService service = s.bean(PasskeyService.class);
        reset(s);
        NewPasskey base = passkey("x");

        Expect.firstIssue(Expect.failure(() -> service.save(new NewPasskey(base.credentialId(), USER, base.publicKey(), 0, 1,
                0, 0, "", base.attestationObject(), "l".repeat(OwnerPasskey.LABEL_MAX_LENGTH + 1)), WEB),
                org.pgledger.domain.error.ValidationCode.VALIDATION_FAILED));
        Expect.failure(() -> service.save(new NewPasskey("c".repeat(OwnerPasskey.CREDENTIAL_ID_MAX_LENGTH + 1), USER,
                base.publicKey(), 0, 1, 0, 0, "", base.attestationObject(), "x"), WEB),
                org.pgledger.domain.error.ValidationCode.VALIDATION_FAILED);
        assertThat(service.live()).isEmpty();

        service.save(new NewPasskey(base.credentialId(), USER, base.publicKey(), 0, 1, 0, 0, "", base.attestationObject(), "  "),
                WEB);
        assertThat(service.live()).singleElement().satisfies(live -> assertThat(live.label()).isEqualTo("passkey"));
    }

    @Test
    @DisplayName("PSK-SVC-09: the live passkeys are listed oldest first")
    void svc09_order(Scenario s) {
        PasskeyService service = s.bean(PasskeyService.class);
        reset(s);
        NewPasskey a = passkey("a");
        NewPasskey b = passkey("b");
        s.ctx().clock().set(TestClock.START);
        service.save(a, WEB);
        s.ctx().clock().set(TestClock.START.plusSeconds(5));
        service.save(b, WEB);
        s.ctx().clock().set(TestClock.START);

        assertThat(service.live()).extracting(OwnerPasskey::label).containsExactly("a", "b");
    }
}
