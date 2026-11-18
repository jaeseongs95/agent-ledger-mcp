package org.pgledger.hook;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * The client hook as a Spring Boot application without a web server (user decision 2026-11-15): a hook is one short
 * process per host call, started by the host as {@code java -jar ledger-client-hook.jar <client>}. It imports only the
 * auto-configurations the hook actually uses so cold CLI startup does not scan the full Spring Boot catalog on every call.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@ComponentScan
@ImportAutoConfiguration({
        PropertyPlaceholderAutoConfiguration.class,
        JacksonAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class HookApplication {
}
