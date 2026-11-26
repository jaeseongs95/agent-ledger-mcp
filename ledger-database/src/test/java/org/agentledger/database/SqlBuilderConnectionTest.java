// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.database.driver.DatabaseProductName;
import org.agentledger.database.repository.SqlBuilder;
import org.agentledger.database.repository.SqlBuilder.Database;
import org.agentledger.testing.database.TestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SQB-009 .. SQB-011: {@link SqlBuilder} on the PostgreSQL of the test PC (the shared pool of the test database support, which is
 * a DataSource bean of the application in the same way): the product is detected from the real connection, once, also when
 * threads ask at the same moment and when the only connection of the pool is held by a transaction; and a query with the limit
 * of the builder returns at most the rows it was asked for.
 */
class SqlBuilderConnectionTest {

    private static final String ROW_LIMIT = "FETCH FIRST ? ROWS ONLY";

    /**
     * The shared pool behind a counter: how many connections were asked for, how many times the metadata was read, and at
     * most {@code connections} of them taken at the same moment (a pool of that size, whatever the shared pool holds).
     */
    private static final class CountingDataSource extends DelegatingDataSource {

        private final Semaphore permits;
        private final AtomicInteger asks = new AtomicInteger();
        private final AtomicInteger metadataReads = new AtomicInteger();

        CountingDataSource(DataSource target, int connections) {
            super(target);
            this.permits = new Semaphore(connections);
        }

        int asks() {
            return asks.get();
        }

        int metadataReads() {
            return metadataReads.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            asks.incrementAndGet();
            permits.acquireUninterruptibly();
            boolean taken = false;
            try {
                Connection real = super.getConnection();
                taken = true;
                return counted(real);
            } finally {
                if (!taken) {
                    permits.release();
                }
            }
        }

        private Connection counted(Connection real) {
            AtomicBoolean released = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if ("getMetaData".equals(method.getName())) {
                            metadataReads.incrementAndGet();
                        }
                        if ("close".equals(method.getName()) && released.compareAndSet(false, true)) {
                            permits.release();
                        }
                        return invoke(real, method, args);
                    });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                // rethrown unchanged: a proxy must show the caller the exception of the real call
                throw e.getCause();
            }
        }
    }

    @Test
    @DisplayName("SQB-009 the product of the test database is detected as PostgreSQL, and the clause is the one of that product")
    void thePostgreSqlOfTheTestsIsDetected() throws SQLException {
        DataSource pool = TestDatabase.shared().pool();
        try (Connection connection = pool.getConnection()) {
            assertThat(SqlBuilder.ofProductName(DatabaseProductName.of(connection))).isEqualTo(Database.POSTGRESQL);
            SqlBuilder.requireSame(TestDatabase.shared().settings().url(), DatabaseProductName.of(connection));
        }
        assertThat(new SqlBuilder(pool).generateLimit("?")).isEqualTo(ROW_LIMIT);
    }

    @Test
    @DisplayName("SQB-009 threads that ask at the same moment resolve the product once")
    void concurrentCallsResolveTheProductOnce() throws Exception {
        CountingDataSource source = new CountingDataSource(TestDatabase.shared().pool(), 4);
        SqlBuilder builder = new SqlBuilder(source);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return builder.generateLimit("?");
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(ROW_LIMIT);
            }
        }
        assertThat(source.metadataReads()).isEqualTo(1);
        // resolved: a later call does not ask the database at all
        int asked = source.asks();
        assertThat(builder.generateLimit("?")).isEqualTo(ROW_LIMIT);
        assertThat(source.asks()).isEqualTo(asked);
    }

    @Test
    @DisplayName("SQB-010 a transaction that holds the only connection of the pool resolves the product while another thread waits")
    void aTransactionHoldingTheOnlyConnectionCanResolveWhileAnotherThreadWaits() throws Exception {
        CountingDataSource source = new CountingDataSource(TestDatabase.shared().pool(), 1);
        SqlBuilder builder = new SqlBuilder(source);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        AtomicReference<Future<String>> waiting = new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                // the transaction has the one connection (one ask); the other thread asks for one and waits for it
                waiting.set(executor.submit(() -> builder.generateLimit("?")));
                await().atMost(Duration.ofSeconds(5)).until(() -> source.asks() >= 2);
                assertThat(builder.generateLimit("?")).isEqualTo(ROW_LIMIT);
                assertThat(waiting.get().isDone()).isFalse();
            });
            assertThat(waiting.get().get(10, TimeUnit.SECONDS)).isEqualTo(ROW_LIMIT);
        }
        assertThat(source.metadataReads()).isEqualTo(1);
    }

    @Test
    @DisplayName("SQB-011 a query with the limit of the builder returns at most the rows asked for, a placeholder carries the number")
    void aQueryWithTheLimitReturnsAtMostTheRowsAskedFor() {
        DataSource pool = TestDatabase.shared().pool();
        SqlBuilder builder = new SqlBuilder(pool);
        String sql = builder.applyLimit("SELECT n FROM (VALUES (1), (2), (3), (4), (5)) AS v(n) ORDER BY n", ":row_count");
        assertThat(sql).endsWith("ORDER BY n FETCH FIRST :row_count ROWS ONLY");
        JdbcClient jdbc = JdbcClient.create(pool);
        for (int max : new int[] {1, 3, 5, 6, 1000}) {
            assertThat(jdbc.sql(sql).param("row_count", max).query(Integer.class).list())
                    .as("limit " + max).containsExactlyElementsOf(List.of(1, 2, 3, 4, 5).subList(0, Math.min(max, 5)));
        }
    }
}
