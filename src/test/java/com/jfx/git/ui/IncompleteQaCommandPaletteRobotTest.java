package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.Settings;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Covers execution and recent-first ordering in the actual palette stage. */
class IncompleteQaCommandPaletteRobotTest extends ApplicationTest {
    @BeforeAll static void toolkitAndTheme() { FxHarness.start(); }
    @TempDir Path temporary;
    private LyraApp app;
    private CommandPalette palette;
    private ThemeManager.Theme originalTheme;

    @Override
    public void start(Stage stage) {
        Settings.useFile(temporary.resolve("palette.properties"));
        app = new LyraApp();
        TabPane tabs = new TabPane();
        try {
            Field field = LyraApp.class.getDeclaredField("tabPane");
            field.setAccessible(true);
            field.set(app, tabs);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not initialize the empty application tab context", failure);
        }
        originalTheme = ThemeManager.current();
        stage.setScene(new Scene(new StackPane(tabs), 600, 400));
        stage.show();
        clearRecents();
    }

    @AfterEach
    void cleanup() {
        RobotWait.drainGitWork();
        interact(() -> ThemeManager.apply(originalTheme));
        interact(() -> {
            clearRecents();
            new ArrayList<>(Window.getWindows()).forEach(Window::hide);
        });
        Settings.flush();
        Settings.useFile(null);
    }

    @Test
    void executingACommandMakesItTheFirstRecentEntryAndFuzzySearchFiltersTheList() {
        Platform.runLater(() -> {
            palette = new CommandPalette(app);
            palette.show();
        });
        RobotWait.untilOnFx("palette did not open", () -> palette != null && palette.isShowing());
        TextField search = lookup(".palette-search").queryAs(TextField.class);
        ListView<?> firstResults = lookup(".list-dark").queryAs(ListView.class);
        RobotWait.untilOnFx("initial commands were not rendered", () -> !firstResults.getItems().isEmpty());
        assertEquals("File: Open repository",
                ((CommandPalette.PaletteItem) firstResults.getItems().get(0)).displayText(),
                "with no recent commands, the palette keeps its normal command order");

        interact(() -> search.setText("view"));
        RobotWait.untilOnFx("normal matching order did not update", () -> !firstResults.getItems().isEmpty());
        assertEquals("View: Settings",
                ((CommandPalette.PaletteItem) firstResults.getItems().get(0)).displayText(),
                "a matching query ranks by the normal inventory order before a command is used");

        ThemeManager.Theme before = FxHarness.call(ThemeManager::current);

        interact(() -> {
            search.setText("switch theme");
            EventUtil.fireKey(search, KeyCode.ENTER);
        });
        RobotWait.untilOnFx("the selected palette action did not execute", () -> !palette.isShowing());
        assertNotEquals(before, FxHarness.call(ThemeManager::current), "enter should run the selected command");

        Platform.runLater(() -> {
            palette = new CommandPalette(app);
            palette.show();
        });
        RobotWait.untilOnFx("second palette did not open", () -> palette != null && palette.isShowing());
        ListView<?> secondResults = lookup(".list-dark").queryAs(ListView.class);
        RobotWait.untilOnFx("recent command was not rendered", () -> !secondResults.getItems().isEmpty());
        assertEquals("View: Switch theme", ((CommandPalette.PaletteItem) secondResults.getItems().get(0)).displayText());

        TextField secondSearch = lookup(".palette-search").queryAs(TextField.class);
        interact(() -> secondSearch.setText("swt"));
        RobotWait.untilOnFx("fuzzy results did not update", () -> secondResults.getItems().stream()
                .map(item -> ((CommandPalette.PaletteItem) item).displayText()).toList()
                .equals(List.of("View: Switch theme")));
        assertEquals(List.of("View: Switch theme"), FxHarness.call(() -> secondResults.getItems().stream()
                .map(item -> ((CommandPalette.PaletteItem) item).displayText()).toList()),
                "noncontiguous letters find the global command without an open repository");
    }

    private static void clearRecents() {
        try {
            Field field = CommandPalette.class.getDeclaredField("RECENT");
            field.setAccessible(true);
            ((ArrayDeque<?>) field.get(null)).clear();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not isolate palette recency", e);
        }
    }

    @Test
    void aNoncontiguousQueryFiltersTheGlobalCommandsWithoutAnOpenRepository() {
        Platform.runLater(() -> {
            palette = new CommandPalette(app);
            palette.show();
        });
        RobotWait.untilOnFx("palette did not open", () -> palette != null && palette.isShowing());
        TextField search = lookup(".palette-search").queryAs(TextField.class);
        ListView<?> results = lookup(".list-dark").queryAs(ListView.class);
        interact(() -> search.setText("swt"));
        RobotWait.untilOnFx("fuzzy query did not select the global theme command", () ->
                results.getItems().stream().map(item -> ((CommandPalette.PaletteItem) item).displayText())
                        .toList().equals(List.of("View: Switch theme")));
    }

    /** Fire the same key event the search field's handler receives, without Glass key-code lookup. */
    private static final class EventUtil {
        private static void fireKey(javafx.scene.Node target, KeyCode code) {
            javafx.event.Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED,
                    "", "", code, false, false, false, false));
        }
    }
}
