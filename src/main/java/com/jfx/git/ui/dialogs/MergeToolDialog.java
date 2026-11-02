package com.jfx.git.ui.dialogs;

import com.jfx.git.ui.CodeStyler;
import com.jfx.git.ui.DiffTextArea;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.UiScale;
import com.jfx.git.ui.dialogs.ConflictDocument.Conflict;
import com.jfx.git.ui.dialogs.ConflictDocument.Located;
import com.jfx.git.ui.dialogs.ConflictDocument.Range;
import com.jfx.git.ui.dialogs.ConflictRegions.Resolution;
import com.jfx.git.ui.dialogs.ConflictRegions.Slot;
import com.jfx.git.ui.dialogs.ConflictRegions.State;
import com.jfx.git.ui.syntax.HighlightLane;
import com.jfx.git.ui.syntax.Highlighter;

import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogEvent;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.undo.UndoManager;
import org.fxmisc.undo.UndoManagerFactory;
import org.reactfx.EventSource;
import org.reactfx.collection.LiveList;
import org.reactfx.value.Val;
import org.reactfx.value.Var;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Three-way merge editor for a conflicted file.
 *
 * <p>Returns the resolved text when the user saves, so the caller can write it back
 * and stage the file. The previous version was a bare {@code Stage} whose save button
 * only called {@code close()} - the edits were discarded and it was never wired to a
 * real conflict in the first place.
 *
 * <h2>The result is a clean file</h2>
 * The result pane used to show the working-tree file as git left it, markers and all, and
 * resolving meant editing {@code <<<<<<<}, {@code =======} and {@code >>>>>>>} out by hand
 * - or leaving half of them behind. It shows the file without them now: the text both
 * sides agree on, and in place of each conflict what has been chosen for it. A conflict
 * nobody has decided shows the common ancestor's lines, tinted as unresolved, and goes
 * back to disk as git wrote it, markers included, so a draft stays conflicted for git and
 * opens the same way next time ({@link ConflictDocument}, {@link ConflictRegions}).
 *
 * <h2>The alternatives are in the side panes</h2>
 * With the sides gone from the result, the Ours and Theirs panes carry them: each
 * conflict's lines tinted in that side's colour, scrolled to whichever conflict is being
 * worked on, with a tick box beside every line. A conflict's result is the ticked lines
 * of ours, then the ticked lines of theirs; Use ours, Use theirs and Use both tick a whole
 * side. Typing into a conflict's lines resolves it with what was typed.
 *
 * <h2>Why the panes are code areas</h2>
 * They used to be bare text areas with no style class at all, which rendered as black
 * proportional text on white inside a dialog the rest of the application draws dark. They
 * are the diff viewer's own surface now: the same theme, font, zoom and syntax colours as
 * every other place this application shows source.
 *
 * <h2>Saving is not resolving</h2>
 * In a conflict, Save draft writes the result to the working tree and leaves the file
 * conflicted; Mark resolved writes it and stages it. A long merge can be put down and
 * picked up again without having to pretend it is finished. {@link #savedAsDraft()} says
 * which one the returned text is for.
 */
public class MergeToolDialog extends Dialog<String> {

    private final DiffTextArea resultArea;
    private final DiffTextArea oursArea;
    private final DiffTextArea theirsArea;
    private final DiffTextArea baseArea;
    /** Ctrl+F, Ctrl+H and Ctrl+G over the panes; see {@link com.jfx.git.ui.FindBar}. */
    private final com.jfx.git.ui.FindBar findBar;
    private final Label conflictSummary = new Label();
    private final Button previousConflict;
    private final Button nextConflict;
    private final Button useOurs;
    private final Button useTheirs;
    private final Button useBoth;
    /** Ticks a side's lines in every conflict; in the side's pane header. */
    private final CheckBox allOurs = new CheckBox();
    private final CheckBox allTheirs = new CheckBox();

    /** Whether the text is a git conflict, rather than two versions being compared. */
    private final boolean conflictMode;
    /** The working-tree file taken apart; null when comparing. */
    private final ConflictDocument document;
    /** Where each conflict sits in the three versions, and its ancestor's lines. */
    private final List<Located> located;

    /** Each conflict's lines in the result, in order; empty once a whole file is taken. */
    private List<Slot> slots = List.of();
    /** What each slot's lines amount to, worked out from the text after every edit. */
    private List<State> states = List.of();
    /**
     * The result's text as the last change left it. The slots are moved along by each
     * change, and a change's paragraphs are counted in the text as it was before it -
     * which the area no longer has by the time it says what changed.
     */
    private String mirror = "";
    /** The slots a programmatic edit, an undo or a redo puts in place of moving them. */
    private List<Slot> pendingSlots;
    /** Each edit of the result with the slots before and after it, for undo. */
    private final EventSource<Step> steps = new EventSource<>();
    private UndoManager<Step> undo;

    /** What the gutters show, published after each repaint. */
    private final Var<Marks> marks = Var.newSimpleVar(Marks.NONE);
    /** The same, as of the last edit - what a tick box re-reads after it is pressed. */
    private Marks marksNow = Marks.NONE;
    private boolean paintQueued;
    /** The slot the side panes were last scrolled to, so they scroll only when it changes. */
    private int revealed = -1;

    /**
     * Whether anything has been typed into the result since it was last filled wholesale.
     *
     * <p>Tracked here rather than asked of the undo manager, and the narrower question
     * anyway: what the whole-file buttons must not silently discard is work done here -
     * typing, and choosing a side for a conflict.
     */
    private boolean resultEdited;

    /** Whether the dialog closed on Save draft rather than on the accept button. */
    private boolean savedAsDraft;

    private final String path;
    private final Highlighter highlighter = new Highlighter();

    private final SplitPane topSplit;
    private final VBox oursBox;
    private final VBox theirsBox;
    private final VBox baseBox;
    private final ToggleButton showBase = new ToggleButton("Base", Icons.eyeClosed());
    private final HBox toolbar;
    private final MenuButton take;

    /**
     * What the dialog is reconciling, and what to call the two sides.
     *
     * <p>The wording is not decoration. This dialog was written for a git conflict, where
     * "Ours", "Theirs" and "Mark resolved" are exactly right, and it is now also used to
     * reconcile an editor buffer against a file that changed on disk - where nothing is
     * being marked resolved and neither side is "theirs" in any sense the user would
     * recognise.
     *
     * @param conflictMode whether the text carries git conflict markers. False hides the
     *                     conflict navigation and skips the unresolved-marker check, which
     *                     would otherwise announce "No conflict markers left" over two
     *                     files that merely differ
     * @param path         the file, for choosing its syntax colours; null for none
     */
    public record Labels(String title, String oursTitle, String theirsTitle,
                         String acceptText, boolean conflictMode, String path) {

        public Labels(String title, String oursTitle, String theirsTitle,
                      String acceptText, boolean conflictMode) {
            this(title, oursTitle, theirsTitle, acceptText, conflictMode, null);
        }

        /** The original wording, for an actual merge conflict. */
        public static Labels forConflict(String filename, String oursLabel, String theirsLabel) {
            return new Labels("Resolve conflict - " + filename,
                    sideTitle("Ours", oursLabel, "current branch"),
                    sideTitle("Theirs", theirsLabel, "incoming"),
                    "Mark resolved", true, filename);
        }
    }

    /** Which part of a conflict to keep. */
    public enum Choice { OURS, THEIRS, BOTH }

    /**
     * "Resolve conflict - src/shop/Payment.java" as the header shows it: the action and
     * the file's name for the title, its folder for the subtitle. The window's own title
     * keeps the whole string. A title with no path in it is shown as it is.
     */
    static String[] headingFor(String title) {
        int dash = title.lastIndexOf(" - ");
        if (dash < 0) {
            return new String[]{title, null};
        }
        String path = title.substring(dash + 3);
        int slash = path.lastIndexOf('/');
        String file = path.substring(slash + 1);
        return new String[]{title.substring(0, dash) + " `" + file + "`",
                slash > 0 ? "In `" + path.substring(0, slash) + "`" : null};
    }

    /** Built from {@link Labels}, so the accept button can say what it actually does. */
    private final ButtonType accept;
    /** Writes the result without resolving; only offered for a real conflict. */
    // LEFT: a "come back to it later" action belongs at the other end of the footer from
    // the answer. As APPLY, the button bar placed it after Cancel.
    private final ButtonType saveDraft = new ButtonType("Save draft", ButtonBar.ButtonData.LEFT);

    /** How long typing has to pause before the result is re-coloured. */
    private static final Duration RESTYLE_DEBOUNCE = Duration.ofMillis(150);

    /** What the result shows beside a conflict that has no lines of its own to show. */
    static final String PLACEHOLDER_CAPTION = "Unresolved conflict: tick lines in Ours or Theirs";

    /**
     * @param filename      conflicted path, shown in the title
     * @param oursContent   index stage 2 - the current branch's version
     * @param theirsContent index stage 3 - the incoming version
     * @param baseContent   index stage 1 - the common ancestor
     * @param mergedContent working-tree content, normally carrying conflict markers
     */
    public MergeToolDialog(String filename,
                           String oursContent,
                           String theirsContent,
                           String baseContent,
                           String mergedContent) {
        this(filename, oursContent, theirsContent, baseContent, mergedContent, null, null);
    }

    /**
     * @param oursLabel   what "ours" actually is here - the branch name, or the commit
     *                    being replayed during a rebase; {@code null} falls back to
     *                    generic wording
     * @param theirsLabel the same for the incoming side
     */
    public MergeToolDialog(String filename,
                           String oursContent,
                           String theirsContent,
                           String baseContent,
                           String mergedContent,
                           String oursLabel,
                           String theirsLabel) {
        this(oursContent, theirsContent, baseContent, mergedContent,
                Labels.forConflict(filename, oursLabel, theirsLabel));
    }

    /**
     * The general form: two versions of one file, a common ancestor, and an editable
     * result.
     *
     * @param mergedContent what the result pane starts from
     * @param labels        what this particular reconciliation is called
     */
    public MergeToolDialog(String oursContent,
                           String theirsContent,
                           String baseContent,
                           String mergedContent,
                           Labels labels) {
        JfxDialog.shell(this, JfxDialog.Size.XL, labels.title());
        String[] heading = headingFor(labels.title());
        JfxDialog.header(this, Icons.merge(), JfxDialog.Tone.CAUTION, heading[0], heading[1]).ruled();
        setResizable(true);
        this.accept = new ButtonType(labels.acceptText(), ButtonBar.ButtonData.OK_DONE);
        this.path = labels.path();
        this.conflictMode = labels.conflictMode();

        oursArea = readOnlyArea(oursContent);
        theirsArea = readOnlyArea(theirsContent);
        baseArea = readOnlyArea(baseContent);

        resultArea = codeArea();
        resultArea.setEditable(true);
        resultArea.setWrapText(false);
        if (conflictMode) {
            // pick() falls back to the base, then to ours, when there is no merged text: a
            // git conflict's working copy can simply be missing.
            document = ConflictDocument.parse(pick(mergedContent, baseContent, oursContent));
            located = document.locate(oursContent, theirsContent, baseContent);
            List<List<String>> undecided = new ArrayList<>();
            for (Located at : located) {
                undecided.add(ConflictRegions.undecidedLines(at.shown()));
            }
            List<Integer> starts = new ArrayList<>();
            List<String> clean = document.interleave(undecided, starts);
            List<Slot> initial = new ArrayList<>();
            for (int i = 0; i < starts.size(); i++) {
                initial.add(new Slot(i, starts.get(i), undecided.get(i).size(), Resolution.UNDECIDED));
            }
            slots = List.copyOf(initial);
            mirror = String.join("\n", clean);
            resultArea.replaceText(mirror);
            // An undo history of its own, whose every step carries where the conflicts were
            // before and after it: undoing a choice has to put back what was decided as well
            // as the text. Plain text only, so re-colouring is never an edit to undo.
            undo = UndoManagerFactory.unlimitedHistorySingleChangeUM(steps, Step::invert, this::applyStep,
                    Step::mergeWith, Step::isIdentity, Duration.ofMillis(500));
            resultArea.setUndoManager(undo);
            resultArea.multiPlainChanges().subscribe(this::onResultChanges);
            resultArea.setParagraphGraphicFactory(resultGutter(resultArea));
            oursArea.setParagraphGraphicFactory(sideGutter(oursArea, true));
            theirsArea.setParagraphGraphicFactory(sideGutter(theirsArea, false));
            resultArea.caretPositionProperty().addListener((obs, old, value) -> updateRegionActions());
        } else {
            document = null;
            located = List.of();
            // A reconciliation starts from the user's own buffer: an emptied buffer is a
            // deliberate edit, and pre-filling the ancestor in its place meant accepting
            // without looking saved the text they had removed.
            resultArea.replaceText(mergedContent == null ? "" : mergedContent);
            // Loading the text is not an edit to undo back past.
            resultArea.getUndoManager().forgetHistory();
            resultArea.plainTextChanges().subscribe(change -> resultEdited = true);
        }
        resultArea.plainTextChanges().successionEnds(RESTYLE_DEBOUNCE)
                .subscribe(change -> styleResult());

        // Each pane's header in git's colour for its side - ours the accent, theirs amber,
        // the ancestor neutral - the same tints each conflict's lines are painted in.
        oursBox = titledBox(labels.oursTitle(), oursArea, "ours", conflictMode ? allOurs : null);
        theirsBox = titledBox(labels.theirsTitle(), theirsArea, "theirs", conflictMode ? allTheirs : null);
        // In a comparison the third version is no ancestor: it is the file as it was opened.
        baseBox = titledBox(labels.conflictMode() ? "Base (common ancestor)" : "Original (when opened)",
                baseArea, "base", null);
        topSplit = new SplitPane(oursBox, theirsBox);
        topSplit.getStyleClass().add("merge-split");
        topSplit.setOrientation(Orientation.HORIZONTAL);
        topSplit.setDividerPositions(0.5);

        SplitPane mainSplit = new SplitPane(topSplit, titledBox("Result (editable)", resultArea, "result", null));
        mainSplit.getStyleClass().add("merge-split");
        mainSplit.setOrientation(Orientation.VERTICAL);
        mainSplit.setDividerPositions(0.5);
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        // Where you are as well as how many are left: "2 of 3 conflicts", between the two
        // steps that move through them.
        conflictSummary.getStyleClass().add("merge-conflict-count");
        conflictSummary.setMinWidth(Region.USE_PREF_SIZE);
        previousConflict = new Button("Previous", Icons.chevronUp());
        previousConflict.setTooltip(new Tooltip("Go to the previous conflict"));
        previousConflict.setOnAction(e -> stepConflict(-1));
        nextConflict = new Button("Next", Icons.chevronDown());
        nextConflict.setTooltip(new Tooltip("Go to the next conflict"));
        nextConflict.setOnAction(e -> stepConflict(1));
        for (Button step : List.of(previousConflict, nextConflict)) {
            step.getStyleClass().add("btn-ghost");
            step.setMinWidth(Region.USE_PREF_SIZE);
        }

        // One conflict at a time: the choice that used to be missing here, where the only
        // way to take a side was for the whole file - discarding every other conflict's
        // resolution along with it. Joined into one control, each with its side's colour.
        // Each ticks a whole side of the conflict in the panes above, and is marked while
        // the ticks are exactly that.
        useOurs = new Button("Use ours", sideDot("ours"));
        useOurs.setTooltip(new Tooltip("Resolve this conflict with the current branch's lines"));
        useOurs.setOnAction(e -> resolveActive(Choice.OURS));
        useTheirs = new Button("Use theirs", sideDot("theirs"));
        useTheirs.setTooltip(new Tooltip("Resolve this conflict with the incoming lines"));
        useTheirs.setOnAction(e -> resolveActive(Choice.THEIRS));
        useBoth = new Button("Use both", new HBox(2, sideDot("ours"), sideDot("theirs")));
        useBoth.setTooltip(new Tooltip("Keep both sides' lines: ours, then theirs"));
        useBoth.setOnAction(e -> resolveActive(Choice.BOTH));
        useOurs.getStyleClass().add("left-pill");
        useTheirs.getStyleClass().add("center-pill");
        useBoth.getStyleClass().add("right-pill");
        HBox useGroup = new HBox(useOurs, useTheirs, useBoth);
        useGroup.getStyleClass().add("merge-use");
        for (Button use : List.of(useOurs, useTheirs, useBoth)) {
            use.setMinWidth(Region.USE_PREF_SIZE);
        }

        // The whole file, in one menu. It was a second row of three buttons - "Take ours,
        // Take theirs, Take base" under "Use ours, Use theirs, Use both" - and only the
        // tooltips said which row worked on one conflict and which on everything.
        MenuItem takeOurs = new MenuItem("Take ours");
        takeOurs.setOnAction(e -> replaceWholeResult("ours", oursContent));
        MenuItem takeTheirs = new MenuItem("Take theirs");
        takeTheirs.setOnAction(e -> replaceWholeResult("theirs", theirsContent));
        MenuItem takeBase = new MenuItem("Take base");
        boolean hasBase = baseContent != null && !baseContent.isEmpty();
        // A comparison's original is only worth a pane when it differs from both sides: a
        // recovered draft is compared with the file on disk, and passes that file again.
        boolean offersBase = hasBase && (labels.conflictMode()
                || (!folded(baseContent).equals(folded(theirsContent))
                        && !folded(baseContent).equals(folded(oursContent))));
        takeBase.setDisable(!hasBase);
        takeBase.setOnAction(e -> replaceWholeResult("the common ancestor", baseContent));
        take = wholeFileMenu(takeOurs, takeTheirs, takeBase);

        // The ancestor is what makes a three-way conflict readable - what did each side
        // change it from - but it is a third pane in a dialog that already has three, so
        // it is there when asked for.
        showBase.setTooltip(new Tooltip(labels.conflictMode()
                ? "Show the common ancestor between the two sides"
                : "Show the file as it was when it was opened"));
        if (!labels.conflictMode()) {
            showBase.setText("Original");
        }
        showBase.getStyleClass().add("merge-base-toggle");
        showBase.setMinWidth(Region.USE_PREF_SIZE);
        showBase.setVisible(offersBase);
        showBase.setManaged(offersBase);
        showBase.selectedProperty().addListener((obs, old, on) -> {
            showBase.setGraphic(on ? Icons.eyeOpen() : Icons.eyeClosed());
            if (on) {
                topSplit.getItems().setAll(oursBox, baseBox, theirsBox);
                topSplit.setDividerPositions(1.0 / 3, 2.0 / 3);
                // Shown on the conflict being worked on, like the other two.
                int active = activeRegion();
                if (active >= 0) {
                    Platform.runLater(() -> revealIn(baseArea, slotRange(active, baseArea)));
                }
            } else {
                topSplit.getItems().setAll(oursBox, theirsBox);
                topSplit.setDividerPositions(0.5);
            }
        });

        // One row: where you are and what to do with this conflict on the left, the whole
        // file on the right. Not a ToolBar, which quietly moves whatever does not fit into
        // an overflow popup - for a dialog this narrow, the resolution buttons.
        Separator divider = new Separator(Orientation.VERTICAL);
        HBox conflictControls = new HBox(UiScale.STEP, conflictSummary, previousConflict, nextConflict,
                divider, useGroup);
        conflictControls.setAlignment(Pos.CENTER_LEFT);
        // Stepping between conflict regions means nothing when there are no markers to
        // step between; two files that merely differ have no regions, only a difference -
        // so a comparison's row says where the result starts from and how far apart the two
        // versions are, and marks the lines that differ in each.
        if (labels.conflictMode()) {
            toolbar = JfxDialog.toolbar(conflictControls, JfxDialog.toolbarSpacer(), take, showBase);
        } else {
            List<Edit> differences = lineEdits(oursContent, theirsContent);
            tint(oursArea, differences, true);
            tint(theirsArea, differences, false);
            Label count = new Label(differences.isEmpty() ? "No differences"
                    : differences.size() == 1 ? "1 difference" : differences.size() + " differences");
            count.getStyleClass().add("merge-difference-count");
            count.setMinWidth(Region.USE_PREF_SIZE);
            toolbar = JfxDialog.toolbar(startFrom(labels, oursContent, theirsContent, baseContent, offersBase),
                    JfxDialog.toolbarSpacer(), count, showBase);
        }
        toolbar.getStyleClass().add("merge-toolbar");

        // Find over whichever pane was last clicked into - the result unless another was -
        // and replace only in the result, the one pane that is an editor.
        DiffTextArea[] searched = {resultArea};
        for (DiffTextArea pane : List.of(oursArea, theirsArea, baseArea, resultArea)) {
            pane.focusedProperty().addListener((obs, was, now) -> {
                if (now) searched[0] = pane;
            });
        }
        findBar = new com.jfx.git.ui.FindBar(new com.jfx.git.ui.FindBar.Host() {
            @Override
            public List<org.fxmisc.richtext.GenericStyledArea<?, ?, ?>> areas() {
                return List.of(searched[0]);
            }

            @Override
            public boolean editable() {
                return searched[0] == resultArea;
            }

            @Override
            public int lineCount() {
                return searched[0].getParagraphs().size();
            }

            @Override
            public int paragraphOfLine(int line) {
                return line - 1 < searched[0].getParagraphs().size() ? line - 1 : -1;
            }
        });
        findBar.setPadding(new javafx.geometry.Insets(4, 20, 4, 20));
        getDialogPane().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (!e.isShortcutDown() || e.isAltDown()) return;
            switch (e.getCode()) {
                case F -> findBar.showFind(false);
                case H -> {
                    // Replacing is for the result; asked for anywhere, it goes there.
                    searched[0] = resultArea;
                    findBar.showFind(true);
                }
                case G -> findBar.showGoToLine();
                default -> {
                    return;
                }
            }
            e.consume();
        });

        // Edge to edge under the toolbar: the panes meet the window's sides, divided by
        // hairlines rather than boxed one inside the other inside the dialog's margin.
        JfxDialog.workspace(this, toolbar, new VBox(findBar, mainSplit));
        if (labels.conflictMode()) {
            getDialogPane().getButtonTypes().addAll(saveDraft, accept, ButtonType.CANCEL);
            Button draftButton = (Button) getDialogPane().lookupButton(saveDraft);
            draftButton.setTooltip(new Tooltip(
                    "Write the result to the file and keep it conflicted, to finish later"));
        } else {
            getDialogPane().getButtonTypes().addAll(accept, ButtonType.CANCEL);
        }
        try {
            getDialogPane().getStylesheets().add(
                    getClass().getResource("/diff-styles.css").toExternalForm());
        } catch (Exception ignored) {
            // Styling is optional; the dialog is still usable without it.
        }

        // Filters rather than converts: a result that would still carry markers needs a
        // decision before it becomes the file, and setResultConverter has no way to
        // refuse. Only for a real conflict - a source file that legitimately contains
        // marker-like text would otherwise be interrogated about it every time it was
        // reconciled - and only for resolving: a draft is expected to still have conflicts.
        if (labels.conflictMode()) {
            Button resolveButton = (Button) getDialogPane().lookupButton(accept);
            resolveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                if (!confirmUnresolvedMarkers()) {
                    event.consume();
                }
            });
        }

        styleSide(oursArea);
        styleSide(theirsArea);
        styleSide(baseArea);
        if (conflictMode) {
            allOurs.setTooltip(new Tooltip("Tick this side's lines in every conflict"));
            allOurs.setOnAction(e -> tickAll(true));
            allTheirs.setTooltip(new Tooltip("Tick this side's lines in every conflict"));
            allTheirs.setOnAction(e -> tickAll(false));
            for (DiffTextArea side : List.of(oursArea, theirsArea, baseArea)) {
                side.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
                    if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress()) {
                        activateFromSide(side);
                    }
                });
            }
            // Open on the first conflict, scrolled into view, so it is what the count names
            // and what Use ours acts on. Loading the text left the caret at its end: the
            // dialog said "2 of 2" and the buttons worked on the last conflict first.
            if (!slots.isEmpty()) {
                goTo(slots.get(0));
            }
            refreshConflicts();
            // The side panes can only be scrolled once they have been laid out.
            addEventHandler(DialogEvent.DIALOG_SHOWN, e -> Platform.runLater(() -> {
                revealed = -1;
                updateRegionActions();
                int active = activeRegion();
                if (active >= 0) {
                    Slot slot = slots.get(active);
                    revealIn(resultArea, new Range(slot.start(), slot.end()));
                }
            }));
        }
        styleResult();
        resultEdited = false;
        setResultConverter(button -> {
            savedAsDraft = button == saveDraft;
            return button == accept || button == saveDraft ? outputText() : null;
        });
    }

    /** Whether the returned text is a draft to write, rather than a resolution to stage. */
    public boolean savedAsDraft() {
        return savedAsDraft;
    }

    /**
     * Offers to resolve the file in the external merge tool instead: a toolbar button that
     * closes this window without writing anything, then runs {@code open}. Only for a
     * conflict, and only worth calling when one is configured.
     */
    public void offerExternalTool(Runnable open) {
        if (!conflictMode || toolbar.lookup(".merge-external") != null) {
            return;
        }
        // An icon beside Take: with words it pushed the toolbar past the window's width
        // once the count said how many were left.
        Button external = new Button(null, Icons.external());
        external.getStyleClass().addAll("btn-ghost", "merge-external");
        external.setMinWidth(Region.USE_PREF_SIZE);
        external.setAccessibleText("Open in external merge tool");
        external.setTooltip(new Tooltip("Open in external merge tool: closes this and resolves the file"
                + " in the tool chosen in Settings, External Tools. Nothing here is written."));
        external.setOnAction(e -> {
            // Closed first, with no result, so the caller writes nothing; the tool is
            // started once this window is gone.
            close();
            Platform.runLater(open);
        });
        toolbar.getChildren().add(toolbar.getChildren().indexOf(take), external);
    }

    /**
     * The file the result stands for: the result's text, with every conflict nobody has
     * decided written back as git wrote it, markers included.
     */
    private String outputText() {
        if (!conflictMode) {
            return resultArea.getText();
        }
        return String.join("\n", ConflictRegions.output(resultLines(), slots, states, document.conflicts()));
    }

    // ------------------------------------------------------------------
    // Following the result's edits
    // ------------------------------------------------------------------

    /**
     * One undoable edit of the result: its text changes, and where the conflicts were
     * before and after. Undoing puts back the slots - what was decided as well as where -
     * so undoing a choice leaves the conflict as it was, ticks and all.
     */
    static final class Step {
        final List<PlainTextChange> changes;
        final List<Slot> before;
        final List<Slot> after;

        Step(List<PlainTextChange> changes, List<Slot> before, List<Slot> after) {
            this.changes = List.copyOf(changes);
            this.before = before;
            this.after = after;
        }

        Step invert() {
            List<PlainTextChange> inverted = new ArrayList<>(changes.size());
            for (int i = changes.size() - 1; i >= 0; i--) {
                inverted.add(changes.get(i).invert());
            }
            return new Step(inverted, after, before);
        }

        /** Typing merges into one step, as it does in any editor; a choice never does. */
        Optional<Step> mergeWith(Step later) {
            if (changes.size() != 1 || later.changes.size() != 1) {
                return Optional.empty();
            }
            return changes.get(0).mergeWith(later.changes.get(0))
                    .map(merged -> new Step(List.of(merged), before, later.after));
        }

        boolean isIdentity() {
            return changes.stream().allMatch(PlainTextChange::isIdentity) && before.equals(after);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Step step && changes.equals(step.changes) && after.equals(step.after);
        }

        @Override
        public int hashCode() {
            return Objects.hash(changes, after);
        }
    }

    /**
     * Moves the slots along with an edit - or puts in place the ones a choice, an undo or
     * a redo brought - and records the step for undo.
     */
    private void onResultChanges(List<PlainTextChange> changes) {
        List<Slot> before = slots;
        List<Slot> moved = slots;
        for (PlainTextChange change : changes) {
            int position = change.getPosition();
            String removed = change.getRemoved();
            int p0 = breaks(mirror, 0, position);
            int c0 = position - (mirror.lastIndexOf('\n', position - 1) + 1);
            int p1 = p0 + breaks(removed, 0, removed.length());
            mirror = mirror.substring(0, position) + change.getInserted()
                    + mirror.substring(position + removed.length());
            if (pendingSlots == null) {
                moved = ConflictRegions.afterEdit(moved, p0, c0, p1, change.getInserted(),
                        breaks(mirror, 0, mirror.length()) + 1);
            }
        }
        if (pendingSlots != null) {
            moved = pendingSlots;
            pendingSlots = null;
        }
        slots = moved;
        resultEdited = true;
        steps.push(new Step(changes, before, slots));
        refreshConflicts();
    }

    /** Replays an undo or redo step: its text, and the slots it left. */
    private void applyStep(Step step) {
        if (step.changes.isEmpty()) {
            slots = step.after;
            steps.push(step);
            refreshConflicts();
            return;
        }
        pendingSlots = step.after;
        if (step.changes.size() == 1) {
            PlainTextChange change = step.changes.get(0);
            resultArea.replaceText(change.getPosition(), change.getRemovalEnd(), change.getInserted());
        } else {
            var multi = resultArea.createMultiChange(step.changes.size());
            for (PlainTextChange change : step.changes) {
                multi.replaceTextAbsolutely(change.getPosition(), change.getRemovalEnd(), change.getInserted());
            }
            multi.commit();
        }
        PlainTextChange last = step.changes.get(step.changes.size() - 1);
        resultArea.moveTo(Math.min(last.getPosition() + Math.max(0, last.getNetLength()), resultArea.getLength()));
    }

    /**
     * Puts {@code text} in the result, with {@code newSlots} as where the conflicts are
     * now, as one undoable edit. Only the span that changed is replaced, so the caret and
     * the scroll position above it stay where they were.
     */
    private void replaceResult(String text, List<Slot> newSlots) {
        undo.preventMerge();
        if (text.equals(mirror)) {
            // Nothing to type - unticking back to text the ancestor happens to share - but
            // still a step, so undo finds what was decided.
            if (!newSlots.equals(slots)) {
                List<Slot> before = slots;
                slots = newSlots;
                steps.push(new Step(List.of(), before, newSlots));
                refreshConflicts();
            }
        } else {
            int prefix = 0;
            int limit = Math.min(mirror.length(), text.length());
            while (prefix < limit && mirror.charAt(prefix) == text.charAt(prefix)) prefix++;
            int suffix = 0;
            while (suffix < limit - prefix && mirror.charAt(mirror.length() - 1 - suffix)
                    == text.charAt(text.length() - 1 - suffix)) suffix++;
            pendingSlots = newSlots;
            resultArea.replaceText(prefix, mirror.length() - suffix, text.substring(prefix, text.length() - suffix));
        }
        undo.preventMerge();
    }

    /**
     * Gives some conflicts new resolutions, as one undoable edit: each one's lines become
     * what its resolution says.
     */
    private void resolve(Map<Integer, Resolution> changes) {
        List<String> lines = resultLines();
        List<String> out = new ArrayList<>(lines.size());
        List<Slot> newSlots = new ArrayList<>(slots.size());
        int cursor = 0;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            out.addAll(lines.subList(cursor, slot.start()));
            Resolution resolution = changes.getOrDefault(i, slot.resolution());
            List<String> body = changes.containsKey(i)
                    ? ConflictRegions.expected(conflict(slot), located.get(slot.conflict()).shown(), resolution)
                    : lines.subList(slot.start(), slot.end());
            newSlots.add(new Slot(slot.conflict(), out.size(), body.size(), resolution));
            out.addAll(body);
            cursor = slot.end();
        }
        out.addAll(lines.subList(cursor, lines.size()));
        replaceResult(String.join("\n", out), List.copyOf(newSlots));
    }

    // ------------------------------------------------------------------
    // Conflict regions
    // ------------------------------------------------------------------

    /**
     * Works out what each conflict amounts to and updates everything that says so: the
     * count, the buttons, the header ticks, and - after this edit has finished - the tints
     * and the gutters.
     */
    private void refreshConflicts() {
        List<String> lines = resultLines();
        List<State> worked = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            worked.add(ConflictRegions.state(conflict(slot), located.get(slot.conflict()).shown(),
                    slot.resolution(), lines.subList(slot.start(), slot.end())));
        }
        states = List.copyOf(worked);
        previousConflict.setDisable(slots.isEmpty());
        nextConflict.setDisable(slots.isEmpty());
        updateRegionActions();
        syncHeaderTick(allOurs, true);
        syncHeaderTick(allTheirs, false);
        marksNow = buildMarks();
        schedulePaint();
    }

    private Conflict conflict(Slot slot) {
        return document.conflicts().get(slot.conflict());
    }

    /**
     * The conflict the per-conflict buttons act on: the one the caret is in, or else the
     * next one below it, or else the last one above.
     */
    private int activeRegion() {
        if (slots.isEmpty()) {
            return -1;
        }
        int caret = resultArea.getCurrentParagraph();
        int inside = slotAt(caret);
        if (inside >= 0) {
            return inside;
        }
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).start() > caret) {
                return i;
            }
        }
        return slots.size() - 1;
    }

    /** The slot whose lines {@code paragraph} is on, or an empty one anchored there; -1 for none. */
    private int slotAt(int paragraph) {
        int owner = ConflictRegions.ownerOf(slots, paragraph);
        if (owner >= 0) {
            return owner;
        }
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).count() == 0 && slots.get(i).start() == paragraph) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The per-conflict buttons, and the count that says which conflict they act on: "2 of
     * 3 conflicts", following the caret, with how many are left once some are not.
     */
    private void updateRegionActions() {
        if (!conflictMode) {
            return;
        }
        int active = activeRegion();
        boolean none = active < 0;
        useOurs.setDisable(none);
        useTheirs.setDisable(none);
        useBoth.setDisable(none);
        State activeState = none || active >= states.size() ? null : states.get(active);
        markChosen(useOurs, activeState == State.OURS);
        markChosen(useTheirs, activeState == State.THEIRS);
        markChosen(useBoth, activeState == State.BOTH);

        int total = slots.size();
        long left = states.stream().filter(state -> state == State.UNRESOLVED).count();
        conflictSummary.setText(left == 0 ? "All conflicts resolved"
                : (active + 1) + " of " + total + (total == 1 ? " conflict" : " conflicts")
                        + (left < total ? " · " + left + " left" : ""));
        conflictSummary.setGraphic(left == 0 ? Icons.check() : null);
        // Amber while any are left - work in progress, not an error - and green once none are.
        conflictSummary.getStyleClass().removeAll("merge-conflicts-remaining", "merge-conflicts-resolved");
        conflictSummary.getStyleClass().add(left > 0 ? "merge-conflicts-remaining" : "merge-conflicts-resolved");
        if (active != revealed && active >= 0) {
            schedulePaint();
        }
    }

    private static void markChosen(Button button, boolean chosen) {
        if (chosen && !button.getStyleClass().contains("merge-use-chosen")) {
            button.getStyleClass().add("merge-use-chosen");
        } else if (!chosen) {
            button.getStyleClass().remove("merge-use-chosen");
        }
    }

    /** Moves the caret to the next or previous conflict, wrapping around. */
    private void stepConflict(int direction) {
        if (slots.isEmpty()) {
            return;
        }
        int caret = resultArea.getCurrentParagraph();
        int inside = slotAt(caret);
        int target;
        if (inside >= 0) {
            target = Math.floorMod(inside + direction, slots.size());
        } else if (direction > 0) {
            target = 0;
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i).start() > caret) {
                    target = i;
                    break;
                }
            }
        } else {
            target = slots.size() - 1;
            for (int i = slots.size() - 1; i >= 0; i--) {
                if (slots.get(i).start() <= caret) {
                    target = i;
                    break;
                }
            }
        }
        goTo(slots.get(target));
        resultArea.requestFocus();
    }

    /**
     * Puts the caret on a conflict and shows it in the result with a few lines above it,
     * as the side panes do. Following the caret showed the conflict on the top row with
     * nothing of what leads into it.
     */
    private void goTo(Slot slot) {
        moveCaretTo(slot);
        revealIn(resultArea, new Range(slot.start(), slot.end()));
    }

    /** Puts the caret at the start of a conflict's lines, or where an empty one would put them. */
    private void moveCaretTo(Slot slot) {
        if (slot.start() < resultArea.getParagraphs().size()) {
            resultArea.moveTo(slot.start(), 0);
        } else {
            resultArea.moveTo(resultArea.getLength());
        }
    }

    /** Resolves the active conflict with one side, or both, as one undoable edit. */
    private void resolveActive(Choice choice) {
        int index = activeRegion();
        if (index < 0) {
            return;
        }
        if (states.get(index) == State.EDITED && !confirmReplaceEdits(1)) {
            return;
        }
        resolve(Map.of(index, Resolution.choose(choice, conflict(slots.get(index)))));
        // On to the next conflict still to decide: the caret goes there, and Next steps on
        // from it. With none left, it stays on the one just resolved.
        int next = index;
        for (int step = 1; step <= slots.size(); step++) {
            int candidate = (index + step) % slots.size();
            if (states.get(candidate) == State.UNRESOLVED) {
                next = candidate;
                break;
            }
        }
        goTo(slots.get(next));
    }

    /**
     * Ticks or clears one line of one side of a conflict. Over typing, asks first: the
     * ticks replace what was typed.
     */
    private void toggleTick(boolean oursSide, Tick tick) {
        if (tick == null || tick.slot() >= slots.size()) {
            return;
        }
        Slot slot = slots.get(tick.slot());
        boolean edited = states.get(tick.slot()) == State.EDITED;
        if (edited && !confirmReplaceEdits(1)) {
            return;
        }
        Resolution from = edited ? Resolution.UNDECIDED : slot.resolution();
        resolve(Map.of(tick.slot(), from.toggled(oursSide, tick.line())));
        Slot now = slots.get(tick.slot());
        goTo(now);
        revealOthers(tick.slot(), oursSide ? oursArea : theirsArea);
    }

    /**
     * A pane header's tick: every line of that side in every conflict, or - when they all
     * are already - none. The other side's ticks are left as they are.
     */
    private void tickAll(boolean oursSide) {
        boolean clear = sideTicked(oursSide) == Boolean.TRUE;
        Map<Integer, Resolution> changes = new HashMap<>();
        int edited = 0;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            Conflict conflict = conflict(slot);
            boolean typed = states.get(i) == State.EDITED;
            if (typed && clear) {
                continue;
            }
            Resolution from = typed ? Resolution.UNDECIDED : slot.resolution();
            java.util.Set<Integer> keep = from.decided() ? (oursSide ? from.theirs() : from.ours()) : java.util.Set.of();
            java.util.Set<Integer> side = clear ? java.util.Set.of()
                    : Resolution.all(oursSide ? conflict.ours().size() : conflict.theirs().size());
            Resolution to = clear
                    ? Resolution.picked(oursSide ? side : keep, oursSide ? keep : side)
                    : new Resolution(true, oursSide ? side : keep, oursSide ? keep : side);
            if (!to.equals(slot.resolution()) || typed) {
                changes.put(i, to);
                if (typed) edited++;
            }
        }
        if (!changes.isEmpty() && (edited == 0 || confirmReplaceEdits(edited))) {
            resolve(changes);
        }
        syncHeaderTick(oursSide ? allOurs : allTheirs, oursSide);
    }

    /**
     * Whether one side's lines are ticked in every conflict (true), in some (null), or in
     * none (false). Typed-over conflicts have no ticks.
     */
    private Boolean sideTicked(boolean oursSide) {
        boolean all = !slots.isEmpty();
        boolean any = false;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            Resolution resolution = slot.resolution();
            boolean live = i < states.size() && states.get(i) != State.EDITED && resolution.decided();
            int size = oursSide ? conflict(slot).ours().size() : conflict(slot).theirs().size();
            int ticked = live ? (oursSide ? resolution.ours() : resolution.theirs()).size() : 0;
            all &= live && ticked == size;
            any |= ticked > 0;
        }
        return all ? Boolean.TRUE : any ? null : Boolean.FALSE;
    }

    private void syncHeaderTick(CheckBox box, boolean oursSide) {
        Boolean ticked = sideTicked(oursSide);
        box.setSelected(ticked == Boolean.TRUE);
        box.setIndeterminate(ticked == null);
        box.setDisable(slots.isEmpty());
    }

    /**
     * Whether typing in some conflicts may be replaced by ticked lines. Undoable, so the
     * replacement is the default, as Take's is.
     */
    private boolean confirmReplaceEdits(int conflicts) {
        return Dialogs.destructive("Replace edits")
                .icon(Icons.merge())
                .title(conflicts == 1 ? "Replace what was typed in this conflict?"
                        : "Replace what was typed in " + conflicts + " conflicts?")
                .subtitle(path == null ? null : "`" + path + "`")
                .message("The ticked lines take the place of the typing. Ctrl+Z in the result"
                        + " brings it back.")
                .action("Replace")
                .ask();
    }

    /** How many conflicts nobody has decided. */
    private int unresolvedCount() {
        return (int) states.stream().filter(state -> state == State.UNRESOLVED).count();
    }

    /**
     * Whether a result that would still have markers in it should be accepted anyway.
     *
     * <p>Marking a file resolved is a promise to git that it holds finished content, and
     * staging {@code <<<<<<< HEAD} is how that promise gets broken. It is not forbidden,
     * only confirmed: a file can legitimately contain marker-like text - this dialog's own
     * source does - so the user is the one who knows.
     *
     * @return true to carry on with the resolution
     */
    private boolean confirmUnresolvedMarkers() {
        int remaining = unresolvedCount();
        if (remaining == 0 && !hasStrayMarkers()) {
            return true;
        }
        // Save draft is offered here as well as behind it: the message names it as the
        // way out, and it used to be a button in the window this question covers.
        ButtonType draft = new ButtonType("Save draft", ButtonBar.ButtonData.LEFT);
        boolean drafts = getDialogPane().getButtonTypes().contains(saveDraft);
        String conflicts = remaining == 1 ? "1 conflict" : remaining + " conflicts";
        // Staging marker text is the promise to git this exists to stop: Enter cancels.
        ButtonType answer = Dialogs.warn("Mark resolved")
                .icon(Icons.merge())
                .title(remaining == 0
                        ? "Mark resolved with conflict markers in it?"
                        : "Mark resolved with " + conflicts + " left?")
                .subtitle(path == null ? null : "`" + path + "`")
                .message(remaining == 0
                        ? "Marking the file resolved stages it exactly as it is, markers included. Only"
                                + " do this if the marker text belongs in the file. Save draft writes it and"
                                + " keeps it conflicted instead."
                        : "Marking the file resolved stages it with conflict markers for the "
                                + (remaining == 1 ? "unresolved conflict" : remaining + " unresolved conflicts")
                                + ". Save draft writes it and keeps it conflicted instead.")
                .buttons(drafts ? new ButtonType[]{ButtonType.OK, draft, ButtonType.CANCEL}
                        : new ButtonType[]{ButtonType.OK, ButtonType.CANCEL})
                .action("Mark resolved anyway")
                .safeDefault()
                .answer();
        if (answer == draft) {
            // After the press that asked has finished: firing the draft button inside its
            // filter would close this window under the event still being delivered.
            Button draftButton = (Button) getDialogPane().lookupButton(saveDraft);
            Platform.runLater(draftButton::fire);
            return false;
        }
        return answer == ButtonType.OK;
    }

    /**
     * Marker-like lines in the result itself. The result shows none of git's own, so
     * these were typed or pasted - or belong in the file, which the user can say.
     */
    private boolean hasStrayMarkers() {
        for (String line : resultLines()) {
            if (line.startsWith(ConflictDocument.THEIRS_MARKER) || line.equals(ConflictDocument.SPLIT_MARKER)
                    || line.startsWith(ConflictDocument.OURS_MARKER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces the entire result with one side, asking first if there is work to lose.
     * The conflicts go with it: there is nothing left to decide.
     *
     * <p>These buttons used to overwrite an edited result without a word, so a half-hour
     * of careful merging went on a mis-click.
     */
    private void replaceWholeResult(String which, String content) {
        // The replacement is one edit in the result's undo history, so Enter replaces.
        if (resultEdited && !Dialogs.destructive("Replace result")
                .icon(Icons.merge())
                .title("Replace the whole result with " + which + "?")
                .subtitle(path == null ? null : "`" + path + "`")
                .message("The changes made to the result here are discarded. Ctrl+Z in the result"
                        + " brings them back.")
                .action("Replace result")
                .ask()) {
            return;
        }
        if (conflictMode) {
            replaceResult(folded(content), List.of());
        } else {
            resultArea.replaceText(content == null ? "" : content);
        }
        // The replacement is itself the only edit now, so a second whole-file choice
        // straight after the first has nothing to warn about.
        resultEdited = false;
    }

    // ------------------------------------------------------------------
    // Side panes: where each conflict is, scrolled to the one being worked on
    // ------------------------------------------------------------------

    /** The lines of a slot's conflict in one side pane, or null when they are not there. */
    private Range slotRange(int slotIndex, DiffTextArea side) {
        if (slotIndex < 0 || slotIndex >= slots.size()) {
            return null;
        }
        Located at = located.get(slots.get(slotIndex).conflict());
        return side == oursArea ? at.ours() : side == theirsArea ? at.theirs() : at.base();
    }

    /** Makes the conflict under a click in a side pane the one being worked on. */
    private void activateFromSide(DiffTextArea side) {
        int paragraph = side.getCurrentParagraph();
        for (int i = 0; i < slots.size(); i++) {
            Range range = slotRange(i, side);
            if (range != null && range.contains(paragraph)) {
                if (i != activeRegion()) {
                    goTo(slots.get(i));
                    revealOthers(i, side);
                }
                return;
            }
        }
    }

    /** Scrolls the panes other than {@code except} to a conflict. */
    private void revealOthers(int slotIndex, DiffTextArea except) {
        revealed = slotIndex;
        for (DiffTextArea side : List.of(oursArea, theirsArea, baseArea)) {
            if (side != except) {
                revealIn(side, slotRange(slotIndex, side));
            }
        }
    }

    /**
     * Brings a conflict's first line into view, a few lines down from the top so what
     * leads into it shows - unless it is in view already, so a pane is not yanked about
     * while the caret moves within one conflict.
     */
    private static void revealIn(DiffTextArea side, Range range) {
        if (range == null || side.getParagraphs().isEmpty()) {
            return;
        }
        int line = Math.min(range.start(), side.getParagraphs().size() - 1);
        try {
            int first = side.firstVisibleParToAllParIndex();
            int last = side.lastVisibleParToAllParIndex();
            if (line >= first && line <= last - 2) {
                return;
            }
        } catch (RuntimeException notLaidOut) {
            // No rows on screen yet: scroll anyway, and the flow lands there when it is.
        }
        side.showParagraphAtTop(Math.max(0, line - 3));
    }

    // ------------------------------------------------------------------
    // Colours and gutters
    // ------------------------------------------------------------------

    /**
     * Repaints the tints and the gutters once the edit under way has finished: restyling a
     * paragraph from inside the area's own change notification would hand its other
     * listeners a second change before they had seen the first.
     */
    private void schedulePaint() {
        if (paintQueued) {
            return;
        }
        paintQueued = true;
        Platform.runLater(() -> {
            paintQueued = false;
            paintRegions();
            marks.setValue(marksNow);
            int active = activeRegion();
            if (active >= 0 && active != revealed) {
                revealOthers(active, null);
            }
        });
    }

    /**
     * Tints each conflict's lines: in the result by what they amount to, in the side panes
     * in that side's colour.
     */
    private void paintRegions() {
        int paragraphs = resultArea.getParagraphs().size();
        List<List<String>> rows = new ArrayList<>(paragraphs);
        for (int i = 0; i < paragraphs; i++) {
            rows.add(new ArrayList<>(1));
        }
        for (int i = 0; i < slots.size() && i < states.size(); i++) {
            Slot slot = slots.get(i);
            State state = states.get(i);
            if (slot.count() == 0) {
                // Resolved to nothing: a rule where its lines would be.
                if (slot.start() < paragraphs) {
                    rows.get(slot.start()).add("merge-anchor-above");
                } else if (paragraphs > 0) {
                    rows.get(paragraphs - 1).add("merge-anchor-below");
                }
                continue;
            }
            int fromOurs = state == State.EDITED || state == State.UNRESOLVED
                    ? 0 : ConflictRegions.oursLinesIn(conflict(slot), slot.resolution());
            for (int line = slot.start(); line < slot.end() && line < paragraphs; line++) {
                rows.get(line).add(switch (state) {
                    case UNRESOLVED -> "merge-unresolved-line";
                    case EDITED -> "merge-edited-line";
                    default -> line - slot.start() < fromOurs ? "merge-ours-line" : "merge-theirs-line";
                });
            }
        }
        for (int i = 0; i < paragraphs; i++) {
            restyle(resultArea, i, rows.get(i));
        }
        paintSide(oursArea, "merge-ours-line");
        paintSide(theirsArea, "merge-theirs-line");
        paintSide(baseArea, "merge-base-line");
    }

    private void paintSide(DiffTextArea side, String tint) {
        int paragraphs = side.getParagraphs().size();
        String[] rows = new String[paragraphs];
        for (int i = 0; i < slots.size(); i++) {
            Range range = slotRange(i, side);
            if (range == null) continue;
            for (int line = range.start(); line < range.end() && line < paragraphs; line++) {
                rows[line] = tint;
            }
        }
        for (int i = 0; i < paragraphs; i++) {
            restyle(side, i, rows[i] == null ? List.of() : List.of(rows[i]));
        }
    }

    /** Sets a paragraph's style only when it differs: each set is a document change. */
    private static void restyle(DiffTextArea area, int paragraph, List<String> classes) {
        Collection<String> current = area.getParagraph(paragraph).getParagraphStyle();
        if (!(current instanceof List<?> list ? list.equals(classes) : List.copyOf(current).equals(classes))) {
            area.setParagraphStyle(paragraph, List.copyOf(classes));
        }
    }

    /** What the gutters show beside each line: captions in the result, ticks in the sides. */
    private record Marks(Map<Integer, String> captions, Map<Integer, Tick> ours, Map<Integer, Tick> theirs) {
        static final Marks NONE = new Marks(Map.of(), Map.of(), Map.of());

        Tick tick(boolean oursSide, int line) {
            return (oursSide ? ours : theirs).get(line);
        }
    }

    /** A side pane line's tick box: the slot and line of that side it picks, and its state. */
    private record Tick(int slot, int line, boolean ticked) {
    }

    private Marks buildMarks() {
        Map<Integer, String> captions = new HashMap<>();
        Map<Integer, Tick> ours = new HashMap<>();
        Map<Integer, Tick> theirs = new HashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            Located at = located.get(slot.conflict());
            State state = states.get(i);
            if (state == State.UNRESOLVED && at.shown().isEmpty()) {
                captions.put(slot.start(), PLACEHOLDER_CAPTION);
            }
            Conflict conflict = conflict(slot);
            boolean live = state != State.EDITED && slot.resolution().decided();
            if (at.ours() != null && at.ours().length() == conflict.ours().size()) {
                for (int line = 0; line < conflict.ours().size(); line++) {
                    ours.put(at.ours().start() + line,
                            new Tick(i, line, live && slot.resolution().ours().contains(line)));
                }
            }
            if (at.theirs() != null && at.theirs().length() == conflict.theirs().size()) {
                for (int line = 0; line < conflict.theirs().size(); line++) {
                    theirs.put(at.theirs().start() + line,
                            new Tick(i, line, live && slot.resolution().theirs().contains(line)));
                }
            }
        }
        return new Marks(Map.copyOf(captions), Map.copyOf(ours), Map.copyOf(theirs));
    }

    /** Syntax colours for a read-only side, lexed once. */
    private void styleSide(DiffTextArea area) {
        List<String> lines = List.of(area.getText().split("\n", -1));
        HighlightLane.submit(area, () -> CodeStyler.lex(highlighter, path, lines), ranges -> {
            if (ranges == null) {
                return;
            }
            for (int i = 0; i < ranges.size(); i++) {
                CodeStyler.apply(area, i, ranges.get(i));
            }
        });
    }

    /**
     * Syntax colours for the result.
     *
     * <p>Re-run after typing pauses, from scratch: the result is one file, not thousands
     * of lines of diff, and a comment opened on one line changes the colour of every line
     * below it.
     */
    private void styleResult() {
        String text = resultArea.getText();
        List<String> lines = List.of(text.split("\n", -1));
        HighlightLane.submit(resultArea, () -> CodeStyler.lex(highlighter, path, lines), ranges -> {
            // Typed over while it was being lexed: the next pause re-colours it.
            if (text.equals(resultArea.getText())) {
                int count = resultArea.getParagraphs().size();
                for (int i = 0; i < count; i++) {
                    CodeStyler.apply(resultArea, i, ranges == null || i >= ranges.size()
                            ? List.of() : ranges.get(i));
                }
            }
        });
    }

    /** The result's lines: as the last edit left them in a conflict, as the area holds them otherwise. */
    private List<String> resultLines() {
        return List.of((conflictMode ? mirror : resultArea.getText()).split("\n", -1));
    }

    /** Line breaks in {@code text} between two offsets. */
    private static int breaks(String text, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Construction helpers
    // ------------------------------------------------------------------

    /** Prefers the working-tree text (with conflict markers), falling back sensibly. */
    private static String pick(String merged, String base, String ours) {
        if (merged != null && !merged.isEmpty()) return merged;
        if (base != null && !base.isEmpty()) return base;
        return ours == null ? "" : ours;
    }

    /**
     * "Ours (feature/login)" when the caller knows the name, "Ours (current branch)"
     * otherwise.
     *
     * <p>The generic wording is not merely vaguer, it can be wrong: during a rebase the
     * sides are swapped relative to what "current branch" suggests, so a name is worth
     * having whenever there is one.
     */
    static String sideTitle(String side, String name, String fallback) {
        String detail = name == null || name.isBlank() ? fallback : name;
        return side + " (" + detail + ")";
    }

    /**
     * What git itself called the incoming side: the text after the first {@code >>>>>>>}
     * marker - the merged branch for a merge, {@code <hash> (<subject>)} for the commit a
     * rebase or cherry-pick is replaying. Git wrote it knowing the operation, so it is
     * right in each case where a label worked out here from the repository state would
     * have to guess. {@code null} when the text carries no labelled marker.
     */
    public static String incomingLabel(String merged) {
        if (merged == null) return null;
        for (String line : merged.split("\r?\n", -1)) {
            if (line.startsWith(ConflictDocument.THEIRS_MARKER)) {
                String label = line.substring(ConflictDocument.THEIRS_MARKER.length()).strip();
                return label.isEmpty() ? null : label;
            }
        }
        return null;
    }

    /** A code surface in the application's own theme, font, zoom and gutter. */
    private static DiffTextArea codeArea() {
        DiffTextArea area = new DiffTextArea();
        area.setParagraphGraphicFactory(lineNumbers(area));
        return area;
    }

    /**
     * Line numbers in the diff viewer's gutter.
     *
     * <p>RichTextFX's stock {@code LineNumberFactory} paints its strip in code - a
     * light grey no stylesheet reaches, glaring beside a dark editor. These carry the
     * diff viewer's classes from {@code diff-styles.css} instead, so the gutter follows
     * the theme and looks the same in both places.
     *
     * <p>Padded to the digits of the last line, in the gutter's monospace font, so the
     * column keeps one width. Observed rather than read once: the result is an editor,
     * and its hundredth line widens the column for the rows already on screen.
     */
    private static IntFunction<Node> lineNumbers(DiffTextArea area) {
        Val<Integer> lines = LiveList.sizeOf(area.getParagraphs());
        return index -> gutter(lineNumber(lines, index));
    }

    private static Label lineNumber(Val<Integer> lines, int index) {
        Label number = new Label();
        number.getStyleClass().add("diff-line-number");
        // Only while the row is on screen, as the stock factory does: a row scrolled
        // away and dropped would otherwise go on listening to the document.
        number.textProperty().bind(lines.map(n -> padded(index + 1, n)).conditionOnShowing(number));
        return number;
    }

    private static HBox gutter(Node... cells) {
        HBox gutter = new HBox(cells);
        gutter.getStyleClass().add("diff-gutter");
        gutter.setAlignment(Pos.CENTER_RIGHT);
        return gutter;
    }

    /**
     * The result's gutter: line numbers, and beside a conflict that has no lines of its
     * own to show - both sides added different text at one place, or the ancestor could
     * not be worked out - a caption saying it is still to be resolved. The placeholder
     * line itself is empty, and is never written to the file.
     */
    private IntFunction<Node> resultGutter(DiffTextArea area) {
        Val<Integer> lines = LiveList.sizeOf(area.getParagraphs());
        return index -> {
            Label caption = new Label();
            caption.getStyleClass().add("merge-placeholder-caption");
            caption.setMinWidth(Region.USE_PREF_SIZE);
            Val<String> text = marks.map(m -> m.captions().get(index)).conditionOnShowing(caption);
            caption.textProperty().bind(text);
            caption.visibleProperty().bind(text.map(t -> true).orElseConst(false));
            caption.managedProperty().bind(caption.visibleProperty());
            HBox row = new HBox(gutter(lineNumber(lines, index)), caption);
            row.setAlignment(Pos.CENTER_LEFT);
            return row;
        };
    }

    /**
     * A side pane's gutter: a tick box beside each line of a conflict, then the line
     * number. Lines outside every conflict keep the box's room, so the numbers line up.
     */
    private IntFunction<Node> sideGutter(DiffTextArea area, boolean oursSide) {
        Val<Integer> lines = LiveList.sizeOf(area.getParagraphs());
        return index -> {
            CheckBox box = new CheckBox();
            box.getStyleClass().add("merge-tick");
            box.setFocusTraversable(false);
            box.setVisible(false);
            Val<Tick> tick = marks.map(m -> m.tick(oursSide, index)).conditionOnShowing(box);
            tick.values().subscribe(t -> {
                box.setVisible(t != null);
                box.setSelected(t != null && t.ticked());
            });
            box.setOnAction(e -> {
                toggleTick(oursSide, marksNow.tick(oursSide, index));
                // Back to what the ticks are now: the press may have been refused, and the
                // published marks only change after the repaint.
                Tick now = marksNow.tick(oursSide, index);
                box.setSelected(now != null && now.ticked());
            });
            // The pane's own click handler reads the caret, which a press on the box never
            // moved: the click is the box's alone.
            box.addEventHandler(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
            StackPane cell = new StackPane(box);
            cell.getStyleClass().add("merge-tick-cell");
            return gutter(cell, lineNumber(lines, index));
        };
    }

    /** {@code line}, right-aligned to as many digits as {@code lines} has. */
    private static String padded(int line, int lines) {
        String digits = String.valueOf(line);
        return " ".repeat(Math.max(0, String.valueOf(lines).length() - digits.length())) + digits;
    }

    private static DiffTextArea readOnlyArea(String content) {
        DiffTextArea area = codeArea();
        // CRs folded first: split on LF alone, a CRLF file's lines each kept a trailing CR
        // that the area then dropped, leaving its row mapping a character longer than the
        // row.
        area.setLines(List.of(folded(content).split("\n", -1)), false);
        area.setEditable(false);
        return area;
    }

    /** "Ours (main)": the side, and what it is here. */
    private static final Pattern SIDE_TITLE = Pattern.compile("(\\S+) \\((.+)\\)");

    /**
     * A titled pane around one code area, its header in the colour of {@code role}: ours,
     * theirs, base or result. "Ours (main)" is set as the side in bold and its name after
     * it, quieter.
     *
     * <p>Each area gets its own scroll pane, so the panes scroll independently - two
     * sides of a conflict rarely line up, and locking them together mostly means neither
     * is where the reader wants it. Moving to a conflict scrolls each to it once.
     *
     * @param lead a control before the title, or null
     */
    private static VBox titledBox(String title, DiffTextArea area, String role, Node lead) {
        Matcher parts = SIDE_TITLE.matcher(title);
        boolean split = parts.matches();
        Label name = new Label(split ? parts.group(1) : title);
        name.getStyleClass().add("merge-pane-title");
        name.setMinWidth(Region.USE_PREF_SIZE);
        HBox header = new HBox(UiScale.STEP, name);
        if (lead != null) {
            lead.getStyleClass().add("merge-pane-tick");
            header.getChildren().add(0, lead);
        }
        if (split) {
            Label detail = new Label(parts.group(2));
            detail.getStyleClass().add("merge-pane-detail");
            detail.setMinWidth(0);
            header.getChildren().add(detail);
        }
        header.getStyleClass().add("merge-pane-header");
        // Text on one baseline; a tick box has none worth lining up with, so it is centred.
        header.setAlignment(lead == null ? Pos.BASELINE_LEFT : Pos.CENTER_LEFT);
        VirtualizedScrollPane<DiffTextArea> scroll = new VirtualizedScrollPane<>(area);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox box = new VBox(header, scroll);
        box.getStyleClass().addAll("merge-pane", "merge-pane-" + role);
        return box;
    }

    /** A dot in a side's colour, for the buttons that keep that side's lines. */
    private static Node sideDot(String role) {
        Circle dot = new Circle(3.5);
        dot.getStyleClass().addAll("merge-side-dot", "merge-side-dot-" + role);
        return dot;
    }

    /**
     * A comparison's whole-file choice, said as what it is there - where the result starts
     * from: "Start result from  My edits | On disk now | Original". Each replaces the
     * result, asking first when it was edited, as Take does in a conflict.
     */
    private HBox startFrom(Labels labels, String ours, String theirs, String base, boolean withBase) {
        Label lead = new Label("Start result from");
        lead.getStyleClass().add("merge-start-from");
        lead.setMinWidth(Region.USE_PREF_SIZE);
        List<Button> choices = new ArrayList<>();
        Button mine = new Button(labels.oursTitle(), sideDot("ours"));
        mine.setOnAction(e -> replaceWholeResult("“" + labels.oursTitle() + "”", ours));
        choices.add(mine);
        Button other = new Button(labels.theirsTitle(), sideDot("theirs"));
        other.setOnAction(e -> replaceWholeResult("“" + labels.theirsTitle() + "”", theirs));
        choices.add(other);
        if (withBase) {
            Button original = new Button("Original");
            original.setTooltip(new Tooltip("The file as it was when it was opened"));
            original.setOnAction(e -> replaceWholeResult("the original", base));
            choices.add(original);
        }
        for (int i = 0; i < choices.size(); i++) {
            choices.get(i).getStyleClass().add(i == 0 ? "left-pill" : i == choices.size() - 1 ? "right-pill" : "center-pill");
            choices.get(i).setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox group = new HBox();
        group.getChildren().addAll(choices);
        group.getStyleClass().add("merge-use");
        HBox row = new HBox(UiScale.STEP, lead, group);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Where two texts differ, line by line, as JGit's histogram diff sees it. */
    static List<Edit> lineEdits(String ours, String theirs) {
        RawText a = new RawText(folded(ours).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        RawText b = new RawText(folded(theirs).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return List.copyOf(DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                .diff(RawTextComparator.DEFAULT, a, b));
    }

    /** Tints the lines of one side that {@code edits} change, in that side's colour. */
    private static void tint(DiffTextArea area, List<Edit> edits, boolean ours) {
        int paragraphs = area.getParagraphs().size();
        for (Edit edit : edits) {
            int from = ours ? edit.getBeginA() : edit.getBeginB();
            int to = ours ? edit.getEndA() : edit.getEndB();
            for (int line = from; line < to && line < paragraphs; line++) {
                area.setParagraphStyle(line, List.of(ours ? "merge-ours-line" : "merge-theirs-line"));
            }
        }
    }

    /** Line endings as the panes hold them: CRs folded away. */
    private static String folded(String text) {
        return ConflictDocument.folded(text);
    }

    /** "Take ▾": the whole result replaced by one version of the file. */
    private static MenuButton wholeFileMenu(MenuItem... items) {
        MenuButton take = new MenuButton("Take", null, items);
        take.setMinWidth(Region.USE_PREF_SIZE);
        take.setTooltip(new Tooltip("Replace the entire result with one version of the file"));
        take.getStyleClass().add("merge-take");
        return take;
    }

    // ------------------------------------------------------------------
    // For tests
    // ------------------------------------------------------------------

    /** What the result pane currently holds. For tests. */
    String resultTextForTest() {
        return resultArea.getText();
    }

    /** What Save draft or Mark resolved would hand back now. For tests. */
    String returnedTextForTest() {
        return outputText();
    }

    /** Puts the caret on {@code paragraph} of the result. For tests. */
    void moveCaretForTest(int paragraph) {
        resultArea.moveTo(paragraph, 0);
    }

    /** Presses Use ours / theirs / both. For tests. */
    void resolveActiveForTest(Choice choice) {
        resolveActive(choice);
    }

    /** Ticks or clears line {@code line} of one side of conflict {@code slot}. For tests. */
    void tickForTest(boolean oursSide, int slot, int line) {
        toggleTick(oursSide, new Tick(slot, line, false));
    }

    /** Presses a pane header's tick. For tests. */
    void tickAllForTest(boolean oursSide) {
        tickAll(oursSide);
    }

    /** What each conflict amounts to now. For tests. */
    List<State> statesForTest() {
        return states;
    }

    /** Which paragraphs of the result each conflict holds now. For tests. */
    List<Slot> slotsForTest() {
        return slots;
    }

    /** The count beside Previous and Next. For tests. */
    String conflictSummaryForTest() {
        return conflictSummary.getText();
    }

    /** Which side-pane lines have a tick box, and which are ticked. For tests. */
    Map<Integer, Boolean> ticksForTest(boolean oursSide) {
        Map<Integer, Boolean> ticks = new java.util.TreeMap<>();
        (oursSide ? marksNow.ours() : marksNow.theirs()).forEach((line, tick) -> ticks.put(line, tick.ticked()));
        return ticks;
    }

    /** The caption shown beside a result line, or null. For tests. */
    String captionForTest(int paragraph) {
        return marksNow.captions().get(paragraph);
    }

    /** The paragraph style of a line in the result or a side ("result", "ours", "theirs", "base"). For tests. */
    Collection<String> lineStyleForTest(String pane, int paragraph) {
        DiffTextArea area = switch (pane) {
            case "ours" -> oursArea;
            case "theirs" -> theirsArea;
            case "base" -> baseArea;
            default -> resultArea;
        };
        return area.getParagraph(paragraph).getParagraphStyle();
    }

    /** The first line a side pane shows. For tests. */
    int firstVisibleForTest(String pane) {
        DiffTextArea area = switch (pane) {
            case "ours" -> oursArea;
            case "theirs" -> theirsArea;
            default -> baseArea;
        };
        return area.firstVisibleParToAllParIndex();
    }

    /** Replaces part of the result as typing would. For tests. */
    void typeForTest(int paragraph, int column, int length, String text) {
        int from = resultArea.getAbsolutePosition(paragraph, column);
        resultArea.replaceText(from, from + length, text);
    }

    /** How many panes the top row holds. For tests. */
    int sidePaneCountForTest() {
        return topSplit.getItems().size();
    }

    /** Ticks or clears Show base. For tests. */
    void showBaseForTest(boolean on) {
        showBase.setSelected(on);
    }

    /** The find bar over the panes. For tests. */
    com.jfx.git.ui.FindBar findBarForTest() {
        return findBar;
    }

    /** Undoes the last edit to the result. For tests. */
    void undoForTest() {
        resultArea.undo();
    }

    /** Redoes the last undone edit. For tests. */
    void redoForTest() {
        resultArea.redo();
    }

    /** Convenience for callers that only care about a completed resolution. */
    public Optional<String> resolve() {
        return showAndWait();
    }
}
