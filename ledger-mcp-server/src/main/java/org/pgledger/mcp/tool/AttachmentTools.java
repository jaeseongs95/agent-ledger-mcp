// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.mcp.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import org.pgledger.application.attachment.AttachmentFlow;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.application.attachment.ConditionalOnAttachments;
import org.pgledger.application.attachment.UploadCommands;
import org.pgledger.domain.error.ValidationException;
import org.pgledger.domain.error.ValidationReason;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The five bundle attachment MCP tools: {@code artifact_begin}, {@code artifact_chunk}, {@code artifact_finish},
 * {@code artifact_status}, and {@code artifact_read}.
 */
@Component
@ConditionalOnAttachments
public class AttachmentTools implements ToolAdapter {

    /** One declared file entry in an {@code artifact_begin} request. */
    public record BeginFileInput(
            @JsonProperty("fileName") @JsonPropertyDescription("param.artifact_file_name") String fileName,
            @JsonProperty("mediaType") @JsonPropertyDescription("param.artifact_media_type") String mediaType,
            @JsonProperty("size") @JsonPropertyDescription("param.artifact_size") long size,
            @JsonProperty("sha256") @JsonPropertyDescription("param.artifact_sha256") String sha256) {
    }

    private final AttachmentFlow flow;

    public AttachmentTools(AttachmentFlow flow) {
        this.flow = flow;
    }

    @McpTool(name = "artifact_begin", description = "tool.artifact_begin", generateOutputSchema = true)
    public AttachmentOutputs.Begun begin(
            @McpToolParam(description = "param.artifact_operation_key", required = true) String operationKey,
            @McpToolParam(description = "param.artifact_files", required = true) List<BeginFileInput> files,
            @McpToolParam(description = "param.artifact_purpose", required = false) String purpose,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        List<UploadCommands.BeginFile> commandFiles = files == null ? null : files.stream()
                .map(f -> f == null ? null : new UploadCommands.BeginFile(f.fileName(), f.mediaType(), f.size(), f.sha256()))
                .toList();
        return AttachmentOutputs.of(flow.begin(_hostAttestation,
                new UploadCommands.Begin(operationKey, commandFiles, purpose)));
    }

    @McpTool(name = "artifact_chunk", description = "tool.artifact_chunk", generateOutputSchema = true)
    public AttachmentOutputs.Received chunk(
            @McpToolParam(description = "param.artifact_upload_id", required = true) String uploadId,
            @McpToolParam(description = "param.artifact_sequence", required = true) Long sequence,
            @McpToolParam(description = "param.artifact_data", required = true) String data,
            @McpToolParam(description = "param.artifact_chunk_sha256", required = true) String chunkSha256,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        if (sequence == null) {
            throw ValidationException.invalid("sequence", ValidationReason.REQUIRED);
        }
        return AttachmentOutputs.of(flow.chunk(_hostAttestation,
                new UploadCommands.Chunk(uploadId, sequence, data, chunkSha256)));
    }

    @McpTool(name = "artifact_finish", description = "tool.artifact_finish", generateOutputSchema = true)
    public AttachmentOutputs.Finished finish(
            @McpToolParam(description = "param.artifact_attachment_id", required = true) String attachmentId,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        return AttachmentOutputs.of(flow.finish(_hostAttestation, attachmentId));
    }

    @McpTool(name = "artifact_status", description = "tool.artifact_status", generateOutputSchema = true)
    public AttachmentOutputs.Status status(
            @McpToolParam(description = "param.artifact_status_upload_id", required = false) String uploadId,
            @McpToolParam(description = "param.artifact_status_attachment_id", required = false) String attachmentId,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        if ((uploadId == null) == (attachmentId == null)) {
            throw ValidationException.invalid("uploadId", ValidationReason.MUST_BE_EXACTLY_ONE_OF, "uploadId",
                    "attachmentId");
        }
        return AttachmentOutputs.of(flow.status(_hostAttestation, uploadId, attachmentId));
    }

    @McpTool(name = "artifact_read", description = "tool.artifact_read", generateOutputSchema = true)
    public AttachmentOutputs.Page read(
            @McpToolParam(description = "param.artifact_attachment_id", required = true) String attachmentId,
            @McpToolParam(description = "param.artifact_seq", required = false) Integer seq,
            @McpToolParam(description = "param.artifact_offset_bytes", required = true) Long offsetBytes,
            @McpToolParam(description = "param.artifact_max_bytes", required = true) Long maxBytes,
            @McpToolParam(description = "param.host_attestation", required = false) String _hostAttestation) {
        int fileSeq = seq == null ? 0 : seq;
        if (offsetBytes == null || offsetBytes < 0) {
            throw ValidationException.invalid("offsetBytes", ValidationReason.MUST_NOT_BE_NEGATIVE);
        }
        if (maxBytes == null || maxBytes < 1 || maxBytes > AttachmentProperties.CHUNK_BYTES) {
            throw ValidationException.invalid("maxBytes", ValidationReason.OUT_OF_RANGE, "1",
                    Integer.toString(AttachmentProperties.CHUNK_BYTES));
        }
        return AttachmentOutputs.of(flow.read(_hostAttestation, attachmentId, fileSeq, offsetBytes,
                maxBytes.intValue()));
    }
}
