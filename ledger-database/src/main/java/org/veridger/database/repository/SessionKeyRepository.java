// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.database.repository;

import java.util.Optional;
import org.veridger.domain.model.SessionPublicKey;
import org.springframework.data.repository.Repository;

/** Session keys: insert and read. Closing a key is a statement of {@link SessionKeyStateRepository}. */
public interface SessionKeyRepository extends Repository<SessionPublicKey, Long>, Insertable<SessionPublicKey> {

    Optional<SessionPublicKey> findById(Long id);

    Optional<SessionPublicKey> findByActiveKey(String activeKey);

    Optional<SessionPublicKey> findByFingerprintKey(String fingerprintKey);
}
