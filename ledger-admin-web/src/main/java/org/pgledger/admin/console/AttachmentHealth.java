// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.console;

import java.util.List;
import org.pgledger.application.attachment.AttachmentCheck;
import org.pgledger.domain.error.InfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The read-only result of the attachment check for the owner's page: {@code AttachmentCheck.run(false)} reports what the command
 * {@code attachments-check} would move or find, and moves nothing. The check opens every attachment file, so it runs when the page
 * is asked for and not on every load of the overview. The check exists only when the attachments are configured. Nothing here holds
 * a storage key or a path: only counts and attachment ids.
 */
@Component
public class AttachmentHealth {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentHealth.class);

    /** At most this many ids of unreferenced attachments are listed; the count is always the whole number. */
    static final int MAX_LISTED = 200;

    /** What the page can say about the check. */
    public enum State {
        /** The attachments are not configured on this installation. */
        NOT_CONFIGURED,
        /** The check could not run (for example a key file is missing). */
        FAILED,
        /** The check ran. */
        CHECKED
    }

    /**
     * The result as the page shows it.
     *
     * @param wouldMoveOrphans files without a row that the command would move (older than the grace)
     * @param waitingOrphans   files without a row that are still within the grace
     * @param replaced         replaced files that the command would move
     * @param restored         quarantined files that the command would move back
     * @param quarantined      files in the quarantine folder
     * @param unreferenced     the ids of the first unreferenced attachments (at most {@link #MAX_LISTED})
     * @param unreferencedCount how many attachments no history row points to
     */
    public record Health(State state, int wouldMoveOrphans, int waitingOrphans, int replaced, int restored, int quarantined,
            int attachments, List<String> missing, List<String> damaged, List<String> unreferenced, int unreferencedCount) {

        public Health {
            missing = List.copyOf(missing);
            damaged = List.copyOf(damaged);
            unreferenced = List.copyOf(unreferenced);
        }

        static Health without(State state) {
            return new Health(state, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of(), 0);
        }

        public boolean checked() {
            return state == State.CHECKED;
        }
    }

    private final ObjectProvider<AttachmentCheck> checks;

    public AttachmentHealth(ObjectProvider<AttachmentCheck> checks) {
        this.checks = checks;
    }

    public Health health() {
        AttachmentCheck check = checks.getIfAvailable();
        if (check == null) {
            return Health.without(State.NOT_CONFIGURED);
        }
        try {
            AttachmentCheck.Report report = check.run(false);
            List<String> listed = report.unreferenced().size() > MAX_LISTED
                    ? report.unreferenced().subList(0, MAX_LISTED) : report.unreferenced();
            return new Health(State.CHECKED, report.orphansQuarantined(), report.orphansWaiting(),
                    report.replacedQuarantined(), report.restored(), report.quarantined(), report.attachments(),
                    report.missing(), report.damaged(), listed, report.unreferenced().size());
        } catch (InfrastructureException failed) {
            LOG.warn("The attachment check could not run: {}", failed.code());
            return Health.without(State.FAILED);
        }
    }
}
