// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp.support;

import org.agentledger.application.admin.CommonCodeService.CodeCommand;

/** The common-code changes the catalog cases make, as the admin web or the command line would make them. */
public final class CodeChanges {

    public static final String CATEGORY = "ISSUE_CATEGORY";
    public static final String SEVERITY = "ISSUE_SEVERITY";
    public static final String RELATION = "RELATION_KIND";
    public static final String DELIVERABLE = "DELIVERABLE_KIND";

    private CodeChanges() {
    }

    /** A code of a group with Korean and English texts derived from the code. */
    public static CodeCommand of(String group, String code, String state) {
        return new CodeCommand(group, code, "name " + code, "Name " + code, "description " + code,
                "Description " + code, 5, state);
    }

    public static CodeCommand active(String group, String code) {
        return of(group, code, "ACTIVE");
    }

    public static CodeCommand inactive(String group, String code) {
        return of(group, code, "INACTIVE");
    }

    /** The same code with texts of the given values (the sort order, the group and the state stay). */
    public static CodeCommand withTexts(String group, String code, String nameKo, String nameEn,
            String descriptionKo, String descriptionEn) {
        return new CodeCommand(group, code, nameKo, nameEn, descriptionKo, descriptionEn, 5, "ACTIVE");
    }
}
