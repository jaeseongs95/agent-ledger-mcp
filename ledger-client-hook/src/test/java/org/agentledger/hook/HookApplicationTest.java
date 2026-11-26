// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.hook.resident.ResidentRunner;
import org.agentledger.testing.CodingScan;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

/**
 * HK-049: the hook is a Spring Boot application without a web server, with one entry (an {@code ApplicationRunner}) and
 * its exit code reported through an {@code ExitCodeGenerator}. The context is the one of the application with its
 * {@code application.properties}; the runner itself is not started by the context runner (it reads the process streams: the
 * tests of the real jar run it, see {@link StartupOutputTest}).
 */
class HookApplicationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(HookApplication.class);

    @Test
    @DisplayName("HK-049 the application has exactly one ApplicationRunner (ResidentRunner), no CommandLineRunner, and HookExitCode as its ExitCodeGenerator")
    void oneEntryAndTheExitCodeGenerator() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(ApplicationRunner.class).values()).singleElement().isInstanceOf(ResidentRunner.class);
            assertThat(context.getBeansOfType(CommandLineRunner.class)).isEmpty();
            assertThat(context.getBeansOfType(ExitCodeGenerator.class).values()).anyMatch(HookExitCode.class::isInstance);
        });
    }

    @Test
    @DisplayName("HK-049 the exit code of the process is the one the runner recorded: 0 until then, and SpringApplication.exit returns it")
    void exitCodeIsWhatTheRunnerRecorded() {
        runner.run(context -> {
            HookExitCode exitCode = context.getBean(HookExitCode.class);
            assertThat(exitCode.getExitCode()).isZero();
            exitCode.record(2);
            assertThat(SpringApplication.exit(context)).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("HK-049 no web server, no banner and no startup log line: the settings of application.properties")
    void quietCommandLineProgram() {
        runner.run(context -> {
            Environment environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
            assertThat(environment.getProperty("spring.main.banner-mode")).isEqualTo("off");
            assertThat(environment.getProperty("spring.main.log-startup-info")).isEqualTo("false");
        });
    }

    @Test
    @DisplayName("R4-F2 Main bootstrap preserves DefaultApplicationArguments LSP, SpringApplication.exit, and zero internal bean-name surgery")
    void mainBootstrapFollowsStandardSpringContract() throws Exception {
        ApplicationArguments parsed = new DefaultApplicationArguments("--debug", "claude");
        assertThat(parsed.containsOption("debug")).isTrue();
        assertThat(parsed.getOptionNames()).containsExactly("debug");
        assertThat(parsed.getNonOptionArgs()).containsExactly("claude");
        assertThat(parsed.getSourceArgs()).containsExactly("--debug", "claude");

        int status = Main.execute(Main.jarOnlyEnvironment(), Main.class.getClassLoader(), new String[] {"--unknown-flag"});
        assertThat(status).isEqualTo(2);

        String mainSource = CodingScan.strip(Path.of("src", "main", "java", "org", "agentledger", "hook", "Main.java"));
        assertThat(mainSource)
                .doesNotContain("removeBeanDefinition")
                .doesNotContain("AotHookApplicationContext")
                .doesNotContain("RawApplicationArguments")
                .contains("DefaultApplicationArguments")
                .contains("SpringApplication.exit");
    }
}
