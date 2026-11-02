package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The right panel's two new shapes, driven through the real controls.
 *
 * <p>Both are things that could pass an inspection of the code and still be wrong in the
 * window: a stash whose name is discarded somewhere between a text field and {@code git
 * stash push}, and a file tree that renders but selects the wrong file - or nothing -
 * when a row is clicked. So the assertions read real git state and the real scene graph,
 * never the panel's own idea of what it did.
 */
class RightPanelComposerRobotTest extends ApplicationTest {

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
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
        }
        service = new GitService();
        service.openRepository(tempDir.toFile());

        // A commit spread over nested folders, which is the case the tree exists for -
        // a flat list of these five paths is a column of shared prefixes.
        commit(List.of(
                "README.md",
                "src/main/java/App.java",
                "src/main/java/util/Text.java",
                "src/test/java/AppTest.java",
                "docs/guide.md"), "the commit under test");

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        // A click that starts git work returns before the work does; wait for it, so the
        // backend is not closed - and @TempDir not deleted - under a running command.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(tab::close);
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private void commit(List<String> paths, String message) throws Exception {
        for (String path : paths) {
            Path file = tempDir.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "content of " + path + "\n", StandardCharsets.UTF_8);
            assertTrue(service.stageFile(path).isOk(), path);
        }
        assertTrue(service.commit(message, false).isOk());
    }

    private void writeFile(String path, String content) throws Exception {
        Files.writeString(tempDir.resolve(path), content, StandardCharsets.UTF_8);
    }

    private void settle() {
        WaitForAsyncUtils.waitForFxEvents();
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Selects a graph row through the list's own model.
     *
     * <p>A text lookup cannot reach a row that has not been realised yet, and only a real
     * null-to-row transition fires the listener that builds the panel - the same reasoning
     * {@code CommitPanelRobotTest.openStagingArea} documents.
     *
     * <p>Returns before the panel is built: the reload and the query behind the panel run
     * off the FX thread. Follow it with {@link #awaitCommitFiles} or {@link
     * #awaitStagingArea}, whichever the row is.
     */
    private void selectGraphRow(int index) {
        interact(() -> {
            tab.refresh();
            tab.graphList().getSelectionModel().clearSelection();
            tab.graphList().getSelectionModel().select(index);
        });
    }

    /** Waits for the selected commit's changed files to be listed; they load off the FX thread. */
    private void awaitCommitFiles() {
        RobotWait.untilOnFx("the commit's changed files were never listed",
                () -> !lookup(".changed-files-list").queryAs(javafx.scene.control.ListView.class)
                        .getItems().isEmpty());
    }

    /**
     * Waits for the staging area to show the repository as it is now - both lists matched
     * against real git state, so a panel from before the last change cannot pass for it.
     */
    private void awaitStagingArea() {
        var expected = service.getStagingState();
        RobotWait.untilOnFx("the staging area never showed " + expected,
                () -> !lookup(".staging-unstaged-list").queryAll().isEmpty()
                        && List.copyOf(rowsOf("staging-unstaged-list")).equals(expected.unstaged())
                        && List.copyOf(rowsOf("staging-staged-list")).equals(expected.staged()));
        // Built in a callback; laid out on the pulse after it, before anything clicks into it.
        WaitForAsyncUtils.waitForFxEvents();
    }

    @SuppressWarnings("unchecked")
    private TreeView<Object> filesTree() {
        return (TreeView<Object>) lookup(".changed-files-tree").query();
    }

    private ToggleButton toggle(String styleClass) {
        return lookup("." + styleClass).queryAs(ToggleButton.class);
    }

    /**
     * The rows visible in one of the staging lists.
     *
     * <p>Reads the ListView's items, which since the filter went in is a
     * {@code FilteredList} view rather than the backing list - so this reports what is on
     * screen, which is what the filter tests are about.
     */
    private List<?> rowsOf(String styleClass) {
        return lookup("." + styleClass).queryAll().stream()
                .filter(node -> node instanceof javafx.scene.control.ListView<?>)
                .map(node -> (javafx.scene.control.ListView<?>) node)
                .findFirst()
                .<List<?>>map(javafx.scene.control.ListView::getItems)
                .orElse(List.of());
    }

    /** Every leaf under {@code item}, in the order the tree would draw them. */
    private static void collectLeaves(TreeItem<?> item, List<String> into) {
        for (TreeItem<?> child : item.getChildren()) {
            if (child.isLeaf()) {
                into.add(String.valueOf(child.getValue()));
            } else {
                collectLeaves(child, into);
            }
        }
    }

    // ------------------------------------------------------------------
    // The changed-files tree
    // ------------------------------------------------------------------

    /**
     * A filter that matches nothing says so, rather than drawing an empty box.
     *
     * <p>The same symptom as the collapse bug below, from a different cause: a bordered
     * rectangle with nothing in it is what a panel that failed to load looks like, so the
     * one thing it must not do is stay silent. The wording separates the two kinds of
     * nothing - the user's own filter answering them, versus a fact about the commit.
     */
    @Test
    void aFilterThatMatchesNothingExplainsItself() {
        selectGraphRow(0);
        awaitCommitFiles();

        TextField filter = lookup(".changed-files-filter").queryAs(TextField.class);
        interact(() -> filter.setText("no-such-file-anywhere"));
        settle();

        javafx.scene.control.Label empty =
                lookup(".changed-files-empty").queryAs(javafx.scene.control.Label.class);
        assertTrue(empty.isVisible(), "an empty result has to say why it is empty");
        assertTrue(empty.getText().contains("no-such-file-anywhere"),
                "and name the filter that produced it: " + empty.getText());

        interact(() -> filter.setText(""));
        settle();
        assertFalse(empty.isVisible(), "clearing the filter puts the files back");
    }

    /**
     * "Collapse all" folds the folders; it does not empty the panel.
     *
     * <h2>What went wrong</h2>
     * The tree is drawn with {@code setShowRoot(false)}, and folding walked from the root
     * down - so it collapsed the invisible root, whose children are every row there is.
     * The result was a blank box with an "Expand all" button as the only way back, which
     * looked exactly like a tree that had failed to load.
     */
    @Test
    void collapsingTheTreeFoldsTheFoldersRatherThanEmptyingIt() {
        selectGraphRow(0);
        awaitCommitFiles();
        interact(() -> toggle("changed-files-tree-toggle").fire());
        settle();

        TreeView<Object> tree = filesTree();
        int openRows = tree.getExpandedItemCount();
        assertTrue(openRows > 0, "sanity: the tree has rows while expanded");

        javafx.scene.control.Button fold = lookup(".row-action").queryAll().stream()
                .filter(n -> n instanceof javafx.scene.control.Button b
                        && "Collapse all".equals(b.getText()))
                .map(n -> (javafx.scene.control.Button) n)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no Collapse all button"));
        interact(fold::fire);
        settle();

        int foldedRows = tree.getExpandedItemCount();
        assertTrue(foldedRows > 0,
                "collapsing must leave the top-level rows on screen, not blank the "
                        + "panel; rows went from " + openRows + " to " + foldedRows);
        assertEquals(tree.getRoot().getChildren().size(), foldedRows,
                "exactly the top level should remain visible");
        assertTrue(tree.getRoot().isExpanded(),
                "the hidden root has to stay open or it hides everything beneath it");
    }

    @Test
    void theTreeMirrorsTheFoldersTheCommitTouched() {
        selectGraphRow(0);
        awaitCommitFiles();

        interact(() -> toggle("changed-files-tree-toggle").fire());
        settle();

        TreeView<Object> tree = filesTree();
        assertNotNull(tree.getRoot(), "the tree should have been built from the commit");

        List<String> topLevel = tree.getRoot().getChildren().stream()
                .map(item -> String.valueOf(item.getValue()))
                .toList();
        // Folders first, then files, each alphabetical - what every file browser does.
        assertEquals(List.of("docs", "src", "README.md"), topLevel,
                "top level should be the two folders the commit touched, then the loose file");

        List<String> leaves = new java.util.ArrayList<>();
        collectLeaves(tree.getRoot(), leaves);
        assertEquals(5, leaves.size(), "every changed file should still be reachable: " + leaves);
    }

    @Test
    void clickingAFileInTheTreeOpensThatFilesDiff() {
        selectGraphRow(0);
        awaitCommitFiles();
        interact(() -> toggle("changed-files-tree-toggle").fire());
        settle();

        TreeView<Object> tree = filesTree();
        TreeItem<Object> leaf = deepestLeafNamed(tree.getRoot(), "Text.java");
        assertNotNull(leaf, "src/main/java/util/Text.java should be in the tree");

        interact(() -> tree.getSelectionModel().select(leaf));
        // The diff is requested from the git lane.
        RobotWait.untilOnFx("Text.java's diff never opened", () -> diffMentions("Text.java"));

        // The diff viewer is what a selection is for; a tree that renders but opens
        // nothing is the failure this guards against.
        assertTrue(diffMentions("Text.java"),
                "selecting a tree row should have opened that file's diff");
    }

    @Test
    void theFilterNarrowsBothViews() {
        selectGraphRow(0);
        awaitCommitFiles();

        TextField filter = lookup(".changed-files-filter").queryAs(TextField.class);
        interact(() -> filter.setText("java"));
        settle();

        javafx.scene.control.ListView<?> list =
                lookup(".changed-files-list").queryAs(javafx.scene.control.ListView.class);
        assertEquals(3, list.getItems().size(),
                "three of the five paths contain \"java\": " + list.getItems());

        interact(() -> toggle("changed-files-tree-toggle").fire());
        settle();
        List<String> leaves = new java.util.ArrayList<>();
        collectLeaves(filesTree().getRoot(), leaves);
        assertEquals(3, leaves.size(),
                "the tree must be filtered to the same three files: " + leaves);
    }

    // ------------------------------------------------------------------
    // The working tree's own filter
    // ------------------------------------------------------------------

    /**
     * The working tree gets the filter a commit's file list already had.
     *
     * <p>A commit's changed files got a filter and a Path/Tree toggle when FilesSection
     * was built. The working tree - the list actually worked in, and the one that reaches
     * seventy entries after a Gradle build touches its own output - got neither.
     */
    @Test
    void filteringTheWorkingTreeNarrowsBothLists() throws Exception {
        writeFile("README.md", "changed\n");
        writeFile("src/main/java/App.java", "changed\n");
        assertTrue(service.stageFile("src/main/java/App.java").isOk());
        selectGraphRow(0);
        awaitStagingArea();

        var unstaged = rowsOf("staging-unstaged-list");
        var staged = rowsOf("staging-staged-list");
        assertTrue(unstaged.contains("README.md"), "unstaged: " + unstaged);
        assertTrue(staged.contains("src/main/java/App.java"), "staged: " + staged);

        TextField filter = lookup(".staging-filter").queryAs(TextField.class);
        interact(() -> filter.setText("README"));
        settle();

        assertTrue(rowsOf("staging-unstaged-list").contains("README.md"),
                "the match must stay");
        assertFalse(rowsOf("staging-staged-list").contains("src/main/java/App.java"),
                "one filter covers both lists: a path is a path whichever side it is on");
    }

    /** Clearing the filter brings everything back rather than leaving a narrowed list. */
    @Test
    void clearingTheWorkingTreeFilterRestoresEveryRow() throws Exception {
        writeFile("README.md", "changed\n");
        writeFile("docs/guide.md", "changed\n");
        selectGraphRow(0);
        awaitStagingArea();
        int before = rowsOf("staging-unstaged-list").size();

        TextField filter = lookup(".staging-filter").queryAs(TextField.class);
        interact(() -> filter.setText("README"));
        settle();
        assertTrue(rowsOf("staging-unstaged-list").size() < before, "the filter narrowed it");

        interact(() -> filter.setText(""));
        settle();
        assertEquals(before, rowsOf("staging-unstaged-list").size(),
                "clearing the filter must restore every row");
    }

    /**
     * Staging while a filter is active still moves the file.
     *
     * <p>The lists are FilteredList views now, which cannot be added to or removed from;
     * every optimistic move had to be redirected to the backing lists. This is the test
     * that would fail if one of those thirty-odd call sites had been missed.
     */
    @Test
    void stagingWhileFilteredStillMovesTheFile() throws Exception {
        writeFile("README.md", "changed\n");
        writeFile("docs/guide.md", "changed\n");
        selectGraphRow(0);
        awaitStagingArea();

        TextField filter = lookup(".staging-filter").queryAs(TextField.class);
        interact(() -> filter.setText("README"));
        settle();

        clickOn("README.md");
        settle();
        clickOn("Stage");
        RobotWait.until("README.md was never staged",
                () -> service.getStagingState().staged().contains("README.md"));

        assertTrue(service.getStagingState().staged().contains("README.md"),
                "the real index should show it staged");
        interact(() -> filter.setText(""));
        settle();
        assertTrue(rowsOf("staging-staged-list").contains("README.md"),
                "and the row should have moved lists, not vanished");
    }

    // ------------------------------------------------------------------
    // The stash composer
    // ------------------------------------------------------------------

    @Test
    void stashingFromTheComposerKeepsTheNameTyped() throws Exception {
        writeFile("README.md", "half-finished work\n");
        selectGraphRow(0); // the WIP row, which is newest while the tree is dirty
        awaitStagingArea();

        interact(() -> toggle("composer-tab-stash").fire());
        settle();

        TextField title = lookup(".stash-title").queryAs(TextField.class);
        interact(() -> title.setText("half the readme rewrite"));
        clickOn("Stash changes");
        // Both halves of a stash - recording it and clearing the tree - are waited for,
        // since both are asserted.
        RobotWait.until("the stash never landed", () -> service.getStashEntries().size() == 1
                && !service.hasUncommittedChanges());

        var stashes = service.getStashEntries();
        assertEquals(1, stashes.size(), "the click should have produced a real stash");
        assertEquals("half the readme rewrite", stashes.get(0).shortDescription(),
                "the typed name has to survive the trip to git: " + stashes.get(0).message());
        assertFalse(service.hasUncommittedChanges(),
                "stashing should have taken the work off the tree");
    }

    /**
     * The keyboard submit follows the composer, not the other way round.
     *
     * <p>{@code Ctrl+Enter} is installed as a scene-level event filter, so it runs before
     * the focused control and a handler on the stash fields would never see it. Pointed
     * at committing unconditionally, typing a stash name and pressing the key that
     * finishes every other composer in the application would commit instead - which, with
     * nothing staged, is not even a recoverable mistake so much as a baffling one.
     */
    @Test
    void submittingWhileTheStashTabIsShowingStashes() throws Exception {
        writeFile("README.md", "half-finished work\n");
        selectGraphRow(0);
        awaitStagingArea();

        interact(() -> toggle("composer-tab-stash").fire());
        settle();
        TextField title = lookup(".stash-title").queryAs(TextField.class);
        interact(() -> title.setText("keyboard stash"));

        interact(() -> tab.getRightPanel().submitComposer());
        RobotWait.until("the stash never landed", () -> service.getStashEntries().size() == 1);

        assertEquals(1, service.getStashEntries().size(),
                "the keyboard submit should have stashed, not committed");
        assertEquals("keyboard stash", service.getStashEntries().get(0).shortDescription());
    }

    @Test
    void theComposerStartsOnCommitAndSwitchesBack() throws Exception {
        writeFile("README.md", "half-finished work\n");
        selectGraphRow(0);
        awaitStagingArea();

        assertTrue(toggle("composer-tab-commit").isSelected(),
                "committing is the default; stashing is the deliberate choice");

        interact(() -> toggle("composer-tab-stash").fire());
        settle();
        assertTrue(toggle("composer-tab-stash").isSelected());
        assertFalse(toggle("composer-tab-commit").isSelected(),
                "the two are one slot, not two panels stacked");

        interact(() -> toggle("composer-tab-commit").fire());
        settle();
        assertTrue(toggle("composer-tab-commit").isSelected());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static TreeItem<Object> deepestLeafNamed(TreeItem<Object> item, String name) {
        for (TreeItem<Object> child : (List<TreeItem<Object>>) (List<?>) item.getChildren()) {
            if (child.isLeaf() && String.valueOf(child.getValue()).equals(name)) {
                return child;
            }
            TreeItem<Object> found = deepestLeafNamed(child, name);
            if (found != null) return found;
        }
        return null;
    }

    /** Whether the diff area currently shows something belonging to {@code path}. */
    private boolean diffMentions(String path) {
        return lookup(".diff-file-path").queryAll().stream()
                .anyMatch(node -> node.getAccessibleText() != null
                        && node.getAccessibleText().contains(path));
    }

    // ------------------------------------------------------------------
    // Line counts on staging rows
    // ------------------------------------------------------------------

    /**
     * The counts drawn on the row for {@code path}, as "+a" and "-d", or null if that row
     * is showing none.
     *
     * <p>Read from the scene graph rather than from the map behind it: the numbers arrive
     * on a background query after the rows are already on screen, so the thing that can
     * fail is the repaint, not the arithmetic.
     */
    private String countsOn(String listClass, String path) {
        for (javafx.scene.Node row : lookup("." + listClass + " .file-row").queryAll()) {
            boolean isThisFile = FxHarness.lookupAll(row, "file-normal").stream()
                    .map(n -> ((javafx.scene.control.Label) n).getText())
                    .anyMatch(text -> path.endsWith(text));
            if (!isThisFile) {
                continue;
            }
            List<javafx.scene.Node> added = FxHarness.lookupAll(row, "stat-add");
            List<javafx.scene.Node> deleted = FxHarness.lookupAll(row, "stat-del");
            if (added.isEmpty() || deleted.isEmpty()) {
                return null;
            }
            return ((javafx.scene.control.Label) added.get(0)).getText()
                    + " " + ((javafx.scene.control.Label) deleted.get(0)).getText();
        }
        throw new AssertionError("no row for " + path + " in ." + listClass);
    }

    /** Waits for the row's counts to read {@code expected}; they arrive on a background query. */
    private void awaitCounts(String listClass, String path, String expected) {
        RobotWait.untilOnFx("the row for " + path + " never showed " + expected,
                () -> expected.equals(countsOn(listClass, path)));
    }

    @Test
    void anUnstagedRowShowsHowMuchTheFileChanged() throws Exception {
        // README.md was committed as one line; three lines replace it.
        writeFile("README.md", "one\ntwo\nthree\n");
        selectGraphRow(0);
        awaitStagingArea();
        awaitCounts("staging-unstaged-list", "README.md", "+3 " + "\u2212" + "1");

        assertEquals("+3 " + "\u2212" + "1", countsOn("staging-unstaged-list", "README.md"),
                "three lines in, the single committed line out");
    }

    @Test
    void aStagedRowCountsTheIndexAgainstHead() throws Exception {
        writeFile("README.md", "one\ntwo\n");
        assertTrue(service.stageFile("README.md").isOk());
        selectGraphRow(0);
        awaitStagingArea();
        awaitCounts("staging-staged-list", "README.md", "+2 " + "−" + "1");

        assertEquals("+2 " + "\u2212" + "1", countsOn("staging-staged-list", "README.md"));
    }

    /**
     * Staging a file moves it between two lists whose counts mean different things, so
     * the numbers have to be re-read rather than carried across with the row.
     */
    @Test
    void theCountsFollowAFileAcrossTheStagingLine() throws Exception {
        writeFile("README.md", "one\ntwo\nthree\n");
        selectGraphRow(0);
        awaitStagingArea();
        awaitCounts("staging-unstaged-list", "README.md", "+3 " + "\u2212" + "1");
        assertEquals("+3 " + "\u2212" + "1", countsOn("staging-unstaged-list", "README.md"),
                "sanity: it starts unstaged with counts");

        assertTrue(service.stageFile("README.md").isOk());
        selectGraphRow(0);
        awaitStagingArea();
        awaitCounts("staging-staged-list", "README.md", "+3 " + "\u2212" + "1");

        assertEquals("+3 " + "\u2212" + "1", countsOn("staging-staged-list", "README.md"),
                "the same change, now measured from the index instead of the tree");
    }

    /** A binary file has no line count, and the row must say nothing rather than "+0". */
    @Test
    void aBinaryRowShowsNoNumbersAtAll() throws Exception {
        byte[] bytes = new byte[128];
        bytes[0] = (byte) 0x89;
        bytes[9] = 0;
        Files.write(tempDir.resolve("blob.bin"), bytes);
        selectGraphRow(0);
        awaitStagingArea();
        // "No numbers" is also what the row shows before the count query answers, so
        // there is nothing to wait for: let the query finish and its repaint land first.
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();

        assertNull(countsOn("staging-unstaged-list", "blob.bin"),
                "git reports a dash for a binary file, and a row that says +0 would be "
                        + "claiming it did not change");
    }

    /**
     * A rebuild of the staging panel puts the user back where they were.
     *
     * <p>Staging a hunk, saving, or coming back to the window rebuilds the whole panel. It
     * used to come back unfiltered, with nothing selected and the open file closed.
     */
    @Test
    void aStagingRebuildKeepsTheFilterTheSelectionAndTheOpenFile() throws Exception {
        writeFile("README.md", "changed\n");
        writeFile("docs/guide.md", "changed\n");
        selectGraphRow(0);
        awaitStagingArea();

        TextField filter = lookup(".staging-filter").queryAs(TextField.class);
        interact(() -> filter.setText("README"));
        settle();
        @SuppressWarnings("unchecked")
        javafx.scene.control.ListView<String> before =
                lookup(".staging-unstaged-list").queryAs(javafx.scene.control.ListView.class);
        interact(() -> before.getSelectionModel().select("README.md"));
        RobotWait.untilOnFx("README.md never opened",
                () -> lookup(".diff-viewer").query().isVisible());

        // A refresh now updates the panel in place rather than rebuilding it, so there is
        // no new control to wait for: the refresh is done once its git query has landed.
        interact(() -> tab.getRightPanel().showStagingArea());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals("README", lookup(".staging-filter").queryAs(TextField.class).getText(),
                "the filter survives");
        @SuppressWarnings("unchecked")
        javafx.scene.control.ListView<String> after =
                lookup(".staging-unstaged-list").queryAs(javafx.scene.control.ListView.class);
        assertEquals("README.md", after.getSelectionModel().getSelectedItem(),
                "so does the selected row");
        RobotWait.untilOnFx("the open file was closed by the rebuild",
                () -> lookup(".diff-viewer").query().isVisible());
    }

    /**
     * A stage git refuses says so on the row, with a way to try again.
     *
     * <p>It used to move the row back without a word on it; the reason was a line in
     * the status bar at the other end of the window.
     */
    @Test
    void aFailedStageIsReportedOnItsRowAndCanBeRetried() throws Exception {
        writeFile("README.md", "changed\n");
        selectGraphRow(0);
        awaitStagingArea();

        Path lock = tempDir.resolve(".git/index.lock");
        Files.writeString(lock, "");
        try {
            clickOn("README.md");
            settle();
            clickOn("Stage");
            RobotWait.untilOnFx("the failure never showed on the row",
                    () -> lookup(".row-failed").tryQuery().isPresent());
            assertFalse(service.getStagingState().staged().contains("README.md"),
                    "git refused it, so nothing is staged");
            assertTrue(rowsOf("staging-unstaged-list").contains("README.md"),
                    "and the row is back where it was");
        } finally {
            Files.deleteIfExists(lock);
        }

        javafx.scene.Node retry = lookup(".row-retry").query();
        clickOn(retry);
        RobotWait.until("the retry never staged the file",
                () -> service.getStagingState().staged().contains("README.md"));
        RobotWait.untilOnFx("the failure marker outlived the retry",
                () -> lookup(".row-failed").tryQuery().isEmpty());
    }
}
