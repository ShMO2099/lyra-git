package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.testsupport.MainWindowFixture;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the application says while it works and after: progress with a stage and a
 * percentage, a way to stop, notifications that stay until read, and Undo after a delete.
 */
class FeedbackTest {

    @TempDir
    Path tempDir;

    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        RobotWait.drainGitWork();
        FxHarness.closeShownStages();
    }

    /** Holds {@code tab}'s repository lane until {@link #release}. */
    private void holdTheLane(RepoTab tab) {
        GitTaskRunner.run(tab.getGitService(), () -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GitResult.ok("held");
        }, r -> { });
    }

    private static List<Node> buttonsLabelled(Node root, String text) {
        return FxHarness.lookupAll(root, "button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText())).toList();
    }

    private static StatusBar shownBar() {
        return FxHarness.call(() -> {
            StatusBar bar = new StatusBar();
            ToastHost toasts = new ToastHost();
            bar.setToastHost(toasts);
            FxHarness.show(new javafx.scene.layout.VBox(toasts, bar), 900, 300);
            return bar;
        });
    }

    @Test
    void workThatReportsProgressShowsItsStageAndCanBeCancelled() {
        StatusBar bar = shownBar();
        CountDownLatch reported = new CountDownLatch(1);
        AtomicBoolean sawCancel = new AtomicBoolean();
        FxHarness.run(() -> {
            bar.busy("Fetching");
            GitTaskRunner.runDetached(() -> {
                GitTaskRunner.reportProgress("Receiving objects", 0.45);
                reported.countDown();
                long until = System.currentTimeMillis() + 10_000;
                while (!GitTaskRunner.isCancelled() && System.currentTimeMillis() < until) {
                    Thread.onSpinWait();
                }
                sawCancel.set(GitTaskRunner.isCancelled());
                return GitResult.failed("cancelled");
            }, result -> bar.idle());
        });
        RobotWait.untilOnFx("the progress never reached the bar", () -> bar.progressBar().getProgress() > 0.4);
        FxHarness.run(() -> {
            assertEquals(0.45, bar.progressBar().getProgress(), 0.001);
            assertTrue(FxHarness.lookupAll(bar, "status-message").stream()
                    .anyMatch(n -> ((Label) n).getText().contains("Receiving objects 45%")));
            Button cancel = (Button) buttonsLabelled(bar, "Cancel").get(0);
            assertTrue(cancel.isVisible(), "work that reports progress can be stopped");
            cancel.fire();
        });
        RobotWait.untilOnFx("the cancel never reached the work", sawCancel::get);
    }

    @Test
    void anErrorIsANotificationThatStaysAndOffersTheDetails() {
        StatusBar bar = shownBar();
        FxHarness.run(() -> {
            bar.error("Push was rejected\nhint: fetch first\nhint: then push again");
            ToastHost toasts = bar.toastHost();
            assertEquals(1, toasts.shown().size());
            Node card = toasts.shown().get(0);
            assertTrue(card.getStyleClass().contains("toast-error"));
            assertFalse(buttonsLabelled(card, "Details").isEmpty(), "a long message offers its details");
            assertTrue(FxHarness.lookupAll(card, "toast-message").stream()
                    .anyMatch(n -> "Push was rejected".equals(((Label) n).getText())));
        });
    }

    @Test
    void neverMoreThanAFewNotificationsAtOnce() {
        StatusBar bar = shownBar();
        FxHarness.run(() -> {
            for (int i = 0; i < 7; i++) bar.error("failure " + i);
            assertEquals(ToastHost.MAX_SHOWN, bar.toastHost().shown().size(),
                    "the oldest give way");
        });
    }

    @Test
    void undoIsOfferedAndRunsTheWayBack() {
        StatusBar bar = shownBar();
        AtomicBoolean undone = new AtomicBoolean();
        FxHarness.run(() -> {
            bar.offerUndo("Deleted branch topic", () -> undone.set(true));
            Node card = bar.toastHost().shown().get(0);
            ((Button) buttonsLabelled(card, "Undo").get(0)).fire();
        });
        assertTrue(undone.get());
    }

    @Test
    void aMessageDoesNotHideTheProgressOfWorkStillRunning() {
        StatusBar bar = shownBar();
        FxHarness.run(() -> {
            bar.busy("Fetching");
            bar.info("Staged a.txt");
            assertTrue(bar.progressBar().isVisible(), "the fetch is still going");
            bar.idle();
            bar.info("Fetched");
            assertFalse(bar.progressBar().isVisible());
        });
    }

    @Test
    void backgroundWorkIsAQuietSpinner() {
        StatusBar bar = shownBar();
        FxHarness.run(() -> {
            bar.backgroundActivity("Fetching in the background", true);
            assertTrue(bar.showingBackgroundActivity());
            bar.backgroundActivity("Fetching in the background", false);
            assertFalse(bar.showingBackgroundActivity());
        });
    }

    @Test
    void theToolbarSpinsThePressedButtonAndRefusesASecondRun() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RepoTab tab = window.tab();
            holdTheLane(tab);
            FxHarness.run(() -> tab.getToolbar().fetchAll());
            FxHarness.run(() -> {
                ButtonBase fetch = (ButtonBase) tab.getToolbar().getItems().stream()
                        .filter(n -> n instanceof ButtonBase b && "Fetch".equals(b.getAccessibleText()))
                        .findFirst().orElseThrow();
                assertTrue(fetch.getGraphic() instanceof ProgressIndicator, "the pressed button spins");
                assertTrue(tab.getToolbar().isRunning());
                tab.getToolbar().fetchAll();
                assertTrue(FxHarness.lookupAll(tab.getContent(), "status-message").stream()
                                .anyMatch(n -> ((Label) n).getText().contains("still running")),
                        "a second fetch says the first is still going instead of queueing behind it");
            });
            release.countDown();
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("the fetch never finished", () -> !tab.getToolbar().isRunning());
        }
    }

    @Test
    void aDeletedBranchComesBackWithUndo() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RepoTab tab = window.tab();
            String tip;
            try (Git git = Git.open(window.workTree().toFile())) {
                tip = git.getRepository().resolve("refs/heads/feature/search").getName();
            }
            FxHarness.run(() -> tab.getSidebar().deleteBranchWithUndo("feature/search", true));
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("no undo was offered", () -> tab.toastHost().shown().stream()
                    .anyMatch(card -> !buttonsLabelled(card, "Undo").isEmpty()));
            try (Git git = Git.open(window.workTree().toFile())) {
                assertTrue(git.getRepository().resolve("refs/heads/feature/search") == null, "deleted");
            }
            FxHarness.run(() -> {
                Node card = tab.toastHost().shown().stream()
                        .filter(c -> !buttonsLabelled(c, "Undo").isEmpty()).findFirst().orElseThrow();
                ((Button) buttonsLabelled(card, "Undo").get(0)).fire();
            });
            RobotWait.drainGitWork();
            try (Git git = Git.open(window.workTree().toFile())) {
                assertNotNull(git.getRepository().resolve("refs/heads/feature/search"), "restored");
                assertEquals(tip, git.getRepository().resolve("refs/heads/feature/search").getName(),
                        "at the commit it pointed at");
            }
        }
    }
}
