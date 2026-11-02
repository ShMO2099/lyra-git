package com.jfx.git.ui;

import com.jfx.git.testsupport.LayoutAssert;
import com.jfx.git.testsupport.MainWindowFixture;

import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Labeled;
import javafx.scene.control.Separator;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository toolbar at the widths people actually have.
 *
 * <p>At 1100 pixels - a 1080p laptop at 175% - the bar used to show "..." where Pull and
 * Push were, an empty branch box, stray dashes between its groups and a chevron hiding
 * the rest. Each of those is a property of the laid-out bar, so each is stated here
 * against the styled window rather than argued from the stylesheet.
 */
@Tag("heavy")
class ToolbarLayoutTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "{0}px wide")
    @ValueSource(ints = {1100, 1480})
    void everyCaptionIsReadableAndNothingOverflows(int width) throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(width, 900, ThemeManager.Theme.DARK, false);
            window.layOut();
            FxHarness.run(() -> {
                RepoToolbar bar = window.tab().getToolbar();
                assertFalse(bar.isCompact(), "there is room for captions at " + width + "px");
                assertNoOverflowMenu(bar);

                List<String> problems = new ArrayList<>();
                for (ButtonBase button : toolButtons(bar)) {
                    if (!button.isVisible()) continue;
                    String caption = RepoToolbar.captionOf(button);
                    if (!button.getText().startsWith(caption)) {
                        problems.add(caption + " shows \"" + button.getText() + "\"");
                    }
                    javafx.scene.text.Text shown = (javafx.scene.text.Text) button.lookup(".text");
                    if (shown != null && !button.getText().equals(shown.getText())) {
                        problems.add(caption + " is cut off as \"" + shown.getText() + "\"");
                    }
                }
                LayoutAssert.failWith("toolbar captions", problems);
            });
        }
    }

    /** The counts are on screen: the fixture's branch is one ahead and two behind. */
    @Test
    void theButtonsSayHowMuchWorkIsWaiting() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1480, 900, ThemeManager.Theme.DARK, false);
            FxHarness.run(() -> {
                RepoToolbar bar = window.tab().getToolbar();
                assertEquals("Pull  ↓2", bar.toolButton("Pull").getText());
                assertEquals("Push  ↑1", bar.toolButton("Push").getText());
                assertEquals(ContentDisplay.TOP, bar.toolButton("Pull").getContentDisplay(),
                        "a count in a text that is never displayed is no count");
                assertEquals("Pull ↓2", bar.toolButton("Pull").getAccessibleText());
            });
        }
    }

    /**
     * When there is not room for the captions, they all go, and every action stays.
     *
     * <p>The alternative the bar used to take - shrink each label until it reads "..." and
     * push the rest behind a chevron - leaves a row of buttons nobody can tell apart.
     */
    @Test
    void aNarrowBarDropsItsCaptionsTogetherAndKeepsEveryButton() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(560, 700, ThemeManager.Theme.DARK, false);
            window.layOut();
            FxHarness.run(() -> {
                RepoToolbar bar = window.tab().getToolbar();
                assertTrue(bar.isCompact(), "560px cannot hold the captions");
                assertNoOverflowMenu(bar);
                for (ButtonBase button : toolButtons(bar)) {
                    assertFalse(button.getText().contains("…") || button.getText().contains("..."),
                            RepoToolbar.captionOf(button) + " must not show an ellipsis");
                    assertTrue(button.getAccessibleText().startsWith(RepoToolbar.captionOf(button)),
                            "an icon-only button still says what it is");
                }
                // The count is the reason to press the button, so it outlives the caption.
                assertEquals("↓2", bar.toolButton("Pull").getText());
                assertEquals(ContentDisplay.GRAPHIC_ONLY, bar.toolButton("Fetch").getContentDisplay());
            });

            window.resize(1480, 900);
            window.layOut();
            FxHarness.run(() -> assertFalse(window.tab().getToolbar().isCompact(),
                    "the captions come back when there is room again"));
        }
    }

    @Test
    void theBranchSwitcherLeadsTheBarAndShowsTheWholeName() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1100, 900, ThemeManager.Theme.DARK, false);
            FxHarness.run(() -> {
                RepoToolbar bar = window.tab().getToolbar();
                Node first = bar.getItems().get(0);
                assertTrue(first instanceof ComboBox, "the branch you are on comes first: " + first);
                ComboBox<?> switcher = (ComboBox<?>) first;
                assertEquals("main", switcher.getValue());
                for (Node node : switcher.lookupAll(".list-cell")) {
                    if (node instanceof Labeled cell && cell.getText() != null) {
                        LayoutAssert.assertNotTruncated(cell);
                    }
                }
            });
        }
    }

    /**
     * Groups are divided by a hairline, not by a {@link Separator}: in a horizontal
     * toolbar a Separator keeps its own horizontal orientation and draws as a dash.
     */
    @Test
    void groupsAreDividedByHairlinesAndButtonsShareOneHeight() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1480, 900, ThemeManager.Theme.DARK, false);
            window.layOut();
            FxHarness.run(() -> {
                RepoToolbar bar = window.tab().getToolbar();
                assertTrue(bar.getItems().stream().noneMatch(item -> item instanceof Separator),
                        "no Separator in the bar");
                long dividers = bar.getItems().stream()
                        .filter(item -> item.getStyleClass().contains("toolbar-divider")).count();
                assertTrue(dividers >= 3, "the groups are still divided: " + dividers);

                TreeSet<Long> heights = new TreeSet<>();
                for (ButtonBase button : toolButtons(bar)) {
                    if (button.isVisible()) heights.add(Math.round(button.getHeight()));
                }
                assertTrue(heights.last() - heights.first() <= 1,
                        "plain and split buttons should be one height, were " + heights);
            });
        }
    }

    private static List<ButtonBase> toolButtons(RepoToolbar bar) {
        List<ButtonBase> buttons = new ArrayList<>();
        for (Node item : bar.getItems()) {
            if (item instanceof ButtonBase button && RepoToolbar.captionOf(button) != null) {
                buttons.add(button);
            }
        }
        assertFalse(buttons.isEmpty(), "the bar should have buttons");
        return buttons;
    }

    /** The chevron ToolBar shows when its items do not fit. */
    private static void assertNoOverflowMenu(RepoToolbar bar) {
        for (Node node : bar.lookupAll(".tool-bar-overflow-button")) {
            assertFalse(node.isVisible(), "items were pushed into the overflow menu");
        }
    }
}
