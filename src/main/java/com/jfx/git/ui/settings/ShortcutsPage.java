package com.jfx.git.ui.settings;

import com.jfx.git.ui.Shortcuts;
import com.jfx.git.ui.UiScale;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import static com.jfx.git.ui.settings.SettingsLayout.page;
import static com.jfx.git.ui.settings.SettingsLayout.section;

/**
 * The keyboard cheat-sheet: every live binding, grouped by section - the Keyboard
 * shortcuts section of the Settings tab.
 *
 * <p>Reads straight out of {@link Shortcuts#bindings()} each time it is built, so it can
 * never list a key the application stopped honouring, or miss one it gained. It was a
 * dialog of its own; as a page it sits beside the settings it is most often opened
 * alongside, and stays open while the user tries the keys it lists.
 */
final class ShortcutsPage extends VBox {

    /** Matches on both halves of a row: "commit" and "Ctrl+Enter" are both things a user arrives holding. */
    private final TextField filter = new TextField();

    ShortcutsPage(Supplier<List<Shortcuts.Binding>> bindings) {
        VBox rows = new VBox(UiScale.SNUG);

        // The sheet is long enough that finding one binding means reading past forty
        // others, which is the point at which a reference stops being usable as one.
        filter.setPromptText("Filter shortcuts…");
        filter.getStyleClass().add("file-filter-field");
        filter.setMaxWidth(360);
        Label noMatches = new Label("No shortcut matches that.");
        noMatches.getStyleClass().add("t-caption");
        noMatches.setVisible(false);
        noMatches.setManaged(false);

        Runnable rebuild = () -> {
            String query = filter.getText() == null ? "" : filter.getText().strip().toLowerCase(Locale.ROOT);
            Map<String, List<Shortcuts.Binding>> groups = new LinkedHashMap<>();
            for (Shortcuts.Binding binding : bindings.get()) {
                if (!query.isEmpty()
                        && !binding.description().toLowerCase(Locale.ROOT).contains(query)
                        && !binding.display().toLowerCase(Locale.ROOT).contains(query)) {
                    continue;
                }
                groups.computeIfAbsent(binding.category(), k -> new ArrayList<>()).add(binding);
            }

            rows.getChildren().clear();
            for (Map.Entry<String, List<Shortcuts.Binding>> entry : groups.entrySet()) {
                rows.getChildren().add(section(entry.getKey()));
                for (Shortcuts.Binding binding : entry.getValue()) {
                    Label key = new Label(binding.display());
                    key.getStyleClass().add("shortcut-key");
                    // A floor, so the descriptions line up; a longer chord still gets its width.
                    key.setMinWidth(140);
                    Label what = new Label(binding.description());
                    what.getStyleClass().add("t-body");
                    HBox row = new HBox(UiScale.GAP, key, what);
                    row.setAlignment(Pos.CENTER_LEFT);
                    rows.getChildren().add(row);
                }
            }
            boolean nothing = groups.isEmpty();
            noMatches.setVisible(nothing);
            noMatches.setManaged(nothing);
        };
        filter.textProperty().addListener((o, was, now) -> rebuild.run());
        rebuild.run();

        VBox content = page("Keyboard shortcuts");
        content.getChildren().addAll(filter, noMatches, rows);
        getChildren().add(content);
    }

    /** Puts the caret in the filter: the filter is the reason the sheet is opened by its own key. */
    void focusFilter() {
        filter.requestFocus();
    }
}
