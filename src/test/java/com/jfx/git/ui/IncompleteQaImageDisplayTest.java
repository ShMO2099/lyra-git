package com.jfx.git.ui;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The image comparison's shared scale, alpha rendering, and unsupported-format fallback. */
class IncompleteQaImageDisplayTest {
    @AfterEach void closeStages() { FxHarness.closeShownStages(); ImageDiffView.clearCache(); }

    @Test void bothSidesUseOneScaleAndUnsupportedBytesShowAnExplanation() throws Exception {
        byte[] before = png(100, 50);
        byte[] after = png(1000, 500);
        ImageDiffView scaled = FxHarness.call(() -> {
            ImageDiffView view = new ImageDiffView("pixel.png", new ImageDiffView.Blobs(before, after));
            FxHarness.show(view, 900, 700);
            FxHarness.pulse(view);
            return view;
        });
        FxHarness.run(() -> {
            List<ImageView> canvases = FxHarness.lookupAll(scaled, "image-diff-canvas").stream()
                    .filter(ImageView.class::isInstance).map(ImageView.class::cast).toList();
            assertEquals(2, canvases.size());
            double beforeScale = canvases.get(0).getFitWidth() / canvases.get(0).getImage().getWidth();
            double afterScale = canvases.get(1).getFitWidth() / canvases.get(1).getImage().getWidth();
            assertEquals(beforeScale, afterScale, 0.0001,
                    "both revisions share the same scale, so a resized image reads as resized");
            assertEquals(0.0, canvases.get(0).getImage().getPixelReader().getColor(0, 0).getOpacity(), 0.01,
                    "transparent pixels survive decoding");
            assertEquals(420.0, canvases.get(1).getFitWidth(), 0.01,
                    "the larger side is bounded by the viewer's shared maximum");
        });

        ImageDiffView fallback = FxHarness.call(() -> {
            ImageDiffView view = new ImageDiffView("unknown.png",
                    new ImageDiffView.Blobs(new byte[]{1, 2, 3}, new byte[]{4, 5}));
            FxHarness.show(view, 900, 700);
            FxHarness.pulse(view);
            return view;
        });
        FxHarness.run(() -> assertTrue(FxHarness.lookupAll(fallback, "image-diff-absent").stream()
                .filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(label -> label.getText().contains("Not a format this viewer can draw"))));
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            image.setRGB(x, y, x == 0 && y == 0 ? 0x00000000 : 0xff35a0c0);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        return bytes.toByteArray();
    }
}
