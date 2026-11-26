// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.domain.rule;

import org.veridger.domain.value.Token;

/** The extensible code lists. Only these four exist; other value sets are fixed enums. */
public enum CodeGroup implements Token {
    ISSUE_CATEGORY(false),
    ISSUE_SEVERITY(true),
    DELIVERABLE_KIND(true),
    RELATION_KIND(false);

    private final boolean alwaysActive;

    CodeGroup(boolean alwaysActive) {
        this.alwaysActive = alwaysActive;
    }

    @Override
    public String token() {
        return name();
    }

    /** True when every write of a row needs an active code even if keeping an existing value. */
    public boolean alwaysActive() {
        return alwaysActive;
    }
}
