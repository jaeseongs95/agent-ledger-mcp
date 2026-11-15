package org.pgledger.application.admin;

import java.util.List;
import java.util.Optional;
import org.pgledger.database.repository.CodeRevisionRepository;
import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.CommonCode;
import org.pgledger.domain.rule.CodeDirection;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.CodeValue;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.domain.value.Tokens;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The extensible code lists. A code is added or updated (names, descriptions, order, state); it is never deleted
 * or renamed, because rows keep pointing at it, and there is no method that could. A change bumps the code
 * revision in the same transaction so other processes notice; setting a code to what it already is changes
 * nothing and does not bump.
 */
@Service
@Transactional
public class CommonCodeService {

    /** The default position of a code that has none. */
    public static final int DEFAULT_SORT_ORDER = 100;

    /**
     * A code to set; the English texts fall back to the Korean ones, the state to ACTIVE. The direction ({@code GIVEN} or
     * {@code CANONICAL}, see {@link CodeDirection}) is only for a code of the group RELATION_KIND; without one a new code is
     * CANONICAL and an existing code keeps its direction.
     */
    public record CodeCommand(String group, String code, String nameKo, String nameEn, String descriptionKo,
            String descriptionEn, Integer sortOrder, String state, String direction) {

        /** A command that does not name a direction. */
        public CodeCommand(String group, String code, String nameKo, String nameEn, String descriptionKo,
                String descriptionEn, Integer sortOrder, String state) {
            this(group, code, nameKo, nameEn, descriptionKo, descriptionEn, sortOrder, state, null);
        }
    }

    /** The code as stored and the revision after the call. */
    public record CodeResult(CommonCode code, long revision) {
    }

    private final CommonCodeRepository codes;
    private final CodeRevisionRepository revision;
    private final OwnerAuditWriter audit;
    private final LinkRepository links;

    public CommonCodeService(CommonCodeRepository codes, CodeRevisionRepository revision, OwnerAuditWriter audit,
            LinkRepository links) {
        this.codes = codes;
        this.revision = revision;
        this.audit = audit;
        this.links = links;
    }

    /** Sets the code and, when that changes anything, records who did it in the owner's audit trail. */
    public CodeResult set(CodeCommand command, OwnerIdentity actor) {
        CodeGroup group = Tokens.parse("group", command.group(), CodeGroup.values());
        CodeValue code = CodeValue.of(command.code());
        CodeState state = command.state() == null || command.state().isBlank() ? CodeState.ACTIVE
                : Tokens.parse("state", command.state(), CodeState.values());
        Optional<CommonCode> existing = codes.find(group, code);
        int sort = command.sortOrder() != null ? command.sortOrder()
                : existing.map(CommonCode::sortOrder).orElse(DEFAULT_SORT_ORDER);
        CodeDirection direction = directionOf(command, existing.map(CommonCode::direction).orElse(CodeDirection.CANONICAL));
        CommonCode wanted = new CommonCode(group, code, command.nameKo(), command.nameEn(),
                command.descriptionKo(), command.descriptionEn(), sort, state, direction,
                existing.map(CommonCode::createdBy).orElse(AuthorId.OWNER));
        if (existing.filter(wanted::equals).isPresent()) {
            // the code is what the owner asked for already: nothing to write, no revision, no audit row
            return new CodeResult(wanted, revision.current());
        }
        existing.ifPresent(current -> requireDirectionNotInUse(current, wanted));
        if (existing.isPresent()) {
            codes.update(wanted);
        } else {
            codes.insert(wanted);
        }
        revision.bump();
        audit.record(actor, OwnerAction.CODE_SET, group.name() + "/" + code.value(),
                (existing.isEmpty() ? "added" : "changed") + " state=" + state.name());
        return new CodeResult(wanted, revision.current());
    }

    /**
     * Adds the code when it does not exist, with the revision bump and the audit row of {@link #set}; a code that exists is left
     * exactly as it is (nothing is written, not even by a command that would not change it). The first setup of an installation
     * adds the codes of the seed through this, so it can never overwrite a code that the owner changed or retired.
     *
     * @return the new code and the revision, or empty when the code exists
     */
    public Optional<CodeResult> addIfAbsent(CodeCommand command, OwnerIdentity actor) {
        CodeGroup group = Tokens.parse("group", command.group(), CodeGroup.values());
        CodeValue code = CodeValue.of(command.code());
        if (codes.find(group, code).isPresent()) {
            return Optional.empty();
        }
        CodeState state = command.state() == null || command.state().isBlank() ? CodeState.ACTIVE
                : Tokens.parse("state", command.state(), CodeState.values());
        int sort = command.sortOrder() != null ? command.sortOrder() : DEFAULT_SORT_ORDER;
        CommonCode created = new CommonCode(group, code, command.nameKo(), command.nameEn(), command.descriptionKo(),
                command.descriptionEn(), sort, state, directionOf(command, CodeDirection.CANONICAL), AuthorId.OWNER);
        codes.insert(created);
        revision.bump();
        audit.record(actor, OwnerAction.CODE_SET, group.name() + "/" + code.value(), "added state=" + state.name());
        return Optional.of(new CodeResult(created, revision.current()));
    }

    /** The direction the command names, or {@code current} when it names none (CANONICAL for a code that is new). */
    private static CodeDirection directionOf(CodeCommand command, CodeDirection current) {
        if (command.direction() == null || command.direction().isBlank()) {
            return current;
        }
        return Tokens.parse("direction", command.direction(), CodeDirection.values());
    }

    /**
     * The stored relations of a code keep their two ends under its direction (a directed kind in the order of the caller, any
     * other in canonical order), so the direction of a code that has relations cannot change: a relation stored under the
     * other direction would be read, and unlinked, the wrong way round.
     */
    private void requireDirectionNotInUse(CommonCode existing, CommonCode wanted) {
        if (existing.direction() == wanted.direction() || existing.group() != CodeGroup.RELATION_KIND) {
            return;
        }
        long stored = links.countRelations(existing.code());
        if (stored > 0) {
            throw ValidationException.invalid("direction", ValidationReason.DIRECTION_IN_USE, String.valueOf(stored));
        }
    }

    @Transactional(readOnly = true)
    public List<CommonCode> list(CodeGroup group) {
        return codes.catalog(group).entries();
    }

    /** The code revision: it moves whenever a code is changed, so a reader of the codes can tell when to read them again. */
    @Transactional(readOnly = true)
    public long revision() {
        return revision.current();
    }
}
