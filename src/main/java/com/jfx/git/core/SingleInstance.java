package com.jfx.git.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps a second copy of the application from running against the same settings.
 *
 * <h2>Why</h2>
 * Two copies started from the same shortcut each kept their own idea of the open tabs, the
 * session and every preference, and each wrote it back on its own schedule. {@link Settings}
 * already merges a save into what is on disk, so neither erases the other's keys any more,
 * but the tab session and the window it describes are still last-writer-wins, and two
 * windows on the same repository each refresh, fetch and watch it. One copy is the only
 * arrangement where what the user sees is what gets saved.
 *
 * <h2>An operating-system lock, not a marker file</h2>
 * The claim is a {@link FileChannel#tryLock lock} on a file beside the settings file. The
 * operating system drops it when the process ends however it ends - a crash, a kill from
 * Task Manager, a power cut - so nothing is ever left behind to lock the user out. A file
 * that merely exists, or one holding a process id, would survive exactly those endings
 * and need guesswork to tell a stale one from a live one.
 *
 * <p>The lock covers one byte far past the end of the file rather than its contents.
 * Windows locks are mandatory: a lock over the contents would stop the refused copy from
 * reading them, and the contents are how it finds the running one (below).
 *
 * <h2>One per settings file, not one per machine</h2>
 * The lock is named after the settings file, so it follows {@code -Dlyra.settings}: the
 * test suite starts the application dozens of times against a throwaway file while the
 * developer has the real one open, and the two never meet.
 *
 * <h2>Bringing the running copy forward</h2>
 * Once its window is up, the copy holding the lock listens on a loopback port and writes
 * the port and a random token into the lock file. A refused copy reads them and asks for
 * the window to be shown. The token keeps that to processes that can read the user's own
 * settings folder; anything else on the machine can reach a loopback port. All the
 * listener ever does is show a window, and a request it cannot read is dropped.
 */
public final class SingleInstance implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(SingleInstance.class.getName());

    /** The byte the lock covers: far past anything the file will hold. See the class comment. */
    private static final long LOCK_POSITION = Long.MAX_VALUE - 1;

    private static final String SHOW = "show ";

    /** How long either side waits on the other before giving up on a request. */
    private static final int TIMEOUT_MILLIS = 2_000;

    /** Longest line a request may send; anything longer is not one of ours. */
    private static final int MAX_REQUEST = 128;

    private final FileChannel channel;
    private final FileLock lock;
    private ServerSocket server;

    private SingleInstance(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** The lock file that guards {@code settingsFile}: beside it, named after it. */
    public static Path lockFileFor(Path settingsFile) {
        return settingsFile.resolveSibling(settingsFile.getFileName() + ".lock");
    }

    /**
     * Claims {@code lockFile} for this process.
     *
     * @return the claim, held for as long as the process runs (or until it is closed); empty
     *         when another copy - in this process or another - holds it already
     * @throws IOException when the file cannot be created or locked at all, as in a
     *                     read-only folder or on a file system without locks. The caller
     *                     starts anyway: refusing to run over a missing safeguard would be
     *                     worse than the second copy it guards against.
     */
    public static Optional<SingleInstance> claim(Path lockFile) throws IOException {
        Path parent = lockFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock(LOCK_POSITION, 1, false);
            if (lock == null) {
                channel.close();
                return Optional.empty();
            }
            return Optional.of(new SingleInstance(channel, lock));
        } catch (OverlappingFileLockException heldInThisProcess) {
            // The JVM keeps its own table of locks and answers for them itself.
            channel.close();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    /**
     * Starts answering requests to show the window; {@code onShow} runs on a background
     * thread for each genuine one. Called once the window exists, so a copy that is still
     * starting up is not asked for a window it does not have yet.
     */
    public synchronized void answerShowRequests(Runnable onShow) {
        if (server != null || !channel.isOpen()) {
            return;
        }
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        String token = HexFormat.of().formatHex(random);
        try {
            server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
            byte[] contents = (server.getLocalPort() + " " + token + "\n").getBytes(StandardCharsets.US_ASCII);
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(contents), 0);
        } catch (IOException e) {
            // Without it a second copy can only say this one is running, not bring it forward.
            LOGGER.log(Level.INFO, "Cannot listen for a second copy of the application", e);
            closeServer();
            return;
        }
        ServerSocket listening = server;
        byte[] expected = (SHOW + token).getBytes(StandardCharsets.US_ASCII);
        Thread thread = new Thread(() -> answer(listening, expected, onShow), "single-instance");
        thread.setDaemon(true);
        thread.start();
    }

    private static void answer(ServerSocket listening, byte[] expected, Runnable onShow) {
        while (!listening.isClosed()) {
            try (Socket caller = listening.accept()) {
                caller.setSoTimeout(TIMEOUT_MILLIS);
                byte[] request = readLine(caller.getInputStream());
                if (request != null && MessageDigest.isEqual(request, expected)) {
                    onShow.run();
                }
            } catch (IOException e) {
                // A caller that hung up, timed out or sent nothing: next one. A closed server
                // ends the loop through the condition.
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Showing the window for a second copy failed", e);
            }
        }
    }

    /** One line, without its ending; null when it is too long or never ends. */
    private static byte[] readLine(InputStream in) throws IOException {
        byte[] line = new byte[MAX_REQUEST];
        int length = 0;
        for (int b; (b = in.read()) != -1; ) {
            if (b == '\n') {
                return java.util.Arrays.copyOf(line, length);
            }
            if (length == line.length) {
                return null;
            }
            line[length++] = (byte) b;
        }
        return null;
    }

    /**
     * Asks the copy holding {@code lockFile} to show its window.
     *
     * @return true when the request was delivered; false when nothing is listening there,
     *         which the caller treats as "running, but could not be brought forward"
     */
    public static boolean requestShow(Path lockFile) {
        String[] parts;
        try {
            parts = Files.readString(lockFile, StandardCharsets.US_ASCII).trim().split(" ");
        } catch (IOException | RuntimeException e) {
            return false;
        }
        if (parts.length != 2) {
            return false;
        }
        try (Socket socket = new Socket()) {
            int port = Integer.parseInt(parts[0]);
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write((SHOW + parts[1] + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Lets go of the claim and stops answering. The process ending does the same; this is
     * for tests, which claim and release within one JVM.
     */
    @Override
    public synchronized void close() {
        closeServer();
        try {
            if (channel.isOpen()) {
                // A copy that starts later should not find a port nobody answers on.
                channel.truncate(0);
            }
            lock.release();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Releasing the single-instance lock", e);
        }
        try {
            channel.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Closing the single-instance lock file", e);
        }
    }

    private void closeServer() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Closing is all that was wanted.
            }
            server = null;
        }
    }
}
