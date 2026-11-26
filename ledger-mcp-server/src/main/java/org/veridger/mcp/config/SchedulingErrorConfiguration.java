// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.mcp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.task.SimpleAsyncTaskSchedulerCustomizer;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one place where the failure of a scheduled task is logged (with its cause); the schedule goes on. */
@Configuration(proxyBeanMethods = false)
public class SchedulingErrorConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SchedulingErrorConfiguration.class);

    /**
     * A {@code @Bean} method because the customizer is the one-method interface of Spring Boot, which the scheduler
     * auto-configuration looks up by type; it is made from a lambda, not from a class.
     */
    @Bean
    ThreadPoolTaskSchedulerCustomizer scheduledTaskErrorLogging() {
        return scheduler -> scheduler.setErrorHandler(failure -> LOG.error("a scheduled task failed", failure));
    }

    /**
     * Same error logging hook for the virtual-thread {@code SimpleAsyncTaskScheduler} auto-configured when
     * {@code spring.threads.virtual.enabled=true}.
     */
    @Bean
    SimpleAsyncTaskSchedulerCustomizer virtualScheduledTaskErrorLogging() {
        return scheduler -> scheduler.setErrorHandler(failure -> LOG.error("a scheduled task failed", failure));
    }
}
