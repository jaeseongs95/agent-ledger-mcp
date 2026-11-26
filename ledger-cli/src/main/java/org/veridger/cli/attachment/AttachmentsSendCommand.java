// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.cli.attachment;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.veridger.cli.CliCommand;
import org.veridger.cli.CliOptions;
import org.veridger.cli.CliOutput;
import org.veridger.cli.Command;
import org.veridger.cli.ErrorCode;
import org.veridger.cli.FailureException;
import org.veridger.cli.UsageException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code attachments-send <file> --upload-id=<id> [--url=<url>]}: uploads chunks of a local regular file directly to
 * the running MCP server using the {@code uploadId} capability token from {@code artifact_begin}. Needs no database.
 */
@Component
public class AttachmentsSendCommand implements Command {

    static final String UPLOAD_ID = "upload-id";
    static final String URL = "url";

    private final UploadClient client;
    private final JsonMapper json;
    private final CliOutput output;

    AttachmentsSendCommand(UploadClient client, JsonMapper json, CliOutput output) {
        this.client = client;
        this.json = json;
        this.output = output;
    }

    @Override
    public CliCommand name() {
        return CliCommand.ATTACHMENTS_SEND;
    }

    @Override
    public Set<String> options() {
        return Set.of(UPLOAD_ID, URL);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.isEmpty()) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        Path file = AttachmentsPrepareCommand.parsePath(operands.getFirst());
        String uploadId = CliOptions.required(arguments, UPLOAD_ID);
        String url = CliOptions.valueOrNull(arguments, URL);
        switch (client.send(file, uploadId, url)) {
            case UploadClient.Sent sent -> output.info(json.writeValueAsString(sent));
            case UploadClient.Stopped stopped -> throw new FailureException(ErrorCode.ATTACHMENTS_UPLOAD_STOPPED,
                    stopped.message(), String.valueOf(stopped.received()), String.valueOf(stopped.nextSequence()));
        }
    }
}
