package com.jfx.git.ui.dialogs;

import com.jfx.git.core.RebaseTodo;
import com.jfx.git.core.RebaseTodo.Action;
import com.jfx.git.core.RebaseTodo.Step;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.MessageEditor;
import com.jfx.git.ui.UiScale;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Edits the plan for an interactive rebase.
 *
 * <p>Shows the commits <b>oldest first</b>, the order Git replays them in. That is the
 * reverse of the graph, and deliberately so: squash and fixup fold a commit into the one
 * <em>above</em> it, and that sentence is only true in this direction. The header says so
 * rather than leaving the reader to work it out from which way the dates run.
 *
 * <p>The plan is meant to be read at a glance. Each row's action is a pill in the colour
 * of what it does - reword in the accent, squash and fixup violet, drop red with the
 * subject struck through - and under the list a sentence says what the plan produces:
 * "3 commits become 1 · e20b043 squashed into 807d3b6 · a64205a dropped".
 *
 * <p>The dialog owns no rebase logic. It edits a {@link RebaseTodo}, asks it what is
 * wrong, and hands it back; the confirm button is disabled while the plan cannot run, so
 * an invalid plan never reaches the repository.
 */
public class InteractiveRebaseDialog extends Dialog<RebaseTodo> {

    /** Every action a rebase plan can carry; the default for this editor. */
    public static final Set<Action> ALL_ACTIONS =
            Collections.unmodifiableSet(EnumSet.allOf(Action.class));

    /**
     * What a caller who can only replay and skip commits may offer.
     *
     * <p>For a cherry-pick that only needs order and omission - one that does not chain
     * a follow-up rebase over the picked commits to fold or reword them afterward. See
     * {@link #CHERRY_PICK_ACTIONS} for the fuller cherry-pick editor that does.
     */
    public static final Set<Action> PICK_OR_DROP =
            Collections.unmodifiableSet(EnumSet.of(Action.PICK, Action.DROP));

    /**
     * What {@code GitGraphCell.cherryPickSelected} offers: order and omission, plus
     * reword and squash - honoured by cherry-picking every non-dropped commit as usual
     * and then chaining a second, ordinary interactive rebase over just the commits that
     * batch created, carrying the reword/squash markers across. {@link Action#EDIT} and
     * {@link Action#FIXUP} are left out: edit stops the operation partway through for the
     * user to amend the working tree by hand, which a caller cannot resume unattended
     * the way this two-phase flow does, and fixup is squash's "discard this message"
     * variant - one fold verb is enough for a picker that is already asking for a
     * replacement message on reword.
     */
    public static final Set<Action> CHERRY_PICK_ACTIONS =
            Collections.unmodifiableSet(EnumSet.of(Action.PICK, Action.DROP, Action.REWORD, Action.SQUASH));

    /** A row: the 24px action pill with room above and below it. */
    private static final double ROW = 36;

    /**
     * The plan's rows on screen before it scrolls, and the fewest it leaves room for. The
     * dialog is as tall as the plan between the two: a fixed 480px had three commits above
     * 200px of empty list, and twenty with no more room than that.
     */
    private static final int MIN_ROWS = 3;
    private static final int MAX_ROWS = 10;

    /** What a row dragged by its handle carries: where it was in the plan. */
    private static final DataFormat STEP_INDEX = dataFormat("application/x-lyra-rebase-step");

    /** The edge a dragged row would land against: the top of this row, or its bottom. */
    private static final PseudoClass DROP_ABOVE = PseudoClass.getPseudoClass("drop-above");
    private static final PseudoClass DROP_BELOW = PseudoClass.getPseudoClass("drop-below");

