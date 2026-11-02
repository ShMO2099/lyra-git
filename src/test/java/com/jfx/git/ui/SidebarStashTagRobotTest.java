package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.RepositoryCommands;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The STASHES and TAGS sections' own actions - pop, apply, drop, create, checkout, delete -
 * driven from the sidebar.
 *
 * <p>A stash row's text is git's own default - {@code "stash@{0}: WIP on <branch>:
 * <shorthash> <subject>"} - there is no custom-message API to give a stash a distinguishing
 * name, so every row in these tests is found by its real, exact string from {@code
 * GitService.getStashEntries()} rather than an assumed label. Pop/apply/drop act on the index
 * captured fresh in each cell's own {@code updateItem} at menu-open time, not one carried
 * across refreshes - {@link #poppingActsOnTheRowClickedNotJustTheNewestStash()} exists
 * specifically to prove a click on the older of two rows acts on that one, not on {@code
 * stash@{0}} by default.
 *
 * <h2>Reaching context menus under Monocle Headless</h2>
 * As elsewhere this session, a robot right-click does not become a {@code ContextMenuEvent}
 * under Monocle Headless. The event is fired directly on the row, which is what {@code
 * ListCell.setContextMenu} itself listens for.
 */
class SidebarStashTagRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;
    /** The application window, so a dialog lookup can exclude everything it contains. */
    private Stage mainWindow;

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
        commit("a.txt", "original a", "add a");

        tab = new RepoTab(new LyraApp(), "test", service);
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        mainWindow = stage;
        interact(() -> tab.refresh());
        settle();
    }

    @AfterEach
    void tidyUp() {
        // A click that starts git work returns before the work does; wait for it, so the
        // backend is not closed - and @TempDir not deleted - under a running command.
        RobotWait.drainGitWork();
        if (tab != null) {
            interact(() -> tab.close());
        } else if (service != null) {
            service.close();
        }
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
        // Global AppPreferences state, not scoped to this test's throwaway repository -
        // left set, it would leak into whichever test runs next in this class.
        com.jfx.git.core.AppPreferences.setGraphHiddenBranches(null);
        com.jfx.git.core.AppPreferences.setGraphSoloBranch(null);
        com.jfx.git.core.AppPreferences.setHiddenStashes(null);
    }

    private void commit(String file, String content, String message) throws Exception {
        Files.writeString(tempDir.resolve(file), content, StandardCharsets.UTF_8);
        assertTrue(service.stageFile(file).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private void writeFile(String file, String content) throws Exception {
        Files.writeString(tempDir.resolve(file), content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                    // Best-effort cleanup; a leftover handle on Windows is not this test's problem.
                }
            });
        }
    }

    /** A working-tree file's content, for a condition; null while it cannot be read. */
    private String contentOf(String file) {
        try {
            return Files.readString(tempDir.resolve(file));
        } catch (java.io.IOException notYet) {
            return null;
        }
    }

    /** Where {@code ref} points in the repository at {@code repo}; throws while it does not resolve. */
    private static String hashIn(Path repo, String ref) {
        try (Git git = Git.open(repo.toFile())) {
            return git.getRepository().resolve(ref).getName();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Whether the repository at {@code repo} has {@code ref}; throws, rather than answering, if unreadable. */
    private static boolean hasRef(Path repo, String ref) {
        try (Git git = Git.open(repo.toFile())) {
            return git.getRepository().getRefDatabase().exactRef(ref) != null;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
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
     * Refreshes, and waits for everything the refresh queued on the git lane, then for its
     * results to be applied on the FX thread. The reload resizes every section when it
     * lands, so a header clicked before then can move out from under the click.
     */
    private void refreshAndWait() {
        interact(() -> tab.refresh());
        RobotWait.drainGitWork();
        WaitForAsyncUtils.waitForFxEvents();
    }

    /**
     * Expands the named section by clicking its {@code TitledPane} directly.
     *
     * <p>Not a text lookup: TAGS carries its title as an explicit {@code Label} inside a
     * custom graphic (so its own {@code getText()} is empty), while STASHES uses the
     * pane's own {@code setText(String)} instead - and TestFX's text matching does not
     * reliably reach a {@code TitledPane}'s own internally-rendered title the way it
     * reaches an explicit {@code Label}. Checking both of the pane's own possible
     * sources for its title works uniformly across either shape.
     */
    private void expandSection(String title) {
        // STASHES/SUBMODULES/WORKTREES carry a live count appended to the pane's own
        // text (e.g. "STASHES   1"), unlike LOCAL/REMOTE/TAGS, which use a separate
        // badge Label inside a custom graphic instead - startsWith tolerates either.
        Node header = FxHarness.lookupAll(tab.getContent(), "titled-pane").stream()
                .filter(n -> n instanceof javafx.scene.control.TitledPane tp
                        && (tp.getText() != null && tp.getText().startsWith(title)
                                || graphicSaysText(tp, title)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no \"" + title + "\" section found"));
        clickOn(header);
        settle();
    }

    private boolean graphicSaysText(javafx.scene.control.TitledPane pane, String title) {
        if (!(pane.getGraphic() instanceof javafx.scene.Parent graphic)) {
            return false;
        }
        return graphic.getChildrenUnmodifiable().stream()
                .anyMatch(c -> c instanceof javafx.scene.control.Label l && title.equals(l.getText()));
    }

    /** Opens the context menu on the row showing {@code label}, without a real right-click. */
    /** What a stash row displays now: its selector plus git's shortened message. */
    private String stashCellText(int index) {
        var entry = service.getStashEntries().get(index);
        return entry.selector() + "  " + entry.shortDescription();
    }

    private void openMenuOn(String label) {
        // Waited for: the rows come from a refresh that runs off the FX thread.
        Node[] row = new Node[1];
        RobotWait.untilOnFx("no row labelled \"" + label + "\" appeared", () -> {
            row[0] = lookup(label).query();
            return row[0] != null;
        });
        interact(() -> Event.fireEvent(row[0], new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
    }

    /**
     * Waits for the row's own menu to offer {@code item}. Hide and Show rebuild the rows
     * through a refresh off the FX thread, and until that lands the row still carries the
     * menu it was drawn with - the one offering the opposite.
     */
    private void untilRowOffers(String label, String item) {
        RobotWait.untilOnFx("the \"" + label + "\" row never offered \"" + item + "\"", () -> {
            Node node = lookup(label).query();
            while (!(node instanceof javafx.scene.control.Control control
                    && control.getContextMenu() != null)) {
                node = node.getParent();
            }
            return ((javafx.scene.control.Control) node).getContextMenu().getItems().stream()
                    .anyMatch(i -> item.equals(i.getText()));
        });
    }

    private void closeStrayContextMenus() {
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).stream()
                .filter(Window::isShowing)
                .filter(w -> w instanceof javafx.scene.control.ContextMenu)
                .forEach(Window::hide));
        settle();
    }

    private List<String> tagNames() {
        return service.getTags().stream().map(r -> r.shortName()).toList();
    }

    /**
     * The node with {@code id} in whichever dialog is showing. By id, not by prompt text
     * or position: the commit panel's own summary field is a ".text-field" too, and a
     * placeholder is an example that may change.
     */
    private Node dialogNodeWithId(String id) {
        return FxHarness.call(() -> {
            for (javafx.stage.Window window : new java.util.ArrayList<>(javafx.stage.Window.getWindows())) {
                if (window == mainWindow || !window.isShowing() || window.getScene() == null) continue;
                Node found = window.getScene().getRoot().lookup("#" + id);
                if (found != null) return found;
            }
            throw new AssertionError("no dialog shows #" + id);
        });
    }

    /** @see FxHarness#dialogFields(javafx.stage.Window) */
    private java.util.Set<Node> dialogFields() {
        return new java.util.LinkedHashSet<>(FxHarness.dialogFields(mainWindow));
    }

    private void openNewTagDialog() {
        expandSection("TAGS");
        Node tagsTree = lookup(".sidebar-list").query();
        interact(() -> Event.fireEvent(tagsTree, new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED, 10, 10, 100, 100, false, null)));
        settle();
        clickOn("New tag on HEAD…");
        RobotWait.untilDialogWithButton("OK");
        closeStrayContextMenus();
    }

    // ------------------------------------------------------------------
    // Stashes
    // ------------------------------------------------------------------

    @Test
    void poppingAStashAppliesItAndRemovesItFromTheRealStashList() throws Exception {
        writeFile("a.txt", "changed a");
        assertTrue(new RepositoryCommands(service).stash().isOk());
        assertEquals(1, service.getStashEntries().size());
        assertEquals("original a", Files.readString(tempDir.resolve("a.txt")),
                "stashing should have restored the working tree to HEAD's content");
        // The stash row now shows selector + message (the point of the change); the
        // menu opens on it either way.

        refreshAndWait();
        expandSection("STASHES");

        openMenuOn(stashCellText(0));
        clickOn("Pop (apply and drop)");
        // The drop is the last thing a pop does, after the change is back in the tree.
        RobotWait.until("the stash was never popped", () -> service.getStashEntries().isEmpty());

        assertEquals("changed a", Files.readString(tempDir.resolve("a.txt")),
                "popping should have re-applied the stashed change to the real working tree");
        assertTrue(service.getStashEntries().isEmpty(),
                "popping removes the stash - it should be gone from the real stash list");
    }

    @Test
    void applyingAStashKeepsItInTheRealStashList() throws Exception {
        writeFile("a.txt", "changed a");
        assertTrue(new RepositoryCommands(service).stash().isOk());
        refreshAndWait();
        expandSection("STASHES");

        openMenuOn(stashCellText(0));
        clickOn("Apply (keep stash)");
        RobotWait.until("the stash was never applied", () -> "changed a".equals(contentOf("a.txt")));

        assertEquals("changed a", Files.readString(tempDir.resolve("a.txt")),
                "applying should have re-applied the stashed change to the real working tree");
        assertEquals(1, service.getStashEntries().size(),
                "apply, unlike pop, must leave the stash sitting in the real stash list");
    }

    @Test
    void droppingAStashRemovesItWithoutApplyingIt() throws Exception {
        writeFile("a.txt", "changed a");
        assertTrue(new RepositoryCommands(service).stash().isOk());
        refreshAndWait();
        expandSection("STASHES");

        openMenuOn(stashCellText(0));
        // Dropping discards the only copy of that work, so it asks first - here and on
        // the graph's identical menu, which is why the label carries an ellipsis.
        clickOn("Drop…");
        RobotWait.untilDialogWithButton("OK");
        clickOn("Drop stash");
        RobotWait.until("the stash was never dropped", () -> service.getStashEntries().isEmpty());

        assertEquals("original a", Files.readString(tempDir.resolve("a.txt")),
                "dropping must not touch the working tree - the change should stay gone");
        assertTrue(service.getStashEntries().isEmpty(),
                "the real stash list should no longer carry it");
    }

    /**
     * R6-3. The confirmation is only worth adding if declining it protects the stash.
     *
     * <p>The graph's stash menu has always asked before dropping; the sidebar's fired
     * straight away, so which of the two lists a user happened to right-click decided
     * whether a discard-only-copy action asked first.
     */
    @Test
    void cancellingTheDropConfirmationKeepsTheStash() throws Exception {
        writeFile("a.txt", "changed a");
        assertTrue(new RepositoryCommands(service).stash().isOk());
        refreshAndWait();
        expandSection("STASHES");

        openMenuOn(stashCellText(0));
        clickOn("Drop…");
        RobotWait.untilDialogWithButton("Cancel");
        clickOn("Cancel");
        // Nothing to wait for when nothing should happen; let any work that would have
        // run finish, then check it did not.
        RobotWait.drainGitWork();

        assertEquals(1, service.getStashEntries().size(),
                "declining the confirmation must leave the stash exactly where it was");
    }

    @Test
    void poppingActsOnTheRowClickedNotJustTheNewestStash() throws Exception {
        writeFile("a.txt", "first change");
        assertTrue(new RepositoryCommands(service).stash().isOk());
        writeFile("a.txt", "second change");
        assertTrue(new RepositoryCommands(service).stash().isOk());
        assertEquals(2, service.getStashEntries().size());
        // Newest first: stash@{0} is "second change", stash@{1} is "first change".

        refreshAndWait();
        expandSection("STASHES");

        // Pop the older one specifically, by clicking its own row - not index 0.
        openMenuOn(stashCellText(1));
        clickOn("Pop (apply and drop)");
        RobotWait.until("the stash was never popped", () -> service.getStashEntries().size() == 1);

        assertEquals("first change", Files.readString(tempDir.resolve("a.txt")),
                "the row that was actually clicked should be the one popped, not stash@{0}");
        assertEquals(1, service.getStashEntries().size());
        assertTrue("stash@{0}".equals(service.getStashEntries().get(0).selector()));
    }

    // ------------------------------------------------------------------
    // Tags
    // ------------------------------------------------------------------

    /**
     * This used to fail now and then as part of the full class, and was put down to the
     * environment. The likelier cause, now removed: the dialog was given a fixed 300ms to
     * open, and the assertion ran a fixed 300ms after OK, while createTag was still on
     * the git lane. It now waits for the dialog, and for the tag.
     */
    @Test
    void creatingATagOnHeadMakesARealTag() throws Exception {
        openNewTagDialog();

        Node field = dialogNodeWithId(com.jfx.git.ui.dialogs.NewTagDialog.NAME_ID);
        clickOn(field);
        interact(() -> ((javafx.scene.control.TextInputControl) field).setText("v1"));
        settle();
        clickOn("Create tag");
        RobotWait.until("tag v1 was never created", () -> tagNames().contains("v1"));

        assertTrue(tagNames().contains("v1"), "a real tag named v1 should now exist: " + tagNames());
    }

    /**
     * The Message field is optional and, when filled, must produce a genuinely
     * annotated tag object - not a lightweight one that merely happens to have a
     * message recorded somewhere else - proven by peeling the ref and parsing what
     * it actually points at, the same check {@code GitServiceTest} uses at the
     * backend level.
     */
    @Test
    void creatingATagOnHeadWithAMessageMakesARealAnnotatedTagObject() throws Exception {
        openNewTagDialog();

        Node nameField = dialogNodeWithId(com.jfx.git.ui.dialogs.NewTagDialog.NAME_ID);
        clickOn(nameField);
        interact(() -> ((javafx.scene.control.TextInputControl) nameField).setText("v1"));
        Node messageField = dialogNodeWithId(com.jfx.git.ui.dialogs.NewTagDialog.MESSAGE_ID);
        clickOn(messageField);
        interact(() -> ((javafx.scene.control.TextInputControl) messageField).setText("release notes"));
        settle();
        // The button says which kind of tag it makes.
        clickOn("Create annotated tag");
        RobotWait.until("tag v1 was never created", () -> tagNames().contains("v1"));

        assertTrue(tagNames().contains("v1"), "a real tag named v1 should now exist: " + tagNames());
        try (Git git = Git.open(tempDir.toFile())) {
            var ref = git.getRepository().getRefDatabase().exactRef("refs/tags/v1");
            assertNotNull(ref, "refs/tags/v1 should exist");
            try (var walk = new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
                var tag = walk.parseTag(ref.getObjectId());
                assertEquals("release notes", tag.getFullMessage().trim());
            }
        }
    }

    @Test
    void checkingOutATagDetachesHeadAtItsRealCommit() throws Exception {
        assertTrue(service.createTag("v1", null, "HEAD").isOk());
        commit("b.txt", "b", "add b");
        refreshAndWait();
        expandSection("TAGS");

        openMenuOn("v1");
        clickOn("Checkout tag");
        RobotWait.until("the tag was never checked out",
                () -> !Files.exists(tempDir.resolve("b.txt")));

        assertFalse(Files.exists(tempDir.resolve("b.txt")),
                "checking out v1 should move the real working tree back before add b");
        assertEquals("original a", Files.readString(tempDir.resolve("a.txt")));
    }

    @Test
    void deletingATagRemovesItForReal() throws Exception {
        assertTrue(service.createTag("v1", null, "HEAD").isOk());
        refreshAndWait();
        expandSection("TAGS");

        openMenuOn("v1");
        clickOn("Delete tag");
        RobotWait.until("tag v1 was never deleted", () -> !tagNames().contains("v1"));

        assertFalse(tagNames().contains("v1"), "v1 should be gone from the real tag list: " + tagNames());
    }

    @Test
    void pushingATagFromItsContextMenuPublishesItToTheRealRemote() throws Exception {
        assertTrue(service.createTag("v1", null, "HEAD").isOk());
        Path remoteDir = Files.createTempDirectory("lyra-sidebar-push-tag");
        try {
            Git.init().setDirectory(remoteDir.toFile()).setBare(true).call().close();
            assertTrue(service.addRemote("origin", remoteDir.toString()).isOk());

            refreshAndWait();
            expandSection("TAGS");

            openMenuOn("v1");
            clickOn("Push tag");
            RobotWait.until("the tag never reached the remote",
                    () -> hasRef(remoteDir, "refs/tags/v1"));

            try (Git bare = Git.open(remoteDir.toFile())) {
                assertNotNull(bare.getRepository().getRefDatabase().exactRef("refs/tags/v1"),
                        "the real remote should now carry this tag");
            }
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    /**
     * Formerly order-dependent; the notes below are kept in case it comes back. The root
     * cause was never proven.
     *
     * <p><b>Status (28 September 2026):</b> the reproduction below no longer fails. Two
     * consecutive runs of {@code NativeGitBackendTest} followed by this class in one JVM
     * both passed, as did a full-suite run. One of the suspects listed further down -
     * native {@code git} processes outliving their test - was closed in between:
     * {@code NativeGitRunner} now destroys a command's whole process tree on cancel,
     * timeout and interrupt, where it used to kill only {@code git.exe} and never touch
     * an interrupted one. That is consistent with the fix but does not prove it.
     *
     * <h3>Symptom</h3>
     * The test timed out at what was then a {@code WaitForAsyncUtils.waitFor(60, SECONDS,
     * ...)} poll with "branch from-tag was never created" - i.e. it fails at the
     * assertion that GitService.getLocalBranches() ever contains "from-tag", not at any
     * of the UI-driving steps before it (field lookup, typing, OK click all succeed).
     *
     * <p><b>Update:</b> that poll is now {@code RobotWait.until} (30s), the dialog is
     * waited for rather than given a fixed 300ms, and every test in this class drains the
     * git lane in teardown, so no earlier test's command can still be running on that
     * shared, single-threaded lane when this one queues its own. Whether any of that was
     * the cause is unproven: if this still times out, the notes below still stand.
     *
     * <h3>How to reproduce</h3>
     * <pre>
     * mvn -o test -Dtest=NativeGitBackendTest,SidebarStashTagRobotTest
     * </pre>
     * It reproducibly FAILS - not intermittently, every single time - when {@code
     * NativeGitBackendTest} runs anywhere earlier in the same Surefire JVM fork.
     * Failing runs consistently take ~75.3x seconds to time out (i.e. the elapsed time
     * is suspiciously consistent across repeated failing runs, not randomly
     * distributed the way GC-pause-style flakiness usually looks).
     * <p><b>Update (26 August):</b> the earlier claim that this test "passes reliably
     * when run alone" does not fully hold - it was observed to fail with this exact
     * symptom (~75.45s timeout, same assertion) running as the sole test class in its
     * own {@code mvn -Dtest=SidebarStashTagRobotTest} invocation, with no {@code
     * NativeGitBackendTest} anywhere in that JVM. It had passed reliably alone across
     * several earlier repeated runs before that, so {@code NativeGitBackendTest}
     * running first is still a real, highly-reproducible trigger - just evidently not
     * the only one. Whatever the underlying cause is, it is not exclusively a
     * cross-class interaction.
     *
     * <h3>What has been tried and did NOT fix it</h3>
     * <ul>
     *   <li>Raising the poll timeout 5s -&gt; 20s -&gt; 60s. Made no
     *       difference, which is the key clue: this points at the branch-creation
     *       delivery never arriving in the failing combination at all, not merely
     *       arriving late. A longer timeout only wastes CI time here.</li>
     * </ul>
     *
     * <h3>What is already ruled out / known context</h3>
     * <ul>
     *   <li>Not a defect in the feature itself: the exact same "New branch from here…"
     *       on a tag is independently proven correct at the backend level by
     *       {@code GitServiceTest} and {@code NativeGitBackendTest}'s own equivalent
     *       tests (no UI, no GitTaskRunner involved) - whatever is wrong is specific
     *       to this test's own async delivery path, not the feature it exercises.</li>
     *   <li>Suspected mechanism (not confirmed): {@code NativeGitBackendTest} shells
     *       out to real OS {@code git} subprocesses. Something about that appears to
     *       interact badly with delivery back to the FX thread via
     *       {@code GitTaskRunner} in a later test class in the same fork - e.g. a
     *       shared executor, a leaked thread, JGit's memory-mapped pack-file handles
     *       being held open, or something OS-process-related left
     *       behind by the native git calls. None of these have been confirmed - they
     *       are just the plausible places to start looking.</li>
     *   <li>A related, likely-connected case: {@code SidebarBranchRobotTest.
     *       renamingABranchRenamesItForReal} fails only as part of its own full test
     *       class (passes alone) - a different symptom shape but the same
     *       "GitTaskRunner-delivered write silently never lands, only when this test
     *       runs after certain other tests" family. That one's assertions ran a fixed
     *       400ms after OK; it now waits for the rename.
     *       Investigate both together; a shared root cause is plausible since both
     *       involve a background-thread git write that provably completes at the
     *       backend level but fails to surface in the UI-facing assertion.</li>
     *   <li>Package-alphabetical Surefire ordering means {@code NativeGitBackendTest}
     *       (under {@code com.jfx.git.core}) runs before {@code com.jfx.git.ui.*} by
     *       default in an unfiltered {@code mvn test}, so this is not just a
     *       hand-picked pairing - it plausibly affects full-suite reliability more
     *       broadly than this one test.</li>
     * </ul>
     *
     * <h3>Suggested next steps</h3>
     * <ul>
     *   <li>Add logging/instrumentation inside {@code GitTaskRunner} (task submitted,
     *       task started, task completed, result posted to FX thread) and run the
     *       failing combination to see whether the {@code createBranch} task is ever
     *       submitted at all, hangs mid-execution, or completes but its result never
     *       reaches {@code Platform.runLater}.</li>
     *   <li>Try forcing a fresh JVM fork per test class ({@code
     *       reuseForks=false}/{@code forkCount} in the surefire-plugin config) for just
     *       these two classes to confirm the fork-sharing theory directly - if the
     *       failure disappears, that confirms cross-test-class JVM state leakage as the
     *       cause and narrows the search to what {@code NativeGitBackendTest} leaves
     *       behind (thread pools, open file handles, environment/working-directory
     *       state, JGit's global repository cache, etc).</li>
     *   <li>Check for orphaned native {@code git} subprocesses or file handles still
     *       open after {@code NativeGitBackendTest} completes (Windows file locking has
     *       already bitten this codebase once around JGit's memory-mapped pack files -
     *       worth checking whether a similar lock is held past that test class's own
     *       teardown).</li>
     *   <li>See `PARITY_PLAN.md` Wave 6 item #8 for the full narrative of what was
     *       already tried during the original investigation.</li>
     * </ul>
     */
    @Test
    void newBranchFromHereOnATagStartsAtThatTagsCommitNotHead() throws Exception {
        assertTrue(service.createTag("v1", null, "HEAD").isOk());
        String tagCommit;
        try (Git git = Git.open(tempDir.toFile())) {
            tagCommit = git.getRepository().resolve("v1").getName();
        }
        // Give HEAD a commit the tag does not have, so starting from the tag is a real,
        // checkable difference from starting at HEAD.
        commit("b.txt", "b", "add b past the tag");
        refreshAndWait();
        expandSection("TAGS");

        openMenuOn("v1");
        clickOn("New branch from here…");
        RobotWait.untilDialogWithButton("OK");
        closeStrayContextMenus();

        // The prompt's field is the only text field in the dialog window, which is a
        // question that does not depend on window ordering. See dialogFields().
        Node field = dialogFields().stream().findFirst()
                .orElseThrow(() -> new AssertionError("new-branch field not found"));
        clickOn(field);
        interact(() -> ((javafx.scene.control.TextInputControl) field).setText("from-tag"));
        settle();
        clickOn("Create and check out");
        // Confirming kicks off createBranch on GitTaskRunner's background thread, a
        // second async round trip past the dialog closing itself - poll for the real
        // outcome rather than guessing a fixed delay. RobotWait, not WaitForAsyncUtils:
        // that one failed the whole poll the first time a read of the repository
        // mid-write threw, where this treats it as "not yet".
        RobotWait.until("branch from-tag was never created", () ->
                service.getLocalBranches().stream().anyMatch(r -> "from-tag".equals(r.shortName())));

        try (Git git = Git.open(tempDir.toFile())) {
            assertEquals(tagCommit, git.getRepository().resolve("from-tag").getName(),
                    "should start at the tag's commit, not HEAD");
        }
    }

    /**
     * {@code deleteRemoteTag} pushes an empty source refspec: it must remove the tag
     * from the remote while leaving the identically-named local tag alone, unlike a
     * plain "Delete tag" - so this only counts as proven if BOTH sides are checked
     * against a real bare remote, through the real confirmation {@code Alert}.
     */
    @Test
    void deletingATagFromRemoteRemovesItFromTheRemoteButKeepsItLocally() throws Exception {
        assertTrue(service.createTag("v1", null, "HEAD").isOk());
        Path remoteDir = Files.createTempDirectory("lyra-sidebar-delete-remote-tag");
        try {
            Git.init().setDirectory(remoteDir.toFile()).setBare(true).call().close();
            assertTrue(service.addRemote("origin", remoteDir.toString()).isOk());
            assertTrue(new RepositoryCommands(service).push(null, false, false, true).isOk());
            // pushTag: "push with tags" follows annotated tags only, and v1 is lightweight.
            assertTrue(service.pushTag("origin", "v1").isOk());
            try (Git bare = Git.open(remoteDir.toFile())) {
                assertNotNull(bare.getRepository().getRefDatabase().exactRef("refs/tags/v1"));
            }

            refreshAndWait();
            expandSection("TAGS");

            openMenuOn("v1");
            clickOn("Delete tag from remote…");
            RobotWait.untilDialogWithButton("OK");
            clickOn("Delete from origin");
            // hasRef throws, rather than answering false, when the remote cannot be read,
            // so an unreadable repository cannot pass for a deleted tag.
            RobotWait.until("the tag was never deleted from the remote",
                    () -> !hasRef(remoteDir, "refs/tags/v1"));

            try (Git bare = Git.open(remoteDir.toFile())) {
                assertNull(bare.getRepository().getRefDatabase().exactRef("refs/tags/v1"),
                        "the remote's copy should be gone");
            }
            assertTrue(tagNames().contains("v1"), "the local tag should be untouched: " + tagNames());
        } finally {
            deleteRecursively(remoteDir);
        }
    }

    // ------------------------------------------------------------------
    // Move to HEAD ("Fast-forward when possible, else delete+recreate")
    // ------------------------------------------------------------------

    @Test
    void movingATagToHeadWhenItIsAFastForwardMovesItThereWithOneConfirmation() throws Exception {
        assertTrue(service.createTag("v1", null, "HEAD").isOk());
        commit("b.txt", "b", "advance past the tag");
        String newHead;
        try (Git git = Git.open(tempDir.toFile())) {
            newHead = git.getRepository().resolve("HEAD").getName();
        }
        refreshAndWait();
        expandSection("TAGS");

        openMenuOn("v1");
        clickOn("Move to HEAD…");
        RobotWait.untilDialogWithButton("OK");
        clickOn("Move tag");
        // A move is a delete and a recreate; hashIn throws while v1 is briefly gone.
        RobotWait.until("the tag never moved to HEAD", () -> newHead.equals(hashIn(tempDir, "v1")));

        try (Git git = Git.open(tempDir.toFile())) {
            assertEquals(newHead, git.getRepository().resolve("v1").getName(),
                    "the tag should now point at the real new HEAD");
        }
    }

    @Test
    void movingATagAlreadyAtHeadShowsAnInformationalMessageAndChangesNothing() throws Exception {
        assertTrue(service.createTag("v1", null, "HEAD").isOk());
        String originalTarget;
        try (Git git = Git.open(tempDir.toFile())) {
            originalTarget = git.getRepository().resolve("v1").getName();
        }
        refreshAndWait();
        expandSection("TAGS");

        openMenuOn("v1");
        clickOn("Move to HEAD…");
        // Informational, not a confirmation: said in the status bar, with no dialog to dismiss.
        RobotWait.untilOnFx("the status bar never said the tag is already at HEAD",
                () -> lookup(".status-message").tryQuery()
                        .map(n -> String.valueOf(((javafx.scene.control.Labeled) n).getText()))
                        .orElse("").contains("v1 already points at HEAD"));
        interact(() -> assertTrue(Window.getWindows().stream()
                        .noneMatch(w -> w.isShowing() && w.getScene() != null
                                && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane),
                "a notice asks nothing, so no dialog opens"));
        RobotWait.drainGitWork();

        try (Git git = Git.open(tempDir.toFile())) {
            assertEquals(originalTarget, git.getRepository().resolve("v1").getName(),
                    "already sitting on HEAD - nothing should have changed");
        }
    }

    @Test
    void movingATagBackwardWarnsThatItIsNotAFastForwardBeforeMovingIt() throws Exception {
        // Diverge two branches from the same starting commit so the tag's own commit and
        // HEAD end up genuinely unrelated - neither an ancestor nor a descendant of the
        // other - the real "not a fast-forward" case, not merely "moving backward".
        assertTrue(service.createBranch("side", null, false).isOk());
        commit("a.txt", "further on main", "advance main past the branch point");
        assertTrue(service.createTag("v1", null, "HEAD").isOk());
        String taggedCommit;
        try (Git git = Git.open(tempDir.toFile())) {
            taggedCommit = git.getRepository().resolve("v1").getName();
        }
        assertTrue(service.checkoutBranch("side").isOk());
        commit("side.txt", "side", "diverge on side");

        refreshAndWait();
        expandSection("TAGS");

        openMenuOn("v1");
        clickOn("Move to HEAD…");
        RobotWait.untilDialogWithButton("OK");
        closeStrayContextMenus();

        // Read the Alert's own content text directly rather than guessing a CSS
        // selector - the same "find the showing DialogPane window" idiom
        // CommitPanelRobotTest already established.
        String contentText = FxHarness.call(() -> javafx.stage.Window.getWindows().stream()
                .filter(w -> w.isShowing()
                        && w.getScene() != null
                        && w.getScene().getRoot() instanceof javafx.scene.control.DialogPane)
                .map(w -> ((javafx.scene.control.DialogPane) w.getScene().getRoot()).getContentText())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no dialog showing")));
        assertTrue(contentText.contains("not a fast-forward"),
                "expected the non-fast-forward warning text, got: " + contentText);
        clickOn("Move tag");
        RobotWait.until("the tag never moved", () -> !taggedCommit.equals(hashIn(tempDir, "v1")));

        try (Git git = Git.open(tempDir.toFile())) {
            assertNotEquals(taggedCommit, git.getRepository().resolve("v1").getName(),
                    "the tag should have moved even though it was not a fast-forward");
        }
    }

    // ------------------------------------------------------------------
    // Double-click to jump to commit
    // ------------------------------------------------------------------

    /**
     * Tags cannot be checked out directly the way a branch can (double-clicking a
     * branch checks it out - see {@code SidebarBranchRobotTest.
     * doubleClickingABranchChecksItOutForReal}), so a plain jump-to-commit is the
     * more correct double-click action for a tag row (Wave 6 item #16).
     */
    @Test
    void doubleClickingATagSelectsItsCommitInTheGraphNotHead() throws Exception {
        assertTrue(service.createTag("v1", null, "HEAD").isOk());
        String taggedCommit;
        try (Git git = Git.open(tempDir.toFile())) {
            taggedCommit = git.getRepository().resolve("v1").getName();
        }
        // Give HEAD a commit the tag does not have, so landing on the tag's own commit
        // is a real, checkable difference from wherever HEAD happens to be.
        commit("b.txt", "b", "advance past the tag");
        refreshAndWait();
        expandSection("TAGS");

        // Not doubleClickOn(): TestFX's own double-click sends two separate synthetic
        // clicks, and Monocle Headless does not reliably fold them into one clickCount
        // == 2 event the way a real double-click would (see SidebarBranchRobotTest.
        // doubleClickingABranchChecksItOutForReal for the same finding) - the cell's
        // own handler checks getClickCount() == 2 on a single MOUSE_CLICKED, so that is
        // dispatched directly.
        Node[] found = new Node[1];
        RobotWait.untilOnFx("no v1 row appeared", () -> {
            found[0] = lookup("v1").query();
            return found[0] != null;
        });
        Node row = found[0];
        interact(() -> Event.fireEvent(row, new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_CLICKED, 10, 10, 100, 100,
                javafx.scene.input.MouseButton.PRIMARY, 2,
                false, false, false, false, true, false, false, true, false, true, null)));
        settle();

        var selected = tab.graphList().getSelectionModel().getSelectedItem();
        assertNotNull(selected, "double-clicking the tag should select a row");
        assertNotNull(selected.getCommit(), "should land on a real commit, not a synthetic row");
        assertEquals(taggedCommit, selected.getCommit().getHash(),
                "should select the tag's own commit, not whatever HEAD currently is");
    }

    // ------------------------------------------------------------------
    // Hide & Solo (Wave 5's extension beyond LOCAL branches, which
    // SidebarBranchRobotTest already covers - GraphVisibility and
    // AppPreferences were never branch-specific, only the UI wiring was)
    // ------------------------------------------------------------------

    @Test
    void hidingThenShowingATagAgainClearsTheHiddenSet() throws Exception {
        assertTrue(service.createTag("v1", null, "HEAD").isOk());
        refreshAndWait();
        expandSection("TAGS");

        openMenuOn("v1");
        clickOn("Hide from graph");
        untilRowOffers("v1", "Show in graph");
        assertTrue(com.jfx.git.core.AppPreferences.graphHiddenBranches().contains("refs/tags/v1"),
                "the hidden set remembers this tag by its full ref name");
        openMenuOn("v1");
        clickOn("Show in graph");
        settle();

        assertTrue(com.jfx.git.core.AppPreferences.graphHiddenBranches().isEmpty(),
                "toggling the same tag again should clear the hidden set");
    }

    @Test
    void soloingATagStoresItByFullRefName() throws Exception {
        assertTrue(service.createTag("v1", null, "HEAD").isOk());
        refreshAndWait();
        expandSection("TAGS");

        openMenuOn("v1");
        clickOn("Solo (show only this tag)");
        settle();

        assertEquals("refs/tags/v1", com.jfx.git.core.AppPreferences.graphSoloBranch());
    }

    @Test
    void hidingThenShowingAStashAgainClearsTheHiddenSet() throws Exception {
        writeFile("a.txt", "changed a");
        assertTrue(new RepositoryCommands(service).stash().isOk());
        refreshAndWait();
        expandSection("STASHES");

        String hash = service.getStashEntries().get(0).hash();
        openMenuOn(stashCellText(0));
        clickOn("Hide from graph");
        untilRowOffers(stashCellText(0), "Show in graph");
        assertTrue(com.jfx.git.core.AppPreferences.hiddenStashes().contains(hash),
                "the hidden set remembers this stash by its commit hash, not its shifting index");
        openMenuOn(stashCellText(0));
        clickOn("Show in graph");
        settle();

        assertTrue(com.jfx.git.core.AppPreferences.hiddenStashes().isEmpty(),
                "toggling the same stash again should clear the hidden set");
    }
}
