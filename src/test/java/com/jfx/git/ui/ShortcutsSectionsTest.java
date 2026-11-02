package com.jfx.git.ui;

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The cheat-sheet reads categories straight off the map; this pins that plumbing. */
class ShortcutsSectionsTest {

    @Test
    void sectionsFileSubsequentBindingsUntilTheNextMarker() {
        var shortcuts = new Shortcuts();
        shortcuts.section("Git actions")
                .add(KeyCode.B, "New branch", () -> { })
                .add(KeyCode.L, "Fetch all", () -> { });
        shortcuts.add(KeyCode.P, "Command palette", () -> { });

        var bindings = shortcuts.bindings();
        assertEquals(3, bindings.size());
        assertEquals("Git actions", bindings.get(0).category());
        assertEquals("Git actions", bindings.get(1).category());
        // A section holds until the next marker - the map's layout mirrors the sheet's.
        assertEquals("Git actions", bindings.get(2).category());

        // Before any marker at all, bindings still land somewhere readable, not null.
        var fresh = new Shortcuts().add(KeyCode.P, "Palette", () -> { });
        assertEquals("Other", fresh.bindings().get(0).category());
    }

    @Test
    void everyBindingCarriesADisplayableKey() {
        var shortcuts = new Shortcuts().section("Nav").add(KeyCode.J, "Older", () -> { });
        assertFalse(shortcuts.bindings().get(0).display().isBlank(),
                "the cheat-sheet has nothing to show without a key name");
    }
}
