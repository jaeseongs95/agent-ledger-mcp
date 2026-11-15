package org.pgledger.application.admin;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.pgledger.attestation.AttestationException;
import org.pgledger.attestation.KeyBlock;
import org.pgledger.attestation.PublicKeys;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.SessionKeyRepository;
import org.pgledger.database.repository.SessionKeyStateRepository;
import org.pgledger.domain.error.ConflictCode;
import org.pgledger.domain.error.ConflictException;
import org.pgledger.domain.error.LookupCode;
import org.pgledger.domain.error.LookupException;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.rule.OwnerAction;
import org.pgledger.domain.value.AuthorId;
import org.pgledger.domain.value.OwnerIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * The public keys that authors sign their writes with. Used by the admin web and the CLI, never by the MCP tools: only
 * the owner decides which key belongs to which author (the owner compares the fingerprint with the one the requesting
 * session printed). A key is bound to an author that has none; an author that has one gets another only by an explicit,
 * confirmed replacement, which closes the live key first; the owner can close a key without replacing it
 * ({@link #revokeConfirmed}); rows are never deleted, so the keys an
 * author had stay on record. Two registrations of one author at the same time end with exactly one live key, and a
 * key is live for one author only.
 */
@Service
@Transactional
public class SessionKeyService {

    private static final Logger LOG = LoggerFactory.getLogger(SessionKeyService.class);

    /** The author and its public key (X.509 DER in base64, as the hook prints it) with an optional note. */
    public record RegisterCommand(String author, String publicKey, String note) {
    }

    /** What was registered: the row, the author and the fingerprint the owner compares. */
    public record Registered(long id, String author, String fingerprint) {
    }

    private final SessionKeyStateRepository state;
    private final SessionKeyRepository keys;
    private final IdAllocator ids;
    private final OwnerAuditWriter audit;
    private final Clock clock;

    public SessionKeyService(SessionKeyStateRepository state, SessionKeyRepository keys, IdAllocator ids,
            OwnerAuditWriter audit, Clock clock) {
        this.state = state;
        this.keys = keys;
        this.ids = ids;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Binds the key to an author that has no live key yet and records who did it in the owner's audit trail. Registering
     * the key that is already live for the author changes nothing and answers with the existing row. A key that is live
     * for another author is refused (DUPLICATE_KEY). An author that already has another live key is refused
     * (KEY_REPLACEMENT_NOT_CONFIRMED, naming the fingerprint and the registration time of the live key): a key is never
     * replaced silently, because whoever gets the owner to register a key under a known author name would otherwise
     * inherit that author's grants. Another registration of the same author or key that commits first makes the insert a
     * duplicate key (or the conditional close of the replaced key affect no row): this call is the loser, it does not run
     * again, it reads what the winner left and answers with that (the repeated registration of the same key answers with its
     * row).
     */
    public Registered register(RegisterCommand command, OwnerIdentity actor) {
        return bind(command, actor, null);
    }

    /**
     * Like {@link #register(RegisterCommand, OwnerIdentity)}, and when the author already has another live key, replaces
     * it. The caller must name the fingerprint of the key being replaced ({@code replacedFingerprint}, any letter case),
     * which the owner read from the refusal or from the screen together with the registration time; a different
     * fingerprint (the live key changed meanwhile) is refused like no confirmation at all. The replaced key stays on
     * record as a closed row; the audit trail says that it was a replacement.
     */
    public Registered registerReplacing(RegisterCommand command, OwnerIdentity actor, String replacedFingerprint) {
        if (replacedFingerprint == null || replacedFingerprint.isBlank()) {
            throw ValidationException.invalid("replace", ValidationReason.REQUIRED);
        }
        return bind(command, actor, replacedFingerprint.strip().toLowerCase(Locale.ROOT));
    }

    private Registered bind(RegisterCommand command, OwnerIdentity actor, String replacedFingerprint) {
        AuthorId author = Authors.of(command.author());
        if (AuthorId.OWNER.equals(author)) {
            // the owner role is not a session: nothing signs as user:owner, and a key under that name would be a key
            // that the grants and the audit of the owner could be mistaken for
            throw ValidationException.invalid("author", ValidationReason.MUST_BE_A_SESSION_AUTHOR);
        }
        PublicKey key = key(command.publicKey());
        String fingerprint = PublicKeys.fingerprint(key);
        return state.findLiveByFingerprint(fingerprint)
                .map(holder -> sameAuthor(holder, author, fingerprint))
                .orElseGet(() -> bindNew(command, actor, author, PublicKeys.encode(key), fingerprint,
                        replacedFingerprint));
    }

    /** The key is live already: for this author it is the answer of a repeated registration, for another one it is refused. */
    private static Registered sameAuthor(SessionPublicKey holder, AuthorId author, String fingerprint) {
        if (Objects.equals(holder.authorId(), author)) {
            return registered(holder);
        }
        throw new ConflictException(ConflictCode.DUPLICATE_KEY, List.of("public key " + fingerprint));
    }

    /** The key is live for nobody: it becomes the key of the author, closing the one the author has when that is confirmed. */
    private Registered bindNew(RegisterCommand command, OwnerIdentity actor, AuthorId author, String text,
            String fingerprint, String replacedFingerprint) {
        Instant now = clock.instant();
        Optional<SessionPublicKey> current = state.findLive(author);
        // another registration of this very key for this author became live after the lookup by fingerprint found
        // nothing: the same answer as for any repeated registration (no row, no audit, nothing closed)
        return current.filter(open -> Objects.equals(open.fingerprint(), fingerprint))
                .map(SessionKeyService::registered)
                .orElseGet(() -> current
                        .map(open -> {
                            requireConfirmed(open, author, replacedFingerprint);
                            return write(command, actor, author, text, fingerprint, now, OwnerAction.KEY_REPLACED,
                                    " replaced=" + open.fingerprint(), () -> state.close(open.id(), now));
                        })
                        .orElseGet(() -> write(command, actor, author, text, fingerprint, now,
                                OwnerAction.KEY_REGISTERED, "", () -> true)));
    }

    /**
     * Closes the old key ({@code closeOld}, true when it was closed; always true when there is none) and inserts the
     * new one, then records the audit entry.
     */
    private Registered write(RegisterCommand command, OwnerIdentity actor, AuthorId author, String text,
            String fingerprint, Instant now, OwnerAction action, String replacedDetail, BooleanSupplier closeOld) {
        // The close and the insert are the writes that other registrations race for. Whoever loses is decided by the
        // database, not by running this method again: the conditional close affects no row, or the insert breaks a
        // unique key. The savepoint takes back what the loser wrote (the closed key, the number of the counter) and
        // keeps the transaction usable on every DBMS, as in ReceiptService.
        TransactionStatus transaction = TransactionAspectSupport.currentTransactionStatus();
        Object savepoint = transaction.createSavepoint();
        SessionPublicKey row;
        try {
            if (!closeOld.getAsBoolean()) {
                transaction.rollbackToSavepoint(savepoint);
                transaction.releaseSavepoint(savepoint);
                return lostRace(author, fingerprint);
            }
            row = keys.insert(SessionPublicKey.register(ids.next(Counter.SESSION_KEY), author, text, fingerprint,
                    AuthorId.OWNER, now, command.note()));
            transaction.releaseSavepoint(savepoint);
        } catch (DuplicateKeyException duplicate) {
            LOG.debug("the key of {} was registered by another call first; its row decides", author.value(), duplicate);
            transaction.rollbackToSavepoint(savepoint);
            transaction.releaseSavepoint(savepoint);
            return lostRace(author, fingerprint);
        }
        audit.record(actor, action, author.value(), "key=" + fingerprint + replacedDetail);
        return registered(row);
    }

    /** The owner confirmed the very key the author has now; otherwise the replacement is refused. */
    private static void requireConfirmed(SessionPublicKey open, AuthorId author, String replacedFingerprint) {
        if (!Objects.equals(open.fingerprint(), replacedFingerprint)) {
            throw new ConflictException(ConflictCode.KEY_REPLACEMENT_NOT_CONFIRMED,
                    List.of(author.value(), open.fingerprint(), open.registeredAt().toString()));
        }
    }

    /**
     * The answer of a registration that lost the race, from what the winner committed (nothing is decided again): the
     * key is live now for this author (the same registration: its row), for another author (DUPLICATE_KEY), or the
     * author has another live key (KEY_REPLACEMENT_NOT_CONFIRMED, as for any key that is not the confirmed one); an author
     * whose key was closed meanwhile with nothing registered in its place has no live key (NOT_FOUND).
     */
    private Registered lostRace(AuthorId author, String fingerprint) {
        return state.findLiveByFingerprint(fingerprint)
                .map(holder -> sameAuthor(holder, author, fingerprint))
                .orElseGet(() -> {
                    SessionPublicKey live = state.findLive(author)
                            .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND,
                                    List.of("session key", author.value())));
                    throw new ConflictException(ConflictCode.KEY_REPLACEMENT_NOT_CONFIRMED,
                            List.of(author.value(), live.fingerprint(), live.registeredAt().toString()));
                });
    }

    /** Closes the live key of the author and records who did it; true when there was one. */
    public boolean revoke(String author, OwnerIdentity actor) {
        AuthorId id = Authors.of(author);
        return state.findLive(id).map(row -> {
            boolean closed = state.close(row.id(), clock.instant());
            if (closed) {
                audit.record(actor, OwnerAction.KEY_REVOKED, id.value(), "key=" + row.fingerprint());
            }
            return closed;
        }).orElse(false);
    }

    /**
     * The owner's action that closes the live key of an author: the same as {@link #revoke(String, OwnerIdentity)}, but
     * only when the key that is live now is the one the owner compared (the fingerprint shown on the screen or by the
     * command, any letter case), so that a key that was replaced meanwhile is not closed by a confirmation that was
     * meant for the old one. The check and the close are one transaction. Closing leaves the author without a key, so
     * its signed writes are refused until another key is registered; the grants stay.
     *
     * @throws LookupException NOT_FOUND when the author has no live key
     * @throws ValidationException VALIDATION_FAILED path {@code fingerprint} (MUST_MATCH, naming the live fingerprint)
     *         when the fingerprint is not that of the live key
     */
    public void revokeConfirmed(String author, String fingerprint, OwnerIdentity actor) {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw ValidationException.invalid("fingerprint", ValidationReason.REQUIRED);
        }
        AuthorId id = Authors.of(author);
        SessionPublicKey open = state.findLive(id)
                .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND, List.of("session key", id.value())));
        if (!Objects.equals(open.fingerprint(), fingerprint.strip().toLowerCase(Locale.ROOT))) {
            throw ValidationException.invalid("fingerprint", ValidationReason.MUST_MATCH, open.fingerprint());
        }
        if (!state.close(open.id(), clock.instant())) {
            // closed between the read and the update by another owner action: the same answer as no live key
            throw new LookupException(LookupCode.NOT_FOUND, List.of("session key", id.value()));
        }
        audit.record(actor, OwnerAction.KEY_REVOKED, id.value(), "key=" + open.fingerprint());
    }

    /**
     * A key as the owner pasted it, ready to compare and to register: the public key alone, or the whole block that the
     * hook command {@code session-key <server> <author>} prints ({@link KeyBlock}). {@code server} is the server the
     * key was made for when the owner pasted the block (to show it next to the fingerprint), {@code null} for the bare
     * key.
     */
    public record PresentedKey(String publicKey, String fingerprint, String server) {
    }

    /**
     * Reads what the owner pasted for the author: a bare public key, or the block of the hook command. A block must be
     * for this very author (its author line equals {@code author}) and its fingerprint line must belong to its key.
     * Nothing is stored.
     *
     * @throws ValidationException VALIDATION_FAILED path {@code public_key} (REQUIRED, MUST_BE_ED25519_PUBLIC_KEY,
     *         MUST_BE_SESSION_KEY_BLOCK, KEY_BLOCK_FINGERPRINT_DIFFERS) or path {@code author} (KEY_BLOCK_AUTHOR_DIFFERS)
     */
    @Transactional(readOnly = true)
    public PresentedKey present(String author, String text) {
        AuthorId id = Authors.of(author);
        if (text == null) {
            throw ValidationException.invalid("public_key", ValidationReason.REQUIRED);
        }
        if (!KeyBlock.isBlock(text)) {
            PublicKey key = key(text);
            return new PresentedKey(PublicKeys.encode(key), PublicKeys.fingerprint(key), null);
        }
        KeyBlock block;
        try {
            block = KeyBlock.parse(text);
        } catch (AttestationException failure) {
            ValidationReason reason = switch (failure.reason()) {
                case KEY_BLOCK_FINGERPRINT -> ValidationReason.KEY_BLOCK_FINGERPRINT_DIFFERS;
                case KEY_INVALID -> ValidationReason.MUST_BE_ED25519_PUBLIC_KEY;
                // a block that is not one: every other reason of the parser (no token or signature is read here)
                case MALFORMED, VERSION_UNSUPPORTED, SIGNATURE_INVALID, KEY_BLOCK_INVALID ->
                    ValidationReason.MUST_BE_SESSION_KEY_BLOCK;
            };
            throw ValidationException.invalid("public_key", reason, failure);
        }
        if (!Objects.equals(block.author(), id.value())) {
            throw ValidationException.invalid("author", ValidationReason.KEY_BLOCK_AUTHOR_DIFFERS, block.author());
        }
        return new PresentedKey(block.publicKey(), block.fingerprint(), block.server());
    }

    /** The fingerprint of a public key as the owner compares it (the validation of {@code register}, nothing stored). */
    @Transactional(readOnly = true)
    public String fingerprintOf(String publicKey) {
        return PublicKeys.fingerprint(key(publicKey));
    }

    /** The live key of the author now. */
    @Transactional(readOnly = true)
    public Optional<SessionPublicKey> live(String author) {
        return state.findLive(Authors.of(author));
    }

    /** Every live key of every author, by author (the keys screen). */
    @Transactional(readOnly = true)
    public List<SessionPublicKey> liveKeys() {
        return state.findAllLive();
    }

    /** Every key row of the author, the live one and the closed ones, oldest first. */
    @Transactional(readOnly = true)
    public List<SessionPublicKey> history(String author) {
        return state.findHistory(Authors.of(author));
    }

    private static Registered registered(SessionPublicKey row) {
        return new Registered(row.id(), row.authorId().value(), row.fingerprint());
    }

    private static PublicKey key(String text) {
        try {
            return PublicKeys.decode(text);
        } catch (AttestationException failure) {
            throw ValidationException.invalid("public_key", ValidationReason.MUST_BE_ED25519_PUBLIC_KEY, failure);
        }
    }
}
