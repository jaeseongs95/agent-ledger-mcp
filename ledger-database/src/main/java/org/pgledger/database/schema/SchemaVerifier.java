// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.database.schema;

import java.util.List;
import org.pgledger.database.repository.SchemaHistoryRepository;
import org.pgledger.domain.error.InfrastructureCode;
import org.pgledger.domain.error.InfrastructureException;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;

/**
 * What an application does about the schema at startup: it only looks, it never changes anything. The schema is
 * up to date when every file has a history whose latest outcome is SUCCEEDED with the digest of the file, and the
 * history knows no version without a file ({@link SchemaHistory} judges it). Anything else stops the application, naming the
 * command that the operator runs ({@code ledger-cli migrate}; the server never holds the password of the DDL account, see
 * {@link SchemaMigrator}).
 */
@Component
public class SchemaVerifier {

    private final SchemaHistoryRepository repository;
    private final ResourcePatternResolver resolver;
    private final SchemaProperties properties;

    public SchemaVerifier(SchemaHistoryRepository repository, ResourcePatternResolver resolver,
            SchemaProperties properties) {
        this.repository = repository;
        this.resolver = resolver;
        this.properties = properties;
    }

    /**
     * @throws InfrastructureException one of the schema codes ({@code SCHEMA_*}) when the schema is not up to date
     */
    public void verify() {
        List<MigrationFile> files = MigrationFile.load(resolver, properties.location());
        MigrationFile.requireContiguous(files);
        try {
            if (!repository.tableExists()) {
                throw new InfrastructureException(InfrastructureCode.SCHEMA_HISTORY_MISSING, List.of());
            }
            SchemaHistory history = new SchemaHistory(repository.rows());
            history.requireFileForEveryVersion(files);
            for (MigrationFile file : files) {
                switch (history.verdictOf(file)) {
                    case INTACT -> {
                    }
                    case PENDING -> throw SchemaHistory.pending(file);
                    case FAILED_ONLY, LATEST_FAILED -> throw SchemaHistory.recordedFailed(file);
                    case DIGEST_CHANGED -> throw history.digestMismatch(file);
                }
            }
        } catch (CannotGetJdbcConnectionException e) {
            throw new InfrastructureException(InfrastructureCode.SCHEMA_DATABASE_UNREACHABLE, List.of(), e);
        }
    }
}
