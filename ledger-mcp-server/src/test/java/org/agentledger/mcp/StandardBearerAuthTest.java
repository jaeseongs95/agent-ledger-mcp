// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.admin.GrantService;
import org.agentledger.application.admin.GrantService.GrantCommand;
import org.agentledger.mcp.error.ToolError;
import org.agentledger.mcp.support.Calls;
import org.agentledger.mcp.support.McpServerTest;
import org.agentledger.mcp.support.McpSession;
import org.agentledger.mcp.support.RawHttp;
import org.agentledger.mcp.support.RawHttp.Header;
import org.agentledger.mcp.support.Scenario;
import org.agentledger.mcp.tool.AttachmentOutputs;
import org.agentledger.mcp.tool.ReadOutputs;
import org.agentledger.mcp.tool.WriteOutputs;
import org.agentledger.testing.TestOwners;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the v4.0.0 public-internet MCP perimeter: {@code ledger.mcp.token} is the sole accepted Bearer token (no
 * per-session {@code lgt_...} token is accepted), {@code _hostAttestation} is required as a plain
 * {@code <vendor>:<session_id>}, and full issue/spec/deliverable/independent-review/attachment isolation holds.
 */
@McpServerTest
class StandardBearerAuthTest {

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    @DisplayName("STD-001: shared MCP Bearer token + direct session grants complete full issue/spec/review/attachment lifecycle with author isolation")
    void sharedBearerLifecycleAndIsolation(McpSession session, Scenario scenario,
            @Autowired GrantService grants) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String author = "agent:author-" + suffix;
        String reviewer = "agent:reviewer-" + suffix;

        grants.grant(new GrantCommand(author, scenario.projectId(),
                List.of("issue", "spec", "task", "deliverable", "verify"), null, "author session"),
                TestOwners.OWNER);
        grants.grant(new GrantCommand(reviewer, scenario.projectId(), List.of("verify"), null, "reviewer session"),
                TestOwners.OWNER);

        WriteOutputs.Whoami who = session.ok("ledger_whoami", Map.of("_hostAttestation", author),
                WriteOutputs.Whoami.class);
        assertThat(who.author()).isEqualTo(author);
        assertThat(who.authorFormatOk()).isTrue();
        assertThat(who.grants()).hasSize(5);

        // Issue creation + deliverable + SAME_SESSION self-verify refusal
        WriteOutputs.Saved savedIssue = session.ok("ledger_issue_save",
                Calls.IssueSave.create(scenario.projectId(), "continuity mismatch in reel 2", author),
                WriteOutputs.Saved.class);
        assertThat(savedIssue.author()).isEqualTo(author);

        WriteOutputs.DeliverableRecorded issueDeliverable = session.ok("ledger_record_deliverable",
                Calls.RecordDeliverable.ofIssue(savedIssue.id(), "reel2-cut-v4", author),
                WriteOutputs.DeliverableRecorded.class);
        assertThat(issueDeliverable.targetState()).isEqualTo("RESOLVED");

        ToolError selfVerifyIssue = session.failed("ledger_review",
                Calls.Review.of("issue", savedIssue.id(), 2, "VERIFIED", author));
        assertThat(selfVerifyIssue.code()).isEqualTo("SAME_SESSION");

        // Spec creation + deliverable
        WriteOutputs.Saved savedSpec = session.ok("ledger_spec_save",
                Calls.SpecSave.create(scenario.projectId(), "chapter 4 editorial spec", author),
                WriteOutputs.Saved.class);
        WriteOutputs.DeliverableRecorded specDeliverable = session.ok("ledger_record_deliverable",
                Calls.RecordDeliverable.ofSpec(savedSpec.id(), "ch4-draft-final", author),
                WriteOutputs.DeliverableRecorded.class);
        assertThat(specDeliverable.targetState()).isEqualTo("COMPLETED");

        // Attachment upload + cross-author uploadId isolation
        byte[] payload = "independent review transcript".getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(payload);
        AttachmentOutputs.Begun begun = session.ok("artifact_begin", Map.of(
                "_hostAttestation", author,
                "operationKey", Calls.freshKey(),
                "files", List.of(Map.of(
                        "fileName", "review.txt",
                        "mediaType", "text/plain",
                        "size", payload.length,
                        "sha256", sha))),
                AttachmentOutputs.Begun.class);
        String uploadId = begun.files().getFirst().uploadId();

