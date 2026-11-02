package com.jfx.git.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * Two revisions of an image, side by side.
 *
 * <p>Git calls an image binary and refuses to diff it, which leaves a viewer showing
 * "no textual changes" for a file that plainly changed. Showing the two revisions is the
 * only useful answer.
 *
 * <p>Both sides are drawn at the <b>same</b> scale rather than each fitted to its own
 * box, so a resized image reads as resized instead of looking identical.
 */
public class ImageDiffView extends VBox {

    /** The before and after bytes of one path; either may be empty for an add or delete. */
    public record Blobs(byte[] before, byte[] after) {

        public boolean hasBefore() {
            return before != null && before.length > 0;
        }

        public boolean hasAfter() {
            return after != null && after.length > 0;
        }
    }

    private static final double MAX_SIDE = 420;

    public ImageDiffView(String path, Blobs blobs) {
        getStyleClass().add("image-diff");
        setSpacing(12);
        setAlignment(Pos.TOP_CENTER);

        Image before = load(blobs.before());
        Image after = load(blobs.after());

        // One scale for both sides, taken from whichever is larger, so the comparison
        // shows the real difference in dimensions.
        double widest = Math.max(width(before), width(after));
        double tallest = Math.max(height(before), height(after));
        double scale = 1.0;
        if (widest > 0 && tallest > 0) {
            scale = Math.min(1.0, Math.min(MAX_SIDE / widest, MAX_SIDE / tallest));
        }

        HBox sides = new HBox(24,
                side("Before", before, blobs.before(), scale, !blobs.hasBefore()),
                side("After", after, blobs.after(), scale, !blobs.hasAfter()));
        sides.setAlignment(Pos.TOP_CENTER);

        Label summary = new Label(describe(path, before, after, blobs));
        summary.getStyleClass().add("image-diff-summary");
        summary.setWrapText(true);

        getChildren().addAll(sides, summary);
    }

    private static Image load(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Encoded key = new Encoded(bytes);
        synchronized (DECODED) {
            Image cached = DECODED.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Image image;
        try {
            image = new Image(new ByteArrayInputStream(bytes));
            if (image.isError()) {
                return null;
            }
        } catch (Exception e) {
            return null; // not something JavaFX can decode; the caller shows the size only
        }
        synchronized (DECODED) {
            DECODED.put(key, image);
            decodedPixels += pixels(image);
            var eldest = DECODED.entrySet().iterator();
            while ((DECODED.size() > MAX_DECODED || decodedPixels > MAX_DECODED_PIXELS)
                    && DECODED.size() > 1 && eldest.hasNext()) {
                decodedPixels -= pixels(eldest.next().getValue());
                eldest.remove();
            }
        }
        return image;
    }

    /**
     * Decoded images by their encoded bytes, most recently used last.
     *
     * <p>Stepping between the commits of an image's history, or back to a file just left,
     * hands over the same blobs as new arrays, and every one was decoded again - the
     * slowest thing this view does, on the FX thread. Keyed by content, compared in full
     * on a hash match, so a collision can only cost a decode, never show the wrong image.
     */
    private static final java.util.LinkedHashMap<Encoded, Image> DECODED =
            new java.util.LinkedHashMap<>(16, 0.75f, true);
    /** Bounds on the cache: entries, and decoded pixels (four bytes each in memory). */
    private static final int MAX_DECODED = 16;
    private static final long MAX_DECODED_PIXELS = 16L * 1024 * 1024;
    private static long decodedPixels;

    private static long pixels(Image image) {
        return (long) image.getWidth() * (long) image.getHeight();
    }

    /** Forgets every decoded image; for when the viewer drops its file. */
    public static void clearCache() {
        synchronized (DECODED) {
            DECODED.clear();
            decodedPixels = 0;
        }
    }

    /** Encoded image bytes as a cache key, compared by content. */
    private static final class Encoded {
        private final byte[] bytes;
        private final int hash;

        Encoded(byte[] bytes) {
            this.bytes = bytes;
            this.hash = java.util.Arrays.hashCode(bytes);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Encoded that && that.hash == hash
                    && java.util.Arrays.equals(that.bytes, bytes);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static double width(Image image) {
        return image == null ? 0 : image.getWidth();
    }

    private static double height(Image image) {
        return image == null ? 0 : image.getHeight();
    }

    /** One revision: the picture, its dimensions and its size on disk. */
    private static VBox side(String title, Image image, byte[] bytes, double scale, boolean absent) {
        Label heading = new Label(title);
        heading.getStyleClass().add("image-diff-heading");

        Region body;
        if (absent) {
            Label none = new Label(title.equals("Before") ? "Added in this change" : "Deleted");
            none.getStyleClass().add("image-diff-absent");
            body = none;
        } else if (image == null) {
            Label unreadable = new Label("Not a format this viewer can draw");
            unreadable.getStyleClass().add("image-diff-absent");
            body = unreadable;
        } else {
            ImageView view = new ImageView(image);
            view.setPreserveRatio(true);
            view.setFitWidth(image.getWidth() * scale);
            view.getStyleClass().add("image-diff-canvas");
            VBox holder = new VBox(view);
            holder.setAlignment(Pos.CENTER);
            body = holder;
        }

        Label meta = new Label(absent ? "" : dimensions(image) + " · " + humanSize(bytes.length));
        meta.getStyleClass().add("image-diff-meta");

        VBox column = new VBox(6, heading, body, meta);
        column.setAlignment(Pos.TOP_CENTER);
        HBox.setHgrow(column, Priority.ALWAYS);
        return column;
    }

    private static String dimensions(Image image) {
        if (image == null) {
            return "unknown size";
        }
        return (int) image.getWidth() + " × " + (int) image.getHeight();
    }

    /** What actually changed, in the terms someone comparing images cares about. */
    private static String describe(String path, Image before, Image after, Blobs blobs) {
        if (!blobs.hasBefore()) {
            return path + " was added.";
        }
        if (!blobs.hasAfter()) {
            return path + " was deleted.";
        }

        StringBuilder text = new StringBuilder();
        if (before != null && after != null
                && (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight())) {
            text.append("Resized from ").append(dimensions(before))
                    .append(" to ").append(dimensions(after)).append(". ");
        }
        long delta = (long) blobs.after().length - blobs.before().length;
        if (delta == 0) {
            text.append("Same file size.");
        } else {
            text.append(delta > 0 ? "Grew by " : "Shrank by ")
                    .append(humanSize(Math.abs(delta)))
                    .append(" (now ").append(humanSize(blobs.after().length)).append(").");
        }
        return text.toString();
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        return String.format(Locale.ROOT, "%.1f MB", kb / 1024);
    }

    /** Whether {@code path} names something worth trying to draw. */
    public static boolean isImage(String path) {
        if (path == null) {
            return false;
        }
        String name = path.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return switch (name.substring(dot + 1)) {
            case "png", "jpg", "jpeg", "gif", "bmp" -> true;
            default -> false;
        };
    }
}
