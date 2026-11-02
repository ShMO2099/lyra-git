package com.jfx.git.ui.settings;

import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.SettingsSection;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TabPane;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nothing on the Integrations page is cut short, in a Settings tab at the main window's size.
 *
 * <h2>The bug this pins</h2>
 * One row rendered "Not connec…", "Conne…" and "Serv…" — three truncations side by side —
 * and the window clipped its own Close button underneath them. The row divided whatever
 * space was left between a fixed-width name, a status label and two buttons, none of
 * which had a minimum, so everything shrank together and the window never grew.
 */
class IntegrationRowTest {

    private SettingsTab tab;

    @AfterEach
    void closeWindows() {
        FxHarness.run(() -> {
            if (tab != null) tab.dispose();
        });
        FxHarness.closeShownStages();
    }

    private static List<Labeled> labelledParts(Node root) {
        List<Labeled> found = new ArrayList<>();
        walk(root, found);
        return found;
    }

    private static void walk(Node node, List<Labeled> into) {
        if ((node instanceof Label || node instanceof Button) && node instanceof Labeled labeled
                && labeled.getText() != null && !labeled.getText().isBlank()) {
            into.add(labeled);
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                walk(child, into);
            }
        }
    }

    /** Shows the real page in a Settings tab at the main window's size, and returns the page. */
    private Node shown() {
        return FxHarness.call(() -> {
            tab = new SettingsTab();
            TabPane pane = SettingsTabs.show(tab);
            SettingsTabs.at(pane, tab, SettingsSection.INTEGRATIONS);
            return tab.page(SettingsSection.INTEGRATIONS);
        });
    }

    @Test
    void noLabelOrButtonInTheWindowIsTruncated() {
        Node page = shown();
        FxHarness.run(() -> {
            List<Labeled> parts = labelledParts(page);
            assertFalse(parts.isEmpty(), "the page should have rendered some content");

            List<String> clipped = new ArrayList<>();
            for (Labeled part : parts) {
                // Wrapping labels are skipped, and this is a known hole rather than an
                // oversight. A paragraph fails by height, not width: given less room than
                // its wrapped text needs, a Label ellipsizes the last visible line, which
                // is how the header here reached a user as "...you are prompted for a ...".
                //
                // A height check was tried and removed. Under this harness a wrapped
                // Label reports prefHeight(itsOwnWidth) as 16px - one line - for text that
                // plainly needs several, so the assertion could never fire. Keeping it
                // would have been worse than this comment: a test that cannot fail reads
                // as coverage. Vertical truncation of a paragraph is currently caught by
                // looking at the window, and the defence against it is to not put six
                // sentences of documentation in a dialog header.
                if (part.isWrapText()) {
                    continue;
                }
                if (part.getWidth() + 0.5 < part.prefWidth(-1)) {
                    clipped.add("\"" + part.getText() + "\" given " + part.getWidth()
                            + "px, needs " + part.prefWidth(-1) + "px");
                }
            }
            assertTrue(clipped.isEmpty(), () -> "nothing may be cut short: " + clipped);
        });
    }

    /**
     * The status text is the part that lost first, so it is worth naming.
     *
     * <p>"Not connected" is thirteen characters and was arriving as "Not connec…".
     */
    @Test
    void theConnectionStatusIsReadableInFull() {
        Node page = shown();
        FxHarness.run(() -> {
            Labeled status = labelledParts(page).stream()
                    .filter(l -> l.getText().toLowerCase(java.util.Locale.ROOT)
                            .contains("connected"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no connection status on screen"));
            assertTrue(status.getWidth() + 0.5 >= status.prefWidth(-1),
                    () -> "\"" + status.getText() + "\" is being ellipsized");
        });
    }

    /** Every card, and the switch under them, is on screen without scrolling at the window's size. */
    @Test
    void everyControlFitsInsideTheWindow() {
        Node page = shown();
        FxHarness.run(() -> {
            double height = page.getScene().getHeight();
            for (Labeled part : labelledParts(page)) {
                javafx.geometry.Bounds bounds =
                        part.localToScene(part.getBoundsInLocal());
                assertTrue(bounds.getMaxY() <= height + 0.5,
                        () -> "\"" + part.getText() + "\" sits below the window's bottom edge ("
                                + bounds.getMaxY() + " > " + height + ")");
            }
        });
    }
}
