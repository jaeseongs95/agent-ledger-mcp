package org.pgledger.migrate1x.wiring;

import org.pgledger.cli.CliClockConfiguration;
import org.pgledger.cli.CliErrorTranslator;
import org.pgledger.cli.CliExitCodeMapper;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliRunner;
import org.pgledger.cli.StreamCliOutput;
import org.pgledger.cli.database.CliActor;
import org.pgledger.cli.secret.ConsoleSecretInput;
import org.pgledger.cli.secret.SecretVault;
import org.pgledger.cli.secret.SecretsProperties;
import org.pgledger.cli.secret.StandardInput;
import org.pgledger.cli.secret.SystemProcessUser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * What the tool takes from {@code ledger-cli} instead of copying it: the runner that dispatches a command, the report of a refusal
 * and its exit code, the texts, the output, the clock, the account of the process, the secret store with its input (the one store
 * the 2.0 services read, so the format cannot differ) and the settings that say where it is. Only these classes of
 * {@code org.pgledger.cli} are imported; the commands of {@code ledger-cli} are not, and the failure analyzer of that module is
 * found through its {@code META-INF/spring.factories} and works with the translator imported here.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecretsProperties.class)
@Import({CliRunner.class, CliErrorTranslator.class, CliExitCodeMapper.class, CliMessages.class, StreamCliOutput.class,
        CliClockConfiguration.class, CliActor.class, SystemProcessUser.class, StandardInput.class, ConsoleSecretInput.class,
        SecretVault.class})
public class CommandLineKit {
}
