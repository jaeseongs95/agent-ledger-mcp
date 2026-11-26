// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.IdGenerator;
import org.springframework.util.JdkIdGenerator;

/**
 * The one generator of random ids of the Spring applications (trace ids of a server failure): Spring's {@link IdGenerator}
 * as a bean, so that a test can replace it and no class makes a random id itself. It is found by the component scan of
 * {@link LedgerApplicationConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class LedgerIdConfiguration {

    @Bean
    IdGenerator idGenerator() {
        return new JdkIdGenerator();
    }
}
