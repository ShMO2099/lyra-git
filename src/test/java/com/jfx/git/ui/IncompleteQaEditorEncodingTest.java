package com.jfx.git.ui;

import com.jfx.git.core.GitService;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.TextEncoding;
import com.jfx.git.core.ThreadGuard;
import javafx.stage.Stage;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** A UTF-16LE editor save writes real working-tree bytes and leaves the index intact. */
class IncompleteQaEditorEncodingTest {
    @TempDir Path repository;
    private GitService service;

    private static byte[] utf16leWithBom(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] bytes = new byte[body.length + 2];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xFE;
        System.arraycopy(body, 0, bytes, 2, body.length);
        return bytes;
    }

    @AfterEach void close() {
        RobotWait.drainGitWork();
        if (service != null) service.close();
        FxHarness.closeShownStages();
    }

    @Test void editingUtf16leWritesBomAndCrlfBytesWithoutChangingTheIndex() throws Exception {
        byte[] original = utf16leWithBom("one\r\ntwo\r\n");
        try (var fixture = ThreadGuard.buildingFixture();
             Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Synthetic QA User");
            config.setString("user", null, "email", "qa-user@example.test");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.write(repository.resolve("utf16.txt"), original);
            git.add().addFilepattern("utf16.txt").call();
            git.commit().setMessage("seed").call();
        }
        service = new GitService();
        assertTrue(service.openRepository(repository.toFile()));
        String indexedBefore = stagedBlob("utf16.txt");
        DiffViewer viewer = FxHarness.call(() -> {
            DiffViewer v = new DiffViewer();
            Stage stage = FxHarness.show(v, 900, 600);
            v.setFileContentSupplier(service::getWorkingTreeBytes);
            v.setWorkingCopyExistsCheck(path -> true);
            v.setOnSaveFile((path, text, done) -> GitTaskRunner.run(service, () ->
                    service.writeWorkingTreeFile(path,
                            v.getEncoding().encode(text, service.getWorkingTreeBytes(path))), done));
            v.setEditable(true);
            v.showFileBytes("utf16.txt", original);
            stage.setUserData("keep-alive");
            FxHarness.pulse(v);
            v.beginEditing();
            FxHarness.pulse(v);
            return v;
        });

        assertEquals(TextEncoding.GUESS, FxHarness.call(viewer::getEncoding));
        FxHarness.run(() -> viewer.replaceBuffer("one\nchanged α\n"));
        FxHarness.run(viewer::saveEdits);
        byte[] expected = utf16leWithBom("one\r\nchanged α\r\n");
        RobotWait.until("the edited bytes were not written to the real working tree", () -> {
            try { return java.util.Arrays.equals(expected, Files.readAllBytes(repository.resolve("utf16.txt"))); }
            catch (Exception inProgress) { return false; }
        });
        RobotWait.drainGitWork();
        assertArrayEquals(expected, Files.readAllBytes(repository.resolve("utf16.txt")),
                "save preserves BOM, UTF-16LE and CRLF in the actual file");
        assertEquals(indexedBefore, stagedBlob("utf16.txt"), "ordinary Save does not alter the staged blob");
        assertFalse(FxHarness.call(viewer::hasUnsavedEdits));
    }

    private String stagedBlob(String path) throws Exception {
        try (Git git = Git.open(repository.toFile())) {
            var repo = git.getRepository();
            var index = repo.readDirCache();
            int position = index.findEntry(path);
            assertTrue(position >= 0);
            return java.util.HexFormat.of().formatHex(repo.getObjectDatabase().open(
                    index.getEntry(position).getObjectId()).getBytes());
        }
    }
}
