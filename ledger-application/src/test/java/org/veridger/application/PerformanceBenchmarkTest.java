// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.veridger.application.attachment.AttachmentStore;
import org.veridger.application.support.CountingFiles;
import org.veridger.application.support.Fields;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.Scenario;
import org.veridger.application.support.StoreFixture;
import org.veridger.application.support.Uploads;
import org.veridger.application.write.BatchOpInput;
import org.veridger.application.write.BatchResult;
import org.veridger.application.write.BatchService;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.attestation.ArgumentsDigest;
import org.veridger.attestation.JsonCanonicalizer;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;

/**
 * Verifies correctness and records empirical metrics for v4.2.0 application-layer performance improvements:
 * batched write throughput, single-pass O(S) attachment streaming, and zero-clone integer fast-path canonicalization.
 */
@LedgerServiceTest
class PerformanceBenchmarkTest {

    private static final Logger LOG = LoggerFactory.getLogger(PerformanceBenchmarkTest.class);

    @Test
    @DisplayName("PERF-APP-01: 50-op ledger_batch completes atomically with bulk ID/history/receipt flush and replays idempotently")
    void batch50OperationsAndIdempotentReplay(Scenario s) {
        int opsCount = 50;
        List<BatchOpInput> ops = new ArrayList<>(opsCount);
        for (int i = 0; i < opsCount; i++) {
            ops.add(new BatchOpInput.Save("issue", Fields.of(s.issueFields("perf-batch-issue-" + i)),
                    Scenario.REASON, null, null));
        }
        String opKey = Scenario.uuid();
        BatchService batchService = s.bean(BatchService.class);
        long beforeIssues = s.count("SELECT COUNT(*) FROM issue");
        long beforeHistory = s.count("SELECT COUNT(*) FROM history");

        long startNanos = System.nanoTime();
        BatchResult first = batchService.batch(Scenario.AUTHOR, opKey, ops);
        double elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0;

        assertThat(first.count()).isEqualTo(opsCount);
        assertThat(first.results()).hasSize(opsCount);
        assertThat(((SaveResult) first.results().getFirst()).id()).isEqualTo(s.issueId(1));
        assertThat(((SaveResult) first.results().getLast()).id()).isEqualTo(s.issueId(opsCount));
        assertThat(s.count("SELECT COUNT(*) FROM issue")).isEqualTo(beforeIssues + opsCount);
        assertThat(s.count("SELECT COUNT(*) FROM history")).isEqualTo(beforeHistory + opsCount);

        // Idempotent replay hits batch receipt immediately
        long replayStart = System.nanoTime();
        BatchResult replay = batchService.batch(Scenario.AUTHOR, opKey, ops);
        double replayMs = (System.nanoTime() - replayStart) / 1_000_000.0;

        assertThat(replay.count()).isEqualTo(opsCount);
        assertThat(((SaveResult) replay.results().getLast()).id()).isEqualTo(s.issueId(opsCount));
        assertThat(s.count("SELECT COUNT(*) FROM issue")).isEqualTo(beforeIssues + opsCount);
        LOG.info("PERF-APP-01 50-op batch: first={}ms replay={}ms",
                String.format(java.util.Locale.ROOT, "%.2f", elapsedMs),
                String.format(java.util.Locale.ROOT, "%.2f", replayMs));
    }

    @Test
    @DisplayName("PERF-APP-02: AttachmentStore.stream decrypts multi-segment files in a single linear O(S) pass and catches corruption")
    void attachmentStreamSinglePassMatchesBytesAndDetectsCorruption() throws IOException {
        int segments = 12;
        int segmentBytes = 100_000;
        StoreFixture fixture = StoreFixture.create();
        try {
            byte[][] chunks = new byte[segments][];
            for (int i = 0; i < segments; i++) {
                chunks[i] = Uploads.body(segmentBytes, 4200L + i);
            }
            StoreFixture.Written written = fixture.write(fixture.keyId(), chunks);
            long onDiskBytes = Files.size(fixture.fileOf(written.key()));

            CountingFiles counting = new CountingFiles();
            AttachmentStore store = new AttachmentStore(fixture.properties(), counting, fixture.keys(), fixture.cipher());

            ByteArrayOutputStream out = new ByteArrayOutputStream((int) (segments * (long) segmentBytes));
            long startNanos = System.nanoTime();
            long streamed = store.stream(written.key(), written.keyId(), out);
            double streamMs = (System.nanoTime() - startNanos) / 1_000_000.0;

            assertThat(streamed).isEqualTo((long) segments * segmentBytes);
            assertThat(out.toByteArray()).isEqualTo(written.plain());
            // Single linear pass: total bytes read equals the on-disk file size (no quadratic re-scan)
            assertThat(counting.bytesRead()).isEqualTo(onDiskBytes);
            LOG.info("PERF-APP-02 stream 1.2MB (12 segments): {}ms bytesRead={}/{}",
                    String.format(java.util.Locale.ROOT, "%.2f", streamMs), counting.bytesRead(), onDiskBytes);

            // Corrupt trailing bytes -> must throw ARTIFACT_CORRUPTED
            Path file = fixture.fileOf(written.key());
            byte[] raw = Files.readAllBytes(file);
            assertThat(file.toFile().setWritable(true)).isTrue();
            Files.write(file, Arrays.copyOf(raw, raw.length - 4));

            assertThatThrownBy(() -> store.stream(written.key(), written.keyId(), new ByteArrayOutputStream()))
                    .isInstanceOfSatisfying(InfrastructureException.class,
                            ex -> assertThat(ex.code()).isEqualTo(InfrastructureCode.ARTIFACT_CORRUPTED));
        } finally {
            fixture.close();
        }
    }

    @Test
    @DisplayName("PERF-APP-03: ArgumentsDigest zero-clone canonicalization and integer fast-path preserve exact canonical output")
    void argumentsDigestFastPathMatchesCanonicalization() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", 42);
        nested.put("a", -0.0d);
        nested.put("b", 1.0d);
        nested.put("bigSafe", BigInteger.valueOf(9_007_199_254_740_991L));
        nested.put("safe", 9_007_199_254_740_991L);
        nested.put("list", List.of(1, 2L, -3, "txt", Map.of("k2", true, "k1", false)));

        Map<String, Object> argsWithReserved = new LinkedHashMap<>();
        argsWithReserved.put("_hostAttestation", "codex:ignored-in-digest");
        argsWithReserved.put("operationKey", "op-1234567890123456");
        argsWithReserved.put("payload", nested);

        Map<String, Object> argsWithoutReserved = new LinkedHashMap<>(argsWithReserved);
        argsWithoutReserved.remove("_hostAttestation");

        assertThat(ArgumentsDigest.of(argsWithReserved)).isEqualTo(ArgumentsDigest.of(argsWithoutReserved));
        assertThat(JsonCanonicalizer.canonicalize(Map.of("a", -0.0d, "b", 1.0d, "c", 0L)))
                .isEqualTo("{\"a\":0,\"b\":1,\"c\":0}");
        assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(BigInteger.ONE.shiftLeft(60)))
                .isInstanceOf(IllegalArgumentException.class);

        long startNanos = System.nanoTime();
        String last = "";
        for (int i = 0; i < 5_000; i++) {
            last = ArgumentsDigest.of(argsWithReserved);
        }
        double elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0;
        assertThat(last).hasSize(64);
        LOG.info("PERF-APP-03 5000x ArgumentsDigest.of: {}ms",
                String.format(java.util.Locale.ROOT, "%.2f", elapsedMs));
    }
}
