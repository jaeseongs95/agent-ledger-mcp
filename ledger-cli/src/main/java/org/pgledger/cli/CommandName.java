package org.pgledger.cli;

/**
 * The name of a command of a program that is built on the command line machinery of this module (the runner, the refusals and
 * the usage report). {@link CliCommand} is the set of the commands of {@code ledger-cli}; another program (the migration tool) has
 * a set of its own, an enum that implements this interface, and runs on the same machinery.
 */
public interface CommandName extends java.io.Serializable {

    /** The word on the command line. */
    String cliName();

    /** Message key of the command line of this command in the usage text. */
    String usageKey();

    /** Whether the command works on the ledger database. */
    boolean usesDatabase();

    /** Message key of the first line of the usage text of the program this command belongs to (it names the program). */
    default String usageHeaderKey() {
        return "usage.header";
    }
}
