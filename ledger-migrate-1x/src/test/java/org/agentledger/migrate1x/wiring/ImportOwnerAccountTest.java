// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.cli.owner.OwnerAccountEnvironmentPostProcessor;
import org.agentledger.cli.owner.OwnerAccountPropertySource;
import org.agentledger.migrate1x.Migrate1xApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * M1X-026 (review C2): the owner account is asked for in the tool only for {@code import-1x}. The post-processor of {@code ledger-cli} is
 * registered in the {@code spring.factories} of its jar, so it is on the class path of the tool too: it must not act there (it would ask
 * for a password for {@code migrate} or {@code setup}, commands the tool does not have), and the one of the tool must not act for
 * another application.
 */
class ImportOwnerAccountTest {

    private static StandardEnvironment environment(String command) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource("--owner-account=ledger_ddl", command));
        return environment;
    }

    private static boolean asksForTheOwnerAccount(StandardEnvironment environment) {
        return environment.getPropertySources().stream().anyMatch(OwnerAccountPropertySource.class::isInstance);
    }

    @Test
    @DisplayName("M1X-026 the tool's post-processor adds the owner account for import-1x of the tool and for no other command or application")
    void onlyForImportOfTheTool() {
        ImportOwnerAccount processor = new ImportOwnerAccount();

        StandardEnvironment imported = environment("import-1x");
        processor.postProcessEnvironment(imported, new SpringApplication(Migrate1xApplication.class));
        assertThat(asksForTheOwnerAccount(imported)).isTrue();

        StandardEnvironment migrate = environment("migrate");
        processor.postProcessEnvironment(migrate, new SpringApplication(Migrate1xApplication.class));
        assertThat(asksForTheOwnerAccount(migrate)).isFalse();

        StandardEnvironment other = environment("import-1x");
        processor.postProcessEnvironment(other, new SpringApplication(ImportOwnerAccountTest.class));
        assertThat(asksForTheOwnerAccount(other)).isFalse();
    }

    @Test
    @DisplayName("M1X-026 the post-processor of ledger-cli does nothing in the tool: no password is asked for for migrate or setup")
    void theOneOfLedgerCliDoesNothingInTheTool() {
        OwnerAccountEnvironmentPostProcessor ledgerCli = new OwnerAccountEnvironmentPostProcessor();

        StandardEnvironment migrate = environment("migrate");
        ledgerCli.postProcessEnvironment(migrate, new SpringApplication(Migrate1xApplication.class));

        assertThat(asksForTheOwnerAccount(migrate)).isFalse();
    }
}
