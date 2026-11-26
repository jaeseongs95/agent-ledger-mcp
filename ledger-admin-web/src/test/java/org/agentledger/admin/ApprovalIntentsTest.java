// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.admin.passkey.ApprovalIntents;
import org.agentledger.admin.passkey.ApprovalIntents.Binding;
import org.agentledger.admin.support.MutableClock;
import org.agentledger.domain.error.AdminCode;
import org.agentledger.domain.error.AdminException;
import tools.jackson.databind.json.JsonMapper;

/**
 * PSK-INT-01 .. PSK-INT-05 (TEST_CASES section 39): the approvals this process asked for. The binding of a write is the digest of
 * its fields (the CSRF token and the approval itself left out, the order of the fields and of the values of a name not mattering)
 * and of its session; an approval is spent once, expires, and is bound to the write and the session it was asked for.
 */
class ApprovalIntentsTest {

    private final MutableClock clock = new MutableClock();
    private final ApprovalIntents intents = new ApprovalIntents(clock, JsonMapper.builder().build());

    private static Binding binding(Map<String, List<String>> fields, String session) {
        return ApprovalIntents.binding("POST", "/projects", fields, session);
    }

    private static String key(byte[] challenge) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);
    }

    private static AdminCode refused(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(AdminException.class);
        return (AdminCode) ((AdminException) thrown).code();
    }

    @Test
    @DisplayName("PSK-INT-01: the binding ignores the CSRF token, the approval and the order of fields and of the values of a name; any other change is another binding")
    void int01_binding() {
        Binding base = binding(Map.of("id", List.of("p"), "scopes", List.of("b", "a")), "session");

        assertThat(binding(Map.of("scopes", List.of("a", "b"), "id", List.of("p"), "_csrf", List.of("x"), "approval",
                List.of("y")), "session")).isEqualTo(base);
        assertThat(binding(Map.of("id", List.of("q"), "scopes", List.of("b", "a")), "session")).isNotEqualTo(base);
        assertThat(binding(Map.of("id", List.of("p"), "scopes", List.of("b")), "session")).isNotEqualTo(base);
        assertThat(binding(Map.of("id", List.of("p"), "scopes", List.of("b", "a")), "another")).isNotEqualTo(base);
        assertThat(ApprovalIntents.binding("PUT", "/projects", Map.of("id", List.of("p"), "scopes", List.of("b", "a")),
                "session")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("PSK-INT-02: an approval is spent once; the challenge is 32 bytes and every request gives another")
    void int02_spentOnce() {
        Binding binding = binding(Map.of("id", List.of("p")), "session");
        byte[] first = intents.issue(binding);
        byte[] second = intents.issue(binding);

        intents.spend(key(first), binding);

        assertThat(first).hasSize(32).isNotEqualTo(second);
        assertThat(refused(() -> intents.spend(key(first), binding))).isEqualTo(AdminCode.PASSKEY_APPROVAL_INVALID);
        intents.spend(key(second), binding);
    }

    @Test
    @DisplayName("PSK-INT-03: an approval for another write or another session is refused, and it is gone afterwards")
    void int03_bound() {
        Binding binding = binding(Map.of("id", List.of("p")), "session");
        byte[] challenge = intents.issue(binding);

        assertThat(refused(() -> intents.spend(key(challenge), binding(Map.of("id", List.of("q")), "session"))))
                .isEqualTo(AdminCode.PASSKEY_APPROVAL_INVALID);
        assertThat(refused(() -> intents.spend(key(challenge), binding))).isEqualTo(AdminCode.PASSKEY_APPROVAL_INVALID);
        assertThat(refused(() -> intents.spend(key(new byte[32]), binding))).isEqualTo(AdminCode.PASSKEY_APPROVAL_INVALID);
    }

    @Test
    @DisplayName("PSK-INT-04: an approval is good for its time to live and not a moment longer")
    void int04_expires() {
        Binding binding = binding(Map.of("id", List.of("p")), "session");
        byte[] fresh = intents.issue(binding);
        byte[] stale = intents.issue(binding);

        clock.advance(ApprovalIntents.TTL);
        intents.spend(key(fresh), binding);
        clock.advance(Duration.ofSeconds(1));

        assertThat(refused(() -> intents.spend(key(stale), binding))).isEqualTo(AdminCode.PASSKEY_APPROVAL_INVALID);
    }

    @Test
    @DisplayName("PSK-INT-05: ceiling: past 1024 pending approvals the oldest is dropped first")
    void int05_ceiling() {
        Binding binding = binding(Map.of("id", List.of("p")), "session");
        byte[] oldest = intents.issue(binding);
        byte[] newest = null;
        for (int i = 0; i < 1024; i++) {
            newest = intents.issue(binding);
        }

        assertThat(refused(() -> intents.spend(key(oldest), binding))).isEqualTo(AdminCode.PASSKEY_APPROVAL_INVALID);
        intents.spend(key(newest), binding);
    }

    @Test
    @DisplayName("PSK-INT-06 (D-0062): a line break or an equals sign inside a name or a value does not move a boundary: one field whose value holds a break and \"b=y\" is not two fields, and the name \"a=x\" with the value \"y\" is not the name \"a\" with the value \"x=y\"")
    void int06_separatorsInValuesDoNotMerge() {
        Binding oneField = binding(Map.of("a", List.of("x\nb=y")), "session");
        Binding twoFields = binding(Map.of("a", List.of("x"), "b", List.of("y")), "session");
        Binding equalsInName = binding(Map.of("a=x", List.of("y")), "session");
        Binding equalsInValue = binding(Map.of("a", List.of("x=y")), "session");

        assertThat(oneField).isNotEqualTo(twoFields);
        assertThat(equalsInName).isNotEqualTo(equalsInValue);
    }
}
