package com.jfx.git.ui;

import com.jfx.git.graph.GraphPalette;
import com.jfx.git.graph.RefRail;
import com.jfx.git.testsupport.MainWindowFixture;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The small things that make the window easier to read and to use: empty states with a
 * way forward, shortcuts where commands are, tabs and a title that say what is in them,
 * branch chips in their lane's colour, drop targets that light up, and motion that can be
 * turned off.
 */
class PolishTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @AfterEach
    void tearDown() {
        com.jfx.git.core.AppPreferences.setReduceMotion(false);
        RobotWait.drainGitWork();
        FxHarness.closeShownStages();
    }

    private static boolean visible(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
        }
        return true;
    }

    private static List<Button> buttons(Node root, String text) {
        return FxHarness.lookupAll(root, "button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .map(n -> (Button) n).toList();
    }

    // ------------------------------------------------------------------ the palette

    private static CommandPalette.PaletteItem item(String text) {
        return new CommandPalette.PaletteItem(text, () -> { });
    }

    @Test
    void thePaletteFindsCommandsByTheirLettersInOrder() {
        List<CommandPalette.PaletteItem> items = List.of(item("File: Open repository"), item("Git: Fetch all"),
                item("Git: Push"), item("View: Switch theme"));
        assertEquals("Git: Fetch all", CommandPalette.ranked(items, "fa", List.of()).get(0).displayText());
        assertEquals("Git: Push", CommandPalette.ranked(items, "psh", List.of()).get(0).displayText());
        assertTrue(CommandPalette.ranked(items, "zzz", List.of()).isEmpty());
        assertTrue(CommandPalette.score("git: push", "push") > CommandPalette.score("git: push", "psh"),
                "the letters together beat the letters scattered");
    }

    @Test
    void recentlyUsedCommandsComeFirst() {
        List<CommandPalette.PaletteItem> items = List.of(item("Git: Fetch all"), item("Git: Push"), item("Git: Stash changes"));
        assertEquals("Git: Stash changes",
                CommandPalette.ranked(items, "", List.of("Git: Stash changes")).get(0).displayText());
    }

    @Test
    void tooltipsNameTheirShortcut() {
        assertTrue(RepoToolbar.keys(javafx.scene.input.KeyCode.L, false).matches(" \\(.*L\\)"),
                RepoToolbar.keys(javafx.scene.input.KeyCode.L, false));
    }

    // ------------------------------------------------------------------ colour

    @Test
    void branchChipsTakeTheirLanesColourAndTagsKeepTheirOwn() {
        javafx.scene.paint.Color lane = GraphPalette.lane(3);
        String local = com.jfx.git.graph.GitGraphCell.laneStyle(
                new RefRail.Chip("main", RefRail.Kind.LOCAL, false, false, null), lane);
        assertTrue(local.contains("-fx-border-color: rgba("), local);
        String head = com.jfx.git.graph.GitGraphCell.laneStyle(
                new RefRail.Chip("main", RefRail.Kind.LOCAL, true, false, null), lane);
        assertTrue(head.contains("-fx-background-color: rgba(") && head.contains("1.00)"), head);
        assertEquals("", com.jfx.git.graph.GitGraphCell.laneStyle(
                new RefRail.Chip("v1", RefRail.Kind.TAG, false, false, null), lane));
    }

    // ------------------------------------------------------------------ drag and drop

    @Test
    void aDropTargetLightsUpAndGoesOutAgain() {
        FxHarness.run(() -> {
            Label chip = new Label("main");
            RefDropMenu.markTarget(chip, true);
            assertTrue(chip.getPseudoClassStates().stream().anyMatch(p -> p.getPseudoClassName().equals("drop-target")));
            RefDropMenu.markTarget(chip, false);
            assertTrue(chip.getPseudoClassStates().stream().noneMatch(p -> p.getPseudoClassName().equals("drop-target")));
        });
    }

    // ------------------------------------------------------------------ motion

    @Test
    void reduceMotionMeansNoFades() {
        com.jfx.git.core.AppPreferences.setReduceMotion(true);
        FxHarness.run(() -> {
            Region node = new Region();
            node.setOpacity(0.3);
            Motion.fadeIn(node);
            assertEquals(1.0, node.getOpacity(), 0.001, "shown at once");
            boolean[] ran = {false};
            Motion.fadeOut(node, () -> ran[0] = true);
            assertTrue(ran[0], "and gone at once");
        });
    }

    // ------------------------------------------------------------------ the window

    @Test
    void theTabSaysWhatIsWaitingAndTheTitleNamesRepositoryAndBranch() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RepoTab tab = window.tab();
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("the badge never appeared", () -> tab.getGraphic() != null);
            FxHarness.run(() -> {
                Node badge = tab.getGraphic();
                assertFalse(FxHarness.lookupAll(badge, "tab-dirty").isEmpty(), "uncommitted changes are marked");
                Label sync = (Label) FxHarness.lookupAll(badge, "tab-sync").get(0);
                assertEquals("↑1 ↓2", sync.getText(), "one to push, two to pull");
                assertEquals("demo — main", tab.windowTitleProperty().get());
            });
        }
    }

    @Test
    void emptyStatesOfferTheWayForward() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RepoTab tab = window.tab();
            FxHarness.run(() -> {
                // Filter mode: a search otherwise dims what it does not match, in place.
                tab.filterModeToggleForTest().setSelected(true);
                tab.searchField().setText("no commit says this");
            });
            RobotWait.untilOnFx("the search never filtered", () -> tab.getCommitList().getItems().isEmpty()
                    || tab.getCommitList().getItems().stream().allMatch(r -> r.isSynthetic()));
            FxHarness.run(() -> {
                // The repository is dirty, so its WIP row stays and the list is never empty:
                // the way back is beside the count rather than in the empty message.
                Button clear = FxHarness.lookupAll(tab.getContent(), "search-clear").stream()
                        .map(n -> (Button) n).findFirst().orElseThrow();
                assertTrue(visible(clear), "a search is on: here is the way back");
                clear.fire();
                assertEquals("", tab.searchField().getText());
                assertFalse(clear.isVisible(), "and it goes with the search");
            });
        }
    }

    @Test
    void aRepositoryWithNoRemoteOffersToAddOne() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            Files.writeString(tempDir.resolve("a.txt"), "a");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").setAuthor("T", "t@example.com").setCommitter("T", "t@example.com")
                    .setSign(false).call();
        }
        com.jfx.git.core.GitService service = new com.jfx.git.core.GitService();
        service.openRepository(tempDir.toFile());
        RepoTab tab = FxHarness.call(() -> {
            RepoTab t = new RepoTab(new com.jfx.git.LyraApp(), "plain", service);
            FxHarness.show(new javafx.scene.control.TabPane(t), 1180, 690);
            t.refresh();
            return t;
        });
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("no way to add a remote was offered", () ->
                buttons(tab.getContent(), "Add a remote…").stream().anyMatch(Node::isVisible));
        FxHarness.run(tab::close);
    }

    @Test
    void aProviderListWithNoAccountHasAConnectButton() {
        FxHarness.run(() -> {
            Node prompt = com.jfx.git.ui.dialogs.IntegrationDialog.connectPrompt("GitHub");
            assertFalse(buttons(prompt, "Connect GitHub…").isEmpty());
        });
    }
}
