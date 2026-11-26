// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.attachment;

import java.util.List;
import java.util.Optional;
import org.agentledger.database.repository.AttachmentFileRepository;
import org.agentledger.database.repository.AttachmentRepository;
import org.agentledger.database.repository.AttachmentStorageRepository;
import org.agentledger.domain.model.Attachment;
import org.agentledger.domain.model.AttachmentFile;
import org.agentledger.domain.model.AttachmentStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only query service for kept attachment bundle metadata (requires no agent attestation or configured disk). */
@Service
@Transactional(readOnly = true)
public class AttachmentReadService {

    private final AttachmentRepository attachments;
    private final AttachmentFileRepository files;
    private final AttachmentStorageRepository storages;

    public AttachmentReadService(
            AttachmentRepository attachments,
            AttachmentFileRepository files,
            AttachmentStorageRepository storages) {
        this.attachments = attachments;
        this.files = files;
        this.storages = storages;
    }

    public Optional<Attachment> find(String attachmentId) {
        return attachments.findById(attachmentId);
    }

    public List<AttachmentFile> filesOf(String attachmentId) {
        return files.ofAttachment(attachmentId);
    }

    public Optional<AttachmentFile> fileAt(String attachmentId, int seq) {
        return files.at(attachmentId, seq);
    }

    public Optional<AttachmentStorage> currentStorage(String attachmentId, int seq) {
        return storages.current(attachmentId, seq);
    }
}
