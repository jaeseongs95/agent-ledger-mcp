// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.EncoderBase;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Programmatic Logback setup for the short-lived CLI hook process: matches {@code logback-spring.xml} (stderr
 * {@code ConsoleAppender}, UTF-8 pattern, root {@code WARN}) without loading Joran XML, {@code java.xml} SAX parsers,
 * {@code ApplicationConversionService}, or Logback's pattern compiler unless a log record is actually emitted.
 */
final class HookLoggingSystem {

    private static final String CONSOLE_APPENDER = "CONSOLE";
    private static final String STDERR_TARGET = "System.err";
    private static final String LOG_PATTERN = "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level %logger{36} - %msg%n";
    private static final String LEVEL_PREFIX = "logging.level.";
    private static final String ROOT_SUFFIX = "root";
    private static final String DEBUG_PROPERTY = "debug";

    private HookLoggingSystem() {}

    static void initialize(MutablePropertySources sources) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.stop();
        context.reset();
        LazyPatternEncoder encoder = new LazyPatternEncoder(context);
        encoder.start();
        ConsoleAppender<ILoggingEvent> appender = new ConsoleAppender<>();
        appender.setContext(context);
        appender.setName(CONSOLE_APPENDER);
        appender.setTarget(STDERR_TARGET);
        appender.setEncoder(encoder);
        appender.start();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.WARN);
        root.addAppender(appender);
        applyLevels(context, root, sources);
    }

    private static void applyLevels(LoggerContext context, Logger root, MutablePropertySources sources) {
        List<PropertySource<?>> ordered = new ArrayList<>();
        for (PropertySource<?> source : sources) {
            ordered.add(source);
        }
        // Apply from lowest precedence to highest precedence so -D system properties win.
        for (int i = ordered.size() - 1; i >= 0; i--) {
            if (!(ordered.get(i) instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            Object debug = enumerable.getProperty(DEBUG_PROPERTY);
            if (debug != null && "true".equalsIgnoreCase(debug.toString().strip())) {
                root.setLevel(Level.DEBUG);
            }
            for (String name : enumerable.getPropertyNames()) {
                if (!name.startsWith(LEVEL_PREFIX)) {
                    continue;
                }
                Object raw = enumerable.getProperty(name);
                if (raw == null) {
                    continue;
                }
                String loggerName = name.substring(LEVEL_PREFIX.length());
                Logger target = ROOT_SUFFIX.equalsIgnoreCase(loggerName) ? root : context.getLogger(loggerName);
                target.setLevel(Level.toLevel(raw.toString().strip().toUpperCase(Locale.ROOT), Level.WARN));
            }
        }
    }

    private static final class LazyPatternEncoder extends EncoderBase<ILoggingEvent> {

        private final LoggerContext loggerContext;
        private PatternLayoutEncoder delegate;

        LazyPatternEncoder(LoggerContext loggerContext) {
            this.loggerContext = loggerContext;
        }

        @Override
        public byte[] headerBytes() {
            return null;
        }

        @Override
        public byte[] encode(ILoggingEvent event) {
            return initialized().encode(event);
        }

        @Override
        public byte[] footerBytes() {
            return null;
        }

        private synchronized PatternLayoutEncoder initialized() {
            if (delegate == null) {
                PatternLayoutEncoder created = new PatternLayoutEncoder();
                created.setContext(loggerContext);
                created.setPattern(LOG_PATTERN);
                created.setCharset(StandardCharsets.UTF_8);
                created.start();
                delegate = created;
            }
            return delegate;
        }
    }
}
