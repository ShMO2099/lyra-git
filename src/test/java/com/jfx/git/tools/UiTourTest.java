package com.jfx.git.tools;

import com.jfx.git.LyraApp;
import com.jfx.git.core.RecentRepositories;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.testsupport.MainWindowFixture;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RobotWait;
import com.jfx.git.ui.ThemeManager;
import com.jfx.git.ui.WelcomeTab;
import com.jfx.git.ui.settings.SettingsTab;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Screenshots of the main screens, for looking at - not an assertion.
 *
 * <p>Renders the workspace, a commit's details, a diff in unified and split layout,
 * Preferences and the welcome page, at each width in {@code tour.widths} and in both
 * themes, into {@code target/ui-tour/<tour.label>/}. Running it once as {@code before}
 * and again as {@code after} writes {@code target/ui-tour/index.html} with the two
 * side by side, which is how a styling change is reviewed.
 *
 * <pre>
 * mvnw test -Dtest=UiTourTest -DexcludedTestGroups= -Dtour.label=before
 * </pre>
 * Options: {@code -Dtour.widths=1100,1480}, {@code -Dtour.height=900},
 * {@code -Dtour.scale=1.75} (the output resolution, as on a 175% display), and
 * {@code -Dtour.themes=dark_green,paper} to photograph just those themes (by constant
 * name or id; the default is every built-in theme).
 *
 * <p>Tagged {@code tour} and excluded from ordinary runs: it measures nothing.
 */
@Tag("tour")
class UiTourTest {

    @TempDir
    Path tempDir;

    private final String label = System.getProperty("tour.label", "latest");
    private final double scale = Double.parseDouble(System.getProperty("tour.scale", "1.75"));
    private final double height = Double.parseDouble(System.getProperty("tour.height", "900"));
    private final Path out = Path.of("target", "ui-tour", label);

    @Test
    void tour() throws Exception {
        Files.createDirectories(out);
        List<Integer> widths = Stream.of(System.getProperty("tour.widths", "1100,1480").split(","))
                .map(String::strip).map(Integer::parseInt).toList();
        for (ThemeManager.Theme theme : themes()) {
            for (int width : widths) {
                try (MainWindowFixture window = MainWindowFixture.create(tempDir.resolve(theme + "-" + width))) {
                    window.open(width, height, theme, false);
                    mainWindow(window, width, theme);
                    RecentRepositories.add(window.workTree().toFile());
                    welcome(width, theme);
                }
            }
            settings(theme);
        }
        writeIndex();
    }

    /** Every built-in theme, or the ones {@code tour.themes} names. */
    private static List<ThemeManager.Theme> themes() {
        String wanted = System.getProperty("tour.themes", "").strip();
        if (wanted.isEmpty()) {
            return List.of(ThemeManager.Theme.values());
        }
        return Stream.of(wanted.split(",")).map(String::strip).map(name -> {
            ThemeManager.Theme theme = ThemeManager.Theme.fromName(name);
            if (theme == null) {
                throw new IllegalArgumentException("tour.themes: no theme called " + name);
            }
            return theme;
        }).toList();
    }

    private void mainWindow(MainWindowFixture window, int width, ThemeManager.Theme theme) throws Exception {
        String suffix = "-" + width + "-" + name(theme);

        selectRow(window, row -> row.getCommit() == null && !row.isStash());
        snap(window.scene(), "01-workspace" + suffix);

        selectRow(window, row -> row.getCommit() != null && row.getCommit().getSubject().startsWith("Round totals"));
        snap(window.scene(), "02-commit-detail" + suffix);

        selectRow(window, row -> row.getCommit() == null && !row.isStash());
        FxHarness.run(() -> {
            ListView<?> unstaged = (ListView<?>) window.scene().lookup(".staging-unstaged-list");
            for (int i = 0; i < unstaged.getItems().size(); i++) {
                if (String.valueOf(unstaged.getItems().get(i)).endsWith("PaymentService.java")) {
                    unstaged.getSelectionModel().select(i);
                }
            }
        });
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the diff never opened",
                () -> window.scene().lookup(".diff-viewer") != null && window.scene().lookup(".diff-viewer").isVisible());
        window.layOut();
        snap(window.scene(), "03-diff-unified" + suffix);

