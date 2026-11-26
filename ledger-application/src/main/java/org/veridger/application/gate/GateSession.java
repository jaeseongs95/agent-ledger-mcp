// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application.gate;

import java.util.function.Supplier;
import org.veridger.domain.context.ActorScope;
import org.veridger.domain.rule.Channel;
import org.veridger.domain.value.AuthorId;

/** The trusted caller of one write: who, through which channel. */
public record GateSession(AuthorId author, Channel channel) {

    /** Runs the work with the author bound for auditing; the binding ends when the work returns. */
    public <T> T bind(Supplier<T> work) {
        return ScopedValue.where(ActorScope.CURRENT, author).call(work::get);
    }
}
