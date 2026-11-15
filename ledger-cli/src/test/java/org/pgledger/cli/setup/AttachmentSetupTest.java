package org.pgledger.cli.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.pgledger.application.attachment.PrivateFiles;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.ExitStatus;
import org.pgledger.cli.support.CliExtension;
import org.pgledger.cli.support.CliExtension.Cli;
import org.pgledger.cli.support.CliExtension.Outcome;
import org.pgledger.cli.support.EnglishMessages;
import org.pgledger.cli.support.RecordingOutput;
import org.pgledger.cli.support.RequiresOwnerOnlyStorage;
import org.pgledger.cli.support.TestLedgerDatabase;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * ATA-037: {@code setup} makes the first key of the attachments once. The installation of the test is a folder with the settings
 * folder {@code etc} (its {@code local.yaml} is where the owner's settings live), the folder of the keys and the attachment folder
 * as {@code prepare-host} leaves them (the key folder is made by the run when it is missing). Every run is a new application context
 * that reads {@code local.yaml} back, as every process of the product does.
 */
@ExtendWith({ CliExtension.class, EnglishMessages.class })
@RequiresOwnerOnlyStorage
class AttachmentSetupTest {

    private static final String SETTING = "ledger.attachments.active-key-id";

    @TempDir
    Path tmp;

    private TestLedgerDatabase database;

    @BeforeEach
    void emptyDatabase() throws IOException {
        database = TestLedgerDatabase.empty();
        Files.createDirectories(secrets());
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Path etc() {
        return tmp.resolve("etc");
    }

    private Path secrets() {
        return etc().resolve("secrets");
    }

    private Path keyDirectory() {
        return secrets().resolve("attachment-keys");
    }

    private Path local() {
        return etc().resolve("local.yaml");
    }

    private Outcome run(Cli cli, RecordingOutput output, String... settings) {
        List<String> arguments = new ArrayList<>(List.of("--ledger.secrets.home=" + etc()));
        arguments.addAll(List.of(settings));
        return cli.withDatabase(database.dataSource()).withApplicationAccount(database.applicationDataSource(true))
                .asProcessUser("alice").withArguments(arguments.toArray(String[]::new))
                .run(tmp.resolve("unused-secrets"), null, output, "setup", "--app-account=" + database.appAccount());
    }

    private Outcome runWithTheAttachments(Cli cli, RecordingOutput output) {
        return run(cli, output, "--ledger.attachments.directory=" + tmp.resolve("data/attachments"),
                "--ledger.attachments.key-directory=" + keyDirectory());
    }

    private List<String> keyFiles() throws IOException {
        if (!Files.isDirectory(keyDirectory())) {
            return List.of();
        }
        try (Stream<Path> found = Files.list(keyDirectory())) {
            return found.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static List<PropertySource<?>> yaml(String text) {
        try {
            return new YamlPropertySourceLoader().load("local", new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Test
    @DisplayName("ATA-037 the first setup makes one 256-bit key file (owner only, named by its key id), writes the key id as ledger.attachments.active-key-id into local.yaml and prints the id and no key; a second setup finds the setting, makes no key and changes no file")
    void ata037_theFirstKeyIsMadeOnce(Cli cli) throws IOException {
        RecordingOutput first = new RecordingOutput();

        Outcome made = runWithTheAttachments(cli, first);

        assertThat(made.failure()).isNull();
        assertThat(made.exitCode()).isZero();
        List<String> keys = keyFiles();
        assertThat(keys).hasSize(1);
        String keyId = keys.getFirst();
        byte[] key = Files.readAllBytes(keyDirectory().resolve(keyId));
        assertThat(key).hasSize(32);
        new PrivateFiles().requireFile(keyDirectory().resolve(keyId));
        assertThat(first.info()).contains("Created the first attachment key " + keyId);
        assertThat(first.info()).doesNotContain(HexFormat.of().formatHex(key)).doesNotContain(Base64.getEncoder().encodeToString(key))
                .doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(key));
        String settings = Files.readString(local());
        assertThat(settings).contains(SETTING + ": '" + keyId + "'");
        assertThat(yaml(settings).getFirst().getProperty(SETTING)).isEqualTo(keyId);
        assertThat(settings).doesNotContain(HexFormat.of().formatHex(key));
        // the setting is in one place only: the rendered files are not touched by setup, and local.yaml names it once
        assertThat(settings.lines().filter(line -> line.startsWith(SETTING)).count()).isEqualTo(1);

        RecordingOutput second = new RecordingOutput();
        Outcome again = runWithTheAttachments(cli, second);

        assertThat(again.failure()).isNull();
        assertThat(again.exitCode()).isZero();
        assertThat(second.info()).contains("The attachment key " + keyId + " is active and its key file is there");
        assertThat(keyFiles()).containsExactly(keyId);
        assertThat(Files.readString(local())).isEqualTo(settings);
        assertThat(Files.readAllBytes(keyDirectory().resolve(keyId))).isEqualTo(key);
    }

    @Test
    @DisplayName("ATA-037 an existing local.yaml keeps every line it had (also when it ends without a line end): the setting is added to it, and the file still holds the other settings")
    void ata037_aLocalFileOfTheOwnerKeepsItsSettings(Cli cli) throws IOException {
        String owners = "ledger:\n  admin:\n    lockout:\n      global-delay: 5s";
        Files.writeString(local(), owners);

        Outcome made = runWithTheAttachments(cli, new RecordingOutput());

        assertThat(made.exitCode()).isZero();
        String keyId = keyFiles().getFirst();
        String settings = Files.readString(local());
        assertThat(settings).startsWith(owners + "\n");
        PropertySource<?> loaded = yaml(settings).getFirst();
        assertThat(loaded.getProperty("ledger.admin.lockout.global-delay")).isEqualTo("5s");
        assertThat(loaded.getProperty(SETTING)).isEqualTo(keyId);
    }

    @Test
    @DisplayName("ATA-037 a setting that names a key whose file is missing is a refusal naming the code and not a new key (a restore without the key directory is found, not hidden)")
    void ata037_aMissingKeyOfTheSettingIsRefused(Cli cli) throws IOException {
        RecordingOutput output = new RecordingOutput();

        Outcome result = run(cli, output, "--ledger.attachments.directory=" + tmp.resolve("data/attachments"),
                "--ledger.attachments.key-directory=" + keyDirectory(), "--" + SETTING + "=key-gone");

        assertThat(result.exitCode()).isEqualTo(ExitStatus.FAILURE.code());
        assertThat(result.code()).contains(ErrorCode.LEDGER_REFUSED);
        assertThat(result.refusal().orElseThrow().arguments().getFirst()).contains("ARTIFACT_KEY_MISSING");
        assertThat(keyFiles()).isEmpty();
        assertThat(local()).doesNotExist();
    }

    @Test
    @DisplayName("ATA-037 without the settings of the attachments setup makes no key and no file (an installation of an older kind is set up as before)")
    void ata037_withoutTheSettingsNothingIsMade(Cli cli) throws IOException {
        Outcome result = run(cli, new RecordingOutput());

        assertThat(result.exitCode()).isZero();
        assertThat(keyFiles()).isEmpty();
        assertThat(local()).doesNotExist();
    }
}
