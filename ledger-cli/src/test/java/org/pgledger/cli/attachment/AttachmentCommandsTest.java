package org.pgledger.cli.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.application.attachment.AttachmentStore;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.ExitStatus;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.support.AttachmentFixture;
import org.pgledger.cli.support.AttachmentFixture.Stored;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.RequiresOwnerOnlyStorage;
import org.pgledger.cli.support.TestLedgerDatabase;
import org.pgledger.domain.value.StorageKey;

/**
 * ATA-008, ATA-022 and ATA-031 at the level of the commands {@code attachments-check}, {@code attachments-key-create} and
 * {@code attachments-rekey}: the real application context over a schema of its own in the PostgreSQL of the test PC, the real
 * storage classes over a folder of the test, and the rows of the attachments written the way a finished upload leaves them. The
 * tests read the rows, the folders and the output the commands left.
 */
@ExtendWith({ CliExtension.class, EnglishMessages.class })
@RequiresOwnerOnlyStorage
class AttachmentCommandsTest {

    private static final String USER = "alice";
    private static final String AUTHOR = "claude:e2e-author";
    private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");
    private static final String AUDIT = "SELECT actor, operation, subject, detail FROM owner_audit WHERE operation = ? ORDER BY id";

    @TempDir
    Path tmp;

    private TestLedgerDatabase database;
    private AttachmentFixture fixture;

    @BeforeEach
    void prepare() {
        database = TestLedgerDatabase.migrated();
        fixture = AttachmentFixture.in(tmp);
    }

    @AfterEach
    void close() {
        database.close();
    }

