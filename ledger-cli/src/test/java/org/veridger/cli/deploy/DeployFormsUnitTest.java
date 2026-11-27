// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.UsageException;

/**
 * Exhaustive unit tests for {@link DeployForms} covering host names, instance names, path limits, and plain absolute paths.
 */
class DeployFormsUnitTest {

    @Test
    @DisplayName("DEP-FRM-01 isHostName validates RFC-1123 labels, length cap, and refuses backtracking/malformed hosts")
    void isHostNameBoundaries() {
        assertThat(DeployForms.isHostName("localhost")).isTrue();
        assertThat(DeployForms.isHostName("a")).isTrue();
        assertThat(DeployForms.isHostName("ledger-1.example-host.internal")).isTrue();
        assertThat(DeployForms.isHostName("a".repeat(63) + ".example")).isTrue();

        assertThat(DeployForms.isHostName(null)).isFalse();
        assertThat(DeployForms.isHostName("")).isFalse();
        assertThat(DeployForms.isHostName("LOCALHOST")).isFalse();
        assertThat(DeployForms.isHostName("-bad.example")).isFalse();
        assertThat(DeployForms.isHostName("bad-.example")).isFalse();
        assertThat(DeployForms.isHostName("a..b")).isFalse();
        assertThat(DeployForms.isHostName("a".repeat(64) + ".example")).isFalse();
        assertThat(DeployForms.isHostName(("a".repeat(50) + ".").repeat(6) + "x")).isFalse();
        assertThat(DeployForms.isHostName("a".repeat(50) + "!")).isFalse();
    }

    @Test
    @DisplayName("DEP-FRM-02 requireName validates plain instance names and rejects null, invalid, and -admin suffix")
    void requireNameBoundaries() {
        assertThat(DeployForms.requireName("v", "--instance")).isEqualTo("v");
        assertThat(DeployForms.requireName("veridger-prod-01", "--instance")).isEqualTo("veridger-prod-01");
        assertThat(DeployForms.requireName("a" + "b".repeat(21) + "9", "--instance")).hasSize(23);

        for (String invalid : new String[] { null, "", "1bad", "UPPER", "bad-", "my-admin", "a".repeat(24) }) {
            assertThatThrownBy(() -> DeployForms.requireName(invalid, "--instance"))
                    .isInstanceOf(UsageException.class)
                    .extracting(e -> ((UsageException) e).code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);
        }
    }

    @Test
    @DisplayName("DEP-FRM-03 isPath, requirePath, and isPlainAbsolute enforce length cap, plain segments, and absolute roots")
    void pathValidationBoundaries() {
        String unixForm = "/(?:" + DeployForms.SEGMENT + "/)*" + DeployForms.SEGMENT;
        assertThat(DeployForms.isPath("/opt/veridger", unixForm)).isTrue();
        assertThat(DeployForms.requirePath("/opt/veridger", unixForm, "--root")).isEqualTo("/opt/veridger");
        assertThat(DeployForms.isPath(null, unixForm)).isFalse();
        assertThat(DeployForms.isPath("/" + "a".repeat(201), unixForm)).isFalse();
        assertThat(DeployForms.isPath("/opt/../etc", unixForm)).isFalse();
        assertThatThrownBy(() -> DeployForms.requirePath("/opt/with space", unixForm, "--root"))
                .isInstanceOf(UsageException.class)
                .extracting(e -> ((UsageException) e).code()).isEqualTo(ErrorCode.INVALID_DEPLOY_VALUE);

        Path cwdRoot = Path.of(".").toAbsolutePath().normalize().getRoot();
        assertThat(DeployForms.isPlainAbsolute(cwdRoot.resolve("veridger").resolve("data-1"))).isTrue();
        assertThat(DeployForms.isPlainAbsolute(Path.of("relative/path"))).isFalse();
        assertThat(DeployForms.isPlainAbsolute(cwdRoot)).isFalse();
        assertThat(DeployForms.isPlainAbsolute(cwdRoot.resolve("a".repeat(201)))).isFalse();
        assertThat(DeployForms.isPlainAbsolute(cwdRoot.resolve("bad space"))).isFalse();
        assertThat(DeployForms.isPlainAbsolute(cwdRoot.resolve("ok").resolve(".."))).isFalse();
    }
}
