package com.jfx.git.testsupport;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A throwaway {@code git daemon} on loopback, serving every repository under one directory
 * as {@code git://127.0.0.1:<port>/<name>}, that {@link #close()} stops completely.
 *
 * <h2>Why close() walks the process tree</h2>
 * {@code Process.destroy()} only ends the process that was started, and the process that
 * was started is not the daemon. On Windows, {@code git} on the PATH is
 * {@code Program Files\Git\cmd\git.exe}, a launcher that starts the real
 * {@code ucrt64\bin\git.exe}, which starts {@code git-daemon.exe}. Killing the launcher
 * leaves the daemon listening after the test JVM has gone. Every full run used to leave six
 * behind, and since a daemon inherits the working directory of the Maven that started it,
 * they also locked it: a scratch worktree a suite had run in could not be deleted until
 * they were stopped by hand.
 *
 * <p>So {@link #close()} stops the launcher and everything beneath it - including the
 * per-connection {@code git upload-pack} the daemon forks - and waits for all of them.
 * The tree is read <em>before</em> anything is killed: once the launcher has gone its
 * children can no longer be found by asking it, which is the moment the daemon would have
 * slipped away.
 *
 * <p>Needs git installed; callers check {@code NativeGitRunner.isGitInstalled()} first.
 */
public final class LocalGitDaemon implements AutoCloseable {

    private static final Duration STARTUP_LIMIT = Duration.ofSeconds(10);
    private static final Duration STOP_LIMIT = Duration.ofSeconds(5);

    private final Process launcher;
    private final int port;
    /**
     * Everything seen beneath the launcher so far. Remembered because a process that has
     * already exited by {@link #close()} is no longer reachable from the launcher, but the
     * handle still names it (and a handle never matches a recycled PID: the JDK checks the
     * start time too).
     */
    private final Set<ProcessHandle> seen = new LinkedHashSet<>();

    private LocalGitDaemon(Process launcher, int port) {
        this.launcher = launcher;
        this.port = port;
    }

    /**
     * Starts a daemon exporting everything under {@code exportRoot} (created if missing)
     * and returns once it accepts connections.
     *
     * @throws AssertionError if it exits, or has not started listening within ten seconds;
     *                        whatever was started is stopped first
     */
    public static LocalGitDaemon start(Path exportRoot) throws IOException, InterruptedException {
        Files.createDirectories(exportRoot);
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        Process launcher = new ProcessBuilder("git", "daemon", "--reuseaddr", "--export-all",
                "--listen=127.0.0.1", "--port=" + port, "--base-path=" + exportRoot,
                exportRoot.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        LocalGitDaemon daemon = new LocalGitDaemon(launcher, port);
        try {
            daemon.awaitListening();
        } catch (Throwable failed) {
            try {
                daemon.close();
            } catch (RuntimeException stuck) {
                failed.addSuppressed(stuck);
            }
            throw failed;
        }
        return daemon;
    }

    public int port() {
        return port;
    }

    /** The address a clone of {@code repository} (a directory name under the export root) uses. */
    public String url(String repository) {
        return "git://127.0.0.1:" + port + "/" + repository;
    }

    /**
     * The launcher and every process beneath it right now, plus any seen earlier that have
     * since exited. A test uses it to name what {@link #close()} must leave dead.
     */
    public synchronized List<ProcessHandle> processTree() {
        seen.add(launcher.toHandle());
        launcher.descendants().forEach(seen::add);
        return List.copyOf(seen);
    }

    /** Whether something is accepting connections on the daemon's port. */
    public boolean isListening() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("127.0.0.1", port), 100);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    /**
     * Stops the launcher and everything beneath it, and waits until none is alive and the
     * port has stopped accepting connections. Safe to call again.
     *
     * @throws IllegalStateException if a process survives even a forcible kill, or the port
     *                               still answers once every process is gone, since either
     *                               is a leak the suite would otherwise carry out silently
     */
    @Override
    public void close() {
        List<ProcessHandle> tree = processTree();
        for (ProcessHandle process : tree) process.destroy();
        if (!awaitExit(tree)) {
            for (ProcessHandle process : tree) process.destroyForcibly();
            awaitExit(tree);
        }
        List<ProcessHandle> survivors = tree.stream().filter(ProcessHandle::isAlive).toList();
        if (!survivors.isEmpty()) {
            throw new IllegalStateException("git daemon processes survived close(): "
                    + survivors.stream().map(process -> String.valueOf(process.pid())).toList());
        }
        awaitNotListening();
    }

    /**
     * Closes every daemon even when one will not stop, then reports the ones that would not
     * - so a stuck process in the first does not leave the rest running.
     */
    public static void closeAll(Collection<LocalGitDaemon> daemons) {
        RuntimeException stuck = null;
        for (LocalGitDaemon daemon : new ArrayList<>(daemons)) {
            try {
                daemon.close();
            } catch (RuntimeException failure) {
                if (stuck == null) stuck = failure;
                else stuck.addSuppressed(failure);
            }
        }
        if (stuck != null) throw stuck;
    }

    private void awaitListening() throws InterruptedException {
        long deadline = System.nanoTime() + STARTUP_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!launcher.isAlive()) throw new AssertionError("temporary git daemon exited before listening");
            if (isListening()) {
                // Listening means the daemon itself exists, so this is the moment the whole
                // chain from the launcher down to git-daemon.exe can be recorded.
                processTree();
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("temporary git daemon did not listen on loopback");
    }

    /**
     * A process counts as exited before Windows has finished tearing it down, and a socket
     * it held (the listening one, and the copy each per-connection helper inherits) lives
     * until then: for a few hundred milliseconds after the last process is gone the port
     * can still accept a connection. Waiting here is what makes "closed" mean the same to
     * the next caller as it does to the operating system.
     */
    private void awaitNotListening() {
        long deadline = System.nanoTime() + STOP_LIMIT.toNanos();
        while (isListening()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("port " + port
                        + " still accepts connections with every git daemon process gone");
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean awaitExit(List<ProcessHandle> processes) {
        CompletableFuture<?>[] exits = processes.stream()
                .map(ProcessHandle::onExit).toArray(CompletableFuture<?>[]::new);
        try {
            CompletableFuture.allOf(exits).get(STOP_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException | ExecutionException notYet) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
