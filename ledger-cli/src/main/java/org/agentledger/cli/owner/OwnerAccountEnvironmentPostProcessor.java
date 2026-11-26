// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.owner;

import java.util.Set;
import org.agentledger.cli.CliCommand;
import org.agentledger.cli.LedgerCliApplication;
import org.agentledger.cli.secret.ConsoleSecretInput;
import org.agentledger.cli.secret.SecretInput;
import org.agentledger.cli.secret.StandardInput;
import org.agentledger.cli.secret.StdinSecretInput;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Puts {@link OwnerAccountPropertySource} in front of the other sources when the command line has
 * {@code --owner-account=<account>} (registered in {@code META-INF/spring.factories}; Boot calls it while it prepares the
 * environment, before any bean is made). Only the command line is read for the option: an environment variable of the
 * same name changes nothing. The option is declared by the commands that use it ({@code migrate}, {@code setup});
 * every other command refuses it as an unknown option.
 *
 * <p>Why an environment post-processor (principle 2.1): the DataSource is made from {@code ledger.jdbc.*} when the context
 * starts, before any command runs, so the account has to be in the environment by then; Spring Boot's contract for that is
 * {@link EnvironmentPostProcessor}
 * (https://docs.spring.io/spring-boot/how-to/application.html#howto.application.customize-the-environment-or-application-context).
 */
public class OwnerAccountEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** The option, as the command line property source names it. */
    public static final String OPTION = "owner-account";
    /** The flag that takes the password from standard input instead of the terminal. */
    public static final String STDIN = "stdin";

    /**
     * The commands that declare the option: the schema migration and the first setup (which migrates)
     * ({@code CliCommand.cliName}).
     */
    private static final Set<String> COMMANDS = Set.of(CliCommand.MIGRATE.cliName(), CliCommand.SETUP.cliName());

    /**
     * The words of the commands that declare the option. Another program that is built on this module (the migration tool) has a
     * post-processor of its own, a subclass that names its commands; the rest is this class.
     */
    protected Set<String> commands() {
        return COMMANDS;
    }

    /**
     * Whether this post-processor acts for the program that starts. It is registered in the {@code spring.factories} of this module, so
     * it is on the class path of every program built on the module and Boot calls it for each: it acts only for the command line tool
     * itself (the program the module is), and a program that has its own owner commands has a subclass that says so. Without an
     * application (a test that calls it directly) it acts.
     */
    protected boolean appliesTo(SpringApplication application) {
        return application == null || application.getAllSources().contains(LedgerCliApplication.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!appliesTo(application)) {
            return;
        }
        PropertySource<?> commandLine = environment.getPropertySources()
                .get(CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME);
        Object account = commandLine == null ? null : commandLine.getProperty(OPTION);
        Object operands = commandLine == null ? null
                : commandLine.getProperty(CommandLinePropertySource.DEFAULT_NON_OPTION_ARGS_PROPERTY_NAME);
        // an option without a value is refused by the command itself (MISSING_OPTION_VALUE); only the two commands that
        // declare the option ask for the password, any other command refuses the option without having prompted
        if (account instanceof String text && !text.isBlank() && operands instanceof String words
                && commands().contains(words.split(",")[0])) {
            // --stdin: the password is the next line of standard input (an installation without a terminal), else it is typed
            SecretInput input = commandLine.containsProperty(STDIN) ? new StdinSecretInput(StandardInput.ofProcess())
                    : new ConsoleSecretInput();
            environment.getPropertySources().addFirst(new OwnerAccountPropertySource(text, prompt(text), input));
        }
    }

    /** The prompt in the language of the machine; the bundle is the one of the commands (the context does not exist yet). */
    private static String prompt(String account) {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        return messages.getMessage("owner-account.prompt", new Object[] { account }, LocaleContextHolder.getLocale());
    }
}
