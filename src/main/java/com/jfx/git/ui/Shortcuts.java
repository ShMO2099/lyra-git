package com.jfx.git.ui;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.GenericStyledArea;

import java.util.ArrayList;
import java.util.List;

/**
 * The application's keyboard map.
 *
 * <h2>Why bindings live here rather than on the controls</h2>
 * A shortcut that only works while a particular pane has focus is not a shortcut. These
 * are installed once on the {@link Scene} and dispatched to whichever repository tab is
 * in front, so {@code Ctrl+Enter} commits whether the caret is in the message box, the
 * graph, or nowhere at all.
 *
 * <p>Accelerators registered through {@code Scene.getAccelerators()} would fire even while
 * the user is typing a branch name into a dialog's text field. These are filtered instead,
 * so a binding without a modifier — or one that would shadow ordinary text entry — can be
 * skipped while a text control has focus. {@code Shortcut} is JavaFX's name for the
 * platform's command key, which is Ctrl on every platform this ships on.
 */
public final class Shortcuts {

    /**
     * One binding: a key combination, what it does, how to describe it, and whether an
     * editable text control's own meaning for the same keys should win instead.
     */
    public record Binding(KeyCombination keys, String description, Runnable action,
                          boolean yieldsToEditableText, String category) {
        public Binding(KeyCombination keys, String description, Runnable action,
                       boolean yieldsToEditableText) {
            this(keys, description, action, yieldsToEditableText, "Other");
        }

        public String display() {
            return keys.getDisplayText();
        }
    }

    private final List<Binding> bindings = new ArrayList<>();

    /** The category every subsequent {@code add} files itself under. */
    private String currentCategory = "Other";

    public Shortcuts() {}

    /**
     * Names the section subsequent bindings belong to — the cheat-sheet groups by
     * these. A marker between adds rather than a parameter on each, because the map
     * reads best when its layout mirrors the sheet's.
     */
    public Shortcuts section(String name) {
        currentCategory = name;
        return this;
    }

    public List<Binding> bindings() {
        return List.copyOf(bindings);
    }

    public Shortcuts add(KeyCode code, String description, Runnable action) {
        return add(new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN), description, action);
    }

    public Shortcuts addShift(KeyCode code, String description, Runnable action) {
        return add(new KeyCodeCombination(code,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN), description, action);
    }

    public Shortcuts add(KeyCombination keys, String description, Runnable action) {
        bindings.add(new Binding(keys, description, action, false, currentCategory));
        return this;
    }

    /**
     * Like {@link #add(KeyCode, String, Runnable)}, but stands aside when an editable
     * text control has focus - for a shortcut, like undo/redo, that such a control
     * already has its own meaning for. Without this, e.g. Ctrl+Z while fixing a typo in
     * the commit message box or the built-in editor undoes a git operation instead,
     * since the control never even sees the keystroke: {@link #installOn} uses an event
     * filter, which runs before the focused control does.
     */
    public Shortcuts addTextSensitive(KeyCode code, String description, Runnable action) {
        bindings.add(new Binding(
                new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN), description, action,
                true, currentCategory));
        return this;
    }

    /** Like {@link #addTextSensitive}, with Shift held as well. */
    public Shortcuts addTextSensitiveShift(KeyCode code, String description, Runnable action) {
        bindings.add(new Binding(new KeyCodeCombination(code,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN), description, action,
                true, currentCategory));
        return this;
    }

    /**
     * Installs every binding on {@code scene}.
     *
     * <p>Uses an event filter rather than accelerators so the handler runs before the
     * focused control sees the key, and so {@link #shouldSkip} can decline.
     */
    public void installOn(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            for (Binding binding : bindings) {
                if (binding.keys().match(event)) {
                    if (shouldSkip(scene, binding)) return;
                    event.consume();
                    binding.action().run();
                    return;
                }
            }
        });
    }

    /**
     * Whether a binding should stand aside for the focused control.
     *
     * <p>No-modifier bindings stand aside for any text control, editable or not: those
     * would otherwise swallow ordinary characters as soon as the user typed into a
     * search box. Everything in this map is modifier-based today, so that branch alone
     * skips nothing - it is here so adding a bare function-key binding later cannot
     * silently break text entry.
     *
     * <p>{@link Binding#yieldsToEditableText}-marked bindings (undo/redo) stand aside
     * too, but only for a control that is both a text control and actually editable -
     * so Ctrl+Z still drives git-history undo while merely viewing a read-only diff,
     * and only defers to the control's own undo/redo while something is genuinely
     * being typed into.
     */
    private static boolean shouldSkip(Scene scene, Binding binding) {
        if (binding.keys() instanceof KeyCodeCombination combo
                && combo.getShortcut() == KeyCombination.ModifierValue.UP
                && combo.getAlt() == KeyCombination.ModifierValue.UP
                && combo.getControl() == KeyCombination.ModifierValue.UP
                && combo.getShift() == KeyCombination.ModifierValue.UP) {
            // Bare-letter navigation (J/K/H/L) must not swallow typing: a text field
            // stands aside always, and an editable RichTextFX area (the built-in
            // editor) too. A read-only diff area is not typing, so J still navigates.
            Node focus = scene.getFocusOwner();
            return focus instanceof TextInputControl || isEditableTextControl(focus);
        }
        if (binding.yieldsToEditableText()) {
            return isEditableTextControl(scene.getFocusOwner());
        }
        return false;
    }

    /**
     * Whether {@code node} is a text control currently accepting typed input - either
     * the standard {@link TextInputControl} family, or a RichTextFX area (the built-in
     * editor is one, and is not a {@code TextInputControl}).
     */
    private static boolean isEditableTextControl(Node node) {
        if (node instanceof TextInputControl text) {
            return text.isEditable();
        }
        if (node instanceof GenericStyledArea<?, ?, ?> area) {
            return area.isEditable();
        }
        return false;
    }
}
