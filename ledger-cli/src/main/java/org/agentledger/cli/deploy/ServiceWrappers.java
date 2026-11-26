// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.deploy;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.ApplicationArguments;

/**
 * The capability of a platform whose services are run by a wrapper program that the installer copies (Windows: the executable of
 * WinSW). A platform whose service manager needs none (systemd) does not implement it, and {@code prepare-host} refuses the option
 * that names a wrapper for it.
 */
interface ServiceWrappers {

    /**
     * The files {@code prepare-host} copies besides the jars and the driver (the executable of the service wrapper), read from
     * its options.
     *
     * @throws org.agentledger.cli.UsageException a required option that is absent
     */
    List<Path> wrappers(ApplicationArguments arguments);
}
