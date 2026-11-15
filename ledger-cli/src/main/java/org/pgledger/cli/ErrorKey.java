package org.pgledger.cli;

/**
 * The reason of a refusal, whose text is {@link #messageKey()} in the message bundles. {@link ErrorCode} is the set of the
 * reasons of {@code ledger-cli}; another program that is built on this module has an enum of its own that implements this
 * interface.
 */
public interface ErrorKey {

    /** The name of the reason (an enum constant name). */
    String name();

    /** The key of its message. */
    String messageKey();
}
