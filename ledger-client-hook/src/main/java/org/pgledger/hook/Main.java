// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.beans.BeanUtils;
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
 * {@link SpringApplication} runs and {@code SpringApplicationAotProcessor} emits {@code HookApplication__ApplicationContextInitializer}.
 * In the packaged {@code bootJar}, the pre-generated AOT initializer populates a {@link GenericApplicationContext}
 * directly without runtime ASM bytecode scanning or auto-configuration condition probing.
 */
public final class Main {

    /**
     * The one place the hook reads {@code application.properties} from: the jar. A host starts the hook in the directory
     * of the project the user works on, which can be a clone of someone else's repository: a file in {@code ./} or
     * {@code ./config/} must never set the key folder, the account of the key owner or the output of the hook.
     */
    private static final String APPLICATION_PROPERTIES = "classpath:/application.properties";
    private static final String AOT_INITIALIZER = "org.pgledger.hook.Main__ApplicationContextInitializer";
    private static final List<String> UNUSED_INFRASTRUCTURE_BEANS = List.of(
            "org.springframework.context.event.internalEventListenerProcessor",
            "org.springframework.context.event.internalEventListenerFactory",
            "jsonComponentModule",
            "jacksonMixinModule",
            "jacksonMixinModuleEntries");

    private Main() {}

    public static void main(String[] args) throws Exception {
        StandardEnvironment environment = jarOnlyEnvironment();
        HookLoggingSystem.initialize(environment.getPropertySources());
        ClassLoader loader = Main.class.getClassLoader();
        if (ClassUtils.isPresent(AOT_INITIALIZER, loader)) {
            System.exit(runAheadOfTime(environment, loader, args));
        }
        System.exit(runDynamic(environment, args));
    }

    @SuppressWarnings("unchecked")
    private static int runAheadOfTime(StandardEnvironment environment, ClassLoader loader, String[] args)
            throws Exception {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);
        context.getBeanFactory().setBeanExpressionResolver(null);
        Class<?> type = ClassUtils.forName(AOT_INITIALIZER, loader);
        ApplicationContextInitializer<GenericApplicationContext> initializer =
                (ApplicationContextInitializer<GenericApplicationContext>) BeanUtils.instantiateClass(type);
        initializer.initialize(context);
        for (String beanName : UNUSED_INFRASTRUCTURE_BEANS) {
            if (context.containsBeanDefinition(beanName)) {
                context.removeBeanDefinition(beanName);
            }
        }
        context.refresh();
        context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments(args));
        return SpringApplication.exit(context);
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

    private static StandardEnvironment jarOnlyEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            environment.getPropertySources().addLast(new ResourcePropertySource(APPLICATION_PROPERTIES));
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read " + APPLICATION_PROPERTIES, failure);
        }
        return environment;
    }
}
