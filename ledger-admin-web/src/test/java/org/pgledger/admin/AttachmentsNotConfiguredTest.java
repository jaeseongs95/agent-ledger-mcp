package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Seeds;
import org.pgledger.admin.support.Seeds.Seeded;
import org.pgledger.database.repository.AttachmentRepository;
import org.pgledger.domain.model.Attachment;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.Sha256;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ATA-023, the installation without attachments ({@code ledger.attachments.directory} is not set): the beans of the attachments do
 * not exist, the history page still works and says that the content is not available, and the download is 404.
 */
@AdminTest
class AttachmentsNotConfiguredTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Seeds seeds;

    @Autowired
    private AttachmentRepository attachments;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("ata023_notConfigured: without the attachment settings the history page shows the metadata and a message instead of the content, and the download is 404")
    void ata023_notConfigured() throws Exception {
        assertThat(context.getBeanNamesForType(org.pgledger.application.attachment.AttachmentStore.class)).isEmpty();
        Seeded project = seeds.project();
        String defect = seeds.defect(project, "defect with an attachment row only");
        String id = UUID.randomUUID().toString();
        byte[] body = "report".getBytes(StandardCharsets.UTF_8);
        attachments.insert(new Attachment(id, "report.txt", "text/plain", body.length, Sha256.ofBytes(body),
                AuthorId.of(Seeds.AUTHOR), Instant.parse("2026-11-15T00:00:00Z")));
        seeds.disputeWithAttachment(defect, id);
        Browser browser = Browser.signedIn(mvc);

        String page = browser.perform(browser.get("/history?project=" + project.project())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("report.txt", "Attachments are not configured on this installation")
                .doesNotContain("<pre").doesNotContain("/attachments/" + id + "/download");
        browser.perform(browser.get("/attachments/" + id + "/download")).andExpect(status().isNotFound());
    }
}
