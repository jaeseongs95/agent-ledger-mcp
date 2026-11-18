// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.passkey;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.pgledger.admin.app.LedgerAdminProperties;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The relying party of the passkeys of the admin web: the host name a passkey is bound to ({@code rpId}) and the origins the
 * browser may show (scheme, host and port as the address bar writes them). Both come from the settings
 * ({@code ledger.admin.passkey.*}); without them they are the one public host of the admin web (the one host of
 * {@code ledger.admin.allowed-hosts} that is not a loopback name, with {@code https}), or {@code localhost} with
 * {@code http://localhost:<server.port>}. Several public hosts are ambiguous and are refused with the name of the setting. A
 * passkey needs HTTPS: only a loopback origin may be plain HTTP. A wrong host name or origin at the time of a ceremony is the
 * business failure {@code PASSKEY_HOST_MISMATCH} (see {@link AssertionFailures}).
 */
@Component
public final class PasskeyRelyingParty {

    private static final String LOOPBACK = "localhost";
    private static final Set<String> LOOPBACK_NAMES = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final int DEFAULT_PORT = 8865;

    private final String rpId;
    private final String rpName;
    private final Set<String> origins;

    public PasskeyRelyingParty(LedgerAdminProperties properties, ServerProperties server) {
        LedgerAdminProperties.Passkey passkey = properties.passkey();
        this.rpName = passkey.rpName();
        this.rpId = rpId(passkey, properties.allowedHosts());
        this.origins = origins(passkey, rpId, server.getPort() == null ? DEFAULT_PORT : server.getPort());
    }

    /** The relying party id. */
    public String rpId() {
        return rpId;
    }

    /** The name an authenticator shows. */
    public String rpName() {
        return rpName;
    }

    /** The origins the browser may show. */
    public Set<String> origins() {
        return origins;
    }

    private static String rpId(LedgerAdminProperties.Passkey passkey, List<String> allowedHosts) {
        if (StringUtils.hasText(passkey.rpId())) {
            String id = passkey.rpId().strip().toLowerCase(Locale.ROOT);
            if (id.contains(":") || id.chars().allMatch(c -> Character.isDigit(c) || c == '.')) {
                throw new IllegalArgumentException("ledger.admin.passkey.rp-id must be a host name, never an address");
            }
            return id;
        }
        List<String> publicHosts = allowedHosts.stream().map(host -> host.strip().toLowerCase(Locale.ROOT))
                .filter(host -> !LOOPBACK_NAMES.contains(host)).distinct().toList();
        if (publicHosts.size() > 1) {
            throw new IllegalArgumentException("ledger.admin.passkey.rp-id must be set: ledger.admin.allowed-hosts names "
                    + "several public hosts and a passkey belongs to one");
        }
        return publicHosts.isEmpty() ? LOOPBACK : publicHosts.getFirst();
    }

    private static Set<String> origins(LedgerAdminProperties.Passkey passkey, String rpId, int port) {
        Set<String> origins = new LinkedHashSet<>();
        if (passkey.origins().isEmpty()) {
            origins.add(Objects.equals(LOOPBACK, rpId) ? "http://" + LOOPBACK + ":" + port : "https://" + rpId);
        } else {
            origins.addAll(passkey.origins().stream().map(String::strip).toList());
        }
        origins.forEach(origin -> check(origin, rpId));
        return Set.copyOf(origins);
    }

    /** HTTPS except loopback, and a host that is the relying party id or below it. */
    private static void check(String origin, String rpId) {
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException notAnOrigin) {
            throw new IllegalArgumentException("ledger.admin.passkey.origins: " + origin + " is not an origin", notAnOrigin);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean secure = "https".equals(uri.getScheme());
        boolean plainLoopback = "http".equals(uri.getScheme()) && Objects.equals(LOOPBACK, host);
        if (!secure && !plainLoopback) {
            throw new IllegalArgumentException("ledger.admin.passkey.origins: " + origin
                    + " must use https (only localhost may use http)");
        }
        if (uri.getPath() != null && !uri.getPath().isEmpty() || uri.getQuery() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("ledger.admin.passkey.origins: " + origin
                    + " must be scheme, host and port only");
        }
        if (!Objects.equals(host, rpId) && !host.endsWith("." + rpId)) {
            throw new IllegalArgumentException("ledger.admin.passkey.origins: the host of " + origin
                    + " is not ledger.admin.passkey.rp-id or a name below it");
        }
    }
}
