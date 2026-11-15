package org.pgledger.application.admin;

import java.time.Instant;
import java.util.Optional;
import org.pgledger.database.repository.Counter;
import org.pgledger.database.repository.IdAllocator;
import org.pgledger.database.repository.SessionKeyRepository;
import org.pgledger.database.repository.SessionKeyStateRepository;
import org.pgledger.domain.model.SessionPublicKey;
import org.pgledger.domain.value.AuthorId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The writes that registrations of a session key race for: the conditional close of the replaced key and the insert of the new
 * one (with the number of the counter). Each method runs in a nested transaction ({@code Propagation.NESTED}, a JDBC
 * savepoint, which every DBMS and {@code DataSourceTransactionManager} support): Spring makes the savepoint when the method
 * starts and takes back everything the method wrote when it ends with an exception (the insert broke a unique key: the closed
 * key and the number of the counter come back), so the transaction of {@link SessionKeyService} stays usable on every DBMS
 * and the loser reads what the winner committed. A conditional close that affects no row is not an exception: nothing was
 * written then, and the method answers empty. The nested boundary has to be a bean of its own so that the call passes the
 * transaction proxy; the transaction of the use case belongs to the calling service.
 */
@Service
@Transactional(propagation = Propagation.NESTED)
public class SessionKeyWriter {

    /** The key to bind: the author, the public key as stored (base64 of the X.509 DER), its fingerprint and the owner's note. */
    public record NewKey(AuthorId author, String text, String fingerprint, String note) {
    }

    private final SessionKeyStateRepository state;
    private final SessionKeyRepository keys;
    private final IdAllocator ids;

    public SessionKeyWriter(SessionKeyStateRepository state, SessionKeyRepository keys, IdAllocator ids) {
        this.state = state;
        this.keys = keys;
        this.ids = ids;
    }

    /**
     * Inserts the key as the live key of its author.
     *
     * @throws org.springframework.dao.DuplicateKeyException when another registration made the key or the author's key live
     *         first (nothing of this call stays written)
     */
    public SessionPublicKey bind(NewKey key, Instant now) {
        return keys.insert(SessionPublicKey.register(ids.next(Counter.SESSION_KEY), key.author(), key.text(),
                key.fingerprint(), AuthorId.OWNER, now, key.note()));
    }

    /**
     * Closes the live key {@code replacedId} and inserts the new key in its place.
     *
     * @return the new row, or empty when the key was closed meanwhile by another call (nothing was written)
     * @throws org.springframework.dao.DuplicateKeyException when another registration made the key or the author's key live
     *         first (the close is taken back with the rest of this call)
     */
    public Optional<SessionPublicKey> replace(long replacedId, NewKey key, Instant now) {
        if (!state.close(replacedId, now)) {
            return Optional.empty();
        }
        return Optional.of(bind(key, now));
    }
}
