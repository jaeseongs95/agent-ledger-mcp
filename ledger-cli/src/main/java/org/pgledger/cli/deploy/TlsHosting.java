package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The capability of a platform that has the nginx site and so the automatic certificate ({@code --tls-acme}): the directories
 * the certificate needs. A platform without the site (Windows) does not implement it, and {@code prepare-host} refuses
 * {@code --tls-acme} for it.
 */
interface TlsHosting {

    /**
     * The directories of the automatic certificate: the TLS directory (the keys; the service account's alone) and the web
     * root of the HTTP-01 proof (the service account writes it, the web server reads it).
     *
     * @param root the directory to put everything below, or null for the real paths
     */
    void prepareTls(DeployLayout layout, Path root) throws IOException;
}
