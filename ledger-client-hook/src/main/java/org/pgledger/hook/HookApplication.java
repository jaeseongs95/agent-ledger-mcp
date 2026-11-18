// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import org.pgledger.hook.keys.KeyStoreProperties;
import org.pgledger.hook.keys.SessionKeyStore;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * The client hook as a Spring Boot application without a web server (user decision 2026-11-15): a hook is one short
 * process per host call, started by the host as {@code java -jar ledger-client-hook.jar <client>}. It imports only the
 * auto-configurations and beans the hook actually uses, scanning only {@code *Dialect.class} adapters so cold CLI
 * startup avoids full package and configuration-properties bytecode walks on every call.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@ComponentScan(
        basePackageClasses = HookApplication.class,
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = HostDialect.class),
        resourcePattern = "*Dialect.class")
@Import({
    HookRunner.class,
    HookCli.class,
    HookExitCode.class,
    HookClockConfiguration.class,
    HostDialects.class,
    JsonCodec.class,
    LedgerHook.class,
    ProcessChannels.class,
    SessionKeyStore.class
})
@ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, JacksonAutoConfiguration.class})
@EnableConfigurationProperties(KeyStoreProperties.class)
public class HookApplication {}
