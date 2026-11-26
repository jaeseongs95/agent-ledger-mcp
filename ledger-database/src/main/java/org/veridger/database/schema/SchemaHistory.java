// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.schema;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.veridger.database.repository.SchemaHistoryRepository.Outcome;
import org.veridger.database.repository.SchemaHistoryRepository.Row;
import org.veridger.domain.error.InfrastructureCode;
import org.veridger.domain.error.InfrastructureException;

/**
 * What {@code schema_history} says, as a value: the rows of every attempt, by version and attempt, and the one judgement
 * of a schema file against them. The migrator (which applies what is pending) and the verifier (which only looks) both ask
 * this value, so they cannot judge the same history differently.
 *
 * <p>The judgement ({@link Verdict}) looks at the attempts of the version of the file: none at all; only failed ones; a succeeded
 * one applied another digest than the file has; the file is the one applied but the latest attempt failed; or the file is applied
 * and intact.
 */
public record SchemaHistory(List<Row> rows) {

    /** What the history says about one schema file. */
    public enum Verdict {
        /** The version was never attempted. */
        PENDING,
        /** The version was attempted but never succeeded (the next run retries it as the next attempt). */
        FAILED_ONLY,
        /** The version succeeded with another digest than the file has now: the applied file was edited. */
        DIGEST_CHANGED,
        /** The applied digest is the file's, but the latest recorded attempt of the version failed. */
        LATEST_FAILED,
        /** Applied with the digest of the file, and the latest attempt is that success. */
        INTACT
    }

    public SchemaHistory {
        rows = List.copyOf(rows);
    }

    /** The attempts of one version, by attempt number. */
    public List<Row> attemptsOf(int version) {
        return rows.stream().filter(row -> row.version() == version).sorted(Comparator.comparingInt(Row::attempt)).toList();
    }

    /** The number of the next attempt of a version: the highest recorded plus one, 1 for a version never attempted. */
    public int nextAttemptOf(int version) {
        return attemptsOf(version).stream().mapToInt(Row::attempt).max().orElse(0) + 1;
    }

    /**
     * A succeeded attempt of the version of the file has the digest of the file: the version is applied, by this runner or by
     * another one that applied it at the same time.
     */
    public boolean isAppliedWithDigestOf(MigrationFile file) {
        return attemptsOf(file.version()).stream()
                .anyMatch(row -> row.outcome() == Outcome.SUCCEEDED && Objects.equals(row.digest(), file.digest()));
    }

    /**
     * Every version in the history has a schema file.
     *
     * @throws InfrastructureException SCHEMA_APPLIED_FILE_MISSING
     */
    public void requireFileForEveryVersion(List<MigrationFile> files) {
        for (Row row : rows) {
            if (files.stream().noneMatch(file -> file.version() == row.version())) {
                throw new InfrastructureException(InfrastructureCode.SCHEMA_APPLIED_FILE_MISSING,
                        List.of(String.valueOf(row.version()), row.script()));
            }
        }
    }

    /**
     * No later version succeeded while this one is not applied: files must not be added out of order.
     *
     * @throws InfrastructureException SCHEMA_OUT_OF_ORDER
     */
    public void requireNothingLaterApplied(MigrationFile file) {
        for (Row row : rows) {
            if (row.version() > file.version() && row.outcome() == Outcome.SUCCEEDED) {
                throw new InfrastructureException(InfrastructureCode.SCHEMA_OUT_OF_ORDER,
                        List.of(String.valueOf(file.version()), file.fileName(), String.valueOf(row.version())));
            }
        }
    }

    /** The judgement of {@code file} against the attempts of its version. */
    public Verdict verdictOf(MigrationFile file) {
        List<Row> attempts = attemptsOf(file.version());
        if (attempts.isEmpty()) {
            return Verdict.PENDING;
        }
        return lastSuccess(attempts).map(applied -> {
            if (!Objects.equals(applied.digest(), file.digest())) {
                return Verdict.DIGEST_CHANGED;
            }
            return attempts.getLast().outcome() == Outcome.FAILED ? Verdict.LATEST_FAILED : Verdict.INTACT;
        }).orElse(Verdict.FAILED_ONLY);
    }

    /**
     * The failure for a file whose applied digest is not its digest. Nothing is recorded for it: a changed file is no attempt.
     *
     * @throws IllegalStateException when the version never succeeded (only {@link Verdict#DIGEST_CHANGED} has a digest)
     */
    public InfrastructureException digestMismatch(MigrationFile file) {
        Row applied = lastSuccess(attemptsOf(file.version()))
                .orElseThrow(() -> new IllegalStateException("V" + file.version() + " has no applied digest"));
        return new InfrastructureException(InfrastructureCode.SCHEMA_DIGEST_MISMATCH, List.of(
                String.valueOf(file.version()), file.fileName(), applied.digest().hex(), file.digest().hex()));
    }

    /** The failure for a version whose latest recorded attempt is FAILED. */
    public static InfrastructureException recordedFailed(MigrationFile file) {
        return new InfrastructureException(InfrastructureCode.SCHEMA_RECORDED_FAILED,
                List.of(String.valueOf(file.version()), file.fileName()));
    }

    /** The failure for a version that is not applied yet. */
    public static InfrastructureException pending(MigrationFile file) {
        return new InfrastructureException(InfrastructureCode.SCHEMA_PENDING,
                List.of(String.valueOf(file.version()), file.fileName()));
    }

    private static Optional<Row> lastSuccess(List<Row> attempts) {
        return attempts.reversed().stream().filter(row -> row.outcome() == Outcome.SUCCEEDED).findFirst();
    }
}
