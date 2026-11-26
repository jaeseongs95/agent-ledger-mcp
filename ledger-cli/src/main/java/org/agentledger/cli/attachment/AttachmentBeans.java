// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.attachment;

import org.agentledger.cli.ErrorCode;
import org.agentledger.cli.UsageException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The one answer of an attachment command to a bean that is not there because the attachments are not configured: the beans of
 * the attachments exist only when {@code ledger.attachments.directory} is set (and the ledger database is configured, which the
 * command has asked for first). The refusal says which settings to give, so the owner never sees a bean error.
 */
final class AttachmentBeans {

    private AttachmentBeans() {
    }

    /**
     * @throws UsageException ATTACHMENTS_NOT_CONFIGURED when the settings of the attachments are not given
     */
    static <T> T require(ObjectProvider<T> provider) {
        return provider.getIfAvailable(() -> {
            throw new UsageException(ErrorCode.ATTACHMENTS_NOT_CONFIGURED);
        });
    }
}
