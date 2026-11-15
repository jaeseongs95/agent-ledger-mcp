package org.pgledger.database.driver;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * The connections of the application account to the ledger schema, for the check of {@code setup} (see
 * {@link DriverDataSources#applicationAccountDataSource}): Spring's {@link SimpleDriverDataSource} for the registered driver,
 * whose connections all use the schema {@code ledger.schema.name}, folded the way the DBMS stores an unquoted identifier (JDBC
 * metadata tells the folding, as for the pool). The schema is chosen with {@code Connection.setSchema} on each connection, when
 * it is made and not before: the account may not be able to connect yet, which is what the check is for, so nothing is asked of
 * the database when this object is made. It holds no pool and no open connection.
 */
final class ApplicationAccountDataSource extends SimpleDriverDataSource {

    private final String schema;

    ApplicationAccountDataSource(Driver driver, String url, String username, String password, String schema) {
        super(driver, url, username, password);
        this.schema = schema;
    }

    @Override
    protected Connection getConnectionFromDriver(Properties properties) throws SQLException {
        // No catch: the connection is closed again unless it is handed out (JDBC methods declare SQLException).
        Connection connection = super.getConnectionFromDriver(properties);
        boolean ready = false;
        try {
            if (schema != null) {
                connection.setSchema(SchemaHistoryRepository.fold(connection.getMetaData(), schema));
            }
            ready = true;
            return connection;
        } finally {
            if (!ready) {
                connection.close();
            }
        }
    }
}
