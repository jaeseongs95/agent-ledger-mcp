// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.admin.GrantService;
import org.veridger.application.admin.GrantService.GrantCommand;
import org.veridger.application.admin.GrantService.RevokeCommand;
import org.veridger.application.admin.ProjectService;
import org.veridger.application.admin.ProjectService.ProjectCommand;
import org.veridger.application.gate.Target;
import org.veridger.application.gate.WriteGate;
import org.veridger.application.support.HeldTransaction;
import org.veridger.application.support.LedgerServiceTest;
import org.veridger.application.support.Parallel;
import org.veridger.application.support.Scenario;
import org.veridger.database.repository.Counter;
import org.veridger.database.repository.IdAllocator;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.AuthorId;
import org.veridger.domain.value.ProjectId;
import org.veridger.testing.TestOwners;
import org.veridger.testing.database.LockWaits;

/**
 * SVC-LOCK-01 .. SVC-LOCK-03: the order of the locks of the session grants, made deterministic by the lock of the database
 * (a second connection holds one row, the statement under test is waited for, and the rows it holds are read from a third
 * connection) and not by the clock. No test mocks a lock order and none retries a deadlock.
 */
@LedgerServiceTest
class GrantLockOrderTest {

    /**
     * Project ids of every kind of character a project id may have, in the order of their bytes (the order of {@code String}):
     * upper case before {@code _} before lower case, and {@code a-b} before {@code alpha} because {@code -} is below
     * {@code l}. A database that sorts with a linguistic collation (for example en_US.UTF-8) orders them differently, which
     * is why the order of the locks must not be the one of an {@code ORDER BY}.
     */
    private static final List<String> BYTE_ORDER = List.of("Zeta", "_x", "a-b", "alpha");

