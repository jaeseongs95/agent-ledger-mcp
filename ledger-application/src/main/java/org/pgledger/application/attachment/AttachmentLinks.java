package org.pgledger.application.attachment;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.value.AuthorId;
import org.springframework.stereotype.Component;

/**
 * The check of the common design, section 5: a record that points to an attachment (a history row with {@code attachment_id}) is
 * written only when the attachment exists and was uploaded by the author who writes the record. It runs inside the transaction of the
 * record's use case (the callers are {@code @Transactional} services), so a failed check fails that record and nothing of it is
 * written. There is no foreign key; this is the one place that knows the rule. An attachment of another author is answered like
 * one that does not exist.
 */
@Component
public class AttachmentLinks {

    private final AttachmentRepository attachments;

    public AttachmentLinks(AttachmentRepository attachments) {
        this.attachments = attachments;
    }

    /**
     * Checks the attachment of a record; nothing is checked for a record without one.
     *
     * @param attachmentId the id the caller sent, or null
     * @param author       the author that writes the record
     * @throws ValidationException when the id is not a UUID
     * @throws LookupException NOT_FOUND when there is no such attachment of this author
     */
    public void requireOwnedBy(String attachmentId, AuthorId author) {
        if (attachmentId == null) {
            return;
        }
        try {
            UUID.fromString(attachmentId);
        } catch (IllegalArgumentException notAUuid) {
            throw ValidationException.invalid("attachment_id", ValidationReason.MUST_BE_UUID, notAUuid);
        }
        boolean mine = attachments.findById(attachmentId).filter(found -> Objects.equals(found.createdBy(), author)).isPresent();
        if (!mine) {
            throw new LookupException(LookupCode.ARTIFACT_NOT_FOUND, List.of(attachmentId));
        }
    }
}
