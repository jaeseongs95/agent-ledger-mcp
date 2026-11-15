package org.pgledger.hook;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The client hook as a Spring Boot application without a web server (user decision 2026-11-15): a hook is one short
 * process per host call, started by the host as {@code java -jar ledger-client-hook.jar <client>}. The settings are in
 * {@code application.properties} and the environment ({@link org.pgledger.hook.keys.KeyStoreProperties}); the answer to
 * the host is the only thing the process writes on its standard output (see {@link HookRunner}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class HookApplication {
}
