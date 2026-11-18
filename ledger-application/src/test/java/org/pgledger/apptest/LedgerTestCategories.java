// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.apptest;

import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.testing.TestOwners;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Seeds the baseline issue categories of an initialized installation for service tests: one active and one retired.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LedgerTestCategories {

    public record CategoriesSeeded() {
    }

    @Bean
    CategoriesSeeded categoriesSeeded(CommonCodeService codes, LedgerTestInfrastructure.SchemaMigrated schema) {
        codes.set(new CodeCommand("ISSUE_CATEGORY", "ARC", "구조", "Architecture", null, null, 1, "ACTIVE"),
                TestOwners.OWNER);
        codes.set(new CodeCommand("ISSUE_CATEGORY", "RETIRED", "폐기", "Retired", null, null, 9, "INACTIVE"),
                TestOwners.OWNER);
        return new CategoriesSeeded();
    }
}
