package org.pgledger.hook;

import org.springframework.boot.context.config.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The one application context of the tests that need the beans of the hook (principle 3.9, tests): the stateless ones, such
 * as the {@link JsonCodec} with the JsonMapper that Boot configures from {@code application.properties}. It is made once
 * for the whole test run and closed with it. A test that needs another configuration makes its own small context with the
 * {@code ApplicationContextRunner}. The runner of the application ({@link HookRunner}) is a bean of it but is not started:
 * only {@code SpringApplication.run} starts runners, and the tests of the real jar do that in a process of their own.
 */
final class HookContext {

    private static final AnnotationConfigApplicationContext CONTEXT = start();

    private HookContext() {}

    /** The bean of this type from the shared context. */
    static <T> T bean(Class<T> type) {
        return CONTEXT.getBean(type);
    }

    private static AnnotationConfigApplicationContext start() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        new ConfigDataApplicationContextInitializer().initialize(context);
        context.register(HookApplication.class);
        context.refresh();
        context.registerShutdownHook();
        return context;
    }
}
