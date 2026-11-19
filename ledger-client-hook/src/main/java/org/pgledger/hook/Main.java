// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.util.ClassUtils;

/**
 * Entry point of {@code java -jar ledger-client-hook.jar <claude|codex|antigravity>} (the host hook) and
 * {@code java -jar ledger-client-hook.jar session-key <server> <author>} (the public key of an author's session key for
 * an MCP server): the one class that starts the Spring Boot application and hands its exit code to the process.
 *
 * <p>At build time {@code processAot} invokes {@link #main} without the generated initializer on the classpath, so
 * {@link SpringApplication} runs and {@code SpringApplicationAotProcessor} emits
 * {@code Main__ApplicationContextInitializer}. In the packaged {@code bootJar}, that pre-generated AOT initializer
 * populates a standard {@link GenericApplicationContext} with {@link DefaultApplicationArguments} and exits through
 * {@link SpringApplication#exit(org.springframework.context.ApplicationContext,
 * org.springframework.boot.ExitCodeGenerator...)}, keeping the AOT and dynamic bootstrap contracts identical.
 */
public final class Main {

    /**
     * The one place the hook reads {@code application.properties} from: the jar. A host starts the hook in the directory
     * of the project the user works on, which can be a clone of someone else's repository: a file in {@code ./} or
     * {@code ./config/} must never set the key folder, the account of the key owner or the output of the hook.
     */
    private static final String APPLICATION_PROPERTIES = "classpath:/application.properties";
    private static final String AOT_INITIALIZER = "org.pgledger.hook.Main__ApplicationContextInitializer";

    private Main() {}

    public static void main(String[] args) throws Exception {
        System.exit(execute(jarOnlyEnvironment(), Main.class.getClassLoader(), args));
    }

    static int execute(StandardEnvironment environment, ClassLoader loader, String[] args) throws Exception {
        HookLoggingSystem.initialize(environment.getPropertySources());
        if (ClassUtils.isPresent(AOT_INITIALIZER, loader)) {
            return runAheadOfTime(environment, loader, args);
        }
        return runDynamic(environment, args);
    }

    @SuppressWarnings("unchecked")
    private static int runAheadOfTime(StandardEnvironment environment, ClassLoader loader, String[] args)
            throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        try {
            context.setEnvironment(environment);
            Class<?> type = ClassUtils.forName(AOT_INITIALIZER, loader);
            ApplicationContextInitializer<GenericApplicationContext> initializer =
                    (ApplicationContextInitializer<GenericApplicationContext>) BeanUtils.instantiateClass(type);
            initializer.initialize(context);
            context.refresh();
            ApplicationArguments arguments = new DefaultApplicationArguments(args);
            for (ApplicationRunner runner : context.getBeansOfType(ApplicationRunner.class).values()) {
                runner.run(arguments);
            }
            return SpringApplication.exit(context);
        } finally {
            if (context.isActive()) {
                context.close();
            }
        }
    }

    private static int runDynamic(StandardEnvironment environment, String[] args) {
        SpringApplication application = new SpringApplication(HookApplication.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setAddCommandLineProperties(false);
        application.setAddConversionService(false);
        application.setRegisterShutdownHook(false);
        application.setInitializers(List.of());
        application.setListeners(List.of());
        ConfigurableApplicationContext context = application.run(args);
        return SpringApplication.exit(context);
    }

    static StandardEnvironment jarOnlyEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            environment.getPropertySources().addLast(new ResourcePropertySource(APPLICATION_PROPERTIES));
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read " + APPLICATION_PROPERTIES, failure);
        }
        return environment;
    }
}
