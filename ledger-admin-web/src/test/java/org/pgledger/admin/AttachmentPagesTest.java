// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTest;
import org.pgledger.admin.support.Browser;
import org.pgledger.admin.support.Seeds;
import org.pgledger.admin.support.Seeds.BundleFile;
import org.pgledger.admin.support.Seeds.Seeded;
import org.pgledger.application.attachment.AttachmentKeys;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.AttachmentStore;
import org.pgledger.application.attachment.PrivateFiles;
import org.pgledger.database.repository.AttachmentFileRepository;
import org.pgledger.database.repository.AttachmentStorageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.unit.DataSize;

/**
 * ATA-023: the history page shows an attachment of a history row. A text attachment is shown escaped and read only; any other
 * is offered as a download through the application. No page and no header shows a storage key, a path or a key. The files are made
 * through the real upload flow (the five operations, each call signed for its tool), in folders of the test and with a key that
 * {@link AttachmentKeys#create()} made.
 */
@AdminTest
class AttachmentPagesTest {

    private static final Path BASE = base();
    private static final Path DIRECTORY = BASE.resolve("attachments");
    private static final Path KEY_DIRECTORY = BASE.resolve("keys");
    private static final String KEY_ID = newKey();

    @DynamicPropertySource
    static void attachments(DynamicPropertyRegistry registry) {
        registry.add("ledger.attachments.directory", DIRECTORY::toString);
        registry.add("ledger.attachments.key-directory", KEY_DIRECTORY::toString);
        registry.add("ledger.attachments.active-key-id", () -> KEY_ID);
    }