    private final ListView<Integer> stepList = new ListView<>();
    private final Label summaryLabel = new Label();
    private final StackPane summaryIcon = new StackPane();
    private final HBox summaryBox;
    private final Label problemLabel = new Label();
    private final HBox problemBox;
    private final Button reset;
    private final ButtonType startType;
    private final Set<Action> allowedActions;
    /**
     * Whether an unedited plan is a real no-op, worth disabling the confirm button for.
     *
     * <p>True only for an ordinary rebase ({@link #ALL_ACTIONS}), which replays commits
     * back onto their own history - there, "everything picked, nothing reordered"
     * genuinely means nothing would change. This editor is also reused, restricted to a
     * smaller action set, to plan a cherry-pick onto a different branch; there, an
     * unedited plan is the ordinary "apply everything as selected" case, not a no-op -
     * treating it as one made confirming a multi-commit cherry-pick impossible unless
     * the user changed something first, since {@link RebaseTodo#isNoop()} has no way to
     * know the destination differs from the source. Keyed off {@link #ALL_ACTIONS}
     * itself rather than "is this {@link #PICK_OR_DROP}": every restricted set this
     * editor is given is a cherry-pick reuse, not just that one.
     */
    private final boolean unmodifiedPlanIsNoop;

    private RebaseTodo todo;
    /** The untouched plan, kept so "Reset" can discard every edit and start over in place. */
    private final RebaseTodo original;

    public InteractiveRebaseDialog(RebaseTodo initial) {
        this(initial, "Interactive rebase", "Start rebase", ALL_ACTIONS);
    }

    public InteractiveRebaseDialog(RebaseTodo initial, String title, String confirmLabel) {
        this(initial, title, confirmLabel, ALL_ACTIONS);
    }

    /**
     * The same editor under another name, over a chosen subset of the actions.
     *
     * <p>Cherry-picking several commits raises much of what a rebase plan does — which
     * order, which to leave out, and (via {@link #CHERRY_PICK_ACTIONS}) which to reword
     * or fold — so it reuses this rather than growing a second editor that would drift
     * from it. Whatever set is passed, the picker, the single-key shortcuts and the key
     * legend all narrow together, so an action the caller would not honour is never offered.
     */
    public InteractiveRebaseDialog(RebaseTodo initial, String title, String confirmLabel,
                                   Set<Action> allowedActions) {
        this.todo = initial;
        this.original = initial;
        this.allowedActions = Set.copyOf(allowedActions);
        this.unmodifiedPlanIsNoop = this.allowedActions.equals(ALL_ACTIONS);
        this.startType = new ButtonType(confirmLabel, ButtonBar.ButtonData.OK_DONE);

        JfxDialog.shell(this, JfxDialog.Size.WIDE, title);
        setResizable(true);

        JfxDialog.Header header = JfxDialog.header(this, Icons.rebase(), JfxDialog.Tone.NEUTRAL,
                unmodifiedPlanIsNoop ? rebaseHeading(initial) : title,
                unmodifiedPlanIsNoop
                        ? "Oldest first: they replay from top to bottom"
                        : "Oldest first: they are applied from top to bottom").ruled();
        // What tests and the tour find the dialog by; it was the heading label's class.
        header.node().getStyleClass().add("rebase-heading");

        // A plain toolbar Button rather than a ButtonType in the dialog's own bar - the
        // established pattern this codebase already uses for an action that must not
        // auto-close the dialog the way any ButtonType in getButtonTypes() would.
        reset = new Button("Reset", Icons.undo());
        reset.getStyleClass().addAll("btn-ghost", "btn-sm", "rebase-reset");
        reset.setTooltip(new Tooltip("Discard every edit and start over from the original plan"));
        reset.setOnAction(e -> apply(original));
        HBox toolbar = JfxDialog.toolbar(keysLegend(this.allowedActions), JfxDialog.toolbarSpacer(), reset);

        stepList.getStyleClass().addAll("jfx-framed-list", "rebase-steps");
        stepList.setFixedCellSize(ROW);
        stepList.setCellFactory(view -> new StepCell());
        // Its rows and the frame, as DialogParts.framed measures one (2px short and the
        // list scrolled); it still grows when the window is.
        stepList.setPrefHeight(Math.min(Math.max(initial.size(), MIN_ROWS), MAX_ROWS) * ROW + 4);
        VBox.setVgrow(stepList, Priority.ALWAYS);

        problemLabel.getStyleClass().add("rebase-problem");
        problemBox = callout(Icons.error(), problemLabel);
        problemBox.getStyleClass().addAll("danger", "rebase-problems");

        summaryLabel.getStyleClass().add("rebase-summary");
        summaryBox = callout(summaryIcon, summaryLabel);
        summaryBox.getStyleClass().add("rebase-outcome");

        VBox body = new VBox(UiScale.GAP, problemBox, stepList, summaryBox);
        body.getStyleClass().add("jfx-workspace-body");
        JfxDialog.workspace(this, toolbar, body);
        getDialogPane().getButtonTypes().addAll(startType, ButtonType.CANCEL);

        installKeyboardShortcuts();

        refresh();
        setResultConverter(button -> button == startType ? todo : null);
    }

