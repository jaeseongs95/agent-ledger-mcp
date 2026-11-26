// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.SecureRandom;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.admin.support.AdminTest;
import org.agentledger.admin.support.Browser;
import org.agentledger.admin.support.MutableClock;
import org.agentledger.admin.support.Seeds;
import org.agentledger.admin.support.Seeds.Seeded;
import org.agentledger.application.attachment.AttachmentKeys;
import org.agentledger.application.attachment.AttachmentProperties;
import org.agentledger.application.attachment.AttachmentStore;
import org.agentledger.application.attachment.PrivateFiles;
import org.agentledger.application.attachment.StorageKeys;
import org.agentledger.database.repository.AttachmentStorageRepository;
import org.agentledger.domain.value.StorageKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.unit.DataSize;

/**
 * ATA-023, the attachment check on the admin page ({@code /attachments}): the read-only result of {@code AttachmentCheck.run(false)}.
 * One scenario with known numbers: one good attachment, one whose file is gone, one whose file does not match what was uploaded,
 * one that no history row points to, and one file that no row names (older than the grace). The page reports the counts and the ids,
 * moves nothing, and shows no storage key and no path.
 */
@AdminTest
class AttachmentHealthPageTest {

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
            return Files.createTempDirectory("ledger-admin-health");
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
    private AttachmentKeys keys;

    @Autowired
    private StorageKeys storageKeys;

    @Autowired
    private AttachmentStorageRepository storages;

    @Autowired
    private MutableClock clock;

    private StorageKey keyOf(String attachmentId) {
        return storages.current(attachmentId, 0).orElseThrow().storageKey();
    }

    private static Path pathOf(StorageKey key) {
        return DIRECTORY.resolve(key.folder()).resolve(key.hex());
    }

    @Test
    @DisplayName("ata023_healthPage: the page shows the counts and ids of the read-only check, the sentence about attachments-check and --purge-orphans, moves nothing and shows no key or path")
    void ata023_healthPage() throws Exception {
        store.prepare();
        Seeded project = seeds.project();
        String good = seeds.attachment("good.txt", "text/plain", "the good report".getBytes(StandardCharsets.UTF_8));
        String gone = seeds.attachment("gone.txt", "text/plain", "this file will be removed".getBytes(StandardCharsets.UTF_8));
        String altered = seeds.attachment("altered.txt", "text/plain", "what was uploaded".getBytes(StandardCharsets.UTF_8));
        String loose = seeds.attachment("loose.txt", "text/plain", "no history row points here".getBytes(StandardCharsets.UTF_8));
        for (String attachment : new String[] {good, gone, altered}) {
            seeds.disputeWithAttachment(seeds.issue(project, "issue of " + attachment), attachment);
        }
        StorageKey goodKey = keyOf(good);
        StorageKey goneKey = keyOf(gone);
        StorageKey alteredKey = keyOf(altered);
        // the file of "gone" is removed; the file of "altered" is replaced by a valid file of another body
        store.remove(goneKey);
        store.remove(alteredKey);
        store.rekey(goodKey, KEY_ID, alteredKey, KEY_ID);
        // a file that no row names, older than the grace (24 hours): a valid file made by a rekey whose row was never added
        StorageKey orphan = storageKeys.next();
        store.rekey(goodKey, KEY_ID, orphan, keys.create());
        Files.setLastModifiedTime(pathOf(orphan), FileTime.from(clock.instant().minus(Duration.ofHours(48))));
        Browser browser = Browser.signedIn(mvc);

        String page = browser.perform(browser.get("/attachments")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(page).contains("id=\"attachments-total\">4<", "id=\"attachments-orphans\">1<", "id=\"attachments-waiting\">0<",
                "id=\"attachments-replaced\">0<", "id=\"attachments-restored\">0<", "id=\"attachments-quarantined\">0<",
                "id=\"attachments-missing\">1<", "id=\"attachments-damaged\">1<", "id=\"attachments-unreferenced\">1<");
        assertThat(page).contains(gone, altered, loose).doesNotContain(good);
        assertThat(page).contains("attachments-check moves these files", "attachments-check --purge-orphans empties the quarantine");
        // read only: the file without a row is still where it was, the quarantine is empty
        assertThat(Files.exists(pathOf(orphan))).isTrue();
        assertThat(store.listQuarantined()).isEmpty();
        for (StorageKey key : new StorageKey[] {goodKey, goneKey, alteredKey, orphan}) {
            assertThat(page).doesNotContain(key.hex());
        }
        assertThat(page).doesNotContain(DIRECTORY.toString()).doesNotContain(KEY_DIRECTORY.toString()).doesNotContain(KEY_ID);

        String korean = browser.perform(browser.get("/attachments").header(HttpHeaders.ACCEPT_LANGUAGE, "ko"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(korean).contains("첨부 점검", "attachments-check --purge-orphans");
    }

    @Test
    @DisplayName("ata023_healthPageNeedsTheSignIn: an unauthenticated caller is sent to the login")
    void ata023_healthPageNeedsTheSignIn() throws Exception {
        Browser anonymous = new Browser(mvc);

        anonymous.perform(anonymous.get("/attachments")).andExpect(status().is3xxRedirection());
    }
}
