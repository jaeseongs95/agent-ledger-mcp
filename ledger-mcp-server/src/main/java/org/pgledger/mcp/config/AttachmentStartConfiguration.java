package org.pgledger.mcp.config;

import org.pgledger.application.attachment.AttachmentStartCheck;
import org.pgledger.application.attachment.ConditionalOnAttachments;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The check of the attachments at the start of the server (common design 4.3): the attachment directory and the key directory are
 * private to the service account, the key directory is not inside the attachment directory, and every key file that a row points to
 * (and the active key) is there. A failure stops the start with the failure of the check, naming the key id or the folder and never a
 * key. The runner runs once, when the context is up and the schema was migrated and verified (the check reads the rows); a failure
 * here ends the start of the process, so a server with a missing key file or a storage path of the wrong kind (a file where the folder belongs) does not stay up.
 *
 * <p>A {@code @Configuration} class and not a component because {@link ApplicationRunner} is the extension point that Spring Boot
 * calls once at the start; the class exists only when the attachments are configured, like the tools and the reaper.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAttachments
public class AttachmentStartConfiguration implements ApplicationRunner {

    private final AttachmentStartCheck check;

    public AttachmentStartConfiguration(AttachmentStartCheck check) {
        this.check = check;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        check.verify();
    }
}
