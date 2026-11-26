// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;

/**
 * Per-test fixture: runs the hook in process with the shared stateless codec and dialects.
 */
final class HookFixture {

    /** The host session id used by the tests; it exercises the whole allowed character set. */
    static final String HOST_ID = "parent-1234_ab.cd";

    private final JsonCodec codec = HookContext.bean(JsonCodec.class);
    private final HostDialects dialects = HookContext.bean(HostDialects.class);
    private final Path keyDirectory;

    HookFixture(Path keyDirectory) {
        this.keyDirectory = keyDirectory;
    }

    Path keyDirectory() {
        return keyDirectory;
    }

    Run run(String[] args, byte[] stdin) {
        return run(args, new ByteArrayInputStream(stdin));
    }

    Run run(String[] args, InputStream stdin) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = new HookCli(codec, new LedgerHook(dialects, codec)).run(args, stdin, out, err);
        return new Run(exit, out.toByteArray(), err.toByteArray());
    }

    Run run(HostScenario host, byte[] stdin) {
        return run(new String[] {host.client().argument()}, stdin);
    }

    /** Runs the hook and requires the clean channels of HK-016: exit 0, nothing on stderr. */
    Run runClean(HostScenario host, byte[] stdin) {
        Run run = run(host, stdin);
        assertThat(run.err()).as("stderr").isEmpty();
        assertThat(run.exit()).as("exit code").isZero();
        return run;
    }

    Attestations.Stamp stamp(HostScenario host, String op, JsonNode sent, Run run) {
        return stamp(host, HOST_ID, op, sent, run);
    }

    Attestations.Stamp stamp(HostScenario host, String hostId, String op, JsonNode sent, Run run) {
        return stampCarrier(host.author(hostId), op, sent, host.stampedPart(run));
    }

    Attestations.Stamp stampCarrier(String author, String op, JsonNode sent, JsonNode carrier) {
        return Attestations.verify(author, op, sent, carrier);
    }
}
