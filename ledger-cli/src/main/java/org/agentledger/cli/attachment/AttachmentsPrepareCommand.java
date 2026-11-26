// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.cli.attachment;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.agentledger.cli.CliCommand;
import org.agentledger.cli.CliOptions;
import org.agentledger.cli.CliOutput;
import org.agentledger.cli.Command;
import org.agentledger.cli.ErrorCode;
import org.agentledger.cli.UsageException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code attachments-prepare <file> [--name=<name>]}: measures a local regular file in one streaming pass and prints
 * JSON {@code {fileName, size, sha256, mediaType}} for {@code artifact_begin}. Connects to neither database nor server.
 */
@Component
public class AttachmentsPrepareCommand implements Command {

    static final String NAME = "name";

    private final JsonMapper json;
    private final CliOutput output;

    AttachmentsPrepareCommand(JsonMapper json, CliOutput output) {
        this.json = json;
        this.output = output;
    }

    @Override
    public CliCommand name() {
        return CliCommand.ATTACHMENTS_PREPARE;
    }

    @Override
    public Set<String> options() {
        return Set.of(NAME);
    }

    @Override
    public void run(List<String> operands, ApplicationArguments arguments) {
        if (operands.isEmpty()) {
            throw new UsageException(ErrorCode.MISSING_ARGUMENT);
        }
        if (operands.size() > 1) {
            throw new UsageException(ErrorCode.TOO_MANY_ARGUMENTS);
        }
        Path file = parsePath(operands.getFirst());
        String overrideName = CliOptions.valueOrNull(arguments, NAME);
        PreparedFile prepared = PreparedFile.of(file, overrideName);
        output.info(json.writeValueAsString(prepared));
    }

    static Path parsePath(String raw) {
        try {
            return Path.of(raw);
        } catch (InvalidPathException invalid) {
            throw new UsageException(ErrorCode.ATTACHMENTS_FILE_INVALID, raw, invalid.getReason());
        }
    }
}
