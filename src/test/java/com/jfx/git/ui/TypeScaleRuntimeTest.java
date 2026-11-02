package com.jfx.git.ui;

import com.jfx.git.testsupport.LayoutAssert;
import com.jfx.git.testsupport.MainWindowFixture;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

/**
 * Every piece of text in the main window is on the type scale - measured on the rendered
 * window, not read out of the stylesheets.
 *
 * <p>{@link UiScaleTest} checks the sizes the stylesheets name. It cannot see the sizes
 * nobody named: AtlantaFX derives many of its own from the root in ems, and a node with no
 * size class inherits whatever its parent has. Those are exactly how the window came to
 * show list rows larger than their headings, so this looks at the laid-out text instead.
 */
class TypeScaleRuntimeTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest(name = "{0}px wide, {1}")
    @CsvSource({"1100, DARK", "1480, LIGHT"})
    void theMainWindowUsesOnlyTheScalesSizes(int width, ThemeManager.Theme theme) throws Exception {
        try (MainWindowFixture window = MainWindowFixture.create(tempDir)) {
            window.open(width, 900, theme, false);
            FxHarness.run(() -> LayoutAssert.assertTextSizesOnScale(window.scene().getRoot()));
        }
    }
}
