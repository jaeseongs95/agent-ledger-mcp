// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.database.support;

import org.agentledger.database.jdbc.LedgerJdbcConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/** The smallest Spring Boot application around the ledger repositories (DataSource and Clock are supplied). */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(LedgerJdbcConfiguration.class)
public class TestApplication {
}
