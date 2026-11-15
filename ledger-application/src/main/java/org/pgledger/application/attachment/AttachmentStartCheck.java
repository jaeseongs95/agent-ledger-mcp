package org.pgledger.application.attachment;

import org.springframework.stereotype.Component;

/**
 * The check at the start of a server (common design 4.3): the attachment directory and its quarantine folder exist and only the service
 * account may use them, the key directory is private, and every key file that a current file or an upload names is there (the active
 * key too). A missing key file stops the start and names the key id, never the key. The class opens no transaction.
 */
@Component
@ConditionalOnAttachments
public class AttachmentStartCheck {

    private final AttachmentStore store;
    private final AttachmentKeys keys;
    private final AttachmentMaintenanceService maintenance;

    public AttachmentStartCheck(AttachmentStore store, AttachmentKeys keys, AttachmentMaintenanceService maintenance) {
        this.store = store;
        this.keys = keys;
        this.maintenance = maintenance;
    }

    /** Runs the check; the first failure is thrown. */
    public void verify() {
        store.prepare();
        keys.requirePresent(maintenance.keyIdsInUse());
    }
}
