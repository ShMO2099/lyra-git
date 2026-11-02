package com.jfx.git.ui;

import com.jfx.git.core.AutoFetchScheduler;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

import org.testfx.util.WaitForAsyncUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Waiting in robot tests for something to have happened, rather than for time to pass.
 *
 * <h2>Why this exists</h2>
 * Git work runs on a {@code GitTaskRunner} lane, or in a native {@code git} subprocess,
 * and a dialog opened from a menu appears one round trip after the click. Robot tests
 * waited for all of that with a fixed 200-300ms sleep. On an idle machine that was enough;
 * under the load of the full suite it was not, and three suites failed intermittently while
 * passing every time they were rerun alone. A fixed delay is a guess about how long the
 * machine will take. This polls for the outcome instead, so it is exactly as long as it
 * needs to be and no longer.
 *
 * <h2>Which thread a condition runs on</h2>
 * Two methods, because the two kinds of condition have opposite constraints:
 * <ul>
 *   <li>{@link #until} runs on the calling test thread. Its conditions read disk or git
 *       state, and git must not run on the FX thread - {@code ThreadGuard} refuses a
 *       subprocess there, and {@code getWorktrees()} is exactly that.</li>
 *   <li>{@link #untilOnFx} and {@link #untilDialogWithButton} run on the FX thread. Their
 *       conditions read the scene graph, and reading it from the test thread while windows
 *       open and close is what made the sparse-checkout suite's own poll throw
 *       {@code IndexOutOfBoundsException} out of TestFX's window walk.</li>
 * </ul>
 * In both, a condition that throws counts as "not yet": a node that is not there yet, or a
 * repository mid-write, is the normal state while waiting, not a failure.
 */
public final class RobotWait {

    private RobotWait() {
    }

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final long POLL_MILLIS = 50;

    /** Polls {@code condition} on this thread until it is true; fails naming {@code what}. */
    public static void until(String what, BooleanSupplier condition) {
        until(what, condition, DEFAULT_TIMEOUT);
    }

    static void until(String what, BooleanSupplier condition, Duration timeout) {
        poll(what, condition::getAsBoolean, timeout);
    }

    /** Polls {@code condition} on the FX thread until it is true; for scene-graph questions. */
    public static void untilOnFx(String what, BooleanSupplier condition) {
        untilOnFx(what, condition, DEFAULT_TIMEOUT);
    }

    static void untilOnFx(String what, BooleanSupplier condition, Duration timeout) {
        poll(what, () -> onFx(condition::getAsBoolean), timeout);
    }

    /**
     * Waits for a showing dialog with a button labelled one of {@code buttons}, and returns
     * it, so the caller's next lookups can be scoped to that dialog instead of searching
     * every window on screen.
     */
    public static DialogPane untilDialogWithButton(String... buttons) {
        DialogPane[] found = new DialogPane[1];
        untilOnFx("no dialog with a button labelled " + String.join(" or ", buttons) + " appeared",
                () -> {
                    found[0] = showingDialogWith(buttons);
                    return found[0] != null;
                });
        return found[0];
    }

    /**
     * Waits for everything already queued on the repository lanes to finish - every open
     * repository's, including one whose work starts while this waits.
     *
     * <p>For {@code @AfterEach}, before the backend is closed. A click that starts git work
     * returns long before the work does, so a test can reach its teardown with a command
     * still running against the temporary repository - and closing the backend under it,
     * or letting {@code @TempDir} delete the directory under it, is how a passing test
     * failed in cleanup.
     *
     * <p>Also before a negative assertion ("nothing changed"), which cannot be polled for.
     *
     * <p>Drains in rounds, not once: a finished operation's callback runs on the FX thread
     * and commonly queues a refresh, which lands on the lane <em>behind</em> the first drain
     * marker. Flushing FX events between rounds lets that follow-up queue, and the next round
     * waits for it. Three rounds cover operation, refresh, and the graph load a refresh
     * starts.
     */
    public static void drainGitWork() {
        if (Platform.isFxApplicationThread()) {
            throw new IllegalStateException("RobotWait must not be called on the FX thread");
        }
        for (int round = 0; round < DRAIN_ROUNDS; round++) {
            if (!AutoFetchScheduler.drainPendingWork(30_000)) {
                throw new AssertionError("git work was still running after 30s");
            }
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    private static final int DRAIN_ROUNDS = 3;

    // ------------------------------------------------------------------

    private static void poll(String what, Callable<Boolean> condition, Duration timeout) {
        if (Platform.isFxApplicationThread()) {
            // Polling from the FX thread would block the very thread the outcome depends on.
            throw new IllegalStateException("RobotWait must not be called on the FX thread");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        Throwable lastFailure = null;
        while (true) {
            try {
                if (Boolean.TRUE.equals(condition.call())) {
                    return;
                }
                lastFailure = null;
            } catch (Throwable notYet) {
                lastFailure = notYet;
            }
            if (System.nanoTime() >= deadline) {
                AssertionError timedOut = new AssertionError(
                        what + " (waited " + timeout.toMillis() + "ms)");
                if (lastFailure != null) {
                    timedOut.initCause(lastFailure);
                }
                throw timedOut;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(what + " (interrupted while waiting)", e);
            }
        }
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        try {
            return WaitForAsyncUtils.asyncFx(work).get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            // The condition's own exception, not the executor's wrapper around it.
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    /** The first showing dialog offering one of {@code buttons}. Call on the FX thread. */
    private static DialogPane showingDialogWith(String... buttons) {
        List<Window> windows = new ArrayList<>(Window.getWindows());
        for (Window window : windows) {
            if (!window.isShowing() || window.getScene() == null
                    || !(window.getScene().getRoot() instanceof DialogPane pane)) {
                continue;
            }
            for (ButtonType type : pane.getButtonTypes()) {
                for (String wanted : buttons) {
                    Node button = pane.lookupButton(type);
                    if (wanted.equals(type.getText()) && button != null) {
                        return pane;
                    }
                }
            }
        }
        return null;
    }
}
