// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pgledger.application.admin.GrantService;
import org.pgledger.application.admin.GrantService.GrantCommand;
import org.pgledger.application.admin.GrantService.RevokeCommand;
import org.pgledger.application.admin.ProjectService;
import org.pgledger.application.admin.ProjectService.ProjectCommand;
import org.pgledger.application.admin.SessionKeyService;
import org.pgledger.application.admin.SessionKeyService.RegisterCommand;
import org.pgledger.application.gate.Target;
import org.pgledger.application.gate.WriteGate;
import org.pgledger.application.support.HeldTransaction;
import org.pgledger.application.support.LedgerServiceTest;
import org.pgledger.application.support.Parallel;
import org.pgledger.application.support.Scenario;
import org.pgledger.apptest.TestKeyRing;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.ProjectId;
import org.pgledger.testing.TestOwners;
import org.pgledger.testing.database.LockWaits;

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

    private static final List<String> COUNTERS = List.of(Counter.OWNER_AUDIT.name(), Counter.SESSION_KEY.name(),
            Counter.SESSION_GRANT.name());

    /** The use case under test: the grant screen with a new key (false) or with the confirmed replacement of the key (true). */
    private static Runnable grantWithKey(Scenario s, boolean replacing) {
        String author = "claude:wk" + suffix();
        GrantService grants = s.bean(GrantService.class);
        GrantCommand command = new GrantCommand(author, s.projectId(), List.of("issue", "task"), null, null);
        String key = PublicKeys.encode(TestKeyRing.newPair().getPublic());
        if (!replacing) {
            return () -> grants.grantWithKey(command, key, TestOwners.OWNER);
        }
        KeyPair old = TestKeyRing.newPair();
        s.bean(SessionKeyService.class).register(new RegisterCommand(author, PublicKeys.encode(old.getPublic()), null),
                TestOwners.OWNER);
        return () -> grants.grantReplacingKey(command, key, PublicKeys.fingerprint(old.getPublic()), TestOwners.OWNER);
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("SVC-LOCK-02: a grant with a key (new, or replacing) holds no counter row of the owner while it waits for the project row: project, then grant rows, then numbers")
    void lock02_keyGrantHoldsNoNumberWhileItWaitsForTheProject(boolean replacing, Scenario s) throws Exception {
        Runnable keyGrant = grantWithKey(s, replacing);
        CountDownLatch pinned = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        Future<?> holder = projectHolder(s, pinned, holderPid, go, false);
        assertThat(pinned.await(60, TimeUnit.SECONDS)).isTrue();
        Future<?> running = Parallel.start(() -> {
            keyGrant.run();
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("SVC-LOCK-03: a grant with a key (new, or replacing) and a grant that pins the project and then takes the number of the owner's audit row both end: neither is a deadlock victim")
    void lock03_keyGrantAndPlainGrantNeverDeadlock(boolean replacing, Scenario s) throws Exception {
        Runnable keyGrant = grantWithKey(s, replacing);
        CountDownLatch pinned = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        Future<?> holder = projectHolder(s, pinned, holderPid, go, true);
        assertThat(pinned.await(60, TimeUnit.SECONDS)).isTrue();
        Future<?> running = Parallel.start(() -> {
            keyGrant.run();
            return null;
        });
        LockWaits.awaitWaitingFor(s.ctx().jdbc(), holderPid.get());
        go.countDown();
        // a deadlock (SQLState 40P01) makes the database end one of the two, and the get of its future throws
        holder.get(60, TimeUnit.SECONDS);
        running.get(60, TimeUnit.SECONDS);
        assertThat(running.isDone() && holder.isDone()).isTrue();
    }
}