    /** The command on the real wiring, with the settings of the attachments the installer would have rendered. */
    private Outcome run(Cli cli, RecordingOutput output, String activeKeyId, String... args) {
        return cli.withDatabase(database.dataSource()).asProcessUser(USER)
                .withArguments("--ledger.attachments.directory=" + fixture.directory(),
                        "--ledger.attachments.key-directory=" + fixture.keyDirectory(),
                        "--ledger.attachments.active-key-id=" + activeKeyId)
                .run(tmp.resolve("secrets"), null, output, args);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** Adds the rows of a finished upload (the attachment and its first file) and returns the attachment id. */
    private String addAttachment(Stored stored, String keyId) {
        return addAttachment(stored.key(), stored.size(), stored.sha256().hex(), keyId);
    }

    private String addAttachment(StorageKey key, long size, String sha256, String keyId) {
        String id = UUID.randomUUID().toString();
        database.update("INSERT INTO attachment (id, file_name, media_type, size_bytes, content_sha256, created_by, created_at) "
                + "VALUES (?, 'report.md', 'text/markdown', ?, ?, ?, ?)", id, size, sha256, AUTHOR, utc(LONG_AGO));
        database.update("INSERT INTO attachment_file (attachment_id, seq, storage_key, key_id, created_at) VALUES (?, 1, ?, ?, ?)",
                id, key.hex(), keyId, utc(LONG_AGO));
        return id;
    }

    private static byte[] body(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ATA-008 ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("ATA-008 attachments-check reports the counts and the ids of the missing, damaged and unreferenced attachments, moves old files without a row to orphans/ and never deletes, exits 1 while an attachment is missing or damaged, and shows no storage key and no path")
    void ata008_theReportAndTheMoves(Cli cli) {
        Stored good = fixture.write(body("a good report"));
        String goodId = addAttachment(good, fixture.keyId());
        StorageKey damagedKey = fixture.writeUnfinished(body("a report without its end mark"));
        String damagedId = addAttachment(damagedKey, 28, "b".repeat(64), fixture.keyId());
        StorageKey missingKey = fixture.newStorageKey();
        String missingId = addAttachment(missingKey, 10, "c".repeat(64), fixture.keyId());
        Stored oldOne = fixture.write(body("an old file without a row"));
        Stored oldTwo = fixture.write(body("another old file without a row"));
        Stored young = fixture.write(body("a young file without a row"));
        fixture.touch(oldOne.key(), LONG_AGO);
        fixture.touch(oldTwo.key(), LONG_AGO);

        RecordingOutput output = new RecordingOutput();
        Outcome result = run(cli, output, fixture.keyId(), "attachments-check");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.ATTACHMENTS_NOT_CLEAN);
        assertThat(result.refusal().orElseThrow().arguments()).containsExactly("1", "1");
        String text = output.info();
        assertThat(text).contains("3 attachment(s) checked: 1 missing, 1 damaged, 3 pointed to by no history row");
        assertThat(text).contains("Files without a row: 2 moved to the orphans folder, 1 younger than the grace period");
        assertThat(text).contains("Files in the orphans folder now: 2.");
        assertThat(text).contains("Missing attachment (its file is not there): " + missingId);
        assertThat(text).contains("Damaged attachment (its file does not pass the check): " + damagedId);
        assertThat(text).contains("Attachment that no history row points to (kept): " + goodId);
        // moved, never deleted; the young file and the files of rows stay where they are
        assertThat(fixture.fileOf(oldOne.key())).doesNotExist();
        assertThat(fixture.quarantinedOf(oldOne.key())).exists();
        assertThat(fixture.fileOf(oldTwo.key())).doesNotExist();
        assertThat(fixture.quarantinedOf(oldTwo.key())).exists();
        assertThat(fixture.fileOf(young.key())).exists();
        assertThat(fixture.fileOf(good.key())).exists();
        assertThat(fixture.fileOf(damagedKey)).exists();
        // the missing and the damaged attachment are reported and never deleted
        assertThat(database.rows("SELECT id FROM attachment")).hasSize(3);
        assertThat(database.rows("SELECT attachment_id FROM attachment_file")).hasSize(3);
        // no line shows a storage key (32 hex characters) or a path
        assertThat(text).doesNotContainPattern("[0-9a-f]{32}").doesNotContain(tmp.toString())
                .doesNotContain(fixture.directory().toString()).doesNotContain(fixture.keyDirectory().toString());
        assertThat(database.rows(AUDIT, "ORPHANS_PURGED")).isEmpty();

        // a restore: the row of a quarantined file exists again, so the check moves the file back
        addAttachment(oldOne, fixture.keyId());
        RecordingOutput restoring = new RecordingOutput();
        Outcome restored = run(cli, restoring, fixture.keyId(), "attachments-check");

        assertThat(restored.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(restoring.info()).contains("Files moved back because their row exists again: 1")
                .contains("Files in the orphans folder now: 1.");
        assertThat(fixture.fileOf(oldOne.key())).exists();
        assertThat(fixture.quarantinedOf(oldOne.key())).doesNotExist();
        assertThat(fixture.quarantinedOf(oldTwo.key())).exists();

        // --purge-orphans empties the orphans folder, first, and the owner audit trail names the count (no storage key)
        RecordingOutput purging = new RecordingOutput();
        Outcome purged = run(cli, purging, fixture.keyId(), "attachments-check", "--purge-orphans");

        assertThat(purged.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(purging.info()).startsWith("Emptied the orphans folder: 1 file(s) deleted.")
                .contains("Files in the orphans folder now: 0.");
        assertThat(fixture.quarantinedOf(oldTwo.key())).doesNotExist();
        assertThat(fixture.quarantined()).isZero();
        List<Map<String, Object>> audit = database.rows(AUDIT, "ORPHANS_PURGED");
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor")).isEqualTo("cli:" + USER);
        assertThat(audit.getFirst().get("detail")).isEqualTo("1 files");
        assertThat(String.valueOf(audit.getFirst())).doesNotContainPattern("[0-9a-f]{32}");
    }

    @Test
    @DisplayName("ATA-008 attachments-check on attachments that are all there exits 0 and prints the counts; a repeated run changes nothing")
    void ata008_aCleanCheckExitsZero(Cli cli) {
        Stored good = fixture.write(body("a good report"));
        String goodId = addAttachment(good, fixture.keyId());

        RecordingOutput output = new RecordingOutput();
        Outcome result = run(cli, output, fixture.keyId(), "attachments-check");
        Outcome again = run(cli, new RecordingOutput(), fixture.keyId(), "attachments-check");

        assertThat(result.failure()).isNull();
        assertThat(result.exitCode()).isZero();
        assertThat(again.exitCode()).isZero();
        assertThat(output.info()).contains("1 attachment(s) checked: 0 missing, 0 damaged, 1 pointed to by no history row")
                .contains("Attachment that no history row points to (kept): " + goodId);
        assertThat(fixture.fileOf(good.key())).exists();
        assertThat(fixture.quarantined()).isZero();
    }

    @Test
    @DisplayName("ATA-008 there is no option --remove-orphan-files: it is UNKNOWN_OPTION (exit 2) and no file is touched")
    void ata008_noOptionRemovesFiles(Cli cli) {
        Stored old = fixture.write(body("an old file without a row"));
        fixture.touch(old.key(), LONG_AGO);

        Outcome result = run(cli, new RecordingOutput(), fixture.keyId(), "attachments-check", "--remove-orphan-files");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(result.code()).contains(ErrorCode.UNKNOWN_OPTION);
        assertThat(fixture.fileOf(old.key())).exists();
        assertThat(fixture.quarantined()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = { "attachments-check", "attachments-key-create" })
    @DisplayName("ATA-035 without ledger.attachments.directory a command of the attachments is the usage error ATTACHMENTS_NOT_CONFIGURED (exit 2), not a bean error, and changes nothing")
    void ata008_withoutTheSettingsTheCommandIsRefused(String command, Cli cli) {
        Outcome result = cli.withDatabase(database.dataSource()).asProcessUser(USER).run(tmp.resolve("secrets"), null,
                new RecordingOutput(), command);

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(result.code()).contains(ErrorCode.ATTACHMENTS_NOT_CONFIGURED);
        assertThat(result.refusal().orElseThrow()).isInstanceOf(UsageException.class);
        assertThat(fixture.keyFileNames()).containsExactly(fixture.keyId());
    }

    @Test
    @DisplayName("ATA-035 attachments-rekey without the settings is the usage error ATTACHMENTS_NOT_CONFIGURED (exit 2)")
    void ata031_rekeyWithoutTheSettingsIsRefused(Cli cli) {
        Outcome result = cli.withDatabase(database.dataSource()).asProcessUser(USER).run(tmp.resolve("secrets"), null,
                new RecordingOutput(), "attachments-rekey", "--from=" + fixture.keyId());

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(result.code()).contains(ErrorCode.ATTACHMENTS_NOT_CONFIGURED);
    }

    // ATA-022 ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("ATA-022 attachments-key-create twice makes two new key files of 32 bytes, owner only, named by the key id the command prints; the old key stays; the output has the key id and no key bytes")
    void ata022_aNewKeyEachTime(Cli cli) {
        Set<String> start = fixture.keyFileNames();
        byte[] first = fixture.keyBytes(fixture.keyId());

        RecordingOutput one = new RecordingOutput();
        Outcome madeOne = run(cli, one, fixture.keyId(), "attachments-key-create");
        Set<String> afterOne = fixture.keyFileNames();
        RecordingOutput two = new RecordingOutput();
        Outcome madeTwo = run(cli, two, fixture.keyId(), "attachments-key-create");
        Set<String> afterTwo = fixture.keyFileNames();

        assertThat(madeOne.failure()).isNull();
        assertThat(madeOne.exitCode()).isZero();
        assertThat(madeTwo.exitCode()).isZero();
        List<String> newOne = fixture.newKeys(start);
        assertThat(newOne).hasSize(2);
        List<String> idOne = afterOne.stream().filter(name -> !start.contains(name)).toList();
        List<String> idTwo = afterTwo.stream().filter(name -> !afterOne.contains(name)).toList();
        assertThat(idOne).hasSize(1);
        assertThat(idTwo).hasSize(1);
        assertThat(idOne.getFirst()).isNotEqualTo(idTwo.getFirst());
        assertThat(one.info()).contains(idOne.getFirst()).contains("ledger.attachments.active-key-id");
        assertThat(two.info()).contains(idTwo.getFirst()).doesNotContain(idOne.getFirst());
        for (String id : List.of(idOne.getFirst(), idTwo.getFirst())) {
            byte[] key = fixture.keyBytes(id);
            assertThat(key).hasSize(32);
            fixture.requirePrivateKeyFile(id);
            for (String text : List.of(one.info(), two.info())) {
                assertThat(text).doesNotContain(HexFormat.of().formatHex(key)).doesNotContain(Base64.getEncoder().encodeToString(key))
                        .doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(key));
            }
        }
        // the old key stays, byte for byte, and is still the active one: the command changes no setting
        assertThat(afterTwo).contains(fixture.keyId());
        assertThat(fixture.keyBytes(fixture.keyId())).isEqualTo(first);
        assertThat(afterTwo).hasSize(3);
    }

    // ATA-031 ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("ATA-031 attachments-rekey --from=<old key> writes the file again under the active key, adds the second row of attachment_file (seq 2), audits it, keeps the plain SHA-256, and the old file is moved to orphans/ by attachments-check once the grace has passed")
    void ata031_rekeyWritesTheFileAgain(Cli cli) {
        Stored stored = fixture.write(body("the body of a review report"));
        String id = addAttachment(stored, fixture.keyId());
        Set<String> start = fixture.keyFileNames();
        assertThat(run(cli, new RecordingOutput(), fixture.keyId(), "attachments-key-create").exitCode()).isZero();
        String newKeyId = fixture.newKeys(start).getFirst();

        RecordingOutput output = new RecordingOutput();
        Outcome result = run(cli, output, newKeyId, "attachments-rekey", "--from=" + fixture.keyId());

        assertThat(result.failure()).isNull();
        assertThat(result.exitCode()).isZero();
        assertThat(output.info()).contains("Wrote 1 attachment file(s) again, from the key " + fixture.keyId()
                + " to the active key " + newKeyId + "; skipped 0").contains("attachments-check");
        List<Map<String, Object>> rows = database.rows(
                "SELECT seq, storage_key, key_id FROM attachment_file WHERE attachment_id = ? ORDER BY seq", id);
        assertThat(rows).hasSize(2);
        assertThat(((Number) rows.get(1).get("seq")).longValue()).isEqualTo(2);
        assertThat(rows.get(1).get("key_id")).isEqualTo(newKeyId);
        assertThat(rows.get(1).get("storage_key")).isNotEqualTo(stored.key().hex());
        StorageKey fresh = StorageKey.of((String) rows.get(1).get("storage_key"));
        AttachmentStore.Content content = fixture.verify(fresh, newKeyId);
        assertThat(content.sizeBytes()).isEqualTo(stored.size());
        assertThat(content.sha256()).isEqualTo(stored.sha256());
        List<Map<String, Object>> audit = database.rows(AUDIT, "ATTACHMENT_REKEY");
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("actor")).isEqualTo("cli:" + USER);
        assertThat(audit.getFirst().get("subject")).isEqualTo(id);
        assertThat(audit.getFirst().get("detail")).isEqualTo("key " + newKeyId);
        assertThat(output.info()).doesNotContainPattern("[0-9a-f]{32}").doesNotContain(tmp.toString());
        // the old file is a replaced file now, and stays in place until the grace has passed since the new row
        assertThat(fixture.fileOf(stored.key())).exists();
        database.update("UPDATE attachment_file SET created_at = ? WHERE attachment_id = ? AND seq = 2", utc(LONG_AGO), id);

        RecordingOutput checked = new RecordingOutput();
        Outcome check = run(cli, checked, newKeyId, "attachments-check");

        assertThat(check.exitCode()).isZero();
        assertThat(checked.info()).contains("Replaced files moved to the orphans folder: 1");
        assertThat(fixture.fileOf(stored.key())).doesNotExist();
        assertThat(fixture.quarantinedOf(stored.key())).exists();
        assertThat(fixture.fileOf(fresh)).exists();
    }

    @Test
    @DisplayName("ATA-035 attachments-rekey --from=<the active key> is ATTACHMENTS_REKEY_FROM_ACTIVE (exit 2): nothing is written and no row is added")
    void ata031_theActiveKeyIsRefused(Cli cli) {
        Stored stored = fixture.write(body("the body of a review report"));
        addAttachment(stored, fixture.keyId());

        Outcome result = run(cli, new RecordingOutput(), fixture.keyId(), "attachments-rekey", "--from=" + fixture.keyId());

        assertThat(result.exitCode()).isEqualTo(ExitStatus.USAGE.code());
        assertThat(result.code()).contains(ErrorCode.ATTACHMENTS_REKEY_FROM_ACTIVE);
        assertThat(database.rows("SELECT attachment_id FROM attachment_file")).hasSize(1);
        assertThat(database.rows(AUDIT, "ATTACHMENT_REKEY")).isEmpty();
        assertThat(Files.exists(fixture.fileOf(stored.key()))).isTrue();
    }
}
