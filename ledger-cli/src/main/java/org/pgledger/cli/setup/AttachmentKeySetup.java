package org.pgledger.cli.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.pgledger.application.attachment.AttachmentKeys;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.cli.CliMessages;
import org.pgledger.cli.CliOutput;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.database.CliLedger;
import org.pgledger.cli.secret.SecretsProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The first key of the attachments, made by {@code setup} (common design 4.3): when the attachments are configured
 * ({@code ledger.attachments.directory}) and no key is active yet ({@code ledger.attachments.active-key-id} is not set anywhere),
 * it creates a 256-bit key file ({@link AttachmentKeys#create()}) and writes its id as {@code ledger.attachments.active-key-id} into
 * {@code local.yaml} of the installation (the settings file of the owner: every process of the product reads it, no run of the
 * installer replaces it, and a rotation of the key changes the setting there, in the one place). The order of an installation
 * follows from that: {@code prepare-host} makes the folders and renders {@code directory} and {@code key-directory} (it cannot
 * know a key id, and a render that is repeated must not lose the setting), {@code setup} makes the key and the setting.
 *
 * <p>It is idempotent: when the setting exists, the key file of it is checked and nothing is made; the next run of {@code setup}
 * therefore changes nothing. A run that stopped after the key file and before the setting leaves a key that no setting names; the
 * next run makes another one (an unused key is harmless and is deleted by the owner). Without the settings of the attachments
 * nothing is done. The id is printed, never the key.
 */
@Component
public class AttachmentKeySetup {

    static final String LOCAL_FILE = "local.yaml";
    static final String SETTING = "ledger.attachments.active-key-id";

    private final ObjectProvider<AttachmentKeys> keys;
    private final ObjectProvider<AttachmentProperties> properties;
    private final SecretsProperties secrets;
    private final CliOutput output;
    private final CliMessages messages;

    AttachmentKeySetup(ObjectProvider<AttachmentKeys> keys, ObjectProvider<AttachmentProperties> properties,
            SecretsProperties secrets, CliOutput output, CliMessages messages) {
        this.keys = keys;
        this.properties = properties;
        this.secrets = secrets;
        this.output = output;
        this.messages = messages;
    }

    /** Makes the first key and its setting when there is none; the schema is migrated (the door is {@code target}). */
    void ensure(CliLedger target) {
        AttachmentKeys attachmentKeys = keys.getIfAvailable();
        AttachmentProperties settings = properties.getIfAvailable();
        if (attachmentKeys == null || settings == null) {
            return;
        }
        if (settings.activeKeyId() != null) {
            // the key file of the setting has to be there (a restore without the key directory is found here, naming the id only)
            target.call(attachmentKeys::active);
            output.info(messages.get("setup.attachment-key.present", settings.activeKeyId()));
            return;
        }
        String keyId = target.call(attachmentKeys::create);
        Path file = secrets.home().resolve(LOCAL_FILE);
        try {
            write(file, keyId);
        } catch (IOException failure) {
            throw new FailureException(ErrorCode.ATTACHMENT_SETTING_FAILED, failure, file.toString(),
                    failure.getClass().getSimpleName(), keyId);
        }
        output.info(messages.get("setup.attachment-key.created", keyId, file.toString()));
    }

    /**
     * Adds the setting to {@code local.yaml}: the file is created when it is missing, and the line is appended (after a line end) to
     * a file that exists, whose other settings are not touched. The key is written as one key with dots, which a YAML file may hold
     * next to nested keys.
     */
    private static void write(Path file, String keyId) throws IOException {
        String line = SETTING + ": '" + keyId + "'\n";
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            String current = Files.readString(file, StandardCharsets.UTF_8);
            String separator = current.isEmpty() || current.endsWith("\n") ? "" : "\n";
            Files.writeString(file, separator + "# Added by setup: the key new uploads of the attachments are written with.\n" + line,
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } else {
            Files.writeString(file, "# The settings of the owner: setup and the installer never replace this file.\n"
                    + "# Added by setup: the key new uploads of the attachments are written with.\n" + line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        }
    }
}
