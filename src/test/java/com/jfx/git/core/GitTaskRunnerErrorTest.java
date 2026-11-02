package com.jfx.git.core;

import com.jfx.git.ui.FxHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An {@link Error} thrown by git work still reaches the caller (E8).
 *
 * <p>The runner caught only {@code Exception}, so an {@code Error} - an assertion, a
 * {@code NoClassDefFoundError} from an optional dependency - escaped into the executor
 * and neither callback ran. The status bar then said "Committing…" for the rest of the
 * session, and the tab's {@code checkingExternalChanges} guard never came down again.
 */
class GitTaskRunnerErrorTest {

    /** Names this test's lane; the work below never touches it. */
    private final GitBackend repository = new GitService();

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @Test
    void anErrorInRunIsDeliveredAsAFailure() throws Exception {
        CompletableFuture<GitResult> delivered = new CompletableFuture<>();

        GitTaskRunner.run(repository, () -> {
            throw new AssertionError("boom");
        }, delivered::complete);

        GitResult result = delivered.get(10, TimeUnit.SECONDS);
        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("boom"), result.getMessage());
    }

    @Test
    void anErrorInAQueryReachesItsErrorHandler() throws Exception {
        CompletableFuture<String> error = new CompletableFuture<>();

        GitTaskRunner.query(repository, () -> {
            throw new NoClassDefFoundError("some/Missing");
        }, value -> error.completeExceptionally(new AssertionError("onDone must not run")), error::complete);

        assertTrue(error.get(10, TimeUnit.SECONDS).contains("NoClassDefFoundError"));
    }

    @Test
    void theLaneKeepsWorkingAfterAnError() throws Exception {
        GitTaskRunner.run(repository, () -> {
            throw new StackOverflowError();
        }, result -> { });
        CompletableFuture<GitResult> next = new CompletableFuture<>();

        GitTaskRunner.run(repository, () -> GitResult.ok("still here"), next::complete);

        assertTrue(next.get(10, TimeUnit.SECONDS).isOk());
    }
}
