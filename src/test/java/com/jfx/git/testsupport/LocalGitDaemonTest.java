package com.jfx.git.testsupport;

import com.jfx.git.core.NativeGitRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A stopped {@link LocalGitDaemon} leaves nothing running.
 *
 * <p>The suite used to end with six {@code git-daemon.exe} processes still listening,
 * because only the {@code git} launcher in front of each was ever killed. These tests ask
 * the operating system, not the {@code Process} the test started: it was that process
 * reporting dead while its child lived on that hid the leak.
 */
class LocalGitDaemonTest {

    @TempDir
    Path root;

    @BeforeEach
    void requireNativeGit() {
        assumeTrue(NativeGitRunner.isGitInstalled(), "a git daemon needs native Git");
    }

    @Test
    void closeStopsTheLauncherAndEveryProcessBeneathIt() throws Exception {
        LocalGitDaemon daemon = LocalGitDaemon.start(root.resolve("exports"));
        List<ProcessHandle> tree;
        try {
            tree = daemon.processTree();
            assertTrue(tree.stream().anyMatch(LocalGitDaemonTest::isGitDaemon),
                    "the daemon itself is among the processes close() must stop: " + describe(tree));
            assertTrue(daemon.isListening(), "a running daemon takes connections");
        } finally {
            daemon.close();
        }

        assertEquals(List.of(), alive(tree), "processes still running after close()");
        assertFalse(daemon.isListening(), "a stopped daemon takes no connections");
    }

    @Test
    void closingTwiceIsHarmless() throws Exception {
        LocalGitDaemon daemon = LocalGitDaemon.start(root.resolve("exports"));
        List<ProcessHandle> tree = daemon.processTree();

        daemon.close();
        daemon.close();

        assertEquals(List.of(), alive(tree), "processes still running after close()");
    }

    @Test
    void closeAllStopsEveryDaemon() throws Exception {
        List<LocalGitDaemon> daemons = new ArrayList<>();
        List<ProcessHandle> trees = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                LocalGitDaemon daemon = LocalGitDaemon.start(root.resolve("exports-" + i));
                daemons.add(daemon);
                trees.addAll(daemon.processTree());
            }
        } finally {
            LocalGitDaemon.closeAll(daemons);
        }

        assertEquals(List.of(), alive(trees), "processes still running after closeAll()");
    }

    private static List<Long> alive(List<ProcessHandle> processes) {
        return processes.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
    }

    private static boolean isGitDaemon(ProcessHandle process) {
        return process.info().command().map(command -> command.contains("git-daemon")).orElse(false);
    }

    private static String describe(List<ProcessHandle> processes) {
        return processes.stream()
                .map(process -> process.pid() + "=" + process.info().command().orElse("?"))
                .toList().toString();
    }
}
