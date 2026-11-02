package com.jfx.git.ui;

import com.jfx.git.testsupport.LayoutAssert;
import com.jfx.git.testsupport.MainWindowFixture;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The working-tree panel and its commit box, as laid out.
 *
 * <p>Each test is one thing the panel used to get wrong: a switch stranded on a row of
 * its own, a line of the last commit's message loose under the checkboxes, a counter
 * measuring an empty box, every action outlined, and a fixed width that took two fifths
 * of a laptop's window.
 */
@Tag("heavy")
class RightPanelLayoutTest {

    @TempDir
    Path tempDir;

    private static Parent panel(MainWindowFixture window) {
        Node header = window.scene().lookup(".mode-header");
        assertNotNull(header, "the details panel should be showing the working tree");
        return header.getParent();
    }

    @Test
    void theAllFilesSwitchSitsInTheHeaderBesideThePanelsName() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1480, 900, ThemeManager.Theme.DARK, false);
            window.layOut();
            FxHarness.run(() -> {
                Node allFiles = window.scene().lookup(".all-files-toggle");
                Node title = window.scene().lookup(".mode-header-title");
                assertNotNull(allFiles, "the switch to the repository's files");
                LayoutAssert.assertSameRow(title, allFiles);
            });
        }
    }

    @Test
    void theAmendLineAppearsOnlyWhileAmendingAndSaysWhatItIs() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1480, 900, ThemeManager.Theme.DARK, false);
            RobotWait.untilOnFx("the composer never read HEAD's message",
                    () -> ((Label) window.scene().lookup(".amend-target")).getText().contains("Log payments"));
            FxHarness.run(() -> {
                Label target = (Label) window.scene().lookup(".amend-target");
                assertFalse(target.isManaged() || target.isVisible(),
                        "not amending, so there is nothing to say about the last commit");

                amendBox(window).fire();
                assertTrue(target.isManaged() && target.isVisible());
                assertTrue(target.getText().startsWith("Amending: "),
                        "the line says what it is: " + target.getText());

                amendBox(window).fire();
                assertFalse(target.isManaged(), "and goes again when Amend is unticked");
            });
        }
    }

    @Test
    void theCounterCountsDownWhatIsLeftOfTheSummary() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1480, 900, ThemeManager.Theme.DARK, false);
            window.layOut();
            FxHarness.run(() -> {
                Label counter = (Label) window.scene().lookup(".composer-counter");
                TextField summary = (TextField) window.scene().lookup(".composer-summary");
                assertEquals("72", counter.getText(), "an empty summary has all of it left");
                LayoutAssert.assertSameRow(summary, counter);

                summary.setText("x".repeat(45));
                assertTrue(counter.isVisible());
                assertEquals("27", counter.getText());
                assertFalse(counter.getStyleClass().contains("composer-counter-warn"));

                summary.setText("x".repeat(60));
                assertTrue(counter.getStyleClass().contains("composer-counter-warn"),
                        "past the target: a caution");

                summary.setText("x".repeat(80));
                assertTrue(counter.getStyleClass().contains("composer-counter-over"),
                        "past what most tools show: a warning");
                summary.setText("");
            });
        }
    }

    /** One filled button: Commit. Everything else in the panel is quieter than it. */
    @Test
    void commitIsTheOnlyFilledButton() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1480, 900, ThemeManager.Theme.DARK, false);
            window.layOut();
            FxHarness.run(() -> {
                assertEquals(1, LayoutAssert.countFilledButtons(panel(window)),
                        "the commit box should have exactly one action that reads as the action");
                LayoutAssert.assertNoGlyphIcons(panel(window));
            });
        }
    }

    @ParameterizedTest(name = "{0}px wide")
    @ValueSource(ints = {1100, 1480})
    void thePanelsTakeAShareOfTheWindowWithinLimits(int width) throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(width, 900, ThemeManager.Theme.DARK, false);
            window.layOut();
            FxHarness.run(() -> {
                double detail = ((javafx.scene.layout.Region) panel(window)).getWidth();
                assertEquals(RepoTab.defaultDetailWidth(width), detail, 6,
                        "the details panel should start at its share of the window");
                assertTrue(detail >= 354 && detail <= 486, "and within its limits: " + detail);
            });
        }
    }

    @Test
    void theDefaultsGiveANarrowWindowsGraphMoreRoom() {
        assertEquals(360, RepoTab.defaultDetailWidth(1100), 0.01, "the floor, not 430");
        assertEquals(210, RepoTab.defaultSidebarWidth(1100), 0.01, "the floor, not 250");
        assertEquals(480, RepoTab.defaultDetailWidth(1920), 0.01, "the ceiling on a wide window");
        assertEquals(280, RepoTab.defaultSidebarWidth(1920), 0.01);
    }

    private static CheckBox amendBox(MainWindowFixture window) {
        for (Node node : window.scene().getRoot().lookupAll(".check-box")) {
            if (node instanceof CheckBox box && "Amend".equals(box.getText())) {
                return box;
            }
        }
        throw new AssertionError("no Amend checkbox");
    }
}
