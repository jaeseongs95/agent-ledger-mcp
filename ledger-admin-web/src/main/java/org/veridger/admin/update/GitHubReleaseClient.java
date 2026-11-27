// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.veridger.admin.app.LedgerAdminProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * GitHub Releases client backed by Spring's {@link RestClient}. Every download hop validates scheme, userinfo absence,
 * port, and host allowlist before following redirects so a compromised or misconfigured redirect can never perform SSRF.
 */
@Component
public final class GitHubReleaseClient implements ReleaseClient {

    private static final int MAX_REDIRECTS = 3;
    private static final int STREAM_BUFFER = 16_384;
    private static final int MAX_RELEASE_JSON_BYTES = 4 * 1024 * 1024;
    private static final Pattern SCHEMA_MARKER = Pattern.compile("(?i)schema[- ]version\\s*[:=]\\s*V?(\\d+)");

    /** Typed projection of the GitHub Releases API response (no JSON tree model). */
    record GitHubReleaseDto(String tag_name, String html_url, String body, boolean draft, boolean prerelease,
            List<GitHubAssetDto> assets) {
    }

    /** Typed projection of one GitHub release asset entry. */
    record GitHubAssetDto(String name, String browser_download_url, long size) {
    }

    private final RestClient client;
    private final JsonMapper mapper;
    private final URI latestUri;
    private final boolean requireHttps;
    private final Set<String> allowedHosts;
    private final int currentSchemaVersion;

    public GitHubReleaseClient(LedgerAdminProperties properties,
            @Qualifier("updateRestClient") RestClient client, JsonMapper mapper) {
        this.client = client;
        this.mapper = mapper;
        LedgerAdminProperties.Update cfg = properties.update();
        String base = cfg.apiBaseUrl().endsWith("/")
                ? cfg.apiBaseUrl().substring(0, cfg.apiBaseUrl().length() - 1)
                : cfg.apiBaseUrl();
        this.latestUri = URI.create(base + "/repos/" + cfg.repository() + "/releases/latest");
        this.requireHttps = "https".equalsIgnoreCase(this.latestUri.getScheme());
        Set<String> hosts = new HashSet<>(cfg.allowedDownloadHosts());
        String apiHost = this.latestUri.getHost();
        if (apiHost != null) {
            hosts.add(apiHost.toLowerCase(Locale.ROOT));
        }
        this.allowedHosts = Set.copyOf(hosts);
        this.currentSchemaVersion = cfg.currentSchemaVersion();
    }

