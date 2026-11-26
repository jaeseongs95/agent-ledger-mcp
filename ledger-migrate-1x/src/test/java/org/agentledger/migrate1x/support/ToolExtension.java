// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.agentledger.cli.CliException;
import org.agentledger.cli.CliOutput;
import org.agentledger.cli.ErrorKey;
import org.agentledger.cli.secret.ProcessUser;
import org.agentledger.cli.secret.SecretInput;
import org.agentledger.cli.secret.StandardInput;
import org.agentledger.migrate1x.Migrate1xApplication;
import org.agentledger.migrate1x.database.LegacySourceConfiguration;
import org.agentledger.migrate1x.database.LegacySourceWiring;
import org.agentledger.migrate1x.host.ServiceControl;
import org.agentledger.testing.database.TestDatabaseScanFilter;
import org.agentledger.testing.database.TestDriverRegistration;
import org.springframework.boot.ExitCodeEvent;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * JUnit extension that hands a test a {@link Tool} to start the real application the way {@code main} does. Only the input and
 * output boundaries (and the service manager) can be swapped; they are the beans of {@code ledger-cli} ({@link SecretInput},
 * {@link StandardInput}, {@link ProcessUser}, {@link CliOutput}) and of the tool ({@link ServiceControl}). The exit code is the one
 * Boot publishes as an {@link ExitCodeEvent} after {@code CliExitCodeMapper} mapped the failure, or the code of
 * {@link SpringApplication#exit} when the run succeeded; the failure report itself is what Boot logs
 * ({@code CliFailureAnalyzer}), which a test reads with {@code OutputCaptureExtension}.
 */
public final class ToolExtension implements ParameterResolver {

    /** @param failure what {@code SpringApplication.run} threw, or null; @param context closed by the time the run returns */
    public record Outcome(int exitCode, RuntimeException failure, ConfigurableApplicationContext context) {

        /** The refusal in the failure chain, if the run was refused. */
        public Optional<CliException> refusal() {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof CliException refusal) {
                    return Optional.of(refusal);
                }
            }
            return Optional.empty();
        }

        /**
         * The error code of the refusal in the failure chain, if the run was refused: an {@code ErrorCode} of {@code ledger-cli}
         * or a {@code MigrationError} of the tool.
         */
        public Optional<ErrorKey> code() {
            return refusal().map(CliException::code);
        }
    }

    /** The legacy ledger of a test: the connection of its read-only account and its schema. */
    private record LegacyReader(DataSource reader, String schema) {
    }

    public static final class Tool {

        private final DataSource database;
        private final String processUser;
        private final List<String> extraArguments;
        private final LegacyReader legacySource;
        private final byte[] standardInput;
        private final ServiceControl serviceControl;

        Tool() {
            this(null, null, List.of(), null, null, null);
        }

        private Tool(DataSource database, String processUser, List<String> extraArguments, LegacyReader legacySource,
                byte[] standardInput, ServiceControl serviceControl) {
            this.database = database;
            this.processUser = processUser;
            this.extraArguments = extraArguments;
            this.legacySource = legacySource;
            this.standardInput = standardInput;
            this.serviceControl = serviceControl;
        }

        /** A Tool whose standard input holds these bytes (what a pipe would give {@code --stdin}); without it the input is empty. */
        public Tool withStandardInput(String text) {
            return new Tool(database, processUser, extraArguments, legacySource, text.getBytes(StandardCharsets.UTF_8),
                    serviceControl);
        }

        /**
         * A Tool whose application has the PostgreSQL driver registered (so the condition of the product holds by its own path)
         * and leaves the product's DataSource configuration out of its scan: the ledger beans (the real services, repositories and
         * transaction manager) are created over this database, which the test has built with the real migrator, instead of over
         * the DataSource of the registered driver. The DataSource is handed in without a close method, so it is still open when the
         * run has closed the context and the test reads the rows.
         */
        public Tool withDatabase(DataSource dataSource) {
            return new Tool(dataSource, processUser, extraArguments, legacySource, standardInput, serviceControl);
        }

        /** A Tool that runs as this process user (the account name that the audit trail records as {@code cli:<name>}). */
        public Tool asProcessUser(String name) {
            return new Tool(database, name, extraArguments, legacySource, standardInput, serviceControl);
        }

        /** A Tool whose application has this 1.x ledger as the source of {@code import-1x} (the way the settings {@code ledger.import.source.*} would configure it). */
        public Tool withLegacySource(DataSource reader, String schema) {
            return new Tool(database, processUser, extraArguments, new LegacyReader(reader, schema), standardInput,
                    serviceControl);
        }

        /** A Tool whose {@code stop-1x} makes its calls to the service manager through this boundary (a test records them). */
        public Tool withServiceControl(ServiceControl control) {
            return new Tool(database, processUser, extraArguments, legacySource, standardInput, control);
        }

        /** A Tool that also gets these command line arguments (for example {@code --spring.config.import=...}). */
        public Tool withArguments(String... arguments) {
            List<String> all = new ArrayList<>(extraArguments);
            all.addAll(List.of(arguments));
            return new Tool(database, processUser, List.copyOf(all), legacySource, standardInput, serviceControl);
        }

        /** @param input null keeps the product Console input; @param output null keeps the product stream output */
        public Outcome run(Path secretsDirectory, SecretInput input, CliOutput output, String... args) {
            List<String> all = new ArrayList<>(List.of(args));
            all.add("--ledger.secrets.directory=" + secretsDirectory);
            all.addAll(extraArguments);
            AtomicReference<ConfigurableApplicationContext> started = new AtomicReference<>();
            ExitCodes exitCodes = new ExitCodes();
            SpringApplicationBuilder builder = new SpringApplicationBuilder(Migrate1xApplication.class)
                    .listeners(exitCodes)
                    .initializers(ctx -> {
                        started.set(ctx);
                        GenericApplicationContext generic = (GenericApplicationContext) ctx;
                        if (database != null) {
                            generic.getBeanFactory().registerSingleton("testDatabaseScanFilter", new TestDatabaseScanFilter());
                            // the ledger data source is the primary one, as in production (the 1.x data source may join it)
                            generic.registerBean("testDataSource", DataSource.class, () -> new DelegatingDataSource(database),
                                    bd -> bd.setPrimary(true));
                        }
                        if (legacySource != null) {
                            // the 1.x data source is a bean of the context, as the pool is in production
                            // (LegacySourceConfiguration); the test owns it, so the context does not close it. The 1.x
                            // JdbcClient, its transaction manager and the repository are the production ones (LegacySourceWiring).
                            generic.registerBean(LegacySourceConfiguration.DATA_SOURCE, DataSource.class,
                                    () -> legacySource.reader(), bd -> bd.setDestroyMethodName(""));
                            generic.registerBean(LegacySourceWiring.class);
                        }
                        if (processUser != null) {
                            UserPrincipal principal = () -> processUser;
                            generic.registerBean("testProcessUser", ProcessUser.class, () -> fileSystem -> principal,
                                    bd -> bd.setPrimary(true));
                        }
                        if (standardInput != null) {
                            generic.registerBean("testStandardInput", StandardInput.class,
                                    () -> new StandardInput(new java.io.ByteArrayInputStream(standardInput)), bd -> bd.setPrimary(true));
                        }
                        if (serviceControl != null) {
                            generic.registerBean("testServiceControl", ServiceControl.class, () -> serviceControl,
                                    bd -> bd.setPrimary(true));
                        }
                        if (input != null) {
                            generic.registerBean("testSecretInput", SecretInput.class, () -> input, bd -> bd.setPrimary(true));
                        }
                        if (output != null) {
                            generic.registerBean("testOutput", CliOutput.class, () -> output, bd -> bd.setPrimary(true));
                        }
                    });
            if (legacySource != null) {
                // the settings that the wiring of the 1.x source reads; the connection is the bean above, never opened by url
                builder.properties("ledger.import.source.url=jdbc:unused:test",
                        "ledger.import.source.schema=" + legacySource.schema());
            }
            if (database != null) {
                // the test brings its own database: the product's configuration of the DataSource is left out of the scan, and
                // the registration of the PostgreSQL driver lets the product's own condition hold (TestDatabaseScanFilter)
                builder.properties(TestDriverRegistration.settings());
            }
            try {
                ConfigurableApplicationContext context = builder.run(all.toArray(String[]::new));
                return new Outcome(SpringApplication.exit(context), null, context);
            } catch (RuntimeException failure) {
                // The test entry plays the boundary (what main() does): whatever the start throws is handed to the
                // test in the Outcome, which asserts on it. Nothing is swallowed.
                return new Outcome(exitCodes.code.get(), failure, started.get());
            }
        }
    }

    private static final class ExitCodes implements ApplicationListener<ExitCodeEvent> {

        private final AtomicInteger code = new AtomicInteger();

        @Override
        public void onApplicationEvent(ExitCodeEvent event) {
            code.set(event.getExitCode());
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext extension) {
        return parameter.getParameter().getType() == Tool.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext extension) {
        return new Tool();
    }
}
