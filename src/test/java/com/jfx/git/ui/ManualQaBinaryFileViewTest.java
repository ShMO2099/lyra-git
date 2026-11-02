package com.jfx.git.ui;

import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.TextEncoding;
import com.jfx.git.core.ThreadGuard;

import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for a binary historical file opened from a commit diff. */
class ManualQaBinaryFileViewTest {

    @TempDir
    Path tempDir;

    private GitService service;

    @AfterEach
    void closeHarnessStages() {
        if (service != null) {
            RobotWait.drainGitWork();
            service.close();
        }
        FxHarness.closeShownStages();
    }

    @Test
    void binaryEncodingInFileViewShowsTheFileBytesRatherThanItsPatch() {
        byte[] patch = ("diff --git a/assets/sample.bin b/assets/sample.bin\n"
                + "index 1234567..abcdef0 100644\n"
                + "Binary files a/assets/sample.bin and b/assets/sample.bin differ\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] selectedCommitBytes = new byte[]{0x00, 0x01, 0x02, (byte) 0xff, 0x00, (byte) 0x80};

        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer result = new DiffViewer();
            Stage stage = FxHarness.show(result, 900, 600);
            result.setFileDiffBytes("assets/sample.bin", patch, selectedCommitBytes);
            ((javafx.scene.control.ComboBox<TextEncoding>) result.encodingSelector())
                    .setValue(TextEncoding.BINARY);
            FxHarness.pulse(result);
            stage.setUserData("keep-alive");
            return result;
        });

