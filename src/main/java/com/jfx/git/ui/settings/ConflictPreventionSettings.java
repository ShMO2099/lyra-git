package com.jfx.git.ui.settings;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.RepositoryPreferences;

import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.io.File;
import java.util.List;

import static com.jfx.git.ui.settings.SettingsLayout.hint;
import static com.jfx.git.ui.settings.SettingsLayout.row;

/**
 * The settings behind the early conflict warning and working-tree snapshots: the
 * per-repository part, which the Repository section of Settings embeds, and the
 * application-wide part on the General page.
 *
 * <p>Built here rather than on either page so the two halves of one feature are read and
 * changed together - and so the page that hosts the per-repository half only has to place
 * one node.
 */
public final class ConflictPreventionSettings {

    /** The per-repository switch's id, for tests. */
    public static final String REPOSITORY_SWITCH_ID = "conflict-prevention-repository";
    /** The base-branch patterns' id, for tests. */
    public static final String PATTERNS_ID = "conflict-prevention-patterns";
    /** The global switch's id, for tests. */
    public static final String GLOBAL_SWITCH_ID = "conflict-prevention-global";
    /** The snapshot interval's id, for tests. */
    public static final String SNAPSHOT_INTERVAL_ID = "snapshot-interval";

    /** What the help under the patterns shows: a pattern, and what it does. */
    static final List<String[]> EXAMPLES = List.of(
            new String[]{"origin/main", "include a single branch"},
            new String[]{"origin/*", "include every branch directly on origin"},
            new String[]{"origin/**", "include every branch on origin, release/1.0 too"},
            new String[]{"!origin/wip/**", "leave these out, whichever line they are on"});

    private ConflictPreventionSettings() {
    }

    /**
     * The Repository section's part: whether this repository warns, and which branches
     * count as its base.
     *
     * @param repository the repository's working tree; null shows only a line saying so
     */
    public static Node section(File repository) {
        VBox box = new VBox(10);
        box.getChildren().add(SettingsLayout.section("Conflict prevention"));
        if (repository == null) {
            box.getChildren().add(hint("Open a repository to change its settings."));
            return box;
        }

        CheckBox enabled = new CheckBox("Warn about conflicts with the base branch in this repository");
        enabled.setId(REPOSITORY_SWITCH_ID);
        enabled.setSelected(RepositoryPreferences.conflictWarning(repository).orElse(true));
        enabled.setDisable(!AppPreferences.warnAboutConflicts());
        // Stored only once it says something other than the default, so a repository
        // nobody touched keeps following the global switch.
        enabled.selectedProperty().addListener((obs, old, on) ->
                RepositoryPreferences.setConflictWarning(repository, on ? null : Boolean.FALSE));

        TextArea patterns = new TextArea(RepositoryPreferences.effectiveConflictBasePatterns(repository));
        patterns.setId(PATTERNS_ID);
        patterns.setPrefRowCount(4);
        patterns.setPrefColumnCount(30);
        patterns.setWrapText(false);
        patterns.getStyleClass().add("t-mono");
        patterns.textProperty().addListener((obs, old, text) ->
                RepositoryPreferences.setConflictBasePatterns(repository, text));
        patterns.disableProperty().bind(enabled.selectedProperty().not().or(enabled.disableProperty()));

        box.getChildren().addAll(
                enabled,
                hint(AppPreferences.warnAboutConflicts()
                        ? "Checks in the background, after a fetch and whenever this branch moves,"
                                + " whether merging its base branch would conflict - and says so in the"
                                + " status bar while there is still time to do something about it."
                        : "Turned off for every repository on the General page."),
                row("Base branches", patterns),
                hint("One pattern per line. Of the remote branches they match, the one this branch"
                        + " was most recently in step with is its base."),
                examples());
        return box;
    }

    /** The pattern help, one example per row. */
    private static Node examples() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(4);
        int line = 0;
        for (String[] example : EXAMPLES) {
            Label pattern = new Label(example[0]);
            pattern.getStyleClass().add("pattern-example");
            pattern.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            Label meaning = new Label(example[1]);
            meaning.getStyleClass().add("preferences-hint");
            meaning.setWrapText(true);
            grid.addRow(line++, pattern, meaning);
        }
        return grid;
    }

    /**
     * The General page's rows: the switch for every repository, and how often the working
     * tree is snapshotted.
     */
    static List<Node> generalRows() {
        CheckBox warn = new CheckBox("Warn about conflicts with the base branch");
        warn.setId(GLOBAL_SWITCH_ID);
        warn.setSelected(AppPreferences.warnAboutConflicts());
        warn.selectedProperty().addListener((obs, old, on) -> AppPreferences.setWarnAboutConflicts(on));

        ChoiceBox<Integer> interval = new ChoiceBox<>();
        interval.setId(SNAPSHOT_INTERVAL_ID);
        interval.getItems().setAll(0, 5, 10, 15, 30, 60);
        int current = AppPreferences.snapshotIntervalMinutes();
        if (!interval.getItems().contains(current)) interval.getItems().add(current);
        interval.setValue(current);
        interval.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Integer minutes) {
                return minutes == null || minutes <= 0 ? "Off" : "Every " + minutes + " min";
            }

            @Override
            public Integer fromString(String text) {
                return AppPreferences.snapshotIntervalMinutes();
            }
        });
        interval.valueProperty().addListener((obs, old, minutes) ->
                AppPreferences.setSnapshotIntervalMinutes(minutes == null ? 0 : minutes));

        return List.of(
                SettingsLayout.section("Safety nets"),
                warn,
                hint("Shows in the status bar when merging the base branch into the branch you are on"
                        + " would conflict. Each repository's base branches are set in its own settings."),
                row("Working-tree snapshots", interval),
                hint("Keeps a copy of your uncommitted work, untracked files included, without"
                        + " touching the index or the stash. One is always taken just before a discard"
                        + " or a hard reset, whatever this says."));
    }
}
