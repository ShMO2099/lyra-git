package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.MainWindowSize;
import com.jfx.git.ui.ThemeManager;
import com.jfx.git.ui.UiScale;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * The shape every dialog in this application starts from.
 *
 * <h2>Why this exists</h2>
 * There are twenty-nine dialog classes and no shared shell between them. Each sets its
 * own title, attaches its own stylesheets, adds its own button types and picks its own
 * dimensions — and the dimensions are the tell: six of them independently arrived at
 * {@code 720x380} and three more at {@code 420x320}. Nine dialogs converged on two sizes
 * with nobody deciding they should. That is a token trying to exist, so this declares it.
 *
 * <h2>What it is not</h2>
 * Not a base class. The dialogs already split between {@link Dialog} subclasses and
 * {@link Stage} subclasses, and forcing those together would be a rewrite rather than a
 * shell. These are configurators: an existing dialog adopts them one call at a time, and
 * a half-migrated codebase still works.
 *
 * <h2>Theming</h2>
 * {@link #shell} attaches the stylesheets, so a dialog that goes through it cannot be the
 * one that forgot. Today every dialog does remember — through
 * {@link ThemeManager#style} for a dialog pane or {@link ThemeManager#register} for a
 * stage — but it is remembered rather than guaranteed, and the failure is silent: a
 * dialog that skips it still renders, because AtlantaFX is the user-agent stylesheet, and
 * simply ignores every {@code .hash-chip} and {@code -jfx-} token it contains.
 */
public final class JfxDialog {

    private JfxDialog() {
    }

    /**
     * The widths a dialog in this application is allowed to be, plus the one that
     * measures itself.
     *
     * <p>Chosen from what the existing dialogs already do rather than invented: the
     * clusters at 420 and 720 wide are theirs, and MD fills the gap between a form and
     * a browser that the settings dialogs were each approximating separately.
     *
     * <h2>Height</h2>
     * SM, MD and WIDE pin only the width; their height is whatever the content needs, measured
     * at that width as the dialog opens. They used to pin a height too (420x320,
     * 560x460), and a short form then floated above a band of empty space while a dialog
     * with more to say than the preset allowed was squeezed - New tag opened with 118px
     * of nothing above its buttons, Undo preview with its two lists cut to a row and a
     * half each. LG and XL keep their height: they are workspaces and browsers, whose
     * lists and editors take whatever room the window has.
     */
    public enum Size {
        /** A form, a confirmation, a single question. */
        SM(420, -1),
        /** Settings, identity, connections: a longer form. */
        MD(560, -1),
        /** A browsing list with actions under it: requests, issues, history. */
        LG(720, 480),
        /**
         * As wide as LG, as tall as its content: a list that grows with what it holds and
         * then scrolls - the rebase plan, whose three rows sat above 200px of nothing at
         * LG's pinned height. Pinned by width only, as SM and MD are.
         */
        WIDE(720, -1),
        /**
         * A workspace rather than a question: a review to read, a conflict to resolve.
         *
         * <p>Added on discovering the three presets did not cover the application. The PR
         * review pane had taken 900x560 and the merge tool 1000x700, and neither is an
         * outlier to be talked down - they are dialogs you work inside for minutes rather
         * than answer in seconds, and that is a fourth category, not a violation of three.
         */
        XL(960, 640),
        /** Sized by content. For anything whose height depends on what it found. */
        FIT(-1, -1);

        private final double width;
        private final double height;

        Size(double width, double height) {
            this.width = width;
            this.height = height;
        }

        public double width() {
            return width;
        }

        public double height() {
            return height;
        }

        /** Whether this preset pins a width at all; FIT does not. */
        public boolean isFixed() {
            return width > 0;
        }

        /** Whether this preset pins the height as well; only the workspaces do. */
        public boolean pinsHeight() {
            return height > 0;
        }
    }

    /** How far below its preset a dialog may be resized: three quarters each way. */
    private static final double MIN_SHARE = 0.75;

    /**
     * The most of the screen a dialog that fits its content may take. Past this its
     * content is squeezed rather than the footer pushed off the bottom of the screen.
     */
    private static final double SCREEN_SHARE = 0.85;

    /** Marks a pane the shell has already wired, so shelling twice adds nothing twice. */
    private static final Object SHELLED = new Object();

    /** Marks a pane whose height follows its content, so {@link #refit} may change it. */
    private static final Object FITS = new Object();

    /** Where a pane keeps its {@link Header}, for {@link #headerOf}. */
    private static final Object HEADER = new Object();

    /** The style class of the one filled button a footer may have. */
    private static final String PRIMARY = "btn-primary";

    /** The style class of a button that destroys or discards something. */
    private static final String DANGER = "btn-danger";

    /**
     * Titles, sizes and themes a dialog, and gives it the shell's footer.
     *
     * <p>The width is applied to the dialog pane rather than the window, so a pane that
     * genuinely needs more — a long error, a wide path — can still grow past it. The
     * preset is a starting point, not a cap; capping is what produced a clipped Close
     * button in the Integrations window.
     *
     * <p>What it does to the buttons, so that no dialog has to remember:
     * <ul>
     *   <li>The first button whose role makes it the default (OK, Yes, Finish) is filled
     *       as {@code .btn-primary}. Primer fills whichever button is <em>default</em>,
     *       so a dialog that made Cancel the default for safety showed Cancel as the
     *       recommended action; here the default button only gets a ring.</li>
     *   <li>No button is stretched to the width of the widest. ButtonBar's uniform
     *       sizing cut "Disable Sparse Checkout" to "Disable Sparse Chec…" and made a
     *       Cancel 218px wide beside a long label.</li>
     *   <li>When the dialog opens with focus on a footer button - what JavaFX does when
     *       nobody asks for anything else - focus moves to the first field instead, so
     *       Enter in Add remote no longer closes it before a name is typed.</li>
     * </ul>
     *
     * <p>The window's own title should be a short noun ("New tag"); the question or
     * action goes in {@link #header}.
     */
    public static void shell(Dialog<?> dialog, Size size, String title) {
        if (dialog == null) {
            return;
        }
        dialog.setTitle(title);
        dialog.setResizable(true);
        DialogPane pane = dialog.getDialogPane();
        if (size != null && size.isFixed()) {
            pane.setPrefWidth(size.width());
            // A floor as well as a starting size. A dialog is resizable, and dragged small
            // enough its content was cut off with nothing to say there was more of it.
            pane.setMinWidth(size.width() * MIN_SHARE);
            if (size.pinsHeight()) {
                pane.setPrefHeight(size.height());
                pane.setMinHeight(size.height() * MIN_SHARE);
            }
        }
        if (!pane.getStyleClass().contains("jfx-dialog")) {
            pane.getStyleClass().add("jfx-dialog");
        }
        ThemeManager.style(dialog);

        if (pane.getProperties().putIfAbsent(SHELLED, Boolean.TRUE) != null) {
            return;
        }
        dressButtons(pane);
        pane.getButtonTypes().addListener((ListChangeListener<ButtonType>) change -> dressButtons(pane));
        if (size == null || !size.pinsHeight()) {
            pane.getProperties().put(FITS, Boolean.TRUE);
            dialog.addEventHandler(DialogEvent.DIALOG_SHOWING, event -> fitHeight(dialog));
            // Show Details: JavaFX resizes the window to the pane's preferred height, which
            // fitHeight pinned before the details were visible. Measured again after it.
            pane.expandedProperty().addListener((obs, was, is) -> Platform.runLater(() -> refit(dialog)));
        }
        // SHOWN fires before showAndWait blocks, and before a show() has drawn: run once
        // the window is up, which is also after any runLater a dialog queued for itself.
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWN,
                event -> Platform.runLater(() -> focusFirstInput(pane)));
        dialog.addEventHandler(DialogEvent.DIALOG_SHOWN, event -> floorWindow(dialog));
    }

    /**
     * Holds the dialog's window to the floor its pane declares (see {@link #shell}).
     *
     * <p>JavaFX's dialog window never reads the pane's minimum, so a resizable dialog went
     * down to nothing when dragged, with the pane - and its buttons - sticking out of it:
     * the very thing the floor was there to stop. Set once it shows, when the window's
     * frame can be measured, and never larger than the screen it is on.
     */
    private static void floorWindow(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        Scene scene = pane.getScene();
        if (scene == null || !(scene.getWindow() instanceof Stage window)) {
            return;
        }
        MainWindowSize.Size floor = new MainWindowSize.Size(pane.minWidth(-1), pane.minHeight(-1));
        MainWindowSize.Size frame = new MainWindowSize.Size(
                Math.max(0, window.getWidth() - scene.getWidth()),
                Math.max(0, window.getHeight() - scene.getHeight()));
        java.util.List<Screen> screens = Screen.getScreensForRectangle(window.getX(), window.getY(),
                Math.max(1, window.getWidth()), Math.max(1, window.getHeight()));
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
        MainWindowSize.Size minimum = MainWindowSize.minimum(floor, 1, frame, screen.getVisualBounds());
        window.setMinWidth(minimum.width());
        window.setMinHeight(minimum.height());
    }

    /**
     * Gives a footer button a verb in place of the stock label: "Create tag" rather than
     * "OK". The button type is kept, so a caller - and a test - that looks the button up
     * by {@link ButtonType#OK} still finds it.
     */
    public static void verb(Dialog<?> dialog, ButtonType type, String label) {
        if (dialog != null && dialog.getDialogPane().lookupButton(type) instanceof Button button) {
            button.setText(label);
        }
    }

    /**
     * Marks a footer button as destructive: red, and never the filled primary as well.
     * For the confirming button of a delete, discard, drop, reset or force - nothing else.
     */
    public static void danger(Dialog<?> dialog, ButtonType type) {
        if (dialog != null && dialog.getDialogPane().lookupButton(type) instanceof Button button) {
            button.getStyleClass().remove(PRIMARY);
            if (!button.getStyleClass().contains(DANGER)) {
                button.getStyleClass().add(DANGER);
            }
        }
    }

    /**
     * Measures the dialog again for content that changed after it opened - a result
     * that appeared, a section that was revealed - and resizes the window to it.
     *
     * <p>Not automatic, because most content changes should not move a window under
     * the pointer: a dialog that grows when it reports an outcome says so by calling this.
     */
    public static void refit(Dialog<?> dialog) {
        if (dialog != null) {
            refit(dialog.getDialogPane());
        }
    }

    /**
     * {@link #refit(Dialog)} from any node inside the dialog - a field whose error line
     * appeared - for code that has the node and not the dialog.
     */
    public static void refit(Node inside) {
        Scene scene = inside == null ? null : inside.getScene();
        if (scene == null || !(scene.getRoot() instanceof DialogPane pane)
                || !Boolean.TRUE.equals(pane.getProperties().get(FITS))
                || scene.getWindow() == null || !scene.getWindow().isShowing()) {
            return; // not in a dialog that fits its content, or not open yet: measured as it opens
        }
        fitHeight(pane, scene.getWindow());
        scene.getWindow().sizeToScene();
    }

    // ------------------------------------------------------------------
    // Footer
    // ------------------------------------------------------------------

    /** Where a pane keeps the node at the left of its footer. */
    private static final Object FOOTER_LEFT = new Object();

    /**
     * Puts a control at the left end of the footer: "Don't ask again", "Copy report", a
     * status. DialogPane rebuilds its footer whenever its buttons or expandable content
     * change and drops anything it did not make, so the node is put back each time.
     */
    public static void footerLeft(Dialog<?> dialog, Node node) {
        DialogPane pane = dialog.getDialogPane();
        boolean first = pane.getProperties().put(FOOTER_LEFT, node) == null;
        ButtonBar.setButtonData(node, ButtonData.LEFT);
        ButtonBar.setButtonUniformSize(node, false);
        Runnable install = () -> {
            Node wanted = (Node) pane.getProperties().get(FOOTER_LEFT);
            if (wanted != null && pane.lookup(".button-bar") instanceof ButtonBar bar
                    && !bar.getButtons().contains(wanted)) {
                bar.getButtons().add(wanted);
            }
        };
        if (first) {
            pane.getButtonTypes().addListener((ListChangeListener<ButtonType>) change -> install.run());
            pane.expandableContentProperty().addListener((obs, old, now) -> install.run());
        }
        // Replace an earlier node rather than keep both.
        if (pane.lookup(".button-bar") instanceof ButtonBar bar) {
            bar.getButtons().removeIf(n -> n != node && ButtonBar.getButtonData(n) == ButtonData.LEFT
                    && n.getStyleClass().contains("jfx-footer-left"));
        }
        if (!node.getStyleClass().contains("jfx-footer-left")) {
            node.getStyleClass().add("jfx-footer-left");
        }
        install.run();
    }

    // ------------------------------------------------------------------
    // Workspaces
    // ------------------------------------------------------------------

    /**
     * A workspace's body: a toolbar strip across the top, edge to edge under the header,
     * and the work beneath it.
     *
     * <p>The pane's own 20px padding is taken off for this, so the strip runs the full width
     * the way the header's rule and the footer do. {@code body} brings its own: pass it
     * with {@code jfx-workspace-body} for the dialog's usual margins, or bare for panes that
     * should meet the window's edges, as the merge tool's do.
     */
    public static VBox workspace(Dialog<?> dialog, Node toolbar, Node body) {
        DialogPane pane = dialog.getDialogPane();
        if (!pane.getStyleClass().contains("jfx-workspace")) {
            pane.getStyleClass().add("jfx-workspace");
        }
        VBox content = new VBox(toolbar, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        pane.setContent(content);
        return content;
    }

    /** The strip a {@link #workspace} opens with: controls in one row, 40px tall. */
    public static HBox toolbar(Node... items) {
        HBox bar = new HBox(UiScale.STEP, items);
        bar.getStyleClass().add("jfx-toolbar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /** What pushes the toolbar's later items to its right end. */
    public static Region toolbarSpacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    // ------------------------------------------------------------------
    // Acting in place
    // ------------------------------------------------------------------

    /**
     * What a dialog that runs its action in place reports back; see {@link #actInPlace}.
     * Call it on the FX thread.
     */
    public interface Progress {
        /** What is happening now, beside a spinner: "Receiving objects 42%". */
        void say(String text);

        /** Done: the dialog closes with the action's result. */
        void succeeded();

        /** Done, and the dialog stays for another go: the note shows in green. */
        void kept(String note);

        /** Did not work: the dialog stays, as it was, with the reason in red. */
        void failed(String reason);

        /** Whether the dialog was closed while the work ran; its outcome then goes nowhere. */
        boolean cancelled();
    }

    /**
     * Makes {@code action} run its work with the dialog still open.
     *
     * <p>A dialog that closed first and ran afterwards reported its failure somewhere
     * else - a second alert, the status bar - and the user's input went with the window:
     * the clone form's URL had to be typed again after a typo in the folder. Here the
     * button starts {@code work}, the body locks, the footer says what is happening, and
     * the window closes only on {@link Progress#succeeded}. A failure lands in the footer
     * with everything still filled in. Cancel still closes.
     */
    public static void actInPlace(Dialog<?> dialog, ButtonType action, String busyText,
                                  java.util.function.Consumer<Progress> work) {
        DialogPane pane = dialog.getDialogPane();
        if (!(pane.lookupButton(action) instanceof Button button)) {
            return;
        }
        FooterStatus status = footerStatus(dialog);
        boolean[] proceeding = {false};
        boolean[] closed = {false};
        dialog.addEventHandler(DialogEvent.DIALOG_HIDDEN, e -> closed[0] = true);

        Runnable settle = () -> {
            if (pane.getContent() != null) pane.getContent().setDisable(false);
            button.setDisable(false);
        };
        Progress progress = new Progress() {
            @Override
            public void say(String text) {
                if (!closed[0]) status.busy(text);
            }

            @Override
            public void succeeded() {
                if (closed[0]) return;
                settle.run();
                status.clear();
                proceeding[0] = true;
                button.fire();
            }

            @Override
            public void kept(String note) {
                if (closed[0]) return;
                settle.run();
                status.ok(note);
            }

            @Override
            public void failed(String reason) {
                if (closed[0]) return;
                settle.run();
                status.failed(reason);
            }

            @Override
            public boolean cancelled() {
                return closed[0];
            }
        };

        button.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (proceeding[0]) {
                return;
            }
            event.consume();
            if (pane.getContent() != null) pane.getContent().setDisable(true);
            button.setDisable(true);
            status.busy(busyText);
            work.accept(progress);
        });
    }

    /** Where a pane keeps its {@link FooterStatus}. */
    private static final Object FOOTER_STATUS = new Object();

    /**
     * The dialog's status line, at the left of its footer: what is happening, what worked,
     * what did not. Made once per dialog; {@link #actInPlace} reports through the same one,
     * so a second action in the body - Add remote's fork - says its outcome in the same
     * place as the footer's.
     */
    public static FooterStatus footerStatus(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        FooterStatus status = (FooterStatus) pane.getProperties().get(FOOTER_STATUS);
        if (status == null) {
            status = new FooterStatus(pane);
            pane.getProperties().put(FOOTER_STATUS, status);
            footerLeft(dialog, status.node);
        }
        return status;
    }

    /** See {@link #footerStatus}. Use on the FX thread. */
    public static final class FooterStatus {

        private final DialogPane pane;
        private final HBox node;
        private final ProgressIndicator spinner = new ProgressIndicator();
        private final Label words = new Label();

        private FooterStatus(DialogPane pane) {
            this.pane = pane;
            spinner.getStyleClass().add("jfx-spinner");
            // Wraps beside the buttons rather than pushing them off the end of the footer.
            words.setWrapText(true);
            words.setMaxWidth(260);
            words.setMinHeight(Region.USE_PREF_SIZE);
            node = new HBox(UiScale.STEP, spinner, words);
            node.setAlignment(Pos.CENTER_LEFT);
            node.getStyleClass().add("jfx-footer-status");
            node.setVisible(false);
        }

        /** Something is happening: a spinner and what it is. */
        public void busy(String text) {
            show(text, null, true);
        }

        /** It worked, and the dialog stays: in green. */
        public void ok(String text) {
            show(text, "ok", false);
        }

        /** It did not work: in red. */
        public void failed(String text) {
            show(text, "failed", false);
        }

        public void clear() {
            show("", null, false);
        }

        /** What the line says now; empty when nothing. */
        public String text() {
            return words.getText();
        }

        private void show(String text, String tone, boolean spinning) {
            node.getStyleClass().removeAll("ok", "failed");
            if (tone != null) node.getStyleClass().add(tone);
            spinner.setVisible(spinning);
            spinner.setManaged(spinning);
            words.setText(text == null ? "" : text);
            node.setVisible(text != null && !text.isBlank());
            refit(pane);
        }
    }

    /**
     * A line that is only there while it says something: an outcome, a validation message.
     *
     * <p>Empty, it takes no room. A dialog is as tall as its content, so an empty status
     * line laid out anyway was a band of nothing above the footer - Add remote had one.
     * When it fills, the dialog is measured again, since a line that appears after the
     * window opened would otherwise be squeezed into room nobody left for it.
     */
    public static Label collapsible(Dialog<?> dialog, Label line) {
        line.managedProperty().bind(line.textProperty().isNotEmpty());
        line.visibleProperty().bind(line.managedProperty());
        line.textProperty().addListener((obs, old, text) -> refit(dialog));
        return line;
    }

    /** Makes the first button whose role is "default" the filled primary, and unstretches all. */
    private static void dressButtons(DialogPane pane) {
        boolean defaultSeen = false;
        for (ButtonType type : pane.getButtonTypes()) {
            if (!(pane.lookupButton(type) instanceof Button button)) {
                continue;
            }
            ButtonBar.setButtonUniformSize(button, false);
            ButtonData data = type.getButtonData();
            boolean isDefaultRole = data != null && data.isDefaultButton();
            // Only the first: DialogPane makes only the first such button the default,
            // and two filled buttons in one footer is two primaries.
            if (isDefaultRole && !defaultSeen && !button.getStyleClass().contains(DANGER)
                    && !button.getStyleClass().contains(PRIMARY)) {
                button.getStyleClass().add(PRIMARY);
            }
            defaultSeen |= isDefaultRole;
        }
    }

    /**
     * Pins the pane's height to what its content needs at the width it will have.
     *
     * <p>Left to itself, JavaFX sizes a dialog window by asking the pane for its height
     * at no particular width, and every wrapping label and hint inside then answers for
     * one line - the window opens too short and the text is cut. Asking at the real
     * width gets the real answer.
     */
    private static void fitHeight(Dialog<?> dialog) {
        fitHeight(dialog.getDialogPane(), dialog.getOwner());
    }

    /** @param near a window on the screen the dialog opens on: its owner, or itself */
    private static void fitHeight(DialogPane pane, Window near) {
        pane.setPrefHeight(Region.USE_COMPUTED_SIZE);
        pane.applyCss();
        double width = pane.getPrefWidth() > 0 ? pane.getPrefWidth() : pane.prefWidth(-1);
        double height = Math.ceil(pane.prefHeight(width));
        double cap = screenHeightFor(near) * SCREEN_SHARE;
        if (cap > 0) {
            height = Math.min(height, cap);
        }
        pane.setPrefHeight(height);
        pane.setMinHeight(height * MIN_SHARE);
    }

    /** The usable height of the screen {@code owner} is on, or of the primary screen. */
    private static double screenHeightFor(Window owner) {
        java.util.List<Screen> screens = owner == null || Double.isNaN(owner.getX())
                ? java.util.List.of()
                : Screen.getScreensForRectangle(owner.getX(), owner.getY(),
                        Math.max(1, owner.getWidth()), Math.max(1, owner.getHeight()));
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
        return screen == null ? 0 : screen.getVisualBounds().getHeight();
    }

    /** Moves focus off a footer button JavaFX defaulted to, onto the first field. */
    private static void focusFirstInput(DialogPane pane) {
        Scene scene = pane.getScene();
        if (scene == null) {
            return;
        }
        Node owner = scene.getFocusOwner();
        if (owner != null && !(owner instanceof Button && isInFooter(owner, pane))) {
            return;
        }
        Node first = firstInput(pane.getContent());
        if (first != null) {
            first.requestFocus();
        }
    }

    private static boolean isInFooter(Node node, DialogPane pane) {
        for (Node n = node; n != null && n != pane; n = n.getParent()) {
            if (n instanceof ButtonBar) {
                return true;
            }
        }
        return false;
    }

    /** The first visible, enabled control in {@code node} that takes typing or a choice. */
    static Node firstInput(Node node) {
        if (node == null || !node.isVisible() || node.isDisabled()) {
            return null;
        }
        boolean takesInput = (node instanceof TextInputControl text && text.isEditable())
                || node instanceof ComboBoxBase<?>
                || node instanceof ChoiceBox<?>
                || node instanceof ListView<?>
                || (node instanceof RadioButton radio && radio.isSelected());
        if (takesInput && node.isFocusTraversable()) {
            return node;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = firstInput(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Header
    // ------------------------------------------------------------------

    /**
     * How risky the thing a dialog asks about is. It colours the header's icon tile; the
     * buttons carry it separately ({@link #danger}).
     */
    public enum Tone {
        /** Create, edit, choose: nothing at risk. The accent. */
        NEUTRAL("tone-neutral"),
        /** A rewrite, a force, a trust decision: read before going on. */
        CAUTION("tone-caution"),
        /** Delete, discard, drop: something goes away. */
        DESTRUCTIVE("tone-destructive"),
        /** A notice, About: nothing being asked. */
        INFO("tone-info"),
        /** Something that went right. */
        SUCCESS("tone-success");

        private final String styleClass;

        Tone(String styleClass) {
            this.styleClass = styleClass;
        }
    }

    /**
     * Replaces JavaFX's header - a band with a glossy "?" or "!" bitmap on the right and
     * the header text in plain body type - with the shell's: an icon tile coloured by
     * {@code tone}, the title in the title step, and an optional muted subtitle.
     *
     * <p>The title is the decision or the action with its object named ("Delete
     * feature/search?", "Create a tag"), not a category ("Confirmation"). The subtitle
     * says where it applies. Text between backticks in either is set in the monospace
     * face, for a SHA or a path: {@code "On `a64205a` · Fix typos"}.
     *
     * <p>The dialog's {@code headerText} is set to the title without the backticks, so
     * anything reading it - assistive technology, a test - still finds the words.
     *
     * @param subtitle may be null for none
     * @return the header, which can be changed after the dialog opens (a count that
     *         arrives later, a title that follows the selected option)
     */
    public static Header header(Dialog<?> dialog, Node icon, Tone tone, String title, String subtitle) {
        Header header = new Header(dialog, icon, tone, title, subtitle);
        DialogPane pane = dialog.getDialogPane();
        pane.getProperties().put(HEADER, header);
        dialog.setGraphic(null);
        pane.setHeader(header.node());
        return header;
    }

    /**
     * The same header for a window that is a {@link Stage} rather than a {@link Dialog}:
     * the caller places {@link Header#node()} at the top of its own layout.
     */
    public static Header header(Node icon, Tone tone, String title, String subtitle) {
        return new Header(null, icon, tone, title, subtitle);
    }

    /** The header {@link #header} put on this dialog, or null. */
    public static Header headerOf(Dialog<?> dialog) {
        return dialog == null ? null : (Header) dialog.getDialogPane().getProperties().get(HEADER);
    }

    /** A dialog's header: see {@link JfxDialog#header}. */
    public static final class Header {

        /** Where a title wraps when nothing else decides: a dialog sized by its content. */
        private static final double WRAP = 460;
        private static final double TILE = 32;

        private final Dialog<?> dialog;
        private final HBox root = new HBox(UiScale.GAP);
        private final StackPane tile = new StackPane();
        private final TextFlow title = new TextFlow();
        private final TextFlow subtitle = new TextFlow();
        private Tone tone;
        private String titleText = "";
        private String subtitleText = "";

        private Header(Dialog<?> dialog, Node icon, Tone tone, String title, String subtitle) {
            this.dialog = dialog;
            root.getStyleClass().add("jfx-dialog-header");
            root.setAlignment(Pos.TOP_LEFT);
            tile.getStyleClass().add("jfx-dialog-tile");
            tile.setAlignment(Pos.CENTER);
            tile.setMinSize(TILE, TILE);
            tile.setPrefSize(TILE, TILE);
            tile.setMaxSize(TILE, TILE);
            this.title.getStyleClass().add("jfx-dialog-title");
            this.subtitle.getStyleClass().add("jfx-dialog-subtitle");

            // A text column that wraps at a readable width when the dialog is sized by its
            // content, and answers its height for that width rather than for one long line.
            VBox text = new VBox(UiScale.TIGHT, this.title, this.subtitle) {
                @Override
                protected double computePrefWidth(double height) {
                    return Math.min(super.computePrefWidth(height), WRAP);
                }

                @Override
                protected double computePrefHeight(double width) {
                    return super.computePrefHeight(width < 0 ? computePrefWidth(-1) : width);
                }
            };
            text.getStyleClass().add("jfx-dialog-header-text");
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            root.getChildren().addAll(tile, text);

            icon(icon);
            tone(tone == null ? Tone.NEUTRAL : tone);
            title(title);
            subtitle(subtitle);
        }

        /** The node to place at the top of a window. A dialog's is placed already. */
        public Node node() {
            return root;
        }

        public Header title(String markup) {
            titleText = plain(markup);
            setRuns(title, markup, "jfx-dialog-title-text");
            if (dialog != null) {
                dialog.setHeaderText(titleText);
            }
            return this;
        }

        public Header subtitle(String markup) {
            subtitleText = plain(markup);
            setRuns(subtitle, markup, "jfx-dialog-subtitle-text");
            boolean any = !subtitleText.isEmpty();
            subtitle.setVisible(any);
            subtitle.setManaged(any);
            return this;
        }

        public Header tone(Tone next) {
            if (this.tone != null) {
                tile.getStyleClass().remove(this.tone.styleClass);
            }
            this.tone = next;
            tile.getStyleClass().add(next.styleClass);
            return this;
        }

        public Header icon(Node icon) {
            tile.getChildren().setAll(icon == null ? java.util.List.of() : java.util.List.of(icon));
            return this;
        }

        /**
         * A hairline under the header, for a dialog whose body is a form, a list or a
         * workspace. A short question reads as one block and goes without.
         */
        public Header ruled() {
            if (!root.getStyleClass().contains("ruled")) {
                root.getStyleClass().add("ruled");
            }
            return this;
        }

        /** The title as read, without the backticks. */
        public String titleText() {
            return titleText;
        }

        /** The subtitle as read, without the backticks; empty for none. */
        public String subtitleText() {
            return subtitleText;
        }

        public Tone tone() {
            return tone;
        }

        private static String plain(String markup) {
            return markup == null ? "" : markup.replace("`", "");
        }

        /** Text runs, every second one (the backticked ones) in the monospace face. */
        private static void setRuns(TextFlow flow, String markup, String runClass) {
            flow.getChildren().clear();
            if (markup == null || markup.isEmpty()) {
                return;
            }
            String[] parts = markup.split("`", -1);
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isEmpty()) {
                    continue;
                }
                Text run = new Text(parts[i]);
                run.getStyleClass().add(runClass);
                if (i % 2 == 1) {
                    run.getStyleClass().add("jfx-dialog-mono");
                }
                flow.getChildren().add(run);
            }
        }
    }

    /**
     * The same for a window that is a {@link Stage} rather than a {@link Dialog}.
     *
     * <p>{@link ThemeManager#register} rather than {@code style}: a stage carries its
     * stylesheets on its scene, and registering also keeps the window following a theme
     * switch made while it is open.
     *
     * <p>For SM and MD the preset becomes the root's preferred width and the window takes
     * the height the content needs at that width. A stage whose content grows after it
     * opens - a sign-in code that arrives - calls {@link Stage#sizeToScene()} itself.
     */
    public static void shell(Stage stage, Size size, String title) {
        if (stage == null) {
            return;
        }
        stage.setTitle(title);
        if (size != null && size.isFixed()) {
            if (size.pinsHeight()) {
                stage.setWidth(size.width());
                stage.setHeight(size.height());
            } else if (stage.getScene() != null && stage.getScene().getRoot() instanceof Region root) {
                root.setPrefWidth(size.width());
            } else {
                stage.setWidth(size.width());
            }
        }
        if (stage.getScene() != null) {
            stage.getScene().getRoot().getStyleClass().add("jfx-dialog");
            ThemeManager.register(stage.getScene());
        }
        com.jfx.git.ui.DialogOwner.adopt(stage);
    }

    // ------------------------------------------------------------------
    // Forms
    // ------------------------------------------------------------------

    /**
     * The width every label column starts at, so rows line up down a page.
     *
     * <p>A floor, not a fixed size. Sized to clear the longest label in the application
     * ("Scrollbar diff-map width"), so today every row aligns exactly and a longer label
     * added tomorrow widens its own column rather than losing its ending.
     */
    public static final double LABEL_COLUMN = 152;

    /**
     * A two-column form: labels that cannot truncate, controls that take the rest.
     *
     * <p>The alternative — an {@link javafx.scene.layout.HBox} per row with the label
     * given a preferred width — is what put "Default branch ..." on screen in a dialog
     * with 380px of empty space beside it. A Label asked for less room than its text needs
     * does not push its neighbour aside; it ellipsizes, quietly. A grid column sizes to
     * the widest preferred width among its children, so it cannot.
     */
    public static GridPane form() {
        GridPane grid = new GridPane();
        grid.setHgap(UiScale.STEP);
        grid.setVgap(UiScale.SNUG);
        grid.getStyleClass().add("jfx-form");

        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(LABEL_COLUMN);
        labels.setHgrow(Priority.NEVER);

        ColumnConstraints controls = new ColumnConstraints();
        controls.setHgrow(Priority.ALWAYS);
        controls.setFillWidth(true);

        grid.getColumnConstraints().addAll(labels, controls);
        return grid;
    }

    /**
     * Adds a labelled control to the next free row.
     *
     * <p>No colon on the label: one sitting in its own column is already identifiable as
     * a label without punctuation saying so.
     *
     * @return the row index used, so a caller can hang a hint off it
     */
    public static int addRow(GridPane form, String label, Node control) {
        int row = form.getRowCount();
        form.add(new Label(label), 0, row);
        form.add(control, 1, row);
        return row;
    }

    /**
     * Adds helper text under the control it explains, indented to the control column.
     *
     * <p>One step down the type scale and one step dimmer than the label above it. Both,
     * not either: a hint that differs from its label by size alone still reads as a
     * second label on a narrow window, and one that differs by colour alone disappears on
     * a low-contrast display.
     */
    public static void addHint(GridPane form, String text) {
        Label hint = new Label(text);
        hint.getStyleClass().add("t-caption");
        hint.setWrapText(true);
        hint.setMaxWidth(420);
        form.add(hint, 1, form.getRowCount());
    }

    /** A full-width heading between groups of rows. */
    public static void addSection(GridPane form, String title) {
        Label heading = new Label(title);
        heading.getStyleClass().add("t-eyebrow");
        int row = form.getRowCount();
        form.add(heading, 0, row, 2, 1);
        GridPane.setMargin(heading, new javafx.geometry.Insets(UiScale.GAP, 0, 0, 0));
    }

    // ------------------------------------------------------------------
    // Provider browsers
    // ------------------------------------------------------------------

    /**
     * The body every provider browser has: a sentence saying what is listed, an optional
     * filter, the list itself, and a row of actions under it.
     *
     * <p>Eight dialogs across three providers assemble this by hand — GitHub, GitLab and
     * Bitbucket, each with pull or merge requests and issues — and they agree on the shape
     * without agreeing on the numbers. The spacing, the padding and the alignment of the
     * action row are all slightly different in each, which is invisible one dialog at a
     * time and is what makes three providers feel like three applications.
     *
     * <p>Deliberately not the list-and-detail split the design sketch proposed: these
     * dialogs do not have a detail pane today, and inventing one here would be a redesign
     * smuggled in as a refactor. This extracts the shape they actually share.
     *
     * @param heading a sentence naming what the list holds; null for none
     * @param filter  the filter field, or null for a list short enough to scan
     * @param list    the list, which takes all the vertical space going
     * @param actions trailing buttons, right-aligned; the leading ones stay left
     */
    public static VBox browser(String heading, Node filter, Region list, Node... actions) {
        VBox content = new VBox(UiScale.GAP);
        content.setPadding(new javafx.geometry.Insets(UiScale.EDGE));

        if (heading != null && !heading.isBlank()) {
            Label caption = new Label(heading);
            caption.getStyleClass().add("t-caption");
            caption.setWrapText(true);
            content.getChildren().add(caption);
        }
        if (filter != null) {
            content.getChildren().add(filter);
        }

        VBox.setVgrow(list, Priority.ALWAYS);
        list.setMaxHeight(Double.MAX_VALUE);
        content.getChildren().add(list);

        if (actions.length > 0) {
            Region spacer = new Region();
            javafx.scene.layout.HBox.setHgrow(spacer, Priority.ALWAYS);
            javafx.scene.layout.HBox row =
                    new javafx.scene.layout.HBox(UiScale.STEP);
            row.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            // A leading action - "New pull request…" - belongs at the other end from the
            // ones that act on the selection, so the spacer goes after it rather than
            // first. Callers pass it as the first element and get that for free.
            row.getChildren().add(actions[0]);
            row.getChildren().add(spacer);
            for (int i = 1; i < actions.length; i++) {
                row.getChildren().add(actions[i]);
            }
            content.getChildren().add(row);
        }
        return content;
    }

    /** A control that should fill its column rather than sit at its natural width. */
    public static <T extends Region> T wide(T control) {
        control.setMaxWidth(Double.MAX_VALUE);
        return control;
    }
}
