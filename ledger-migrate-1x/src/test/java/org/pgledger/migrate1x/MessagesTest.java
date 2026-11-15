package org.pgledger.migrate1x;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.ErrorKey;
import org.pgledger.migrate1x.tool.MigrationError;
import org.pgledger.migrate1x.tool.ToolCommand;

/** M1X-021: the message bundles of the tool, English and Korean, say the same things with the same placeholders. */
class MessagesTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

    /**
     * The codes of {@code ledger-cli} that the commands of the tool can end in: the command line, the secret store and the
     * ledger database. Their texts are in the bundles of the tool, because the bundles of {@code ledger-cli} are not the tool's.
     */
    private static final List<ErrorCode> CLI_CODES = List.of(
            ErrorCode.MISSING_COMMAND, ErrorCode.UNKNOWN_COMMAND, ErrorCode.MISSING_ARGUMENT, ErrorCode.TOO_MANY_ARGUMENTS,
            ErrorCode.MISSING_OPTION_VALUE, ErrorCode.UNKNOWN_OPTION, ErrorCode.INVALID_OPTION_VALUE,
            ErrorCode.INVALID_SECRET_NAME, ErrorCode.NO_CONSOLE, ErrorCode.EMPTY_SECRET, ErrorCode.EMPTY_SECRET_FILE,
            ErrorCode.INVALID_ENCODING, ErrorCode.NON_POSIX_FILESYSTEM, ErrorCode.LOOSE_PERMISSIONS,
            ErrorCode.UNEXPECTED_FILE_TYPE, ErrorCode.UNKNOWN_PROCESS_USER, ErrorCode.PARENT_MISSING, ErrorCode.IO_FAILED,
            ErrorCode.SECRET_TOO_LONG, ErrorCode.STDIN_FAILED, ErrorCode.DRIVER_NOT_CONFIGURED, ErrorCode.LEDGER_REFUSED,
            ErrorCode.DATABASE_FAILED, ErrorCode.SCHEMA_NOT_READY);

    /** Every reason a command of the tool can be refused for: its own and the ones of {@code ledger-cli} it uses. */
    private static List<ErrorKey> reasons() {
        List<ErrorKey> reasons = new ArrayList<>(List.of(MigrationError.values()));
        reasons.addAll(CLI_CODES);
        return reasons;
    }

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
    @DisplayName("M1X-021 both bundles have the same keys; every error code and every command has its text; the placeholders of a key are the same in both languages")
    void m1x021_theBundlesAgree() throws IOException {
        Properties english = load("/messages.properties");
        Properties korean = load("/messages_ko.properties");

        assertThat(korean.stringPropertyNames()).containsExactlyInAnyOrderElementsOf(english.stringPropertyNames());
        for (ErrorKey code : reasons()) {
            assertThat(english).as(code.messageKey()).containsKey(code.messageKey());
            assertThat(korean).as(code.messageKey()).containsKey(code.messageKey());
        }
        for (ToolCommand command : ToolCommand.values()) {
            assertThat(english).as(command.usageKey()).containsKey(command.usageKey());
            assertThat(korean).as(command.usageKey()).containsKey(command.usageKey());
        }
        for (String key : english.stringPropertyNames()) {
            assertThat(placeholders(korean.getProperty(key))).as(key).isEqualTo(placeholders(english.getProperty(key)));
            assertThat(english.getProperty(key)).as(key).isNotBlank();
        }
    }

    @Test
    @DisplayName("M1X-021 every key of the bundle starts a usage text, a message of a command or an error: no key without a user")
    void m1x021_everyKeyHasAKnownKind() throws IOException {
        Properties english = load("/messages.properties");
        Set<String> commands = new TreeSet<>();
        for (ToolCommand command : ToolCommand.values()) {
            commands.add(command.cliName());
        }
        for (String key : english.stringPropertyNames()) {
            boolean known = key.equals("usage.header") || key.equals("owner-account.prompt") || key.startsWith("error.")
                    || commands.stream().anyMatch(command -> key.startsWith(command + ".") || key.equals("usage." + command));
            assertThat(known).as(key).isTrue();
        }
        for (ErrorKey code : reasons()) {
            assertThat(english).containsKey(code.messageKey());
        }
    }
}
