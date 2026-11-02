package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.RecentRepositories;
import com.jfx.git.testsupport.LayoutAssert;

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TabPane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launchpad, laid out with the application's stylesheets.
 *
 * <p>Its three action cards were four hundred pixels tall around nine words each, and a
 * repository's full path gave the recent list a horizontal scrollbar.
 */
class WelcomeLayoutTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    private Scene welcome(double width) throws Exception {
        // A path long enough that it cannot fit beside its name at any sensible width.
        Path deep = tempDir.resolve("a-rather-long-folder-name/and-another-one-inside-it/"
                + "then-a-third-for-good-measure/the-repository");
        Files.createDirectories(deep.resolve(".git"));
        RecentRepositories.add(deep.toFile());
        return FxHarness.call(() -> {
            Scene scene = new Scene(new TabPane(new WelcomeTab(new LyraApp())), width, 800);
            ThemeManager.register(scene);
            ThemeManager.apply(ThemeManager.Theme.DARK);
            for (int i = 0; i < 2; i++) {
                scene.getRoot().applyCss();
                scene.getRoot().resize(width, 800);
                scene.getRoot().layout();
            }
            return scene;
        });
    }

    @Test
    void theCardsAreAsTallAsTheirTwoLinesNotTheWindow() throws Exception {
        Scene scene = welcome(1100);
        FxHarness.run(() -> {
            List<Node> cards = LayoutAssert.visibleNodes(scene.getRoot(),
                    n -> n.getStyleClass().contains("welcome-action"));
            assertTrue(cards.size() == 3, "Open, Clone and Create: " + cards.size());
            double first = cards.get(0).getLayoutBounds().getWidth();
            for (Node card : cards) {
                double height = card.getLayoutBounds().getHeight();
                assertTrue(height <= 80, "a card holds an icon and two lines, was " + height + "px tall");
                assertTrue(Math.abs(card.getLayoutBounds().getWidth() - first) <= 2,
                        "the three cards share the row equally");
                assertNotNull(((ButtonBase) card).getAccessibleText());
            }
            ThemeManager.unregister(scene);
        });
    }

    @Test
    void aLongPathIsShortenedNotScrolled() throws Exception {
        Scene scene = welcome(1100);
        FxHarness.run(() -> {
            for (Node node : LayoutAssert.visibleNodes(scene.getRoot(), n -> n instanceof ScrollBar)) {
                ScrollBar bar = (ScrollBar) node;
                assertFalse(bar.getOrientation() == Orientation.HORIZONTAL
                                && bar.getVisibleAmount() < bar.getMax() - bar.getMin(),
                        "the launchpad should never scroll sideways");
            }
            LayoutAssert.assertNoGlyphIcons(scene.getRoot());
            ThemeManager.unregister(scene);
        });
    }
}
