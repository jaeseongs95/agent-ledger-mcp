// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.wiring;

import org.agentledger.cli.CliClockConfiguration;
import org.agentledger.cli.CliErrorTranslator;
import org.agentledger.cli.CliExitCodeMapper;
import org.agentledger.cli.CliMessages;
import org.agentledger.cli.CliRunner;
import org.agentledger.cli.StreamCliOutput;
import org.agentledger.cli.database.CliActor;
import org.agentledger.cli.secret.ConsoleSecretInput;
import org.agentledger.cli.secret.SecretVault;
import org.agentledger.cli.secret.SecretsProperties;
import org.agentledger.cli.secret.StandardInput;
import org.agentledger.cli.secret.SystemProcessUser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * What the tool takes from {@code ledger-cli} instead of copying it: the runner that dispatches a command, the report of a refusal
 * and its exit code, the texts, the output, the clock, the account of the process, the secret store with its input (the one store
 * the 2.0 services read, so the format cannot differ) and the settings that say where it is. Only these classes of
 * {@code org.agentledger.cli} are imported; the commands of {@code ledger-cli} are not, and the failure analyzer of that module is
 * found through its {@code META-INF/spring.factories} and works with the translator imported here.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecretsProperties.class)
@Import({CliRunner.class, CliErrorTranslator.class, CliExitCodeMapper.class, CliMessages.class, StreamCliOutput.class,
        CliClockConfiguration.class, CliActor.class, SystemProcessUser.class, StandardInput.class, ConsoleSecretInput.class,
        SecretVault.class})
public class CommandLineKit {
}
