// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.pgledger.hook.keys.KeyStoreProperties;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.SimpleAutowireCandidateResolver;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.jackson.autoconfigure.JacksonProperties;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.OrderComparator;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.env.MutablePropertySources;
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
    private static final String KEY_STORE_PROPERTIES_BEAN =
            "ledger.hook-org.pgledger.hook.keys.KeyStoreProperties";
    private static final String JACKSON_PROPERTIES_BEAN =
            "spring.jackson-org.springframework.boot.jackson.autoconfigure.JacksonProperties";
    private static final List<String> UNUSED_INFRASTRUCTURE_BEANS = List.of(
            "org.springframework.context.event.internalEventListenerProcessor",
            "org.springframework.context.event.internalEventListenerFactory",
            "org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor",
            "org.springframework.boot.context.internalConfigurationPropertiesBinder",
            "org.springframework.boot.context.properties.BoundConfigurationProperties",
            "org.springframework.boot.context.properties.EnableConfigurationPropertiesRegistrar.methodValidationExcludeFilter",
            "org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration$JacksonMixinConfiguration",
            "jsonComponentModule",
            "jacksonMixinModule",
            "jacksonMixinModuleEntries",
            KEY_STORE_PROPERTIES_BEAN,
            JACKSON_PROPERTIES_BEAN);

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
        GenericApplicationContext context = new AotHookApplicationContext();
        context.setEnvironment(environment);
        Class<?> type = ClassUtils.forName(AOT_INITIALIZER, loader);
        ApplicationContextInitializer<GenericApplicationContext> initializer =
                (ApplicationContextInitializer<GenericApplicationContext>) BeanUtils.instantiateClass(type);
        initializer.initialize(context);
        context.getDefaultListableBeanFactory().setAutowireCandidateResolver(new SimpleAutowireCandidateResolver());
        context.getDefaultListableBeanFactory().setDependencyComparator(OrderComparator.INSTANCE);
        for (String beanName : UNUSED_INFRASTRUCTURE_BEANS) {
            if (context.containsBeanDefinition(beanName)) {
                context.removeBeanDefinition(beanName);
            }
        }
        Binder binder = propertyBinder(environment.getPropertySources());
        context.getBeanFactory()
                .registerSingleton(
                        KEY_STORE_PROPERTIES_BEAN, binder.bindOrCreate("ledger.hook", KeyStoreProperties.class));
        context.getBeanFactory()
                .registerSingleton(
                        JACKSON_PROPERTIES_BEAN, binder.bindOrCreate("spring.jackson", JacksonProperties.class));
        context.refresh();
        context.getBean(ApplicationRunner.class).run(new RawApplicationArguments(args));
        return context.getBean(HookExitCode.class).getExitCode();
    }

    private static Binder propertyBinder(MutablePropertySources propertySources) {
        GenericConversionService conversionService = new GenericConversionService();
        conversionService.addConverter(String.class, Boolean.class, value -> Boolean.valueOf(value.strip()));
        conversionService.addConverter(String.class, Integer.class, value -> Integer.valueOf(value.strip()));
        conversionService.addConverterFactory(new LenientStringToEnum());
        return new Binder(
                ConfigurationPropertySources.from(propertySources),
                new PropertySourcesPlaceholdersResolver(propertySources),
                conversionService);
    }

    private static final class AotHookApplicationContext extends GenericApplicationContext {

        @Override
        protected void prepareBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            beanFactory.setBeanClassLoader(getClassLoader());
            beanFactory.setBeanExpressionResolver(null);
            beanFactory.registerResolvableDependency(BeanFactory.class, beanFactory);
        }

        @Override
        protected void initMessageSource() {}

        @Override
        protected void initApplicationEventMulticaster() {}

        @Override
        protected void registerListeners() {}

        @Override
        protected void finishRefresh() {}

        @Override
        protected void publishEvent(Object event, ResolvableType eventType) {}
    }

    private static final class LenientStringToEnum implements ConverterFactory<String, Enum<?>> {

        @Override
        public <T extends Enum<?>> Converter<String, T> getConverter(Class<T> targetType) {
            return source -> {
                String canonical = canonicalEnumName(source);
                for (T constant : targetType.getEnumConstants()) {
                    if (Objects.equals(canonicalEnumName(constant.name()), canonical)) {
                        return constant;
                    }
                }
                throw new IllegalArgumentException("No enum constant " + targetType.getName() + "." + source);
            };
        }

        private static String canonicalEnumName(String value) {
            StringBuilder normalized = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (Character.isLetterOrDigit(ch)) {
                    normalized.append(Character.toLowerCase(ch));
                }
            }
            return normalized.toString();
        }
    }

    private static final class RawApplicationArguments implements ApplicationArguments {

        private final String[] sourceArgs;

        RawApplicationArguments(String[] sourceArgs) {
            this.sourceArgs = sourceArgs.clone();
        }

        @Override
        public String[] getSourceArgs() {
            return sourceArgs.clone();
        }

        @Override
        public Set<String> getOptionNames() {
            return Set.of();
        }

        @Override
        public boolean containsOption(String name) {
            return false;
        }

        @Override
        public List<String> getOptionValues(String name) {
            return null;
        }

        @Override
        public List<String> getNonOptionArgs() {
            return List.of(sourceArgs);
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
