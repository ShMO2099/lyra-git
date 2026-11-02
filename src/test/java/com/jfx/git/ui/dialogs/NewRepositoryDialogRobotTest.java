package com.jfx.git.ui.dialogs;

import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.RepoInitTemplates;
import com.jfx.git.ui.RobotWait;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opening a folder that is not a repository, from the offer to the reopen, through the
 * real dialog.
 *
 * <p>The init is the production one where the test wants a repository to appear, and a
 * recording stand-in where it wants to know what the form asked for or what a failure
 * looks like; the dialog takes either through its package-private constructor.
 */
class NewRepositoryDialogRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private File folder;
    /** Every starter-file choice the dialog handed to the init, in order. */
    private final List<RepoInitTemplates> inits = new CopyOnWriteArrayList<>();
    private final AtomicInteger opened = new AtomicInteger();
    /** Counted down when {@link NewRepositoryDialog#show()} has returned. */
    private final CountDownLatch finished = new CountDownLatch(1);

    @Override
    public void start(Stage stage) {
        stage.setScene(new Scene(new StackPane(), 400, 300));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    /** Shows the dialog without waiting for it: {@code show()} blocks until it is answered. */
    private void showDialog(NewRepositoryDialog.Purpose purpose, Function<RepoInitTemplates, GitResult> init) {
        folder = tempDir.resolve("project").toFile();
        assertTrue(folder.mkdirs());
        NewRepositoryDialog dialog = new NewRepositoryDialog(folder, purpose, templates -> {
            inits.add(templates);
            return init.apply(templates);
        }, opened::incrementAndGet);
        Platform.runLater(() -> {
            dialog.show();
            finished.countDown();
        });
    }

    /** Presses the button labelled {@code text} on {@code dialog}. */
    private void press(DialogPane dialog, String text) {
        ButtonType type = dialog.getButtonTypes().stream()
                .filter(t -> text.equals(t.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("no " + text + " button"));
        Button button = (Button) dialog.lookupButton(type);
        interact(button::fire);
    }

    /**
     * Waits for the dialog to be over and for anything it handed the network lane to
     * finish, so "nothing happened" is asserted after it would have happened.
     */
    private void awaitSettled() throws Exception {
        assertTrue(finished.await(30, TimeUnit.SECONDS), "the dialog never closed");
        CountDownLatch drained = new CountDownLatch(1);
        GitTaskRunner.runDetached(GitResult::ok, _ -> drained.countDown());
        assertTrue(drained.await(30, TimeUnit.SECONDS), "the network lane never drained");
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void initialiseAndOpenCreatesTheRepositoryThenReopens() throws Exception {
        showDialog(NewRepositoryDialog.Purpose.NOT_A_REPOSITORY,
                templates -> GitService.initRepository(folder, templates));

        DialogPane pane = RobotWait.untilDialogWithButton(NewRepositoryDialog.INITIALISE);
        interact(() -> {
            assertEquals("Make 'project' a Git repository?", pane.getHeaderText());
            CheckBox readme = (CheckBox) pane.lookup(".check-box");
            readme.setSelected(true);
        });
        press(pane, NewRepositoryDialog.INITIALISE);

        RobotWait.until("the repository never appeared", () -> new File(folder, ".git").isDirectory());
        RobotWait.until("the folder was never opened after initialising it", () -> opened.get() == 1);
        awaitSettled();
        assertEquals(1, inits.size(), "one init, for the one confirmation");
        assertTrue(inits.get(0).addReadme(), "the ticked README reached the init");
        assertTrue(new File(folder, "README.md").isFile(), "and was written");
        assertEquals(1, opened.get(), "opened once");
    }

    @Test
    void cancelLeavesTheFolderUntouched() throws Exception {
        showDialog(NewRepositoryDialog.Purpose.NOT_A_REPOSITORY,
                templates -> GitService.initRepository(folder, templates));
        press(RobotWait.untilDialogWithButton(NewRepositoryDialog.INITIALISE), ButtonType.CANCEL.getText());
        awaitSettled();

        assertEquals(List.of(), inits, "Cancel must not initialise anything");
        assertFalse(new File(folder, ".git").exists(), "the folder is as it was");
        assertEquals(0, opened.get(), "and is not opened");
    }

    @Test
    void aFailedInitShowsTheReasonAndDoesNotReopen() throws Exception {
        // The engines' shape: their own prefix, then git's stderr.
        showDialog(NewRepositoryDialog.Purpose.NOT_A_REPOSITORY, templates -> GitResult.failed(
                "Init failed: warning: templates not found\nfatal: cannot mkdir project/.git: Permission denied"));
        press(RobotWait.untilDialogWithButton(NewRepositoryDialog.INITIALISE), NewRepositoryDialog.INITIALISE);

        DialogPane error = RobotWait.untilDialogWithButton(ButtonType.OK.getText());
        interact(() -> {
            assertEquals("Could not create a repository in project", error.getHeaderText(), "names the folder");
            assertEquals("Cannot mkdir project/.git: Permission denied", error.getContentText(),
                    "the init's own reason: git's fatal line, without the prefixes");
            assertTrue(((javafx.scene.control.TextArea) error.getExpandableContent()).getText()
                    .contains("warning: templates not found"), "all of git's output behind Show Details");
        });
        press(error, ButtonType.OK.getText());
        awaitSettled();

        assertEquals(1, inits.size());
        assertEquals(0, opened.get(), "a folder that is still not a repository is not opened");
    }

    @Test
    void theCreatePurposeKeepsTheStarterFilesHeader() throws Exception {
        showDialog(NewRepositoryDialog.Purpose.CREATE, templates -> GitResult.ok());

        DialogPane pane = RobotWait.untilDialogWithButton(NewRepositoryDialog.CREATE);
        interact(() -> assertEquals("Create a repository in project", pane.getHeaderText()));
        press(pane, ButtonType.CANCEL.getText());
        awaitSettled();

        assertEquals(List.of(), inits);
        assertEquals(0, opened.get());
    }
}
