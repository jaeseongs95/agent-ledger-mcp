package org.pgledger.domain.rule;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.pgledger.domain.value.SessionKey;

/** The transitions of one task, oldest first; tells who reported it done last and whether a session reported it at all. */
public record TaskReports(List<TaskTransition> transitions) {

    public TaskReports {
        transitions = List.copyOf(transitions);
    }

    /**
     * The session of the latest REPORTED_DONE transition that did not come from VERIFIED (sending a verified task
     * back is not a report).
     */
    public Optional<SessionKey> latestReporter() {
        return latestReport().map(t -> t.author().sessionKey());
    }

    /** The latest REPORTED_DONE transition that did not come from VERIFIED: who made it, as the history recorded it. */
    public Optional<TaskTransition> latestReport() {
        return transitions.reversed().stream().filter(TaskReports::isReport).findFirst();
    }

    /**
     * Whether {@code session} reported the task done at any time (a REPORTED_DONE transition that did not come from
     * VERIFIED): the session that reported it, even before another one reported it again, cannot verify it.
     */
    public boolean reportedBy(SessionKey session) {
        return transitions.stream().filter(TaskReports::isReport)
                .anyMatch(t -> Objects.equals(t.author().sessionKey(), session));
    }

    private static boolean isReport(TaskTransition t) {
        return t.to() == TaskState.COMPLETED && t.from() != TaskState.VERIFIED;
    }
}
