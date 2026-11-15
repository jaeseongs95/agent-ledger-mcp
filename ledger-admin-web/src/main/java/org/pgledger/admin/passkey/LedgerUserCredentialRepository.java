package org.pgledger.admin.passkey;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.pgledger.admin.security.OwnerActorResolver;
import org.pgledger.application.admin.PasskeyService;
import org.pgledger.application.admin.PasskeyService.NewPasskey;
import org.pgledger.domain.model.OwnerPasskey;
import org.pgledger.domain.value.OwnerIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.stereotype.Component;

/**
 * The passkeys of the owner on the ledger database: the {@link UserCredentialRepository} of Spring Security's WebAuthn support,
 * implemented on the ANSI tables of V7 through {@link PasskeyService} (a byte string is base64url text, a flag is 0 or 1, no
 * BLOB). The framework's JDBC implementation is not used because its columns are BLOBs and booleans.
 *
 * <p>The history of a credential is append-only: {@link #save} stores a credential that is not stored yet and leaves an existing
 * one as it is (the framework saves the credential again after every sign-in with a new counter and time of last use, which are
 * not kept: a synced passkey does not count, and an approval is bound to one change by its one-use challenge); a credential is
 * closed by a revocation row through {@link PasskeyService#revoke}, never through the framework's {@link #delete}, which is
 * refused. There is one owner, so every credential belongs to the one user entity.
 */
@Component
public class LedgerUserCredentialRepository implements UserCredentialRepository {

    private static final String SEPARATOR = ",";

    private final PasskeyService passkeys;
    private final OwnerActorResolver actors;

    public LedgerUserCredentialRepository(PasskeyService passkeys, OwnerActorResolver actors) {
        this.passkeys = passkeys;
        this.actors = actors;
    }

    /** Refused: a passkey is closed by {@link PasskeyService#revoke}, which appends a revocation row. */
    @Override
    public void delete(Bytes credentialId) {
        throw new UnsupportedOperationException("a passkey is closed by a revocation row, never deleted");
    }

    @Override
    public void save(CredentialRecord record) {
        Bytes attestation = record.getAttestationObject();
        if (attestation == null) {
            throw new IllegalArgumentException("a passkey needs its attestation object");
        }
        passkeys.save(new NewPasskey(record.getCredentialId().toBase64UrlString(),
                record.getUserEntityUserId().toBase64UrlString(), new Bytes(record.getPublicKey().getBytes())
                        .toBase64UrlString(), record.getSignatureCount(), flag(record.isUvInitialized()),
                flag(record.isBackupEligible()), flag(record.isBackupState()), transports(record.getTransports()),
                attestation.toBase64UrlString(), record.getLabel()), actor());
    }

    @Override
    public CredentialRecord findByCredentialId(Bytes credentialId) {
        Optional<OwnerPasskey> found = passkeys.findLive(credentialId.toBase64UrlString());
        if (found.isEmpty()) {
            return null;
        }
        return record(found.orElseThrow());
    }

    @Override
    public List<CredentialRecord> findByUserId(Bytes userId) {
        String id = userId.toBase64UrlString();
        return passkeys.live().stream().filter(passkey -> Objects.equals(id, passkey.userId())).map(this::record).toList();
    }

    private CredentialRecord record(OwnerPasskey passkey) {
        return ImmutableCredentialRecord.builder().credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(Bytes.fromBase64(passkey.credentialId())).userEntityUserId(Bytes.fromBase64(passkey.userId()))
                .publicKey(ImmutablePublicKeyCose.fromBase64(passkey.publicKey()))
                .signatureCount(passkey.signatureCount()).uvInitialized(passkey.uvInitialized() == 1)
                .transports(transports(passkey.transports())).backupEligible(passkey.backupEligible() == 1)
                .backupState(passkey.backupState() == 1)
                .attestationObject(Bytes.fromBase64(passkey.attestationObject())).label(passkey.label())
                .created(passkey.registeredAt()).lastUsed(passkey.registeredAt()).build();
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }

    private static String transports(Set<AuthenticatorTransport> transports) {
        return String.join(SEPARATOR, transports.stream().map(AuthenticatorTransport::getValue).sorted().toList());
    }

    private static Set<AuthenticatorTransport> transports(String text) {
        Set<AuthenticatorTransport> transports = new LinkedHashSet<>();
        if (text != null && !text.isBlank()) {
            Arrays.stream(text.split(SEPARATOR)).forEach(value -> transports.add(AuthenticatorTransport.valueOf(value)));
        }
        return transports;
    }

    /** Who is signed in; a credential that is saved with nobody signed in is the existing one saved again (no audit row). */
    private OwnerIdentity actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? OwnerIdentity.of("passkey:unauthenticated") : actors.of(authentication);
    }
}
