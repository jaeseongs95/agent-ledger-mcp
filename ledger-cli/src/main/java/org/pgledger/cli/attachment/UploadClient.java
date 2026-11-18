// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.cli.attachment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import org.pgledger.application.attachment.AttachmentProperties;
import org.pgledger.cli.ErrorCode;
import org.pgledger.cli.FailureException;
import org.pgledger.cli.UsageException;
import org.pgledger.cli.mcp.McpCheckCommand;
import org.pgledger.domain.value.Sha256;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resumable file uploader for {@code ledger-cli attachments-send}. Communicates with the running MCP server over
 * Streamable HTTP ({@link McpCheckCommand}) calling {@code artifact_status} and {@code artifact_chunk} by
 * {@code uploadId} capability (without {@code _hostAttestation}) so large file bytes bypass the LLM context.
 */
@Component
public class UploadClient {

    private static final Logger LOG = LoggerFactory.getLogger(UploadClient.class);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    /** Outcome of a send attempt: either complete or stopped with server resume coordinates. */
    public sealed interface Outcome {
    }

    /** Every byte of {@code file} is now staged on the server under {@code uploadId}. */
    public record Sent(String uploadId, String attachmentId, int seq, long received) implements Outcome {
    }

    /** Sending paused mid-stream; re-running {@code attachments-send} resumes cleanly from {@code received}. */
    public record Stopped(String message, long received, long nextSequence) implements Outcome {
    }

    private record StatusRequest(String uploadId) {
    }

