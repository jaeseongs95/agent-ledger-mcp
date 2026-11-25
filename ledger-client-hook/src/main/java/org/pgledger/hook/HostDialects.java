// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Finds the {@link HostDialect} bean of a {@link Client}: the one place that chooses an implementation, so that no code
 * of the hook branches on the name of a host. The beans are all the dialects of the context; every client has exactly one,
 * and the application does not start otherwise.
 */
@Component
final class HostDialects {

    private final Map<Client, HostDialect> byClient = new EnumMap<>(Client.class);

    HostDialects(List<HostDialect> dialects) {
        for (HostDialect dialect : dialects) {
            if (byClient.putIfAbsent(dialect.client(), dialect) != null) {
                throw new IllegalStateException("More than one HostDialect bean for " + dialect.client());
            }
        }
        for (Client client : Client.values()) {
            if (!byClient.containsKey(client)) {
                throw new IllegalStateException("No HostDialect bean for " + client);
            }
        }
    }

    /** The dialect of the host of this client. */
    HostDialect of(Client client) {
        return byClient.get(client);
    }
}