        ToolError crossAuthorChunk = session.failed("artifact_chunk", Map.of(
                "_hostAttestation", reviewer,
                "uploadId", uploadId,
                "sequence", 0,
                "data", Base64.getEncoder().encodeToString(payload),
                "chunkSha256", sha));
        assertThat(crossAuthorChunk.code()).isEqualTo("ARTIFACT_NOT_FOUND");

        AttachmentOutputs.Received received = session.ok("artifact_chunk", Map.of(
                "_hostAttestation", author,
                "uploadId", uploadId,
                "sequence", 0,
                "data", Base64.getEncoder().encodeToString(payload),
                "chunkSha256", sha),
                AttachmentOutputs.Received.class);
        assertThat(received.received()).isEqualTo(payload.length);

        ToolError crossAuthorFinish = session.failed("artifact_finish",
                Map.of("_hostAttestation", reviewer, "attachmentId", begun.attachmentId()));
        assertThat(crossAuthorFinish.code()).isEqualTo("ARTIFACT_NOT_FOUND");

        AttachmentOutputs.Finished finished = session.ok("artifact_finish",
                Map.of("_hostAttestation", author, "attachmentId", begun.attachmentId()),
                AttachmentOutputs.Finished.class);
        assertThat(finished.files()).hasSize(1);

        AttachmentOutputs.Page page = session.ok("artifact_read",
                Map.of("_hostAttestation", reviewer, "attachmentId", begun.attachmentId(),
                        "seq", 0, "offsetBytes", 0L, "maxBytes", 1024L),
                AttachmentOutputs.Page.class);
        assertThat(page.text()).isEqualTo("independent review transcript");

        // Reviewer independently verifies both issue and spec
        WriteOutputs.Transitioned verifiedIssue = session.ok("ledger_review",
                Calls.Review.of("issue", savedIssue.id(), 2, "VERIFIED", reviewer),
                WriteOutputs.Transitioned.class);
        WriteOutputs.Transitioned verifiedSpec = session.ok("ledger_review",
                Calls.Review.of("spec", savedSpec.id(), 2, "VERIFIED", reviewer),
                WriteOutputs.Transitioned.class);
        assertThat(verifiedIssue.to()).isEqualTo("VERIFIED");
        assertThat(verifiedIssue.author()).isEqualTo(reviewer);
        assertThat(verifiedSpec.to()).isEqualTo("VERIFIED");
        assertThat(verifiedSpec.author()).isEqualTo(reviewer);

        ReadOutputs.Spec specDetail = session.ok("ledger_spec", new Calls.IdOnly(savedSpec.id()),
                ReadOutputs.Spec.class);
        assertThat(specDetail.completedBy()).isEqualTo(author);
        assertThat(specDetail.status()).isEqualTo("VERIFIED");
    }

    @Test
    @DisplayName("STD-002: retired lgt_... tokens and wrong Bearer tokens are rejected 401 on /mcp, and omitted _hostAttestation is refused")
    void perimeterRejectsNonServerTokensAndMissingAttestation(McpSession session, Scenario scenario, RawHttp http) {
        RawHttp.Reply missingBearer = http.callToolWithoutSession("ledger_projects", new Calls.Nothing());
        assertThat(missingBearer.status()).isEqualTo(401);

        RawHttp.Reply retiredSessionToken = http.callToolWithoutSession("ledger_projects", new Calls.Nothing(),
                Header.bearer("lgt_retiredSessionToken0123456789"));
        assertThat(retiredSessionToken.status()).isEqualTo(401);

        RawHttp.Reply wrongBearer = http.callToolWithoutSession("ledger_projects", new Calls.Nothing(),
                Header.bearer("wrong-bearer-token"));
        assertThat(wrongBearer.status()).isEqualTo(401);

        ToolError missingAttestation = session.failed("ledger_issue_save",
                Calls.IssueSave.create(scenario.projectId(), "missing attestation", null));
        assertThat(missingAttestation.code()).isEqualTo("HOST_ATTESTATION_REQUIRED");
    }
}
