package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bisect wizard driven the way a user actually would: right-click a bad commit in
 * the graph to start, then click the banner's Good/Bad buttons in response to what the
 * checked-out candidate actually contains, until the banner reports the answer.
 *
 * <p>{@code GitServiceTest} and {@code NativeGitBackendTest} already prove the search
 * itself converges on the right commit against each backend directly; what is new here
 * is that the real UI - the graph's context menu, {@code RightPanelManager}'s banner,
 * its buttons - actually reaches that backend and reflects its state back correctly.
 *
 * <h2>Reaching the graph row's context menu under Monocle Headless</h2>
 * As with every other context menu this session, a robot right-click does not become a
 * real {@code ContextMenuEvent} under Monocle Headless, so the event is fired directly
 * on the row - the same approach {@code SquashFromGraphRobotTest} and
 * {@code SidebarBranchRobotTest} already use for this exact reason.
 *
 * <h2>Firing the "Bisect: start here" item specifically</h2>
 * Unlike the shorter menus those two classes click by visible text, {@code
 * GitGraphCell}'s context menu has around twenty items, and probing each one in turn
 * found the same node's text resolving to two matches under a robot {@code lookup} once
 * the graph had refreshed even once - not zero, which the earlier hypothesis (deep
 * items never render) would predict, but a duplicate, consistent with a recycled {@code
 * ListCell} briefly holding two live menu instances. {@link #fireMenuItem} sidesteps the
 * lookup entirely by firing the {@code MenuItem} found by object identity - but not from
 * inside TestFX's {@code interact()}: the action fired opens a real modal {@code
 * Alert.showAndWait()}, which pumps its own nested event loop on the FX thread, and
 * calling {@code fire()} from inside an {@code interact()} block deadlocks the instant
 * that loop starts, because the outer {@code interact()} is itself waiting for the FX
 * thread to finish the very call that never returns. A bare {@code Platform.runLater}
 * submits the action without TestFX waiting on it, which is what lets the alert's own
 * loop actually pump and the test proceed to click "OK" normally afterwards.
 */
class BisectRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        // Every bisect step is git work on the lane that outlives its click; the backend
        // must not be closed under it.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    /** Commits a linear chain, "good" through {@code firstBadIndex - 1} then "bad" from there. */
    private String commitChainWithABugPartway(int count, int firstBadIndex) throws Exception {
        String firstBadHash = null;
        for (int i = 0; i < count; i++) {
            String marker = i >= firstBadIndex ? "bad" : "good";
            Files.writeString(tempDir.resolve("f.txt"), marker + " " + i, StandardCharsets.UTF_8);
            assertTrue(service.stageFile("f.txt").isOk());
            assertTrue(service.commit("commit " + i, false).isOk());
            if (i == firstBadIndex) {
                firstBadHash = resolveHead();
            }
        }
        return firstBadHash;
    }

    private String resolveHead() throws Exception {
        try (Git git = Git.open(tempDir.toFile())) {
            return git.getRepository().resolve("HEAD").getName();
        }
    }

    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Refreshes the tab and lets it finish: its queries run on the git lane, and their
     * results are applied on the FX thread.
     */
    private void refreshTab() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Opens the context menu on the row showing {@code subject}, without a real right-click. */
    private void openMenuOn(String subject) {
        // The graph's rows are laid out a pulse after the refresh that fills them.
        RobotWait.untilOnFx("no row showing \"" + subject + "\" appeared",
                () -> lookup(subject).tryQuery().isPresent());
        Node row = lookup(subject).query();
        interact(() -> Event.fireEvent(row, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
    }

    private ContextMenu openContextMenu() {
        return (ContextMenu) Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(w -> w instanceof ContextMenu)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no context menu is currently showing"));
    }

    private MenuItem findMenuItem(ContextMenu menu, String textPrefix) {
        return menu.getItems().stream()
                .filter(i -> i.getText() != null && i.getText().startsWith(textPrefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no menu item starting with \"" + textPrefix + "\""));
    }

    /**
     * Fires a menu item's action directly, found by object identity, rather than
     * through {@code clickOn(text)} - see the class Javadoc for why a text lookup
     * against this specific menu is not reliable. Also hides the menu explicitly
     * afterwards: opened by a synthetic event rather than a real mouse press, it is not
     * reliably auto-hidden once its item fires, and left showing it is one more window
     * a later lookup in this test could land on by mistake.
     */
    private void fireMenuItem(ContextMenu menu, String textPrefix) {
        MenuItem item = findMenuItem(menu, textPrefix);
        // item.fire() is not itself wrapped in interact(): the action it runs opens a
        // real modal Alert via showAndWait(), and showAndWait() pumps its own nested
        // event loop on the FX thread. Calling fire() from inside a TestFX interact()
        // block - which itself waits on the FX thread to finish running what it was
        // given - deadlocks the moment the nested loop starts, because the outer
        // interact() never sees its Runnable return. Platform.runLater alone submits
        // the action without TestFX waiting on it, which is what lets the alert's own
        // event loop actually pump.
        javafx.application.Platform.runLater(() -> {
            item.fire();
            menu.hide();
        });
    }

    /**
     * Confirms the "starts a bisect session" alert that follows firing the start item, and
     * waits for the session it starts. That is three round trips - start, mark this commit
     * bad, then the refresh that reads the status back - before the banner goes up.
     */
    private void confirmStartAlert() {
        RobotWait.untilDialogWithButton("OK");
        clickOn("Start bisect");
        RobotWait.untilOnFx("the bisect banner never appeared",
                () -> bannerButtonExists("Mark good") || bannerButtonExists("End bisect"));
    }

    /**
     * Clicks a Mark good/bad button and waits for the step to show. The mark runs on the
     * git lane, and its callback asks for a refresh that reads the new status on the lane
     * again before the banner is rebuilt - so the step has landed once the banner this
     * button belongs to has been taken down. Clicking again before then would act on the
     * old banner's candidate.
     */
    private void clickBannerButton(String text) {
        Node button = lookup(text).query();
        clickOn(button);
        // Both halves: the old banner gone, and its successor up - the loop reads the
        // new banner's buttons next, and a moment with neither would end it early.
        RobotWait.untilOnFx("the bisect banner was never rebuilt after " + text,
                () -> button.getScene() == null
                        && (bannerButtonExists("Mark good") || bannerButtonExists("Done - reset")));
    }

    private boolean bannerButtonExists(String text) {
        return lookup(text).tryQuery().isPresent();
    }

    // ------------------------------------------------------------------

    @Test
    void startingBisectFromTheGraphMarksTheSelectedCommitBad() throws Exception {
        commitChainWithABugPartway(5, 5); // every commit here reads "good"
        Files.writeString(tempDir.resolve("f.txt"), "bad final", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("f.txt").isOk());
        assertTrue(service.commit("commit final (bad)", false).isOk());
        refreshTab();

        openMenuOn("commit final (bad)");
        fireMenuItem(openContextMenu(), "Bisect: start here");
        confirmStartAlert();

        assertTrue(bannerButtonExists("Mark good") || bannerButtonExists("End bisect"),
                "starting a session should show the bisect banner");
    }

    @Test
    void theWizardConvergesOnTheExactFirstBadCommitThroughTheRealUi() throws Exception {
        Files.writeString(tempDir.resolve("f.txt"), "seed", StandardCharsets.UTF_8);
        assertTrue(service.stageFile("f.txt").isOk());
        assertTrue(service.commit("seed (good)", false).isOk());
        String goodHash = resolveHead();

        String firstBad = commitChainWithABugPartway(11, 6);
        refreshTab();

        // Start bisect from HEAD (the last, bad, commit).
        openMenuOn("commit 10");
        fireMenuItem(openContextMenu(), "Bisect: start here");
        confirmStartAlert();

        // Mark the known-good seed commit directly through the backend - the banner's
        // own buttons only ever act on whatever candidate is currently checked out,
        // which is exactly right for every step after this one but cannot supply the
        // *other* endpoint a session needs before it has a candidate at all.
        interact(() -> service.bisectMarkGood(goodHash));
        interact(() -> tab.refresh());
        // The banner had only "End bisect" before there was a candidate to test.
        RobotWait.untilOnFx("the banner never offered the first candidate",
                () -> bannerButtonExists("Mark good") || bannerButtonExists("Done - reset"));

        int guard = 0;
        while (bannerButtonExists("Mark good")) {
            assertTrue(++guard < 50, "bisect did not converge through the UI");
            String content = Files.readString(tempDir.resolve("f.txt")).strip();
            if (content.startsWith("bad")) {
                clickBannerButton("Mark bad");
            } else {
                clickBannerButton("Mark good");
            }
        }

        assertTrue(bannerButtonExists("Done - reset"), "the banner should report the answer once found");
        assertEquals(firstBad, resolveHead(),
                "the UI-driven session should land on exactly the commit that introduced the bug");

        clickOn("Done - reset");
        RobotWait.until("the bisect was never reset", () -> !service.isHeadDetached());
        assertFalse(service.isHeadDetached(), "resetting should return to the original branch");
    }

    @Test
    void startingASecondSessionWhileOneIsActiveIsRefused() throws Exception {
        commitChainWithABugPartway(4, 2);
        refreshTab();

        openMenuOn("commit 3");
        fireMenuItem(openContextMenu(), "Bisect: start here");
        confirmStartAlert();
        refreshTab();

        openMenuOn("commit 3");
        // The menu asks git whether a session is running as it opens, and relabels the
        // item when the answer arrives.
        RobotWait.untilOnFx("the menu never learned a session is running",
                () -> openContextMenu().getItems().stream().anyMatch(i -> i.getText() != null
                        && i.getText().startsWith("Bisect: session already running")));
        MenuItem secondStart = findMenuItem(openContextMenu(), "Bisect: session already running");

        assertTrue(secondStart.isDisable(), "a second start while one session is active must be refused");
    }
}
