// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp.support;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.agentledger.domain.error.AdminCode;
import org.agentledger.domain.error.AdminException;
import org.agentledger.domain.error.ConflictCode;
import org.agentledger.domain.error.ConflictException;
import org.agentledger.domain.error.ErrorCode;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.InfrastructureException;
import org.agentledger.domain.error.LedgerException;
import org.agentledger.domain.error.LookupCode;
import org.agentledger.domain.error.LookupException;
import org.agentledger.domain.error.TransitionCode;
import org.agentledger.domain.error.TransitionException;
import org.agentledger.domain.error.TrustCode;
import org.agentledger.domain.error.TrustException;
import org.agentledger.domain.error.ValidationCode;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationIssue;
import org.agentledger.domain.error.ValidationReason;

/** The business failures of the domain, made for every error code: the exception of its category with its arguments. */
public final class Failures {

    /** More arguments than any message pattern has placeholders. */
    public static final List<String> ARGS = List.of("arg0", "arg1", "arg2", "arg3", "arg4");

    private Failures() {
    }

    /** Every constant of the seven code enums. */
    public static Stream<ErrorCode> allCodes() {
        return Stream.<ErrorCode[]>of(TrustCode.values(), ValidationCode.values(), TransitionCode.values(),
                ConflictCode.values(), LookupCode.values(), AdminCode.values(), InfrastructureCode.values())
                .flatMap(Stream::of);
    }

    /** The exception of the category of the code, with {@link #ARGS}. */
    public static LedgerException of(ErrorCode code) {
        return switch (code) {
            case TrustCode trust -> new TrustException(trust, ARGS, Map.of());
            case ValidationCode validation -> new ValidationException(validation, ARGS,
                    List.of(new ValidationIssue("some.path", ValidationReason.REQUIRED, List.of())), Map.of());
            case TransitionCode transition -> new TransitionException(transition, ARGS, Map.of());
            case ConflictCode conflict -> new ConflictException(conflict, ARGS);
            case LookupCode lookup -> new LookupException(lookup, ARGS);
            case AdminCode admin -> new AdminException(admin, ARGS);
            case InfrastructureCode infrastructure -> new InfrastructureException(infrastructure, ARGS);
        };
    }
}
