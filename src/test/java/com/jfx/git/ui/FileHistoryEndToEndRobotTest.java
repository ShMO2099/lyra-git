package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.model.CommitInfo;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives File History the way a user actually does: open it on a file with real history,
 * click through several commits, and flip Blame/Unified/Split/Whole file on and off in
 * between - rather than calling one render method in isolation the way {@code
 * DiffViewerRenderTest} does.
 *
 * <p>Every one of those render paths (plain diff, split diff, whole-file diff in both
 * layouts, and blame) shares the same {@link FileHistoryView}/{@link DiffViewer}/{@link
 * DiffScrollMap} plumbing, so a regression in any of them tends to surface only once a
 * real sequence of user actions is replayed against real commits - a single-shot unit test
 * that only ever renders one commit's diff in isolation cannot see a bug that only shows up
 * on the third commit after two layout changes. This test's commit history is deliberately
 * built with hunks that have unequal add/delete counts (drift), at the start, middle and
 * true end of the file, in both directions, since that is exactly the shape of change a
 * long-lived real source file accumulates and the shape earlier drift/trailing-context bugs
 * in {@code DiffRows} were hiding behind.
 *
 * <p>Failures are caught two ways, since a render exception can surface on either thread:
 * a background {@code GitTaskRunner} failure is reported through the status bar, while an
 * exception thrown while rendering on the FX Application thread itself (for instance from
 * a {@code Platform.runLater} delivery callback, which {@code GitTaskRunner.query} does not
 * wrap in a try/catch) would otherwise only print to stderr and pass the test silently.
 */
class FileHistoryEndToEndRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;
    private final AtomicReference<Throwable> fxError = new AtomicReference<>();

    private static List<String> initialLines() {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            lines.add("line" + i);
        }
        return lines;
    }

    private static String text(List<String> lines) {
        return String.join("\n", lines) + "\n";
    }

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

        // v1 -> v2: a hunk near the start growing by two lines (positive drift) and a
        // second hunk shrinking by four lines that lands exactly at end-of-file.
        List<String> v1 = initialLines();
        commit("History.java", text(v1), "v1 - initial file");

        List<String> v2 = new ArrayList<>(v1);
        v2.subList(4, 6).clear();
        v2.addAll(4, List.of("alpha1", "alpha2", "alpha3", "alpha4"));
        v2.subList(26, 32).clear();
        v2.addAll(26, List.of("gamma1", "gamma2"));
        commit("History.java", text(v2), "v2 - drift both ways, last hunk at eof");

        // v2 -> v3: a mid-file hunk shrinking by two lines, with unchanged content after
        // it - the tail-context path, not landing at end-of-file this time - plus an
        // unrelated single-line edit near the first hunk so two hunks with a real gap
        // between them are both in play at once.
        List<String> v3 = new ArrayList<>(v2);
        v3.subList(16, 19).clear();
        v3.addAll(16, List.of("delta1"));
        v3.set(5, "alpha2-edited");
        commit("History.java", text(v3), "v3 - mid-file hunk, trailing context not at eof");

        // v3 -> v4: an edit at line one itself (no leading gap at all for that hunk) plus
        // a pure addition landing exactly at the new end-of-file.
        List<String> v4 = new ArrayList<>(v3);
        v4.set(0, "line1-edited");
        v4.add("omega1");
        v4.add("omega2");
        commit("History.java", text(v4), "v4 - leading-edge hunk plus trailing addition at eof");

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        // Every selection reads from git on the lane; the backend must outlive the last one.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(tempDir.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    /**
     * Waits until whatever the last selection or toggle started has been drawn.
     *
     * <p>Each one reads from git on the lane and renders in a callback on the FX thread,
     * and that callback is where a render error is thrown. So the lane is drained - every
     * read has finished and posted its callback - and then the FX thread is flushed, which
     * runs them. Most checks here are that no error appeared, which a wait for some
     * outcome could never establish; this waits for the render itself instead.
     */
    private void awaitRender() {
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Opens File History and installs an uncaught-exception trap on the FX Application
     * thread for the rest of the test - the only way to catch a render exception that
     * {@code GitTaskRunner.query}'s {@code Platform.runLater} delivery does not route to
     * the status bar.
     */
    private FileHistoryView openHistory(boolean blame) {
        interact(() -> {
            Thread.currentThread().setUncaughtExceptionHandler((t, e) -> fxError.compareAndSet(null, e));
            tab.refresh();
            tab.openFileHistory("History.java", blame);
        });
        List<Node> found = FxHarness.lookupAll(tab.getContent(), "file-history");
        assertEquals(1, found.size(), "file history view should be open");
        FileHistoryView view = (FileHistoryView) found.get(0);
        // Two hops: the commit list loads, and only then selects the newest commit, whose
        // content is a second read.
        RobotWait.untilOnFx("the file's history never loaded", () -> !commitList(view).getItems().isEmpty());
        awaitRender();
        return view;
    }

    @SuppressWarnings("unchecked")
    private ListView<CommitInfo> commitList(FileHistoryView view) {
        return (ListView<CommitInfo>) (ListView<?>) FxHarness.lookupAll(view, "file-history-list").get(0);
    }

    /** {@code File view}/{@code Diff view}, {@code Unified}/{@code Split} and {@code Blame}
     *  all share the {@code diff-segment} style class, so the button text picks one out. */
    private ToggleButton segment(FileHistoryView view, String label) {
        return FxHarness.lookupAll(view, "diff-segment").stream()
                .filter(n -> n instanceof Labeled l && label.equals(l.getText()))
                .map(n -> (ToggleButton) n)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + label + "\" toggle found"));
    }

    /** Context: All, which is what Whole file became. */
    private ToggleButton wholeFileToggle(FileHistoryView view) {
        return segment(view, "All");
    }

    /** Context: 3, the way back out of the whole file. */
    private ToggleButton gitContextToggle(FileHistoryView view) {
        return segment(view, "3");
    }

    private String statusMessage() {
        List<Node> labels = FxHarness.lookupAll(tab.getContent(), "status-message");
        return labels.isEmpty() ? "" : ((Label) labels.get(0)).getText();
    }

    /** Clicks through every commit in {@code commits}, failing with whatever the current
     *  toggle combination was ({@code phase}) the moment either failure channel fires. */
    private void selectEachCommitInTurn(ListView<CommitInfo> commits, String phase) {
        int count = commits.getItems().size();
        for (int i = 0; i < count; i++) {
            int index = i;
            interact(() -> commits.getSelectionModel().select(index));
            awaitRender();
            assertNull(fxError.get(), phase + ": commit " + index + " threw: " + stackOf(fxError.get()));
            String status = statusMessage();
            assertFalse(status.toLowerCase(java.util.Locale.ROOT).contains("out of bounds"),
                    phase + ": commit " + index + " reported an error: " + status);
        }
    }

    @Test
    void aUserBrowsingHistoryTogglingBlameAndLayoutsNeverHitsARenderError() {
        FileHistoryView view = openHistory(false);
        ListView<CommitInfo> commits = commitList(view);
        assertEquals(4, commits.getItems().size(), "all four commits should be listed");

        selectEachCommitInTurn(commits, "plain unified diff");

        interact(() -> segment(view, "Split").fire());
        awaitRender();
        selectEachCommitInTurn(commits, "split diff");

        interact(() -> segment(view, "Unified").fire());
        interact(() -> wholeFileToggle(view).fire());
        awaitRender();
        selectEachCommitInTurn(commits, "whole file, unified");

        interact(() -> segment(view, "Split").fire());
        awaitRender();
        selectEachCommitInTurn(commits, "whole file, split");

        // Whole file is meaningless once Blame takes over the pane - turn it off first,
        // the way the toolbar itself insists on (more context is disabled in Blame mode).
        interact(() -> gitContextToggle(view).fire());
        interact(() -> segment(view, "Blame").fire());
        awaitRender();
        selectEachCommitInTurn(commits, "blame");

        interact(() -> segment(view, "Blame").fire());
        awaitRender();
        selectEachCommitInTurn(commits, "back to plain diff after blame");

        assertNull(fxError.get(), "no render exception should have escaped the whole session: " + fxError.get());
    }

    @Test
    void switchingToDiffViewWhileBlameIsOnActuallyLeavesBlame() {
        // Blame used to win unconditionally: the view-mode handler re-rendered through
        // showCommit, which checked the blame toggle first, so Diff view re-entered blame
        // and syncToggles put the File view button straight back. The toggle looked dead.
        FileHistoryView view = openHistory(true);
        ListView<CommitInfo> commits = commitList(view);
        interact(() -> commits.getSelectionModel().select(0));
        awaitRender();
        assertFalse(FxHarness.lookupAll(view, "blame-band").isEmpty(),
                "precondition: opening with blame on should annotate the file");

        interact(() -> segment(view, "Diff view").fire());
        awaitRender();

        assertTrue(FxHarness.lookupAll(view, "blame-band").isEmpty(),
                "Diff view must actually leave blame rather than springing back to it");
        assertFalse(segment(view, "Blame").isSelected(),
                "and the blame toggle should show that it is no longer on");
        assertNull(fxError.get());
    }

    private String renderedText(FileHistoryView view) {
        return ((DiffTextArea) FxHarness.lookupAll(view, "code-area").get(0)).getText();
    }

    /**
     * Guards the specific bug this class was added to chase down: {@code DiffRows}'
     * expansion logic reads revealed context by the diff's <em>new</em>-side line
     * number, but {@code FileHistoryView}'s file path never changes as the user clicks
     * through commits - only the content behind it does - so {@code DiffViewer}'s own
     * same-path expansion cache had no way to tell a genuine commit change from an
     * incidental re-render of the one already showing, and kept serving whichever
     * commit's content it first loaded into every commit selected afterwards.
     */
    @Test
    void selectingADifferentCommitInWholeFileModeShowsThatCommitsOwnContent() {
        FileHistoryView view = openHistory(false);
        ListView<CommitInfo> commits = commitList(view);

        // Newest first: index 3 is v1, the plain 30-line original; index 0 is v4, which
        // edits line 1 and appends omega1/omega2 past the original end of file.
        interact(() -> commits.getSelectionModel().select(3));
        awaitRender();
        interact(() -> wholeFileToggle(view).fire());
        awaitRender();
        assertNull(fxError.get());

        interact(() -> commits.getSelectionModel().select(0));
        awaitRender();
        assertNull(fxError.get());

        String rendered = renderedText(view);
        assertTrue(rendered.contains("line1-edited"),
                "v4's own edited first line should be visible: " + rendered);
        assertTrue(rendered.contains("omega1") && rendered.contains("omega2"),
                "whole file view of v4 should reveal its own trailing addition, not "
                        + "content cached from v1 (the commit selected before Whole file "
                        + "was turned on): " + rendered);
        assertTrue(rendered.contains("alpha2-edited") && rendered.contains("delta1"),
                "the untouched middle of the file - v4 carries these over from v3 "
                        + "unchanged - should also come from v4's real content, not v1's: "
                        + rendered);
    }

    /** The whole trace: an exception from deep in Flowless is useless as its message alone. */
    private static String stackOf(Throwable error) {
        if (error == null) {
            return "nothing";
        }
        java.io.StringWriter out = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }
}
