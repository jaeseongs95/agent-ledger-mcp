// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.veridger.cli.support.EnglishMessages;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * M1X-009 (review C1): the failure analyzer asks for the translator after a failed start, possibly while a bean is still being
 * made (the password of the owner account is asked for while the data source is made). The translator must not need any command bean
 * for a refusal that is no usage error, or that start would fail a second time and the user would see a stack trace.
 */
@ExtendWith(EnglishMessages.class)
class CliErrorTranslatorTest {

    private static CliMessages messages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return new CliMessages(source);
    }

    /** The commands are looked up only when a usage text is made: asking before is a failure of the test. */
    private static final class UntouchedCommands implements ObjectProvider<Command> {

        int asked;

        @Override
        public Command getObject(Object... args) {
            asked++;
            throw new IllegalStateException("the commands were asked for");
        }

        @Override
        public Command getObject() {
            asked++;
            throw new IllegalStateException("the commands were asked for");
        }

        @Override
        public Command getIfAvailable() {
            asked++;
            throw new IllegalStateException("the commands were asked for");
        }

        @Override
        public Command getIfUnique() {
            asked++;
            throw new IllegalStateException("the commands were asked for");
        }

        @Override
        public java.util.stream.Stream<Command> stream() {
            asked++;
            throw new IllegalStateException("the commands were asked for");
        }

        @Override
        public java.util.stream.Stream<Command> orderedStream() {
            asked++;
            throw new IllegalStateException("the commands were asked for");
        }
    }

    @Test
    @DisplayName("M1X-009 (C1) the translator is made, and a refusal that is no usage error is translated, without asking for any command bean")
    void aFailureIsTranslatedWithoutTheCommandBeans() {
        UntouchedCommands commands = new UntouchedCommands();
        CliErrorTranslator translator = new CliErrorTranslator(messages(), commands);

        CliErrorTranslator.Report report = translator.translate(new FailureException(ErrorCode.EMPTY_SECRET));

        assertThat(report.status()).isEqualTo(ExitStatus.FAILURE);
        assertThat(report.description()).isNotBlank();
        assertThat(report.action()).isNull();
        assertThat(commands.asked).isZero();
    }

    @Test
    @DisplayName("M1X-009 the usage text of a refused command line is made from the commands that run, the header of their program first")
    void aUsageErrorListsTheCommandsThatRun() {
        Command only = new Command() {
            @Override
            public CommandName name() {
                return CliCommand.SECRET_SET;
            }

            @Override
            public void run(List<String> operands, org.springframework.boot.ApplicationArguments arguments) {
            }
        };
        ObjectProvider<Command> provider = new ObjectProvider<>() {
            @Override
            public Command getObject(Object... args) {
                return only;
            }

            @Override
            public Command getObject() {
                return only;
            }

            @Override
            public Command getIfAvailable() {
                return only;
            }

            @Override
            public Command getIfUnique() {
                return only;
            }

            @Override
            public java.util.stream.Stream<Command> stream() {
                return java.util.stream.Stream.of(only);
            }

            @Override
            public java.util.stream.Stream<Command> orderedStream() {
                return java.util.stream.Stream.of(only);
            }
        };

        CliErrorTranslator.Report report = new CliErrorTranslator(messages(), provider)
                .translate(new UsageException(ErrorCode.MISSING_COMMAND));

        assertThat(report.status()).isEqualTo(ExitStatus.USAGE);
        assertThat(report.action().lines().toList()).hasSize(2);
        assertThat(report.action()).startsWith("Usage: java -jar ledger-cli.jar").contains("secret-set");
    }
}
