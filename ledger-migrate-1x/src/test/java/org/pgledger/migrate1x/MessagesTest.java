package org.pgledger.migrate1x;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.migrate1x.tool.ErrorCode;
import org.pgledger.migrate1x.tool.ToolCommand;

/** M1X-021: the message bundles of the tool, English and Korean, say the same things with the same placeholders. */
class MessagesTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");

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
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(english).as(code.messageKey()).containsKey(code.messageKey());
        }
        for (ToolCommand command : ToolCommand.values()) {
            assertThat(english).as(command.usageKey()).containsKey(command.usageKey());
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
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(english).containsKey(code.messageKey());
        }
    }
}
