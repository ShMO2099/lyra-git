package com.jfx.git.ui;

import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The File view's minimap: where it shows, what it paints, and that its colours are the
 * editor's own rather than a copy of them.
 *
 * <p>Viewers here sit on a {@link ThemeManager}-registered scene, as the application's
 * own does, because the colour test needs the {@code -jfx-syntax-*} tokens; an
 * unregistered scene resolves them to black on both sides and the comparison would
 * pass for the wrong reason.
 */
class MinimapTest {

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void closeHarnessStages() {
        FxHarness.closeShownStages();
    }

    private static final String WHOLE_FILE = "one\ntwo\nTHREE-NEW\nfour\nfive\nsix\nseven\neight\n";

    private static final String REPLACING_DIFF = """
            @@ -1,4 +1,4 @@
             one
             two
            -three
            +THREE-NEW
             four
            """;

    private static String longFile(int lines) {
        return IntStream.rangeClosed(1, lines).mapToObj(i -> "line " + i)
                .reduce((a, b) -> a + "\n" + b).orElse("") + "\n";
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A viewer on a registered scene, filled by {@code content}, laid out twice. */
    private static DiffViewer viewer(Consumer<DiffViewer> content) {
        return FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.showRegistered(v, 900, 600);
            content.accept(v);
            FxHarness.pulse(v);
            FxHarness.pulse(v);
            stage.setUserData("keep-alive");
            return v;
        });
    }

    /** The viewer in File view, on the file it was given. */
    private static DiffViewer fileView(String path, String content) {
        return viewer(v -> v.showFileContent(path, content));
    }

    private static void toggle(DiffViewer viewer, String label) {
        for (Node node : FxHarness.lookupAll(viewer, "diff-segment")) {
            if (node instanceof ToggleButton button && label.equals(button.getText())) {
                button.fire();
                return;
            }
        }
        throw new AssertionError("no \"" + label + "\" segment");
    }

    private static DiffTextArea areaOf(DiffViewer viewer) {
        return (DiffTextArea) FxHarness.lookupAll(viewer, "code-area").get(0);
    }

    private static Bounds sceneBounds(Node node) {
        return node.localToScene(node.getBoundsInLocal());
    }

    /** Presses at ({@code x}, {@code y}) in {@code on}'s own coordinates. */
    private static void press(Node on, double x, double y) {
        // A synthetic event's x/y are taken as scene coordinates when it is dispatched
        // and re-expressed for each node on the way; hand it scene coordinates.
        javafx.geometry.Point2D scene = on.localToScene(x, y);
        Event.fireEvent(on, new MouseEvent(MouseEvent.MOUSE_PRESSED, scene.getX(), scene.getY(),
                scene.getX(), scene.getY(), MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null));
    }

    // ------------------------------------------------------------------
    // Where it shows
    // ------------------------------------------------------------------

    @Test
    void theMinimapShowsOnlyInFileView() {
        DiffViewer viewer = viewer(v -> v.setFileDiffBytes("src/Main.java", bytes(REPLACING_DIFF), bytes(WHOLE_FILE)));
        FxHarness.run(() -> {
            Minimap minimap = viewer.minimap();
            assertFalse(minimap.isVisible() || minimap.isManaged(), "a unified diff is hunks, not a file");

            toggle(viewer, "Split");
            FxHarness.pulse(viewer);
            assertFalse(minimap.isVisible(), "nor is a split one");

            toggle(viewer, "File view");
            FxHarness.pulse(viewer);
            assertTrue(minimap.isVisible() && minimap.isManaged(), "the whole file: a picture of it beside it");

            viewer.showBlame("src/Main.java",
                    List.of(new com.jfx.git.core.BlameLine("abc12345", "A", "m", java.time.Instant.parse("2026-01-01T12:00:00Z"), "one")));
            FxHarness.pulse(viewer);
            assertFalse(minimap.isVisible(), "blame is its own gutter, not a file");
        });
    }

    @Test
    void theMinimapSitsBetweenTheTextAndTheScrollbar() {
        DiffViewer viewer = fileView("a.txt", longFile(50));
        FxHarness.run(() -> {
            Bounds text = sceneBounds(areaOf(viewer));
            Bounds map = sceneBounds(viewer.minimap());
            Bounds bar = sceneBounds(FxHarness.lookupAll(viewer, "diff-scroll-map").get(0));
            assertTrue(text.getMaxX() <= map.getMinX() + 0.5, "text, then minimap: " + text + " / " + map);
            assertTrue(map.getMaxX() <= bar.getMinX() + 0.5, "minimap, then scrollbar: " + map + " / " + bar);
            assertEquals(Minimap.WIDTH, map.getWidth(), 0.5);
        });
    }

    @Test
    void theMinimapPreferenceHidesItEvenInFileView() {
        DiffViewer viewer = fileView("a.txt", longFile(50));
        com.jfx.git.core.AppPreferences.setEditorMinimap(false);
        try {
            FxHarness.run(() -> {
                viewer.applyPreferences();
                FxHarness.pulse(viewer);
                assertFalse(viewer.minimap().isVisible());
            });
        } finally {
            com.jfx.git.core.AppPreferences.setEditorMinimap(true);
        }
        FxHarness.run(() -> {
            viewer.applyPreferences();
            FxHarness.pulse(viewer);
            assertTrue(viewer.minimap().isVisible(), "and back");
        });
    }

    // ------------------------------------------------------------------
    // Colours: the editor's own
    // ------------------------------------------------------------------

    @Test
    void probeFillsMatchTheFillsTheEditorRendersForTheSameClasses() {
        DiffViewer viewer = fileView("src/Main.java", "public class Main {\n    String s = \"x\";\n}\n");
        FxHarness.run(() -> {
            // The tail's stylesheet lands after the fixture's own pulses and re-applies
            // CSS to the surface; until the next pass its text carries default fills.
            FxHarness.pulse(viewer);
            Text keyword = areaOf(viewer).lookupAll(".text").stream()
                    .filter(n -> n instanceof Text t && t.getStyleClass().contains("tm-storage"))
                    .map(n -> (Text) n)
                    .findFirst().orElseThrow(() -> new AssertionError("no storage-keyword text was rendered"));
            Minimap minimap = viewer.minimap();
            Paint plain = minimap.fillFor(List.of());
            assertEquals(keyword.getFill(), minimap.fillFor(keyword.getStyleClass()),
                    "the probe resolves to exactly the paint the editor used for " + keyword.getText());
            assertNotEquals(plain, minimap.fillFor(keyword.getStyleClass()),
                    "and a keyword is not painted as plain text");
        });
    }

    @Test
    void switchingThemeInvalidatesTheProbeCache() {
        DiffViewer viewer = fileView("a.txt", "plain text\n");
        ThemeManager.Theme before = ThemeManager.current();
        try {
            Paint dark = FxHarness.call(() -> {
                ThemeManager.apply(ThemeManager.Theme.DARK);
                FxHarness.pulse(viewer);
                return viewer.minimap().fillFor(List.of());
            });
            Paint light = FxHarness.call(() -> {
                ThemeManager.apply(ThemeManager.Theme.LIGHT);
                FxHarness.pulse(viewer);
                return viewer.minimap().fillFor(List.of());
            });
            assertNotEquals(dark, light, "a cached dark-theme fill must not survive the switch to light");
        } finally {
            FxHarness.run(() -> ThemeManager.apply(before));
        }
    }

    // ------------------------------------------------------------------
    // Geometry and input
    // ------------------------------------------------------------------

    @Test
    void theSliderCoversTheRowsOnScreen() {
        DiffViewer viewer = fileView("a.txt", longFile(300));
        FxHarness.run(() -> {
            FxHarness.pulse(viewer);
            DiffTextArea area = areaOf(viewer);
            Node slider = viewer.minimap().slider();
            double rowsPerViewport = area.getHeight() / area.rowHeight();
            assertTrue(slider.isVisible(), "300 lines overflow the viewport: there is something to slide");
            assertEquals(rowsPerViewport * Minimap.ROW_PX, slider.getBoundsInLocal().getHeight(), 2,
                    "two minimap pixels per row on screen");
            assertEquals(0, slider.getLayoutY(), 1, "at the top before scrolling");

            area.showParagraphAtTop(100);
            FxHarness.pulse(viewer);
            FxHarness.pulse(viewer);
            assertTrue(slider.getLayoutY() > 20, "and down the map after: " + slider.getLayoutY());
        });
    }

    @Test
    void clickingTheMinimapCentresThatLine() {
        DiffViewer viewer = fileView("a.txt", longFile(300));
        FxHarness.run(() -> {
            Minimap minimap = viewer.minimap();
            double y = minimap.getHeight() / 2;
            int line = (int) Math.round(y / Minimap.ROW_PX);
            press(minimap, Minimap.WIDTH / 2, y);
            FxHarness.pulse(viewer);
            FxHarness.pulse(viewer);
            DiffTextArea area = areaOf(viewer);
            int first = area.firstVisibleParToAllParIndex();
            int last = area.lastVisibleParToAllParIndex();
            assertTrue(first > 0, "the view moved");
            assertTrue(first < line && line < last,
                    "line " + line + " is on screen, between " + first + " and " + last);
        });
    }

    @Test
    void changedRowsAreTintedFromTheSameDataAsTheScrollMap() {
        DiffViewer viewer = viewer(v -> v.setFileDiffBytes("src/Main.java", bytes(REPLACING_DIFF), bytes(WHOLE_FILE)));
        FxHarness.run(() -> {
            toggle(viewer, "File view");
            FxHarness.pulse(viewer);
            assertEquals(Set.of(2), viewer.minimap().changedRows(),
                    "line 3 replaced an older line: the one row the scroll map marks in both lanes");

            viewer.showFileContent("src/Main.java", WHOLE_FILE);
            FxHarness.pulse(viewer);
            assertEquals(Set.of(), viewer.minimap().changedRows(),
                    "content shown on its own has no hunks that describe it");
        });
    }

    @Test
    void aLineOfTextPaintsAndABlankLineDoesNot() {
        DiffViewer viewer = fileView("a.txt", "hello world\n\nagain\n");
        FxHarness.run(() -> {
            FxHarness.pulse(viewer);
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(Color.TRANSPARENT);
            WritableImage image = viewer.minimap().canvas().snapshot(parameters, null);
            PixelReader pixels = image.getPixelReader();
            assertTrue(IntStream.range(0, 11).anyMatch(x -> pixels.getColor(x, 0).getOpacity() > 0),
                    "the first line's characters leave marks on the first minimap row");
            assertTrue(IntStream.range(0, (int) Minimap.WIDTH).allMatch(x -> pixels.getColor(x, 2).getOpacity() == 0),
                    "the blank second line leaves none");
            assertTrue(IntStream.range(0, 5).anyMatch(x -> pixels.getColor(x, 4).getOpacity() > 0),
                    "the third line paints again");
        });
    }
}