    @Override
    public ReleaseInfo fetchLatest() throws IOException {
        GitHubReleaseDto dto;
        try {
            dto = client.get().uri(latestUri)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .header(HttpHeaders.USER_AGENT, "Veridger-Admin-Updater")
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (!status.is2xxSuccessful()) {
                            throw new IOException("release metadata request failed with HTTP " + status.value());
                        }
                        long contentLength = response.getHeaders().getContentLength();
                        if (contentLength > MAX_RELEASE_JSON_BYTES) {
                            throw new IOException("release metadata exceeds maximum allowed size");
                        }
                        byte[] raw = response.getBody().readNBytes(MAX_RELEASE_JSON_BYTES + 1);
                        if (raw.length > MAX_RELEASE_JSON_BYTES) {
                            throw new IOException("release metadata exceeded maximum allowed size while streaming");
                        }
                        try {
                            return mapper.readValue(raw, GitHubReleaseDto.class);
                        } catch (JacksonException malformed) {
                            throw new IOException("failed to parse latest release metadata", malformed);
                        }
                    });
        } catch (RestClientException e) {
            throw new IOException("failed to fetch latest release metadata", e);
        }
        if (dto == null || dto.draft() || dto.prerelease()) {
            throw new IOException("latest release response is empty or not a stable release");
        }
        SemVer version = SemVer.parse(dto.tag_name())
                .orElseThrow(() -> new IOException("latest release tag is not a valid SemVer"));
        List<ReleaseAsset> assets = new ArrayList<>();
        if (dto.assets() != null) {
            for (GitHubAssetDto item : dto.assets()) {
                if (item != null && item.name() != null && item.browser_download_url() != null) {
                    URI assetUri;
                    try {
                        assetUri = URI.create(item.browser_download_url().trim());
                    } catch (IllegalArgumentException invalid) {
                        throw new IOException("release asset has an invalid download URL", invalid);
                    }
                    assets.add(new ReleaseAsset(item.name(), assetUri, item.size()));
                }
            }
        }
        String htmlUrl = sanitizeHtmlUrl(dto.html_url());
        int requiredSchema = parseRequiredSchema(dto.body(), currentSchemaVersion);
        return new ReleaseInfo(dto.tag_name(), version, htmlUrl, assets, requiredSchema);
    }

    @Override
    public byte[] downloadBytes(URI url, long maxBytes) throws IOException {
        Path temp = Files.createTempFile("veridger-update-dl-", ".tmp");
        try {
            downloadToFile(url, temp, maxBytes);
            return Files.readAllBytes(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public void downloadToFile(URI url, Path target, long maxBytes) throws IOException {
        URI current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            validateDownloadUri(current);
            URI next = fetchHop(current, target, maxBytes);
            if (next == null) {
                return;
            }
            current = current.resolve(next);
        }
        throw new IOException("too many redirects while downloading release asset");
    }

    /** Executes one HTTP GET hop; returns the redirect target URI if 3xx, or null when written to {@code target}. */
    private URI fetchHop(URI uri, Path target, long maxBytes) throws IOException {
        try {
            return client.get().uri(uri)
                    .header(HttpHeaders.USER_AGENT, "Veridger-Admin-Updater")
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is3xxRedirection()) {
                            URI location = response.getHeaders().getLocation();
                            if (location == null) {
                                throw new IOException("redirect response missing Location header");
                            }
                            return location;
                        }
                        if (!status.is2xxSuccessful()) {
                            throw new IOException("download failed with HTTP " + status.value());
                        }
                        long contentLength = response.getHeaders().getContentLength();
                        if (contentLength > maxBytes) {
                            throw new IOException("release asset exceeds maximum allowed size");
                        }
                        copyBounded(response.getBody(), target, maxBytes);
                        return null;
                    });
        } catch (RestClientException e) {
            throw new IOException("failed to download release asset", e);
        }
    }

    private void validateDownloadUri(URI uri) throws IOException {
        if (uri == null || !uri.isAbsolute()) {
            throw new IOException("download URL must be absolute");
        }
        String scheme = uri.getScheme();
        boolean schemeOk = requireHttps
                ? "https".equalsIgnoreCase(scheme)
                : ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
        if (!schemeOk) {
            throw new IOException("download URL scheme is not allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IOException("download URL must not contain user information");
        }
        int port = uri.getPort();
        if (requireHttps && port != -1 && port != 443) {
            throw new IOException("download URL port is not allowed");
        }
        String host = uri.getHost();
        if (host == null || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new IOException("download host is not in the configured allowlist");
        }
    }

    private static void copyBounded(InputStream in, Path target, long maxBytes) throws IOException {
        long total = 0L;
        byte[] buffer = new byte[STREAM_BUFFER];
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("release asset exceeded maximum allowed size while streaming");
                }
                out.write(buffer, 0, read);
            }
        }
    }

    private static String sanitizeHtmlUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            URI parsed = URI.create(raw.trim());
            String scheme = parsed.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && parsed.getRawUserInfo() == null ? parsed.toString() : "";
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    static int parseRequiredSchema(String body, int fallback) {
        if (body == null || body.isBlank()) {
            return fallback;
        }
        Matcher matcher = SCHEMA_MARKER.matcher(body);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException overflow) {
            // Fail closed on absurdly large schema numbers so one-click update is blocked.
            return Integer.MAX_VALUE;
        }
    }
}
