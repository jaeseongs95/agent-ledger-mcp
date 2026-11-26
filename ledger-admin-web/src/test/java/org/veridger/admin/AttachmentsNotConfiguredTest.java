// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.admin.support.AdminTest;
import org.veridger.admin.support.Browser;
import org.veridger.admin.support.Seeds;
import org.veridger.admin.support.Seeds.Seeded;
import org.veridger.database.repository.AttachmentFileRepository;
import org.veridger.database.repository.AttachmentRepository;
import org.veridger.domain.model.Attachment;
import org.veridger.domain.model.AttachmentFile;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.Sha256;
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
    private AttachmentFileRepository files;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("ata023_healthPageNotConfigured: the attachment check page says that nothing is configured and shows no counts")
    void ata023_healthPageNotConfigured() throws Exception {
        Browser browser = Browser.signedIn(mvc);

        String page = browser.perform(browser.get("/attachments")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(page).contains("id=\"attachments-not-configured\"").doesNotContain("id=\"attachments-counts\"")
                .contains("attachments-check --purge-orphans empties the quarantine");
    }

    @Test
    @DisplayName("ata023_notConfigured: without the attachment settings the history page shows the metadata and a message instead of the content, and the download is 404")
    void ata023_notConfigured() throws Exception {
        assertThat(context.getBeanNamesForType(org.veridger.application.attachment.AttachmentStore.class)).isEmpty();
        Seeded project = seeds.project();
        String issue = seeds.issue(project, "issue with an attachment row only");
        String id = UUID.randomUUID().toString();
        byte[] body = "report".getBytes(StandardCharsets.UTF_8);
        attachments.insert(new Attachment(id, AuthorId.of(Seeds.AUTHOR), Instant.parse("2026-11-15T00:00:00Z")));
        files.insert(new AttachmentFile(id, 0, "report.txt", "text/plain", body.length, Sha256.ofBytes(body)));
        seeds.disputeWithAttachment(issue, id);
        Browser browser = Browser.signedIn(mvc);

        String page = browser.perform(browser.get("/history?project=" + project.project())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("report.txt", "Attachments are not configured on this installation")
                .doesNotContain("<pre").doesNotContain("/attachments/" + id + "/download");
        browser.perform(browser.get("/attachments/" + id + "/download")).andExpect(status().isNotFound());
    }
}
