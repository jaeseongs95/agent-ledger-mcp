package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.history.Snapshot;
import org.pgledger.application.support.Expect;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Scenario;
import org.pgledger.application.write.RecordCaseCommand;
import org.pgledger.application.write.RecordCaseService;
import org.pgledger.application.write.RecordDeliverableCommand;
import org.pgledger.application.write.RecordDeliverableService;
import org.pgledger.application.write.SaveService;
import org.pgledger.application.write.WriteResult.SaveResult;
import org.pgledger.apptest.LedgerTestRunners;
import org.pgledger.database.repository.DeliverableRepository;
import org.pgledger.database.repository.GrantStateRepository;
import org.pgledger.database.repository.IssueCaseRepository;
import org.pgledger.domain.error.ErrorCode;
import org.pgledger.domain.error.TrustCode;
import org.pgledger.domain.error.ValidationCode;
import org.pgledger.domain.rule.DeliverableTarget;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.IssueId;
import org.pgledger.testing.TestOwners;

/** SVC-TZ-02 .. SVC-TZ-05: UTC instant handling across time zones. */
@LedgerServiceTest
class TimeZoneServiceTest {

    private static final Instant NOON = Instant.parse("2026-11-05T10:00:00Z");

    @Test
    @DisplayName("SVC-TZ-02: with JVM and DB session in Asia/Seoul stored and returned instants are UTC")
    void tz02_otherZones() {
        TimeZone saved = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        try {
            LedgerTestRunners.inZone("Asia/Seoul", seoul -> {
                Scenario s = new Scenario(seoul);
                SaveResult i = s.newIssue("i");
                s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                        new RecordDeliverableCommand(Scenario.uuid(), "issue", i.id(), "code_change", Scenario.ref('a'),
                                Scenario.REASON, "repo/e2e", "Deliverable summary", "chk", "passed",
                                "2026-11-05T19:00:00+09:00", null));
                assertThat(s.issue(i.id()).createdAt()).isEqualTo(NOON);
                assertThat(s.bean(DeliverableRepository.class)
                        .findByTargetEntityAndTargetIdAndKindAndDeliverableRef(DeliverableTarget.ISSUE, i.id(),
                                CodeValue.of("code_change"), Scenario.ref('a'))
                        .orElseThrow().checkedAt()).isEqualTo(NOON);
                LocalDateTime wall = seoul.jdbc().sql("SELECT created_at FROM issue WHERE id = ?").param(i.id())
                        .query((rs, n) -> rs.getObject(1, LocalDateTime.class)).single();
                assertThat(wall).isEqualTo(LocalDateTime.of(2026, 11, 5, 10, 0, 0));
            });
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    static Stream<String> clockReadings() {
        return Stream.of("2026-11-05T14:59:59Z", "2026-11-05T15:00:00Z", "2026-11-05T23:59:59Z",
                "2026-11-06T00:00:00Z");
    }

    @ParameterizedTest
    @MethodSource("clockReadings")
    @DisplayName("SVC-TZ-03: rows are stamped with UTC clock")
    void tz03_stamping(String reading, Scenario s) {
        Instant now = Instant.parse(reading);
        s.ctx().clock().set(now);
        SaveResult i = s.newIssue("i");
        assertThat(s.issue(i.id()).createdAt()).isEqualTo(now);
        assertThat(s.issue(i.id()).updatedAt()).isEqualTo(now);
        assertThat(s.historyRow(i.historyId()).at()).isEqualTo(now);
    }

    @ParameterizedTest
    @MethodSource("clockReadings")
    @DisplayName("SVC-TZ-03: grant expiry at exact clock reading")
    void tz03_grantExpiry(String reading, Scenario s) {
        String actor = "claude:tz03";
        s.ctx().enroll(actor);
        s.ctx().clock().set(Instant.parse(reading).minusSeconds(1));
        s.bean(GrantService.class).grant(new GrantCommand(actor, s.projectId(), List.of("issue"), reading, null),
                TestOwners.OWNER);
        assertThat(Expect.outcome(() -> s.bean(SaveService.class).save(actor, s.create("issue", null,
                s.issueFields("before"))))).isEmpty();
        s.ctx().clock().set(Instant.parse(reading));
        assertThat(Expect.outcome(() -> s.bean(SaveService.class).save(actor, s.create("issue", null,
                s.issueFields("at"))))).contains(TrustCode.SESSION_NOT_GRANTED);
    }

    record TimedField(String name, BiFunction<Scenario, String, Instant> write) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<TimedField> timedFields() {
        return List.of(
                new TimedField("observed_at", (s, text) -> {
                    SaveResult i = s.newIssue("i");
                    s.bean(RecordCaseService.class).recordCase(Scenario.AUTHOR, new RecordCaseCommand(Scenario.uuid(),
                            i.id(), "seen", Scenario.REASON, null, null, text));
                    return s.bean(IssueCaseRepository.class).findByIssueIdOrderById(IssueId.of(i.id())).getFirst()
                            .observedAt();
                }),
                new TimedField("checked_at", (s, text) -> {
                    SaveResult i = s.newIssue("i");
                    s.bean(RecordDeliverableService.class).recordDeliverable(Scenario.AUTHOR,
                            new RecordDeliverableCommand(Scenario.uuid(), "issue", i.id(), "code_change",
                                    Scenario.ref('a'), Scenario.REASON, "repo/e2e", "Deliverable summary", "chk",
                                    "passed", text, null));
                    return s.bean(DeliverableRepository.class)
                            .findByTargetEntityAndTargetIdAndKindAndDeliverableRef(DeliverableTarget.ISSUE, i.id(),
                                    CodeValue.of("code_change"), Scenario.ref('a'))
                            .orElseThrow().checkedAt();
                }),
                new TimedField("grant expires_at", (s, text) -> {
                    String actor = s.actorWithoutGrants();
                    s.bean(GrantService.class).grant(new GrantCommand(actor, s.projectId(), List.of("issue"), text,
                            null), TestOwners.OWNER);
                    return s.bean(GrantStateRepository.class).findLive(AuthorId.of(actor), NOON.minusSeconds(60))
                            .getFirst().expiresAt();
                }));
    }

    static Stream<TimedField> everyTimedField() {
        return timedFields().stream();
    }

    @ParameterizedTest
    @MethodSource("everyTimedField")
    @DisplayName("SVC-TZ-04: Z and offset normalize to identical Instant")
    void tz04_sameInstant(TimedField field, Scenario s) {
        s.ctx().clock().set(NOON.minusSeconds(3600));
        Instant utc = field.write().apply(s, "2026-11-05T10:00:00Z");
        Instant offset = field.write().apply(s, "2026-11-05T19:00:00+09:00");
        assertThat(utc).isEqualTo(NOON);
        assertThat(offset).isEqualTo(NOON);
    }

    static Stream<Arguments> refusedTimes() {
        return timedFields().stream().flatMap(f -> Stream.of("2026-11-05T10:00:00", "2026-11-05")
                .map(text -> Arguments.of(f, text)));
    }

    @ParameterizedTest(name = "{0} with {1}")
    @MethodSource("refusedTimes")
    @DisplayName("SVC-TZ-04: time without zone and bare date are refused")
    void tz04_refused(TimedField field, String text, Scenario s) {
        s.ctx().clock().set(NOON.minusSeconds(3600));
        Optional<ErrorCode> outcome = Expect.outcome(() -> field.write().apply(s, text));
        assertThat(outcome).contains(ValidationCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("SVC-TZ-05: instants inside before_data end in Z")
    void tz05_beforeDataInstants(Scenario s) {
        SaveResult i = s.newIssue("i");
        SaveResult edited = s.bean(SaveService.class).save(Scenario.AUTHOR, s.edit("issue", i.id(), 1,
                Map.of("title", "x")));
        String json = s.historyRow(edited.historyId()).beforeData().text();
        assertThat(json).contains("\"created_at\":\"2026-11-05T10:00:00Z\"")
                .contains("\"updated_at\":\"2026-11-05T10:00:00Z\"");
        assertThat(s.before(edited.historyId(), Snapshot.IssueRow.class).createdAt()).isEqualTo(NOON);
    }
}
