package com.jfx.git.ui;

import javafx.event.Event;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A long list's scrollbar keeps a thumb you can see and grab.
 *
 * <p>JavaFX floors the thumb at 1.5 times the bar's width - 12 pixels under the theme's
 * 8-pixel bars - which is what a graph with a few thousand commits loaded came down to.
 * The list here holds a hundred thousand rows, far past where that floor bites.
 */
class MinThumbScrollBarSkinTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    private record Shown(ListView<String> list, ScrollBar bar, Node thumb) {
    }

    private static Shown show(int rows) {
        return FxHarness.call(() -> {
            ListView<String> list = new ListView<>();
            list.setFixedCellSize(24);
            list.getItems().setAll(IntStream.range(0, rows).mapToObj(i -> "row " + i).toList());
            MinThumbScrollBarSkin.installOn(list);
            Stage stage = FxHarness.showRegistered(list, 400, 600);
            stage.setUserData("keep-alive");
            FxHarness.pulse(list);
            FxHarness.pulse(list);
            ScrollBar bar = list.lookupAll(".scroll-bar").stream()
                    .filter(n -> n instanceof ScrollBar b && b.getOrientation() == Orientation.VERTICAL)
                    .map(n -> (ScrollBar) n).findFirst().orElseThrow();
            Node thumb = bar.lookup(".thumb");
            return new Shown(list, bar, thumb);
        });
    }

    private static void mouse(Node on, javafx.event.EventType<MouseEvent> type, double y) {
        Point2D scene = on.localToScene(on.getLayoutBounds().getWidth() / 2, y);
        Event.fireEvent(on, new MouseEvent(type, scene.getX(), scene.getY(), scene.getX(), scene.getY(),
                MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, false, null));
    }

    @Test
    void aHundredThousandRowsStillLeaveAGrabbableThumb() {
        Shown s = show(100_000);
        FxHarness.run(() -> {
            assertTrue(s.bar().getSkin() instanceof MinThumbScrollBarSkin, "the list's bar wears the skin");
            assertTrue(s.bar().isVisible());
            assertEquals(MinThumbScrollBarSkin.MIN_THUMB, s.thumb().getLayoutBounds().getHeight(), 0.5,
                    "the thumb holds its minimum instead of shrinking to a fleck");
            assertEquals(0, s.thumb().getLayoutY(), 0.5, "at the top before scrolling");
        });
    }

    @Test
    void aShortListKeepsAProportionalThumb() {
        Shown s = show(40);   // 960px of rows in a ~600px list
        FxHarness.run(() -> {
            double thumb = s.thumb().getLayoutBounds().getHeight();
            double track = s.bar().getHeight();
            assertTrue(thumb > track * 0.5 && thumb < track * 0.75,
                    "about the visible share of the list: " + thumb + " of " + track);
        });
    }

    @Test
    void draggingTheThumbToTheBottomReachesTheLastRow() {
        Shown s = show(100_000);
        FxHarness.run(() -> {
            Node thumb = s.thumb();
            mouse(thumb, MouseEvent.MOUSE_PRESSED, 5);
            mouse(thumb, MouseEvent.MOUSE_DRAGGED, 5 + s.bar().getHeight());
            mouse(thumb, MouseEvent.MOUSE_RELEASED, 5 + s.bar().getHeight());
            FxHarness.pulse(s.list());
            FxHarness.pulse(s.list());
            assertEquals(1.0, s.bar().getValue(), 1e-9, "the bar is at its end");
            assertEquals(s.bar().getHeight(),
                    thumb.getLayoutY() + thumb.getLayoutBounds().getHeight(), 0.5,
                    "and the thumb sits flush with the bottom of the track");
        });
    }

    @Test
    void theRepositoryTabsCommitListWearsIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        try (var window = com.jfx.git.testsupport.MainWindowFixture.create(temp)) {
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RobotWait.drainGitWork();
            var bars = FxHarness.call(() -> FxHarness.lookupAll(window.tab().getContent(), "commit-graph")
                    .stream().flatMap(list -> list.lookupAll(".scroll-bar").stream())
                    .filter(n -> n instanceof ScrollBar b && b.getOrientation() == Orientation.VERTICAL)
                    .map(n -> (ScrollBar) n).toList());
            assertTrue(!bars.isEmpty(), "precondition: the commit graph has a vertical bar");
            assertTrue(bars.stream().allMatch(b -> b.getSkin() instanceof MinThumbScrollBarSkin),
                    "the commit graph's vertical bar uses the minimum-thumb skin");
        }
    }

    @Test
    void pressingTheTrackPagesTowardThePointer() {
        Shown s = show(100_000);
        FxHarness.run(() -> {
            Node track = s.bar().lookup(".track");
            mouse(track, MouseEvent.MOUSE_PRESSED, s.bar().getHeight() - 5);
            FxHarness.pulse(s.list());
            assertTrue(s.bar().getValue() > 0, "one page down");
            assertTrue(s.bar().getValue() < 0.01, "a page, not a jump to the end");
        });
    }
}
