package org.pgledger.mcp.tool;

import org.pgledger.application.attachment.AttachmentFlow;
import org.pgledger.application.attachment.ConditionalOnAttachments;
import org.pgledger.application.attachment.UploadCommands;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The five attachment tools (the common design of the three products, section 3). Each method turns the flat arguments of the tool
 * into the input of {@link AttachmentFlow}, which is the entry point of the operation (the file work is done there, between its
 * database steps), and the result into its output record; the gate, the validation and the transactions belong to the flow and its
 * service. The host attestation of the call is the actor: it is signed for the tool name below (with a hyphen) and for the exact
 * arguments, with an empty request id, because these tools carry none (an upload is made repeatable by {@code operationKey} and by
 * the numbers of its chunks, not by a request receipt).
 *
 * <p>The parameter names are the wire keys of the design ({@code operationKey}, {@code uploadId}, {@code _hostAttestation}, ...), which
 * is why they are camel case here while the keys of the write tools are snake case. The descriptions are message keys that the tool
 * catalog resolves from the message bundle. The tools exist when the attachments are configured, like the flow they call.
 */
@Component
@ConditionalOnAttachments
public class AttachmentTools implements ToolAdapter {

    private final AttachmentFlow flow;

    public AttachmentTools(AttachmentFlow flow) {
        this.flow = flow;
    }

    @McpTool(name = "artifact-begin", description = "tool.artifact_begin", generateOutputSchema = true)
    public AttachmentOutputs.Begun begin(
            @McpToolParam(description = "param.artifact_operation_key", required = true) String operationKey,
            @McpToolParam(description = "param.artifact_file_name", required = true) String fileName,
            @McpToolParam(description = "param.artifact_media_type", required = true) String mediaType,
            @McpToolParam(description = "param.artifact_size", required = true) Long size,
            @McpToolParam(description = "param.artifact_sha256", required = true) String sha256,
            @McpToolParam(description = "param.artifact_purpose", required = false) String purpose,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        return AttachmentOutputs.of(flow.begin(_hostAttestation,
                new UploadCommands.Begin(operationKey, fileName, mediaType, size, sha256, purpose)));
    }

    @McpTool(name = "artifact-chunk", description = "tool.artifact_chunk", generateOutputSchema = true)
    public AttachmentOutputs.Received chunk(
            @McpToolParam(description = "param.artifact_upload_id", required = true) String uploadId,
            @McpToolParam(description = "param.artifact_sequence", required = true) Long sequence,
            @McpToolParam(description = "param.artifact_data", required = true) String data,
            @McpToolParam(description = "param.artifact_chunk_sha256", required = true) String chunkSha256,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        return AttachmentOutputs.of(flow.chunk(_hostAttestation,
                new UploadCommands.Chunk(uploadId, sequence, data, chunkSha256)));
    }

    @McpTool(name = "artifact-finish", description = "tool.artifact_finish", generateOutputSchema = true)
    public AttachmentOutputs.Finished finish(
            @McpToolParam(description = "param.artifact_upload_id", required = true) String uploadId,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        return AttachmentOutputs.of(flow.finish(_hostAttestation, uploadId));
    }

    @McpTool(name = "artifact-status", description = "tool.artifact_status", generateOutputSchema = true)
    public AttachmentOutputs.Status status(
            @McpToolParam(description = "param.artifact_status_upload_id", required = false) String uploadId,
            @McpToolParam(description = "param.artifact_status_attachment_id", required = false) String attachmentId,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        if ((uploadId == null) == (attachmentId == null)) {
            throw ValidationException.invalid("uploadId", ValidationReason.MUST_BE_EXACTLY_ONE_OF, "uploadId",
                    "attachmentId");
        }
        return AttachmentOutputs.of(flow.status(_hostAttestation, uploadId == null ? attachmentId : uploadId));
    }

    @McpTool(name = "artifact-read", description = "tool.artifact_read", generateOutputSchema = true)
    public AttachmentOutputs.Page read(
            @McpToolParam(description = "param.artifact_attachment_id", required = true) String attachmentId,
            @McpToolParam(description = "param.artifact_offset_bytes", required = true) Long offsetBytes,
            @McpToolParam(description = "param.artifact_max_bytes", required = true) Long maxBytes,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        return AttachmentOutputs.of(flow.read(_hostAttestation, attachmentId, offsetBytes, maxBytes));
    }
}