    private record ChunkRequest(String uploadId, long sequence, String data, String chunkSha256) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ToolErrorPayload(String code, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UploadStatusPayload(String kind, String attachmentId, Integer seq, String state, Long size,
            String sha256, Long received, Long sequence) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChunkAckPayload(Long sequence, Long received) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StatusEnvelope(String schemaVersion, Boolean ok, UploadStatusPayload data, ToolErrorPayload error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChunkEnvelope(String schemaVersion, Boolean ok, ChunkAckPayload data, ToolErrorPayload error) {
    }

    private final McpCheckCommand mcp;
    private final JsonMapper json;

    public UploadClient(McpCheckCommand mcp, JsonMapper json) {
        this.mcp = mcp;
        this.json = json;
    }

    /** Sends {@code file} to the staged {@code uploadId} at {@code urlOption} (or the default local MCP server). */
    public Outcome send(Path file, String uploadId, String urlOption) {
        PreparedFile.Measure local = PreparedFile.measure(file);
        URI uri = mcp.resolveEndpoint(urlOption);
        String token = mcp.requireToken();
        String shown = mcp.shown(uri);
        try (McpSyncClient client = mcp.openClient(uri, token, CALL_TIMEOUT)) {
            UploadStatusPayload status = readStatus(client, shown, uploadId);
            String attachmentId = status.attachmentId();
            int seq = status.seq() == null ? 0 : status.seq();
            if ("COMPLETED".equals(status.state())) {
                long received = status.received() == null ? 0L : status.received();
                return new Sent(uploadId, attachmentId, seq, received);
            }
            if (status.size() == null || status.size() != local.size()
                    || !Objects.equals(status.sha256(), local.sha256())) {
                throw new UsageException(ErrorCode.ATTACHMENTS_FILE_INVALID, String.valueOf(file),
                        "does not match declared size/sha256 on server; prepare and begin a new upload");
            }
            long offset = status.received() == null ? 0L : status.received();
            long sequence = status.sequence() == null ? 0L : status.sequence();
            return sendAndVerify(client, uri, token, shown, file, uploadId, attachmentId, seq, local, offset, sequence);
        }
    }

    private Outcome sendAndVerify(McpSyncClient client, URI uri, String token, String shown, Path file,
            String uploadId, String attachmentId, int seq, PreparedFile.Measure local, long offset, long sequence) {
        try {
            sendChunks(client, shown, file, uploadId, local.size(), offset, sequence);
            if (!Objects.equals(local, PreparedFile.measure(file))) {
                throw new UsageException(ErrorCode.ATTACHMENTS_FILE_INVALID, String.valueOf(file),
                        "changed while being sent");
            }
            return new Sent(uploadId, attachmentId, seq, local.size());
        } catch (FailureException interrupted) {
            if (interrupted.code() == ErrorCode.ATTACHMENTS_TOOL_REFUSED
                    || interrupted.code() == ErrorCode.MCP_AUTHORIZATION_REFUSED) {
                throw interrupted;
            }
            LOG.debug("Chunk upload interrupted for {}; probing server status.", uploadId, interrupted);
            return stopped(uri, token, shown, uploadId, local.size(), interrupted.code().name());
        }
    }

    private void sendChunks(McpSyncClient client, String shown, Path file, String uploadId, long size,
            long startOffset, long firstSequence) {
        long offset = startOffset;
        long sequence = firstSequence;
        boolean more = offset < size || (size == 0L && sequence == 0L);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (more) {
                int length = (int) Math.min(AttachmentProperties.CHUNK_BYTES, size - offset);
                byte[] data = readAt(file, channel, offset, length);
                ChunkRequest request = new ChunkRequest(uploadId, sequence,
                        Base64.getEncoder().encodeToString(data), Sha256.ofBytes(data).hex());
                sendChunk(client, shown, request);
                offset += data.length;
                sequence++;
                more = offset < size;
            }
        } catch (IOException cause) {
            LOG.debug("The file {} could not be read while sending chunks.", file, cause);
            throw new UsageException(ErrorCode.ATTACHMENTS_FILE_INVALID, String.valueOf(file),
                    cause.getClass().getSimpleName());
        }
    }

    private static byte[] readAt(Path file, FileChannel channel, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset + buffer.position());
            if (read < 0) {
                throw new UsageException(ErrorCode.ATTACHMENTS_FILE_INVALID, String.valueOf(file),
                        "shorter than declared during upload");
            }
        }
        return buffer.array();
    }

    private Outcome stopped(URI uri, String token, String shown, String uploadId, long size, String causeCode) {
        try (McpSyncClient probe = mcp.openClient(uri, token, CALL_TIMEOUT)) {
            UploadStatusPayload status = readStatus(probe, shown, uploadId);
            long received = status.received() == null ? 0L : status.received();
            long nextSeq = status.sequence() == null ? 0L : status.sequence();
            int seq = status.seq() == null ? 0 : status.seq();
            if ("COMPLETED".equals(status.state()) || (nextSeq > 0L && received == size)) {
                return new Sent(uploadId, status.attachmentId(), seq, received);
            }
            return new Stopped(causeCode, received, nextSeq);
        } catch (FailureException probeFailed) {
            LOG.debug("Status probe after upload interruption also failed for {}.", uploadId, probeFailed);
            return new Stopped(causeCode, 0L, 0L);
        }
    }

    private UploadStatusPayload readStatus(McpSyncClient client, String shown, String uploadId) {
        String raw = callRaw(client, shown, "artifact_status", new StatusRequest(uploadId));
        try {
            StatusEnvelope envelope = json.readValue(raw, StatusEnvelope.class);
            if (Boolean.FALSE.equals(envelope.ok()) || envelope.error() != null) {
                throw refused(envelope.error(), raw);
            }
            if (envelope.data() != null) {
                return envelope.data();
            }
            return json.readValue(raw, UploadStatusPayload.class);
        } catch (JacksonException malformed) {
            throw new FailureException(ErrorCode.MCP_FAILED, malformed, shown, "JacksonException");
        }
    }

    private void sendChunk(McpSyncClient client, String shown, ChunkRequest request) {
        String raw = callRaw(client, shown, "artifact_chunk", request);
        try {
            ChunkEnvelope envelope = json.readValue(raw, ChunkEnvelope.class);
            if (Boolean.FALSE.equals(envelope.ok()) || envelope.error() != null) {
                throw refused(envelope.error(), raw);
            }
        } catch (JacksonException malformed) {
            throw new FailureException(ErrorCode.MCP_FAILED, malformed, shown, "JacksonException");
        }
    }

    private String callRaw(McpSyncClient client, String shown, String tool, Record arguments) {
        CallToolResult result = mcp.callTool(client, shown,
                () -> client.callTool(CallToolRequest.builder(tool)
                        .arguments(new JacksonMcpJsonMapper(json), json.writeValueAsString(arguments))
                        .build()));
        if (result == null) {
            throw new FailureException(ErrorCode.MCP_FAILED, shown, "EmptyContent");
        }
        String structured = result.structuredContent() == null ? null
                : json.writeValueAsString(result.structuredContent());
        if (Boolean.TRUE.equals(result.isError())) {
            throw decodeError(structured != null ? structured : firstText(result, shown), shown);
        }
        return structured != null ? structured : firstText(result, shown);
    }

    private FailureException decodeError(String raw, String shown) {
        try {
            StatusEnvelope envelope = json.readValue(raw, StatusEnvelope.class);
            if (envelope.error() != null) {
                return refused(envelope.error(), raw);
            }
            ToolErrorPayload direct = json.readValue(raw, ToolErrorPayload.class);
            if (direct.code() != null && !direct.code().isBlank()) {
                return refused(direct, raw);
            }
        } catch (JacksonException notJson) {
            int colon = raw.indexOf(": ");
            if (colon > 0) {
                return new FailureException(ErrorCode.ATTACHMENTS_TOOL_REFUSED,
                        raw.substring(0, colon), raw.substring(colon + 2));
            }
            return new FailureException(ErrorCode.MCP_FAILED, notJson, shown, "JacksonException");
        }
        return new FailureException(ErrorCode.ATTACHMENTS_TOOL_REFUSED, "UNKNOWN", raw);
    }

    private static FailureException refused(ToolErrorPayload error, String fallback) {
        String code = error == null || error.code() == null || error.code().isBlank() ? "UNKNOWN" : error.code();
        String message = error == null || error.message() == null ? fallback : error.message();
        return new FailureException(ErrorCode.ATTACHMENTS_TOOL_REFUSED, code, message);
    }

    private static String firstText(CallToolResult result, String shown) {
        if (result.content() == null || result.content().isEmpty()) {
            throw new FailureException(ErrorCode.MCP_FAILED, shown, "EmptyContent");
        }
        for (Content item : result.content()) {
            if (item instanceof TextContent text) {
                return text.text();
            }
        }
        throw new FailureException(ErrorCode.MCP_FAILED, shown, "NonTextContent");
    }
}
