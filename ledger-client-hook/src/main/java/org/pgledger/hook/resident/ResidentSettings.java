// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.hook.resident;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The settings of the resident program, bound by Boot from {@code application.properties} and, as everywhere in the hook, from
 * the environment ({@code LEDGER_HOOK_RESIDENT_IDLE_TIMEOUT} is the relaxed form of {@code ledger.hook.resident.idle-timeout}).
 *
 * @param idleTimeout how long the program waits for a call before it ends by itself: nobody is left running that nobody uses.
 *                    A call that is being answered keeps it alive; the next call after it ended starts a new program.
 */
@ConfigurationProperties("ledger.hook.resident")
public record ResidentSettings(Duration idleTimeout) {}
