// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.support;

import java.nio.file.Path;
import java.time.Duration;
import org.agentledger.application.attachment.AttachmentProperties;
import org.springframework.util.unit.DataSize;

/** The settings record of the attachments as the tests build it by hand: the defaults of the design, section 3.1, for what is not named. */
public final class AttachmentSettings {

    private AttachmentSettings() {
    }

    /** The defaults with these folders and this active key. */
    public static AttachmentProperties properties(Path directory, Path keyDirectory, String activeKeyId) {
        return new AttachmentProperties(directory, keyDirectory, activeKeyId, DataSize.ofMegabytes(64),
                DataSize.ofMegabytes(512), DataSize.ofGigabytes(10), 4, DataSize.ofMegabytes(64), Duration.ofMinutes(10), Duration.ofHours(24),
                Duration.ofMinutes(1));
    }
}
