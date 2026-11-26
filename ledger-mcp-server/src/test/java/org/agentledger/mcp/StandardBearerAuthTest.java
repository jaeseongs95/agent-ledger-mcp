// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.agentledger.application.admin.GrantService;
import org.agentledger.application.admin.GrantService.GrantCommand;
import org.agentledger.application.admin.SessionTokenService;
import org.agentledger.application.admin.SessionTokenService.IssueCommand;
import org.agentledger.application.admin.SessionTokenService.IssuedToken;
import org.agentledger.application.gate.AttestationVerifier;
import org.agentledger.application.gate.ToolCall;
import org.agentledger.domain.error.TrustException;
import org.agentledger.domain.value.AuthorId;
import org.agentledger.mcp.caller.DefaultMcpCallerResolver;
import org.agentledger.mcp.config.AuthMode;
import org.agentledger.mcp.config.LedgerMcpProperties;
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
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

/**
 * Vendor-independent standard HTTP Bearer session-token authentication ({@link AuthMode#STANDARD}) and strict
 * {@link AuthMode#ATTESTED} enforcement. Standard MCP clients connect with {@code Authorization: Bearer lgt_...} and
 * zero client hook or {@code _hostAttestation}.
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
    @DisplayName("STD-001: standard Bearer lgt_... session tokens authenticate whoami, flat writes, deliverable completion, "
            + "independent review, and attachment isolation without any client hook or _hostAttestation")
    void standardBearerLifecycleAndIsolation(ApplicationContext context, Scenario scenario, RawHttp http,
            @Autowired SessionTokenService tokens, @Autowired GrantService grants, @Autowired JsonMapper mapper) {
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String author = "agent:author-" + suffix;
        String reviewer = "agent:reviewer-" + suffix;

        IssuedToken authorToken = tokens.issue(new IssueCommand(author, null, "author session"), TestOwners.OWNER);
        IssuedToken reviewerToken = tokens.issue(new IssueCommand(reviewer, null, "reviewer session"),
                TestOwners.OWNER);
        assertThat(authorToken.rawToken()).startsWith(SessionTokenService.TOKEN_PREFIX);

        grants.grant(new GrantCommand(author, scenario.projectId(),
                List.of("issue", "spec", "task", "deliverable", "verify"), null, "standard author"),
                TestOwners.OWNER);
        grants.grant(new GrantCommand(reviewer, scenario.projectId(), List.of("verify"), null, "standard reviewer"),
                TestOwners.OWNER);

        try (McpSession authorSession = McpSession.open(port, authorToken.rawToken(), mapper);
                McpSession reviewerSession = McpSession.open(port, reviewerToken.rawToken(), mapper)) {

            WriteOutputs.Whoami who = authorSession.ok("ledger_whoami", Map.of(), WriteOutputs.Whoami.class);
            assertThat(who.author()).isEqualTo(author);
            assertThat(who.authorFormatOk()).isTrue();
            assertThat(who.grants()).hasSize(5);

            // Issue creation + deliverable + independent review without _hostAttestation
            WriteOutputs.Saved savedIssue = authorSession.ok("ledger_issue_save",
                    Calls.IssueSave.create(scenario.projectId(), "continuity mismatch in reel 2", null),
                    WriteOutputs.Saved.class);
            assertThat(savedIssue.author()).isEqualTo(author);

            WriteOutputs.DeliverableRecorded issueDeliverable = authorSession.ok("ledger_record_deliverable",
                    Calls.RecordDeliverable.ofIssue(savedIssue.id(), "reel2-cut-v4", null),
                    WriteOutputs.DeliverableRecorded.class);
            assertThat(issueDeliverable.targetState()).isEqualTo("RESOLVED");

            ToolError selfVerifyIssue = authorSession.failed("ledger_review",
                    Calls.Review.of("issue", savedIssue.id(), 2, "VERIFIED", null));
            assertThat(selfVerifyIssue.code()).isEqualTo("SAME_SESSION");

            // Spec creation + deliverable + independent review without _hostAttestation
            WriteOutputs.Saved savedSpec = authorSession.ok("ledger_spec_save",
                    Calls.SpecSave.create(scenario.projectId(), "chapter 4 editorial spec", null),
                    WriteOutputs.Saved.class);
            WriteOutputs.DeliverableRecorded specDeliverable = authorSession.ok("ledger_record_deliverable",
                    Calls.RecordDeliverable.ofSpec(savedSpec.id(), "ch4-draft-final", null),
                    WriteOutputs.DeliverableRecorded.class);
            assertThat(specDeliverable.targetState()).isEqualTo("COMPLETED");

            // Attachment bundle upload + cross-author uploadId isolation in STANDARD mode
            byte[] payload = "independent review transcript".getBytes(StandardCharsets.UTF_8);
            String sha = sha256Hex(payload);
            AttachmentOutputs.Begun begun = authorSession.ok("artifact_begin", Map.of(
                    "operationKey", Calls.freshKey(),
                    "files", List.of(Map.of(
                            "fileName", "review.txt",
                            "mediaType", "text/plain",
                            "size", payload.length,
                            "sha256", sha))),
                    AttachmentOutputs.Begun.class);
            String uploadId = begun.files().getFirst().uploadId();

            ToolError crossAuthorChunk = reviewerSession.failed("artifact_chunk", Map.of(
                    "uploadId", uploadId,
                    "sequence", 0,
                    "data", Base64.getEncoder().encodeToString(payload),
                    "chunkSha256", sha));
            assertThat(crossAuthorChunk.code()).isEqualTo("ARTIFACT_NOT_FOUND");

            AttachmentOutputs.Received received = authorSession.ok("artifact_chunk", Map.of(
                    "uploadId", uploadId,
                    "sequence", 0,
                    "data", Base64.getEncoder().encodeToString(payload),
                    "chunkSha256", sha),
                    AttachmentOutputs.Received.class);
            assertThat(received.received()).isEqualTo(payload.length);

            ToolError crossAuthorFinish = reviewerSession.failed("artifact_finish",
                    Map.of("attachmentId", begun.attachmentId()));
            assertThat(crossAuthorFinish.code()).isEqualTo("ARTIFACT_NOT_FOUND");

            AttachmentOutputs.Finished finished = authorSession.ok("artifact_finish",
                    Map.of("attachmentId", begun.attachmentId()), AttachmentOutputs.Finished.class);
            assertThat(finished.files()).hasSize(1);

            AttachmentOutputs.Page page = reviewerSession.ok("artifact_read",
                    Map.of("attachmentId", begun.attachmentId(), "seq", 0, "offsetBytes", 0L, "maxBytes", 1024L),
                    AttachmentOutputs.Page.class);
            assertThat(page.text()).isEqualTo("independent review transcript");

            // Reviewer verifies both issue and spec without _hostAttestation
            WriteOutputs.Transitioned verifiedIssue = reviewerSession.ok("ledger_review",
                    Calls.Review.of("issue", savedIssue.id(), 2, "VERIFIED", null),
                    WriteOutputs.Transitioned.class);
            WriteOutputs.Transitioned verifiedSpec = reviewerSession.ok("ledger_review",
                    Calls.Review.of("spec", savedSpec.id(), 2, "VERIFIED", null),
                    WriteOutputs.Transitioned.class);
            assertThat(verifiedIssue.to()).isEqualTo("VERIFIED");
            assertThat(verifiedIssue.author()).isEqualTo(reviewer);
            assertThat(verifiedSpec.to()).isEqualTo("VERIFIED");
            assertThat(verifiedSpec.author()).isEqualTo(reviewer);

            ReadOutputs.Spec specDetail = reviewerSession.ok("ledger_spec", new Calls.IdOnly(savedSpec.id()),
                    ReadOutputs.Spec.class);
            assertThat(specDetail.completedBy()).isEqualTo(author);
            assertThat(specDetail.status()).isEqualTo("VERIFIED");
        }

        // Revoking the session token immediately rejects subsequent HTTP requests with 401
        assertThat(tokens.revoke(authorToken.id(), TestOwners.OWNER)).isTrue();
        RawHttp.Reply afterRevoke = http.callToolWithoutSession("ledger_projects", new Calls.Nothing(),
                Header.bearer(authorToken.rawToken()));
        assertThat(afterRevoke.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("STD-002: ATTESTED mode refuses omitted _hostAttestation even when an ambient Bearer author is present")
    void attestedModeRequiresHostAttestation(@Autowired AttestationVerifier attestationVerifier) {
        LedgerMcpProperties attestedProps = new LedgerMcpProperties("test-token-0123456789",
                List.of("127.0.0.1", "localhost"), List.of(), DataSize.ofMegabytes(1), Duration.ofSeconds(5),
                AuthMode.ATTESTED);
        DefaultMcpCallerResolver resolver = new DefaultMcpCallerResolver(attestationVerifier, attestedProps);

        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(AuthorId.of("agent:ambient-1"), null, List.of()));
        try {
            assertThat(resolver.hasAmbientCaller()).isFalse();
            ToolCall call = new ToolCall.Named("ledger_issue_save");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.verifyForWrite(null, call, "op-1"))
                    .isInstanceOf(TrustException.class)
                    .extracting(ex -> ((TrustException) ex).code().token())
                    .isEqualTo("HOST_ATTESTATION_REQUIRED");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
