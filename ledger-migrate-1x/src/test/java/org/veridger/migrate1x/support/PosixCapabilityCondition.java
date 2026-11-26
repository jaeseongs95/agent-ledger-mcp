// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.migrate1x.support;

import java.nio.file.FileSystems;
import java.util.Set;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/** Evaluates {@link RequiresPosixPermissions} and {@link RequiresOwnerOnlyStorage} from the file system capability. */
final class PosixCapabilityCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Set<String> views = FileSystems.getDefault().supportedFileAttributeViews();
        boolean posix = views.contains("posix");
        var element = context.getElement();
        if (element.isEmpty()) {
            return ConditionEvaluationResult.enabled("no annotated element");
        }
        if (AnnotationSupport.isAnnotated(element.get(), RequiresPosixPermissions.class) && !posix) {
            return ConditionEvaluationResult.disabled("the default file system has no POSIX permissions");
        }
        boolean acl = views.contains("acl");
        if (AnnotationSupport.isAnnotated(element.get(), RequiresOwnerOnlyStorage.class) && !posix && !acl) {
            return ConditionEvaluationResult.disabled("the default file system has neither POSIX permissions nor ACLs");
        }
        return ConditionEvaluationResult.enabled("file system capability matches");
    }
}
