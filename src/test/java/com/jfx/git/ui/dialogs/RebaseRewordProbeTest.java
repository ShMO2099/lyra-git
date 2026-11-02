package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitService;
import com.jfx.git.core.RebaseTodo;
import com.jfx.git.core.RebaseTodo.Action;
import com.jfx.git.graph.GraphOrder;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RobotWait;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Isolates one question: does a reword's message, typed character by character, reach
 * the plan.
 *
 * <p>Prompted by {@code InteractiveRebaseRobotTest} losing all but the first character of
 * a typed reword, when the message was a field inside the row and every keystroke rebuilt
 * the rows. The message is written in the commit box's editor now, opened over the plan;
 * this builds the dialog directly, away from the two-window ambiguity a dialog opened from
 * the graph carries, and drives it as a user would.
 */
class RebaseRewordProbeTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private List<CommitInfo> log;
    private InteractiveRebaseDialog dialog;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();
        }
        try (GitService service = new GitService()) {
            service.openRepository(tempDir.toFile());
            for (String name : List.of("a", "b")) {
                Files.writeString(tempDir.resolve(name + ".txt"), name, StandardCharsets.UTF_8);
                service.stageFile(name + ".txt");
                service.commit("add " + name, false);
            }
            log = service.getCommitGraphPage(10, GraphOrder.current()).rows().stream()
                    .filter(r -> !r.isWipNode())
                    .map(r -> r.getCommit())
                    .toList();
        }
    }

    @AfterEach
    void closeTheDialog() {
        if (dialog != null) {
            FxHarness.run(() -> dialog.getDialogPane().getScene().getWindow().hide());
        }
    }

    @Test
    void typingAMessageIntoTheRewordEditorKeepsEveryCharacterAndReachesThePlan() {
        chooseRewordOnARow();

        DialogPane editor = RobotWait.untilDialogWithButton("Reword");
        TextField summary = (TextField) editor.lookup("#" + RewordDialog.SUMMARY_ID);
        RebaseTodo.Step target = FxHarness.call(() -> currentPlan().steps().stream()
                .filter(s -> s.action() == Action.REWORD).findFirst().orElseThrow());
        assertEquals(target.commit().getSubject(), summary.getText(),
                "the editor opens on the message the commit has now");

        clickOn(summary);
        interact(summary::selectAll);
        write("renamed");
        // A lost keystroke never arrives, so this still fails - after the timeout rather
        // than a fixed 150ms, which under load could also be before the last one landed.
        RobotWait.untilOnFx("the typed text never all landed", () -> "renamed".equals(summary.getText()));
        press(editor, "Reword");

        RobotWait.untilOnFx("the message never reached the plan", () -> "renamed".equals(
                currentPlan().step(currentPlan().indexOf(target.commit().getHash())).message()));
        assertTrue(FxHarness.call(() -> FxHarness.lookupAll(dialog.getDialogPane(), "rebase-message").stream()
                        .anyMatch(n -> n instanceof Label l && "renamed".equals(l.getText()))),
                "the row shows the summary it is being given");
    }

    @Test
    void cancellingTheFirstMessagePutsTheRowBackToPick() {
        chooseRewordOnARow();

        DialogPane editor = RobotWait.untilDialogWithButton("Reword");
        press(editor, "Cancel");

        RobotWait.untilOnFx("cancelling left a reword with nothing to change",
                () -> currentPlan().steps().stream().allMatch(s -> s.action() == Action.PICK));
        assertTrue(FxHarness.call(() -> dialog.getDialogPane().lookupButton(startType()).isDisabled()),
                "back to the untouched plan, which changes nothing");
    }

    /** Opens the plan and picks "reword" from a row's action pill, as a user would. */
    private void chooseRewordOnARow() {
        RebaseTodo plan = RebaseTodo.fromNewestFirst(log);
        dialog = FxHarness.call(() -> {
            InteractiveRebaseDialog d = new InteractiveRebaseDialog(plan);
            d.show();
            return d;
        });
        // No git here, but not synchronous either: the rows and the combo box's popup each
        // exist only after a layout pulse, which a loaded machine can take longer than a
        // fixed sleep to run. Each step waits for what it touches next.
        RobotWait.untilOnFx("the plan's rows were never laid out",
                () -> !FxHarness.lookupAll(dialog.getDialogPane(), "combo-box").isEmpty());

        ComboBox<?> box = (ComboBox<?>) FxHarness.lookupAll(dialog.getDialogPane(), "combo-box")
                .iterator().next();
        clickOn(box);
        RobotWait.untilOnFx("the action picker never opened",
                () -> lookup(".combo-box-popup").tryQuery().filter(Node::isVisible).isPresent());
        // In the open picker: the key legend above the rows says "reword" too.
        clickOn(from(lookup(".combo-box-popup")).lookup("reword").queryAs(Node.class));
    }

    private ButtonType startType() {
        return dialog.getDialogPane().getButtonTypes().stream()
                .filter(t -> "Start rebase".equals(t.getText())).findFirst().orElseThrow();
    }

    /** The plan the dialog would hand back if confirmed now. */
    private RebaseTodo currentPlan() {
        return dialog.getResultConverter().call(startType());
    }

    private void press(DialogPane pane, String label) {
        interact(() -> pane.getButtonTypes().stream()
                .filter(t -> label.equals(t.getText()))
                .map(t -> (Button) pane.lookupButton(t))
                .findFirst().orElseThrow().fire());
    }
}
