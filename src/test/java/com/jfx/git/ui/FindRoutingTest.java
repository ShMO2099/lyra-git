package com.jfx.git.ui;

import com.jfx.git.testsupport.MainWindowFixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ctrl+F means "find in what I am looking at". With a file open that is the file; with
 * only the history on screen it is the graph's search box, as it always was.
 */
class FindRoutingTest {

    @TempDir
    Path tempDir;

    @Test
    void ctrlFSearchesTheGraphUntilAFileIsOpenThenTheFile() throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(1100, 700, ThemeManager.Theme.DARK, true);
            RepoTab tab = window.tab();
            FxHarness.run(() -> {
                tab.find(false);
                assertTrue(tab.searchField().isFocused()
                                || window.scene().getFocusOwner() == tab.searchField(),
                        "nothing open: the graph's search box");
                tab.find(true);
                assertFalse(tab.diffViewer().findBar().isVisible(),
                        "nothing to replace in with no file open");

                tab.diffViewer().showFileContent("README.md", "# Demo\n\nfind me\n");
            });
            window.layOut();
            FxHarness.run(() -> {
                DiffViewer viewer = tab.diffViewer();
                assertTrue(viewer.isVisible(), "the file is open");
                tab.find(false);
                assertTrue(viewer.findBar().isFinding(), "Ctrl+F now finds in the file");

                tab.closeDiff();
                assertFalse(viewer.findBar().isVisible(), "Esc closes the find bar first");
                assertTrue(viewer.isVisible(), "and leaves the file open");

                tab.goToLine();
                assertTrue(viewer.findBar().isVisible() && !viewer.findBar().isFinding(),
                        "Ctrl+G goes to a line of the file");
            });
        }
    }
}
