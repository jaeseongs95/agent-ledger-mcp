// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.application.attachment;

import java.security.SecureRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The settings record of the attachments and the random source of the storage keys, IVs and keys (one {@link SecureRandom}). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAttachments
@EnableConfigurationProperties(AttachmentProperties.class)
public class AttachmentConfiguration {

    /** The name of the random source: other modules of the product have a {@link SecureRandom} of their own, so it is asked for by name. */
    public static final String RANDOM = "attachmentSecureRandom";

    /** A {@code @Bean} method because {@link SecureRandom} is a JDK class; the components of the attachments ask for it by its name. */
    @Bean(name = RANDOM)
    SecureRandom attachmentSecureRandom() {
        return new SecureRandom();
    }
}
