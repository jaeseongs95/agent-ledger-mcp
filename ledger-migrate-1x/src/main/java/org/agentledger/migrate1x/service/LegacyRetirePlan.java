// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.service;

import java.util.ArrayList;
import java.util.List;
import org.agentledger.domain.value.Sha256;
import org.agentledger.migrate1x.database.LegacyObject;
import org.agentledger.migrate1x.database.LegacyRoleSkip;

/**
 * What {@code retire-legacy} would remove, as the owner reads it and later confirms: the objects in the order of the drop and
 * the roles that stay with the reason. The digest is the SHA-256 of the lines (each line ends with a line feed, in UTF-8), so
 * the owner confirms exactly the list that was shown, and a change of the legacy database in between changes the digest.
 *
 * @param schema       the legacy schema
 * @param objects      the objects to drop, in the order of the drop
 * @param skippedRoles the roles of the old installer that stay
 */
public record LegacyRetirePlan(String schema, List<LegacyObject> objects, List<LegacyRoleSkip> skippedRoles) {

    public LegacyRetirePlan {
        objects = List.copyOf(objects);
        skippedRoles = List.copyOf(skippedRoles);
    }

    /** The lines of the list: the objects in the order of the drop, then the roles that stay. */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        objects.forEach(object -> lines.add(object.line()));
        skippedRoles.forEach(skipped -> lines.add(skipped.line()));
        return List.copyOf(lines);
    }

    public Sha256 digest() {
        return Sha256.ofText(String.join("\n", lines()) + "\n");
    }
}
