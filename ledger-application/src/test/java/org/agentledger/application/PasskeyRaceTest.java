// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.admin.PasskeyService;
import org.agentledger.application.admin.PasskeyService.IssuedCode;
import org.agentledger.application.admin.PasskeyService.NewPasskey;
import org.agentledger.application.support.HeldTransaction;
import org.agentledger.application.support.LedgerServiceTest;
import org.agentledger.application.support.Parallel;
import org.agentledger.application.support.Scenario;
import org.agentledger.domain.error.AdminCode;
import org.agentledger.domain.error.AdminException;
import org.agentledger.domain.error.LookupCode;
import org.agentledger.domain.error.LookupException;
import org.agentledger.domain.value.OwnerIdentity;

/**
 * R9-D0081 and R9-D0082: two owner operations on the passkeys that run at the same time. The two meet deterministically, with
 * the lock of the database and not the clock: a second connection holds the row of the counter {@code OWNER_AUDIT}, which
 * every one of these operations needs (for its audit row), the two operations are started and waited for in
 * {@code pg_stat_activity}, and the second connection commits. An operation that checked first and locked later has then
 * done its check on the state that the other one has not yet committed.
 */
@LedgerServiceTest
class PasskeyRaceTest {

    private static final OwnerIdentity CLI = OwnerIdentity.of("cli:owner");
    private static final OwnerIdentity WEB = OwnerIdentity.of("passkey:owner");
    private static final String USER = "dXNlci1oYW5kbGU";

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static NewPasskey passkey(String label) {
        String id = UUID.randomUUID().toString().replace("-", "");
        return new NewPasskey(id, USER, b64("cose-key-" + id), 0, 1, 0, 0, "internal", b64("attestation-" + id), label);
    }

    /** The reset of the owner (no live passkey) and then {@code count} live passkeys; their credential ids. */
    private static List<String> livePasskeys(Scenario s, int count) {
        PasskeyService service = s.bean(PasskeyService.class);
        service.issueCode(CLI, true);
        List<NewPasskey> made = java.util.stream.IntStream.range(0, count).mapToObj(i -> passkey("key" + i)).toList();
        made.forEach(key -> service.save(key, WEB));
        return made.stream().map(NewPasskey::credentialId).toList();
    }

    /** Runs the two calls while a second connection holds the audit counter, and returns what each of them ended with. */
    private static List<Throwable> runTogether(Scenario s, Runnable first, Runnable second) throws Exception {
        HeldTransaction<Integer> holder = HeldTransaction.start(s.ctx(), () -> s.ctx().jdbc()
                .sql("UPDATE id_counter SET issued_no = issued_no WHERE name = 'OWNER_AUDIT'").update());
        holder.awaitHeld();
        Future<?> one = Parallel.start(() -> {
            first.run();
            return null;
        });
        Future<?> two = Parallel.start(() -> {
            second.run();
            return null;
        });
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(10)).until(() -> s.ctx().jdbc()
                .sql("SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() "
                        + "AND wait_event_type = 'Lock'").query(Long.class).single() >= 2);
        holder.release();
        return java.util.Arrays.asList(failure(one), failure(two));
    }

    /** The failure of a finished call, or null. */
    private static Throwable failure(Future<?> call) throws Exception {
        try {
            call.get(60, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    @Test
    @DisplayName("R9-D0081: two passkeys revoked at the same time leave one live; the other revocation is PASSKEY_LAST_KEY")
    void theLastPasskeyIsKeptWhenTwoAreRevokedTogether(Scenario s) throws Exception {
        PasskeyService service = s.bean(PasskeyService.class);
        List<String> keys = livePasskeys(s, 2);

        List<Throwable> ended = runTogether(s, () -> service.revoke(keys.get(0), WEB),
                () -> service.revoke(keys.get(1), WEB));

        assertThat(service.live()).as("the last live passkey is never closed").hasSize(1);
        assertThat(ended).filteredOn(failure -> failure == null).hasSize(1);
        assertThat(ended).filteredOn(failure -> failure != null).singleElement().isInstanceOfSatisfying(
                AdminException.class, e -> assertThat(e.code()).isEqualTo(AdminCode.PASSKEY_LAST_KEY));
    }

    @Test
    @DisplayName("R9-D0081: one passkey revoked twice at the same time is revoked once; the other call is NOT_FOUND, not a duplicate key")
    void theSamePasskeyRevokedTwiceIsRevokedOnce(Scenario s) throws Exception {
        PasskeyService service = s.bean(PasskeyService.class);
        List<String> keys = livePasskeys(s, 3);

        List<Throwable> ended = runTogether(s, () -> service.revoke(keys.get(0), WEB),
                () -> service.revoke(keys.get(0), WEB));

        assertThat(service.live()).hasSize(2);
        assertThat(ended).filteredOn(failure -> failure == null).hasSize(1);
        assertThat(ended).filteredOn(failure -> failure != null).singleElement().isInstanceOfSatisfying(
                LookupException.class, e -> assertThat(e.code()).isEqualTo(LookupCode.NOT_FOUND));
    }

    @Test
    @DisplayName("R9-D0082: two codes asked for at the same time leave one code that can be spent: the later one ends the earlier")
    void aNewCodeEndsTheEarlierOneWhenTwoAreAskedForTogether(Scenario s) throws Exception {
        PasskeyService service = s.bean(PasskeyService.class);
        livePasskeys(s, 1);
        IssuedCode[] issued = new IssuedCode[2];

        runTogether(s, () -> issued[0] = service.issueCode(CLI, false), () -> issued[1] = service.issueCode(CLI, false));

        long spendable = List.of(issued[0], issued[1]).stream().filter(code -> {
            try {
                service.spendCode(code.code());
                return true;
            } catch (AdminException e) {
                return false;
            }
        }).count();
        assertThat(spendable).as("one code is open at a time").isEqualTo(1);
    }
}
