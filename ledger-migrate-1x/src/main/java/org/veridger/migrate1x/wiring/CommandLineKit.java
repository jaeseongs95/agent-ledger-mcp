// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x.wiring;

import org.veridger.cli.CliClockConfiguration;
import org.veridger.cli.CliErrorTranslator;
import org.veridger.cli.CliExitCodeMapper;
import org.veridger.cli.CliMessages;
import org.veridger.cli.CliRunner;
import org.veridger.cli.StreamCliOutput;
import org.veridger.cli.database.CliActor;
import org.veridger.cli.secret.ConsoleSecretInput;
import org.veridger.cli.secret.SecretVault;
import org.veridger.cli.secret.SecretsProperties;
import org.veridger.cli.secret.StandardInput;
import org.veridger.cli.secret.SystemProcessUser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * What the tool takes from {@code ledger-cli} instead of copying it: the runner that dispatches a command, the report of a refusal
 * and its exit code, the texts, the output, the clock, the account of the process, the secret store with its input (the one store
 * the 2.0 services read, so the format cannot differ) and the settings that say where it is. Only these classes of
 * {@code org.veridger.cli} are imported; the commands of {@code ledger-cli} are not, and the failure analyzer of that module is
 * found through its {@code META-INF/spring.factories} and works with the translator imported here.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecretsProperties.class)
@Import({CliRunner.class, CliErrorTranslator.class, CliExitCodeMapper.class, CliMessages.class, StreamCliOutput.class,
        CliClockConfiguration.class, CliActor.class, SystemProcessUser.class, StandardInput.class, ConsoleSecretInput.class,
        SecretVault.class})
public class CommandLineKit {
}