        FxHarness.run(() -> {
            ToggleButton fileView = FxHarness.lookupAll(viewer, "diff-segment").stream()
                    .filter(ToggleButton.class::isInstance)
                    .map(ToggleButton.class::cast)
                    .filter(button -> button.getText().equals("File view"))
                    .findFirst().orElseThrow(() -> new AssertionError("File view toggle missing"));
            fileView.fire();
            FxHarness.pulse(viewer);

            assertTrue(viewer.isFileView(), "the selected view is the whole file");
            String shown = FxHarness.lookupAll(viewer, "code-area").stream()
                    .map(node -> ((DiffTextArea) node).getText())
                    .findFirst().orElseThrow(() -> new AssertionError("file content surface missing"));
            assertTrue(shown.startsWith("00000000  "), shown);
            assertTrue(shown.contains("00 01 02 ff 00 80"), shown);
            assertFalse(shown.contains("diff --git"), "patch metadata must not appear in File view: " + shown);
            assertFalse(shown.contains("Binary files"), "patch summary must not appear in File view: " + shown);

            ToggleButton diffView = FxHarness.lookupAll(viewer, "diff-segment").stream()
                    .filter(ToggleButton.class::isInstance)
                    .map(ToggleButton.class::cast)
                    .filter(button -> button.getText().equals("Diff view"))
                    .findFirst().orElseThrow(() -> new AssertionError("Diff view toggle missing"));
            diffView.fire();
            FxHarness.pulse(viewer);
            assertFalse(viewer.isFileView(), "the selected view returned to the patch");
            String patchDump = FxHarness.lookupAll(viewer, "code-area").stream()
                    .map(node -> ((DiffTextArea) node).getText())
                    .findFirst().orElseThrow(() -> new AssertionError("diff content surface missing"));
            assertEquals(TextEncoding.hexDump(patch).stripTrailing(), patchDump.stripTrailing(),
                    "Diff view continues to dump exactly the binary patch bytes");
        });
    }

    @Test
    void binaryEncodingInStagedFileViewUsesWorkingTreeBytesWhileDiffViewUsesPatch() {
        byte[] patch = ("diff --git a/assets/sample.bin b/assets/sample.bin\n"
                + "index 1234567..abcdef0 100644\n"
                + "Binary files a/assets/sample.bin and b/assets/sample.bin differ\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] stagedIndexBytes = new byte[]{0x11, 0x22, 0x33, (byte) 0xdd};
        byte[] workingTreeBytes = new byte[]{0x44, 0x55, 0x66, (byte) 0xee};

        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer result = new DiffViewer();
            Stage stage = FxHarness.show(result, 900, 600);
            result.setFileContentSupplier(path -> workingTreeBytes);
            result.setStagedDiffBytes("assets/sample.bin", patch, stagedIndexBytes);
            ((javafx.scene.control.ComboBox<TextEncoding>) result.encodingSelector())
                    .setValue(TextEncoding.BINARY);
            FxHarness.pulse(result);
            stage.setUserData("keep-alive");
            return result;
        });

        FxHarness.run(() -> {
            ToggleButton fileView = FxHarness.lookupAll(viewer, "diff-segment").stream()
                    .filter(ToggleButton.class::isInstance)
                    .map(ToggleButton.class::cast)
                    .filter(button -> button.getText().equals("File view"))
                    .findFirst().orElseThrow(() -> new AssertionError("File view toggle missing"));
            fileView.fire();
            FxHarness.pulse(viewer);

            String shownFile = FxHarness.lookupAll(viewer, "code-area").stream()
                    .map(node -> ((DiffTextArea) node).getText())
                    .findFirst().orElseThrow(() -> new AssertionError("file content surface missing"));
            assertTrue(shownFile.contains("44 55 66 ee"),
                    "staged File view follows the working tree bytes: " + shownFile);
            assertFalse(shownFile.contains("11 22 33 dd"),
                    "staged File view must not substitute the index's new-side bytes: " + shownFile);

            ToggleButton diffView = FxHarness.lookupAll(viewer, "diff-segment").stream()
                    .filter(ToggleButton.class::isInstance)
                    .map(ToggleButton.class::cast)
                    .filter(button -> button.getText().equals("Diff view"))
                    .findFirst().orElseThrow(() -> new AssertionError("Diff view toggle missing"));
            diffView.fire();
            FxHarness.pulse(viewer);
            String shownPatch = FxHarness.lookupAll(viewer, "code-area").stream()
                    .map(node -> ((DiffTextArea) node).getText())
                    .findFirst().orElseThrow(() -> new AssertionError("diff content surface missing"));
            assertEquals(TextEncoding.hexDump(patch).stripTrailing(), shownPatch.stripTrailing(),
                    "Diff view continues to dump exactly the patch bytes");
        });
    }

    @Test
    void savingThenStagingTheSameBufferUpdatesTheRealIndexWithoutCommitting() throws Exception {
        byte[] original = "before\n".getBytes(StandardCharsets.UTF_8);
        byte[] saved = "after save\n".getBytes(StandardCharsets.UTF_8);
        try (var fixture = ThreadGuard.buildingFixture();
             Git git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.write(tempDir.resolve("config.txt"), original);
            git.add().addFilepattern("config.txt").call();
            git.commit().setMessage("base config").call();
        }

        service = new GitService();
        assertTrue(service.openRepository(tempDir.toFile()));
        String headBefore = service.getHeadHash();

        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer result = new DiffViewer();
            Stage stage = FxHarness.show(result, 900, 600);
            result.setOnSaveFile((path, text, done) -> GitTaskRunner.run(service,
                    () -> service.writeWorkingTreeFile(path, text.getBytes(StandardCharsets.UTF_8)), done));
            result.setOnSaveAndStage((path, text, done) -> GitTaskRunner.run(service, () -> {
                GitResult written = service.writeWorkingTreeFile(
                        path, text.getBytes(StandardCharsets.UTF_8));
                return written.isOk() ? service.stageFile(path) : written;
            }, done));
            result.setEditable(true);
            result.showFileBytes("config.txt", original);
            stage.setUserData("keep-alive");
            FxHarness.pulse(result);
            result.beginEditing();
            FxHarness.pulse(result);
            return result;
        });

        FxHarness.run(() -> viewer.replaceBuffer("after save\n"));
        FxHarness.run(viewer::saveEdits);
        RobotWait.drainGitWork();
        assertArrayEquals(saved, Files.readAllBytes(tempDir.resolve("config.txt")),
                "Save writes the current buffer to the working tree");
        assertArrayEquals(original, service.getStagedBytes("config.txt"),
                "ordinary Save leaves the index at the committed bytes");
        assertEquals(headBefore, service.getHeadHash(), "Save does not move HEAD");

        FxHarness.run(() -> {
            Button button = FxHarness.lookupAll(viewer, "diff-tool-button").stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(candidate -> "Save and stage".equals(candidate.getText()))
                    .findFirst().orElseThrow(() -> new AssertionError("Save and stage button missing"));
            assertTrue(button.isVisible(), "Save and stage remains available for the saved buffer");
            assertFalse(button.isDisabled());
            button.fire();
        });
        RobotWait.drainGitWork();

        assertArrayEquals(saved, Files.readAllBytes(tempDir.resolve("config.txt")),
                "Save and stage preserves the working-tree bytes");
        assertArrayEquals(saved, service.getStagedBytes("config.txt"),
                "Save and stage updates the real index to the saved bytes");
        assertEquals(List.of("config.txt"), service.getStagingState().staged());
        assertTrue(service.getStagingState().unstaged().isEmpty(),
                "the saved bytes and index bytes agree");
        assertEquals(headBefore, service.getHeadHash(), "staging does not create a commit");
    }
}
