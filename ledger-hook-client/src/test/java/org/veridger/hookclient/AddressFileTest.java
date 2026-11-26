// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hookclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.veridger.files.ProcessAccount;

/** R9-D0096-F (address file): the file the program writes and every client reads. */
class AddressFileTest {

    @TempDir
    Path folder;

    private OwnerOnlyFiles files() throws IOException {
        return OwnerOnlyFiles.forFileSystem(FileSystems.getDefault(), ProcessAccount.name().orElse(""));
    }

    @Test
    @DisplayName("R9-D0096-F the address written is the address read; the secret is a new 256-bit value for every start and the text of the address never shows it")
    void roundTripAndRedaction() throws IOException {
        AddressFile file = new AddressFile(folder.resolve("address"));
        String secret = ResidentAddress.newSecret();
        ResidentAddress written = new ResidentAddress(5555, 4242, 1795307660377L, "1-100-200", secret);
        file.write(written, files());

        assertThat(file.read()).contains(written);
        assertThat(written.toString()).doesNotContain(secret).contains("5555").contains("4242");
        assertThat(ResidentAddress.newSecret()).isNotEqualTo(secret).hasSize(43);
        try (Stream<Path> names = Files.list(folder)) {
            assertThat(names).as("the temporary file of the write is gone").containsExactly(folder.resolve("address"));
        }
        file.write(new ResidentAddress(6666, 4243, 0, "1-100-200", ResidentAddress.newSecret()), files());
        assertThat(file.read().orElseThrow().port()).as("the new file replaced the old one").isEqualTo(6666);
    }

    @Test
    @DisplayName("R9-D0096-F a file that is missing is no address; removeIfOwnedBy removes the file of this process only, never the one of a newer program")
    void missingAndOwnership() throws IOException {
        AddressFile file = new AddressFile(folder.resolve("address"));
        assertThat(file.read()).isEmpty();
        file.removeIfOwnedBy(1);

        file.write(new ResidentAddress(5555, 4242, 0, "b", ResidentAddress.newSecret()), files());
        file.removeIfOwnedBy(9999);
        assertThat(folder.resolve("address")).exists();
        file.removeIfOwnedBy(4242);
        assertThat(folder.resolve("address")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "garbage", "port=1\n", "port=0\npid=1\nprocessStart=0\nbuild=b\nsecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "port=70000\npid=1\nprocessStart=0\nbuild=b\nsecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "port=x\npid=1\nprocessStart=0\nbuild=b\nsecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "port=80\npid=x\nprocessStart=0\nbuild=b\nsecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "port=80\npid=1\nprocessStart=0\nbuild=\nsecret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "port=80\npid=1\nprocessStart=0\nbuild=b\nsecret=short",
            "port=80\npid=1\nprocessStart=0\nbuild=b\nsecret=not base64 at all!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"})
    @DisplayName("R9-D0096-F a file that is not an address (empty, cut, a port or pid that is no number, a port out of range, no build, a secret of the wrong size or not base64) is no address and never an error: it is a stale file")
    void aFileThatIsNotAnAddressIsStale(String content) throws IOException {
        Path path = folder.resolve("address");
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        assertThat(new AddressFile(path).read()).isEmpty();
    }

    @Test
    @DisplayName("R9-D0096-F a line of a later version is ignored: the five known lines still make the address")
    void unknownLinesAreIgnored() throws IOException {
        String secret = ResidentAddress.newSecret();
        Path path = folder.resolve("address");
        Files.writeString(path, "port=81\nfuture=1\npid=2\nprocessStart=3\nbuild=b\nsecret=" + secret + "\n");
        assertThat(new AddressFile(path).read()).contains(new ResidentAddress(81, 2, 3, "b", secret));
    }

    @Test
    @DisplayName("3.0.1 concurrent polling reads do not break atomic AddressFile replacement")
    void concurrentReadsDoNotBreakAtomicReplacement() throws Exception {
        AddressFile file = new AddressFile(folder.resolve("address"));
        OwnerOnlyFiles ownerFiles = files();
        file.write(new ResidentAddress(5000, 1000, 1L, "1-1-1", ResidentAddress.newSecret()), ownerFiles);
        AtomicBoolean done = new AtomicBoolean(false);

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<Integer> reader = pool.submit(() -> {
                int reads = 0;
                while (!done.get()) {
                    Optional<ResidentAddress> seen = file.read();
                    if (seen.isPresent()) {
                        assertThat(seen.orElseThrow().port()).isBetween(5000, 5050);
                        reads++;
                    }
                }
                return reads;
            });
            try {
                for (int i = 1; i <= 50; i++) {
                    file.write(new ResidentAddress(5000 + i, 1000 + i, i, "1-1-1", ResidentAddress.newSecret()), ownerFiles);
                }
            } finally {
                done.set(true);
            }
            assertThat(reader.get()).isGreaterThanOrEqualTo(0);
        }
        assertThat(file.read().orElseThrow().port()).isEqualTo(5050);
        try (Stream<Path> names = Files.list(folder)) {
            assertThat(names).containsExactly(folder.resolve("address"));
        }
    }
}
