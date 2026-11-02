package com.jfx.git.ui.views;

import com.jfx.git.core.DivergedCheckout;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The question a diverged pull-request checkout asks (R10), and what each answer runs.
 *
 * <p>The real checkout is pinned per engine in {@code RemoteConformanceTest
 * .aDivergedPullRequestBranchIsResetOnlyWhenAsked}; this is the views' side: a refusal
 * that says the branch diverged becomes a question, and only "Reset" asks the engine to
 * reset.
 */
class PullRequestCheckoutTest {

    private PullRequestCheckout.Asker realAsker;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> asked = new CopyOnWriteArrayList<>();

    /** What the engine says to a checkout that does not reset: pr-7 has 2 commits of its own. */
    private static final GitResult DIVERGED = DivergedCheckout.refusal("pr-7", "pull request #7", 2);

    @BeforeEach
    void setUp() {
        FxHarness.start();
        realAsker = PullRequestCheckout.asker;
    }

    @AfterEach
    void tearDown() {
        PullRequestCheckout.asker = realAsker;
    }

    /** Records {@code checkoutBranch}; answers everything else harmlessly. */
    private GitBackend backend() {
        return (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    if ("checkoutBranch".equals(method.getName())) {
                        calls.add("checkoutBranch " + args[0]);
                        return GitResult.ok("switched");
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == List.class) return List.of();
                    return null;
                });
    }

    /** @param answer what the user picks; null for Cancel */
    private GitResult run(PullRequestCheckout.Choice answer) throws Exception {
        PullRequestCheckout.asker = (local, what, localOnly) -> {
            asked.add(local + " / " + what + " / " + localOnly);
            return Optional.ofNullable(answer);
        };
        AtomicReference<GitResult> done = new AtomicReference<>();
        GitBackend backend = backend();
        FxHarness.run(() -> PullRequestCheckout.start(OperationRunner.of(backend, new StatusBar(), () -> { }), "pr-7", "pull request #7",
                reset -> {
                    calls.add("checkout reset=" + reset);
                    return reset ? GitResult.ok("Reset pr-7") : DIVERGED;
                },
                done::set));
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> done.get() != null);
        return done.get();
    }

    @Test
    void theRefusalIsRecognisedWithItsCount() {
        assertEquals(2, DivergedCheckout.localCommits(DIVERGED));
        assertEquals(-1, DivergedCheckout.localCommits(GitResult.failed("network down")));
        assertEquals(-1, DivergedCheckout.localCommits(GitResult.ok("fine")));
    }

    @Test
    void resetAsksOnceThenRunsTheCheckoutAgainWithReset() throws Exception {
        GitResult result = run(PullRequestCheckout.Choice.RESET);

        assertTrue(result.isOk(), result::getMessage);
        assertEquals(List.of("pr-7 / pull request #7 / 2"), asked);
        assertEquals(List.of("checkout reset=false", "checkout reset=true"), calls);
    }

    @Test
    void keepSwitchesToTheBranchWithoutResettingIt() throws Exception {
        GitResult result = run(PullRequestCheckout.Choice.KEEP);

        assertTrue(result.isOk(), result::getMessage);
        assertEquals(List.of("checkout reset=false", "checkoutBranch pr-7"), calls);
    }

    @Test
    void cancelDoesNothingMore() throws Exception {
        GitResult result = run(null);

        assertFalse(result.isOk(), "cancelled is not checked out, so the view does not refresh");
        assertEquals(List.of("checkout reset=false"), calls);
    }

    @Test
    void anOrdinaryResultIsNotAQuestion() throws Exception {
        PullRequestCheckout.asker = (local, what, localOnly) -> {
            throw new AssertionError("nothing to ask");
        };
        AtomicReference<GitResult> done = new AtomicReference<>();
        GitBackend backend = backend();
        FxHarness.run(() -> PullRequestCheckout.start(OperationRunner.of(backend, new StatusBar(), () -> { }), "pr-7", "pull request #7",
                reset -> GitResult.failed("could not fetch"), done::set));
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> done.get() != null);

        assertEquals("could not fetch", done.get().getMessage());
    }
}
