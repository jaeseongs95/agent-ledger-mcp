package org.pgledger.application.admin;

import org.pgledger.database.repository.CommonCodeRepository;
import org.pgledger.domain.model.CommonCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that creates a row of {@code common_code}: the insert that two owner actions on the same new code race for.
 * It runs in a nested transaction ({@code Propagation.NESTED}, a JDBC savepoint, which every DBMS supports): when the insert
 * breaks the primary key because another action created the code first, Spring rolls back to the savepoint and rethrows the
 * {@code DuplicateKeyException}, so the transaction of {@link CommonCodeService} stays usable on every DBMS and the service
 * lets the row of the winner decide. The nested boundary has to be a bean of its own so that the call passes the transaction
 * proxy; the transaction of the use case belongs to the calling service.
 */
@Service
@Transactional(propagation = Propagation.NESTED)
public class CommonCodeWriter {

    private final CommonCodeRepository codes;

    public CommonCodeWriter(CommonCodeRepository codes) {
        this.codes = codes;
    }

    /**
     * Inserts the code (version 0).
     *
     * @throws org.springframework.dao.DuplicateKeyException when the code exists already (nothing of this call stays written)
     */
    public void insert(CommonCode code) {
        codes.insert(code);
    }
}
