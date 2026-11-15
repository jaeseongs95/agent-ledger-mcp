package org.pgledger.hook;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock of the hook: the one place that makes it (principle 3.7). The time of an attestation is the time of this
 * clock, in UTC; the tests pass a fixed clock to the objects that take one.
 */
@Configuration(proxyBeanMethods = false)
class HookClockConfiguration {

    /** A {@code @Bean} method because {@link Clock} is a JDK class that cannot carry an annotation: the one UTC clock. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
