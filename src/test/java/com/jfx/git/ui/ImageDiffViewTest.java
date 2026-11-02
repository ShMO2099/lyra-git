package com.jfx.git.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageDiffViewTest {

    @Test
    void imagesAreRecognisedByExtension() {
        assertTrue(ImageDiffView.isImage("assets/logo.png"));
        assertTrue(ImageDiffView.isImage("A/B/Photo.JPEG"));
        assertTrue(ImageDiffView.isImage("icon.gif"));
        assertFalse(ImageDiffView.isImage("src/Main.java"));
        assertFalse(ImageDiffView.isImage("Makefile"), "no extension is not an image");
        assertFalse(ImageDiffView.isImage(null));
        // A vector image is text and diffs perfectly well as text.
        assertFalse(ImageDiffView.isImage("logo.svg"));
    }

    @Test
    void sidesReportWhetherTheyExist() {
        var added = new ImageDiffView.Blobs(new byte[0], new byte[]{1, 2});
        assertFalse(added.hasBefore());
        assertTrue(added.hasAfter());

        var deleted = new ImageDiffView.Blobs(new byte[]{1}, null);
        assertTrue(deleted.hasBefore());
        assertFalse(deleted.hasAfter());
    }

    @Test
    void sizesAreReadableAtAGlance() {
        assertEquals("512 B", ImageDiffView.humanSize(512));
        assertEquals("1.0 KB", ImageDiffView.humanSize(1024));
        assertEquals("1.5 MB", ImageDiffView.humanSize(1024L * 1024 * 3 / 2));
    }
}
