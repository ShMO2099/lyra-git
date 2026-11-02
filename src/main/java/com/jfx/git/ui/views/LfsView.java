package com.jfx.git.ui.views;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.NativeGitRunner;
import com.jfx.git.ui.RepoView;
import com.jfx.git.ui.UiScale;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Supplier;

/**
 * Git LFS for one repository: which files it holds, and its content fetched, pulled or
 * pruned - shown in the repository's tab in place of the graph.
 *
 * <p>Every action reports in the view and leaves it open, the way adding a remote does:
 * tracking one pattern should not rule out tracking a second in the same visit, and a
 * fetch that fails should say so beside the button that started it.
 *
 * <p>Nothing here is hidden when LFS cannot be used - on an engine that cannot run
 * {@code git-lfs}, or a machine without it. The status line says which, and the
 * engine's own refusal says what would work; a screen of missing buttons would say
 * neither.
 */
public final class LfsView implements RepoView {

    public static final String PATTERNS_ID = "lfs-patterns";
    public static final String PATTERN_FIELD_ID = "lfs-new-pattern";
    public static final String STATUS_ID = "lfs-status";
    public static final String RESULT_ID = "lfs-result";

    private final GitBackend gitService;
    private final Runnable onChanged;

    private final Label status = new Label("Checking…");
    private final ListView<String> patterns = new ListView<>();
    private final TextField newPattern = new TextField();
    private final Label result = new Label();
    private final Button setUp = new Button("Set up LFS here");
    private final List<Button> actions;
    private final VBox body;

    /** What the view shows about the repository, read on the git lane. */
    private record State(boolean installed, boolean usable, List<String> patterns) {
    }

    /** @param onChanged run after anything that may have changed the working tree or the index */
    public LfsView(GitBackend gitService, Runnable onChanged) {
        this.gitService = gitService;
        this.onChanged = onChanged == null ? () -> { } : onChanged;

        status.setId(STATUS_ID);
        status.setWrapText(true);
        status.getStyleClass().add("t-caption");

        patterns.setId(PATTERNS_ID);
        patterns.setPlaceholder(new Label("No files are tracked with LFS"));
        VBox.setVgrow(patterns, Priority.ALWAYS);

        newPattern.setId(PATTERN_FIELD_ID);
        newPattern.setPromptText("*.psd");
        HBox.setHgrow(newPattern, Priority.ALWAYS);
        Button track = new Button("Track");
        track.getStyleClass().add("btn-primary");
        track.disableProperty().bind(newPattern.textProperty().map(text -> text == null || text.isBlank()));
        track.setOnAction(e -> {
            String pattern = newPattern.getText().trim();
            run(() -> gitService.lfsTrack(pattern), newPattern::clear);
        });
        newPattern.setOnAction(e -> track.fire());

        Button untrack = new Button("Stop tracking");
        untrack.disableProperty().bind(patterns.getSelectionModel().selectedItemProperty().isNull());
        untrack.setOnAction(e -> {
            String pattern = patterns.getSelectionModel().getSelectedItem();
            if (pattern != null) {
                run(() -> gitService.lfsUntrack(pattern), null);
            }
        });

        setUp.setOnAction(e -> run(gitService::lfsInstall, null));
        Button fetch = new Button("Fetch");
        fetch.setOnAction(e -> run(gitService::lfsFetch, null));
        Button pull = new Button("Pull");
        pull.setOnAction(e -> run(gitService::lfsPull, null));
        Button prune = new Button("Prune");
        prune.setOnAction(e -> run(gitService::lfsPrune, null));
        actions = List.of(setUp, fetch, pull, prune);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox content = new HBox(UiScale.STEP, setUp, spacer, fetch, pull, prune);
        content.setAlignment(Pos.CENTER_LEFT);

        result.setId(RESULT_ID);
        result.setWrapText(true);

        Label tracked = new Label("Tracked patterns");
        tracked.getStyleClass().add("t-eyebrow");
        Label hint = new Label("Tracking changes .gitattributes, which has to be committed like any "
                + "other file. Files already committed are not converted.");
        hint.setWrapText(true);
        hint.getStyleClass().add("t-caption");

        body = new VBox(UiScale.GAP, status, tracked, patterns,
                new HBox(UiScale.STEP, newPattern, track, untrack), hint, content, result);
        // A form, not a table: on a wide window the pattern box and the buttons beside it
        // would otherwise sit a screen's width apart.
        body.setMaxWidth(720);
        javafx.scene.layout.StackPane.setAlignment(body, Pos.TOP_LEFT);

        reload();
    }

    @Override
    public String title() {
        return "Git LFS";
    }

    @Override
    public Node content() {
        return body;
    }

    /** Runs one LFS action, reports it under the buttons, and re-reads the repository. */
    private void run(Supplier<GitResult> action, Runnable onSuccess) {
        actions.forEach(button -> button.setDisable(true));
        result.setText("Working…");
        GitTaskRunner.run(gitService, action, outcome -> {
            actions.forEach(button -> button.setDisable(false));
            result.setText(outcome.describe());
            if (outcome.isOk() && onSuccess != null) {
                onSuccess.run();
            }
            reload();
            onChanged.run();
        });
    }

    private void reload() {
        GitTaskRunner.query(gitService,
                () -> new State(NativeGitRunner.isGitLfsInstalled(), gitService.canMaterializeLfsContent(),
                        gitService.lfsTrackedPatterns()),
                state -> {
                    patterns.getItems().setAll(state.patterns());
                    status.setText(describe(state));
                    // Offered only while it would change something.
                    setUp.setVisible(state.installed() && !state.usable());
                    setUp.setManaged(setUp.isVisible());
                },
                error -> status.setText("Could not read this repository's LFS state: " + error));
    }

    private static String describe(State state) {
        if (!state.installed()) {
            return "Git LFS is not installed on this machine, so its files stay as small pointer files "
                    + "here. Install it from git-lfs.com.";
        }
        if (!state.usable()) {
            return "Git LFS is installed, but it is not set up for this repository, or the engine in "
                    + "use cannot run it.";
        }
        return "Git LFS is installed and set up for this repository.";
    }
}
