package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.repository.CodeRevisionRepository;
import org.pgledger.database.repository.DefectCaseLockRepository;
import org.pgledger.database.repository.GrantStateRepository;
import org.pgledger.database.repository.ReceiptCompletionRepository;
import org.pgledger.database.repository.RowLockRepository;
import org.pgledger.database.repository.SessionKeyStateRepository;
import org.pgledger.database.support.DatabaseTest;
import org.pgledger.database.support.LedgerTestContext;
import org.pgledger.database.support.Seed;
import org.pgledger.domain.model.Project;
import org.pgledger.domain.rule.Scope;
import org.pgledger.domain.value.ConditionId;
import org.pgledger.domain.value.DefectId;
import org.pgledger.domain.value.JsonDocument;
import org.pgledger.domain.value.RequestId;
import org.pgledger.domain.value.TaskId;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * TXF-006 (principle 4.4): the repository methods that lock a row, or that complete a write of the use case (the response of a
 * receipt, the bump of the code revision, the revocation of the live grants), run inside the transaction of the calling service
 * method. A repository opens no transaction and has no {@code @Transactional} (not even {@code MANDATORY}), so each method refuses
 * a call without one with Spring's {@link IllegalTransactionStateException}, as the id allocator does (TXD-003): a lock taken
 * outside a transaction would be released at once and protect nothing. Inside a transaction every method works (the repository
 * cases of the module, which run through {@code ctx.tx}).
 */
@DatabaseTest
class TransactionRequiredTest {

    private LedgerTestContext ctx;
    private Seed seed;

    @BeforeEach
    void setUp(LedgerTestContext context) {
        ctx = context;
        seed = new Seed(ctx);
    }

    @Test
    @DisplayName("TXF-006 every locking or completing repository method refuses a call with no transaction")
    void txf006_aCallWithoutATransactionIsRefused() {
        Project project = seed.project();
        RowLockRepository locks = ctx.bean(RowLockRepository.class);
        Class<IllegalTransactionStateException> refusal = IllegalTransactionStateException.class;
        Instant now = Instant.parse("2026-11-05T10:00:00Z");

        assertThatThrownBy(() -> locks.lock(DefectId.of(project.code().value() + "-D-1"))).isInstanceOf(refusal);
        assertThatThrownBy(() -> locks.lock(TaskId.of(project.code().value() + "-T-1"))).isInstanceOf(refusal);
        assertThatThrownBy(() -> locks.lock(ConditionId.of(project.code().value() + "-C-1"))).isInstanceOf(refusal);
        assertThatThrownBy(() -> ctx.bean(DefectCaseLockRepository.class)
                .lockForCase(DefectId.of(project.code().value() + "-D-1"))).isInstanceOf(refusal);
        GrantStateRepository grants = ctx.bean(GrantStateRepository.class);
        assertThatThrownBy(() -> grants.markUsed(Seed.ALICE, project.id(), Scope.DEFECT, now)).isInstanceOf(refusal);
        assertThatThrownBy(() -> grants.revokeLive(Seed.ALICE, project.id(), Scope.DEFECT, now)).isInstanceOf(refusal);
        assertThatThrownBy(() -> ctx.bean(SessionKeyStateRepository.class).holdLive(1L)).isInstanceOf(refusal);
        assertThatThrownBy(() -> ctx.bean(ReceiptCompletionRepository.class).finish(new RequestId(UUID.randomUUID()),
                JsonDocument.EMPTY_OBJECT)).isInstanceOf(refusal);
        assertThatThrownBy(() -> ctx.bean(CodeRevisionRepository.class).bump()).isInstanceOf(refusal);
    }

    @Test
    @DisplayName("TXF-006 the same methods work inside a transaction, and none of these repositories is a transaction boundary: no @Transactional on a class or a method")
    void txf006_insideATransactionTheyWork() {
        Project project = seed.project();
        CodeRevisionRepository revision = ctx.bean(CodeRevisionRepository.class);
        long before = revision.current();

        ctx.tx(() -> {
            revision.bump();
            return ctx.bean(RowLockRepository.class).lock(DefectId.of(project.code().value() + "-D-1"));
        });

        assertThat(revision.current()).isEqualTo(before + 1);
        for (Class<?> type : new Class<?>[] {RowLockRepository.class, DefectCaseLockRepository.class,
                GrantStateRepository.class, SessionKeyStateRepository.class, ReceiptCompletionRepository.class,
                CodeRevisionRepository.class}) {
            assertThat(type.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).as(type
                    .getSimpleName()).isFalse();
        }
    }
}
