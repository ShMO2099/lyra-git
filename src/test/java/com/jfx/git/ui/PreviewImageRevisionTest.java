package com.jfx.git.ui;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.testsupport.MainWindowFixture;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ListView;
import javafx.scene.image.ImageView;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A README opened from an old commit is previewed with that commit's images.
 *
 * <p>Here the logo was deleted in a later commit, so the working tree has none: a preview
 * reading images from the working tree - as it used to - shows only "[Logo]".
 */
class PreviewImageRevisionTest {

    @TempDir
    Path tempDir;

    @Test
    void aCommitsReadmeShowsTheImagesOfThatCommit() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)
                .commitFiles("Add the guide", Map.of(
                        "docs/guide/GUIDE.md", "# Guide\n\n![Logo](logo.png)\n".getBytes(),
                        "docs/guide/logo.png", png()), List.of())
                .commitFiles("Drop the guide's logo", Map.of(), List.of("docs/guide/logo.png"))) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    java.nio.file.Files.exists(window.workTree().resolve("docs/guide/logo.png")),
                    "the point of the test: today's working tree has no logo to fall back on");
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RepoTab tab = window.tab();

            CommitInfo added = FxHarness.call(() -> tab.getCommitList().getItems().stream()
                    .map(row -> row.getCommit())
                    .filter(commit -> commit != null && "Add the guide".equals(commit.getSubject()))
                    .findFirst().orElseThrow());
            FxHarness.run(() -> tab.getRightPanel().showCommitDetails(added));
            RobotWait.untilOnFx("the commit's files never listed", () -> changedFiles(window) != null
                    && changedFiles(window).getItems().stream().anyMatch(f -> f.path().endsWith("GUIDE.md")));
            FxHarness.run(() -> {
                ListView<com.jfx.git.core.GitBackend.FileChange> files = changedFiles(window);
                files.getSelectionModel().select(files.getItems().stream()
                        .filter(f -> f.path().endsWith("GUIDE.md")).findFirst().orElseThrow());
            });
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("the guide never opened",
                    () -> "docs/guide/GUIDE.md".equals(tab.diffViewer().filePathProperty().get())
                            && tab.diffViewer().previewToggle().isVisible());

            FxHarness.run(() -> tab.diffViewer().previewToggle().fire());
            RobotWait.drainGitWork();
            RobotWait.untilOnFx("the preview never showed the logo as it was in that commit",
                    () -> tab.diffViewer().markdownView() != null
                            && hasImage(tab.diffViewer().markdownView().body()));
        }
    }

    @SuppressWarnings("unchecked")
    private static ListView<com.jfx.git.core.GitBackend.FileChange> changedFiles(MainWindowFixture window) {
        Node list = window.scene().lookup(".changed-files-list");
        return list instanceof ListView<?> view ? (ListView<com.jfx.git.core.GitBackend.FileChange>) view : null;
    }

    private static boolean hasImage(Node node) {
        if (node instanceof ImageView) return true;
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (hasImage(child)) return true;
            }
        }
        return false;
    }

    private static byte[] png() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(4, 4,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
