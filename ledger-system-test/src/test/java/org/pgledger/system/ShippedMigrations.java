package org.pgledger.system;

import org.pgledger.database.schema.MigrationFile;
import org.pgledger.database.schema.SchemaProperties;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** The schema files the product ships (V1 .. Vn of {@code classpath:db/}), read as the migrator reads them. */
final class ShippedMigrations {

    private ShippedMigrations() {
    }

    /** The number of schema files, so a new migration does not need a changed literal in the system tests. */
    static int count() {
        return MigrationFile.load(new PathMatchingResourcePatternResolver(ShippedMigrations.class.getClassLoader()),
                SchemaProperties.DEFAULT_LOCATION).size();
    }
}
