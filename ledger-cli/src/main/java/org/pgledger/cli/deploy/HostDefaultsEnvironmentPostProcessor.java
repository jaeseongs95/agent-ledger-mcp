// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.deploy;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Puts the defaults of the host ({@link HostDefaults}) into the environment of the tool, at the lowest precedence, before Spring
 * Boot reads the configuration (registered in {@code META-INF/spring.factories}). It runs before the post-processor of Boot that
 * loads {@code application.properties} and the files named by {@code spring.config.import}, because the import is written with the
 * settings directory of the host in it ({@code ledger.secrets.home} defaults to it) and is resolved while that post-processor runs.
 * Spring Boot's contract for changing the environment before the configuration data is read is {@link EnvironmentPostProcessor}
 * ordered before {@code ConfigDataEnvironmentPostProcessor}
 * (https://docs.spring.io/spring-boot/how-to/application.html#howto.application.customize-the-environment-or-application-context).
 *
 * <p>Another program that is built on this module (the migration tool) is served the same way: the source is added for every
 * program that has this class on its class path, and it only defines two properties that a program may or may not use.
 */
public class HostDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** The name of the property source: it is the one source of the defaults of the host. */
    static final String SOURCE_NAME = "ledgerHostDefaults";

    private final FileSystem fileSystem;

    /** The file system of this host (the one Boot instantiates this class with). */
    public HostDefaultsEnvironmentPostProcessor() {
        this(FileSystems.getDefault());
    }

    /** A test gives the file system of the kind of host it plays. */
    HostDefaultsEnvironmentPostProcessor(FileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    /** Before {@code ConfigDataEnvironmentPostProcessor} ({@code HIGHEST_PRECEDENCE + 10}). */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, HostDefaults.settingsPlaceholders(fileSystem)));
    }
}
