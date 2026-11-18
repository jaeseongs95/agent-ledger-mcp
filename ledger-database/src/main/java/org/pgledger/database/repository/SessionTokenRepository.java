// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.repository;

import java.util.Optional;
import org.pgledger.domain.model.SessionToken;
import org.springframework.data.repository.Repository;

/** Bearer session tokens: insert and read. Revocation/touch is handled by {@link SessionTokenStateRepository}. */
public interface SessionTokenRepository extends Repository<SessionToken, Long>, Insertable<SessionToken> {

    Optional<SessionToken> findById(Long id);

    Optional<SessionToken> findByTokenHash(String tokenHash);
}
