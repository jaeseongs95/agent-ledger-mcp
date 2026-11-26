// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.agentledger.hook.keys.SessionKey;
import org.agentledger.hook.keys.SessionKeyException;
import org.agentledger.hook.keys.SessionKeyStore;

/**
 * R9-D0096-U: the resident program answers several calls at once in one process, and the first calls of a new author for a
 * server arrive together (a host runs tool calls in parallel). The store creates the key file with CREATE_NEW and writes it;
 * a call that read the file between those two steps used to find an empty file and the call was denied
 * ({@code SESSION_KEY_UNAVAILABLE}): the ceiling that {@code KeyFileWriter} names, which was tolerable when every call was a
 * process of its own and is not in a program that serves them all. Every one of the calls must get the key.
 */
@ExtendWith(HookExtension.class)
class SessionKeyRaceTest {

    private static final int THREADS = 12;
    private static final int ROUNDS = 150;

    @Test
    @DisplayName("R9-D0096-U first calls of one author for one server at the same moment in one process all get the same key, in 150 rounds of 12 calls (none is refused because it read the file of a call that was still writing it)")
    void concurrentFirstCallsAllGetTheKey(TestWorkspace workspace) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int round = 0; round < ROUNDS; round++) {
                Path folder = workspace.root().resolve("race-" + round);
                SessionKeyStore store = TestStores.open(folder);
                CyclicBarrier ready = new CyclicBarrier(THREADS);
                List<Future<SessionKey>> loads = new ArrayList<>();
                for (int i = 0; i < THREADS; i++) {
                    loads.add(pool.submit(() -> {
                        ready.await();
                        return store.load("ledger", "claude:race");
                    }));
                }
                List<String> keys = new ArrayList<>();
                for (Future<SessionKey> load : loads) {
                    keys.add(publicKeyOf(round, load));
                }
                assertThat(keys).as("round " + round).allSatisfy(key -> assertThat(key).isEqualTo(keys.getFirst()));
            }
        }
    }

    private static String publicKeyOf(int round, Future<SessionKey> load) throws InterruptedException, IOException {
        try {
            return load.get().info().publicKey();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof SessionKeyException refused) {
                throw new AssertionError("round " + round + ": a first call was refused: " + refused.reason(), refused);
            }
            throw new IOException(failure.getCause());
        }
    }
}
