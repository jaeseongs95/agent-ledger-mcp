// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.database.repository.SchemaHistoryRepository.Outcome;
import org.pgledger.database.repository.SchemaHistoryRepository.Row;
import org.pgledger.database.schema.MigrationFile;
import org.pgledger.database.schema.SchemaHistory;
import org.pgledger.database.schema.SchemaHistory.Verdict;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.value.Sha256;

/**
 * SCH-030 (SchemaHistory): the one judgement of a schema file against {@code schema_history} that the migrator and the
 * verifier share. No database: the history is a value, so every state is a list of rows. A row is one attempt of a version
 * (the key is the version and the attempt, the common form of the three products).
 */
class SchemaHistoryTest {

    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
    private static final MigrationFile TWO = file(2, "two");
    private static final MigrationFile EDITED_TWO = file(2, "two -- edited");

    private static MigrationFile file(int version, String text) {
        return new MigrationFile(version, "V" + version + "__x.sql", text, Sha256.ofText(text));
    }

    private static Row row(MigrationFile file, int attempt, Outcome outcome) {
        return new Row(file.version(), attempt, file.fileName(), file.digest(), T0.plusSeconds(attempt), outcome);
    }

    /** A history, the file it is asked about, and what it must say. */
    private record Case(String name, List<Row> rows, MigrationFile file, Verdict verdict) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("never attempted", List.of(row(file(1, "one"), 1, Outcome.SUCCEEDED)), TWO, Verdict.PENDING),
                new Case("only failed attempts", List.of(row(TWO, 1, Outcome.FAILED), row(TWO, 2, Outcome.FAILED)), TWO,
                        Verdict.FAILED_ONLY),
                new Case("applied and intact", List.of(row(TWO, 1, Outcome.SUCCEEDED)), TWO, Verdict.INTACT),
                new Case("failed once, then applied", List.of(row(TWO, 1, Outcome.FAILED), row(TWO, 2, Outcome.SUCCEEDED)),
                        TWO, Verdict.INTACT),
                new Case("the applied file was edited", List.of(row(TWO, 1, Outcome.SUCCEEDED)), EDITED_TWO,
                        Verdict.DIGEST_CHANGED),
                new Case("failed with the old text, applied, then the file was edited",
                        List.of(row(file(2, "old"), 1, Outcome.FAILED), row(TWO, 2, Outcome.SUCCEEDED)), EDITED_TWO,
                        Verdict.DIGEST_CHANGED),
                new Case("failed with the old text, the file was edited since: it is tried again",
                        List.of(row(TWO, 1, Outcome.FAILED)), EDITED_TWO, Verdict.FAILED_ONLY),
                new Case("applied, then a later attempt failed",
                        List.of(row(TWO, 1, Outcome.SUCCEEDED), row(TWO, 2, Outcome.FAILED)), TWO, Verdict.LATEST_FAILED));
    }

    @ParameterizedTest
    @MethodSource("cases")
    @DisplayName("SCH-030 the verdict of a file follows from the attempts of its version")
    void theVerdict(Case c) {
        assertThat(new SchemaHistory(c.rows()).verdictOf(c.file())).isEqualTo(c.verdict());
    }

    @Test
    @DisplayName("SCH-030 only the attempts of the version of the file count")
    void otherVersionsAreNotLookedAt() {
        SchemaHistory history = new SchemaHistory(List.of(row(file(1, "one"), 1, Outcome.FAILED),
                row(TWO, 1, Outcome.SUCCEEDED)));
        assertThat(history.verdictOf(TWO)).isEqualTo(Verdict.INTACT);
        assertThat(history.verdictOf(file(1, "one"))).isEqualTo(Verdict.FAILED_ONLY);
        assertThat(history.attemptsOf(2)).hasSize(1);
    }

    @Test
    @DisplayName("RUN-015 the next attempt of a version is the highest attempt recorded plus one, counted from 1 for a version never attempted")
    void theNextAttempt() {
        SchemaHistory history = new SchemaHistory(List.of(row(file(1, "one"), 1, Outcome.SUCCEEDED),
                row(TWO, 1, Outcome.FAILED), row(TWO, 2, Outcome.FAILED), row(TWO, 3, Outcome.FAILED)));

        assertThat(history.nextAttemptOf(2)).isEqualTo(4);
        assertThat(history.nextAttemptOf(1)).isEqualTo(2);
        assertThat(history.nextAttemptOf(3)).isEqualTo(1);
        assertThat(new SchemaHistory(List.of()).nextAttemptOf(1)).isEqualTo(1);
    }

    @Test
    @DisplayName("RUN-012 a version counts as applied by a peer only when a SUCCEEDED attempt of it has the digest of the file")
    void appliedWithTheSameDigest() {
        SchemaHistory history = new SchemaHistory(List.of(row(TWO, 1, Outcome.FAILED), row(TWO, 2, Outcome.SUCCEEDED)));

        assertThat(history.isAppliedWithDigestOf(TWO)).isTrue();
        assertThat(history.isAppliedWithDigestOf(EDITED_TWO)).as("another digest").isFalse();
        assertThat(new SchemaHistory(List.of(row(TWO, 1, Outcome.FAILED))).isAppliedWithDigestOf(TWO)).as("only a failure")
                .isFalse();
        assertThat(new SchemaHistory(List.of()).isAppliedWithDigestOf(TWO)).isFalse();
    }

    @Test
    @DisplayName("SCH-030 a version of the history without a file is SCHEMA_APPLIED_FILE_MISSING; a later success with the version not applied is SCHEMA_OUT_OF_ORDER")
    void theRefusalsThatDoNotDependOnOneFile() {
        SchemaHistory history = new SchemaHistory(List.of(row(file(1, "one"), 1, Outcome.SUCCEEDED),
                row(file(3, "three"), 1, Outcome.SUCCEEDED)));

        InfrastructureException missing = catchThrowableOfType(InfrastructureException.class,
                () -> history.requireFileForEveryVersion(List.of(file(1, "one"))));
        assertThat(missing.code()).isEqualTo(InfrastructureCode.SCHEMA_APPLIED_FILE_MISSING);
        assertThat(missing.args()).containsExactly("3", "V3__x.sql");
        history.requireFileForEveryVersion(List.of(file(1, "one"), file(3, "three")));

        InfrastructureException outOfOrder = catchThrowableOfType(InfrastructureException.class,
                () -> history.requireNothingLaterApplied(TWO));
        assertThat(outOfOrder.code()).isEqualTo(InfrastructureCode.SCHEMA_OUT_OF_ORDER);
        assertThat(outOfOrder.args()).containsExactly("2", "V2__x.sql", "3");
        history.requireNothingLaterApplied(file(4, "four"));
    }

    @Test
    @DisplayName("RUN-013 a digest mismatch names both digests")
    void theMismatch() {
        SchemaHistory applied = new SchemaHistory(List.of(row(TWO, 1, Outcome.SUCCEEDED)));

        assertThat(applied.digestMismatch(EDITED_TWO).args()).containsExactly("2", "V2__x.sql", TWO.digest().hex(),
                EDITED_TWO.digest().hex());
        assertThat(applied.digestMismatch(EDITED_TWO).code()).isEqualTo(InfrastructureCode.SCHEMA_DIGEST_MISMATCH);
    }
}
