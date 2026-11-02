package com.jfx.git.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Starts a JavaFX toolkit once for the whole test run and hands work to its thread.
 *
 * <p>Layout is the thing under test in the render tests, and layout only happens on a
 * shown stage, so these tests need a real toolkit rather than a mock. The stage is
 * placed off-screen and never focused so a test run does not steal the desktop.
 */
public final class FxHarness {

    private static boolean started;

    private FxHarness() {
    }

    public static synchronized void start() {
        if (started) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.startup(latch::countDown);
        } catch (IllegalStateException alreadyRunning) {
            // TestFX starts the toolkit for its own tests, and both kinds run in one JVM.
            // Whoever gets there first wins; the second only needs to know it is up.
            Platform.setImplicitExit(false);
            applyTheTheme();
            started = true;
            return;
        }
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX toolkit did not start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        Platform.setImplicitExit(false);
        applyTheTheme();
        started = true;
    }

    /**
     * Gives test scenes the same user-agent stylesheet the application runs with.
     *
     * <p>The {@code -color-*} tokens every stylesheet here is written against - and that
     * {@code diff-styles.css} uses nineteen of - come from AtlantaFX's Primer theme, which
     * reaches scenes through {@link javafx.application.Application#setUserAgentStylesheet}
     * rather than through any file this project owns. {@code LyraApp} sets it at startup,
     * so every real window and dialog resolves them; the harness never did, so every test
     * scene styled against tokens that were not there. JavaFX says so, once per rule per
     * scene - "Could not resolve '-color-fg-default'", then a {@code ClassCastException}
     * turning the unresolved name into a Paint - which is most of the noise in a test run.
     *
     * <p>Worth doing for more than quiet: a robot test rendering without the application's
     * own theme is measuring a widget nobody ships.
     */
    private static void applyTheTheme() {
        try {
            // On the FX thread: setUserAgentStylesheet is a toolkit call, and start() runs
            // on the test thread. Waited for, so the first scene a test builds already has
            // it - a scene styled after the fact keeps whatever it resolved at build time.
            CountDownLatch applied = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    ThemeManager.apply(ThemeManager.saved());
                } finally {
                    applied.countDown();
                }
            });
            if (!applied.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("theming the test toolkit timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable headlessOrMissing) {
            // Styling is not what any of these tests assert on; running unstyled is worse
            // than running styled, and better than not running.
            System.getLogger(FxHarness.class.getName())
                    .log(System.Logger.Level.DEBUG, "Could not theme the test toolkit", headlessOrMissing);
        }
    }

    /** Runs {@code work} on the FX thread and returns its result. */
    public static <T> T call(Supplier<T> work) {
        start();
        FutureTask<T> task = new FutureTask<>(work::get);
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
        try {
            return task.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e.getCause() == null ? e : e.getCause());
        }
    }

    public static void run(Runnable work) {
        call(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Every stage {@link #show} has opened that is still showing.
     *
     * <p>Tracked here rather than by each test, because leaving them open is not a local
     * cost. A showing stage keeps taking part in focus and layout for the rest of the JVM,
     * and the bill arrives somewhere unrelated: CherryPickRobotTest failing from three
     * classes away, a sidebar dialog that "never opened" because a stale off-screen window
     * held focus. Nine test classes were leaking before this existed, one of them
     * twenty-four stages.
     *
     * <p>Only while they show: a stage the test hid itself is dropped, and comes back if
     * it is shown again. Holding hidden ones until the next {@code closeShownStages} kept
     * each with its whole scene - 298 MB from one class that hid its own and never asked.
     */
    private static final List<Stage> shownStages = new ArrayList<>();

    /** Shows {@code root} on an off-screen stage and lays it out. */
    public static Stage show(Parent root, double width, double height) {
        return show(root, width, height, false);
    }

    /**
     * {@link #show}, with the scene registered with {@link ThemeManager} <em>before</em>
     * its first CSS pass, as the application's own scene is - so the theme's
     * {@code -jfx-*} tokens resolve for every node from the start. Registering after
     * the first pass leaves styles computed without them cached on nodes already shown.
     */
    public static Stage showRegistered(Parent root, double width, double height) {
        return show(root, width, height, true);
    }

    private static Stage show(Parent root, double width, double height, boolean registered) {
        Stage stage = new Stage();
        Scene scene = new Scene(root, width, height);
        if (registered) {
            ThemeManager.register(scene);
        }
        stage.setScene(scene);
        stage.setX(-4000);
        stage.setY(-4000);
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_SHOWN, e -> track(stage, true));
        stage.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> track(stage, false));
        stage.show();
        pulse(root);
        return stage;
    }

    private static void track(Stage stage, boolean showing) {
        synchronized (shownStages) {
            shownStages.remove(stage);
            if (showing) {
                shownStages.add(stage);
            }
        }
    }

    /**
     * Closes every stage {@link #show} opened.
     *
     * <p>Safe to call from any thread and from a test that opened none. Call it from an
     * {@code @AfterEach}: a test that shows a window owns closing it, and the harness can
     * only offer the mechanism, not the discipline.
     */
    public static void closeShownStages() {
        run(() -> {
            List<Stage> copy;
            synchronized (shownStages) {
                copy = new ArrayList<>(shownStages);
                shownStages.clear();
            }
            copy.forEach(Stage::hide);
        });
    }

    /** Forces a CSS pass and a layout pass, which a hidden test stage will not do on its own. */
    public static void pulse(Parent root) {
        root.applyCss();
        root.layout();
    }

    /** Every descendant carrying {@code styleClass}, in scene-graph order. */
    public static List<Node> lookupAll(Node root, String styleClass) {
        List<Node> found = new ArrayList<>();
        collect(root, styleClass, found);
        return found;
    }

    /**
     * Every text field belonging to a dialog, and none belonging to the application.
     *
     * <p>Robot tests reach a dialog's field with a scene-wide {@code lookup(".text-field")},
     * which also finds the sidebar's branch filters and the commit panel's summary box -
     * all showing, just not where the test is looking. Picking the dialog's own field by
     * index, or by excluding one known prompt, therefore only worked while window ordering
     * happened to cooperate, which is why several of these tests passed alone and failed in
     * suite order. Asking which window a field is in removes the ordering from the question.
     *
     * <p>Four test classes had grown their own copy of this before it moved here.
     *
     * @param mainWindow the application's own window, whose fields are never wanted
     */
    public static List<Node> dialogFields(javafx.stage.Window mainWindow) {
        List<Node> fields = new ArrayList<>();
        for (javafx.stage.Window window : new ArrayList<>(javafx.stage.Window.getWindows())) {
            if (window == mainWindow || !window.isShowing() || window.getScene() == null) {
                continue;
            }
            collectByClass(window.getScene().getRoot(), "text-field", fields);
        }
        return fields;
    }

    /**
     * The {@code index}-th field of {@link #dialogFields}, failing loudly when there are
     * fewer than that - typing into whatever else the scene happened to offer is how this
     * class of bug hid in the first place.
     */
    public static Node dialogField(javafx.stage.Window mainWindow, int index) {
        List<Node> fields = dialogFields(mainWindow);
        if (index >= fields.size()) {
            throw new AssertionError("dialog has " + fields.size()
                    + " text field(s), wanted #" + index);
        }
        return fields.get(index);
    }

    /** Like {@link #lookupAll} but adding into a caller's list, and without re-wrapping. */
    private static void collectByClass(Node root, String styleClass, List<Node> into) {
        collect(root, styleClass, into);
    }

    private static void collect(Node node, String styleClass, List<Node> into) {
        if (node.getStyleClass().contains(styleClass)) {
            into.add(node);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, styleClass, into);
            }
        }
    }
}