    /** "Rebase 3 commits onto `1a2b3c4`": what an ordinary rebase plan does, and where. */
    private static String rebaseHeading(RebaseTodo plan) {
        String commits = plan.size() == 1 ? "1 commit" : plan.size() + " commits";
        String base = plan.base();
        return base != null && base.matches("[0-9a-f]{7,}")
                ? "Rebase " + commits + " onto `" + base.substring(0, 7) + "`"
                : "Rebase " + commits;
    }

    private static DataFormat dataFormat(String mimeType) {
        DataFormat known = DataFormat.lookupMimeType(mimeType);
        return known != null ? known : new DataFormat(mimeType);
    }

    /**
     * Single-key actions on the selected step.
     *
     * <p>Bound on the list rather than the dialog so that typing anywhere else is not
     * intercepted. Reordering uses Alt with the arrows because the bare arrows have to
     * keep moving the selection.
     */
    private void installKeyboardShortcuts() {
        stepList.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            Integer index = stepList.getSelectionModel().getSelectedItem();
            if (index == null || index >= todo.size()) return;

            if (event.isAltDown()) {
                switch (event.getCode()) {
                    case UP -> {
                        move(index, index - 1);
                        event.consume();
                    }
                    case DOWN -> {
                        move(index, index + 1);
                        event.consume();
                    }
                    default -> { }
                }
                return;
            }
            if (event.isShortcutDown()) return;

            Action action = switch (event.getCode()) {
                case P -> Action.PICK;
                case R -> Action.REWORD;
                case E -> Action.EDIT;
                case S -> Action.SQUASH;
                case F -> Action.FIXUP;
                case D -> Action.DROP;
                default -> null;
            };
            // A key for an action this editor does not offer is left alone rather than
            // applied, so the shortcuts and the picker never disagree.
            if (action != null && allowedActions.contains(action)) {
                if (action == Action.REWORD) {
                    reword(index);
                } else {
                    apply(todo.withAction(index, action));
                    stepList.getSelectionModel().select(index);
                }
                event.consume();
            }
        });
    }

    /** The key for each action, in the order the legend and the picker list them. */
    private static String keyOf(Action action) {
        return switch (action) {
            case PICK -> "P";
            case REWORD -> "R";
            case EDIT -> "E";
            case SQUASH -> "S";
            case FIXUP -> "F";
            case DROP -> "D";
        };
    }

    /** The single-key legend, naming only the actions this editor actually applies. */
    private static HBox keysLegend(Set<Action> allowed) {
        HBox legend = new HBox(UiScale.GAP);
        legend.getStyleClass().add("rebase-keys-hint");
        legend.setAlignment(Pos.CENTER_LEFT);
        for (Action action : Action.values()) {
            if (allowed.contains(action)) {
                legend.getChildren().add(DialogParts.keyHint(action.token(), keyOf(action)));
            }
        }
        legend.getChildren().add(DialogParts.keyHint("move", "Alt", "↑", "↓"));
        return legend;
    }

    /** An icon and a sentence on a tinted ground; the tone is a style class on the box. */
    private static HBox callout(Node icon, Label text) {
        text.setWrapText(true);
        text.setMinHeight(Region.USE_PREF_SIZE);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox box = new HBox(UiScale.STEP, icon, text);
        box.getStyleClass().add("jfx-callout");
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    private static void show(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** Rebuilds the rows and re-checks the plan after every edit. */
    private void refresh() {
        // Rebuilding the items list is only ever needed once, going from empty to the
        // full plan at construction: every structural edit this dialog offers - an
        // action change, a drop, a move - edits an existing step in RebaseTodo or
        // reorders its internal list, and the row for index i always still exists.
        if (stepList.getItems().size() != todo.size()) {
            Integer selected = stepList.getSelectionModel().getSelectedItem();
            stepList.getItems().clear();
            for (int i = 0; i < todo.size(); i++) {
                stepList.getItems().add(i);
            }
            if (selected != null && selected < todo.size()) {
                stepList.getSelectionModel().select(selected);
            }
        } else {
            // ListView.refresh() is documented to "recreate... the cells necessary to
            // populate the visible portion of the control" - not merely re-run
            // updateItem on the instances already there. Nothing is typed into a row any
            // more (a reword's message is written in its own editor), so losing the
            // focused cell to it costs nothing.
            stepList.refresh();
        }
        refreshOutcome();
    }

    /** Updates the problems, the summary, and whether Reset and Start are enabled. */
    private void refreshOutcome() {
        // A cherry-pick editor takes merges: the graph asks which parent each is picked
        // against once the plan is confirmed. Only a real rebase has to refuse them.
        List<String> problems = todo.problems(!unmodifiedPlanIsNoop);
        problemLabel.setText(String.join("\n", problems));
        show(problemBox, !problems.isEmpty());

        boolean noop = unmodifiedPlanIsNoop && todo.isNoop();
        // "Rewrites history" is the part that matters most: everything else here describes
        // what you asked for, and that part says what it costs. A plan that changes
        // anything replaces the commits it touches, so anyone who has already pushed them
        // is about to need a force-push. A cherry-pick makes new commits and rewrites
        // nothing, so it does not say so.
        summaryLabel.setText(noop
                ? "Nothing to do yet - change an action or the order."
                : outcome(todo, unmodifiedPlanIsNoop));
        boolean warns = !noop && unmodifiedPlanIsNoop;
        summaryLabel.getStyleClass().remove("rebase-summary-warn");
        summaryBox.getStyleClass().removeAll("muted", "caution");
        if (warns) {
            summaryLabel.getStyleClass().add("rebase-summary-warn");
            summaryBox.getStyleClass().add("caution");
        } else if (noop) {
            summaryBox.getStyleClass().add("muted");
        }
        summaryIcon.getChildren().setAll(warns ? Icons.warning() : Icons.info());

        reset.setDisable(todo.steps().equals(original.steps()));
        // An invalid plan must not be reachable, and neither must one that does nothing.
        getDialogPane().lookupButton(startType).setDisable(!problems.isEmpty() || noop);
    }

    /**
     * What the plan produces, as one line: "3 commits become 1 · e20b043 squashed into
     * 807d3b6 · a64205a dropped · rewrites history".
     *
     * @param rewrites whether replaying the plan replaces the commits it names - true for
     *                 a rebase, false for a cherry-pick, which leaves the originals alone
     */
    static String outcome(RebaseTodo plan, boolean rewrites) {
        int before = plan.size();
        int after = plan.resultingCommitCount();
        List<String> parts = new ArrayList<>();
        String commits = before == 1 ? "1 commit" : before + " commits";
        if (before != after) {
            parts.add(commits + " become" + (before == 1 ? "s " : " ") + after);
        } else {
            parts.add(plan.isReordered() ? commits + " in a new order" : commits);
        }
        if (before != after && plan.isReordered()) {
            parts.add("reordered");
        }

        Map<String, List<String>> folds = new LinkedHashMap<>();
        List<String> reworded = new ArrayList<>();
        List<String> stopped = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            Step step = plan.step(i);
            switch (step.action()) {
                case SQUASH, FIXUP -> {
                    int leader = leaderOf(plan, i);
                    if (leader >= 0) {
                        String verb = step.action() == Action.SQUASH ? "squashed into " : "fixed up into ";
                        folds.computeIfAbsent(verb + plan.step(leader).shortId(), k -> new ArrayList<>())
                                .add(step.shortId());
                    }
                }
                case REWORD -> reworded.add(step.shortId());
                case EDIT -> stopped.add(step.shortId());
                case DROP -> dropped.add(step.shortId());
                default -> { }
            }
        }
        folds.forEach((what, shas) -> parts.add(String.join(", ", shas) + " " + what));
        if (!reworded.isEmpty()) parts.add(String.join(", ", reworded) + " reworded");
        if (!stopped.isEmpty()) {
            parts.add(String.join(", ", stopped) + (stopped.size() == 1 ? " stops" : " stop") + " for editing");
        }
        if (!dropped.isEmpty()) parts.add(String.join(", ", dropped) + " dropped");
        if (rewrites && plan.isRelocated() && plan.base() != null) {
            parts.add("replayed onto " + plan.base().substring(0, Math.min(7, plan.base().length())));
        }
        if (rewrites) parts.add("rewrites history");
        return String.join(" · ", parts);
    }

    /**
     * The step a squash or fixup at {@code index} folds into: the nearest one above it
     * that is kept and not itself folding, or -1 when there is none - a plan problem
     * {@link RebaseTodo#problems()} reports.
     */
    private static int leaderOf(RebaseTodo plan, int index) {
        for (int i = index - 1; i >= 0; i--) {
            Action action = plan.step(i).action();
            if (action != Action.DROP && !action.foldsUpward()) return i;
        }
        return -1;
    }

    /**
     * Where a row dragged from {@code from} ends up when dropped on the row {@code onto},
     * against its bottom edge when {@code below}; on the empty space under the rows
     * ({@code onto} null) it goes last.
     */
    static int dropTarget(int from, Integer onto, boolean below, int size) {
        if (onto == null) return size - 1;
        int insertAt = below ? onto + 1 : onto;
        int to = from < insertAt ? insertAt - 1 : insertAt;
        return Math.max(0, Math.min(size - 1, to));
    }

    private void apply(RebaseTodo updated) {
        this.todo = updated;
        refresh();
    }

    /** Moves a step and keeps the selection on it, so repeated presses walk it along. */
    private void move(int from, int to) {
        if (to < 0 || to >= todo.size()) {
            return;
        }
        apply(todo.moved(from, to));
        stepList.getSelectionModel().select(to);
    }

    /**
     * Sets a step to reword and opens the message editor on it. Cancelling a first edit
     * puts the action back as it was: a reword with no new message would change nothing.
     */
    private void reword(int index) {
        Step step = todo.step(index);
        Action before = step.action();
        if (before != Action.REWORD) {
            apply(todo.withAction(index, Action.REWORD));
        }
        stepList.getSelectionModel().select(index);
        String hash = step.commit().getHash();
        // After the key or the picker that asked has finished: a window opened inside the
        // combo box's own selection handler would show under an event still being delivered.
        Platform.runLater(() -> editMessage(hash, before == Action.REWORD ? null : before));
    }

    /**
     * The message editor for the reworded step {@code hash}: the commit box's summary and
     * description, opening on the message it would replay with now.
     *
     * @param restoreOnCancel the action to put back if this is cancelled before any
     *                        message was written; null to leave the step as it is
     */
    private void editMessage(String hash, Action restoreOnCancel) {
        int index = todo.indexOf(hash);
        if (index < 0 || todo.step(index).action() != Action.REWORD) return;
        Step step = todo.step(index);
        Optional<String> written = new RewordDialog(step.shortId(), messageOf(step),
                unmodifiedPlanIsNoop
                        ? "The rebase replays this commit with the message written here."
                        : "The copy this applies is given the message written here.").showAndWait();
        int now = todo.indexOf(hash);
        if (now < 0) return;
        if (written.isPresent()) {
            apply(todo.withMessage(now, written.get()));
        } else if (restoreOnCancel != null && todo.step(now).message() == null) {
            apply(todo.withAction(now, restoreOnCancel));
        }
        stepList.getSelectionModel().select(now);
        stepList.requestFocus();
    }

    /** What a step replays with: the message written for it, else the commit's own. */
    private static String messageOf(Step step) {
        if (step.message() != null && !step.message().isBlank()) return step.message();
        CommitInfo commit = step.commit();
        return commit.hasFullMessage() ? commit.getFullMessage() : commit.getSubject();
    }

    /** Whether {@code node} sits inside a button or a picker, which handle their own clicks. */
    private static boolean inControl(Node node) {
        for (Node n = node; n != null && !(n instanceof ListCell); n = n.getParent()) {
            if (n instanceof ButtonBase || n instanceof ComboBoxBase) return true;
        }
        return false;
    }

    /** One action in the picker: its word, and in the open list what it does as well. */
    private static final class ActionCell extends ListCell<Action> {

        private final boolean described;

        ActionCell(boolean described) {
            this.described = described;
        }

        @Override
        protected void updateItem(Action item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(item.token());
            if (described) {
                Label what = new Label(item.description());
                what.getStyleClass().add("rebase-action-description");
                setGraphic(what);
                setContentDisplay(ContentDisplay.RIGHT);
                setGraphicTextGap(UiScale.GAP);
            }
        }
    }

    /** A drag handle, the action pill, the hash, the message, and the controls to move it. */
    private final class StepCell extends ListCell<Integer> {

        private final StackPane grip = new StackPane(Icons.grip());
        private final ComboBox<Action> actionBox = new ComboBox<>();
        private final Label hash = new Label();
        private final Label message = new Label();
        private final Label into = new Label();
        private final Button editButton = Icons.button(Icons.edit(), "Edit message",
                "Edit the message this commit is reworded to");
        private final Button up = Icons.button(Icons.chevronUp(), "Move up", "Move this step earlier");
        private final Button down = Icons.button(Icons.chevronDown(), "Move down", "Move this step later");
        private final HBox row;

        /**
         * Suppresses the picker's handler while it is filled from the plan.
         *
         * <p>A cell is refilled constantly - on scroll, on every {@code refresh()} an edit
         * itself triggers - and whether a programmatic {@code setValue} re-fires
         * {@code onAction} is a ComboBox implementation detail. Where it does fire, the
         * cell would re-apply the action it was just given: a no-op plan edit and a
         * redundant refresh, which is why nothing caught it, and exactly the kind of
         * near-miss that stops being a no-op the moment {@code apply} does any more.
         */
        private boolean updating;

        StepCell() {
            getStyleClass().add("rebase-step-cell");
            // As wide as the frame, not as its longest subject.
            setPrefWidth(0);

            grip.getStyleClass().add("rebase-grip");
            grip.setCursor(Cursor.OPEN_HAND);
            Tooltip.install(grip, new Tooltip("Drag to move this step"));

            // Enum order, not set order, so the picker reads the same however the
            // allowed subset was written down.
            actionBox.getItems().setAll(
                    Arrays.stream(Action.values()).filter(allowedActions::contains).toList());
            actionBox.getStyleClass().add("rebase-action");
            actionBox.setCellFactory(view -> new ActionCell(true));
            actionBox.setButtonCell(new ActionCell(false));
            actionBox.setOnAction(e -> {
                Integer index = getItem();
                Action chosen = actionBox.getValue();
                if (index == null || chosen == null || updating) return;
                if (chosen == Action.REWORD) {
                    reword(index);
                } else {
                    apply(todo.withAction(index, chosen));
                    stepList.getSelectionModel().select(index);
                }
            });

            hash.getStyleClass().add("rebase-hash");
            hash.setMinWidth(Region.USE_PREF_SIZE);
            message.getStyleClass().add("rebase-message");
            message.setMinWidth(0);
            message.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(message, Priority.ALWAYS);
            into.getStyleClass().addAll("jfx-pill", "rebase-into");
            into.setMinWidth(Region.USE_PREF_SIZE);

            editButton.getStyleClass().addAll("btn-sm", "rebase-edit-message");
            editButton.setOnAction(e -> {
                if (getItem() != null) editMessage(todo.step(getItem()).commit().getHash(), null);
            });
            up.getStyleClass().addAll("btn-sm", "rebase-move");
            up.setOnAction(e -> move(getItem(), getItem() - 1));
            down.getStyleClass().addAll("btn-sm", "rebase-move");
            down.setOnAction(e -> move(getItem(), getItem() + 1));

            row = new HBox(UiScale.STEP, grip, actionBox, hash, message, into, editButton, up, down);
            row.getStyleClass().add("rebase-step");
            row.setAlignment(Pos.CENTER_LEFT);

            // A reworded row opens its message on a double click, as well as from its pencil.
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null
                        && getItem() < todo.size() && todo.step(getItem()).action() == Action.REWORD
                        && !(e.getTarget() instanceof Node target && inControl(target))) {
                    editMessage(todo.step(getItem()).commit().getHash(), null);
                }
            });
            installDragAndDrop();
        }

        /**
         * Rows move by their handle as well as by the buttons and Alt+arrows. The row being
         * dropped on shows a line at the edge the dragged one will land against.
         */
        private void installDragAndDrop() {
            grip.setOnDragDetected(e -> {
                Integer index = getItem();
                if (index == null) return;
                Dragboard board = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.put(STEP_INDEX, index);
                board.setContent(content);
                SnapshotParameters transparent = new SnapshotParameters();
                transparent.setFill(Color.TRANSPARENT);
                board.setDragView(row.snapshot(transparent, null), e.getX(), e.getY());
                e.consume();
            });
            setOnDragOver(e -> {
                if (e.getDragboard().hasContent(STEP_INDEX)) {
                    e.acceptTransferModes(TransferMode.MOVE);
                    boolean below = getItem() != null && e.getY() > getHeight() / 2;
                    pseudoClassStateChanged(DROP_ABOVE, !below);
                    pseudoClassStateChanged(DROP_BELOW, below);
                }
                e.consume();
            });
            setOnDragExited(e -> {
                pseudoClassStateChanged(DROP_ABOVE, false);
                pseudoClassStateChanged(DROP_BELOW, false);
            });
            setOnDragDropped(e -> {
                boolean moved = false;
                if (e.getDragboard().getContent(STEP_INDEX) instanceof Integer from && from < todo.size()) {
                    move(from, dropTarget(from, getItem(), e.getY() > getHeight() / 2, todo.size()));
                    moved = true;
                }
                e.setDropCompleted(moved);
                e.consume();
            });
        }

        @Override
        protected void updateItem(Integer index, boolean empty) {
            super.updateItem(index, empty);
            if (empty || index == null || index >= todo.size()) {
                setGraphic(null);
                return;
            }

            Step step = todo.step(index);
            updating = true;
            actionBox.setValue(step.action());
            updating = false;
            actionBox.getStyleClass().removeIf(c -> c.startsWith("rebase-action-"));
            actionBox.getStyleClass().add("rebase-action-" + step.action().token());

            hash.setText(step.shortId());
            // A reworded commit shows the summary it is being given; the old one is a hover away.
            String subject = step.commit().getSubject();
            boolean reworded = step.action() == Action.REWORD
                    && step.message() != null && !step.message().isBlank();
            message.setText(reworded ? MessageEditor.split(step.message()).summary() : subject);
            message.setTooltip(reworded ? new Tooltip("Reworded from: " + subject) : null);

            // A dropped commit is struck through and a folded one indented under the commit
            // it joins, so the plan reads at a glance.
            row.getStyleClass().removeAll("rebase-row-drop", "rebase-row-fold", "rebase-row-reword");
            if (step.action() == Action.DROP) {
                row.getStyleClass().add("rebase-row-drop");
            } else if (step.action().foldsUpward()) {
                row.getStyleClass().add("rebase-row-fold");
            } else if (reworded) {
                row.getStyleClass().add("rebase-row-reword");
            }

            int leader = step.action().foldsUpward() ? leaderOf(todo, index) : -1;
            into.setText(leader >= 0 ? "into " + todo.step(leader).shortId() : "");
            show(into, leader >= 0);
            show(editButton, step.action() == Action.REWORD);

            up.setDisable(index == 0);
            down.setDisable(index == todo.size() - 1);
            setGraphic(row);
        }
    }

    /** Selects the row at {@code index}. For the dialog tour. */
    void selectForTest(int index) {
        stepList.getSelectionModel().select(index);
    }
}
