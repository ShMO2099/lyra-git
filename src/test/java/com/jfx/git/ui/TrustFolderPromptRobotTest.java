package com.jfx.git.ui;

import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.SafeDirectory;
import com.jfx.git.core.ScratchSafeDirectory;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * "Trust and open", from git's refusal to the reopen, through the real dialog.
 *
 * <p>A repository owned by another user cannot be made in a test, so the refusal is git's
 * message naming a scratch folder; everything after it is real - the dialog, its
 * buttons, and a {@code git config --global --add safe.directory} that writes the file.
 * That file is a scratch global config reached through {@code GIT_CONFIG_GLOBAL}, never
 * the developer's own, and the tests check it is the one written.
 */
class TrustFolderPromptRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private Path globalConfig;
    private Path shared;
    private String sharedPath;
    private String refusal;

    /** Every path the prompt asked to trust, in order. */
    private final List<String> trusted = new CopyOnWriteArrayList<>();
    private final AtomicInteger reopened = new AtomicInteger();
    /** Counted down when {@link TrustFolderPrompt#show()} has returned. */
    private final CountDownLatch finished = new CountDownLatch(1);

    @Override
    public void start(Stage stage) {
        stage.setScene(new Scene(new StackPane(), 400, 300));
        stage.show();
    }

    @BeforeEach
    void scratchFolderAndConfig() throws Exception {
        assumeTrue(ScratchSafeDirectory.gitInstalled(),
                "needs git: without it trust would write JGit's user config, the real one");
        globalConfig = tempDir.resolve("global.gitconfig");
        Files.writeString(globalConfig, "");
        shared = Files.createDirectories(tempDir.resolve("shared-repo"));
        sharedPath = SafeDirectory.gitPath(shared.toFile());
        // Git 2.55's refusal, naming the scratch folder.
        refusal = "fatal: detected dubious ownership in repository at '" + sharedPath + "'\n"
                + "'" + sharedPath + "' is owned by:\n"
                + "\tBUILTIN/Administrators (S-1-5-32-544)\n"
                + "but the current user is:\n"
                + "\tHOST/someone (S-1-5-21-1-2-3-1001)\n"
                + "To add an exception for this directory, call:\n\n"
                + "\tgit config --global --add safe.directory " + sharedPath;
    }

    @AfterEach
    void tidyUp() {
        interact(() -> new java.util.ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    /** Shows the prompt without waiting for it: {@code show()} blocks until it is answered. */
    private TrustFolderPrompt showPrompt() {
        Function<String, GitResult> scratch = ScratchSafeDirectory.trustingInto(globalConfig);
        TrustFolderPrompt prompt = new TrustFolderPrompt(shared.toFile(), refusal, path -> {
            trusted.add(path);
            return scratch.apply(path);
        }, reopened::incrementAndGet);
        Platform.runLater(() -> {
            prompt.show();
            finished.countDown();
        });
        return prompt;
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
     * Waits for the prompt to be over and for anything it handed the network lane to
     * finish, so "nothing was written" is asserted after the write would have happened.
     */
    private void awaitPromptSettled() throws Exception {
        assertTrue(finished.await(30, TimeUnit.SECONDS), "the prompt never closed");
        // The lane is one thread: a marker queued now runs after anything queued before it.
        CountDownLatch drained = new CountDownLatch(1);
        GitTaskRunner.runDetached(GitResult::ok, _ -> drained.countDown());
        assertTrue(drained.await(30, TimeUnit.SECONDS), "the network lane never drained");
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** The {@code safe.directory} values in the scratch global config. */
    private List<String> scratchSafeDirectories() throws Exception {
        FileBasedConfig config = new FileBasedConfig(globalConfig.toFile(), FS.DETECTED);
        config.load();
        return List.of(config.getStringList("safe", null, "directory"));
    }

    @Test
    void trustingAddsOneSafeDirectoryEntryToTheScratchConfigAndReopens() throws Exception {
        TrustFolderPrompt prompt = showPrompt();
        assertEquals(sharedPath, prompt.path(), "the path to trust is the one git's message names");

        DialogPane ask = RobotWait.untilDialogWithButton(TrustFolderPrompt.TRUST);
        interact(() -> {
            assertEquals("Trust a folder owned by another user?", ask.getHeaderText());
            assertTrue(ask.getContentText().contains("It belongs to BUILTIN/Administrators, and you are"
                            + " HOST/someone."),
                    "the owner and the user, read out of git's message without their SIDs: "
                            + ask.getContentText());
            assertFalse(ask.getContentText().contains("S-1-"), "no SIDs in the sentence");
            assertEquals(refusal, ((TextArea) ask.getExpandableContent()).getText(),
                    "git's own words, as git wrote them, behind Show Details");
            assertEquals("git config --global --add safe.directory " + sharedPath,
                    ((Label) ask.lookup(".jfx-code-text")).getText(), "the prompt shows what it will run");
        });
        press(ask, TrustFolderPrompt.TRUST);

        RobotWait.until("the folder was never reopened after trusting it", () -> reopened.get() == 1);
        assertEquals(List.of(sharedPath), trusted, "trusted exactly the folder git named, once");
        assertEquals(List.of(sharedPath), scratchSafeDirectories(),
                "one safe.directory entry, in the scratch global config");
        awaitPromptSettled();
        assertEquals(1, reopened.get(), "reopened once");
    }

    @Test
    void cancellingWritesNothingAndDoesNotReopen() throws Exception {
        showPrompt();
        DialogPane ask = RobotWait.untilDialogWithButton(TrustFolderPrompt.TRUST);
        press(ask, ButtonType.CANCEL.getText());
        awaitPromptSettled();

        assertEquals(List.of(), trusted, "Cancel must not trust anything");
        assertEquals("", Files.readString(globalConfig), "nor write the global config");
        assertEquals(0, reopened.get(), "nor reopen the folder");
    }

    /** Git before 2.4x, and libgit2, name no owner: the prompt then says it without one. */
    @Test
    void theOwnerIsReadOnlyWhenGitNamesOne() {
        assertArrayEquals(new String[]{"BUILTIN/Administrators", "HOST/someone"},
                TrustFolderPrompt.owners(refusal));
        assertNull(TrustFolderPrompt.owners("fatal: detected dubious ownership in repository at '/srv/shop'\n"
                + "To add an exception for this directory, call:\n\n"
                + "\tgit config --global --add safe.directory /srv/shop"));
        assertNull(TrustFolderPrompt.owners(null));
    }

    /**
     * The confirmation that used to follow the refusal is gone, folded into the one
     * question; what it guarded against - trusting by reflex - is now that Enter is Cancel.
     */
    @Test
    void enterDoesNotTrust() throws Exception {
        showPrompt();
        DialogPane ask = RobotWait.untilDialogWithButton(TrustFolderPrompt.TRUST);
        interact(() -> {
            Button trust = (Button) ask.lookupButton(ask.getButtonTypes().stream()
                    .filter(t -> TrustFolderPrompt.TRUST.equals(t.getText())).findFirst().orElseThrow());
            assertFalse(trust.isDefaultButton(), "Enter must not be how a folder is trusted");
            assertTrue(((Button) ask.lookupButton(ButtonType.CANCEL)).isDefaultButton(), "Enter cancels");
        });
        press(ask, ButtonType.CANCEL.getText());
        awaitPromptSettled();

        assertEquals(List.of(), trusted, "nothing was trusted");
        assertEquals(0, reopened.get(), "nor reopened");
    }
}
