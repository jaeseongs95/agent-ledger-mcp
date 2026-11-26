// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application.admin;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.agentledger.database.repository.PasskeyCodeRepository;
import org.agentledger.database.repository.PasskeyRepository;
import org.agentledger.domain.error.AdminCode;
import org.agentledger.domain.error.AdminException;
import org.agentledger.domain.error.LookupCode;
import org.agentledger.domain.error.LookupException;
import org.agentledger.domain.error.ValidationException;
import org.agentledger.domain.error.ValidationReason;
import org.agentledger.domain.model.OwnerPasskey;
import org.agentledger.domain.model.PasskeyUser;
import org.agentledger.domain.rule.OwnerAction;
import org.agentledger.domain.value.OwnerIdentity;
import org.agentledger.domain.value.Sha256;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The passkeys of the one owner, as the storage of the WebAuthn support of the admin web (the ceremonies themselves, the
 * signature checks and the CBOR are Spring Security's and WebAuthn4J's; this class knows only text, rows and the audit trail).
 * Every operation that changes something is one method and one transaction and is recorded in the owner's audit trail:
 *
 * <ul>
 * <li>{@link #issueCode}: the one-time enrolment code the owner asks for on the server ({@code passkey-code}); with the removal
 *     flag every live passkey is closed first (the reset of an owner who lost every passkey).</li>
 * <li>{@link #spendCode}: the sign-in with a code (an enrolment session) spends it.</li>
 * <li>{@link #save}: a passkey that the WebAuthn registration made is stored (an existing one is left as it is: the history of
 *     a credential is never changed).</li>
 * <li>{@link #revoke}: one passkey is closed by a revocation row; the last live one is never closed here.</li>
 * </ul>
 *
 * <p>The code is made from {@link SecureRandom} (20 characters of a 32-letter alphabet without look-alikes, 100 bits), shown
 * once, stored only as its SHA-256, valid {@link #CODE_TTL} and spent by one conditional UPDATE: a second use, an expired or an
 * unknown code is {@code PASSKEY_CODE_INVALID}. Asking for a new code ends the earlier one that was not used.
 *
 * <p>The checks of these operations read the passkeys and the codes that the other operations wrote ("the last live passkey is
 * never closed", "a new code ends the earlier one"), so two of them at the same moment must not run side by side. Every one
 * that changes the passkeys or the codes starts with its audit row, the write of the owner's operation that takes the row of
 * the counter {@code OWNER_AUDIT} and keeps it until the transaction ends: the second one waits for the first one's commit
 * and then reads what it wrote (READ COMMITTED). It is the only number of the transaction, so the order of the numbers of
 * {@code Counter} holds. A refusal (or any failure) rolls the audit row back with everything else.
 */
@Service
@Transactional
public class PasskeyService {

    /** How long a code lives. */
    public static final Duration CODE_TTL = Duration.ofMinutes(15);

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 20;
    private static final int CODE_GROUP = 4;
    private static final String PASSKEY_SUBJECT = "passkey";
    private static final int FINGERPRINT_GROUPS = 4;

    /** The code as it is shown once, when it ends, and how many passkeys the request closed. */
    public record IssuedCode(String code, Instant expiresAt, int closedPasskeys) {
    }

    /**
     * A passkey that the WebAuthn registration made (every byte string base64url, the flags 0 or 1).
     *
     * @param transports the transports the authenticator reported, comma separated (may be empty)
     */
    public record NewPasskey(String credentialId, String userId, String publicKey, long signatureCount,
            int uvInitialized, int backupEligible, int backupState, String transports, String attestationObject,
            String label) {
    }

    private final PasskeyRepository passkeys;
    private final PasskeyCodeRepository codes;
    private final OwnerAuditWriter audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PasskeyService(PasskeyRepository passkeys, PasskeyCodeRepository codes, OwnerAuditWriter audit, Clock clock) {
        this.passkeys = passkeys;
        this.codes = codes;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Issues a one-time code (the earlier unused one ends). With {@code removeExisting} every live passkey is closed first, in
     * the same transaction; without it the live passkeys stay. Both are recorded in the owner's audit trail.
     */
    public IssuedCode issueCode(OwnerIdentity actor, boolean removeExisting) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(CODE_TTL);
        // the first write: it takes the row of the counter of the audit rows, which serializes the owner's operations on the
        // passkeys (see the class comment), so what follows reads what the operation before it committed
        audit.record(actor, OwnerAction.PASSKEY_CODE_ISSUED, PASSKEY_SUBJECT, "expires=" + expiresAt);
        int closed = removeExisting ? passkeys.revokeAllLive(actor.value(), now) : 0;
        codes.expireOpen(now);
        String code = newCode();
        codes.insert(hash(code), actor.value(), now, expiresAt);
        if (removeExisting) {
            audit.record(actor, OwnerAction.PASSKEY_RESET, PASSKEY_SUBJECT, "closed=" + closed);
        }
        return new IssuedCode(code, expiresAt, closed);
    }

    /**
     * Spends a code (the sign-in of an enrolment session).
     *
     * @throws AdminException PASSKEY_CODE_INVALID when it is missing, unknown, expired or used
     */
    public void spendCode(String code) {
        if (!codes.spend(hash(code), clock.instant())) {
            throw new AdminException(AdminCode.PASSKEY_CODE_INVALID, List.of());
        }
    }

    /**
     * Stores a passkey that the WebAuthn registration made and records who registered it. A credential id that is stored
     * already is left as it is (the history of a credential is never changed, so the update after a sign-in changes nothing).
     */
    public void save(NewPasskey passkey, OwnerIdentity actor) {
        if (passkeys.exists(passkey.credentialId())) {
            return;
        }
        if (passkey.credentialId().length() > OwnerPasskey.CREDENTIAL_ID_MAX_LENGTH) {
            throw ValidationException.invalid("credentialId", ValidationReason.MUST_BE_AT_MOST_CHARS,
                    String.valueOf(OwnerPasskey.CREDENTIAL_ID_MAX_LENGTH));
        }
        if (passkey.publicKey().length() > OwnerPasskey.PUBLIC_KEY_MAX_LENGTH) {
            throw ValidationException.invalid("publicKey", ValidationReason.MUST_BE_AT_MOST_CHARS,
                    String.valueOf(OwnerPasskey.PUBLIC_KEY_MAX_LENGTH));
        }
        if (passkey.attestationObject().length() > OwnerPasskey.ATTESTATION_MAX_LENGTH) {
            throw ValidationException.invalid("attestationObject", ValidationReason.MUST_BE_AT_MOST_CHARS,
                    String.valueOf(OwnerPasskey.ATTESTATION_MAX_LENGTH));
        }
        String label = label(passkey.label());
        Instant now = clock.instant();
        String fingerprint = fingerprint(passkey.publicKey());
        passkeys.insert(new OwnerPasskey(passkey.credentialId(), passkey.userId(), passkey.publicKey(),
                passkey.signatureCount(), passkey.uvInitialized(), passkey.backupEligible(), passkey.backupState(),
                passkey.transports(), passkey.attestationObject(), label, fingerprint, actor.value(), now));
        audit.record(actor, OwnerAction.PASSKEY_REGISTERED, fingerprint, "label=" + label);
    }

    /**
     * Closes a live passkey by a revocation row. The last live passkey is never closed here (the owner could lock himself out
     * of the approval of every change): reset the passkeys from the shell instead.
     *
     * @throws LookupException NOT_FOUND when no live passkey has this id
     * @throws AdminException PASSKEY_LAST_KEY
     */
    public void revoke(String credentialId, OwnerIdentity actor) {
        // the fingerprint and the label of a credential never change (the history is append-only), so the audit row can be
        // written first, as the write that takes the row of the audit counter and serializes the owner's operations on the
        // passkeys; a refusal below rolls it back with the rest of the transaction
        OwnerPasskey known = passkeys.find(credentialId)
                .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND, List.of("passkey", credentialId)));
        audit.record(actor, OwnerAction.PASSKEY_REVOKED, known.fingerprint(), "label=" + known.label());
        passkeys.findLive(credentialId)
                .orElseThrow(() -> new LookupException(LookupCode.NOT_FOUND, List.of("passkey", credentialId)));
        if (passkeys.countLive() <= 1) {
            throw new AdminException(AdminCode.PASSKEY_LAST_KEY, List.of());
        }
        passkeys.revoke(credentialId, actor.value(), clock.instant());
    }

    /** The live passkey with this id. */
    @Transactional(readOnly = true)
    public Optional<OwnerPasskey> findLive(String credentialId) {
        return passkeys.findLive(credentialId);
    }

    /** The live passkeys, oldest first (what the passkey page lists, and what a sign-in may use). */
    @Transactional(readOnly = true)
    public List<OwnerPasskey> live() {
        return passkeys.findAllLive();
    }

    /** The owner as the user entity of WebAuthn, if it was made. */
    @Transactional(readOnly = true)
    public Optional<PasskeyUser> owner() {
        return passkeys.findUser();
    }

    /** The user entity with this handle. */
    @Transactional(readOnly = true)
    public Optional<PasskeyUser> ownerById(String userId) {
        return passkeys.findUserById(userId);
    }

    /** Stores the owner as the user entity once; there is one owner, so another entity is left out. */
    public void saveOwner(PasskeyUser user) {
        if (passkeys.findUser().isEmpty()) {
            passkeys.insertUser(user);
        }
    }

    /**
     * The fingerprint of a public key: the first 8 bytes of the SHA-256 of its bytes as four groups of upper-case hex digits.
     *
     * @param publicKey the key as base64url text
     */
    public static String fingerprint(String publicKey) {
        byte[] digest = HexFormat.of().parseHex(Sha256.ofBytes(Base64.getUrlDecoder().decode(publicKey)).hex());
        HexFormat hex = HexFormat.of().withUpperCase();
        return IntStream.range(0, FINGERPRINT_GROUPS).mapToObj(group -> hex.formatHex(digest, group * 2, group * 2 + 2))
                .collect(Collectors.joining("-"));
    }

    private static String label(String given) {
        String label = given == null || given.isBlank() ? PASSKEY_SUBJECT : given.strip();
        if (label.length() > OwnerPasskey.LABEL_MAX_LENGTH || label.chars().anyMatch(Character::isISOControl)) {
            throw ValidationException.invalid("label", ValidationReason.MUST_BE_AT_MOST_CHARS,
                    String.valueOf(OwnerPasskey.LABEL_MAX_LENGTH));
        }
        return label;
    }

    /** The code as it is stored: its SHA-256 over the characters of the alphabet in upper case (dashes and blanks ignored). */
    static String hash(String code) {
        String normal = code == null ? "" : code.replace("-", "").replace(" ", "").strip().toUpperCase(Locale.ROOT);
        return Sha256.ofText(normal).hex();
    }

    private String newCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            if (i > 0 && i % CODE_GROUP == 0) {
                code.append('-');
            }
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }
}
