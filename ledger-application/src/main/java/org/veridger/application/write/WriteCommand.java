// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.write;

/** What every write request carries: the operation key (the idempotency key, required) and the reason. */
public sealed interface WriteCommand
        permits SaveCommand, TransitionCommand, RecordDeliverableCommand, RecordCaseCommand, LinkCommand, CorrectCommand {

    /** The idempotency key as text; the server never makes one up, so an absent or empty key is refused (VALIDATION_FAILED, path {@code operationKey}). */
    String operationKey();

    String reason();
}
