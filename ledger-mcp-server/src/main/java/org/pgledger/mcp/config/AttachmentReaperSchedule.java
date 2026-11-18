// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.mcp.config;

import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.AttachmentReaper;
import org.pgledger.application.attachment.ConditionalOnAttachments;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Runs the reaper of the attachments (the uploads that expired, the rows of the uploads that were finished) at
 * {@code ledger.attachments.reaper-interval}, with a fixed delay: the next run starts that long after the previous one ended, so two
 * runs never overlap. A failure of one run is logged by the scheduler error handler ({@code SchedulingErrorConfiguration}) and the
 * next run goes on as usual.
 *
 * <p>A {@code @Configuration} class and not a component for the reason given at {@code CodeRevisionPoller}: the interval is a duration
 * from a setting, which {@link SchedulingConfigurer} takes and a {@code @Scheduled} annotation does not.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAttachments
public class AttachmentReaperSchedule implements SchedulingConfigurer {

    private final AttachmentReaper reaper;
    private final AttachmentProperties properties;

    public AttachmentReaperSchedule(AttachmentReaper reaper, AttachmentProperties properties) {
        this.reaper = reaper;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(reaper::reap, properties.reaperInterval());
    }
}
