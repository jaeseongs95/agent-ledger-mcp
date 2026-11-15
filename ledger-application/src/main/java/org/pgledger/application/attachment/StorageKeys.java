package org.pgledger.application.attachment;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.pgledger.domain.value.StorageKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** The one maker of storage keys: 128 random bits from the secure random source, as 32 lower-case hex characters. */
@Component
@ConditionalOnAttachments
public class StorageKeys {

    private static final int BYTES = 16;

    private final SecureRandom random;

    public StorageKeys(@Qualifier(AttachmentConfiguration.RANDOM) SecureRandom random) {
        this.random = random;
    }

    /** A new storage key; nothing of the file is in it. */
    public StorageKey next() {
        byte[] bytes = new byte[BYTES];
        random.nextBytes(bytes);
        return StorageKey.of(HexFormat.of().formatHex(bytes));
    }
}
