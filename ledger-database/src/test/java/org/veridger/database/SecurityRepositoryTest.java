// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.veridger.database.repository.Counter;
import org.veridger.database.repository.EntityLookupRepository;
import org.veridger.database.repository.GrantStateRepository;
import org.veridger.database.repository.IdAllocator;
import org.veridger.database.repository.MergeChainRepository;
import org.veridger.database.repository.PasskeyRepository;
import org.veridger.database.repository.SessionGrantRepository;
import org.veridger.database.support.DatabaseTest;
import org.veridger.database.support.LedgerTestContext;
import org.veridger.database.support.Seed;
import org.veridger.domain.model.Issue;
import org.veridger.domain.model.OwnerPasskey;
import org.veridger.domain.model.Project;
import org.veridger.domain.model.SessionGrant;
import org.veridger.domain.rule.Scope;
import org.veridger.domain.value.AuthorId;

/**
 * Regression tests for v4.3.1 database security fixes:
 * AW-01 (monotonic signatureCount in V13 owner_passkey_counter), APP-02 (exact-case verification in PasskeyRepository
 * and GrantStateRepository), and APP-03 (chunked IN queries above 500 IDs in EntityLookupRepository and
 * MergeChainRepository).
 */
@DatabaseTest
class SecurityRepositoryTest {

    private LedgerTestContext ctx;
    private Seed seed;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
    }

    @Test
    @DisplayName("SEC-AW-01 / APP-02: PasskeyRepository advances signatureCount monotonically and enforces exact-case credential lookup")
    void aw01_andApp02_passkeyCounterAndExactCase() throws Exception {
        PasskeyRepository repo = ctx.bean(PasskeyRepository.class);
        String id = "CredMixedCase-" + UUID.randomUUID().toString().replace("-", "");
        Instant t0 = LedgerTestContext.START;
        OwnerPasskey passkey = new OwnerPasskey(id, "UserHandleCase", "cHVibGljLWtleQ", 2L, 1, 0, 0, "usb",
                "YXR0ZXN0", "yubikey", "AABB-CCDD-EEFF-0011", "cli:owner", t0);
        ctx.tx(() -> {
            repo.insert(passkey);
            return null;
        });

        assertThat(repo.exists(id)).isTrue();
        assertThat(repo.exists(id.toLowerCase(Locale.ROOT))).isFalse();
        assertThat(repo.findLive(id.toLowerCase(Locale.ROOT))).isEmpty();
        assertThat(repo.findLive(id)).get().extracting(OwnerPasskey::signatureCount).isEqualTo(2L);

        // Advance monotonically to 7
        assertThat(ctx.tx(() -> repo.advanceSignatureCount(id, 7L, t0.plusSeconds(10)))).isTrue();
        assertThat(repo.findLive(id)).get().extracting(OwnerPasskey::signatureCount).isEqualTo(7L);

        // Rollback attempt to 4 and equal non-zero counter 7 must both return false and not regress stored 7
        assertThat(ctx.tx(() -> repo.advanceSignatureCount(id, 4L, t0.plusSeconds(20)))).isFalse();
        assertThat(ctx.tx(() -> repo.advanceSignatureCount(id, 7L, t0.plusSeconds(25)))).isFalse();
        assertThat(repo.findLive(id)).get().extracting(OwnerPasskey::signatureCount).isEqualTo(7L);

        // owner_passkey itself remains append-only (original column untouched)
        long baseCount = ctx.bean(JdbcClient.class)
                .sql("SELECT signature_count FROM owner_passkey WHERE credential_id = :id")
                .param("id", id).query(Long.class).single();
        assertThat(baseCount).isEqualTo(2L);

        // Concurrent replay race on counter 8: exactly one caller succeeds, zero SQL/25P02 errors
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var latch = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    latch.await();
                    return ctx.tx(() -> repo.advanceSignatureCount(id, 8L, t0.plusSeconds(30)));
                }));
            }
            latch.countDown();
            long wins = 0;
            for (var f : futures) {
                if (Boolean.TRUE.equals(f.get())) {
                    wins++;
                }
            }
            assertThat(wins).isEqualTo(1L);
            assertThat(repo.findLive(id)).get().extracting(OwnerPasskey::signatureCount).isEqualTo(8L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("SEC-APP-02: GrantStateRepository verifies exact-case author and active_key on findLive, hasLive, markUsed, and revokeLive")
    void app02_grantStateExactCase() {
        Project project = seed.project();
        SessionGrantRepository grants = ctx.bean(SessionGrantRepository.class);
        GrantStateRepository repo = ctx.bean(GrantStateRepository.class);
        AuthorId author = AuthorId.of("codex:CaseAuthor-" + UUID.randomUUID().toString().substring(0, 8));
        AuthorId lowerAuthor = AuthorId.of(author.value().toLowerCase(Locale.ROOT));
        Instant now = LedgerTestContext.START;
        String key = SessionGrant.liveKey(author, project.id(), Scope.ISSUE);
        long id = ctx.tx(() -> ctx.bean(IdAllocator.class).next(Counter.SESSION_GRANT));
        SessionGrant grant = new SessionGrant(id, author, project.id(), Scope.ISSUE, AuthorId.of("user:owner"),
                now, now.plusSeconds(3600), null, null, "test grant", key);
        ctx.tx(() -> grants.insert(grant));

        assertThat(repo.findLive(author, now)).hasSize(1);
        assertThat(repo.findLive(lowerAuthor, now)).isEmpty();
        assertThat(repo.hasLive(author, project.id(), Scope.ISSUE, now)).isTrue();
        assertThat(repo.hasLive(lowerAuthor, project.id(), Scope.ISSUE, now)).isFalse();
        assertThat(ctx.tx(() -> repo.markUsed(lowerAuthor, project.id(), Scope.ISSUE, now.plusSeconds(1)))).isFalse();
        assertThat(ctx.tx(() -> repo.markUsed(author, project.id(), Scope.ISSUE, now.plusSeconds(1)))).isTrue();

        // Wrong-case revokeLive does not close the grant
        int wrongCaseRevoked = ctx.tx(() -> repo.revokeLive(lowerAuthor, project.id(), Scope.ISSUE, now.plusSeconds(5)));
        assertThat(wrongCaseRevoked).isZero();
        assertThat(repo.findLive(author, now)).hasSize(1);

        // Exact-case revokeLive closes it cleanly
        int exactRevoked = ctx.tx(() -> repo.revokeLive(author, project.id(), Scope.ISSUE, now.plusSeconds(5)));
        assertThat(exactRevoked).isEqualTo(1);
        assertThat(repo.findLive(author, now)).isEmpty();
    }

    @Test
    @DisplayName("SEC-APP-03: EntityLookupRepository and MergeChainRepository handle >500 IDs cleanly via chunking")
    void app03_largeInClausesAreChunked() {
        Project project = seed.project();
        Issue issue = seed.issue(project, 1, "chunk target", LedgerTestContext.START);
        List<String> ids = new ArrayList<>(IntStream.range(0, 650)
                .mapToObj(i -> "MISSING-I-" + String.format("%04d", i + 1000))
                .toList());
        ids.add(issue.id().value());

        EntityLookupRepository lookup = ctx.bean(EntityLookupRepository.class);
        assertThat(lookup.issues(ids)).extracting(EntityLookupRepository.Found::id)
                .containsExactly(issue.id().value());

        MergeChainRepository mergeChain = ctx.bean(MergeChainRepository.class);
        assertThat(mergeChain.mergedInto(issue.id())).isEmpty();
    }
}
