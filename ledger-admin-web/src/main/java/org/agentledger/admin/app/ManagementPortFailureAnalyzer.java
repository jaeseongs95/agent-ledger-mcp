// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.admin.app;

import java.util.Locale;
import org.agentledger.domain.error.InfrastructureCode;
import org.agentledger.domain.error.InfrastructureException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Explains why the admin web does not start when the actuator is given a port of its own (MANAGEMENT_PORT_SEPARATE, PCS-014): the
 * reason and what to do, in English, instead of a stack trace. The text comes from the message bundle of the error codes.
 */
public class ManagementPortFailureAnalyzer extends AbstractFailureAnalyzer<InfrastructureException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InfrastructureException cause) {
        if (cause.code() != InfrastructureCode.MANAGEMENT_PORT_SEPARATE) {
            return null;
        }
        // made with new: Spring Boot creates a failure analyzer from spring.factories (reflection, no-argument constructor)
        // while the application context failed to start, so no container exists to inject a MessageSource from
        ResourceBundleMessageSource bundles = new ResourceBundleMessageSource();
        bundles.setBasename("messages/ledger-errors");
        bundles.setFallbackToSystemLocale(false);
        String reason = bundles.getMessage(cause.code().messageKey(), cause.args().toArray(), cause.code().token(),
                Locale.ENGLISH);
        return new FailureAnalysis(cause.code().token() + ": " + reason,
                "Remove management.server.port and start the admin web again.", cause);
    }
}
