// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import org.pgledger.hook.keys.KeyStoreProperties;
import org.pgledger.hook.keys.SessionKeyStore;
import org.pgledger.hook.resident.HookService;
import org.pgledger.hook.resident.ResidentRunner;
import org.pgledger.hook.resident.ResidentServer;
import org.pgledger.hook.resident.ResidentSettings;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * The client hook as a Spring Boot application without a web server (user decision 2026-11-15): the resident program of the
 * hook (user decision 2026-11-22). The host starts the light client ({@code java -jar ledger-client-hook.jar <client>}, a
 * program of java.base only), and the client asks this application, which keeps running between the calls and answers each
 * one with the same beans that answered the one-shot hook before. It imports only the auto-configurations and beans the hook
 * actually uses, scanning only {@code *Dialect.class} adapters so that the start of the program (once, not per call) avoids
 * full package and configuration-properties bytecode walks.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@ComponentScan(
        basePackageClasses = HookApplication.class,
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = HostDialect.class),
        resourcePattern = "*Dialect.class")
@Import({
    ResidentRunner.class,
    ResidentServer.class,
    HookService.class,
    HookCli.class,
    HookExitCode.class,
    HookClockConfiguration.class,
    HookMessagesConfiguration.class,
    HostDialects.class,
    JsonCodec.class,
    LedgerHook.class,
    SessionKeyStore.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@EnableConfigurationProperties({KeyStoreProperties.class, ResidentSettings.class})
public class HookApplication {}
