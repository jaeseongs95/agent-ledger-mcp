// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.support;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.pgledger.admin.security.LoginDelay;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A {@link LoginDelay} that waits for nothing and records the delay each credential check was given (a check inside the
 * budget gets {@link Duration#ZERO}), so a test can assert what the throttle decided without sleeping and without a
 * measurement of time.
 */
public final class RecordingLoginDelay extends LoginDelay {

    private final List<Duration> waits = new CopyOnWriteArrayList<>();

    @Override
    public void await(Duration delay) {
        waits.add(delay);
    }

    /** The delays given so far, in the order of the checks. */
    public List<Duration> waits() {
        return List.copyOf(waits);
    }

    /** Registers one recording delay as the {@link LoginDelay} of the context. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {

        @Bean
        @Primary
        RecordingLoginDelay recordingLoginDelay() {
            return new RecordingLoginDelay();
        }
    }
}
