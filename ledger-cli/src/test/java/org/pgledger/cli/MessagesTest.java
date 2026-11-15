package org.pgledger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class MessagesTest {

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = new ClassPathResource(name).getInputStream()) {
            properties.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        }
        return properties;
    }

    @Test
    @DisplayName("CLI-001 every error code, command and fixed text has a message in the default and the Korean bundle")
    void everyKeyIsDefinedInBothBundles() throws IOException {
        List<String> required = new ArrayList<>(List.of("usage.header", "secret-set.prompt", "secret-set.stored",
                "key-register.registered", "key-register.replaced", "key-register.server", "key-revoke.revoked",
                "migrate.applied", "migrate.current", "driver-register.registered", "project-add.added", "code-set.set",
                "codes.line", "codes.line.relation", "grant.issued", "grant.never", "revoke.revoked", "grants.none", "grants.line", "mcp-check.ok",
                "attachments-check.summary", "attachments-check.files", "attachments-check.missing", "attachments-check.damaged",
                "attachments-check.unreferenced", "attachments-check.purged", "attachments-rekey.done", "attachments-key-create.created"));
        for (ErrorCode code : ErrorCode.values()) {
            required.add(code.messageKey());
        }
        for (CliCommand command : CliCommand.values()) {
            required.add(command.usageKey());
        }

        Properties base = load("messages.properties");
        Properties korean = load("messages_ko.properties");

        assertThat(base.stringPropertyNames()).containsAll(required);
        assertThat(korean.stringPropertyNames()).isEqualTo(base.stringPropertyNames());
    }

    @Test
    @DisplayName("SFR-016 the messages of the refusals of the read rule name the path or the secret ({0}) in both languages, and the owner of an existing file is no reason of a refusal any more (no FOREIGN_OWNER)")
    void readRuleMessagesNameTheirSubject() throws IOException {
        for (ErrorCode code : List.of(ErrorCode.EMPTY_SECRET_FILE, ErrorCode.UNEXPECTED_FILE_TYPE)) {
            assertThat(load("messages.properties").getProperty(code.messageKey())).as("en %s", code).contains("{0}");
            assertThat(load("messages_ko.properties").getProperty(code.messageKey())).as("ko %s", code).contains("{0}");
        }
        assertThat(ErrorCode.values()).extracting(Enum::name).doesNotContain("FOREIGN_OWNER");
        assertThat(load("messages.properties").stringPropertyNames()).doesNotContain("error.FOREIGN_OWNER");
    }
}
