// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.migrate1x.wiring;

import java.util.Set;
import org.agentledger.cli.owner.OwnerAccountEnvironmentPostProcessor;
import org.agentledger.migrate1x.Migrate1xApplication;
import org.agentledger.migrate1x.tool.ToolCommand;
import org.springframework.boot.SpringApplication;

/**
 * The owner account of a run of {@code import-1x}, which writes the whole ledger: everything is the post-processor of
 * {@code ledger-cli} ({@code --owner-account=<account>} and {@code --stdin} on the command line, the password typed or read from
 * standard input, in front of every other property source); this class only names the command of this tool that declares the
 * option. Registered in {@code META-INF/spring.factories}.
 */
public final class ImportOwnerAccount extends OwnerAccountEnvironmentPostProcessor {

    private static final Set<String> COMMANDS = Set.of(ToolCommand.IMPORT_1X.cliName());

    @Override
    protected Set<String> commands() {
        return COMMANDS;
    }

    /** The tool only: the post-processor of this class is on the class path of no other program, but the check is the same rule. */
    @Override
    protected boolean appliesTo(SpringApplication application) {
        return application == null || application.getAllSources().contains(Migrate1xApplication.class);
    }
}
