// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veridger.application.admin.GrantService;
import org.veridger.application.admin.GrantService.GrantCommand;
import org.veridger.application.admin.GrantService.RevokeCommand;
import org.veridger.application.attachment.AttachmentFlow;
import org.veridger.application.attachment.UploadCommands.Begun;
import org.veridger.application.gate.AuthorAttestationVerifier;
import org.veridger.application.support.AttachmentContext;
import org.veridger.application.support.Expect;
import org.veridger.application.support.Scenario;
import org.veridger.application.support.Uploads;
import org.veridger.application.write.RecordDeliverableCommand;
import org.veridger.application.write.RecordDeliverableService;
import org.veridger.application.write.ReviewService;
import org.veridger.application.write.SaveService;
import org.veridger.application.write.TransitionCommand;
import org.veridger.application.write.TransitionService;
import org.veridger.application.write.WriteResult.SaveResult;
import org.veridger.domain.error.TransitionCode;
import org.veridger.domain.error.TrustCode;
import org.veridger.testing.TestOwners;

/**
 * Regression tests for v4.3.1 application security fixes:
 * APP-01 (merged source issue contributors cannot self-verify the root issue),
 * APP-05 (unsigned uploadId lookup still requires a live grant for the upload owner),
 * and MCP-06 (reserved owner identity {@code user:owner} / {@code user:*} rejected by attestation & grant APIs).
 */
@AttachmentContext
class SecurityRemediationTest {

    @Test
    @DisplayName("SEC-APP-01: editor, owner, and resolver of a merged source issue are all blocked from verifying the root issue (SAME_SESSION)")
    void app01_mergedSourceContributorsCannotVerifyRoot(Scenario s) {
        String sourceEditor = s.actorWith("issue", "verify");
        String sourceDeliverer = s.actorWith("deliverable", "verify");
        String sourceAmender = s.actorWith("deliverable", "verify");
        String sourceResolver = s.actorWith("issue", "verify");
        String independentReviewer = s.actorWith("verify");

        SaveService save = s.bean(SaveService.class);
        RecordDeliverableService deliverables = s.bean(RecordDeliverableService.class);
        TransitionService transition = s.bean(TransitionService.class);
        ReviewService review = s.bean(ReviewService.class);

        // Create root issue and record deliverable so root is RESOLVED
        SaveResult root = s.resolvedIssue("root issue");

        // Create source issue by AUTHOR, edit by sourceEditor (becoming owner), deliver (autoResolve=false) by
        // sourceDeliverer, amend deliverable check evidence by sourceAmender (does not change owner or write ISSUE
        // history), resolve by sourceResolver, then merge into root
        SaveResult source = s.newIssue("merged source issue");
        save.save(sourceEditor, s.edit("issue", source.id(), 1L,
                Map.of("title", "merged source issue edited", "owner_actor_id", sourceEditor)));
        deliverables.recordDeliverable(sourceDeliverer, new RecordDeliverableCommand(Scenario.uuid(), "issue",
                source.id(), "code_change", Scenario.ref('s'), Scenario.REASON, null, "source fix", null, null, null,
                false));
        deliverables.recordDeliverable(sourceAmender, new RecordDeliverableCommand(Scenario.uuid(), "issue",
                source.id(), "code_change", Scenario.ref('s'), Scenario.REASON, null, "source fix", "ci-check-1",
                "passed", null, false));
        transition.transition(sourceResolver, new TransitionCommand(Scenario.uuid(), "issue", source.id(),
                s.issue(source.id()).version(), "RESOLVED", Scenario.REASON, null, null, null));
        transition.transition(Scenario.AUTHOR, new TransitionCommand(Scenario.uuid(), "issue", source.id(),
                s.issue(source.id()).version(), "MERGED", Scenario.REASON, null, null, root.id()));

        long rootVersion = s.issue(root.id()).version();

        // sourceEditor, sourceDeliverer, sourceAmender, and sourceResolver must all be blocked by SAME_SESSION
        for (String contributor : List.of(sourceEditor, sourceDeliverer, sourceAmender, sourceResolver)) {
            Expect.failure(() -> review.review(contributor,
                    s.move("issue", root.id(), rootVersion, "VERIFIED", Scenario.EVIDENCE)),
                    TransitionCode.SAME_SESSION);
        }

        // An independent reviewer with verify scope succeeds
        review.review(independentReviewer, s.move("issue", root.id(), rootVersion, "VERIFIED", Scenario.EVIDENCE));
        assertThat(s.issue(root.id()).status().name()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("SEC-MCP-06: user:owner and user:* are rejected by AuthorAttestationVerifier and GrantService")
    void mcp06_reservedOwnerIdentityIsRejectedForSessionsAndGrants(Scenario s) {
        AuthorAttestationVerifier verifier = s.bean(AuthorAttestationVerifier.class);
        GrantService grants = s.bean(GrantService.class);

        for (String reserved : List.of("user:owner", "user:admin", "USER:owner")) {
            Expect.failure(() -> verifier.verifyForWrite(reserved, null, null), TrustCode.AUTHOR_ID_FORMAT);
            Expect.failure(() -> grants.grant(new GrantCommand(reserved, s.projectId(), List.of("issue"), null, null),
                    TestOwners.OWNER), TrustCode.AUTHOR_ID_FORMAT);
        }
    }

    @Test
    @DisplayName("SEC-APP-05: unsigned uploadStatus is refused with SESSION_NOT_GRANTED once the upload owner's grant is revoked")
    void app05_unsignedUploadStatusRequiresLiveOwnerGrant(Scenario s) {
        String actor = s.actorWith("issue");
        AttachmentFlow flow = s.bean(AttachmentFlow.class);
        byte[] body = Uploads.body(4096, 7);
        Begun begun = flow.begin(actor, Uploads.begin(body));
        String uploadId = begun.files().getFirst().uploadId();

        // While owner has a live grant, unsigned uploadStatus succeeds
        assertThat(flow.uploadStatus(null, uploadId).state()).isEqualTo("STAGING");

        // Once owner's grant is revoked, unsigned uploadStatus must be refused
        s.bean(GrantService.class).revoke(new RevokeCommand(actor, s.projectId(), null), TestOwners.OWNER);
        Expect.failure(() -> flow.uploadStatus(null, uploadId), TrustCode.SESSION_NOT_GRANTED);
    }
}
