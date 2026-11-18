// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import java.util.List;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.support.EnvironmentPostProcessorApplicationListener;

/**
 * Entry point of {@code java -jar ledger-client-hook.jar <claude|codex|antigravity>} (the host hook) and
 * {@code java -jar ledger-client-hook.jar session-key <server> <author>} (the public key of an author's session key for
 * an MCP server): the one class that starts the Spring Boot application and hands its exit code to the process.
 *
 * <p>The work is done by {@link HookRunner}, the one {@code ApplicationRunner} of the application, which reports the exit
 * code through {@link HookExitCode}, an {@code ExitCodeGenerator}: {@link SpringApplication#exit} collects it after the
 * runner has finished, and the process ends with it. The words of the command line are the commands of the hook, never
 * settings, so Boot does not read them as properties ({@code --debug} or {@code --spring.main...} is a usage error like
 * any other unknown word).
 *
 * <p>The classes are Java 25 class files. An older JVM refuses the jar by itself with
 * {@code UnsupportedClassVersionError} (class file version 69.0 = Java 25), before any of this code runs.
 */
public final class Main {

    /**
     * The one place Boot reads {@code application.properties} from: the jar. Boot's default is also {@code ./} and
     * {@code ./config/} of the working directory, and a host starts the hook in the directory of the project the user works
     * on, which can be a clone of someone else's repository: a file there must never set the key folder, the account of the
     * key owner or the output of the hook. A default property is read before any configuration file is, and a file cannot
     * change where files are looked for.
     */
    private static final Map<String, Object> CONFIG_ONLY_FROM_THE_JAR = Map.of("spring.config.location",
            "classpath:/application.properties");

    private Main() {}

    public static void main(String[] args) {
        System.setProperty(
                "org.springframework.boot.logging.LoggingSystem",
                "org.springframework.boot.logging.logback.LogbackLoggingSystem");
        SpringApplication application = new SpringApplication(HookApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setAddCommandLineProperties(false);
        application.setRegisterShutdownHook(false);
        application.setInitializers(List.of());
        application.setListeners(application.getListeners().stream()
                .filter(listener -> listener instanceof EnvironmentPostProcessorApplicationListener
                        || listener instanceof LoggingApplicationListener)
                .toList());
        application.setDefaultProperties(CONFIG_ONLY_FROM_THE_JAR);
        System.exit(SpringApplication.exit(application.run(args)));
    }
}
