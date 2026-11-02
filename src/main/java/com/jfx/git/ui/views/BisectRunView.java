package com.jfx.git.ui.views;

import com.jfx.git.core.BisectRunner;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.RepoView;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.UiScale;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Runs a bisect to its answer with a command instead of a person - {@code git bisect run} -
 * shown in the repository's tab in place of the graph.
 *
 * <p>What the user watches is the log: one line per candidate, saying which commit was
 * tested and what the command decided. That is the part worth seeing, because a bisect
 * that reaches a surprising answer is usually one where a step was skipped or a build
 * failed, and the log is where that shows.
 *
 * <h2>Leaving the view stops the run</h2>
 * Back, Escape or closing the tab cancels a run in progress, as closing the dialog this
 * was did. Keeping it going unseen was the alternative, and it is the worse one: the run
 * holds the repository's git lane for its whole length, so every other action in the tab
 * would queue silently behind a process the user could no longer see or stop - and it
 * checks out commit after commit underneath whatever they turned to instead. The session
 * is left where the run stopped (the user may want to look at that commit), and the
 * status bar says so, since the view that would have said it is gone. The view says this
 * beside Stop while a run is going, so it is not a surprise.
 *
 * <h2>One operation of the tab</h2>
 * A run is the tab's operation from Run to the answer, through its {@link OperationRunner}:
 * busy meanwhile, redrawn once at the end, and the commits it checked out recorded as the
 * tab's own change - without that, the next return to the window reloaded the whole tab
 * for them again.
 *
 * <h2>This runs a command the user typed</h2>
 * Through a shell, so {@code mvn -q test && ./check.sh} works - which makes it arbitrary
 * code execution by design, the same as the custom editor command in Preferences. The
 * view says so plainly rather than burying it, because the difference between this and
 * a malicious paste is entirely whether the person understood what they were pasting.
 */
public final class BisectRunView implements RepoView {

    /** The command field's id, for tests. */
    public static final String COMMAND_ID = "bisect-run-command";
    /** The log list's id. */
    public static final String LOG_ID = "bisect-run-log";
    /** The status line's id. */
    public static final String STATUS_ID = "bisect-run-status";

    private final OperationRunner operations;
    private final StatusBar statusBar;
    private final GitBackend gitService;
    private final BisectRunner runner;

    private final TextField command = new TextField();
    private final ListView<String> log = new ListView<>();
    private final Label status = new Label();
    private final Button start = new Button("Run");
    private final Button stop = new Button("Stop");
    private final VBox root;

    private GitTaskRunner.Task running;
    /** Set once the view has left the tab; the run's result then goes to the status bar. */
    private boolean closed;

    public BisectRunView(OperationRunner operations) {
        this.operations = operations;
        this.statusBar = operations.statusBar();
        this.gitService = operations.repository();
        this.runner = new BisectRunner(gitService);

        command.setId(COMMAND_ID);
        command.setPromptText("mvn -q test    ·    ./run-my-check.sh    ·    cargo test");
        command.setOnAction(e -> {
            if (!start.isDisabled()) startRun();
        });

        Label exitCodes = new Label(
                "Exit 0 marks the commit good, 125 skips it (it will not build), anything "
                        + "else marks it bad - the same codes git bisect run uses.");
        exitCodes.getStyleClass().add("text-muted");
        exitCodes.setWrapText(true);

        Label warning = new Label(
                "⚠ This runs the command you type, in your working tree, with your "
                        + "privileges - once per candidate commit.");
        warning.getStyleClass().add("text-muted");
        warning.setWrapText(true);

        log.setId(LOG_ID);
        log.setPlaceholder(new Label("Each commit tested will be listed here."));
        VBox.setVgrow(log, Priority.ALWAYS);

        status.setId(STATUS_ID);
        status.getStyleClass().add("text-muted");
        status.setWrapText(true);

        start.getStyleClass().add("btn-primary");
        start.setOnAction(e -> startRun());
        stop.setDisable(true);
        stop.setOnAction(e -> stopRun());

        HBox buttons = new HBox(UiScale.STEP, start, stop);
        buttons.setAlignment(Pos.CENTER_LEFT);

        root = new VBox(UiScale.STEP, new Label("Command to test each commit with:"), command,
                exitCodes, warning, buttons, log, status);
    }

    @Override
    public String title() {
        return "Bisect with a script";
    }

    @Override
    public Node content() {
        return root;
    }

    /** Stops a run in progress; see the class comment for why it does not keep going. */
    @Override
    public void closed() {
        closed = true;
        stopRun();
    }

    /** True while a run is going; for tests. */
    public boolean isRunning() {
        return running != null;
    }

    private void startRun() {
        String problem = runner.validate(command.getText());
        if (problem != null) {
            status.setText(problem);
            return;
        }
        log.getItems().clear();
        status.setText("Running… Leaving this screen stops the run.");
        start.setDisable(true);
        command.setDisable(true);
        stop.setDisable(false);

        String script = command.getText();
        operations.operation("Bisecting with a script", OperationRunner.Refresh.EVERYTHING)
                // How it went is said under the log, and in the status bar only once the
                // view that would have said it is gone.
                .showSuccess(this::reportIfClosed)
                .showFailure(result -> {
                    reportIfClosed(result);
                    return true;
                })
                // The run is a task of the view's own rather than one of the operation's
                // steps: Stop needs its handle.
                .start(steps -> running = GitTaskRunner.query(gitService,
                        () -> runner.run(script, step ->
                                // The runner calls back from the worker; the list is the FX
                                // thread's, so the hop happens here rather than in core code.
                                Platform.runLater(() -> log.getItems().add(step.describe()))),
                        outcome -> {
                            String message = outcome.isOk() ? outcome.status().message() : outcome.problem();
                            finished(message);
                            steps.finish(outcome.isOk() ? GitResult.ok(message) : GitResult.failed(message));
                        },
                        error -> {
                            finished(error);
                            steps.finish(GitResult.failed(error));
                        },
                        task -> task.onDropped(() -> {
                            finished("The run was cancelled before it started");
                            steps.finish(GitResult.failed("The run was cancelled before it started"));
                        })));
    }

    private void stopRun() {
        if (running != null) {
            running.cancel();
        }
    }

    /** The run is over; the operation redraws the tab after it, since HEAD moved repeatedly. */
    private void finished(String message) {
        running = null;
        start.setDisable(false);
        command.setDisable(false);
        stop.setDisable(true);
        status.setText(message);
    }

    private void reportIfClosed(GitResult result) {
        if (closed && statusBar != null) {
            statusBar.info("Bisect run: " + result.getMessage());
        }
    }
}
