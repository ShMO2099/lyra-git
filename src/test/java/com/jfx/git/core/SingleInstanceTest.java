package com.jfx.git.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only one copy of the application runs against one settings file.
 *
 * <p>The lock is the operating system's, so it goes when the process does: the test that
 * kills a holder outright is the one that matters, because a lock file that survived a
 * crash would keep the application from ever starting again.
 */
class SingleInstanceTest {

    @TempDir
    Path temp;

    private final List<AutoCloseable> held = new ArrayList<>();
    private final List<Process> children = new ArrayList<>();

    @AfterEach
    void release() throws Exception {
        for (AutoCloseable claim : held) {
            claim.close();
        }
        for (Process child : children) {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
        }
    }

    private Optional<SingleInstance> claim(Path lockFile) throws Exception {
        Optional<SingleInstance> claim = SingleInstance.claim(lockFile);
        claim.ifPresent(held::add);
        return claim;
    }

    @Test
    void theLockSitsBesideTheSettingsFileItGuards() {
        Path settings = temp.resolve("Lyra").resolve("settings.properties");
        assertEquals(temp.resolve("Lyra").resolve("settings.properties.lock"),
                SingleInstance.lockFileFor(settings));
    }

    @Test
    void aSecondClaimOnTheSameSettingsIsRefused() throws Exception {
        Path lock = SingleInstance.lockFileFor(temp.resolve("settings.properties"));
        assertTrue(claim(lock).isPresent(), "the first claim owns the location");
        assertTrue(claim(lock).isEmpty(), "a second claim while the first holds it must be refused");
    }

    @Test
    void separateSettingsFilesDoNotBlockEachOther() throws Exception {
        // The test suite starts the application against a throwaway settings file while the
        // developer has the real one open: the two must not see each other.
        assertTrue(claim(SingleInstance.lockFileFor(temp.resolve("a.properties"))).isPresent());
        assertTrue(claim(SingleInstance.lockFileFor(temp.resolve("b.properties"))).isPresent());
    }

    @Test
    void aClosedClaimCanBeTakenAgain() throws Exception {
        Path lock = SingleInstance.lockFileFor(temp.resolve("settings.properties"));
        SingleInstance first = SingleInstance.claim(lock).orElseThrow();
        first.close();
        assertTrue(claim(lock).isPresent(), "a released location is free again");
    }

    @Test
    void theSettingsFolderIsCreatedWhenItDoesNotExistYet() throws Exception {
        // The very first start, before anything has been saved.
        Path lock = SingleInstance.lockFileFor(temp.resolve("new").resolve("folder").resolve("settings.properties"));
        assertTrue(claim(lock).isPresent());
        assertTrue(Files.isRegularFile(lock));
    }

    @Test
    void anotherProcessHoldingTheLockRefusesThisOne() throws Exception {
        Path lock = SingleInstance.lockFileFor(temp.resolve("settings.properties"));
        Process holder = startHolder(lock);
        assertEquals("claimed", firstLine(holder), "the child process should own the location");

        assertTrue(claim(lock).isEmpty(), "a lock held by another process must refuse this one");
    }

    @Test
    void aKilledHolderLeavesNoStaleLock() throws Exception {
        Path lock = SingleInstance.lockFileFor(temp.resolve("settings.properties"));
        Process holder = startHolder(lock);
        assertEquals("claimed", firstLine(holder));
        assertTrue(claim(lock).isEmpty(), "precondition: the child holds the location");

        // No shutdown hook, no close: the way a crash or Task Manager ends it.
        holder.destroyForcibly();
        assertTrue(holder.waitFor(30, TimeUnit.SECONDS), "the child should have died");

        assertTrue(claim(lock).isPresent(),
                "the lock must go with the process that held it, or a crash locks the user out for good");
        assertTrue(Files.exists(lock), "the file itself stays; only the lock on it went");
    }

    @Test
    void aRefusedInstanceCanAskTheRunningOneToShowItself() throws Exception {
        Path lock = SingleInstance.lockFileFor(temp.resolve("settings.properties"));
        SingleInstance running = claim(lock).orElseThrow();
        CountDownLatch shown = new CountDownLatch(1);
        running.answerShowRequests(shown::countDown);

        assertTrue(SingleInstance.requestShow(lock), "the request should reach the running instance");
        assertTrue(shown.await(10, TimeUnit.SECONDS), "the running instance should have been asked to show");
    }

    @Test
    void aShowRequestWithoutTheTokenIsIgnored() throws Exception {
        Path lock = SingleInstance.lockFileFor(temp.resolve("settings.properties"));
        SingleInstance running = claim(lock).orElseThrow();
        AtomicInteger shows = new AtomicInteger();
        CountDownLatch genuine = new CountDownLatch(1);
        running.answerShowRequests(() -> {
            shows.incrementAndGet();
            genuine.countDown();
        });

        // Anything on the machine can reach a loopback port; only a process that could read
        // the user's own lock file knows the token.
        int port = Integer.parseInt(Files.readString(lock).trim().split(" ")[0]);
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            OutputStream out = socket.getOutputStream();
            out.write("show not-the-token\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        // Requests are answered one at a time, so once the genuine one has run the forged
        // one before it has been read and turned away.
        assertTrue(SingleInstance.requestShow(lock));
        assertTrue(genuine.await(10, TimeUnit.SECONDS));
        assertEquals(1, shows.get(), "only the request carrying the token may show the window");
    }

    @Test
    void askingWhenNothingListensSaysSo() throws Exception {
        Path lock = SingleInstance.lockFileFor(temp.resolve("settings.properties"));
        claim(lock).orElseThrow();
        // Held, but never told to answer: an older build, or one still starting up.
        assertFalse(SingleInstance.requestShow(lock));
        assertFalse(SingleInstance.requestShow(temp.resolve("missing.lock")));
    }

    /** A JVM that claims {@code lock}, says so, and then holds it until it is killed. */
    private Process startHolder(Path lock) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                SingleInstanceHolder.class.getName(), lock.toString())
                .redirectErrorStream(true)
                .start();
        children.add(child);
        return child;
    }

    private static String firstLine(Process child) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
        return CompletableFuture.supplyAsync(() -> {
            try {
                for (String line; (line = reader.readLine()) != null; ) {
                    // A JVM can print warnings of its own first.
                    if (line.equals("claimed") || line.equals("refused")) return line;
                }
                return "exited";
            } catch (Exception e) {
                return e.toString();
            }
        }).get(60, TimeUnit.SECONDS);
    }
}
