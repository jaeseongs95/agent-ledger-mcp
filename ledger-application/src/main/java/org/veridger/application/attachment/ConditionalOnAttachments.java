// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.attachment;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * The component exists when the attachments are configured ({@code ledger.attachments.directory} is set). Every installation
 * renders that setting; an application that does not use attachments (a test of another area) does not have to set it. The
 * components of the attachments are found by the component scan of the module like every other one.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ConditionalOnProperty(prefix = "ledger.attachments", name = "directory")
public @interface ConditionalOnAttachments {
}
