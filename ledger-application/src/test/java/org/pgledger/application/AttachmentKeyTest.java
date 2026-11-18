package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.application.attachment.AttachmentCheck;
import org.pgledger.application.attachment.AttachmentCheck.Report;
import org.pgledger.application.attachment.AttachmentFlow;
import org.pgledger.application.attachment.AttachmentKeys;
import org.pgledger.application.attachment.AttachmentMaintenanceService;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.AttachmentRekey;
import org.pgledger.application.attachment.AttachmentStartCheck;
import org.pgledger.application.attachment.AttachmentStore;
import org.pgledger.application.attachment.StorageKeys;
import org.pgledger.application.attachment.UploadCommands.Begun;
import org.pgledger.application.attachment.UploadCommands.Finished;
import org.pgledger.application.attachment.UploadService;
import org.pgledger.application.support.AttachmentContext;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.FailingMaintenanceService;
import org.pgledger.application.support.LedgerAppContext;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.support.TestClock;
import org.pgledger.application.support.Uploads;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.model.AttachmentStorage;
import org.pgledger.domain.value.StorageKey;
import org.pgledger.testing.TestOwners;

/**
 * ATA-014 (key switch: an attachment written with key A reads after the active key is B, and a new upload is written with B) and
 * ATA-031 (rekey: the body is written again under the active key, reads with the same plain hash, the old file is a replaced file that the
 * check moves under {@code orphans/} only after {@code orphan-grace} counted from the row that replaced it; a failure after the new file and
 * before its row leaves the old file current). The active key of the context is the one the context was started with; "the owner sets
 * {@code active-key-id} to another key" is the upload service built with the settings of the context and another active key
 * ({@link Uploads#serviceWithActiveKey}), and a key that is not the active one is a key made by {@link AttachmentKeys#create()}.
 *
 * <p>The start check of ATA-014 deletes a key file, so it has a context of its own ({@code AttachmentStartCheckTest}); this class shares the
 * context of the other service tests.
 */
@AttachmentContext
class AttachmentKeyTest {

    private static final Duration GRACE = Duration.ofHours(24);

    // ------------------------------------------------------------------------------------------------ ATA-014

    @Test
    @DisplayName("ATA-014: an attachment written with key A reads after the active key is B; a new upload is written with B (its key_id is B) and reads")
    void ata014_theKeySwitch(Scenario s) {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        AttachmentKeys keys = s.bean(AttachmentKeys.class);
        String keyA = s.bean(AttachmentProperties.class).activeKeyId();
        byte[] oldBody = Uploads.body(500, 1);
        byte[] newBody = Uploads.body(500, 2);

        Finished old = Uploads.upload(flow, actor, oldBody, 200);
        assertThat(Uploads.currentStorage(ctx, old.attachmentId()).keyId()).isEqualTo(keyA);

        String keyB = keys.create();
        UploadService afterTheSwitch = Uploads.serviceWithActiveKey(ctx, keyB);
        Begun begun = ctx.tx(() -> afterTheSwitch.begin(actor, Uploads.begin(newBody)));
        String uploadId = begun.files().getFirst().uploadId();
        assertThat(Uploads.row(ctx, uploadId).keyId()).as("the new upload is written under B").isEqualTo(keyB);
        Uploads.sendParts(flow, actor, uploadId, Uploads.parts(newBody, 200), 0);
        Finished fresh = flow.finish(actor, begun.attachmentId());

        assertThat(Uploads.currentStorage(ctx, fresh.attachmentId()).keyId()).isEqualTo(keyB);
        assertThat(Uploads.currentStorage(ctx, old.attachmentId()).keyId()).as("the old row still names A").isEqualTo(keyA);
        assertThat(Uploads.readAll(flow, actor, old.attachmentId(), 128)).as("read with key A").isEqualTo(oldBody);
        assertThat(Uploads.readAll(flow, actor, fresh.attachmentId(), 128)).as("read with key B").isEqualTo(newBody);
    }

    // ------------------------------------------------------------------------------------------------ ATA-031

    private static AttachmentStorage uploadedWith(Scenario s, String actor, String keyId, byte[] body, String... idOut) {
        Finished finished = Uploads.uploadWithKey(s.ctx(), s.bean(AttachmentFlow.class), actor, keyId, body, 150);
        idOut[0] = finished.attachmentId();
        return Uploads.currentStorage(s.ctx(), finished.attachmentId());
    }

