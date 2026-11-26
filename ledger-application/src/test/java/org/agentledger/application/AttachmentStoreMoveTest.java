// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.attachment.AttachmentStore;
import org.agentledger.application.support.StoreFixture;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.InfrastructureException;
import org.agentledger.domain.value.Sha256;
import org.agentledger.domain.value.StorageKey;

/**
 * R9-D0095 (d): a file that is moved to a name that exists is never put over the file of that name; the move fails and both files
 * stay as they were. {@code StandardCopyOption.ATOMIC_MOVE} does not say it: "if the target file exists then it is implementation
 * specific if the existing file is replaced or this method fails" (the JDK documentation of {@code Files.move}); the Windows
 * provider and the rename of Linux both replace it, so the comment "a name that exists is never overwritten" was true of neither.
 */
class AttachmentStoreMoveTest {

    private static final byte[] FIRST = "the file that was quarantined".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SECOND = "the file that is in its place now".getBytes(StandardCharsets.UTF_8);

    private StoreFixture fixture;
    private AttachmentStore store;

    @BeforeEach
    void open() {
        fixture = StoreFixture.create();
        store = fixture.store();
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    @Test
    @DisplayName("R9-D0095 d: restoring a quarantined file to a name that exists fails and replaces nothing")
    void r9d0095_aMoveNeverReplacesAFileOfThatName() throws Exception {
        StorageKey key = fixture.storageKeys().next();
        store.append(key, fixture.keyId(), 0, 0, FIRST, Sha256.ofBytes(FIRST));
        store.quarantine(key);
        // a file of the same name is in its place now (the key is the same, the bytes are others)
        store.append(key, fixture.keyId(), 0, 0, SECOND, Sha256.ofBytes(SECOND));
        byte[] inPlace = Files.readAllBytes(fixture.fileOf(key));

        assertThatThrownBy(() -> store.restore(key)).isInstanceOfSatisfying(InfrastructureException.class,
                failure -> assertThat(failure.code()).isEqualTo(InfrastructureCode.ARTIFACT_STORAGE_IO_ERROR));

        assertThat(Files.readAllBytes(fixture.fileOf(key))).as("the file in its place is the one it was").isEqualTo(inPlace);
        assertThat(store.listQuarantined()).as("the quarantined file is still there").hasSize(1);
    }

    @Test
    @DisplayName("R9-D0095 d: a move to a name that is free works as before: quarantine and restore")
    void r9d0095_aMoveToAFreeNameWorks() throws Exception {
        StorageKey key = fixture.storageKeys().next();
        store.append(key, fixture.keyId(), 0, 0, FIRST, Sha256.ofBytes(FIRST));
        byte[] before = Files.readAllBytes(fixture.fileOf(key));

        store.quarantine(key);
        assertThat(store.exists(key)).isFalse();
        store.restore(key);

        assertThat(store.exists(key)).isTrue();
        assertThat(Files.readAllBytes(fixture.fileOf(key))).isEqualTo(before);
    }
}
