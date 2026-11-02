package com.jfx.git.ui;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.testsupport.MainWindowFixture;

import javafx.scene.image.ImageView;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.jgit.api.Git;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The main graph's image diff must read both blobs from the selected commit history. */
class ManualQaHistoricalImageRobotTest {

    @TempDir
    Path tempDir;

    @Test
    void mainCommitDetailsCompareTheSelectedCommitImageWithItsParent() throws Exception {
        byte[] blue = png(0xff0000ff);
        byte[] orange = png(0xffffa500);
        byte[] green = png(0xff00ff00);
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)
                .commitFiles("blue image", Map.of("assets/pixel.png", blue), List.of())
                .commitFiles("orange image", Map.of("assets/pixel.png", orange), List.of())) {
            // Give the worktree a third, unmistakable image so a historical diff cannot
            // accidentally pass by reading the current file instead of the selected blobs.
            Files.write(window.workTree().resolve("assets/pixel.png"), green);
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RepoTab tab = window.tab();
            CommitInfo selected = FxHarness.call(() -> tab.getCommitList().getItems().stream()
                    .map(row -> row.getCommit())
                    .filter(commit -> commit != null && "orange image".equals(commit.getSubject()))
                    .findFirst().orElseThrow());
            assertEquals(1, selected.getParentHashes().size(), "the image commit has one parent");
            assertArrayEquals(blue, window.service().getFileBytesAtCommit(
                            selected.getParentHashes().get(0), "assets/pixel.png"),
                    "the independent parent-blob read is blue");
            assertArrayEquals(orange, window.service().getFileBytesAtCommit(
                            selected.getHash(), "assets/pixel.png"),
                    "the independent selected-commit blob read is orange");
            assertArrayEquals(green, Files.readAllBytes(
                            window.workTree().resolve("assets/pixel.png")),
                    "the worktree now differs from both historical revisions");

            ThreadGuard.clearViolations();
            FxHarness.run(() -> tab.getRightPanel().showCommitDetails(selected));
            RobotWait.untilOnFx("the historical image change was not listed", () -> {
                ListView<?> files = changedFiles(window);
                return files != null && files.getItems().stream()
                        .map(change -> ((com.jfx.git.core.GitBackend.FileChange) change).path())
                        .anyMatch("assets/pixel.png"::equals);
            });

            FxHarness.run(() -> {
                ListView<com.jfx.git.core.GitBackend.FileChange> files = changedFiles(window);
                int imageIndex = -1;
                for (int i = 0; i < files.getItems().size(); i++) {
                    if ("assets/pixel.png".equals(files.getItems().get(i).path())) {
                        imageIndex = i;
                        break;
                    }
                }
                assertTrue(imageIndex >= 0, "the changed image must be selectable");
                files.getSelectionModel().select(imageIndex);
            });

            RobotWait.untilOnFx("the image comparison was not rendered",
                    () -> FxHarness.lookupAll(tab.diffViewer(), "image-diff-canvas").size() == 2);
            List<Integer> actualPixels = FxHarness.call(() -> FxHarness.lookupAll(
                            tab.diffViewer(), "image-diff-canvas").stream()
                    .map(node -> ((ImageView) node).getImage().getPixelReader().getArgb(0, 0))
                    .toList());
            assertEquals(List.of(0xff0000ff, 0xffffa500), actualPixels,
                    "Before must be the selected commit's parent; After must be the selected commit");
            assertTrue(ThreadGuard.productViolations().isEmpty(),
                    () -> "historical image rendering must not run Git on the FX thread: "
                            + ThreadGuard.productViolations());
        }
    }

    @Test
    void stagedAndUnstagedImageDiffsUseIndexAndWorkingTreeBytes() throws Exception {
        byte[] blue = png(0xff0000ff);
        byte[] orange = png(0xffffa500);
        byte[] green = png(0xff00ff00);
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)
                .commitFiles("blue image", Map.of("assets/pixel.png", blue), List.of())) {
            Path image = window.workTree().resolve("assets/pixel.png");
            Files.write(image, orange);
            try (Git git = Git.open(window.workTree().toFile())) {
                git.add().addFilepattern("assets/pixel.png").call();
            }
            Files.write(image, green);

            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            assertArrayEquals(blue, window.service().getFileBytesAtCommit(
                            window.service().getHeadHash(), "assets/pixel.png"),
                    "HEAD is independently verified as blue");
            assertArrayEquals(orange, window.service().getStagedBytes("assets/pixel.png"),
                    "the index is independently verified as orange");
            assertArrayEquals(green, Files.readAllBytes(image),
                    "the working copy is independently verified as green");

            ThreadGuard.clearViolations();
            FxHarness.run(() -> window.tab().getRightPanel().showStagingArea());
            RobotWait.untilOnFx("the staged image was not listed", () -> {
                ListView<?> list = FxHarness.lookupAll(window.tab().getContent(),
                                "staging-staged-list").stream()
                        .filter(ListView.class::isInstance).map(ListView.class::cast)
                        .findFirst().orElse(null);
                return list != null && list.getItems().contains("assets/pixel.png");
            });
            FxHarness.run(() -> selectStagingPath(window, "staging-staged-list"));
            waitForPixels(window, List.of(0xff0000ff, 0xffffa500),
                    "staged image sides are HEAD blue then index orange");

            FxHarness.run(() -> selectStagingPath(window, "staging-unstaged-list"));
            waitForPixels(window, List.of(0xffffa500, 0xff00ff00),
                    "unstaged image sides are index orange then working green");
            assertTrue(ThreadGuard.productViolations().isEmpty(),
                    () -> "staged and unstaged historical image loading must not run Git on FX: "
                            + ThreadGuard.productViolations());
        }
    }

    @Test
    void arbitraryCommitComparisonUsesEndpointImagesInsteadOfWorkingCopy() throws Exception {
        byte[] blue = png(0xff0000ff);
        byte[] orange = png(0xffffa500);
        byte[] green = png(0xff00ff00);
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)
                .commitFiles("blue image", Map.of("assets/pixel.png", blue), List.of())
                .commitFiles("orange image", Map.of("assets/pixel.png", orange), List.of())) {
            Files.write(window.workTree().resolve("assets/pixel.png"), green);
            window.open(1180, 690, ThemeManager.Theme.DARK, true);
            RepoTab tab = window.tab();
            CommitInfo[] endpoints = FxHarness.call(() -> {
                List<CommitInfo> commits = tab.getCommitList().getItems().stream()
                        .map(row -> row.getCommit())
                        .filter(java.util.Objects::nonNull)
                        .toList();
                return new CommitInfo[]{
                        commits.stream().filter(c -> "blue image".equals(c.getSubject()))
                                .findFirst().orElseThrow(),
                        commits.stream().filter(c -> "orange image".equals(c.getSubject()))
                                .findFirst().orElseThrow()};
            });
            assertArrayEquals(blue, window.service().getFileBytesAtCommit(
                            endpoints[0].getHash(), "assets/pixel.png"),
                    "the comparison's older endpoint is blue");
            assertArrayEquals(orange, window.service().getFileBytesAtCommit(
                            endpoints[1].getHash(), "assets/pixel.png"),
                    "the comparison's newer endpoint is orange");
            assertArrayEquals(green, Files.readAllBytes(
                            window.workTree().resolve("assets/pixel.png")),
                    "the working copy is a separate green version");

            ThreadGuard.clearViolations();
            FxHarness.run(() -> tab.getRightPanel().showComparison(endpoints[0], endpoints[1]));
            RobotWait.untilOnFx("the comparison image was not listed", () -> {
                ListView<?> files = changedFiles(window);
                return files != null && files.getItems().stream()
                        .map(change -> ((com.jfx.git.core.GitBackend.FileChange) change).path())
                        .anyMatch("assets/pixel.png"::equals);
            });
            FxHarness.run(() -> {
                ListView<com.jfx.git.core.GitBackend.FileChange> files = changedFiles(window);
                int index = -1;
                for (int i = 0; i < files.getItems().size(); i++) {
                    if ("assets/pixel.png".equals(files.getItems().get(i).path())) {
                        index = i;
                        break;
                    }
                }
                assertTrue(index >= 0, "the comparison image must be selectable");
                files.getSelectionModel().select(index);
            });
            waitForPixels(window, List.of(0xff0000ff, 0xffffa500),
                    "an arbitrary comparison uses its from/to blobs, not the green worktree");
            assertTrue(ThreadGuard.productViolations().isEmpty(),
                    () -> "comparison image loading must not run Git on FX: "
                            + ThreadGuard.productViolations());
        }
    }

    @SuppressWarnings("unchecked")
    private static ListView<com.jfx.git.core.GitBackend.FileChange> changedFiles(
            MainWindowFixture window) {
        javafx.scene.Node node = window.scene().lookup(".changed-files-list");
        return node instanceof ListView<?> list
                ? (ListView<com.jfx.git.core.GitBackend.FileChange>) list : null;
    }

    private static void selectStagingPath(MainWindowFixture window, String selector) {
        ListView<?> list = FxHarness.lookupAll(window.tab().getContent(), selector).stream()
                .filter(ListView.class::isInstance).map(ListView.class::cast)
                .findFirst().orElseThrow();
        assertTrue(list.getItems().contains("assets/pixel.png"),
                "the selected staging list contains the image");
        list.getSelectionModel().select(list.getItems().indexOf("assets/pixel.png"));
    }

    private static void waitForPixels(MainWindowFixture window, List<Integer> expected,
                                      String message) {
        RobotWait.untilOnFx(message, () -> imagePixels(window).equals(expected));
        assertEquals(expected, FxHarness.call(() -> imagePixels(window)), message);
    }

    private static List<Integer> imagePixels(MainWindowFixture window) {
        return FxHarness.lookupAll(window.tab().diffViewer(), "image-diff-canvas").stream()
                .filter(ImageView.class::isInstance).map(ImageView.class::cast)
                .filter(image -> image.getImage() != null && image.getImage().getPixelReader() != null)
                .map(image -> image.getImage().getPixelReader().getArgb(0, 0)).toList();
    }

    private static byte[] png(int argb) throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) image.setRGB(x, y, argb);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
