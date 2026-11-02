package com.jfx.git.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opening a folder, as far as it can be checked without one actually being opened.
 *
 * <p>"Open drafts folder" in Preferences handed a directory to {@link FileOpener#open},
 * which is for files and refuses anything else - so the button did nothing, with the
 * refusal thrown away. These pin both halves: that {@code open} still refuses a folder,
 * and that the folder route gets one that exists. The launch itself is the platform's,
 * and is not exercised: a test run should not leave file manager windows behind it.
 */
class FileOpenerFolderTest {

    @TempDir
    Path dir;

    @Test
    void theFileOpenerRefusesAFolder() {
        assertFalse(FileOpener.open(dir.toFile()).isOk(), "open is for files");
        assertFalse(FileOpener.openPreferred(dir.toFile()).isOk()
                        && AppPreferences.editorMode() != AppPreferences.EditorMode.CUSTOM,
                "which is why a folder needs a route of its own");
    }

    @Test
    void aFolderThatIsNotThereYetIsMadeBeforeItIsShown() {
        File missing = dir.resolve("made/on/demand").toFile();

        assertNull(FileOpener.ensureFolder(missing));

        assertTrue(missing.isDirectory());
        assertNull(FileOpener.ensureFolder(missing), "and one that is there is simply there");
    }

    @Test
    void whatCannotBeAFolderIsAFailureThatSaysSo() throws Exception {
        Path file = Files.writeString(dir.resolve("a-file"), "not a folder");

        assertNotNull(FileOpener.ensureFolder(file.toFile()), "a file is in the way");
        assertNotNull(FileOpener.ensureFolder(null));
        assertFalse(FileOpener.openFolder(null).isOk());
        assertFalse(FileOpener.openFolder(file.toFile()).isOk());
    }
}
