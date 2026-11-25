// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.migrate1x.database;

import java.util.List;

/**
 * What {@link LegacySchemaRepository#inventory} found in the legacy database.
 *
 * @param objects      what would be dropped, in the order of the drop ({@link LegacyObject.Kind}), the schema and the roles last
 * @param skippedRoles the roles of the 1.x installer that stay, with the reason
 * @param notOwned     the objects of the legacy schema that the running account does not own (neither it nor a role it has the
 *                     rights of), by name; the drop would be refused for them
 * @param sessions     the other sessions that use the legacy schema or run as a 1.x role, one text each ({@code pid <n> ...})
 */
public record LegacyInventory(List<LegacyObject> objects, List<LegacyRoleSkip> skippedRoles, List<String> notOwned,
        List<String> sessions) {

    public LegacyInventory {
        objects = List.copyOf(objects);
        skippedRoles = List.copyOf(skippedRoles);
        notOwned = List.copyOf(notOwned);
        sessions = List.copyOf(sessions);
    }
}
