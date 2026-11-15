package org.pgledger.admin.passkey;

import java.util.Optional;
import org.pgledger.application.admin.PasskeyService;
import org.pgledger.domain.model.PasskeyUser;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.stereotype.Component;

/**
 * The user entity of WebAuthn on the ledger database: there is one owner, so there is one entity, whatever user name asks for
 * it (a passkey of the owner is found whether the owner is signed in with a code, with OpenID Connect or with a passkey). The
 * entity is made by the first registration and carries the fixed name {@link #OWNER}: the name a passkey sign-in loads the
 * account of ({@code OwnerAccounts}), never the name of the session that registered it. The framework's JDBC implementation is
 * not used (see {@link LedgerUserCredentialRepository}).
 */
@Component
public class LedgerPublicKeyCredentialUserEntityRepository implements PublicKeyCredentialUserEntityRepository {

    /** The user name of the one owner. */
    public static final String OWNER = "owner";

    private final PasskeyService passkeys;

    public LedgerPublicKeyCredentialUserEntityRepository(PasskeyService passkeys) {
        this.passkeys = passkeys;
    }

    @Override
    public PublicKeyCredentialUserEntity findById(Bytes id) {
        return entity(passkeys.ownerById(id.toBase64UrlString()));
    }

    @Override
    public PublicKeyCredentialUserEntity findByUsername(String username) {
        return entity(passkeys.owner());
    }

    /** Stores the first entity (as the owner); a later one is the same person and is left out. */
    @Override
    public void save(PublicKeyCredentialUserEntity userEntity) {
        passkeys.saveOwner(new PasskeyUser(userEntity.getId().toBase64UrlString(), OWNER, "Owner"));
    }

    /** Refused: the owner is never deleted. */
    @Override
    public void delete(Bytes id) {
        throw new UnsupportedOperationException("the owner is never deleted");
    }

    private static PublicKeyCredentialUserEntity entity(Optional<PasskeyUser> user) {
        if (user.isEmpty()) {
            return null;
        }
        PasskeyUser owner = user.orElseThrow();
        return ImmutablePublicKeyCredentialUserEntity.builder().name(owner.name()).id(Bytes.fromBase64(owner.userId()))
                .displayName(owner.displayName()).build();
    }
}