    @Test
    @DisplayName("ATA-031: rekey --from A writes the body again under the active key: the same plain SHA-256, a new storage key, the old file is untouched, kept for the grace and then moved under orphans/")
    void ata031_rekeyAndTheReplacedFile(Scenario s) throws IOException {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        AttachmentProperties properties = s.bean(AttachmentProperties.class);
        AttachmentCheck check = s.bean(AttachmentCheck.class);
        String keyA = s.bean(AttachmentKeys.class).create();
        byte[] body = Uploads.body(700, 3);
        String[] id = new String[1];
        AttachmentStorage before = uploadedWith(s, actor, keyA, body, id);
        assertThat(before.keyId()).isEqualTo(keyA);
        assertThat(before.rev()).isEqualTo(0);
        Finished finished = flow.finish(actor, id[0]);
        byte[] oldBytes = Files.readAllBytes(Uploads.fileOf(ctx, before.storageKey()));

        AttachmentRekey.Result result = s.bean(AttachmentRekey.class).rekey(TestOwners.OWNER, keyA);

        assertThat(result).isEqualTo(new AttachmentRekey.Result(1, 0));
        AttachmentStorage after = Uploads.currentStorage(ctx, id[0]);
        assertThat(after.rev()).as("the next revision of the file").isEqualTo(1);
        assertThat(after.keyId()).isEqualTo(properties.activeKeyId());
        assertThat(after.storageKey()).isNotEqualTo(before.storageKey());
        byte[] read = Uploads.readAll(flow, actor, id[0], 200);
        assertThat(read).isEqualTo(body);
        assertThat(Uploads.sha(read)).as("the same plain SHA-256").isEqualTo(finished.files().getFirst().sha256());
        assertThat(Files.readAllBytes(Uploads.fileOf(ctx, before.storageKey()))).as("the old file is not touched").isEqualTo(oldBytes);
        assertThat(s.count("SELECT COUNT(*) FROM owner_audit WHERE operation = 'ATTACHMENT_REKEY' AND subject = ?", id[0])).isEqualTo(1);
        assertThat(ctx.jdbc().sql("SELECT detail FROM owner_audit WHERE operation = 'ATTACHMENT_REKEY' AND subject = ?").param(id[0])
                .query(String.class).single()).as("the audit row names the key id and no storage key")
                .doesNotContain(before.storageKey().hex()).doesNotContain(after.storageKey().hex());
        assertThatThrownBy(() -> s.bean(AttachmentRekey.class).rekey(TestOwners.OWNER, properties.activeKeyId()))
                .as("a rekey from the active key is refused").isInstanceOfSatisfying(ValidationException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ValidationCode.VALIDATION_FAILED));

