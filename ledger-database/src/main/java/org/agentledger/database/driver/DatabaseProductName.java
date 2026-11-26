// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.driver;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.InfrastructureException;

/**
 * The product name that a JDBC connection reports ({@code DatabaseMetaData.getDatabaseProductName()}). Reading it is a call
 * that throws {@link SQLException}, and the database module catches that exception only in this package, the driver boundary
 * (ARC-036); the caller gets the business failure instead. The dialect of the SQL of a query ({@code SqlBuilder}) and the
 * check of the configured JDBC URL against the connected product both start from this name.
 */
public final class DatabaseProductName {

    private DatabaseProductName() {
    }

    /**
     * @param connection a connection the caller has taken and releases
     * @return the product name the driver reports, for example {@code PostgreSQL}
     * @throws InfrastructureException DATABASE_PRODUCT_UNREADABLE when the metadata of the connection cannot be read
     */
    public static String of(Connection connection) {
        try {
            return connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            throw new InfrastructureException(InfrastructureCode.DATABASE_PRODUCT_UNREADABLE, List.of(), e);
        }
    }
}
