package org.pgledger.cli.legacy;

import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.database.LedgerRefusals.TableMismatch;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.LedgerException;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.TransitionException;
import org.pgledger.domain.error.TrustException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationIssue;
import org.springframework.stereotype.Component;

/**
 * What the owner needs to find the rows of a refused legacy comparison, printed before the refusal itself: the values that
 * cannot be imported (one per line, {@code table[key].column} and the reason) or the tables of a failed comparison. Both
 * commands that run the comparison ({@code import-legacy} and {@code retire-legacy}) print it the same way.
 */
@Component
class LegacyRefusalReport {

    private final CliOutput output;
    private final CliMessages messages;

    LegacyRefusalReport(CliOutput output, CliMessages messages) {
        this.output = output;
        this.messages = messages;
    }

    void print(LedgerException refused) {
        switch (refused) {
            case ValidationException invalid -> {
                for (ValidationIssue issue : invalid.issues()) {
                    output.info(messages.get("import-legacy.violation", issue.path(), reasonText(issue)));
                }
            }
            case InfrastructureException failed -> {
                if (failed.code() == InfrastructureCode.INTEGRITY_VIOLATION) {
                    for (TableMismatch mismatch : TableMismatch.of(failed.args())) {
                        output.info(messages.get("import-legacy.mismatch", mismatch.texts()));
                    }
                }
            }
            case ConflictException _, LookupException _, TrustException _, TransitionException _, AdminException _ -> {
                // nothing more to show than the refusal itself
            }
        }
    }

    /** The reason of the issue by name, with its arguments (a limit, a format): enough to find and correct the row. */
    private static String reasonText(ValidationIssue issue) {
        return issue.reasonArgs().isEmpty() ? issue.reason().name()
                : issue.reason().name() + " " + String.join(" ", issue.reasonArgs());
    }
}
