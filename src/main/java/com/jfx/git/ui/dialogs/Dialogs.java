package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.DialogOwner;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.UiScale;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Every confirmation, warning, error, notice, one-line prompt and choice the application
 * asks, in the dialog shell.
 *
 * <h2>Why</h2>
 * Eighty-four of these were built in place on JavaFX's stock {@link Alert},
 * {@code TextInputDialog} and {@code ChoiceDialog}: a band with a glossy "?" or "!"
 * bitmap, the question in plain body text, OK and Cancel, and the destructive answer in
 * the same blue as every other. Each site styled itself, or did not, and was themed only
 * because JavaFX happens to copy an owner's stylesheets - so one raised before the main
 * window existed came out as bare Primer. Here each kind is decided once:
 *
 * <ul>
 *   <li>{@link #confirm}, {@link #destructive}, {@link #warn}, {@link #error} and
 *       {@link #info} set the icon tile's tone, the default icon and the buttons;</li>
 *   <li>the caller names the subject (an icon), the decision (the title), where it applies
 *       (the subtitle), the consequence (the message) and the verb;</li>
 *   <li>a destructive action is red, and {@link Message#safeDefault} makes Cancel what
 *       Enter does when the action cannot be undone;</li>
 *   <li>every one themes its own pane and goes through {@link JfxDialog#shell}.</li>
 * </ul>
 *
 * <p>The stock button types are kept - OK is still {@link ButtonType#OK}, relabelled - so
 * code that looks a button up by type, and tests that wait for "a dialog with an OK
 * button", keep working. The message stays the pane's {@code contentText} and the title
 * its {@code headerText}, for the same reason.
 *
 * <p>{@code DialogOwnershipTest} keeps stock dialogs from being built anywhere else.
 */
public final class Dialogs {

    private Dialogs() {
    }

    /** A question with one action: create, move, check out, open. */
    public static Message confirm(String windowTitle) {
        return new Message(AlertType.CONFIRMATION, windowTitle, Icons.info(), JfxDialog.Tone.NEUTRAL);
    }

    /** Something goes away: delete, discard, drop, reset. The action is red. */
    public static Message destructive(String windowTitle) {
        Message message = new Message(AlertType.CONFIRMATION, windowTitle, Icons.trash(),
                JfxDialog.Tone.DESTRUCTIVE);
        message.danger = true;
        return message;
    }

    /** Read this before going on: a rewrite, a force, an "anyway". */
    public static Message warn(String windowTitle) {
        return new Message(AlertType.WARNING, windowTitle, Icons.warning(), JfxDialog.Tone.CAUTION);
    }

    /** Something failed. One button, Close. */
    public static Message error(String windowTitle) {
        Message message = new Message(AlertType.ERROR, windowTitle, Icons.error(), JfxDialog.Tone.DESTRUCTIVE);
        message.defaultAction = "Close";
        return message;
    }

    /** A notice: nothing is being asked. One button, Close. */
    public static Message info(String windowTitle) {
        Message message = new Message(AlertType.INFORMATION, windowTitle, Icons.info(), JfxDialog.Tone.INFO);
        message.defaultAction = "Close";
        return message;
    }

    /** One line of text to type: a name, a path, a pattern. */
    public static Prompt prompt(String windowTitle) {
        return new Prompt(windowTitle);
    }

    /** One of a few named options, each with room to say what it means. */
    public static <T> Choice<T> choose(String windowTitle, List<T> options) {
        return new Choice<>(windowTitle, options);
    }

    /**
     * The window a dialog opened now belongs to: a dialog that is itself in front - the
     * merge tool, the clone form - or else the main window. Owned by the main window, a
     * question asked from inside the merge tool sat beside it rather than on it.
     */
    static Window owner() {
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage stage && stage.isShowing() && stage.isFocused()
                    && stage.getOwner() != null) {
                return stage;
            }
        }
        return DialogOwner.mainWindow();
    }

    private static void own(Dialog<?> dialog) {
        Window owner = owner();
        if (owner != null && dialog.getOwner() == null) {
            try {
                dialog.initOwner(owner);
            } catch (IllegalStateException alreadyShown) {
                // A reused dialog keeps the owner it had.
            }
        }
    }

    // ------------------------------------------------------------------
    // Message: confirm, destructive, warn, error, info
    // ------------------------------------------------------------------

    /** A message dialog being described; {@link #ask}, {@link #answer}, {@link #show} or {@link #build}. */
    public static final class Message {

        private final AlertType type;
        private final String windowTitle;
        private Node icon;
        private JfxDialog.Tone tone;
        private String title;
        private String subtitle;
        private String message;
        private String action;
        /** The kind's own label for OK - "Close" - used only while the buttons are the kind's own. */
        private String defaultAction;
        private boolean expanded;
        private List<ButtonType> buttons;
        private final List<ButtonType> dangerous = new ArrayList<>();
        private boolean danger;
        private boolean safeDefault;
        private String affectedHeading;
        private List<String> affected;
        private final List<String[]> codes = new ArrayList<>();
        private String details;
        private final List<Node> extras = new ArrayList<>();
        private Node footerLeft;
        private JfxDialog.Size size = JfxDialog.Size.SM;

        private Message(AlertType type, String windowTitle, Node icon, JfxDialog.Tone tone) {
            this.type = type;
            this.windowTitle = windowTitle;
            this.icon = icon;
            this.tone = tone;
        }

        /** The subject's glyph: a branch, a tag, a file. The kind supplies one otherwise. */
        public Message icon(Node icon) {
            this.icon = icon;
            return this;
        }

        public Message tone(JfxDialog.Tone tone) {
            this.tone = tone;
            return this;
        }

        /** The decision or the outcome, object named: "Delete feature/search?". Backticks for monospace. */
        public Message title(String title) {
            this.title = title;
            return this;
        }

        /** Where it applies, in a line: "Local branch", "On `main`". */
        public Message subtitle(String subtitle) {
            this.subtitle = subtitle;
            return this;
        }

        /** The consequence, in a sentence or two. Kept as the pane's content text. */
        public Message message(String message) {
            this.message = message;
            return this;
        }

        /** The verb on the confirming button, in place of OK: "Delete branch". */
        public Message action(String verb) {
            this.action = verb;
            return this;
        }

        /** Buttons other than OK and Cancel; the first with a default role is the primary. */
        public Message buttons(ButtonType... types) {
            this.buttons = List.of(types);
            return this;
        }

        /** Marks one of {@link #buttons} as destroying something: red. */
        public Message danger(ButtonType type) {
            dangerous.add(type);
            return this;
        }

        /**
         * Makes Cancel what Enter does. For an action that cannot be undone - a file
         * deleted from disk, commits forced off a remote - so a reflexive Enter is safe.
         */
        public Message safeDefault() {
            this.safeDefault = true;
            return this;
        }

        /** A framed list of what the action touches: commits as {@code sha subject}, paths. */
        public Message affected(String heading, List<String> rows) {
            this.affectedHeading = heading;
            this.affected = List.copyOf(rows);
            return this;
        }

        /** One fact to check or paste - a fingerprint, a command - in a monospace well with Copy. */
        public Message code(String heading, String text) {
            codes.add(new String[]{heading, text});
            return this;
        }

        /** The raw text behind the message - git's output, a stack trace - behind Show Details. */
        public Message details(String text) {
            this.details = text;
            return this;
        }

        /** Opens with the details showing, for a report whose details are the point. */
        public Message expanded() {
            this.expanded = true;
            return this;
        }

        /** Anything else the body needs under the message: a checkbox, a progress bar. */
        public Message extra(Node... nodes) {
            extras.addAll(List.of(nodes));
            return this;
        }

        /** A control at the left end of the footer: "Don't ask again". */
        public Message footerLeft(Node node) {
            this.footerLeft = node;
            return this;
        }

        public Message size(JfxDialog.Size size) {
            this.size = size;
            return this;
        }

        /** Shows it and waits; true when the confirming button was pressed. */
        public boolean ask() {
            Alert alert = build();
            ButtonType confirming = confirmingType(alert);
            return alert.showAndWait().filter(b -> b == confirming).isPresent();
        }

        /** Shows it and waits; the button pressed, or {@link ButtonType#CANCEL} when closed. */
        public ButtonType answer() {
            return build().showAndWait().orElse(ButtonType.CANCEL);
        }

        /** Shows it without waiting: a notice that need not hold up the caller. */
        public Alert show() {
            Alert alert = build();
            alert.show();
            return alert;
        }

        /** The dialog, built and not shown, for a caller that drives it itself. */
        public Alert build() {
            Alert alert = new Alert(type);
            own(alert);
            JfxDialog.shell(alert, size, windowTitle);
            alert.getDialogPane().getStyleClass().add("jfx-alert");
            JfxDialog.header(alert, icon, tone, title == null ? windowTitle : title, subtitle);

            // A question gets its answer and Cancel; a notice or an error only Close. The
            // stock warning had OK alone, which left an "anyway" with no way to say no.
            alert.getButtonTypes().setAll(buttons != null ? buttons
                    : type == AlertType.ERROR || type == AlertType.INFORMATION
                            ? List.of(ButtonType.OK)
                            : List.of(ButtonType.OK, ButtonType.CANCEL));
            // A kind's own "Close" belongs on its own OK; on a caller's buttons it would
            // relabel "Open release page".
            String verb = action != null ? action : buttons == null ? defaultAction : null;
            if (verb != null) {
                JfxDialog.verb(alert, confirmingType(alert), verb);
            }
            if (danger) {
                JfxDialog.danger(alert, confirmingType(alert));
            }
            for (ButtonType type : dangerous) {
                JfxDialog.danger(alert, type);
            }

            alert.setContentText(message == null ? "" : message);
            alert.getDialogPane().setContent(body());

            if (details != null && !details.isBlank()) {
                TextArea raw = new TextArea(details);
                raw.setEditable(false);
                raw.setWrapText(false);
                raw.setPrefRowCount(Math.min(14, (int) details.lines().count() + 1));
                raw.getStyleClass().add("jfx-details");
                alert.getDialogPane().setExpandableContent(raw);
                alert.getDialogPane().setExpanded(expanded);
            }
            if (safeDefault) {
                makeCancelTheDefault(alert);
            }
            // Last: DialogPane rebuilds the footer whenever its buttons or its expandable
            // content change, and would drop anything added before.
            if (footerLeft != null) {
                JfxDialog.footerLeft(alert, footerLeft);
            }
            return alert;
        }

        private Node body() {
            VBox body = new VBox(UiScale.GAP);
            body.getStyleClass().add("jfx-alert-body");
            if (message != null && !message.isBlank()) {
                Label text = new Label(message);
                text.setWrapText(true);
                text.setMinHeight(Region.USE_PREF_SIZE);
                text.getStyleClass().add("jfx-alert-message");
                body.getChildren().add(text);
            }
            if (affected != null && !affected.isEmpty()) {
                body.getChildren().add(affectedList(affectedHeading, affected));
            }
            for (String[] code : codes) {
                body.getChildren().add(codeWell(code[0], code[1]));
            }
            body.getChildren().addAll(extras);
            if (body.getChildren().isEmpty()) {
                body.getStyleClass().add("jfx-alert-empty");
            }
            return body;
        }
    }

    /** OK when the dialog has it, else the first button whose role is the default one. */
    private static ButtonType confirmingType(Dialog<?> dialog) {
        List<ButtonType> types = dialog.getDialogPane().getButtonTypes();
        if (types.contains(ButtonType.OK)) {
            return ButtonType.OK;
        }
        for (ButtonType type : types) {
            if (type.getButtonData() != null && type.getButtonData().isDefaultButton()) {
                return type;
            }
        }
        return types.isEmpty() ? ButtonType.OK : types.get(0);
    }

    private static void makeCancelTheDefault(Dialog<?> dialog) {
        Button cancel = null;
        for (ButtonType type : dialog.getDialogPane().getButtonTypes()) {
            if (!(dialog.getDialogPane().lookupButton(type) instanceof Button button)) {
                continue;
            }
            boolean isCancel = type.getButtonData() != null && type.getButtonData().isCancelButton();
            button.setDefaultButton(isCancel);
            if (isCancel) {
                cancel = button;
            }
        }
        if (cancel != null) {
            Button focus = cancel;
            dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, e -> Platform.runLater(focus::requestFocus));
        }
    }

    /** "2 COMMITS LEAVE ORIGIN/MAIN" over a framed column of monospace rows. */
    static Node affectedList(String heading, List<String> rows) {
        VBox list = new VBox();
        list.getStyleClass().add("jfx-affected");
        for (String row : rows) {
            Label line = new Label(row);
            line.getStyleClass().add("jfx-affected-row");
            line.setMinWidth(0);
            line.setMaxWidth(Double.MAX_VALUE);
            line.setTextOverrun(javafx.scene.control.OverrunStyle.CENTER_ELLIPSIS);
            line.setTooltip(new Tooltip(row));
            list.getChildren().add(line);
        }
        if (heading == null || heading.isBlank()) {
            return list;
        }
        Label eyebrow = new Label(heading);
        eyebrow.getStyleClass().add("t-eyebrow");
        return new VBox(UiScale.SNUG, eyebrow, list);
    }

    /** A monospace well holding one fact, with a button that copies it. */
    static Node codeWell(String heading, String text) {
        Label value = new Label(text);
        value.getStyleClass().add("jfx-code-text");
        value.setWrapText(true);
        value.setMinHeight(Region.USE_PREF_SIZE);
        value.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(value, Priority.ALWAYS);
        Button copy = Icons.button(Icons.copy(), "Copy", "Copy to the clipboard");
        copy.getStyleClass().addAll("btn-ghost", "btn-sm", "jfx-code-copy");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        });
        HBox well = new HBox(UiScale.STEP, value, copy);
        well.getStyleClass().add("jfx-code-well");
        if (heading == null || heading.isBlank()) {
            return well;
        }
        Label eyebrow = new Label(heading);
        eyebrow.getStyleClass().add("t-eyebrow");
        return new VBox(UiScale.SNUG, eyebrow, well);
    }

    // ------------------------------------------------------------------
    // Prompt
    // ------------------------------------------------------------------

    /** Where the caret lands in a prefilled prompt. */
    public enum Selection {
        /** All of it, so typing replaces it. */
        ALL,
        /**
         * The last path segment without its extension - "rounding" in bugfix/rounding,
         * "Main" in src/Main.java - the part a rename usually changes.
         */
        LAST_SEGMENT,
        /** Nothing selected, caret at the end: a prefix to type after. */
        END
    }

    /** A one-line prompt being described; {@link #ask} or {@link #build}. */
    public static final class Prompt {

        private final String windowTitle;
        private Node icon = Icons.edit();
        private JfxDialog.Tone tone = JfxDialog.Tone.NEUTRAL;
        private String title;
        private String subtitle;
        private String label = "Name";
        private String initial = "";
        private String placeholder;
        private String hint;
        private Function<String, String> validator = text -> null;
        private String action = "OK";
        private Selection selection = Selection.ALL;

        private Prompt(String windowTitle) {
            this.windowTitle = windowTitle;
        }

        public Prompt icon(Node icon) {
            this.icon = icon;
            return this;
        }

        public Prompt tone(JfxDialog.Tone tone) {
            this.tone = tone;
            return this;
        }

        public Prompt title(String title) {
            this.title = title;
            return this;
        }

        public Prompt subtitle(String subtitle) {
            this.subtitle = subtitle;
            return this;
        }

        /** The field's label, above it. */
        public Prompt label(String label) {
            this.label = label;
            return this;
        }

        public Prompt initial(String text) {
            this.initial = text == null ? "" : text;
            return this;
        }

        /** An example of what goes in the field. Not instructions: it vanishes as the field takes focus. */
        public Prompt placeholder(String example) {
            this.placeholder = example;
            return this;
        }

        /** What the field is for, under it, for as long as the dialog is open. */
        public Prompt hint(String hint) {
            this.hint = hint;
            return this;
        }

        /**
         * What is wrong with the text, or null when nothing is. Shown under the field as
         * it is typed, and the action stays disabled until it is null. Blank text is
         * refused without a message.
         */
        public Prompt validator(Function<String, String> validator) {
            this.validator = Objects.requireNonNull(validator);
            return this;
        }

        public Prompt action(String verb) {
            this.action = verb;
            return this;
        }

        public Prompt select(Selection selection) {
            this.selection = selection;
            return this;
        }

        /** Shows it and waits; the text as typed, or empty when cancelled. */
        public Optional<String> ask() {
            return build().showAndWait();
        }

        public Dialog<String> build() {
            Dialog<String> dialog = new Dialog<>();
            own(dialog);
            JfxDialog.shell(dialog, JfxDialog.Size.SM, windowTitle);
            JfxDialog.header(dialog, icon, tone, title == null ? windowTitle : title, subtitle);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            JfxDialog.verb(dialog, ButtonType.OK, action);

            TextField field = new TextField(initial);
            field.getStyleClass().add("jfx-prompt-field");
            if (placeholder != null) {
                field.setPromptText(placeholder);
            }
            Label caption = new Label(label);
            caption.getStyleClass().add("t-label");
            caption.setLabelFor(field);
            VBox body = new VBox(UiScale.SNUG, caption, field);
            if (hint != null && !hint.isBlank()) {
                Label help = new Label(hint);
                help.getStyleClass().add("t-caption");
                help.setWrapText(true);
                help.setMinHeight(Region.USE_PREF_SIZE);
                body.getChildren().add(help);
            }
            Label error = new Label();
            error.getStyleClass().add("jfx-field-error");
            error.setWrapText(true);
            error.setMinHeight(Region.USE_PREF_SIZE);
            body.getChildren().add(JfxDialog.collapsible(dialog, error));
            dialog.getDialogPane().setContent(body);

            Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
            Runnable validate = () -> {
                String text = field.getText() == null ? "" : field.getText();
                String problem = text.isBlank() ? null : validator.apply(text);
                error.setText(problem == null ? "" : problem);
                ok.setDisable(text.isBlank() || problem != null);
            };
            field.textProperty().addListener((obs, old, text) -> validate.run());
            validate.run();

            dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, e -> Platform.runLater(() -> {
                field.requestFocus();
                select(field, selection);
            }));
            dialog.setResultConverter(button -> button == ButtonType.OK ? field.getText() : null);
            return dialog;
        }

        private static void select(TextField field, Selection selection) {
            String text = field.getText() == null ? "" : field.getText();
            switch (selection) {
                case ALL -> field.selectAll();
                case END -> field.positionCaret(text.length());
                case LAST_SEGMENT -> {
                    int start = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\')) + 1;
                    int dot = text.lastIndexOf('.');
                    int end = dot > start ? dot : text.length();
                    field.selectRange(start, end);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Choice
    // ------------------------------------------------------------------

    /** A choice between a few options, as cards; {@link #ask} or {@link #build}. */
    public static final class Choice<T> {

        private final String windowTitle;
        private final List<T> options;
        private Node icon = Icons.settings();
        private JfxDialog.Tone tone = JfxDialog.Tone.NEUTRAL;
        private String title;
        private String subtitle;
        private T initial;
        private Function<T, String> text = String::valueOf;
        private Function<T, String> description = option -> null;
        private String action = "OK";
        private String message;
        private JfxDialog.Size size = JfxDialog.Size.SM;

        private Choice(String windowTitle, List<T> options) {
            this.windowTitle = windowTitle;
            this.options = List.copyOf(options);
            this.initial = options.isEmpty() ? null : options.get(0);
        }

        public Choice<T> icon(Node icon) {
            this.icon = icon;
            return this;
        }

        public Choice<T> tone(JfxDialog.Tone tone) {
            this.tone = tone;
            return this;
        }

        public Choice<T> title(String title) {
            this.title = title;
            return this;
        }

        public Choice<T> subtitle(String subtitle) {
            this.subtitle = subtitle;
            return this;
        }

        public Choice<T> initial(T option) {
            this.initial = option;
            return this;
        }

        /** Each option's name, on its card. */
        public Choice<T> text(Function<T, String> text) {
            this.text = text;
            return this;
        }

        /** A line under each option's name saying what choosing it does; null for none. */
        public Choice<T> description(Function<T, String> description) {
            this.description = description;
            return this;
        }

        public Choice<T> action(String verb) {
            this.action = verb;
            return this;
        }

        /** A line above the options, when they need an introduction. Kept as the content text. */
        public Choice<T> message(String message) {
            this.message = message;
            return this;
        }

        public Choice<T> size(JfxDialog.Size size) {
            this.size = size;
            return this;
        }

        public Optional<T> ask() {
            return build().showAndWait();
        }

        public Dialog<T> build() {
            Dialog<T> dialog = new Dialog<>();
            own(dialog);
            JfxDialog.shell(dialog, size, windowTitle);
            JfxDialog.header(dialog, icon, tone, title == null ? windowTitle : title, subtitle);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            JfxDialog.verb(dialog, ButtonType.OK, action);

            DialogParts.OptionCards<T> cards = new DialogParts.OptionCards<>(options, text, description);
            cards.select(initial);
            if (message != null && !message.isBlank()) {
                Label words = new Label(message);
                words.setWrapText(true);
                words.setMinHeight(Region.USE_PREF_SIZE);
                words.getStyleClass().add("jfx-alert-message");
                dialog.getDialogPane().setContentText(message);
                dialog.getDialogPane().setContent(new VBox(UiScale.GAP, words, cards.node()));
            } else {
                dialog.getDialogPane().setContent(cards.node());
            }
            dialog.getDialogPane().lookupButton(ButtonType.OK)
                    .disableProperty().bind(cards.selectedProperty().isNull());
            dialog.setResultConverter(button -> button == ButtonType.OK ? cards.selected() : null);
            return dialog;
        }
    }
}
