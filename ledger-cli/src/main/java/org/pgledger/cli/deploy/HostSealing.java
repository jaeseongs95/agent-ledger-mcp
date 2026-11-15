package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The capability of a platform whose installation has a last step after the secrets are stored and the services registered
 * ({@code seal-host}: Windows hands the secrets to the service account). A platform whose secrets are the service user's from the
 * start (Linux) does not implement it, and {@code seal-host} refuses it.
 */
interface HostSealing {

    /**
     * The last step of an installation.
     *
     * @param root the directory everything is below, or null for the real paths
     */
    void seal(DeployLayout layout, Path root) throws IOException;
}