        // the old file is a replaced file: it waits for the grace, counted from the row that replaced it
        Instant replacedAt = after.createdAt();
        ctx.clock().set(replacedAt.plus(GRACE).minusSeconds(1));
        check.run(true);
        assertThat(Uploads.fileOf(ctx, before.storageKey())).as("before the grace has passed since the new row").exists();
        assertThat(Uploads.quarantinedFileOf(ctx, before.storageKey())).doesNotExist();
        ctx.clock().set(replacedAt.plus(GRACE));
        Report moved = check.run(true);
        assertThat(moved.replacedQuarantined()).isGreaterThanOrEqualTo(1);
        assertThat(Uploads.fileOf(ctx, before.storageKey())).doesNotExist();
        assertThat(Uploads.quarantinedFileOf(ctx, before.storageKey())).as("moved, never deleted").exists();
        assertThat(Uploads.readAll(flow, actor, id[0], 300)).as("the attachment reads from its current file").isEqualTo(body);
        assertThat(check.run(false).restored()).as("a replaced file is not moved back").isZero();
        assertThat(Uploads.quarantinedFileOf(ctx, before.storageKey())).exists();
    }

    @Test
    @DisplayName("ATA-031: a failure after the new file was written and before its row is added: the old row is still current, the attachment reads from the old file, the new file is a file without a row that the check moves after the grace; a second run succeeds")
    void ata031_aFailureBeforeTheRowLeavesTheOldFileCurrent(Scenario s) throws IOException {
        LedgerAppContext ctx = s.ctx();
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        AttachmentStore store = s.bean(AttachmentStore.class);
        AttachmentMaintenanceService maintenance = s.bean(AttachmentMaintenanceService.class);
        AttachmentCheck check = s.bean(AttachmentCheck.class);
        String keyA = s.bean(AttachmentKeys.class).create();
        byte[] body = Uploads.body(400, 4);
        String[] id = new String[1];
        AttachmentStorage before = uploadedWith(s, actor, keyA, body, id);
        Set<StorageKey> strays = withoutRow(store, maintenance);
        AttachmentRekey failing = new AttachmentRekey(new FailingMaintenanceService(ctx), store, s.bean(StorageKeys.class),
                s.bean(AttachmentProperties.class));

        assertThatThrownBy(() -> failing.rekey(TestOwners.OWNER, keyA)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected failure");

        assertThat(Uploads.currentStorage(ctx, id[0])).as("the old row is still the current one").isEqualTo(before);
        assertThat(Uploads.readAll(flow, actor, id[0], 100)).as("read from the old file").isEqualTo(body);
        Set<StorageKey> now = withoutRow(store, maintenance);
        now.removeAll(strays);
        assertThat(now).as("the new file is a file without a row").hasSize(1);
        StorageKey stray = now.iterator().next();
        assertThat(s.count("SELECT COUNT(*) FROM owner_audit WHERE operation = 'ATTACHMENT_REKEY' AND subject = ?", id[0])).isZero();

        // the check moves it away once it is older than the grace (the file's age is the time of its last write)
        Files.setLastModifiedTime(Uploads.fileOf(ctx, stray), FileTime.from(TestClock.START.minus(GRACE).minusSeconds(60)));
        Report report = check.run(true);
        assertThat(report.orphansQuarantined()).isGreaterThanOrEqualTo(1);
        assertThat(Uploads.quarantinedFileOf(ctx, stray)).exists();
        assertThat(Uploads.fileOf(ctx, before.storageKey())).as("the old file stays").exists();

        // the same rekey again, without a failure
        AttachmentRekey.Result again = s.bean(AttachmentRekey.class).rekey(TestOwners.OWNER, keyA);
        assertThat(again).isEqualTo(new AttachmentRekey.Result(1, 0));
        assertThat(Uploads.currentStorage(ctx, id[0]).rev()).isEqualTo(1);
        assertThat(Uploads.readAll(flow, actor, id[0], 100)).isEqualTo(body);
    }

    private static Set<StorageKey> withoutRow(AttachmentStore store, AttachmentMaintenanceService maintenance) {
        return store.list().stream().map(AttachmentStore.StoredFile::key).filter(key -> !maintenance.holdsStorageKey(key))
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("ATA-014: the keys are files named by their key id with 256 bits, owner only, made one by one; a missing or foreign key id is ARTIFACT_KEY_MISSING naming the key id only")
    void ata014_theKeyFiles(Scenario s) throws IOException {
        AttachmentKeys keys = s.bean(AttachmentKeys.class);
        AttachmentProperties properties = s.bean(AttachmentProperties.class);

        String first = keys.create();
        String second = keys.create();

        assertThat(first).isNotEqualTo(second).matches("key-[0-9a-f]{16}");
        Path file = properties.keyDirectory().resolve(first);
        assertThat(Files.size(file)).as("256 bits").isEqualTo(32);
        assertThat(keys.byId(first).id()).isEqualTo(first);
        assertThat(keys.byId(first).toString()).as("the text of a key never shows it").doesNotContain(
                HexFormat.of().formatHex(Files.readAllBytes(file)));
        for (String missing : new String[] {"key-0000000000000000", "../outside", ""}) {
            InfrastructureException failure = (InfrastructureException) Expect.failure(() -> keys.byId(missing),
                    InfrastructureCode.ARTIFACT_KEY_MISSING);
            assertThat(failure.args()).hasSize(1);
        }
        assertThat(List.of(first, second)).allSatisfy(id -> assertThat(properties.keyDirectory().resolve(id)).exists());
    }

    @Test
    @DisplayName("ATA-014: the start check of the context passes while every needed key file is there")
    void ata014_theStartCheckPassesWhileTheKeysAreThere(Scenario s) {
        Uploads.upload(s.bean(AttachmentFlow.class), s.actorWith("issue"), Uploads.body(100, 5));

        s.bean(AttachmentStartCheck.class).verify();
    }
}
