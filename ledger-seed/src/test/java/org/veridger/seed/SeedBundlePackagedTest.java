// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link SeedBundle#packaged(ClassLoader)}, {@link SeedBundle#ofFile(Path)}, {@link SeedBundle#installDefault(String)},
 * and {@link SeedBuilder#main(String[])} across valid and failure paths.
 */
class SeedBundlePackagedTest {

    private static final String CATEGORIES_TEXT = "code\tname_ko\tname_en\tdescription_ko\tdescription_en\n"
            + "ARC\t아키텍처\tArchitecture\t구조 결함\tStructural defect\n"
            + "SEC\t보안\tSecurity\t보안 결함\tSecurity defect\n";

    private static final String DEFAULTS_TEXT = """
            instance-name=veridger
            mcp-port=8765
            admin-port=8865
            """;

    @Test
    @DisplayName("SET-101 packaged(ClassLoader) extracts bundle into private temp dir, reads codes/defaults/digest, and cleans up")
    void packagedLoadsFromClassLoaderAndHandlesMissingDefault(@TempDir Path dir) throws Exception {
        Path sources = dir.resolve("sources");
        Path out = dir.resolve("out");
        Files.createDirectories(sources);
        Files.writeString(sources.resolve(SeedSources.CATEGORIES), CATEGORIES_TEXT, StandardCharsets.UTF_8);
        Files.writeString(sources.resolve(SeedSources.DEFAULTS_FILE), DEFAULTS_TEXT, StandardCharsets.UTF_8);

        SeedBuilder.main(new String[] { sources.toString(), out.toString() });
        Path built = out.resolve(SeedBundle.RESOURCE);
        assertThat(built).isRegularFile();

        try (URLClassLoader loader = new URLClassLoader(new URL[] { out.toUri().toURL() }, null)) {
            SeedBundle bundle = SeedBundle.packaged(loader);
            assertThat(bundle.codes()).hasSize(2);
            assertThat(bundle.codes().getFirst().code()).isEqualTo("ARC");
            assertThat(bundle.codes().get(1).sortOrder()).isEqualTo(20);
            assertThat(bundle.installDefault("instance-name")).isEqualTo("veridger");
            assertThat(bundle.installDefault("mcp-port")).isEqualTo("8765");
            assertThat(bundle.installDefaults()).containsEntry("admin-port", "8865");
            assertThat(bundle.digest()).hasSize(64).isEqualTo(SeedDigest.of(bundle.codes()));
            assertThatThrownBy(() -> bundle.installDefault("no-such-default"))
                    .isInstanceOf(SeedUnavailableException.class)
                    .hasMessageContaining("no-such-default");
        }
    }

    @Test
    @DisplayName("SET-102 missing packaged resource, unreadable bundle file, and invalid SeedBuilder CLI arguments fail cleanly")
    void failurePathsThrowClearExceptions(@TempDir Path dir) {
        SeedBundle missingPackaged = SeedBundle.packaged();
        assertThatThrownBy(missingPackaged::codes)
                .isInstanceOf(SeedUnavailableException.class)
                .hasMessageContaining(SeedBundle.RESOURCE);

        SeedBundle missingFile = SeedBundle.ofFile(dir.resolve("absent.mv.db"));
        assertThatThrownBy(missingFile::codes)
                .isInstanceOf(SeedUnavailableException.class);

        assertThatThrownBy(() -> SeedBuilder.main(new String[] { "only-one-arg" }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("usage");

        Path sources = dir.resolve("src-bad");
        assertThatThrownBy(() -> SeedBuilder.build(sources, dir.resolve("out-bad")))
                .isInstanceOf(SeedUnavailableException.class);
    }
}