    private static Path base() {
        try {
            return Files.createTempDirectory("ledger-admin-attachments");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String newKey() {
        AttachmentProperties properties = new AttachmentProperties(DIRECTORY, KEY_DIRECTORY, "placeholder",
                DataSize.ofMegabytes(64), DataSize.ofMegabytes(512), DataSize.ofGigabytes(10), 4, DataSize.ofMegabytes(64), Duration.ofMinutes(10),
                Duration.ofHours(24), Duration.ofMinutes(1));
        return new AttachmentKeys(properties, new PrivateFiles(), new SecureRandom()).create();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Seeds seeds;

    @Autowired
    private AttachmentStore store;

    @Autowired
    private AttachmentFileRepository files;

    @Autowired
    private AttachmentStorageRepository storages;

    @BeforeEach
    void folders() {
        store.prepare();
    }

    private String history(Browser browser, Seeded project) throws Exception {
        return browser.perform(browser.get("/history?project=" + project.project())).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
    }

    /** The page and the file hold nothing of the places: no storage key, no folder, no key id. */
    private void assertNoPlaces(String text, String attachmentId) {
        for (var file : files.ofAttachment(attachmentId)) {
            String storageKey = storages.current(attachmentId, file.seq()).orElseThrow().storageKey().hex();
            assertThat(text).doesNotContain(storageKey);
        }
        assertThat(text).doesNotContain(DIRECTORY.toString()).doesNotContain(KEY_DIRECTORY.toString())
                .doesNotContain(BASE.toString()).doesNotContain(KEY_ID);
    }

    @Test
    @DisplayName("ata023_textAttachmentIsEscapedAndReadOnly: a text body with <script> appears only as escaped text in a pre element, with its name, type, size, SHA-256, uploader and time")
    void ata023_textAttachmentIsEscapedAndReadOnly() throws Exception {
        Seeded project = seeds.project();
        String issue = seeds.issue(project, "issue with a report");
        String body = "<script>alert(1)</script> & \"quoted\" <b>bold</b>";
        String attachment = seeds.attachment("report.txt", "text/plain", body.getBytes(StandardCharsets.UTF_8));
        seeds.disputeWithAttachment(issue, attachment);
        Browser browser = Browser.signedIn(mvc);

        String page = history(browser, project);

        assertThat(page).contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;quoted&quot; &lt;b&gt;bold&lt;/b&gt;")
                .doesNotContain("<script>alert(1)</script>").doesNotContain("<b>bold</b>");
        assertThat(page).containsPattern("<pre[^>]*>&lt;script&gt;");
        assertThat(page).contains("report.txt", "text/plain", ">" + body.length() + "<",
                org.pgledger.domain.value.Sha256.ofBytes(body.getBytes(StandardCharsets.UTF_8)).hex(), Seeds.AUTHOR);
        // read only: nothing in the page edits the content
        assertThat(page).doesNotContain("<textarea").doesNotContain("contenteditable");
        assertNoPlaces(page, attachment);
    }

    @Test
    @DisplayName("ata023_longTextIsCutAtTheLimit: a text attachment over 256 KiB shows its first 256 KiB and says so")
    void ata023_longTextIsCutAtTheLimit() throws Exception {
        Seeded project = seeds.project();
        String issue = seeds.issue(project, "issue with a long report");
        String attachment = seeds.attachment("long.txt", "text/plain", "x".repeat(300_000).getBytes(StandardCharsets.UTF_8));
        seeds.disputeWithAttachment(issue, attachment);
        Browser browser = Browser.signedIn(mvc);

        String page = history(browser, project);

        assertThat(page).contains("x".repeat(262_144)).doesNotContain("x".repeat(262_145));
        assertThat(page).contains("Only the first 256 KiB are shown.");
    }

    @Test
    @DisplayName("ata023_nonTextIsADownload: a binary attachment is offered as a download; the download streams the same bytes with attachment, nosniff and application/octet-stream, and no path")
    void ata023_nonTextIsADownload() throws Exception {
        Seeded project = seeds.project();
        String issue = seeds.issue(project, "issue with a pdf");
        byte[] body = new byte[300_000];
        new Random(7).nextBytes(body);
        String attachment = seeds.attachment("보고서 1.pdf", "application/pdf", body);
        seeds.disputeWithAttachment(issue, attachment);
        Browser browser = Browser.signedIn(mvc);

        String page = history(browser, project);
        MockHttpServletResponse download = browser.perform(browser.get("/attachments/" + attachment + "/download"))
                .andExpect(status().isOk()).andReturn().getResponse();

        assertThat(page).contains("href=\"/attachments/" + attachment + "/download\"").doesNotContain("<pre");
        assertNoPlaces(page, attachment);
        assertThat(download.getContentAsByteArray()).isEqualTo(body);
        assertThat(download.getContentType()).isEqualTo("application/octet-stream");
        assertThat(download.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(download.getHeader(HttpHeaders.CONTENT_LENGTH)).isEqualTo(Integer.toString(body.length));
        String disposition = download.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).startsWith("attachment;").contains("filename*=UTF-8''");
        assertThat(java.net.URLDecoder.decode(disposition.substring(disposition.indexOf("filename*=UTF-8''") + 17).replace("+", "%2B"),
                StandardCharsets.UTF_8)).isEqualTo("보고서 1.pdf");
        for (String name : download.getHeaderNames()) {
            assertNoPlaces(download.getHeader(name), attachment);
        }
    }

    @Test
    @DisplayName("ata023_textThatIsNotUtf8IsADownload: a text type whose bytes are not valid UTF-8 is not shown as text")
    void ata023_textThatIsNotUtf8IsADownload() throws Exception {
        Seeded project = seeds.project();
        String issue = seeds.issue(project, "issue with a latin1 report");
        String attachment = seeds.attachment("latin.txt", "text/plain", new byte[] {(byte) 0xff, (byte) 0xfe, 'a'});
        seeds.disputeWithAttachment(issue, attachment);
        Browser browser = Browser.signedIn(mvc);

        String page = history(browser, project);

        assertThat(page).doesNotContain("<pre").contains("/attachments/" + attachment + "/download");
    }

    @Test
    @DisplayName("ata023_downloadNeedsTheSignIn: an unauthenticated caller is sent to the login like the other pages; an unknown id and an id that is no id are 404")
    void ata023_downloadNeedsTheSignIn() throws Exception {
        String id = UUID.randomUUID().toString();

        Browser anonymous = new Browser(mvc);
        anonymous.perform(anonymous.get("/attachments/" + id + "/download")).andExpect(status().is3xxRedirection());

        Browser owner = Browser.signedIn(mvc);
        owner.perform(owner.get("/attachments/" + id + "/download")).andExpect(status().isNotFound());
        owner.perform(owner.get("/attachments/not-an-id/download")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ATA-044: multi-file bundle renders all files on history and downloads each by ?seq=N (default 0), returning 404 on invalid seq")
    void ata044_multiFileBundleRendersAndDownloadsBySeq() throws Exception {
        Seeded project = seeds.project();
        String issue = seeds.issue(project, "issue with a multi-file bundle");
        byte[] doc = "# Bundle Summary\nLine 2\n".getBytes(StandardCharsets.UTF_8);
        byte[] bin = new byte[1024];
        new Random(9).nextBytes(bin);
        String attachment = seeds.bundle(java.util.List.of(
                new BundleFile("summary.md", "text/markdown", doc),
                new BundleFile("trace.bin", "application/octet-stream", bin)));
        seeds.disputeWithAttachment(issue, attachment);
        Browser browser = Browser.signedIn(mvc);

        String page = history(browser, project);
        assertThat(page).contains("summary.md", "text/markdown", "# Bundle Summary",
                "trace.bin", "application/octet-stream",
                "/attachments/" + attachment + "/download?seq=0",
                "/attachments/" + attachment + "/download?seq=1");
        assertNoPlaces(page, attachment);

        MockHttpServletResponse d0 = browser.perform(browser.get("/attachments/" + attachment + "/download?seq=0"))
                .andExpect(status().isOk()).andReturn().getResponse();
        MockHttpServletResponse d1 = browser.perform(browser.get("/attachments/" + attachment + "/download?seq=1"))
                .andExpect(status().isOk()).andReturn().getResponse();
        MockHttpServletResponse dDefault = browser.perform(browser.get("/attachments/" + attachment + "/download"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(d0.getContentAsByteArray()).isEqualTo(doc);
        assertThat(dDefault.getContentAsByteArray()).isEqualTo(doc);
        assertThat(d1.getContentAsByteArray()).isEqualTo(bin);

        browser.perform(browser.get("/attachments/" + attachment + "/download?seq=2")).andExpect(status().isNotFound());
        browser.perform(browser.get("/attachments/" + attachment + "/download?seq=-1")).andExpect(status().isNotFound());
    }
}
