package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.UiScale;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The pieces a dialog's body is built from: fields, option cards, toggle rows, framed
 * lists.
 *
 * <h2>Why these and not the form grid</h2>
 * {@link JfxDialog#form()} sets labels in a 152px column beside their controls. In a
 * settings page that is right; in a 420px dialog it put the field's left edge at x=264 and
 * left three-word labels floating a third of the window away from what they label. A
 * dialog stacks instead: the label above, the control at the full width, a hint under it
 * for what the user must know, and - in the same place, in red - what is wrong with it.
 *
 * <p>Options get the same treatment. A radio list with one description underneath, which
 * changed as you clicked, became cards that each say what choosing them does. A list
 * became a frame with rows that have an icon, a name, a second line and tags, instead of
 * "ada/payments · private" typed into a bare ListView.
 */
public final class DialogParts {

    private DialogParts() {
    }

    private static final PseudoClass DANGER = PseudoClass.getPseudoClass("danger");

    // ------------------------------------------------------------------
    // Fields
    // ------------------------------------------------------------------

    /** A labelled control; see {@link Field}. */
    public static Field field(String label, Node control) {
        return new Field(label, control);
    }

    /**
     * A control with its label above it, an optional hint below, and the place its error
     * goes.
     *
     * <p>Placeholders are for examples only. Primer hides a field's placeholder as soon as
     * the field takes focus, and dialogs focus their first field, so "Optional - leave
     * blank for a lightweight tag" was never read; "(optional)" goes on the label and the
     * explanation in the hint.
     */
    public static final class Field {

        private final VBox root = new VBox(UiScale.SNUG);
        private final HBox labelRow = new HBox(UiScale.SNUG);
        private final Node control;
        private final HBox controlRow;
        private final Label hint = new Label();
        private final Label error = new Label();

        private Field(String label, Node control) {
            this.control = control;
            Label name = new Label(label);
            name.getStyleClass().add("t-label");
            // The control itself, or the first input inside a wrapped one (a secret field's
            // pair, a row of two pickers), so assistive technology reads the label with it.
            javafx.scene.control.Control labelled = firstControl(control);
            if (labelled != null) {
                name.setLabelFor(labelled);
            }
            labelRow.getChildren().add(name);
            labelRow.setAlignment(Pos.BASELINE_LEFT);

            controlRow = new HBox(UiScale.STEP, control);
            controlRow.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(control, Priority.ALWAYS);
            if (control instanceof Region region) {
                region.setMaxWidth(Double.MAX_VALUE);
            }

            hint.getStyleClass().add("t-caption");
            hint.setWrapText(true);
            hint.setMinHeight(Region.USE_PREF_SIZE);
            hint.setManaged(false);
            hint.setVisible(false);

            error.getStyleClass().add("jfx-field-error");
            error.setWrapText(true);
            error.setMinHeight(Region.USE_PREF_SIZE);
            error.setManaged(false);
            error.setVisible(false);

            root.getStyleClass().add("jfx-field");
            root.getChildren().addAll(labelRow, controlRow, hint, error);
        }

        /** What the user must know about the field, under it, for as long as the dialog is open. */
        public Field hint(String text) {
            boolean any = text != null && !text.isBlank();
            String before = hint.getText();
            hint.setText(any ? text : "");
            hint.setManaged(any);
            hint.setVisible(any);
            // A hint that follows the input - "Creates the folder storefront" - can change
            // its line count; measured again, as an error is, so it is never squeezed.
            if (!java.util.Objects.equals(before, hint.getText())) {
                JfxDialog.refit(root);
            }
            return this;
        }

        /** Trailing controls at the top of a tall control (a text area) rather than its middle. */
        public Field trailingAtTop() {
            controlRow.setAlignment(Pos.TOP_LEFT);
            return this;
        }

        /** "(optional)" beside the label, muted. */
        public Field optional() {
            Label optional = new Label("(optional)");
            optional.getStyleClass().add("jfx-field-optional");
            labelRow.getChildren().add(optional);
            return this;
        }

        /** Controls beside the field, at its height: "Browse…", a reveal toggle. */
        public Field trailing(Node... nodes) {
            controlRow.getChildren().addAll(nodes);
            return this;
        }

        /**
         * What is wrong, under the field in red, with the field outlined in red too; null
         * clears both. The dialog is measured again, so the line is never squeezed in.
         */
        public Field error(String message) {
            boolean any = message != null && !message.isBlank();
            error.setText(any ? message : "");
            if (error.isManaged() != any) {
                error.setManaged(any);
                error.setVisible(any);
                JfxDialog.refit(root);
            }
            outline(control, any);
            return this;
        }

        /**
         * The red outline on the control and on every input inside it: a wrapped control - a
         * secret field's pair in a StackPane, two pickers in a row - is not itself drawn
         * with a border, so marking only it showed nothing.
         */
        private static void outline(Node node, boolean on) {
            node.pseudoClassStateChanged(DANGER, on);
            if (node instanceof javafx.scene.Parent parent && !(node instanceof javafx.scene.control.Control)) {
                for (Node child : parent.getChildrenUnmodifiable()) {
                    outline(child, on);
                }
            }
        }

        /** The error now showing, or empty. */
        public String error() {
            return error.getText();
        }

        public Node node() {
            return root;
        }

        @SuppressWarnings("unchecked")
        public <C extends Node> C control() {
            return (C) control;
        }
    }

    /** The first {@link javafx.scene.control.Control} at or under {@code node}, or null. */
    private static javafx.scene.control.Control firstControl(Node node) {
        if (node instanceof javafx.scene.control.Control control) {
            return control;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                javafx.scene.control.Control found = firstControl(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Two fields side by side, each half the width: Branch and Clone depth, Feature and
     * Release prefixes, a passphrase and its repeat. A grid with two equal columns rather
     * than a row of two growing boxes, which would split the room by what each asked for.
     */
    public static javafx.scene.layout.GridPane pair(Field left, Field right) {
        javafx.scene.layout.GridPane row = new javafx.scene.layout.GridPane();
        row.setHgap(UiScale.GAP);
        javafx.scene.layout.ColumnConstraints half = new javafx.scene.layout.ColumnConstraints();
        half.setPercentWidth(50);
        half.setHgrow(Priority.ALWAYS);
        half.setFillWidth(true);
        row.getColumnConstraints().addAll(half, half);
        row.add(left.node(), 0, 0);
        row.add(right.node(), 1, 0);
        javafx.scene.layout.GridPane.setValignment(left.node(), javafx.geometry.VPos.TOP);
        javafx.scene.layout.GridPane.setValignment(right.node(), javafx.geometry.VPos.TOP);
        return row;
    }

    /**
     * A secret's field with a toggle that shows what was typed: a password field and a
     * text field sharing one text, one of them on screen. A pasted token or a long
     * passphrase cannot be checked as a row of dots.
     *
     * <p>Put {@link #node} in a {@link Field} with {@link #reveal} as its trailing control;
     * {@link Field#error} outlines whichever of the two is showing.
     */
    public static final class Secret {

        public final javafx.scene.control.PasswordField hidden = new javafx.scene.control.PasswordField();
        public final javafx.scene.control.TextField shown = new javafx.scene.control.TextField();
        public final javafx.scene.layout.StackPane node = new javafx.scene.layout.StackPane(hidden, shown);
        public final javafx.scene.control.ToggleButton reveal;

        /** @param what the secret in a few words, for the toggle's text: "password", "token" */
        public Secret(String what) {
            shown.textProperty().bindBidirectional(hidden.textProperty());
            shown.setVisible(false);
            hidden.setMaxWidth(Double.MAX_VALUE);
            shown.setMaxWidth(Double.MAX_VALUE);
            hidden.setAccessibleText(what);
            shown.setAccessibleText(what);
            reveal = com.jfx.git.ui.Icons.toggle(com.jfx.git.ui.Icons.eyeOpen(), "Show the " + what, "Show the " + what);
            reveal.selectedProperty().addListener((obs, was, show) -> {
                javafx.scene.control.TextField from = show ? hidden : shown;
                javafx.scene.control.TextField to = show ? shown : hidden;
                int caret = from.getCaretPosition();
                to.setVisible(true);
                from.setVisible(false);
                reveal.setGraphic(show ? com.jfx.git.ui.Icons.eyeClosed() : com.jfx.git.ui.Icons.eyeOpen());
                String says = (show ? "Hide the " : "Show the ") + what;
                reveal.setAccessibleText(says);
                if (reveal.getTooltip() != null) reveal.getTooltip().setText(says);
                to.requestFocus();
                to.positionCaret(caret);
            });
        }

        public String text() {
            return hidden.getText() == null ? "" : hidden.getText();
        }

        /** The red outline on both fields, for a caller not going through {@link Field#error}. */
        public void danger(boolean on) {
            hidden.pseudoClassStateChanged(DANGER, on);
            shown.pseudoClassStateChanged(DANGER, on);
        }

        /** {@code field.error(message)}; kept for callers written before Field outlined wrapped controls. */
        public void error(Field field, String message) {
            field.error(message);
        }
    }

    /** Fields and other blocks, one under another, 12px apart. */
    public static VBox stack(Object... parts) {
        VBox stack = new VBox(UiScale.GAP);
        stack.getStyleClass().add("jfx-stack");
        for (Object part : parts) {
            if (part == null) {
                continue;
            }
            stack.getChildren().add(part instanceof Field field ? field.node() : (Node) part);
        }
        return stack;
    }

    /** A heading between groups of fields: "Starter files", "Prefixes". */
    public static Label section(String title) {
        Label heading = new Label(title);
        heading.getStyleClass().addAll("t-eyebrow", "jfx-section");
        return heading;
    }

    /**
     * A shortcut and what it does, for a workspace's toolbar: the keys as chips in the
     * monospace face, the word after them as a caption - "[Alt] [↑] [↓] move".
     */
    public static HBox keyHint(String does, String... keys) {
        HBox hint = new HBox(UiScale.TIGHT);
        hint.getStyleClass().add("jfx-key-hint");
        hint.setAlignment(Pos.CENTER_LEFT);
        for (String key : keys) {
            Label chip = new Label(key);
            chip.getStyleClass().add("jfx-kbd");
            chip.setMinWidth(Region.USE_PREF_SIZE);
            hint.getChildren().add(chip);
        }
        Label word = new Label(does);
        word.getStyleClass().add("t-caption");
        word.setMinWidth(Region.USE_PREF_SIZE);
        word.setPadding(new Insets(0, 0, 0, UiScale.TIGHT));
        hint.getChildren().add(word);
        return hint;
    }

    /** A checkbox and, under its label, what ticking it does. */
    public static VBox toggle(CheckBox box, String description) {
        VBox row = new VBox(UiScale.TIGHT, box);
        row.getStyleClass().add("jfx-toggle-row");
        if (description != null && !description.isBlank()) {
            Label says = new Label(description);
            says.getStyleClass().add("t-caption");
            says.setWrapText(true);
            says.setMinHeight(Region.USE_PREF_SIZE);
            row.getChildren().add(says);
        }
        return row;
    }

    // ------------------------------------------------------------------
    // Option cards
    // ------------------------------------------------------------------

    /**
     * A choice between a few options, each a card with its name and what choosing it does;
     * the whole card is the target, and the chosen one is outlined in the accent.
     */
    public static final class OptionCards<T> {

        private final VBox root = new VBox(UiScale.STEP - 2);
        private final ToggleGroup group = new ToggleGroup();
        private final ObjectProperty<T> selected = new SimpleObjectProperty<>();
        private final List<RadioButton> radios = new ArrayList<>();

        public OptionCards(List<T> options, Function<T, String> text, Function<T, String> description) {
            root.getStyleClass().add("jfx-option-cards");
            for (T option : options) {
                RadioButton radio = new RadioButton(text.apply(option));
                // An option named by a path wraps rather than losing its end.
                radio.setWrapText(true);
                radio.setMinHeight(Region.USE_PREF_SIZE);
                radio.setToggleGroup(group);
                radio.setUserData(option);
                radio.getStyleClass().add("jfx-option-radio");
                VBox card = new VBox(UiScale.TIGHT, radio);
                card.getStyleClass().add("jfx-option-card");
                String says = description == null ? null : description.apply(option);
                if (says != null && !says.isBlank()) {
                    Label line = new Label(says);
                    line.getStyleClass().add("t-caption");
                    line.setWrapText(true);
                    line.setMinHeight(Region.USE_PREF_SIZE);
                    card.getChildren().add(line);
                }
                card.setOnMouseClicked(e -> {
                    if (!radio.isDisabled()) radio.setSelected(true);
                });
                radio.selectedProperty().addListener((obs, was, is) -> {
                    if (is) card.getStyleClass().add("selected");
                    else card.getStyleClass().remove("selected");
                });
                radio.disableProperty().addListener((obs, was, is) -> card.setDisable(is));
                radios.add(radio);
                root.getChildren().add(card);
            }
            @SuppressWarnings("unchecked")
            Function<javafx.scene.control.Toggle, T> valueOf = toggle -> toggle == null ? null : (T) toggle.getUserData();
            group.selectedToggleProperty().addListener((obs, old, now) -> selected.set(valueOf.apply(now)));
        }

        /** Two columns, for four short options. */
        public OptionCards<T> twoColumns() {
            javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
            grid.setHgap(UiScale.STEP - 2);
            grid.setVgap(UiScale.STEP - 2);
            List<Node> cards = new ArrayList<>(root.getChildren());
            root.getChildren().clear();
            for (int i = 0; i < cards.size(); i++) {
                Node card = cards.get(i);
                if (card instanceof Region region) {
                    region.setMaxWidth(Double.MAX_VALUE);
                    region.setMaxHeight(Double.MAX_VALUE);
                }
                grid.add(card, i % 2, i / 2);
            }
            javafx.scene.layout.ColumnConstraints half = new javafx.scene.layout.ColumnConstraints();
            half.setPercentWidth(50);
            grid.getColumnConstraints().addAll(half, half);
            root.getChildren().add(grid);
            return this;
        }

        public OptionCards<T> select(T option) {
            for (RadioButton radio : radios) {
                if (Objects.equals(radio.getUserData(), option)) {
                    radio.setSelected(true);
                }
            }
            return this;
        }

        /** The option chosen, or null while none is. */
        public ObjectProperty<T> selectedProperty() {
            return selected;
        }

        public T selected() {
            return selected.get();
        }

        /** The option's radio button: to disable one, or for a test to press. */
        public RadioButton radioFor(T option) {
            return radios.stream().filter(r -> Objects.equals(r.getUserData(), option))
                    .findFirst().orElse(null);
        }

        public Node node() {
            return root;
        }
    }

    // ------------------------------------------------------------------
    // Framed lists
    // ------------------------------------------------------------------

    /**
     * One row of a framed list: an icon, the name, a quieter second line, and tags at the
     * end ("Private", "Tracks"). Any of them may be null.
     */
    public record Row(Node icon, String primary, String secondary, List<String> tags, boolean path) {
        public Row(Node icon, String primary, String secondary, List<String> tags) {
            this(icon, primary, secondary, tags, false);
        }

        public Row(Node icon, String primary) {
            this(icon, primary, null, List.of(), false);
        }

        /**
         * A row naming a file. Too long for the frame, it loses its middle rather than its
         * end - the file's own name is the part worth keeping - and says it whole in a tooltip.
         */
        public static Row path(Node icon, String path) {
            return new Row(icon, path, null, List.of(), true);
        }
    }

    /** Row height with one line of text, and with a second line under it. */
    public static final double ROW = 32;
    public static final double ROW_TWO_LINES = 44;

    /**
     * Frames a list and draws its rows from {@code rowOf}.
     *
     * <p>The height is set from the rows actually drawn - a fixed cell height, between
     * {@code minRows} and {@code maxRows} of them - because a ListView otherwise asks for
     * 400px whatever it holds, and a dialog as tall as its content takes all of it.
     */
    public static <T> ListView<T> framed(ListView<T> list, Function<T, Row> rowOf, int minRows, int maxRows,
                                         boolean twoLines) {
        double rowHeight = twoLines ? ROW_TWO_LINES : ROW;
        list.getStyleClass().add("jfx-framed-list");
        list.setFixedCellSize(rowHeight);
        list.setCellFactory(view -> new ListCell<>() {
            {
                // As wide as the frame, not as its longest row: a long description gave
                // every row a horizontal scroll bar and pushed the tags out of sight.
                setPrefWidth(0);
            }

            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(null);
                setGraphic(rowNode(rowOf.apply(item)));
            }
        });
        Runnable size = () -> list.setPrefHeight(
                Math.min(Math.max(list.getItems().size(), minRows), maxRows) * rowHeight + 4);
        list.getItems().addListener((javafx.collections.ListChangeListener<T>) change -> size.run());
        list.itemsProperty().addListener((obs, old, items) -> size.run());
        size.run();
        return list;
    }

    private static Node rowNode(Row row) {
        HBox box = new HBox(UiScale.STEP + 2);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("jfx-row");
        if (row.icon() != null) {
            box.getChildren().add(row.icon());
        }
        VBox text = new VBox(0);
        text.setAlignment(Pos.CENTER_LEFT);
        Label primary = new Label(row.primary());
        primary.getStyleClass().add("jfx-row-primary");
        primary.setMinWidth(0);
        if (row.path()) {
            primary.setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_WORD_ELLIPSIS);
            primary.setTooltip(new javafx.scene.control.Tooltip(row.primary()));
        }
        text.getChildren().add(primary);
        if (row.secondary() != null && !row.secondary().isBlank()) {
            Label secondary = new Label(row.secondary());
            secondary.getStyleClass().add("jfx-row-secondary");
            secondary.setMinWidth(0);
            text.getChildren().add(secondary);
        }
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        box.getChildren().add(text);
        if (row.tags() != null) {
            for (String tag : row.tags()) {
                Label pill = new Label(tag);
                pill.getStyleClass().add("jfx-pill");
                pill.setMinWidth(Region.USE_PREF_SIZE);
                box.getChildren().add(pill);
            }
        }
        return box;
    }

    /**
     * What a framed list says when it has no rows to show: loading (with a spinner),
     * empty, or failed (in red). For {@link ListView#setPlaceholder}.
     */
    public static Node listMessage(String text, ListState state) {
        Label words = new Label(text);
        words.setWrapText(true);
        words.getStyleClass().add(state == ListState.FAILED ? "jfx-list-failed" : "jfx-list-message");
        HBox box = new HBox(UiScale.STEP, words);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(UiScale.GAP));
        if (state == ListState.LOADING) {
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.getStyleClass().add("jfx-spinner");
            box.getChildren().add(0, spinner);
        }
        return box;
    }

    /** What a list with no rows is doing. */
    public enum ListState { LOADING, EMPTY, FAILED }
}
