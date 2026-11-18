package org.pgledger.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.pgledger.admin.support.AdminTestInfrastructure;
import org.pgledger.admin.support.AdminTestSettings;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.pgledger.testing.database.TestDatabaseScanFilter;
import org.pgledger.testing.database.TestDriverRegistration;
import org.springframework.boot.test.context.filter.annotation.TypeExcludeFilters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * ADM-070 (audit2 N1; changed by LGN-005, LGN-006): a flood of wrong tokens, from many clients or from one, slows the checks
 * down and never keeps the owner out, and no client is locked;
 * the budget of the global throttle is counted and decided in one atomic step, so checks that arrive together cannot
 * overshoot it. The budget is 8 checks per window and a check beyond it waits 400 ms.
 */
@ContextConfiguration(initializers = {TestSchemaInitializer.class, TestDriverRegistration.class})
@SpringBootTest(classes = LedgerAdminApplication.class, properties = {AdminTestSettings.TOKEN_PROPERTY,
    AdminTestSettings.AGENT_TOKEN_PROPERTY, "ledger.admin.lockout.global-max-failures=8",
    "ledger.admin.lockout.global-delay=400ms", AdminTestSettings.QUIET_BANNER, AdminTestSettings.QUIET_LOG,
    AdminTestSettings.NO_JMX})
@AutoConfigureMockMvc
@TypeExcludeFilters(TestDatabaseScanFilter.class)
@Import(AdminTestInfrastructure.class)
class GlobalThrottleTest {

    private static final String WRONG = "wrong-wrong-wrong-wrong";
    private static final int BUDGET = 8;
    private static final int THREADS = 64;
    /** A check that was delayed took at least this long (the delay is 400 ms; a margin for timer granularity). */
    private static final Duration DELAYED_AT_LEAST = Duration.ofMillis(250);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private org.springframework.core.env.Environment environment;

    private record Answer(int status, Duration took) {
    }

    private Answer attempt(String address, String token) throws Exception {
        long start = System.nanoTime();
        int status = mvc.perform(MockMvcRequestBuilders.get("/api/session").accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })).andReturn().getResponse().getStatus();
        return new Answer(status, Duration.ofNanos(System.nanoTime() - start));
    }

    @RepeatedTest(2)
    @DisplayName("ADM-070: 64 wrong tokens from 64 addresses at the same moment: all are answered 401 (never 429), and at most the budget of 8 is not delayed (the count and the decision are one atomic step)")
    void adm070_concurrentChecksCannotOvershootTheBudget(org.junit.jupiter.api.RepetitionInfo repetition) throws Exception {
        CyclicBarrier together = new CyclicBarrier(THREADS);
        List<Answer> answers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<Answer>> pending = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                String address = "10.201." + repetition.getCurrentRepetition() + "." + (i + 1);
                pending.add(pool.submit(() -> {
                    together.await();
                    return attempt(address, WRONG);
                }));
            }
            for (Future<Answer> answer : pending) {
                answers.add(answer.get());
            }
        }

        assertThat(answers).extracting(Answer::status).containsOnly(401);
        assertThat(answers.stream().filter(answer -> answer.took().compareTo(DELAYED_AT_LEAST) >= 0).count())
                .as("checks that waited: all but the budget (a check that is not delayed is quick; one that is slow only hides an overshoot, never invents one)")
                .isGreaterThanOrEqualTo(THREADS - BUDGET);
    }

    @Test
    @DisplayName("ADM-070 (changed by LGN-005): after a flood of wrong tokens from 12 clients (more than the budget of 8), the owner's right token from another client logs in (200, delayed), and a client that kept going is answered 401 each time, never 429, and its right token is 200")
    void adm070_theOwnerStillLogsInAfterTheFlood() throws Exception {
        for (int i = 1; i <= 12; i++) {
            assertThat(attempt("10.202." + (i / 250) + "." + (i % 250 + 1), WRONG).status()).isEqualTo(401);
        }

        Answer owner = attempt("10.203.0.1", AdminTestSettings.TOKEN);

        assertThat(owner.status()).isEqualTo(200);
        assertThat(owner.took()).isGreaterThanOrEqualTo(DELAYED_AT_LEAST);
        for (int again = 0; again < 5; again++) {
            assertThat(attempt("10.202.0.1", WRONG).status()).as("a client that goes on failing is not locked").isEqualTo(401);
        }
        assertThat(attempt("10.202.0.1", AdminTestSettings.TOKEN).status()).as("its right token still logs in")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("LGN-006: 64 wrong tokens of ONE address at the same moment: all are 401 (never 429), and at least 56 waited the delay (the address plays no part)")
    void lgn006_oneAddressIsNotLocked() throws Exception {
        CyclicBarrier together = new CyclicBarrier(THREADS);
        List<Answer> answers = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Future<Answer>> pending = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                pending.add(pool.submit(() -> {
                    together.await();
                    return attempt("10.204.0.1", WRONG);
                }));
            }
            for (Future<Answer> answer : pending) {
                answers.add(answer.get());
            }
        }

        assertThat(answers).extracting(Answer::status).containsOnly(401);
        assertThat(answers.stream().filter(answer -> answer.took().compareTo(DELAYED_AT_LEAST) >= 0).count())
                .isGreaterThanOrEqualTo(THREADS - BUDGET);
    }

    @Test
    @DisplayName("REM-006 (D-0013 / BLOCKING-L3): virtual threads are enabled so blocking LoginDelay.await does not tie up platform threads")
    void rem006_virtualThreadsEnabled() {
        assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean.class)).isTrue();
    }
}
