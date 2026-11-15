package org.pgledger.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pgledger.database.driver.DriverDataSources;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.pgledger.testing.database.TestDatabase;

/**
 * SQB-008: where the pool is made ready ({@link DriverDataSources#create} calls {@code requireConfiguredProduct}), the product
 * of the connections is compared with the product the configured JDBC URL names. The connections here are those of the
 * PostgreSQL of the test PC; a URL of another product stands for a configuration that does not match the driver (the product-name
 * path: no other DBMS is needed). The pools that the driver tests make with the PostgreSQL URL (DRV-006, DRV-019) are the
 * accepting side of {@code create}.
 */
class DriverProductCheckTest {

    private static InfrastructureCode codeOf(DataSource pool, String url) {
        return (InfrastructureCode) catchThrowableOfType(InfrastructureException.class,
                () -> DriverDataSources.requireConfiguredProduct(pool, url)).code();
    }

    @Test
    @DisplayName("SQB-008 the URL of the product the pool connects to is accepted")
    void theUrlOfTheConnectedProductIsAccepted() {
        DataSource pool = TestDatabase.shared().pool();
        assertThatCode(() -> DriverDataSources.requireConfiguredProduct(pool, TestDatabase.shared().settings().url()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SQB-008 a URL of another product is refused with both products named, a product with no SQL is refused")
    void aUrlOfAnotherProductIsRefused() {
        DataSource pool = TestDatabase.shared().pool();
        assertThatThrownBy(() -> DriverDataSources.requireConfiguredProduct(pool, "jdbc:mysql://localhost/db"))
                .isInstanceOfSatisfying(InfrastructureException.class, refused -> {
                    assertThat(refused.code()).isEqualTo(InfrastructureCode.DATABASE_PRODUCT_MISMATCH);
                    assertThat(refused.args()).containsExactly("MYSQL", "POSTGRESQL");
                });
        assertThat(codeOf(pool, "jdbc:mariadb://localhost/db")).isEqualTo(InfrastructureCode.DATABASE_PRODUCT_MISMATCH);
        assertThat(codeOf(pool, "jdbc:oracle:thin:@localhost:1521/db"))
                .isEqualTo(InfrastructureCode.DATABASE_PRODUCT_MISMATCH);
        assertThat(codeOf(pool, "jdbc:derby:memory:x")).isEqualTo(InfrastructureCode.DATABASE_PRODUCT_UNSUPPORTED);
    }
}
