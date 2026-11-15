package org.pgledger.hook;

import tools.jackson.core.JacksonException;

/**
 * How one host phrases its pre-tool-use event and the answer to it, and how it spells the name of an MCP tool. This is
 * the vendor boundary of the hook: everything that differs between the hosts (event fields, tool name shapes, answer
 * documents) is in the implementation of that host, and the code that signs ({@link LedgerHook}) knows no host. A new
 * host is a new implementation, a {@code @Component} that names its {@link Client}, and one more constant of
 * {@link Client}; {@link HostDialects} finds the implementation of the client that the command line named.
 */
sealed interface HostDialect permits ClaudeDialect, CodexDialect, AntigravityDialect {

    /** The client (the first argument of the jar) whose host this dialect speaks. */
    Client client();

    /**
     * Binds the host event and finds out whether it is a ledger write call and where its arguments and host id are.
     *
     * @throws JacksonException if {@code stdin} is not exactly one valid JSON document
     */
    Intake read(byte[] stdin) throws JacksonException;

    /** The answer to input that is not valid JSON. */
    Response malformedInput();

    /** The answer for a call that is not a ledger write call. */
    Response notLedgerCall();

    /** The answer that refuses the call. */
    Response deny(Refusal reason);

    /** The answer that lets the call go on with the stamped arguments. */
    Response allow(Intake.Attest call, Verdict.Stamped stamped);
}
