package org.pgledger.migrate1x.wiring;

import java.util.Set;
import org.pgledger.cli.owner.OwnerAccountEnvironmentPostProcessor;
import org.pgledger.migrate1x.tool.ToolCommand;

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
}
