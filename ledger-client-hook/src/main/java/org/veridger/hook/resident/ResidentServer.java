// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.hook.resident;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.FileSystems;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.veridger.hookclient.AddressFile;
import org.veridger.hookclient.OwnerOnlyFiles;
import org.veridger.hookclient.ResidentAddress;
import org.veridger.hookclient.ResidentProcess;
import org.veridger.hookclient.StateFolder;
import org.veridger.hookclient.Wire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The life of the resident program: the instance lock, the server on the loopback, the address file, and the end.
 *
 * <p>The server listens on {@code 127.0.0.1} at a port that the operating system picks (port 0): nothing fixed to collide with,
 * and the port is known only from the address file. A new random secret for every start is written to that file, owner-only,
 * after the server is ready; a request that does not know it gets 401. The address file is removed when the program ends.
 * The program ends when a client asks it to ({@code /stop}) or when nobody has called it for the idle timeout; a call that is
 * being answered is finished first.
 */
@Component
public final class ResidentServer {

    private static final Logger LOG = LoggerFactory.getLogger(ResidentServer.class);
    private static final byte[] LOOPBACK = {127, 0, 0, 1};
    private static final int FINISH_CALLS_SECONDS = 2;

    private final HookService service;
    private final ResidentSettings settings;
    private final ResidentAccountProperties account;
    private final Clock clock;

    ResidentServer(HookService service, ResidentSettings settings, ResidentAccountProperties account, Clock clock) {
        this.service = service;
        this.settings = settings;
        this.account = account;
        this.clock = clock;
    }

    /**
     * Runs the program for this state folder until it is stopped or idle. Returns at once, with nothing started, if another
     * program already holds the state folder.
     *
     * @param state the state folder the client chose
     * @param build the build of the program file, written to the address file for the clients to compare
     */
    public void run(StateFolder state, String build) throws IOException, InterruptedException {
        LOG.info("the resident hook program starts: pid {}, build {}", ProcessHandle.current().pid(), build);
        OwnerOnlyFiles files = OwnerOnlyFiles.forFileSystem(FileSystems.getDefault(), account.userName());
        files.createDirectories(state.directory());
        try (InstanceLock instance = InstanceLock.take(state.instanceLock(), files)) {
            if (!instance.held()) {
                LOG.info("another resident hook program holds the state folder");
                return;
            }
            serve(state, build, files);
        }
    }

    private void serve(StateFolder state, String build, OwnerOnlyFiles files) throws IOException, InterruptedException {
        IdleWatch idle = new IdleWatch(clock, settings.idleTimeout());
        CountDownLatch stopRequested = new CountDownLatch(1);
        String secret = ResidentAddress.newSecret();
        SecretAuthenticator authenticator = new SecretAuthenticator(secret);
        AddressFile addressFile = new AddressFile(state.addressFile());
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(LOOPBACK), 0), 0);
        HttpContext call = server.createContext("/call", new CallHandler(service, new Wire(), idle));
        call.setAuthenticator(authenticator);
        HttpContext stop = server.createContext("/stop", new StopHandler(stopRequested));
        stop.setAuthenticator(authenticator);
        server.setExecutor(executor);
        server.start();
        ResidentProcess processes = new ResidentProcess();
        long pid = ProcessHandle.current().pid();
        try {
            addressFile.write(
                    new ResidentAddress(server.getAddress().getPort(), pid, processes.startOfCurrent(), build, secret),
                    files);
            while (!stopRequested.await(idle.pollInterval().toMillis(), TimeUnit.MILLISECONDS)) {
                if (idle.expired()) {
                    LOG.debug("the resident hook program ends: nobody called it for {}", settings.idleTimeout());
                    break;
                }
            }
        } finally {
            // the address goes first, so that no client is sent to a server that is closing; the calls in flight are finished
            addressFile.removeIfOwnedBy(pid);
            server.stop(FINISH_CALLS_SECONDS);
            executor.shutdown();
        }
    }
}
