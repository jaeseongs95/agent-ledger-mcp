package org.pgledger.migrate1x.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The services of the migration ({@code import-1x}, {@code retire-1x}): the import, the snapshot of the 1.x ledger it reads,
 * the removal of the 1.x objects and the mapper of the rows. This package is not scanned by the application: the database
 * configuration of the tool imports this class, so the services exist exactly when the tool has its database. It is in this
 * package because the mapper is package-private.
 */
@Configuration(proxyBeanMethods = false)
@Import({LegacyImportService.class, LegacySnapshotService.class, LegacyRetireService.class, LegacyRowMapper.class})
public class ImportServices {
}
