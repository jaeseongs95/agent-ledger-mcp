// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.domain.rule;

import java.util.Arrays;
import java.util.Objects;
import org.agentledger.domain.value.Token;

/**
 * The structural kinds of link. Any other kind token names a relation between two issues; the allowed relation
 * kinds are data (common codes of group RELATION_KIND).
 */
public enum LinkKind implements Token {
    ISSUE_TASK("issue_task"),
    ISSUE_SPEC("issue_spec"),
    SPEC_TASK("spec_task"),
    CRITERION_TASK("criterion_task"),
    CRITERION_ISSUE("criterion_issue"),
    CRITERION_SPEC("criterion_spec"),
    RELATION("relation");

    private final String token;

    LinkKind(String token) {
        this.token = token;
    }

    @Override
    public String token() {
        return token;
    }

    /** The structural kind of a wire token; every non-structural token is treated as a RELATION_KIND code. */
    public static LinkKind ofToken(String wireToken) {
        return Arrays.stream(values())
                .filter(kind -> kind != RELATION && Objects.equals(kind.token, wireToken))
                .findFirst()
                .orElse(RELATION);
    }
}
