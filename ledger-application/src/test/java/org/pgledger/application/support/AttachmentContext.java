// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pgledger.apptest.LedgerTestCategories;
import org.pgledger.apptest.LedgerTestInfrastructure;
import org.pgledger.apptest.LedgerTestSettings;
import org.pgledger.testing.database.TestSchemaInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

/**
 * A service test class of the attachments: the same application, database schema and categories as {@link LedgerServiceTest}, and
 * in addition an attachment directory, a key directory and an active key ({@link AttachmentDirectories}), so that the upload
 * services, the store, the reaper, the check and the rekey exist. The annotation repeats the declarations of
 * {@link LedgerServiceTest} instead of carrying it, because a second {@code initializers} list next to the one of
 * {@link LedgerServiceTest} is not merged with it for sure. No transaction wraps a test method (see {@link LedgerServiceTest}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {LedgerTestSettings.QUIET_BANNER, LedgerTestSettings.QUIET_LOG, LedgerTestSettings.NO_JMX})
@ContextConfiguration(initializers = {TestSchemaInitializer.class, AttachmentDirectories.class})
@Import({LedgerTestInfrastructure.class, LedgerTestCategories.class})
@ExtendWith(ScenarioExtension.class)
public @interface AttachmentContext {
}
