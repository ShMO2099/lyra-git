package com.jfx.git.core;

import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.github.GitHubUser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Restoring remembered integrations at startup.
 *
 * <p>The keep-or-forget decision on a failed check is covered by {@code ApiFailure}'s and
 * the clients' tests; what is pinned here is the shape around it: one provider's surprise
 * does not stop the others, and a connect is announced so open screens can follow it.
 */
class IntegrationSessionsTest {

    @AfterEach
    void disconnect() {
        GitHubSession.disconnect();
    }

    @Test
    void oneProviderThrowingDoesNotStopTheOthers() {
        List<String> ran = new ArrayList<>();
        int restored = IntegrationSessions.restoreEach(List.of(
                Map.entry("GitHub", () -> {
                    ran.add("GitHub");
                    throw new IllegalStateException("surprise");
                }),
                Map.entry("GitLab", () -> {
                    ran.add("GitLab");
                    return true;
                }),
                Map.entry("Bitbucket", () -> {
                    ran.add("Bitbucket");
                    return false;
                })));

        assertEquals(List.of("GitHub", "GitLab", "Bitbucket"), ran, "every provider was tried");
        assertEquals(1, restored, "and GitLab's reconnection counted");
    }

    @Test
    void connectingAndDisconnectingAreAnnounced() throws InterruptedException {
        flushFx();
        long before = IntegrationEvents.changesProperty().get();
        GitHubSession.connect("token", new GitHubUser("octocat", null));
        GitHubSession.disconnect();
        flushFx();
        assertEquals(before + 2, IntegrationEvents.changesProperty().get());
    }

    /**
     * Waits for changes queued to the FX thread. Alone this class runs with no toolkit and
     * the change is applied straight away; in the suite an earlier test has started one,
     * and the change arrives through {@code runLater}, in order, ahead of this.
     */
    private static void flushFx() throws InterruptedException {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        try {
            javafx.application.Platform.runLater(done::countDown);
        } catch (IllegalStateException noToolkit) {
            return;
        }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
