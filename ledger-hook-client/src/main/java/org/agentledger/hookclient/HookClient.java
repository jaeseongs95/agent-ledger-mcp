// SPDX-License-Identifier: AGPL-3.0-or-later

package org.agentledger.hookclient;

import java.io.IOException;

/**
 * Carries a hook call to the resident program: finds the program that runs for the key folder, starts it if there is none
 * (or replaces it if it is of another build), asks it, and gives its answer back. There is one retry in the whole call and it
 * is not blind: it happens only when the program did not take the call (it was ending, or another program replaced it between
 * the look at the address file and the request), and then the address file is looked at again, a program is started if needed,
 * and the call is made once more. A failure after the program took the call is never asked again.
 */
public final class HookClient {

    /** How long a call waits for a program to be started: below the 10 seconds the hosts allow a hook. */
    static final long START_DEADLINE_MILLIS = 8_000;

    /** How long a call waits for its answer: below the 10 seconds the hosts allow a hook. */
    static final int ANSWER_TIMEOUT_MILLIS = 9_000;

    private final ResidentJar jar;
    private final ResidentFinder finder;
    private final ResidentClient client;
    private final ResidentLauncher launcher;
    private final ResidentProcess processes;

    HookClient(ResidentJar jar, ResidentFinder finder, ResidentClient client, ResidentLauncher launcher,
            ResidentProcess processes) {
        this.jar = jar;
        this.finder = finder;
        this.client = client;
        this.launcher = launcher;
        this.processes = processes;
    }

    /**
     * The client for this environment.
     *
     * @throws IOException              if the resident program file next to the client cannot be read
     * @throws IllegalArgumentException if the home folder of the account is not usable
     */
    public static HookClient of(ClientEnvironment environment) throws IOException {
        StateFolder state = StateFolder.locate(environment.userHome(), environment.keyFolderText());
        ResidentJar jar = ResidentJar.besideClient(environment.clientJar());
        ResidentProcess processes = new ResidentProcess();
        AddressFile addressFile = new AddressFile(state.addressFile());
        ResidentFinder finder = new ResidentFinder(addressFile, processes);
        ResidentClient client = new ResidentClient(new Wire(), ANSWER_TIMEOUT_MILLIS);
        ResidentLauncher launcher = new ResidentLauncher(state, environment, jar, finder, client, processes, addressFile);
        return new HookClient(jar, finder, client, launcher, processes);
    }

    /** The answer of the resident program to this call. */
    public HookAnswer call(HookCall call) throws ResidentException, IOException {
        ResidentAddress address = running();
        try {
            return client.call(address, call);
        } catch (ResidentUnreachableException notTaken) {
            launcher.awaitGone(address);
            if (processes.isLive(address)) {
                // alive, yet it took no call for the whole wait: it is not ending, it is broken (a program that ends leaves
                // within seconds), so it is replaced
                processes.destroy(address);
                launcher.awaitGone(address);
            }
            return client.call(launcher.ensureRunning(START_DEADLINE_MILLIS), call);
        }
    }

    /** Ends the resident program of this key folder if there is one, whatever its build. */
    public void stop() throws ResidentException, IOException {
        ResidentAddress address = switch (finder.find(jar.build())) {
            case ResidentFinder.Lookup.Usable usable -> usable.address();
            case ResidentFinder.Lookup.OtherBuild other -> other.address();
            case ResidentFinder.Lookup.Nothing nothing -> null;
        };
        if (address != null) {
            try {
                client.stop(address);
            } catch (ResidentUnreachableException alreadyEnding) {
                // it does not take requests (it is ending, or hung): the process is ended
                processes.destroy(address);
            }
            launcher.awaitGone(address);
        }
    }

    private ResidentAddress running() throws ResidentException, IOException {
        if (finder.find(jar.build()) instanceof ResidentFinder.Lookup.Usable usable) {
            return usable.address();
        }
        return launcher.ensureRunning(START_DEADLINE_MILLIS);
    }
}