    /** The scopes granted on every project, in the order of their names. */
    private static final List<Scope> SCOPES = List.of(Scope.ISSUE, Scope.TASK);

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    private static List<String> projects(Scenario s) {
        String suffix = suffix();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < BYTE_ORDER.size(); i++) {
            String id = BYTE_ORDER.get(i) + suffix;
            s.bean(ProjectService.class).add(new ProjectCommand(id, "LK" + suffix.toUpperCase() + i, "lock order " + id),
                    TestOwners.OWNER);
            ids.add(id);
        }
        return ids;
    }

    /** A new author with a live grant of every scope of {@link #SCOPES} on every project, and the ids of those rows in lock order. */
    private record Grants(String author, List<String> projects, List<Long> rowsInLockOrder) {
    }

    private static Grants granted(Scenario s, List<String> projects) {
        String author = "claude:lo" + suffix();
        s.ctx().enroll(author);
        GrantService grants = s.bean(GrantService.class);
        List<String> shuffled = new ArrayList<>(projects);
        Collections.reverse(shuffled);
        for (String project : shuffled) {
            grants.grant(new GrantCommand(author, project, SCOPES.stream().map(Scope::token).toList(), null, null),
                    TestOwners.OWNER);
        }
        List<Long> rows = new ArrayList<>();
        for (String project : projects) {
            for (Scope scope : SCOPES) {
                rows.add(s.ctx().jdbc().sql("SELECT id FROM session_grant WHERE author_id = :author "
                        + "AND project_id = :project AND grant_scope = :scope AND revoked_at IS NULL")
                        .param("author", author).param("project", project).param("scope", scope.name())
                        .query(Long.class).single());
            }
        }
        return new Grants(author, projects, rows);
    }

    /**
     * Starts {@code operation}, makes it wait for the row {@code rows.get(heldIndex)} that a second connection holds, and returns
     * the rows that are locked at that moment (read from a third connection with SKIP LOCKED, which skips every row that is
     * locked): the held row and the rows the operation locked before it came to the held one. The operation ends after the
     * second connection commits.
     */
    private static List<Long> lockedWhileWaitingFor(Scenario s, List<Long> rows, int heldIndex, Runnable operation)
            throws Exception {
        HeldTransaction<Integer> holder = HeldTransaction.start(s.ctx(), () -> s.ctx().jdbc()
                .sql("UPDATE session_grant SET note = note WHERE id = :id").param("id", rows.get(heldIndex)).update());
        int holderPid = holder.awaitHeld();
        Future<?> running = Parallel.start(() -> {
            operation.run();
            return null;
        });
        LockWaits.awaitWaitingFor(s.ctx().jdbc(), holderPid);
        List<Long> free = s.ctx().tx(() -> s.ctx().jdbc()
                .sql("SELECT id FROM session_grant WHERE id IN (:ids) FOR UPDATE SKIP LOCKED").param("ids", rows)
                .query(Long.class).list());
        holder.release();
        running.get(60, TimeUnit.SECONDS);
        return rows.stream().filter(row -> !free.contains(row)).toList();
    }

    @Test
    @DisplayName("SVC-LOCK-01: a revocation without a project locks the live grants in the order of the write gate: project by its characters, then scope, whatever the collation of the database")
    void lock01_revocationLocksInTheOrderOfTheWriteGate(Scenario s) throws Exception {
        List<String> projects = projects(s);
        GrantService grants = s.bean(GrantService.class);
        WriteGate gate = s.bean(WriteGate.class);
        for (int heldIndex = 1; heldIndex < projects.size() * SCOPES.size(); heldIndex++) {
            int held = heldIndex;
            Grants revoked = granted(s, projects);
            assertThat(lockedWhileWaitingFor(s, revoked.rowsInLockOrder(), held, () -> grants.revoke(
                    new RevokeCommand(revoked.author(), null, null), TestOwners.OWNER)))
                    .as("the revocation waits for the row %d of the lock order, so it holds exactly the rows before it", held)
                    .containsExactlyElementsOf(revoked.rowsInLockOrder().subList(0, held + 1));

            Grants gated = granted(s, projects);
            List<Target> targets = new ArrayList<>();
            for (String project : gated.projects().reversed()) {
                SCOPES.reversed().forEach(scope -> targets.add(new Target(ProjectId.of(project), scope)));
            }
            assertThat(lockedWhileWaitingFor(s, gated.rowsInLockOrder(), held, () -> s.ctx().tx(() -> {
                gate.authorizeAll(AuthorId.of(gated.author()), targets);
                return null;
            }))).as("the write gate waits for the row %d of the lock order, so it holds exactly the rows before it", held)
                    .containsExactlyElementsOf(gated.rowsInLockOrder().subList(0, held + 1));
        }
    }

    private static final List<String> COUNTERS = List.of(Counter.OWNER_AUDIT.name(), Counter.SESSION_GRANT.name());

    private static Runnable directGrant(Scenario s) {
        String author = "claude:wk" + suffix();
        GrantService grants = s.bean(GrantService.class);
        GrantCommand command = new GrantCommand(author, s.projectId(), List.of("issue", "task"), null, null);
        return () -> grants.grant(command, TestOwners.OWNER);
    }

    /**
     * The first step of every grant: the project row, pinned as {@code GrantService.issueAll} pins it, and held until the
     * test lets go; then, like the same grant, it takes the number of the owner's audit row ({@code takesAuditNumber}).
     */
    private static Future<?> projectHolder(Scenario s, CountDownLatch pinned, AtomicInteger pid, CountDownLatch go,
            boolean takesAuditNumber) {
        return Parallel.start(() -> s.ctx().tx(() -> {
            s.ctx().jdbc().sql("UPDATE project SET version = version WHERE id = :project")
                    .param("project", s.projectId()).update();
            pid.set(LockWaits.backendPid(s.ctx().jdbc()));
            pinned.countDown();
            try {
                if (!go.await(60, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the holder of the project was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while the project was held", interrupted);
            }
            return takesAuditNumber ? s.bean(IdAllocator.class).next(Counter.OWNER_AUDIT) : 0L;
        }));
    }

    @Test
    @DisplayName("SVC-LOCK-02: a grant holds no counter row of the owner while it waits for the project row: project, then grant rows, then numbers")
    void lock02_grantHoldsNoNumberWhileItWaitsForTheProject(Scenario s) throws Exception {
        Runnable grantCall = directGrant(s);
        CountDownLatch pinned = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        Future<?> holder = projectHolder(s, pinned, holderPid, go, false);
        assertThat(pinned.await(60, TimeUnit.SECONDS)).isTrue();
        Future<?> running = Parallel.start(() -> {
            grantCall.run();
            return null;
        });
        LockWaits.awaitWaitingFor(s.ctx().jdbc(), holderPid.get());

        List<String> free = s.ctx().tx(() -> s.ctx().jdbc()
                .sql("SELECT name FROM id_counter WHERE name IN (:names) FOR UPDATE SKIP LOCKED")
                .param("names", COUNTERS).query(String.class).list());
        go.countDown();
        assertThat(free).as("a grant that waits for the project row holds no number row")
                .containsExactlyInAnyOrderElementsOf(COUNTERS);
        holder.get(60, TimeUnit.SECONDS);
        running.get(60, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("SVC-LOCK-03: two grants contending on project and owner audit counters never deadlock")
    void lock03_concurrentGrantsNeverDeadlock(Scenario s) throws Exception {
        Runnable grantCall = directGrant(s);
        CountDownLatch pinned = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        Future<?> holder = projectHolder(s, pinned, holderPid, go, true);
        assertThat(pinned.await(60, TimeUnit.SECONDS)).isTrue();
        Future<?> running = Parallel.start(() -> {
            grantCall.run();
            return null;
        });
        LockWaits.awaitWaitingFor(s.ctx().jdbc(), holderPid.get());
        go.countDown();
        holder.get(60, TimeUnit.SECONDS);
        running.get(60, TimeUnit.SECONDS);
        assertThat(running.isDone() && holder.isDone()).isTrue();
    }
}