        FxHarness.run(() -> toggle(window.scene(), "Split"));
        window.layOut();
        snap(window.scene(), "04-diff-split" + suffix);
        FxHarness.run(() -> toggle(window.scene(), "Unified"));
    }

    private void welcome(int width, ThemeManager.Theme theme) throws Exception {
        Scene scene = FxHarness.call(() -> {
            Scene s = new Scene(new TabPane(new WelcomeTab(new LyraApp())), width, height);
            ThemeManager.register(s);
            ThemeManager.apply(theme);
            s.getRoot().applyCss();
            s.getRoot().layout();
            return s;
        });
        snap(scene, "06-welcome-" + width + "-" + name(theme));
        FxHarness.run(() -> ThemeManager.unregister(scene));
    }

    /** The Settings tab on its first section, at the window's size, as the tab strip shows it. */
    private void settings(ThemeManager.Theme theme) throws Exception {
        SettingsTab tab = FxHarness.call(SettingsTab::new);
        Scene scene = FxHarness.call(() -> {
            Scene s = new Scene(new TabPane(tab), 1280, height);
            ThemeManager.register(s);
            ThemeManager.apply(theme);
            s.getRoot().applyCss();
            s.getRoot().layout();
            return s;
        });
        snap(scene, "05-settings-" + name(theme));
        FxHarness.run(() -> {
            ThemeManager.unregister(scene);
            tab.dispose();
        });
    }

    // ------------------------------------------------------------------

    private static void selectRow(MainWindowFixture window, java.util.function.Predicate<GitGraphRow> which) {
        FxHarness.run(() -> {
            ListView<GitGraphRow> list = window.tab().getCommitList();
            for (int i = 0; i < list.getItems().size(); i++) {
                if (which.test(list.getItems().get(i))) {
                    list.getSelectionModel().clearSelection();
                    list.getSelectionModel().select(i);
                    return;
                }
            }
            throw new AssertionError("no graph row matched");
        });
        RobotWait.drainGitWork();
        window.layOut();
    }

    private static void toggle(Scene scene, String text) {
        for (Node node : scene.getRoot().lookupAll(".toggle-button")) {
            if (node instanceof ToggleButton button && text.equals(button.getText()) && button.isVisible()) {
                button.fire();
                return;
            }
        }
    }

    private void snap(Scene scene, String name) throws Exception {
        File file = out.resolve(name + ".png").toFile();
        BufferedImage image = FxHarness.call(() -> {
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setTransform(Transform.scale(scale, scale));
            WritableImage shot = scene.getRoot().snapshot(parameters, null);
            int w = (int) shot.getWidth();
            int h = (int) shot.getHeight();
            BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            var pixels = shot.getPixelReader();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    buffered.setRGB(x, y, pixels.getArgb(x, y));
                }
            }
            return buffered;
        });
        ImageIO.write(image, "png", file);
    }

    /** A page showing every label's shots, one column per label, before first. */
    private void writeIndex() throws Exception {
        Path root = out.getParent();
        List<String> labels = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted((a, b) ->
                    a.equals("before") ? -1 : b.equals("before") ? 1 : a.compareTo(b)).forEach(labels::add);
        }
        List<String> shots;
        try (Stream<Path> files = Files.list(out)) {
            shots = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".png")).sorted().toList();
        }
        StringBuilder html = new StringBuilder("<!doctype html><meta charset=utf-8><title>UI tour</title>"
                + "<style>body{font:13px system-ui;background:#888;margin:16px}table{border-spacing:8px}"
                + "img{width:100%;border:1px solid #444}td{vertical-align:top;width:" + (100 / labels.size())
                + "%}</style><table><tr>");
        for (String l : labels) html.append("<th>").append(l).append("</th>");
        html.append("</tr>");
        for (String shot : shots) {
            html.append("<tr><td colspan=").append(labels.size()).append("><b>").append(shot).append("</b></td></tr><tr>");
            for (String l : labels) {
                html.append("<td><img src=\"").append(l).append('/').append(shot).append("\"></td>");
            }
            html.append("</tr>");
        }
        html.append("</table>");
        Files.writeString(root.resolve("index.html"), html, StandardCharsets.UTF_8);
    }

    private static String name(ThemeManager.Theme theme) {
        return theme.name().toLowerCase(java.util.Locale.ROOT);
    }
}
