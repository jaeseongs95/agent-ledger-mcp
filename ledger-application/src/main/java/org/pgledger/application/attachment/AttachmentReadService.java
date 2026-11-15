package org.pgledger.application.attachment;

import java.util.Optional;
import org.pgledger.database.repository.AttachmentFileRepository;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.domain.model.Attachment;
import org.pgledger.domain.model.AttachmentFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database half of showing an attachment to the owner (the admin web): the metadata of an attachment and the row of its current file. It reads rows only and touches no file: the caller reads the body
 * through {@link AttachmentStore} outside the transaction (principle 4.4). It does not need the attachment settings, so it exists
 * in every application; the part that needs them ({@link AttachmentStore}) exists only when the attachments are configured.
 */
@Service
@Transactional(readOnly = true)
public class AttachmentReadService {

    private final AttachmentRepository attachments;
    private final AttachmentFileRepository files;

    public AttachmentReadService(AttachmentRepository attachments,
            AttachmentFileRepository files) {
        this.attachments = attachments;
        this.files = files;
    }

    /** The metadata of an attachment. */
    public Optional<Attachment> find(String attachmentId) {
        return attachments.findById(attachmentId);
    }

    /** The current file of an attachment: its row with the largest number; empty when the attachment has none. */
    public Optional<AttachmentFile> currentFile(String attachmentId) {
        return files.currentOf(attachmentId);
    }
}
