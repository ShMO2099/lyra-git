package com.jfx.git.ui;

import com.jfx.git.core.GitService;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.model.CommitInfo;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.RepositoryCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reverting a hunk from File history writes back each line's own ending.
 *
 * <p>File history built its edit by hand from {@code hunk.newLines()} and
 * {@code oldLines()}, which drop every line's CR. A commit that took a line's CR away
 * therefore gave an edit whose two blocks were the same text, and the revert reported
 * success over identical bytes: the CR never came back. The staging panel's actions were
 * fixed for this in 38a769d by building edits through {@link HunkSelection}; File history
 * was not.
 */
class FileHistoryRevertHunkTest {

    private static final String FILE = "a.txt";
    private static final String LF = "one\ntwo\nthree\n";
    private static final String CRLF_TWO = "one\ntwo\r\nthree\n";

    @TempDir
    Path root;

    private GitService service;

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        FxHarness.closeShownStages();
        if (service != null) {
            service.close();
        }
        RepositoryCache.clear();
    }

    @Test
    void revertingACommitThatOnlyChangedALineEndingPutsTheOldEndingBack() throws Exception {
        Path work = root.resolve("work");
        try (var fixture = ThreadGuard.buildingFixture();
             Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Synthetic QA");
            config.setString("user", null, "email", "qa@example.test");
            config.setBoolean("core", null, "autocrlf", false);
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(work.resolve(FILE), CRLF_TWO, StandardCharsets.UTF_8);
            git.add().addFilepattern(FILE).call();
            git.commit().setMessage("crlf on two").call();
            Files.writeString(work.resolve(FILE), LF, StandardCharsets.UTF_8);
            git.add().addFilepattern(FILE).call();
            git.commit().setMessage("lf throughout").call();
        }
        service = new GitService();
        assertTrue(service.openRepository(work.toFile()));

        FileHistoryView view = FxHarness.call(() -> {
            FileHistoryView v = new FileHistoryView(OperationRunner.of(service, new StatusBar(), () -> { }), FILE, false,
                    () -> { }, ignored -> { });
            FxHarness.showRegistered(v, 1100, 720);
            return v;
        });
        RobotWait.untilOnFx("the history never listed both commits",
                () -> commitList(view).getItems().size() == 2);
        FxHarness.run(() -> commitList(view).getSelectionModel().select(0));
        RobotWait.untilOnFx("the line-ending commit's hunk never offered a revert",
                () -> revertButton(view) != null);

        Button revert = FxHarness.call(() -> revertButton(view));
        // Not run inside FxHarness: the confirmation's nested event loop would hold it open.
        Platform.runLater(revert::fire);
        DialogPane confirm = RobotWait.untilDialogWithButton("OK");
        FxHarness.run(() -> ((Button) confirm.lookupButton(ButtonType.OK)).fire());

        try {
            RobotWait.until("the old line ending never came back",
                    () -> CRLF_TWO.equals(read(work)));
        } catch (AssertionError timedOut) {
            assertEquals(escaped(CRLF_TWO), escaped(read(work)),
                    "the revert puts back the CR the commit took off line two");
            throw timedOut;
        }
    }

    @SuppressWarnings("unchecked")
    private static ListView<CommitInfo> commitList(FileHistoryView view) {
        return (ListView<CommitInfo>) (ListView<?>) FxHarness.lookupAll(view, "file-history-list").getFirst();
    }

    /** The one hunk's revert button, whatever it is labelled, once it is showing. */
    private static Button revertButton(FileHistoryView view) {
        return FxHarness.lookupAll(view, "diff-revert-hunk").stream()
                .filter(n -> n instanceof Button b && b.isVisible()
                        && b.getParent() != null && b.getParent().isVisible())
                .map(Button.class::cast)
                .findFirst().orElse(null);
    }

    private static String read(Path work) {
        try {
            return Files.readString(work.resolve(FILE), StandardCharsets.UTF_8);
        } catch (java.io.IOException midWrite) {
            return "";
        }
    }

    private static String escaped(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }
}
