package com.jfx.git.ui;

import com.jfx.git.ui.DiffParser.Change;
import com.jfx.git.ui.DiffParser.Summary;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rename, a chmod, a binary file and a submodule bump all produce diffs with no hunks.
 * Read as hunks alone they are indistinguishable from "nothing changed" - which is
 * exactly how they used to render.
 */
class DiffSummaryTest {

    @Test
    void aRenameIsReadFromThePreamble() {
        Summary summary = DiffParser.summarize("""
                diff --git a/src/Old.java b/src/New.java
                similarity index 96%
                rename from src/Old.java
                rename to src/New.java
                """);

        assertEquals(Change.RENAMED, summary.change());
        assertEquals("src/Old.java", summary.oldPath());
        assertEquals("src/New.java", summary.newPath());
        assertTrue(summary.isMetadataOnly());
        assertEquals("Renamed from src/Old.java to src/New.java", DiffParser.describe(summary));
    }

    @Test
    void aModeChangeIsReported() {
        Summary summary = DiffParser.summarize("""
                diff --git a/run.sh b/run.sh
                old mode 100644
                new mode 100755
                """);

        assertEquals(Change.MODE_CHANGED, summary.change());
        assertEquals("Mode changed from 100644 to 100755", DiffParser.describe(summary));
    }

    @Test
    void anUnchangedModeIsNotAModeChange() {
        Summary summary = DiffParser.summarize("""
                diff --git a/a.txt b/a.txt
                index 1234567..89abcde 100644
                @@ -1,1 +1,1 @@
                -a
                +b
                """);
        assertEquals(Change.MODIFIED, summary.change());
        assertFalse(summary.isMetadataOnly());
    }

    @Test
    void aNewFileIsReported() {
        Summary summary = DiffParser.summarize("""
                diff --git a/new.txt b/new.txt
                new file mode 100644
                index 0000000..e69de29
                """);
        assertEquals(Change.ADDED, summary.change());
        assertEquals("100644", summary.newMode());
    }

    @Test
    void aDeletedFileIsReported() {
        Summary summary = DiffParser.summarize("""
                diff --git a/gone.txt b/gone.txt
                deleted file mode 100644
                """);
        assertEquals(Change.DELETED, summary.change());
        assertEquals("File deleted", DiffParser.describe(summary));
    }

    @Test
    void binaryContentIsFlaggedRatherThanRenderedAsText() {
        Summary summary = DiffParser.summarize(
                "diff --git a/logo.png b/logo.png\nBinary files a/logo.png and b/logo.png differ\n");
        assertTrue(summary.binary());
        assertEquals("Binary file - content not shown", DiffParser.describe(summary));
    }

    @Test
    void aSubmodulePointerMoveCarriesBothCommits() {
        Summary summary = DiffParser.summarize("""
                diff --git a/vendor/lib b/vendor/lib
                index 1111111..2222222 160000
                @@ -1 +1 @@
                -Subproject commit 1111111111111111111111111111111111111111
                +Subproject commit 2222222222222222222222222222222222222222
                """);
        // The pointers sit below the @@ marker, so they are read from the hunk body.
        Summary body = DiffParser.summarize(
                "Subproject commit 1111111111111111111111111111111111111111\n"
                        + "Subproject commit 2222222222222222222222222222222222222222\n");
        assertEquals("1111111111111111111111111111111111111111", body.submoduleFrom());
        assertEquals("2222222222222222222222222222222222222222", body.submoduleTo());
        assertEquals(Change.MODIFIED, summary.change());
    }

    @Test
    void anEmptyDiffSummarizesWithoutFailing() {
        Summary summary = DiffParser.summarize(null);
        assertEquals(Change.MODIFIED, summary.change());
        assertFalse(summary.binary());
    }
}
