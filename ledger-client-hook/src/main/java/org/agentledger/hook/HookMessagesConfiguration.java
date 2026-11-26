// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.AbstractApplicationContext;

/** The one place that makes the message source of the context (see {@link HookMessages}). */
@Configuration(proxyBeanMethods = false)
class HookMessagesConfiguration {

    /** Named as the context looks for it ({@link AbstractApplicationContext#MESSAGE_SOURCE_BEAN_NAME}). */
    @Bean(AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME)
    MessageSource messageSource() {
        return new HookMessages();
    }
}
