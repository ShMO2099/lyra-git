package com.jfx.git.ui;

import com.jfx.git.core.GitService;

import javafx.scene.Node;
import javafx.scene.image.ImageView;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E22: File History compares the selected commit's image with its first-parent image. */
class ManualQaHistoricalImageComparisonTest {

    private static final String IMAGE_PATH = "assets/pixel.png";

    @TempDir
    Path repository;

    private GitService service;

    @BeforeAll
    static void startToolkit() {
        FxHarness.start();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (service != null) {
            service.close();
        }
        FxHarness.closeShownStages();
        ImageDiffView.clearCache();
    }

    @Test
    void selectedHistoryCommitShowsItsParentImageAndItsOwnImage() throws Exception {
        try (Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Manual QA");
            config.setString("user", null, "email", "qa@example.test");
            config.save();
        }
        service = new GitService();
        service.openRepository(repository.toFile());

        byte[] parentPng = png(Color.BLUE);
        byte[] selectedPng = png(Color.ORANGE);
        Path imageFile = repository.resolve(IMAGE_PATH);
        Files.createDirectories(imageFile.getParent());
        Files.write(imageFile, parentPng);
        commitImage("blue parent image");
        Files.write(imageFile, selectedPng);
        commitImage("orange selected image");
        // A third working-tree color ensures File History reads the selected commit's
        // blob rather than accidentally displaying the current file on both sides.
        Files.write(imageFile, png(Color.GREEN));

        FileHistoryView history = FxHarness.call(() -> {
            FileHistoryView view = new FileHistoryView(OperationRunner.of(service, new StatusBar(), () -> { }), IMAGE_PATH,
                    false, () -> { }, ignored -> { });
            FxHarness.showRegistered(view, 1100, 720);
            return view;
        });

        RobotWait.untilOnFx("File History did not render both historical image revisions", () -> {
            List<ImageView> images = imageViews(history);
            return images.size() == 2 && images.stream()
                    .allMatch(image -> image.getImage() != null && image.getImage().getPixelReader() != null);
        });

        FxHarness.run(() -> {
            List<ImageView> images = imageViews(history);
            assertEquals(2, images.size());
            assertEquals(Color.BLUE.getRGB(), images.get(0).getImage().getPixelReader().getArgb(0, 0),
                    "Before is the selected commit's parent blob");
            assertEquals(Color.ORANGE.getRGB(), images.get(1).getImage().getPixelReader().getArgb(0, 0),
                    "After is the selected commit's blob, not the green working copy");
        });
    }

    private void commitImage(String message) {
        assertTrue(service.stageFile(IMAGE_PATH).isOk());
        assertTrue(service.commit(message, false).isOk());
    }

    private static List<ImageView> imageViews(Node root) {
        return FxHarness.lookupAll(root, "image-diff-canvas").stream()
                .filter(ImageView.class::isInstance)
                .map(ImageView.class::cast)
                .toList();
    }

    private static byte[] png(Color color) throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", encoded));
        byte[] bytes = encoded.toByteArray();
        assertTrue(bytes.length > 0);
        return bytes;
    }
}
