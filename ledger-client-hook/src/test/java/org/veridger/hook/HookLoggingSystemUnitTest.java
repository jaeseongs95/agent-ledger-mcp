// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Direct unit tests for {@link HookLoggingSystem} covering programmatic Logback console attachment,
 * {@link HookLoggingSystem.LogbackConfigurator}, relaxed env/property level resolution, precedence ordering, and
 * lazy pattern encoder initialization.
 */
class HookLoggingSystemUnitTest {

    @Test
    @DisplayName("HKS-LOG-01 LogbackConfigurator attaches lazy CONSOLE appender and encodes log events")
    void configuratorAttachesConsoleAndLazyEncoderFormatsEvent() {
        LoggerContext isolated = new LoggerContext();
        try {
            HookLoggingSystem.LogbackConfigurator configurator = new HookLoggingSystem.LogbackConfigurator();
            assertThat(configurator.configure(isolated))
                    .isEqualTo(Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY);

            Logger root = isolated.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            assertThat(root.getLevel()).isEqualTo(Level.WARN);
            assertThat(root.getAppender("CONSOLE")).isInstanceOf(ConsoleAppender.class);

            @SuppressWarnings("unchecked")
            ConsoleAppender<ILoggingEvent> appender = (ConsoleAppender<ILoggingEvent>) root.getAppender("CONSOLE");
            assertThat(appender.getEncoder().headerBytes()).isNull();
            assertThat(appender.getEncoder().footerBytes()).isNull();

            LoggingEvent event = new LoggingEvent("fqcn", isolated.getLogger("org.veridger.hook.Test"),
                    Level.WARN, "lazy encoder probe", null, null);
            byte[] encoded = appender.getEncoder().encode(event);
            String rendered = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(encoded)).toString();
            assertThat(rendered).contains("WARN").contains("lazy encoder probe");
            // Second encode reuses initialized delegate
            assertThat(appender.getEncoder().encode(event)).isNotEmpty();
        } finally {
            isolated.stop();
        }
    }

    @Test
    @DisplayName("HKS-LOG-02 initialize attaches console if absent and applies debug, logging.level.*, and LOGGING_LEVEL_* in precedence order")
    void initializeAppliesLevelsAndPrecedence() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level savedRoot = root.getLevel();
        try {
            // Exercise both paths: when CONSOLE is missing and when CONSOLE already exists
            root.detachAppender("CONSOLE");

            Map<String, Object> highPrecedence = new LinkedHashMap<>();
            highPrecedence.put("logging.level.org.veridger.hook.sample", "ERROR");
            highPrecedence.put("logging.level.org.veridger.hook.nullval", null);
            highPrecedence.put("unrelated.key", "ignored");

            Map<String, Object> lowPrecedence = new LinkedHashMap<>();
            lowPrecedence.put("debug", "true");
            lowPrecedence.put("logging.level.root", "INFO");
            lowPrecedence.put("logging.level.org.veridger.hook.sample", "TRACE");
            lowPrecedence.put("LOGGING_LEVEL_ORG_VERIDGER_HOOK_ENVTEST", "DEBUG");

            MutablePropertySources sources = new MutablePropertySources();
            sources.addLast(new PropertySource<Object>("nonEnumerable", new Object()) {
                @Override
                public Object getProperty(String name) {
                    return null;
                }
            });
            sources.addLast(new MapPropertySource("high", highPrecedence));
            sources.addLast(new MapPropertySource("low", lowPrecedence));

            HookLoggingSystem.initialize(sources);
            assertThat(root.getAppender("CONSOLE")).isNotNull();
            assertThat(root.getLevel()).isEqualTo(Level.INFO);
            assertThat(context.getLogger("org.veridger.hook.sample").getLevel()).isEqualTo(Level.ERROR);
            assertThat(context.getLogger("org.veridger.hook.envtest").getLevel()).isEqualTo(Level.DEBUG);

            // Second call takes the fast path (CONSOLE already attached) with debug=true only
            MutablePropertySources debugOnly = new MutablePropertySources();
            debugOnly.addLast(new MapPropertySource("dbg", Map.of("debug", "true")));
            HookLoggingSystem.initialize(debugOnly);
            assertThat(root.getLevel()).isEqualTo(Level.DEBUG);
        } finally {
            root.setLevel(savedRoot);
        }
    }
}
