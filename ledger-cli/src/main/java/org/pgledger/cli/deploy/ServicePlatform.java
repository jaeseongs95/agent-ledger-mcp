package org.pgledger.cli.deploy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.pgledger.cli.deploy.DeployLayout.Platform;

/**
 * What differs between the operating system families an installation is made for: how its two services are defined, what
 * the layout of the instance is, what its paths look like in the files it renders, which options it cannot use, and what is done
 * to the files of the host before the secrets exist. One implementation per {@link Platform}, each a component, found by the enum in one place
 * ({@link ServicePlatforms}); a platform without its component stops the start of the tool. The commands and the renderer
 * ask the platform and branch on none.
 *
 * <p>The family is closed (Linux and Windows; macOS is no target), but it is the one axis along which the installer varies,
 * and every place that used to branch on it (the renderer, {@code prepare-host}, the validation of the
 * options) is now a method here. This interface holds what every platform does. What only some platforms do is a capability of
 * its own that only those platforms implement ({@link ServiceWrappers}), found with
 * {@link ServicePlatforms#capability}, so no platform has a method it cannot use.
 */
interface ServicePlatform extends HostPaths {

    Platform platform();

    /**
     * The layout of the instance on this platform.
     *
     * @param home the {@code --home} option, or null when it was not given
     * @throws org.pgledger.cli.UsageException MISSING_ARGUMENT when the platform needs a home and none was given,
     *         INVALID_DEPLOY_VALUE naming {@code home} when it is not a home of this platform
     */
    DeployLayout layout(String name, String home);

    /**
     * The options of the parameters that this platform cannot use, by name ({@link DeployOptions}): a path of the form of another
     * platform, a site that the platform has none of. The Jakarta constraints of {@link DeployParameters} hold what is the same on every
     * platform; the reader of the options joins both and names the first of them in the order of the options.
     */
    List<String> refusedOptions(DeployParameters parameters);

    /** The files that define the two services (the MCP server and the admin web) for the service manager of the platform. */
    List<RenderedFile> serviceDefinitions(DeployParameters parameters);

    /**
     * Prepares the files of the host ({@code prepare-host}): the folders with their owner and mode, or their ACLs, and the
     * copies of the jars, the driver and the wrappers. Everything is looked up that can refuse before the first change.
     *
     * @param root the directory to put everything below, or null for the real paths
     * @param wrappers what {@link ServiceWrappers#wrappers} found, none for a platform that has no wrapper
     */
    void prepare(DeployLayout layout, Path root, Path jars, Path driver, List<Path> wrappers) throws IOException;
}
