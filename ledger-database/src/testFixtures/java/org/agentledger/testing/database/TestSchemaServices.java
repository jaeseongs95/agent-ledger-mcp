// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.testing.database;

import java.time.Clock;
import javax.sql.DataSource;
import org.agentledger.database.repository.DatabaseEncodingRepository;
import org.agentledger.database.repository.SchemaHistoryRepository;
import org.agentledger.database.repository.SessionClockRepository;
import org.agentledger.database.repository.SqlBuilder;
import org.agentledger.database.schema.GrantApplier;
import org.agentledger.database.schema.SchemaFileApplier;
import org.agentledger.database.schema.SchemaMigrator;
import org.agentledger.database.schema.SchemaProperties;
import org.agentledger.database.schema.SchemaVerifier;
import org.agentledger.database.schema.UtcSessionCheck;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * The schema services of the product over a data source a test hands in, for the tests that run the migration by hand and do not
 * start a Spring context. In the product the container makes these (the {@code JdbcClient} and the transaction manager of
 * Boot, the repositories and services as components, {@code LedgerSchemaConfiguration}); here the same objects are put together
 * the same way, with one difference that matters: the service {@link SchemaFileApplier} is called through the transaction
 * interceptor of Spring over the {@code @Transactional} of its method, exactly as the proxy of the container does, so a file and
 * its history row are one transaction here too. The data source decides the schema ({@link TestDatabase#scoped}); nothing here
 * picks one.
 *
 * <p>A test of the wiring itself uses a context ({@code SchemaWiringTest}); this class is for the tests of what the services do.
 */
public final class TestSchemaServices {

    private TestSchemaServices() {
    }

    public static SchemaHistoryRepository history(DataSource dataSource) {
        return new SchemaHistoryRepository(JdbcClient.create(dataSource), dataSource);
    }

    /** The encoding probes of the dialect that the {@code sqlBuilder} reports, over the data source. */
    public static DatabaseEncodingRepository encodings(DataSource dataSource, SqlBuilder sqlBuilder) {
        return new DatabaseEncodingRepository(JdbcClient.create(dataSource), sqlBuilder);
    }

    public static SessionClockRepository sessionClock(DataSource dataSource) {
        return new SessionClockRepository(JdbcClient.create(dataSource));
    }

    public static SchemaMigrator migrator(DataSource dataSource, SchemaProperties properties, Clock clock) {
        return migrator(dataSource, properties, clock, new SqlBuilder(dataSource));
    }

    /** The same with the {@link SqlBuilder} a test hands in (one that reports another product than the database it runs on). */
    public static SchemaMigrator migrator(DataSource dataSource, SchemaProperties properties, Clock clock,
            SqlBuilder sqlBuilder) {
        SchemaHistoryRepository history = history(dataSource);
        ProxyFactory proxy = new ProxyFactory(new SchemaFileApplier(dataSource, history));
        proxy.setProxyTargetClass(true);
        // typed as TransactionManager on purpose: the overload for a PlatformTransactionManager is the deprecated one
        TransactionManager transactions = new DataSourceTransactionManager(dataSource);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        SchemaFileApplier applier = (SchemaFileApplier) proxy.getProxy(TestSchemaServices.class.getClassLoader());
        return new SchemaMigrator(history, applier, new PathMatchingResourcePatternResolver(), properties, clock, sqlBuilder,
                encodings(dataSource, sqlBuilder));
    }

    public static SchemaVerifier verifier(DataSource dataSource, SchemaProperties properties) {
        return new SchemaVerifier(history(dataSource), new PathMatchingResourcePatternResolver(), properties);
    }

    public static UtcSessionCheck utcCheck(DataSource dataSource, Clock clock) {
        return new UtcSessionCheck(sessionClock(dataSource), clock);
    }

    public static GrantApplier grants(DataSource dataSource) {
        return new GrantApplier(dataSource);
    }
}
