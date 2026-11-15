package org.pgledger.application.admin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.database.repository.CodeRevisionRepository;
import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.database.repository.LinkRepository;
import org.pgledger.database.repository.VersionedCode;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The extensible code lists. A code is added or updated (names, descriptions, order, state); it is never deleted
 * or renamed, because rows keep pointing at it, and there is no method that could. A change bumps the code
 * revision in the same transaction so other processes notice; setting a code to what it already is changes
 * nothing and does not bump. A code is created in one place only (read first; the insert at the same moment as another call
 * ends with the translated unique-key error) and changed by an update that is conditional on the version of the row that was
 * read. The transaction is the one of the service method (principle 4.4): no nested write, no retry.
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

    /**
     * Sets the code and, when that changes anything, records who did it in the owner's audit trail. A code that does not exist
     * is created ({@link #create}, the one place that creates a row); a code that exists is changed by an update that is
     * conditional on the version that was read, so a change that another action made in between is never overwritten: the
     * lost race is a VERSION_CONFLICT. When two actions create the same new code at the same time, the primary key decides and
     * the one that loses it ends with DUPLICATE_KEY.
     *
     * @throws ConflictException VERSION_CONFLICT (args: {@code common_code}, {@code <group>/<code>}, the version that was
     *         read, the version now) when the code was changed by another action after this one read it; DUPLICATE_KEY when
     *         another action created the code at the same moment
     */
    public CodeResult set(CodeCommand command, OwnerIdentity actor) {
        CodeGroup group = Tokens.parse("group", command.group(), CodeGroup.values());
        CodeValue code = CodeValue.of(command.code());
        CodeState state = stateOf(command);
        Optional<VersionedCode> existing = codes.findVersioned(group, code);
        if (existing.isEmpty()) {
            return create(command, group, code, state, actor);
        }
        return change(existing.get(), command, state, actor);
    }

    /**
     * Adds the code when it does not exist, with the revision bump and the audit row of {@link #set}; a code that exists is left
     * exactly as it is (nothing is written, not even by a command that would not change it). The first setup of an installation
     * adds the codes of the seed through this, so it can never overwrite a code that the owner changed or retired. Two calls
     * that add the same new code at the same time add one row: the primary key decides, and the call that loses it ends with
     * the translated DUPLICATE_KEY (nothing is run again).
     *
     * @return the new code and the revision, or empty when the code exists
     * @throws ConflictException DUPLICATE_KEY when another call created the code at the same moment
     */
    public Optional<CodeResult> addIfAbsent(CodeCommand command, OwnerIdentity actor) {
        CodeGroup group = Tokens.parse("group", command.group(), CodeGroup.values());
        CodeValue code = CodeValue.of(command.code());
        if (codes.findVersioned(group, code).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(create(command, group, code, stateOf(command), actor));
    }

    /**
     * Adds every code of the list that does not exist yet ({@link #addIfAbsent} for each, with its revision bump and audit row),
     * in one transaction: the first setup of an installation seeds its codes through this, and a code that is invalid or a
     * database failure leaves none of the list behind. A code that exists is left as it is.
     *
     * @return how many codes were added
     */
    public int seed(List<CodeCommand> commands, OwnerIdentity actor) {
        int added = 0;
        for (CodeCommand command : commands) {
            if (addIfAbsent(command, actor).isPresent()) {
                added++;
            }
        }
        return added;
    }

    private static CodeState stateOf(CodeCommand command) {
        return command.state() == null || command.state().isBlank() ? CodeState.ACTIVE
                : Tokens.parse("state", command.state(), CodeState.values());
    }

    /**
     * The one place that creates a code: the insert, then the revision bump and the audit row, in the transaction of the
     * calling method (principle 4.4). The callers have read before and found no row; a creation at the very same moment as
     * another call is decided by the primary key: the call that loses it ends with the translated DUPLICATE_KEY, and the
     * transaction is rolled back with everything it wrote (the rare simultaneous first creation, accepted).
     */
    private CodeResult create(CodeCommand command, CodeGroup group, CodeValue code, CodeState state,
            OwnerIdentity actor) {
        int sort = command.sortOrder() != null ? command.sortOrder() : DEFAULT_SORT_ORDER;
        CommonCode created = new CommonCode(group, code, command.nameKo(), command.nameEn(), command.descriptionKo(),
                command.descriptionEn(), sort, state, directionOf(command, CodeDirection.CANONICAL), AuthorId.OWNER);
        try {
            codes.insert(created);
        } catch (DuplicateKeyException duplicate) {
            throw new ConflictException(ConflictCode.DUPLICATE_KEY,
                    List.of("common code " + group.name() + "/" + code.value()), duplicate);
        }
        revision.bump();
        audit.record(actor, OwnerAction.CODE_SET, group.name() + "/" + code.value(), "added state=" + state.name());
        return new CodeResult(created, revision.current());
    }

    /** Changes the code that exists, if that changes anything, by an update that is conditional on the version read. */
    private CodeResult change(VersionedCode row, CodeCommand command, CodeState state, OwnerIdentity actor) {
        CommonCode current = row.code();
        int sort = command.sortOrder() != null ? command.sortOrder() : current.sortOrder();
        CommonCode wanted = new CommonCode(current.group(), current.code(), command.nameKo(), command.nameEn(),
                command.descriptionKo(), command.descriptionEn(), sort, state,
                directionOf(command, current.direction()), current.createdBy());
        if (Objects.equals(wanted, current)) {
            // the code is what the owner asked for already: nothing to write, no revision, no audit row
            return new CodeResult(wanted, revision.current());
        }
        requireDirectionNotInUse(current, wanted);
        String subject = current.group().name() + "/" + current.code().value();
        if (!codes.update(wanted, row.version())) {
            // another action changed the code after it was read: not overwritten, the owner decides again
            long now = codes.findVersioned(current.group(), current.code()).map(VersionedCode::version)
                    .orElseThrow(() -> vanished(current.group(), current.code()));
            throw new ConflictException(ConflictCode.VERSION_CONFLICT,
                    List.of("common_code", subject, Long.toString(row.version()), Long.toString(now)));
        }
        revision.bump();
        audit.record(actor, OwnerAction.CODE_SET, subject, "changed state=" + state.name());
        return new CodeResult(wanted, revision.current());
    }

    /** A code is never deleted: one that was there a moment ago and is gone is a broken database, not a business case. */
    private static InfrastructureException vanished(CodeGroup group, CodeValue code) {
        return new InfrastructureException(InfrastructureCode.INTEGRITY_VIOLATION,
                List.of("common code vanished: " + group.name() + "/" + code.value()));
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
