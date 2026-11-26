// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.agentledger.domain.error.AdminException;
import org.agentledger.domain.error.ArtifactRetry;
import org.agentledger.domain.error.ConflictException;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.InfrastructureException;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.domain.error.LookupException;
import org.agentledger.domain.error.TransitionException;
import org.agentledger.domain.error.TrustException;
import org.agentledger.domain.error.ValidationCode;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * The one place that turns a failure of a tool call into the error of the tool result: the business failures of
 * the sealed {@link LedgerException} hierarchy (a complete {@code switch}) and, for everything else, an
 * INTEGRITY_VIOLATION or INTERNAL_ERROR that carries no text of the original failure (no SQL, no class names).
 * The English message comes from the message bundles. A failure that is a defect or an outage is logged here,
 * with its cause, and nowhere else.
 */
@Component
public class ToolErrorMapper {

    private static final Logger LOG = LoggerFactory.getLogger(ToolErrorMapper.class);

    private final MessageSourceAccessor messages;

    public ToolErrorMapper(MessageSourceAccessor modelMessages) {
        this.messages = modelMessages;
    }

    public ToolError map(RuntimeException failure) {
        return switch (failure) {
            case LedgerException ledger -> ledger(ledger);
            case DataIntegrityViolationException integrity ->
                ledger(new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION, List.of(), integrity));
            default -> ledger(new InfrastructureException(InfrastructureCode.INTERNAL_ERROR, List.of(), failure));
        };
    }

    private ToolError ledger(LedgerException failure) {
        if (!(failure instanceof InfrastructureException)) {
            LOG.debug("a tool call was refused with {}", failure.code().token(), failure);
        }
        return switch (failure) {
            case ValidationException invalid -> validation(invalid);
            case TrustException trust -> plain(trust, trust.args());
            case TransitionException transition -> plain(transition, transition.args());
            case ConflictException conflict -> plain(conflict, conflict.args());
            case LookupException lookup -> plain(lookup, lookup.args());
            case AdminException admin -> plain(admin, admin.args());
            case InfrastructureException infrastructure -> infrastructure(infrastructure);
        };
    }

    private ToolError validation(ValidationException failure) {
        List<ToolError.Issue> issues = failure.issues().stream()
                .map(issue -> new ToolError.Issue(issue.path(), reason(issue), issue.allowed())).toList();
        List<String> args = failure.code() == ValidationCode.VALIDATION_FAILED
                ? List.of(failure.issues().getFirst().path(), reason(failure.issues().getFirst()))
                : failure.args();
        return plain(failure, args, issues);
    }

    private ToolError infrastructure(InfrastructureException failure) {
        LOG.error("a tool call failed with {}", failure.code().token(), failure);
        return plain(failure, failure.args());
    }

    private ToolError plain(LedgerException failure, List<String> args) {
        return plain(failure, args, List.of());
    }

    private ToolError plain(LedgerException failure, List<String> args, List<ToolError.Issue> issues) {
        MessageSourceResolvable resolvable = new DefaultMessageSourceResolvable(
                new String[] {failure.code().messageKey()}, args.toArray(), failure.code().token());
        // the upload tools tell the caller whether the same request sent later can succeed (details.retryable)
        Map<String, List<String>> details = ArtifactRetry.retryable(failure.code(), failure.details())
                .map(retry -> withRetryable(failure.details(), retry)).orElse(failure.details());
        return new ToolError(failure.code().token(), messages.getMessage(resolvable), details, issues);
    }

    private static Map<String, List<String>> withRetryable(Map<String, List<String>> details, boolean retry) {
        Map<String, List<String>> merged = new LinkedHashMap<>(details);
        merged.put(ArtifactRetry.KEY, List.of(Boolean.toString(retry)));
        return merged;
    }

    private String reason(ValidationIssue issue) {
        MessageSourceResolvable resolvable = new DefaultMessageSourceResolvable(
                new String[] {issue.reason().messageKey()}, issue.reasonArgs().toArray(), issue.reason().name());
        return messages.getMessage(resolvable);
    }
}
