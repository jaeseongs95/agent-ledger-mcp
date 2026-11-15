package org.pgledger.migrate1x;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.cli.ErrorCode;
import org.pgledger.migrate1x.tool.MigrationError;
import org.pgledger.migrate1x.tool.ToolCommand;

/**
 * M1X-021: the message bundle of the tool ({@code messages/migrate-1x}, English and Korean) holds the tool's own texts, says the same
 * things in both languages with the same placeholders, and repeats nothing of the bundle {@code messages} of {@code ledger-cli},
 * which is the second base name of the message source: the texts of the command line, the secret store and the ledger database are
 * read from there.
 */
class MessagesTest {

    private static final String TOOL = "/messages/migrate-1x";
    private static final String CLI = "/messages";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    /** The codes of {@code ledger-cli} that the commands of the tool can end in: their texts are in its bundle. */
    private static final List<ErrorCode> CLI_CODES = List.of(
            ErrorCode.MISSING_COMMAND, ErrorCode.UNKNOWN_COMMAND, ErrorCode.MISSING_ARGUMENT, ErrorCode.TOO_MANY_ARGUMENTS,
            ErrorCode.MISSING_OPTION_VALUE, ErrorCode.UNKNOWN_OPTION, ErrorCode.INVALID_OPTION_VALUE,
            ErrorCode.INVALID_SECRET_NAME, ErrorCode.UNKNOWN_SECRET_NAME, ErrorCode.NO_CONSOLE, ErrorCode.EMPTY_SECRET, ErrorCode.EMPTY_SECRET_FILE,
            ErrorCode.INVALID_ENCODING, ErrorCode.NON_POSIX_FILESYSTEM, ErrorCode.LOOSE_PERMISSIONS,
            ErrorCode.UNEXPECTED_FILE_TYPE, ErrorCode.UNKNOWN_PROCESS_USER, ErrorCode.PARENT_MISSING, ErrorCode.IO_FAILED,
            ErrorCode.SECRET_TOO_LONG, ErrorCode.STDIN_FAILED, ErrorCode.DRIVER_NOT_CONFIGURED, ErrorCode.LEDGER_REFUSED,
            ErrorCode.DATABASE_FAILED, ErrorCode.SCHEMA_NOT_READY);

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = MessagesTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as(resource).isNotNull();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    @Test
    @DisplayName("M1X-021 both languages of the tool's bundle have the same keys with the same placeholders; every MigrationError, every command and the usage header has its text in both")
    void m1x021_theBundlesAgree() throws IOException {
        Properties english = load(TOOL + ".properties");
        Properties korean = load(TOOL + "_ko.properties");

        assertThat(korean.stringPropertyNames()).containsExactlyInAnyOrderElementsOf(english.stringPropertyNames());
        for (MigrationError code : MigrationError.values()) {
            assertThat(english).as(code.messageKey()).containsKey(code.messageKey());
            assertThat(korean).as(code.messageKey()).containsKey(code.messageKey());
        }
        for (ToolCommand command : ToolCommand.values()) {
            assertThat(english).as(command.usageKey()).containsKey(command.usageKey());
            assertThat(korean).as(command.usageKey()).containsKey(command.usageKey());
            assertThat(english).as(command.usageHeaderKey()).containsKey(command.usageHeaderKey());
            assertThat(korean).as(command.usageHeaderKey()).containsKey(command.usageHeaderKey());
        }
        for (String key : english.stringPropertyNames()) {
            assertThat(placeholders(korean.getProperty(key))).as(key).isEqualTo(placeholders(english.getProperty(key)));
            assertThat(english.getProperty(key)).as(key).isNotBlank();
        }
    }

    @Test
    @DisplayName("M1X-021 the tool's bundle repeats no key of the bundle of ledger-cli, and every code of ledger-cli that the tool can throw has its text there, in both languages")
    void m1x021_nothingOfLedgerCliIsRepeated() throws IOException {
        for (String suffix : List.of(".properties", "_ko.properties")) {
            Properties tool = load(TOOL + suffix);
            Properties cli = load(CLI + suffix);

            assertThat(tool.stringPropertyNames()).as("keys of the tool that ledger-cli has" + suffix)
                    .doesNotContainAnyElementsOf(cli.stringPropertyNames());
            for (ErrorCode code : CLI_CODES) {
                assertThat(cli).as(code.messageKey() + suffix).containsKey(code.messageKey());
            }
        }
    }

    @Test
    @DisplayName("M1X-021 every key of the tool's bundle is a usage text, a message of a command or an error of the tool: no key without a user")
    void m1x021_everyKeyHasAKnownKind() throws IOException {
        Properties english = load(TOOL + ".properties");
        Set<String> commands = new TreeSet<>();
        for (ToolCommand command : ToolCommand.values()) {
            commands.add(command.cliName());
        }
        Set<String> errors = new TreeSet<>();
        for (MigrationError code : MigrationError.values()) {
            errors.add(code.messageKey());
        }
        for (String key : english.stringPropertyNames()) {
            boolean known = key.equals(ToolCommand.IMPORT_1X.usageHeaderKey()) || errors.contains(key)
                    || commands.stream().anyMatch(command -> key.startsWith(command + ".") || key.equals("usage." + command));
            assertThat(known).as(key).isTrue();
        }
    }
}
