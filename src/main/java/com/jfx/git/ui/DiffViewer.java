package com.jfx.git.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;


import com.jfx.git.core.RepositoryPreferences;
import com.jfx.git.core.TextEncoding;
import com.jfx.git.ui.DiffRows.Kind;
import com.jfx.git.ui.DiffRows.Row;
import com.jfx.git.ui.syntax.HighlightLane;
import com.jfx.git.ui.syntax.Highlighter;
import com.jfx.git.ui.syntax.ScopedTokenizer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Unified/split diff viewer modelled on the commercial clients'.
 *
 * <p>Everything on screen is one flat list of {@link DiffRows.Row} rendered into a single
 * virtualized text area. That is what lets a selection run across hunk boundaries, keeps
 * memory flat on a diff with hundreds of hunks, and gives the wheel its normal behaviour
 * without any event forwarding. It also removes a whole class of sizing bug: nothing
 * computes a content height any more, so wrapped lines cannot be clipped.
 *
 * <p>Split view is the same surface twice, with the two columns' scroll positions tied
 * together, rather than a different renderer.
 */
public class DiffViewer extends VBox {

    /**
     * What the surface is showing.
     *
     * <p>These used to be independent booleans, which allowed states like "blame on but
     * file view off" that no code path could render - the source of the blame/diff
     * desync. As one value the illegal combinations stop being representable.
     */
    private enum ViewMode { UNIFIED, SPLIT, FILE, BLAME }

    /**
     * How long to wait after the user stops typing before re-lexing and re-colouring
     * the buffer. Long enough that a fast typist's keystrokes are not each paying for
     * a re-lex, short enough that colouring still feels immediate.
     */
    private static final Duration LIVE_HIGHLIGHT_DEBOUNCE = Duration.ofMillis(150);

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private final StringProperty currentFilePath = new SimpleStringProperty("");

    /**
     * The diff on screen - or, in blame, the blamed commit's diff, for the scroll map - and
     * {@link LoadedDiff#NONE} whenever what is shown is not described by one.
     *
     * <p>One value, replaced whole. Its hunks, its summary, the charset its lines were read
     * with and the file behind it used to be separate fields here, kept in step by hand.
     */
    private LoadedDiff diff = LoadedDiff.NONE;
    /** The diff {@link #revealedAbove} was opened in; see {@link LoadedDiff#continues}. */
    private LoadedDiff revealedFor;

    /**
     * The bytes behind whatever is on screen, kept so a change of encoding can be
     * satisfied by decoding again rather than by going back to Git.
     */
    private byte[] currentBytes = new byte[0];
    /** Whether {@link #currentBytes} is diff output or whole-file content. */
    private boolean bytesAreDiff = true;
    private TextEncoding encoding = TextEncoding.GUESS;
    private final ComboBox<TextEncoding> encodingBox;
    /** Suppresses the encoding listener while the selection is reset from code. */
    private boolean updatingEncoding = false;

    /** The rows currently rendered into {@link #area}, indexed by paragraph. */
    private List<Row> rows = List.of();
    /** {@link #rows} with the questions asked of them by position; see {@link #setRows}. */
    private LoadedDiff.Rows rowIndex = LoadedDiff.Rows.EMPTY;

    private ViewMode mode = ViewMode.UNIFIED;
    /** The layout to return to when leaving File view; remembered so the choice sticks. */
    private ViewMode diffLayout = ViewMode.UNIFIED;

    /** Draws spaces and tabs; purely visual. */
    private boolean showWhitespace = false;
    /** Excludes whitespace-only changes from the diff; changes what git reports. */
    private boolean ignoreWhitespace = false;
    private boolean wrapLines = false;
    /** Word-level highlighting inside changed lines; see {@link #setInlineDiffEnabled}. */
    private boolean inlineDiffEnabled = true;
    /** Syntax colouring; see {@link #setSyntaxHighlightingEnabled}. */
    private boolean syntaxEnabled = true;
    private final Highlighter highlighter = new Highlighter();
    /** Says so, quietly, when a file was too large to colour. */
    private final Label syntaxNotice = new Label();
    /** The document facts, along the bottom; see {@link EditorStatusFooter}. */
    private final EditorStatusFooter footer = new EditorStatusFooter();
    private int currentHunkIndex = -1;
    /** Widest line number on screen, in digits; see {@link #measureGutter}. */
    private int gutterDigits = 3;

    /**
     * How many hidden lines have been revealed above each hunk, keyed by hunk index - in
     * {@link #revealedFor}, and kept only while each new diff {@link LoadedDiff#continues}
     * it: a reload of the same unstaged change keeps what was opened, another commit's diff
     * of the same path does not.
     */
    private Map<Integer, Integer> revealedAbove = Map.of();
    /** Lines revealed per click; a screenful at a time reads better than all or nothing. */
    private static final int EXPAND_STEP = 20;

    /** The header's file name, read first; see {@link #showPath}. */
    private final Label lblFileName = new Label();
    /** The folder the file is in, subdued, and the part that gives way when space is short. */
    private final Label lblFileDir = new Label();
    /**
     * Name and folder together. Carries the full path as its accessible text and tooltip,
     * since the folder may be elided on screen.
     */
    private final HBox pathBox;
    /** The full path on hover, whatever the header had room for. */
    private final Tooltip pathTooltip = new Tooltip();
    /** Which two versions of the file are compared; see {@link #setComparedSides}. */
    private final Label lblSides = new Label();
    private DiffSides sides;
    /** Says that whitespace changes are hidden, and turns that off when pressed. */
    private Button whitespaceIgnoredChip;
    /** Ignore-whitespace, held so the chip can turn it off the same way the toggle does. */
    private ToggleButton btnIgnoreWhitespace;
    /** Split view's column names; see {@link #updateSidesLabel()}. */
    private final Label leftColumnHeader = new Label();
    private final Label rightColumnHeader = new Label();
    /** "Change 2 of 5", between the step buttons. */
    private final Label changeCounter = new Label();
    private final HBox titleBar;

    /** The single surface for every mode but split. */
    private final DiffTextArea area = new DiffTextArea();

    /** Find, replace and go to line over whichever surface is showing; see {@link FindBar}. */
    private final FindBar findBar = new FindBar(new FindBar.Host() {
        @Override
        public List<org.fxmisc.richtext.GenericStyledArea<?, ?, ?>> areas() {
            return splitSurfaceVisible && leftArea != null && rightArea != null
                    ? List.of(leftArea, rightArea) : List.of(area);
        }

        @Override
        public boolean editable() {
            // The preview is drawn from the buffer, not typed into: replace works on the source.
            return editing() && !isPreviewing();
        }

        @Override
        public List<FindBar.Surface> surfaces() {
            return isPreviewing() ? List.of(markdownView.findSurface()) : FindBar.Host.super.surfaces();
        }

        @Override
        public int lineCount() {
            if (isFileView() || editing()) {
                return area.getParagraphs().size();
            }
            return rowIndex.lastNewLine();
        }

        @Override
        public int paragraphOfLine(int line) {
            if (isFileView() || editing()) {
                return line - 1 < area.getParagraphs().size() ? line - 1 : -1;
            }
            // In a diff the rows are the new side's lines with the old side's between
            // them; Split view keeps the new side in rows too (see renderSplit).
            return rowIndex.rowOfNewLine(line);
        }

        @Override
        public org.fxmisc.richtext.GenericStyledArea<?, ?, ?> lineArea() {
            return splitSurfaceVisible && rightArea != null ? rightArea : area;
        }
    });
    private final DiffScrollPane areaScroll;

    /**
     * "Preview": a Markdown file drawn as it reads, in place of its source. Offered only in
     * File view of a Markdown file; the source stays the thing that is searched and edited,
     * and while editing the preview follows the typing.
     */
    private final ToggleButton btnPreview = new ToggleButton("Preview");
    /** Whether a Markdown file in File view is shown rendered; kept from one file to the next. */
    private boolean previewWanted;
    /** Built the first time a preview is asked for: most files are not Markdown. */
    private MarkdownView markdownView;
    /** How long typing pauses before the preview is drawn again. */
    private static final Duration PREVIEW_DEBOUNCE = Duration.ofMillis(300);

    /** Split view's two columns, built lazily because most sessions never use it. */
    private DiffTextArea leftArea;
    private DiffTextArea rightArea;
    /** The right column's own scroll pane - the one visible scrollbar in Split view. */
    private DiffScrollPane rightScroll;
    private Node splitPane;
    private List<Row> leftRows = List.of();
    /** {@link #leftRows}' index, as {@link #rowIndex} is {@link #rows}'. */
    private LoadedDiff.Rows leftRowIndex = LoadedDiff.Rows.EMPTY;
    /** Guards the two columns' scroll listeners against driving each other in a loop. */
    private boolean syncingScroll = false;
    /** Which surface {@link #contentRow} currently shows - read by {@link #updateScrollMap()}. */
    private boolean splitSurfaceVisible = false;
    /** Wraps the visible surface and the scrollbar diff-map beside it. */
    private HBox contentRow;
    private final DiffScrollMap scrollMap = new DiffScrollMap();
    /** The whole file in miniature, between the text and the scrollbar; File view only. */
    private final Minimap minimap = new Minimap();
    private boolean minimapEnabled = true;

    /** Shows which hunk and which definition the top of the viewport is inside. */
    private final Label stickyHeader = new Label();
    private final StackPane viewport = new StackPane();
    private final Pane overlay = new Pane();
    /**
     * One action bar per hunk currently on screen, pooled and reused across renders.
     *
     * <p>There used to be a single bar that followed the pointer, revealed on hover. It
     * was close to unusable: the bar floats in {@link #overlay}, above the text, so
     * travelling from the hunk to the button left the text area and hid the bar out from
     * under the pointer before the click landed - reachable only by outrunning the event.
     * (Both robot tests driving it had already worked around this with {@code fire()}
     * rather than a real click, blaming the headless toolkit.) Commercial clients simply show the
     * buttons on every hunk, all the time, which removes the problem rather than
     * mitigating it. Only hunks whose header is actually rendered get a bar, so a
     * thousand-hunk diff still costs a handful of nodes.
     */
    private final List<HunkBar> hunkBars = new ArrayList<>();

    /** The Stage lines / Stage hunk / Discard hunk trio belonging to one hunk. */
    /**
     * What the icon-only toggles are called. Their accessible text, and how tests find
     * them: an icon has no label to look up, and the characters these used to show - a
     * pilcrow, a middle dot, a triple bar - named nothing.
     */
    static final String NAME_IGNORE_WHITESPACE = "Ignore whitespace changes";
    static final String NAME_SHOW_WHITESPACE = "Show whitespace characters";
    static final String NAME_WRAP = "Wrap long lines";

    private static final class HunkBar {
        final Button stageLines = flatButton("Stage lines", "Apply only the selected lines");
        final Button stage = flatButton("Stage hunk", "Apply just this hunk to the index");
        final Button revert = flatButton("Discard hunk", "Undo just this hunk in the working tree");
        /** Reverts only the selected lines; after Discard hunk so lookups by class find that first. */
        final Button discardLines = flatButton("Discard lines",
                "Undo only the selected lines in the working tree");
        /** Says why the actions are missing, when they are missing for a reason the user can change. */
        final Label note = new Label();
        final HBox box;

        HunkBar() {
            stageLines.getStyleClass().add("diff-hunk-action");
            stage.getStyleClass().add("diff-hunk-action");
            revert.getStyleClass().add("diff-revert-hunk");
            discardLines.getStyleClass().add("diff-revert-hunk");
            note.getStyleClass().add("diff-hunk-note");
            box = new HBox(4, note, stageLines, stage, revert, discardLines);
            box.setAlignment(Pos.CENTER_RIGHT);
            box.getStyleClass().add("diff-hunk-actions");
            // Positioned by hand against the overlay, not laid out by it.
            box.setManaged(false);
        }
    }
    private final Label placeholder = new Label();
    /** Offered instead of an oversized diff; see {@link #holdBackLargeDiff}. */
    private final Button showAnyway;

    private final Button btnEdit;
    private final Button btnSave;
    private final Button btnSaveStage;
    private final Button btnCancelEdit;
    /** Ends the session without throwing anything away; see {@link #finishEditing()}. */
    private final Button btnFinishEdit;
    /** Writes the displayed revision over the working copy; see {@link #restoreDisplayedVersion()}. */
    private final Button btnRestoreVersion;
    private final Label editingBadge = new Label("editable", Icons.edit());
    private final Button btnOpenExternally;
    private final Button btnBlame;
    private final Button btnHistory;
    /** Opens what is on screen in the user's own diff tool; see {@link #setOnExternalDiff}. */
    private final Button btnExternalDiff;
    private final ToggleButton btnFileView;
    private final ToggleButton btnDiffView;
    private final ToggleButton btnWhitespace;
    /** The two hunk-step buttons, disabled together when there is nothing to step through. */
    private List<Button> hunkNavButtons = List.of();
    private final ToggleButton btnUnified;
    private final ToggleButton btnSplit;
    /** Context: git's own three lines, ten, or the whole file. */
    private final ToggleButton btnContext3;
    private final ToggleButton btnContext10;
    private final ToggleButton btnContextAll;
    /** The toolbar controls that only mean anything while a diff is on screen. */
    private HBox diffOnlyTools;
    /** The whole toolbar row, measured when deciding what has to collapse. */
    private HBox toolbarRow;
    /**
     * Where the diff controls go when the row is too narrow to hold them.
     *
     * <p>Not a hover affordance and not a second copy of the toolbar - the same actions,
     * behind one labelled button, appearing only when they would otherwise be clipped off
     * the right-hand edge with nothing to say they existed.
     */
    private MenuButton overflowMenu;
    /** Whether the surface shows the whole file continuously, or only the hunks. */
    private boolean wholeFile = false;
    /** Lines of context beyond git's three around each hunk; see {@link #btnContext10}. */
    private int extraContext = 0;
    /** What the Context selector's middle choice adds to git's own three lines. */
    static final int TEN_LINES_EXTRA = 7;

    /** Blame rows for the current file, read by the gutter factory in blame mode. */
    private List<com.jfx.git.core.BlameLine> blameLines = List.of();
    /** Suppresses the toggle listeners while a toggle is being synced from code. */
    private boolean updatingToggle = false;

    /** Whole-file text supplied by the host, used instead of the supplier when set. */
    private String fileContentOverride;
    /** Raw File-view bytes supplied with a historical diff, kept apart from its patch bytes. */
    private byte[] fileViewBytesOverride;

    private Consumer<String> onEditFile;
    /** Tells an empty file from an absent one; see {@link #setWorkingCopyExistsCheck}. */
    private java.util.function.Predicate<String> onWorkingCopyExists;
    /**
     * Where Open externally learns a renamed file's name; the editing session has its own
     * copy, for Edit and Restore this version. See {@link #setRenameLookup}.
     */
    private RenameLookup renameLookup;
    /** Whether what is on screen is the working tree, which is the only editable copy. */
    private boolean editableSource = false;
    /**
     * The editing session: every flow from Edit to Done, and the document's state while
     * one is open. This viewer is its buffer and its prompter; see {@link EditorSession}.
     */
    private final EditorSession session = new EditorSession(
            new SessionBuffer(), new SessionPrompter(), new SessionWorkingCopy());

    /** True while the file view is a typable buffer; see {@link EditorSession#isOpen()}. */
    private boolean editing() {
        return session.isOpen();
    }
    private Consumer<String> onBlame;
    private Consumer<String> onHistory;
    private Consumer<com.jfx.git.core.ExternalTools.DiffTarget> onExternalDiff;
    /** Which two versions of a path the content on screen compares, for a tool outside. */
    private java.util.function.Function<String, com.jfx.git.core.ExternalTools.DiffTarget> externalDiffTarget;
    /** Notified when the user switches File/Diff view, so the host can load the content. */
    private Consumer<Boolean> onViewModeChanged;
    /** Notified when ignore-whitespace changes, so the host can recompute the diff. */
    private Consumer<Boolean> onIgnoreWhitespaceChanged;
    /** Runs the hunk actions the diff on screen offers; see {@link #setOnHunkAction}. */
    private Consumer<HunkAction> onHunkAction;
    private Function<String, byte[]> fileContentSupplier;
    /** Supplies both revisions of a binary path, so an image can be shown side by side. */
    private Function<String, ImageDiffView.Blobs> imageSupplier;
    /** Reads any path as of the revision on screen, for the preview's images; null for the working tree. */
    private Function<String, byte[]> revisionReader;

    /**
     * Writes an edited buffer back to disk and says whether it worked; see
     * {@link EditorSession.SaveHandler}, which the session runs saves through. Kept under
     * this name for the hosts that set one.
     */
    @FunctionalInterface
    public interface FileSaveHandler extends EditorSession.SaveHandler {
    }

    /**
     * Finds what a file shown from an older revision is called in the working tree now;
     * see {@link EditorSession.RenameLookup}.
     */
    @FunctionalInterface
    public interface RenameLookup extends EditorSession.RenameLookup {
    }

    /**
     * Where an unsaved buffer is parked so a crash cannot take it; see
     * {@link EditorSession.DraftStore}.
     *
     * <p>An interface rather than a direct call to {@link com.jfx.git.core.EditorDrafts},
     * because a draft is keyed by <em>repository</em> and this class has never known which
     * repository it belongs to - the host wires that in, the same way it wires the save
     * handler and the content supplier.
     */
    public interface DraftStore extends EditorSession.DraftStore {
    }

    /** Parks unsaved buffers; {@code null} disables drafting for this viewer. */
    public void setDraftStore(DraftStore store) {
        session.setDraftStore(store);
    }

    public DiffViewer() {
        getStyleClass().add("diff-viewer");
        try {
            getStylesheets().add(getClass().getResource("/diff-styles.css").toExternalForm());
        } catch (Exception ignored) {
            // Styling is optional.
        }

        // ---- Title bar: path, encoding, close ----
        // The file name first and in full, the folder after it and subdued. The name is
        // the part being read, and the folder is what gives way when the header is short:
        // elided from its start, so "…/ui/" keeps the end nearest the name.
        lblFileName.getStyleClass().add("diff-file-name");
        lblFileName.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        lblFileDir.getStyleClass().add("diff-file-dir");
        lblFileDir.setMinWidth(0);
        lblFileDir.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
        pathBox = new HBox(6, lblFileName, lblFileDir);
        pathBox.setAlignment(Pos.CENTER_LEFT);
        pathBox.setMinWidth(0);
        pathBox.getStyleClass().add("diff-file-path");
        pathBox.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        Tooltip.install(pathBox, pathTooltip);
        HBox.setHgrow(pathBox, Priority.SOMETIMES);
        MenuItem copyPathItem = new MenuItem("Copy path");
        copyPathItem.setOnAction(e -> copyPath());
        MenuItem copyNameItem = new MenuItem("Copy file name");
        copyNameItem.setOnAction(e -> copyToClipboard(lblFileName.getText()));
        ContextMenu pathMenu = new ContextMenu(copyPathItem, copyNameItem);
        pathBox.setOnContextMenuRequested(e -> {
            if (!currentFilePath.get().isEmpty()) {
                pathMenu.show(pathBox, e.getScreenX(), e.getScreenY());
            }
            e.consume();
        });
        showPath("");

        Button btnCopyPath = Icons.button(Icons.copy(), "Copy path", "Copy the file's path");
        btnCopyPath.getStyleClass().add("diff-title-icon");
        btnCopyPath.setOnAction(e -> copyPath());
        btnCopyPath.disableProperty().bind(currentFilePath.isEmpty());

        // Which two versions the red and green lines are between. A filename and a colour
        // do not say whether this is the unstaged change, the staged one, a commit or a
        // comparison - and each of those stages, reverts and reads differently.
        lblSides.getStyleClass().add("diff-sides");
        lblSides.setMinWidth(0);
        lblSides.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        show(lblSides, false);

        whitespaceIgnoredChip = new Button("Whitespace ignored");
        whitespaceIgnoredChip.setGraphic(Icons.close());
        whitespaceIgnoredChip.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        whitespaceIgnoredChip.getStyleClass().add("diff-chip");
        whitespaceIgnoredChip.setTooltip(new Tooltip(
                "Changes that only move whitespace are hidden. Click to show them again."));
        whitespaceIgnoredChip.setAccessibleText("Whitespace changes are ignored. Show them");
        whitespaceIgnoredChip.setOnAction(e -> {
            // The toggle's own press, so the host reloads the diff exactly as it does
            // when the toggle itself is clicked.
            if (btnIgnoreWhitespace != null && btnIgnoreWhitespace.isSelected()) {
                btnIgnoreWhitespace.fire();
            }
        });
        show(whitespaceIgnoredChip, false);

        encodingBox = buildEncodingBox();

        // A Button, not a Label with a mouse handler. A Label is not focusable, is not
        // reachable by Tab, does not activate on Enter or Space, and reports itself to a
        // screen reader as a piece of text rather than as something that can be pressed -
        // so this was a control only a mouse could find.
        //
        // A cross, at the trailing end of the title bar, named for where it goes: the
        // diff covers the graph and the sidebar, so closing it is how the user gets back
        // to the history. It read "<- History" for a while, two controls along from a
        // button called History that opened the file's history instead - one word on
        // two buttons for two different things.
        Button btnClose = Icons.button(Icons.close(), "Back to history",
                "Close this file and go back to the history (Esc)");
        btnClose.getStyleClass().add("diff-close");
        btnClose.setOnAction(e -> close());

        // A drawn glyph, not the emoji this used to be: JavaFX has no colour-font
        // support, so the code point arrived flat grey at the fallback font's baseline
        // while every other icon in the window was a drawn shape following the theme.
        titleBar = new HBox(10, Icons.file(), pathBox, btnCopyPath, lblSides,
                whitespaceIgnoredChip, grow(), btnClose);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.getStyleClass().add("diff-title-bar");

        // ---- Toolbar ----
        btnEdit = flatButton("Edit this file", "Edit this file here, then save it back");
        btnEdit.setGraphic(Icons.edit());
        btnEdit.setOnAction(e -> {
            // Which editor this means is a preference; the button is the same either way.
            if (com.jfx.git.core.AppPreferences.editorMode()
                    == com.jfx.git.core.AppPreferences.EditorMode.BUILT_IN) {
                beginEditing();
            } else if (onEditFile != null && !currentFilePath.get().isEmpty()) {
                openInWorkingTree(currentFilePath.get());
            }
        });

        btnSave = flatButton("Save", "Write the changes to the working tree (Ctrl+S)");
        btnSave.setOnAction(e -> saveEdits());

        btnSaveStage = flatButton("Save and stage",
                "Write the changes and stage the file in one step");
        btnSaveStage.setOnAction(e -> saveAndStage());

        // "Discard edits", not "Cancel": the button throws typed work away, and next to
        // Save a button called Cancel reads like "never mind, leave things as they are".
        btnCancelEdit = flatButton("Discard edits",
                "Throw away everything typed in this session and stop editing");
        btnCancelEdit.setOnAction(e -> session.confirmDiscard());

        btnFinishEdit = flatButton("Done", "Stop editing, keeping what has been saved");
        btnFinishEdit.setOnAction(e -> finishEditing());

        // The other half of the Edit split. Editing a historical revision used to mean
        // both things at once - open it, and write it over the working copy on save - and
        // only one of them was what the button said. They are two actions now, and this
        // is the one that overwrites.
        btnRestoreVersion = flatButton("Restore this version",
                "Replace the working copy with the revision shown here");
        btnRestoreVersion.setGraphic(Icons.restore());
        btnRestoreVersion.setOnAction(e -> restoreDisplayedVersion());

        editingBadge.getStyleClass().add("diff-editing-badge");

        btnOpenExternally = flatButton("Open externally",
                "Open this file in the application the system associates with it");
        btnOpenExternally.setGraphic(Icons.external());
        btnOpenExternally.setOnAction(e -> {
            if (onEditFile != null && !currentFilePath.get().isEmpty()) {
                openInWorkingTree(currentFilePath.get());
            }
        });

        for (javafx.scene.Node node : new javafx.scene.Node[]{
                btnEdit, btnSave, btnCancelEdit, btnFinishEdit, btnSaveStage, editingBadge,
                btnRestoreVersion, btnOpenExternally}) {
            node.setVisible(false);
            node.setManaged(false);
        }

        ToggleGroup viewGroup = new ToggleGroup();
        btnFileView = segmentedToggle("File view", viewGroup, "Show the whole file");
        btnDiffView = segmentedToggle("Diff view", viewGroup, "Show only the changed hunks");
        btnDiffView.setSelected(true);
        // The third way to look at a Markdown file, beside the other two rather than a
        // switch of its own: from the diff it is one click to the file as it reads, where
        // a separate toggle meant File view first and then Preview.
        btnPreview.setToggleGroup(viewGroup);
        viewGroup.selectedToggleProperty().addListener((obs, old, selected) -> {
            // Keep one of the three always active.
            if (selected == null) {
                viewGroup.selectToggle(old);
                return;
            }
            if (updatingToggle) {
                return; // state already set by the caller
            }
            if (selected != btnDiffView) {
                previewWanted = selected == btnPreview;
            }
            ViewMode wanted = selected == btnDiffView ? diffLayout : ViewMode.FILE;
            if (wanted == mode) {
                // Between the source and the page of a file already showing: nothing to
                // fetch, only the surface to swap.
                updatePreview();
                return;
            }
            // Leaving File view by hand returns to whichever diff layout was last used,
            // and entering it leaves blame behind.
            mode = wanted;
            if (onViewModeChanged != null) {
                // The host owns the content for this mode and will re-render.
                onViewModeChanged.accept(isFileView());
                return;
            }
            render();
        });

        btnPreview.setId("diff-preview");
        btnPreview.getStyleClass().add("diff-segment");
        btnPreview.setTooltip(new Tooltip("Show this Markdown file rendered"));
        btnPreview.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        show(btnPreview, false);

        btnBlame = flatButton("Blame", "Show blame annotations for this file");
        btnBlame.setOnAction(e -> {
            if (onBlame != null && !currentFilePath.get().isEmpty()) {
                onBlame.accept(currentFilePath.get());
            }
        });

        btnHistory = flatButton("File history", "Show the commit history for this file");
        btnHistory.setOnAction(e -> {
            if (onHistory != null && !currentFilePath.get().isEmpty()) {
                onHistory.accept(currentFilePath.get());
            }
        });

        btnExternalDiff = Icons.button(Icons.compare(), NAME_EXTERNAL_DIFF,
                "Open this comparison in the external diff tool");
        btnExternalDiff.getStyleClass().add("diff-tool-button");
        btnExternalDiff.setOnAction(e -> {
            if (onExternalDiff != null && externalDiffTarget != null && !currentFilePath.get().isEmpty()) {
                onExternalDiff.accept(externalDiffTarget.apply(currentFilePath.get()));
            }
        });
        show(btnExternalDiff, false);

        Button btnPrev = Icons.button(Icons.chevronUp(), "Previous change",
                "Jump to the previous change");
        btnPrev.getStyleClass().add("diff-tool-button");
        btnPrev.setOnAction(e -> jumpToHunk(currentHunkIndex - 1));
        Button btnNext = Icons.button(Icons.chevronDown(), "Next change", "Jump to the next change");
        btnNext.getStyleClass().add("diff-tool-button");
        btnNext.setOnAction(e -> jumpToHunk(currentHunkIndex + 1));
        // Held so a render can grey them out. jumpToHunk returns early on an empty diff,
        // so these were live controls that silently did nothing whenever there was
        // nothing to jump between - which is exactly when a user presses them twice to
        // check whether the window is responding.
        this.hunkNavButtons = List.of(btnPrev, btnNext);
        changeCounter.getStyleClass().add("diff-change-counter");
        changeCounter.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);

        ToggleGroup layoutGroup = new ToggleGroup();
        btnUnified = segmentedToggle("Unified", layoutGroup, "One column, changes interleaved");
        btnSplit = segmentedToggle("Split", layoutGroup, "Before and after side by side");
        btnUnified.setSelected(true);
        layoutGroup.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                layoutGroup.selectToggle(old);
                return;
            }
            if (updatingToggle) {
                return;
            }
            diffLayout = selected == btnSplit ? ViewMode.SPLIT : ViewMode.UNIFIED;
            // Changing the diff layout while reading a whole file is a choice about the
            // diff, not a request to leave File view.
            if (mode != ViewMode.FILE && mode != ViewMode.BLAME) {
                mode = diffLayout;
            }
            render();
        });

        // Orthogonal to Unified/Split: how much unchanged file surrounds each change. It
        // replaces a Whole file switch that sat beside "File view" - two controls whose
        // names said the same thing and whose behaviours did not. Every choice is legal
        // with either layout, so it is a setting beside the layout, not a ViewMode.
        ToggleGroup contextGroup = new ToggleGroup();
        btnContext3 = segmentedToggle("3", contextGroup,
                "Three lines of context around each change, as git shows them");
        btnContext10 = segmentedToggle("10", contextGroup,
                "Ten lines of context around each change");
        btnContextAll = segmentedToggle("All", contextGroup,
                "The whole file, with the changes marked in place");
        btnContext3.setAccessibleText("3 lines of context");
        btnContext10.setAccessibleText("10 lines of context");
        btnContextAll.setAccessibleText("Whole file");
        btnContext3.setSelected(true);
        contextGroup.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                contextGroup.selectToggle(old);
                return;
            }
            wholeFile = selected == btnContextAll;
            extraContext = selected == btnContext10 ? TEN_LINES_EXTRA : 0;
            if (!updatingToggle) {
                render();
            }
        });
        Label contextCaption = new Label("Context");
        contextCaption.getStyleClass().add("diff-toolbar-caption");
        HBox contextControl = new HBox(4, contextCaption,
                new HBox(btnContext3, btnContext10, btnContextAll));
        contextControl.setAlignment(Pos.CENTER_LEFT);

        // Two separate concerns: whether whitespace-only changes count as changes at
        // all (recomputes the diff), and whether spaces and tabs are drawn (display only).
        btnIgnoreWhitespace = Icons.toggle(Icons.pilcrow(), NAME_IGNORE_WHITESPACE,
                "Ignore whitespace changes (git diff -w)");
        btnIgnoreWhitespace.getStyleClass().add("diff-tool-toggle");
        btnIgnoreWhitespace.setOnAction(e -> {
            ignoreWhitespace = btnIgnoreWhitespace.isSelected();
            if (onIgnoreWhitespaceChanged != null) {
                // The diff itself must be recomputed, so the host reloads it.
                onIgnoreWhitespaceChanged.accept(ignoreWhitespace);
            } else {
                render();
            }
        });

        btnWhitespace = Icons.toggle(Icons.whitespaceDots(), NAME_SHOW_WHITESPACE,
                "Show whitespace characters");
        btnWhitespace.getStyleClass().add("diff-tool-toggle");
        btnWhitespace.setOnAction(e -> {
            showWhitespace = btnWhitespace.isSelected();
            render();
        });

        ToggleButton btnWrap = Icons.toggle(Icons.wrap(), NAME_WRAP, "Wrap long lines");
        btnWrap.getStyleClass().add("diff-tool-toggle");
        btnWrap.setOnAction(e -> {
            wrapLines = btnWrap.isSelected();
            render();
        });

        Button btnCopy = Icons.button(Icons.copy(), "Copy diff", "Copy the diff to the clipboard");
        btnCopy.getStyleClass().add("diff-tool-button");
        btnCopy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(diff.text());
            Clipboard.getSystemClipboard().setContent(content);
        });

        syntaxNotice.getStyleClass().add("diff-notice");
        syntaxNotice.setVisible(false);
        syntaxNotice.setManaged(false);

        // Everything here describes a *diff* - stepping between hunks, how to lay two
        // sides out, whether whitespace-only changes count, copying the diff text. None
        // of it means anything once the pane is showing a whole file or a blame
        // annotation, where commercial clients likewise leave only Blame and wrap standing.
        // Grouped into one node so a single show() hides the lot, separators included,
        // rather than eight fields each needing to remember to hide themselves.
        // Three groups, each separated: step between hunks, lay the diff out, draw it.
        // The layout group and the drawing group used to run together - Whole file sat
        // directly against the whitespace toggles with nothing between them - so the row
        // read as one long strip of controls after the second separator.
        //
        // A separator rather than the group labels the design sketch showed. This toolbar
        // is set to minWidth 0 so it clips instead of forcing the enclosing SplitPane
        // wider, which means every pixel of width costs a control that disappears sooner
        // in a narrow pane. Three labels cost about ninety pixels; a separator costs
        // seven, and buys the same thing the labels were for.
        diffOnlyTools = new HBox(6,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                btnPrev, changeCounter, btnNext, new Separator(javafx.geometry.Orientation.VERTICAL),
                new HBox(btnUnified, btnSplit), contextControl,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                btnIgnoreWhitespace, btnWhitespace, btnCopy);
        diffOnlyTools.setAlignment(Pos.CENTER_LEFT);

        // The mode labels are the one thing that must never shorten. "Uni..." and
        // "File..." are not abbreviations a reader can expand, they are the control
        // losing its name - and these four are how the reader knows what they are looking
        // at. Pinning them to their preferred width makes the overflow below fall on the
        // secondary controls instead, which is the point of having one.
        for (javafx.scene.control.ButtonBase primary
                : new javafx.scene.control.ButtonBase[]{btnUnified, btnSplit, btnFileView,
                        btnDiffView, btnContext3, btnContext10, btnContextAll}) {
            primary.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        }

        overflowMenu = buildOverflowMenu(btnPrev, btnNext, btnUnified, btnSplit,
                List.of(btnContext3, btnContext10, btnContextAll),
                btnIgnoreWhitespace, btnWhitespace, btnCopy);

        // Blame and History act on the *file*, not on the diff, so they belong beside the
        // path rather than among the controls that lay a diff out. Sitting in the middle
        // of the toolbar they read as two more diff options, which is also why they were
        // the two that stayed enabled in File view and looked like an oversight.
        // Anchored on btnClose, not on encodingBox: the encoding selector now lives in
        // the footer, so indexOf would answer -1 and addAll(-1, ...) would throw here in
        // the constructor - taking every viewer in the application with it.
        titleBar.getChildren().addAll(titleBar.getChildren().indexOf(btnClose),
                java.util.List.of(btnExternalDiff, btnBlame, btnHistory));

        // The badge and the syntax notice are facts about the document, and they now sit
        // in the footer with the rest of them. Worth more than tidiness: updateToolbarOverflow
        // sums prefWidth across this row's managed children, so moving two labels out buys
        // the diff controls real width before they collapse into the overflow menu.
        HBox toolbar = new HBox(6,
                btnEdit, btnSave, btnSaveStage, btnFinishEdit, btnCancelEdit,
                btnRestoreVersion, btnOpenExternally, grow(),
                new HBox(btnFileView, btnPreview, btnDiffView), grow(),
                diffOnlyTools, overflowMenu, btnWrap);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbarRow = toolbar;
        // Re-decided on every resize: which controls are on the row is a function of how
        // much row there is.
        toolbar.widthProperty().addListener((obs, old, width) -> updateToolbarOverflow());
        toolbar.getStyleClass().add("diff-toolbar");
        // Without this the toolbar's natural width becomes the panel's minimum and
        // the enclosing SplitPane overflows instead of honouring its dividers.
        toolbar.setMinWidth(0);
        titleBar.setMinWidth(0);
        setMinWidth(0);

        applyPreferences();

        // ---- Content ----
        areaScroll = new DiffScrollPane(area);
        // The bars sit on every visible hunk rather than following the pointer, so what
        // moves them is scrolling, not hovering. Selection matters too: it decides
        // whether "Stage lines" has anything to stage.
        area.selectionProperty().addListener((obs, old, value) -> {
            layoutHunkActions();
            updateCaretStatus();
        });
        area.caretPositionProperty().addListener((obs, old, value) -> updateCaretStatus());
        // A filter, not a handler: RichTextFX's own caret-placement behaviour consumes
        // the click before an ordinary handler would see it.
        area.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                e -> handleClick(e.getX(), e.getY()));
        // Which hunk you are inside changes as you scroll, so the bar follows the
        // viewport rather than the selection. Once per pulse, however many times the
        // position moved within it; see scheduleScrollFollow.
        area.estimatedScrollYProperty().addListener((obs, old, value) -> scheduleScrollFollow());
        sceneProperty().addListener((obs, old, scene) -> {
            if (old != null) {
                old.removePostLayoutPulseListener(scrollFollow);
            }
            if (scene != null) {
                scene.addPostLayoutPulseListener(scrollFollow);
            }
        });
        area.setContextMenu(buildEditContextMenu());
        // A filter, not a handler: RichTextFX's key handling would otherwise see the
        // key first. Everything here only applies while the buffer is an editor.
        area.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (!editing()) {
                return;
            }
            if (handleSaveShortcut(e.getCode(), e.isControlDown(), e.isShortcutDown())) {
                e.consume();
                saveEdits();
                return;
            }
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && !e.isShortcutDown()
                    && !e.isAltDown() && !e.isShiftDown()) {
                // The window's own Esc stands aside while an editor has focus, since a
                // bare key there is usually typing. Esc is not typing, so it is taken
                // here instead - through the same unsaved-edits question as the button.
                e.consume();
                close();
                return;
            }
            String line = currentLineText();
            switch (e.getCode()) {
                case TAB -> {
                    e.consume();
                    if (e.isShiftDown()) {
                        outdentSelection();
                    } else if (hasMultiLineSelection()) {
                        indentSelection();
                    } else {
                        // A caret with nothing selected: Tab is a character here, not a
                        // block operation. replaceSelection rather than insertText, so a
                        // selection within one line is replaced the way every other
                        // editor replaces it instead of being left behind.
                        area.replaceSelection(indentUnitFor(line));
                    }
                }
                case ENTER -> {
                    e.consume();
                    // Replaces the selection rather than inserting beside it: typing over
                    // a selection is what Enter does everywhere else, and insertText left
                    // the selected text in place with a newline pushed into the middle.
                    area.replaceSelection(autoIndentAfter(line));
                }
                default -> { }
            }
        });
        area.plainTextChanges()
                .successionEnds(LIVE_HIGHLIGHT_DEBOUNCE)
                .subscribe(change -> {
                    if (editing()) {
                        reapplyHighlightingWhileEditing();
                        updateEditingBadge();
                        updateDocumentStatus();
                        // The text changed; the colours follow when the lane answers.
                        minimap.markDirty();
                    }
                });
        // A second, slower subscription rather than more work in the first: this one
        // touches the disk, and it should not run at typing speed.
        area.plainTextChanges()
                .successionEnds(DRAFT_DEBOUNCE)
                .subscribe(change -> {
                    if (editing()) {
                        session.writeDraft();
                    }
                });
        area.plainTextChanges()
                .successionEnds(PREVIEW_DEBOUNCE)
                .subscribe(change -> {
                    if (editing() && isPreviewing()) {
                        markdownView.render(area.getText(), currentFilePath.get());
                    }
                });

        stickyHeader.getStyleClass().add("diff-sticky-header");
        // As wide as the text, not the whole viewport: beyond the text sit the scrollbar
        // and the minimap, and a header painted across their tops hides the thumb.
        stickyHeader.maxWidthProperty().bind(areaScroll.widthProperty());
        stickyHeader.setMouseTransparent(true);
        stickyHeader.setVisible(false);
        stickyHeader.setManaged(false);

        // The overlay only exists to float the hunk actions above the text, so it must
        // not intercept clicks meant for the text underneath it.
        overlay.setPickOnBounds(false);
        overlay.setMouseTransparent(false);
        // The bars are positioned against the overlay's right edge, so a resize moves
        // every one of them.
        overlay.widthProperty().addListener((obs, old, value) -> layoutHunkActions());

        placeholder.getStyleClass().add("diff-empty");
        placeholder.setMouseTransparent(true);
        placeholder.setWrapText(true);

        showAnyway = flatButton("Show anyway", "Render this diff despite its size");
        showAnyway.setVisible(false);
        showAnyway.setManaged(false);
        showAnyway.setOnAction(e -> {
            overrideLargeDiff = true;
            render();
        });
        StackPane.setMargin(showAnyway, new javafx.geometry.Insets(56, 0, 0, 20));

        HBox.setHgrow(areaScroll, Priority.ALWAYS);
        // Text, minimap, scrollbar - the editor's own order. The minimap follows the
        // single surface for good: File view is never split.
        contentRow = new HBox(areaScroll, minimap, scrollMap);
        minimap.attach(area);
        show(minimap, false);

        buildDraftBanner();
        viewport.getChildren().addAll(contentRow, stickyHeader, placeholder, showAnyway,
                draftBanner, overlay);
        StackPane.setAlignment(draftBanner, Pos.TOP_LEFT);
        StackPane.setAlignment(placeholder, Pos.TOP_LEFT);
        StackPane.setAlignment(showAnyway, Pos.TOP_LEFT);
        StackPane.setAlignment(stickyHeader, Pos.TOP_LEFT);
        VBox.setVgrow(viewport, Priority.ALWAYS);

        footer.adopt(syntaxNotice, encodingBox, editingBadge);
        getChildren().addAll(titleBar, toolbar, findBar, viewport, footer);
    }

    // ------------------------------------------------------------------
    // Find
    // ------------------------------------------------------------------

    /**
     * Opens find over what is on screen - with replace as well when {@code withReplace}
     * and the file is being edited. Does nothing with no file open.
     */
    public void showFind(boolean withReplace) {
        if (currentFilePath.get().isEmpty()) return;
        // A rendered page is searched as it reads. Replacing is typing, which happens in
        // the source, so asking to replace while editing goes back to it.
        if (withReplace && editing()) leavePreview();
        findBar.showFind(withReplace);
    }

    /** Opens go to line over what is on screen. Does nothing with no file open. */
    public void showGoToLine() {
        if (currentFilePath.get().isEmpty()) return;
        leavePreview();
        findBar.showGoToLine();
    }

    /**
     * Puts the caret at the start of {@code line} (from 1) of the whole file on screen, and
     * the line in the middle of the view - where a search result opens a file. Does
     * nothing in a diff, or for a line the file does not have.
     */
    public void revealLine(int line) {
        if (!isFileView() || line < 1) return;
        leavePreview();
        DiffTextArea surface = splitSurfaceVisible && rightArea != null ? rightArea : area;
        if (line > surface.getParagraphs().size()) return;
        Runnable place = () -> {
            try {
                surface.moveTo(line - 1, 0);
                surface.showParagraphAtCenter(line - 1);
                surface.requestFocus();
            } catch (RuntimeException beforeLayout) {
                // Nothing laid out yet; the deferred attempt below places it.
            }
        };
        place.run();
        // Again once the new text has been laid out: placed before that, the view settles
        // wherever Flowless's first estimate put it.
        javafx.application.Platform.runLater(place);
    }

    /** Back to the source: what replace and go to line work on. */
    private void leavePreview() {
        if (isPreviewing()) {
            previewWanted = false;
            updatePreview();
        }
    }

    /** Closes find if it is open; for a host whose Esc should close the bar before the file. */
    public boolean closeFind() {
        if (!findBar.isVisible()) return false;
        findBar.hide();
        return true;
    }

    /** The find bar, for tests. */
    FindBar findBar() {
        return findBar;
    }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    /**
     * The encoding dropdown.
     *
     * <p>Git records content as bytes and no encoding, so this is not a display
     * preference - it decides what the bytes say. Everything on screen is decoded from
     * {@link #currentBytes}, which means a change here re-decodes what is already
     * loaded rather than asking Git for it again.
     */
    private ComboBox<TextEncoding> buildEncodingBox() {
        ComboBox<TextEncoding> box = new ComboBox<>();
        box.getItems().setAll(TextEncoding.selectable());
        box.setValue(TextEncoding.GUESS);
        box.getStyleClass().add("diff-encoding");
        box.setTooltip(new Tooltip("How the bytes of this file are decoded"));

        box.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(TextEncoding item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label().toUpperCase(java.util.Locale.ROOT));
            }
        });
        // "Guess Encoding" is a rule, not an answer; the button shows the answer it
        // reached so the user can see what they are actually reading.
        box.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(TextEncoding item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : resolvedEncodingLabel(item));
            }
        });

        box.valueProperty().addListener((obs, old, selected) -> {
            if (selected == null || updatingEncoding) {
                return;
            }
            encoding = selected;
            redecode();
        });
        return box;
    }

    /** What {@code selected} actually decodes the current bytes as. */
    private String resolvedEncodingLabel(TextEncoding selected) {
        if (selected != TextEncoding.GUESS) {
            return selected.label();
        }
        // Guessed once per byte buffer. The button cell asks on every refresh, and every
        // diff arrival refreshes it - and a guess decodes the whole buffer to judge it.
        if (guessedFor != currentBytes) {
            guessedLabel = TextEncoding.guess(currentBytes).name().replace('_', '-');
            guessedFor = currentBytes;
        }
        return guessedLabel;
    }

    /** The buffer {@link #guessedLabel} was guessed from; compared by identity. */
    private byte[] guessedFor;
    private String guessedLabel;

    /** The dropdown itself, so a host with its own header can place it there. */
    public Node encodingSelector() {
        return encodingBox;
    }

    public TextEncoding getEncoding() {
        return encoding;
    }

    /**
     * Re-reads {@link #currentBytes} under the current encoding and redraws.
     *
     * <p>Blame is left alone: its text arrives already split into rows by JGit rather
     * than as a byte buffer this viewer holds.
     */
    private void redecode() {
        refreshEncodingLabel();
        if (mode == ViewMode.BLAME) {
            render();
            return;
        }
        if (bytesAreDiff) {
            if (fileViewBytesOverride != null) {
                fileContentOverride = encoding.decode(fileViewBytesOverride);
            }
            // A new loaded diff, whose newer side - the lines revealed between hunks - is
            // decoded afresh too.
            diff = diff.withEncoding(encoding);
            currentHunkIndex = -1;
        } else {
            fileContentOverride = encoding.decode(currentBytes);
        }
        render();
    }

    /** Nudges the button cell to recompute the label a guess resolved to. */
    private void refreshEncodingLabel() {
        updatingEncoding = true;
        TextEncoding selected = encodingBox.getValue();
        encodingBox.setValue(null);
        encodingBox.setValue(selected);
        updatingEncoding = false;
    }

    /**
     * Returns the dropdown to the repository's default encoding - Guess unless the
     * repository's settings name one - which every newly shown file starts from.
     */
    private void resetEncoding() {
        encoding = RepositoryPreferences.defaultEncoding(repository.get());
        updatingEncoding = true;
        encodingBox.setValue(encoding);
        updatingEncoding = false;
    }

    /** The working tree whose default encoding a newly shown file starts from; none until told. */
    private java.util.function.Supplier<java.io.File> repository = () -> null;

    /**
     * Tells the viewer which repository it shows, so a newly shown file starts at that
     * repository's default encoding (see {@link RepositoryPreferences#defaultEncoding}).
     * A supplier because the tab's backend learns its work tree only once it has opened.
     */
    public void setRepository(java.util.function.Supplier<java.io.File> workTree) {
        this.repository = workTree == null ? () -> null : workTree;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public StringProperty filePathProperty() {
        return currentFilePath;
    }

    /**
     * Names the two versions a diff compares, e.g. Index and Working tree.
     *
     * @param older    the side the red lines come from, or null when there is no pair
     * @param newer    the side the green lines go to
     * @param fileView what File view shows: usually {@code newer}, but a staged diff's
     *                 File view is the working copy, not the index
     */
    public record DiffSides(String older, String newer, String fileView) {
        /** An unstaged change: what is staged against what is on disk. */
        public static final DiffSides UNSTAGED = new DiffSides("Index", "Working tree", "Working tree");
        /** A staged change: the last commit against what is staged. */
        public static final DiffSides STAGED = new DiffSides("HEAD", "Index", "Working tree");
        /** A plain working-tree file with no diff behind it. */
        public static final DiffSides WORKING_TREE = new DiffSides(null, null, "Working tree");

        /** Two revisions, where File view shows the newer one. */
        public static DiffSides between(String older, String newer) {
            return new DiffSides(older, newer, newer);
        }
    }

    /**
     * Says which two versions the next content compares. Call before showing it; it holds
     * until the file is closed or another call replaces it.
     */
    public void setComparedSides(DiffSides sides) {
        this.sides = sides;
        // A new comparison is not the old one's two versions. Whoever knows what they
        // are says so next; until then there is nothing to hand an external tool.
        this.externalDiffTarget = null;
        updateSidesLabel();
        updateExternalDiff();
    }

    /** Accessible name of the header's external-diff button. */
    public static final String NAME_EXTERNAL_DIFF = "External diff";

    /**
     * Says which two versions of a path the content now on screen compares, so they can
     * be opened in the user's own diff tool. Call after {@link #setComparedSides}, which
     * clears it; {@code null} for content that is not a comparison of two versions.
     */
    public void setExternalDiffTarget(
            java.util.function.Function<String, com.jfx.git.core.ExternalTools.DiffTarget> target) {
        this.externalDiffTarget = target;
        updateExternalDiff();
    }

    /** Supplies what opens a comparison in the external tool; {@code null} removes the button. */
    public void setOnExternalDiff(Consumer<com.jfx.git.core.ExternalTools.DiffTarget> handler) {
        this.onExternalDiff = handler;
        updateExternalDiff();
    }

    /**
     * The button is there only when pressing it would do something: a tool is chosen in
     * Preferences, and the host has said what the two versions are. Hidden rather than
     * disabled otherwise - most people never set a tool, and a permanently greyed icon
     * in every diff header is a question with no answer on screen.
     */
    private void updateExternalDiff() {
        boolean available = onExternalDiff != null && externalDiffTarget != null && sides != null
                && !currentFilePath.get().isEmpty()
                && com.jfx.git.core.AppPreferences.diffToolMode() != com.jfx.git.core.ExternalTools.Mode.NONE;
        show(btnExternalDiff, available);
        btnExternalDiff.setDisable(loading);
    }

    /** The header's "older → newer", or the one version File view is showing. */
    private void updateSidesLabel() {
        String text = null;
        if (sides != null && !currentFilePath.get().isEmpty()) {
            if (editing()) {
                // Whatever revision was on screen, the buffer is the working copy.
                text = "Working tree · editing";
            } else if (mode == ViewMode.FILE) {
                text = sides.fileView();
            } else if (mode != ViewMode.BLAME) {
                text = sides.older() != null && sides.newer() != null
                        ? sides.older() + " → " + sides.newer()
                        : sides.fileView();
            }
        }
        lblSides.setText(text == null ? "" : text);
        show(lblSides, text != null && !text.isEmpty());
        leftColumnHeader.setText(sides != null && sides.older() != null ? sides.older() : "Before");
        rightColumnHeader.setText(sides != null && sides.newer() != null ? sides.newer() : "After");
    }

    /** What the header says is being compared; for tests. */
    String comparedSidesText() {
        return lblSides.isVisible() ? lblSides.getText() : "";
    }

    /** Split view's two column names; for tests. */
    List<String> splitColumnHeadersForTest() {
        return List.of(leftColumnHeader.getText(), rightColumnHeader.getText());
    }

    /** Whether the "Whitespace ignored" chip is up; for tests. */
    boolean whitespaceIgnoredChipShowing() {
        return whitespaceIgnoredChip.isVisible();
    }

    /** Presses the "Whitespace ignored" chip; for tests. */
    void pressWhitespaceIgnoredChipForTest() {
        whitespaceIgnoredChip.fire();
    }

    /** Sets the header to {@code path}: its file name, then its folder. */
    private void showPath(String path) {
        if (path == null || path.isEmpty()) {
            lblFileName.setText("No file selected");
            lblFileDir.setText("");
            pathBox.setAccessibleText("No file selected");
            pathTooltip.setText("No file selected");
            return;
        }
        int slash = path.lastIndexOf('/');
        lblFileName.setText(slash < 0 ? path : path.substring(slash + 1));
        lblFileDir.setText(slash < 0 ? "" : path.substring(0, slash + 1));
        pathBox.setAccessibleText(path);
        pathTooltip.setText(path);
    }

    /** The path the header shows, whole; for tests. */
    String headerPath() {
        return pathBox.getAccessibleText();
    }

    private void copyPath() {
        if (!currentFilePath.get().isEmpty()) {
            copyToClipboard(currentFilePath.get());
        }
    }

    private static void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * Closes the file, as the History button and Esc do.
     *
     * <p>Goes through the unsaved-edits question like any other change of content, so a
     * dirty buffer asks first and Cancel leaves everything where it was.
     */
    public void close() {
        if (!currentFilePath.get().isEmpty()) {
            setFileDiff("", "");
        }
    }

    /**
     * Shows diff text that offers no hunk actions; {@code ("", "")} clears the viewer.
     * For a diff that should, say which kind it is: {@link #setFileDiffBytes(DiffKind, String, byte[])}.
     */
    public void setFileDiff(String filePath, String diffText) {
        setFileDiff(DiffKind.COMPARISON, filePath, diffText);
    }

    /** As {@link #setFileDiffBytes(DiffKind, String, byte[])}, from text. */
    public void setFileDiff(DiffKind kind, String filePath, String diffText) {
        setFileDiffBytes(kind, filePath, diffText == null ? new byte[0] : diffText.getBytes(StandardCharsets.UTF_8));
    }

    /** As {@link #setFileDiff}, from bytes: a diff that offers no hunk actions. */
    public void setFileDiffBytes(String filePath, byte[] diffBytes) {
        setFileDiffBytes(DiffKind.COMPARISON, filePath, diffBytes);
    }

    /** As {@link #setFileDiffBytes(DiffKind, String, byte[], byte[])}: a diff that offers no hunk actions. */
    public void setFileDiffBytes(String filePath, byte[] diffBytes, byte[] wholeFileBytesForFileView) {
        setFileDiffBytes(DiffKind.COMPARISON, filePath, diffBytes, wholeFileBytesForFileView);
    }

    /**
     * Shows diff output from its raw bytes, decoded with the selected encoding.
     *
     * <p>Bytes, not text: it is the byte buffer that lets the encoding dropdown mean
     * anything. The kind decides which hunk actions are offered; context is filled in
     * from the host's content supplier, which is the working tree - right for an unstaged
     * diff, so a diff of anything else should hand its own revision over with
     * {@link #setFileDiffBytes(DiffKind, String, byte[], byte[])}.
     */
    public void setFileDiffBytes(DiffKind kind, String filePath, byte[] diffBytes) {
        showDiffAgainst(kind, filePath, diffBytes, null, null);
    }

    /**
     * Like {@link #setFileDiffBytes(DiffKind, String, byte[])}, but also supplies the
     * whole-file content behind it, so File view, Whole file and the expanders show this
     * specific historical, stashed, or compared revision instead of falling through to the
     * generic working-tree supplier, which would otherwise show the wrong (current) content.
     */
    public void setFileDiffBytes(DiffKind kind, String filePath, byte[] diffBytes, byte[] wholeFileBytesForFileView) {
        showDiffAgainst(kind, filePath, diffBytes, wholeFileBytesForFileView, wholeFileBytesForFileView);
    }

    /**
     * Shows a staged diff - HEAD against the index - whose new side is {@code newSide},
     * while File view and editing keep using the working tree.
     *
     * <p>Its new side is the index, which is what Whole file and the context expanders
     * must reveal, but the file a user views or edits from there is still the working
     * copy: a staged diff must not start editing from the index.
     */
    public void setStagedDiffBytes(String filePath, byte[] diffBytes, byte[] newSide) {
        setStagedDiffBytes(DiffKind.STAGED, filePath, diffBytes, newSide);
    }

    /**
     * As {@link #setStagedDiffBytes(String, byte[], byte[])}, of the kind given: an
     * unmerged path's staged side is a {@link DiffKind#CONFLICT}, not a staged change.
     */
    public void setStagedDiffBytes(DiffKind kind, String filePath, byte[] diffBytes, byte[] newSide) {
        showDiffAgainst(kind, filePath, diffBytes, null, newSide);
    }

    private void showDiffAgainst(DiffKind kind, String filePath, byte[] diffBytes, byte[] fileViewBytes,
                                 byte[] newSide) {
        if (!session.leaveForNewContent(() -> showDiffAgainst(kind, filePath, diffBytes, fileViewBytes, newSide))) {
            return;
        }
        boolean sameFile = currentFilePath.get().equals(filePath == null ? "" : filePath);
        // A reload of the diff behind a file being read in File view - the repository
        // changed while the user was reading it - keeps File view. Only a host that owns
        // the mode switch itself (file history) gets the diff layout it asked for.
        boolean stayInFileView = sameFile && mode == ViewMode.FILE && bytesAreDiff
                && onViewModeChanged == null && filePath != null && !filePath.isEmpty();

        this.loading = false;
        this.blameLines = List.of();
        this.mode = stayInFileView ? ViewMode.FILE : diffLayout;
        this.fileViewBytesOverride = fileViewBytes;
        if (filePath == null || filePath.isEmpty()) {
            sides = null;
            externalDiffTarget = null;
        }
        this.currentBytes = diffBytes == null ? new byte[0] : diffBytes;
        this.bytesAreDiff = true;
        showPath(filePath);
        this.currentFilePath.set(filePath == null ? "" : filePath);

        boolean hasFile = filePath != null && !filePath.isEmpty();
        btnBlame.setDisable(!hasFile || onBlame == null);
        btnHistory.setDisable(!hasFile || onHistory == null);
        updateExternalDiff();

        if (!sameFile) {
            // A new file gets a fresh guess; carrying the last file's choice over would
            // silently mis-decode it.
            resetEncoding();
            highlighter.clear();
            // Rows, colours, the decoded file and the image pane were all this file's.
            forgetDerived();
            overrideLargeDiff = false;
        }

        // Decoded after the encoding reset above, and before rendering: it used to be set
        // only once the render had run, so a persisted Whole file had already filled from
        // the working tree by the time the revision's own content arrived.
        this.fileContentOverride = fileViewBytes == null ? null : encoding.decode(fileViewBytes);
        diff = LoadedDiff.read(kind, filePath, currentBytes, encoding, newSideOf(filePath, newSide),
                ignoreWhitespace);
        // What the expanders opened stays open when the diff is reloaded - the same unstaged
        // change after one of its hunks was staged - and closes for anything else, even
        // under the same path: File history steps from commit to commit of one file, and
        // one commit's reveal state, keyed by hunk index, meant nothing in the next.
        if (!sameFile || !diff.continues(revealedFor)) {
            revealedAbove = Map.of();
        }
        revealedFor = diff;
        currentHunkIndex = -1;
        refreshEncodingLabel();
        syncToggles();
        render();
        if (!sameFile) {
            openAtTop();
        }
        session.offerDraft();
    }

    /**
     * Where a diff's newer side is read from, for expanding context and Whole file: what
     * the host handed over with it, or else the host's content supplier - the working tree.
     *
     * <p>Kept apart from {@link #fileContentOverride}, which is also what editing starts
     * from: a staged diff's newer side is the index, and editing must not start from that.
     */
    private java.util.function.Supplier<byte[]> newSideOf(String path, byte[] given) {
        if (given != null) {
            return () -> given;
        }
        Function<String, byte[]> supplier = fileContentSupplier;
        return supplier == null ? null : () -> supplier.apply(path);
    }

    /** The diff on screen; {@link LoadedDiff#NONE} when what is shown is not one. For tests. */
    LoadedDiff loadedDiff() {
        return diff;
    }

    /**
     * Registers the "open in an external editor" action and reveals the button.
     *
     * <p>Visibility itself is decided only by {@link #updateEditControls()}; this method
     * merely records the handler. It used to also write {@code btnEdit}'s visibility,
     * which made two owners of one property — harmless only while the host always
     * passed a non-null handler, and a latent bug otherwise.
     */
    public void setOnEditFile(Consumer<String> handler) {
        this.onEditFile = handler;
        updateEditControls();
    }

    public void setOnBlame(Consumer<String> handler) {
        this.onBlame = handler;
        btnBlame.setDisable(handler == null || currentFilePath.get().isEmpty());
    }

    public void setOnHistory(Consumer<String> handler) {
        this.onHistory = handler;
        btnHistory.setDisable(handler == null || currentFilePath.get().isEmpty());
    }

    /**
     * Runs the hunk actions the diff on screen offers - Stage hunk, Discard lines, Revert
     * hunk and the rest. Which ones appear is the diff's own business, decided by its
     * {@link DiffKind}; the host only runs them. {@code null} offers none.
     */
    public void setOnHunkAction(Consumer<HunkAction> runner) {
        this.onHunkAction = runner;
        layoutHunkActions();
    }

    /**
     * Says how to read any path of the revision now being shown - a commit's, a stash's -
     * so a Markdown preview's images are that revision's, not today's. {@code null} for
     * the working tree. Called off the FX thread. The host sets it with every load, so
     * one file's revision never outlives it.
     */
    public void setRevisionReader(Function<String, byte[]> reader) {
        this.revisionReader = reader;
    }

    /**
     * Says how to find what a file shown from a commit is called now, so "Restore this
     * version" on a file renamed since can offer today's name; see {@link RestoreDestination}.
     * {@code null} where the name on screen is the one to write - a stash, a comparison, the
     * working tree. The host sets it beside {@link #setExternalDiffTarget} on every route that
     * shows a revision, so one commit's lookup never answers for another's file.
     */
    public void setRenameLookup(RenameLookup lookup) {
        this.renameLookup = lookup;
        session.setRenameLookup(lookup);
    }

    /**
     * Where the preview reads its images: the revision on screen, or the working tree
     * while editing, since the buffer being edited is the working copy.
     */
    private Function<String, byte[]> imageReader() {
        return editing() || revisionReader == null ? fileContentSupplier : revisionReader;
    }

    /**
     * Provides whole-file content for File view.
     *
     * <p>In a viewer that saves - one given {@link #setOnSaveFile} - this has to read the
     * working tree: the editing session takes it as the working copy, to start from and to
     * check for changes made underneath. A viewer whose supplier reads a revision, as file
     * history's and the snapshots' do, sets no save handler.
     */
    public void setFileContentSupplier(Function<String, byte[]> supplier) {
        this.fileContentSupplier = supplier;
    }

    /**
     * The repository this viewer's readers read - the file content supplier, the revision
     * reader - so what it reads off the FX thread itself, a Markdown preview's images, runs
     * on that repository's lane. Null, the default, for readers that touch no repository.
     */
    public void setReaderRepository(com.jfx.git.core.GitBackend repository) {
        this.readerRepository = repository;
    }

    /** See {@link #setReaderRepository}. */
    private com.jfx.git.core.GitBackend readerRepository;

    /**
     * Provides the two revisions of a binary file.
     *
     * <p>Without one, an image change renders as "binary file - content not shown", which
     * is accurate and useless. With one, the two revisions are drawn side by side.
     */
    public void setImageSupplier(Function<String, ImageDiffView.Blobs> supplier) {
        this.imageSupplier = supplier;
        invalidateImageDiff();
    }

    /**
     * Hides the Blame and History buttons.
     *
     * <p>Used when the viewer is already embedded in the file-history view, where
     * blame is a toggle on that view rather than a way to open it.
     */
    public void setEmbedded(boolean embedded) {
        btnBlame.setVisible(!embedded);
        btnBlame.setManaged(!embedded);
        btnHistory.setVisible(!embedded);
        btnHistory.setManaged(!embedded);
        // The host already shows the path in its own header; a second copy is noise.
        titleBar.setVisible(!embedded);
        titleBar.setManaged(!embedded);
    }

    /**
     * Shows {@code path} annotated line by line.
     *
     * <p>Sets every piece of state in one go. Driving the mode through separate
     * setters left the flags out of step, because each one triggered its own render.
     */
    public void showBlame(String path, List<com.jfx.git.core.BlameLine> lines) {
        showBlame(path, lines, null);
    }

    /**
     * The same, plus the diff of the commit being blamed, used only to mark where that
     * commit's changes fall on the scroll map.
     *
     * <p>Blame renders every line as plain context, so without this the map has nothing
     * to go on and the strip beside the scrollbar stays blank - the whole file, and no
     * indication of which part of it the selected commit actually touched.
     *
     * @param diffBytesForMarks that commit's diff for this path, or {@code null} for none
     */
    public void showBlame(String path, List<com.jfx.git.core.BlameLine> lines, byte[] diffBytesForMarks) {
        if (!session.leaveForNewContent(() -> showBlame(path, lines, diffBytesForMarks))) {
            return;
        }
        this.loading = false;
        // The blamed commit's diff, read whole like any other - its summary and charset
        // included, where a bare hunk list used to leave the previous diff's behind. Only
        // the scroll map reads it; blame offers no hunk actions. currentBytes is left
        // alone: it is the blamed file here, and the encoding selector reads it.
        this.diff = diffBytesForMarks == null || diffBytesForMarks.length == 0 ? LoadedDiff.NONE
                : LoadedDiff.read(DiffKind.COMMIT, path, diffBytesForMarks, encoding, null, false);
        this.blameLines = lines == null ? List.of() : lines;
        assignBandColours();
        this.mode = ViewMode.BLAME;
        this.fileContentOverride = null;
        this.fileViewBytesOverride = null;

        showPath(path);
        currentFilePath.set(path == null ? "" : path);
        syncToggles();
        render();
    }

    /** Shows a unified diff, clearing any blame annotation. */
    public void showDiff(String path, String diffText) {
        showDiffBytes(path, diffText == null ? new byte[0] : diffText.getBytes(StandardCharsets.UTF_8));
    }

    /** Shows a unified diff from its raw bytes, clearing any blame annotation. */
    public void showDiffBytes(String path, byte[] diffBytes) {
        setFileDiffBytes(path, diffBytes);
    }

    /** Shows the whole file at a given revision, without blame annotations. */
    public void showFileContent(String path, String content) {
        showFileBytes(path, content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
    }

    /** Shows a whole file from its raw bytes, decoded with the selected encoding. */
    public void showFileBytes(String path, byte[] content) {
        if (!session.leaveForNewContent(() -> showFileBytes(path, content))) {
            return;
        }
        this.loading = false;
        this.blameLines = List.of();
        this.mode = ViewMode.FILE;
        // Content replaced on its own: whatever diff was loaded describes some other
        // revision of this path - File history in File view steps from one commit's file
        // to the next without fetching a diff - so nothing may mark positions against it.
        this.diff = LoadedDiff.NONE;

        boolean sameFile = currentFilePath.get().equals(path == null ? "" : path);
        if (!sameFile) {
            resetEncoding();
            forgetDerived();
        }
        this.currentBytes = content == null ? new byte[0] : content;
        this.bytesAreDiff = false;
        this.fileViewBytesOverride = null;
        this.fileContentOverride = encoding.decode(currentBytes);

        showPath(path);
        currentFilePath.set(path == null ? "" : path);
        syncToggles();
        refreshEncodingLabel();
        render();
        if (!sameFile) {
            openAtTop();
        }
        session.offerDraft();
    }

    /**
     * A file just opened starts at its first line, caret there too.
     *
     * <p>Said explicitly rather than assumed. Replacing the text leaves RichTextFX's caret
     * at the end, and Flowless's scroll estimate can lurch while the new paragraphs are
     * still unmeasured; its scroll pane writes that estimate back as a position, and a
     * long file then opened two screens from its end. Only for a new path - a reload of
     * the file already on screen keeps the reader's place.
     */
    private void openAtTop() {
        DiffTextArea surface = splitSurfaceVisible ? rightArea : area;
        if (surface == null) {
            return;
        }
        try {
            surface.moveTo(0);
            surface.showParagraphAtTop(0);
            if (splitSurfaceVisible && leftArea != null) {
                leftArea.moveTo(0);
                leftArea.showParagraphAtTop(0);
            }
        } catch (RuntimeException beforeFirstLayout) {
            // Nothing to place yet; the first layout opens at the top anyway.
        }
    }

    /** Notified with {@code true} for File view and {@code false} for Diff view. */
    public void setOnViewModeChanged(Consumer<Boolean> handler) {
        this.onViewModeChanged = handler;
    }

    /** True when a whole file is showing rather than the diff. */
    public boolean isFileView() {
        return mode == ViewMode.FILE || mode == ViewMode.BLAME;
    }

    /**
     * Whether whitespace-only changes should be excluded from the diff.
     * Callers pass this to the {@code GitService} diff methods.
     */
    public boolean isIgnoreWhitespace() {
        return ignoreWhitespace;
    }

    /** Notified when the ignore-whitespace toggle changes; the host must reload the diff. */
    public void setOnIgnoreWhitespaceChanged(Consumer<Boolean> handler) {
        this.onIgnoreWhitespaceChanged = handler;
    }

    /** Aligns both toolbar toggles with {@link #mode} without re-entering render. */
    private void syncToggles() {
        updatingToggle = true;
        if (isFileView()) {
            btnFileView.setSelected(true);
        } else {
            btnDiffView.setSelected(true);
        }
        if (diffLayout == ViewMode.SPLIT) {
            btnSplit.setSelected(true);
        } else {
            btnUnified.setSelected(true);
        }
        updatingToggle = false;
    }

    /**
     * Declares whether what is being shown is the working tree.
     *
     * <p>Only the working-tree copy can be edited. A file as it stood at some commit
     * is history, and typing into it would either be silently discarded or - worse -
     * written over the current version of a file the user is not looking at.
     */
    public void setEditable(boolean editable) {
        this.editableSource = editable;
        // Only a clean session is ended here. The host calls this just before loading
        // different content - clicking a file in a commit's list does it first - so
        // cancelling a dirty session at this point threw the edits away without the
        // prompt that the content load itself would have shown. It also overrode a
        // Cancel given at that prompt, since the host calls this after it regardless.
        // A dirty session is left for EditorSession.leaveForNewContent to ask about.
        if (!editable && editing() && !hasUnsavedEdits()) {
            cancelEditing();
        }
        updateEditControls();
    }

    /**
     * Receives {@code (path, text)} when the user saves an edit.
     *
     * <p>Setting this is what makes the in-application editor available at all. This
     * method deliberately touches no button state: {@link #updateEditControls()} is the
     * single owner of which editing controls are visible, so a handler arriving early or
     * late cannot fight it for {@code btnEdit}'s visibility. (Two owners used to set it;
     * whichever ran last won.)
     */
    public void setOnSaveFile(FileSaveHandler handler) {
        session.setSaveHandler(handler);
        updateEditControls();
    }

    /** True while the file view is a typable buffer. */
    public boolean isEditing() {
        return editing();
    }

    /**
     * Shows that {@code path} is being loaded, replacing whatever was on screen.
     *
     * <p>Leaving the previous file's diff up until the next one arrives is not a neutral
     * wait. The hunk actions beside it stage and revert, and while the header already says
     * the new file's name they are still aimed at the old one's content - so a click
     * during the gap does something to a file the user is no longer looking at. Emptying
     * the surface removes the hunks, which is what removes the actions.
     *
     * <p>Refuses to disturb an editing session: the buffer is the user's work, and a
     * background reload is not a reason to lose it. The host's own request id then drops
     * the result when it lands.
     */
    public void showLoading(String path) {
        if (editing()) {
            return;
        }
        // The header tells the truth about what is coming, but currentFilePath is left
        // alone: setFileDiffBytes compares against it to decide whether this is a
        // different file, and a different file is what resets the encoding guess, the
        // revealed context and the syntax cache. Moving it here would make every load
        // look like a reload of the same file and none of that would ever reset.
        showPath(path);
        this.loading = true;
        // The offer belongs to the file that was on screen, not the one loading.
        session.withdrawDraftOffer();
        this.diff = LoadedDiff.NONE;
        setRows(LoadedDiff.Rows.EMPTY);
        this.currentBytes = new byte[0];
        this.fileContentOverride = null;
        this.fileViewBytesOverride = null;
        showSingleSurface();
        area.setRows(List.of(), index -> false);
        placeholder.setText("Loading " + (path == null || path.isEmpty() ? "file" : path) + "…");
        updateEditControls();
        afterContentRendered();
    }

    /** Whether the content on screen belongs to the path in the header. */
    private boolean loading;

    /** Reports a diff that could not be loaded, in place of the content. */
    public void showLoadFailed(String message) {
        if (editing()) {
            return;
        }
        placeholder.setText(message == null || message.isBlank()
                ? "This file could not be loaded." : message);
    }

    /** The text currently in the editor, exactly as the buffer holds it. */
    public String editorText() {
        return area.getText();
    }

    /**
     * The buffer in the file's own line-ending convention, ready to be written.
     *
     * <p>Not the same string as {@link #editorText()}, and the difference is a bug this
     * fixes. RichTextFX drops every CR on the way into the document, and
     * {@code TextEncoding.encode} restores only the BOM and the charset - so editing one
     * line of a CRLF file and saving rewrote every line of it to LF. A one-character
     * change produced a whole-file diff and took the file's history with it.
     *
     * <p>A {@code MIXED} file is written in its majority convention. That still rewrites
     * the minority lines, and there is no cheap way not to - the buffer has lost which
     * line had which ending. But it is the least damage available: leaving the buffer's
     * LF-only text alone, which is what this first did, rewrote the <em>majority</em> of a
     * mostly-CRLF file - precisely the whole-file diff this method exists to prevent, on
     * precisely the files autocrlf produces. The footer still says the file is mixed.
     */
    public String textForSaving() {
        return session.withDocumentEndings(area.getText());
    }

    /** The path whose content is in the editor, for a synchronous host-side save. */
    public String editingFilePath() {
        return currentFilePath.get();
    }

    /** Whether the user has actually changed anything since editing began. */
    public boolean hasUnsavedEdits() {
        return session.hasUnsavedEdits();
    }

    /**
     * The badge between Save and Cancel, which doubles as the dirty indicator.
     *
     * <p>Commercial clients mark unsaved edits with a blue dot; the same information rides on a
     * label this pane already shows. Package-private so tests can drive it without
     * waiting out the keystroke debounce.
     */
    void updateEditingBadge() {
        boolean dirty = hasUnsavedEdits();
        session.follow(dirty);

        switch (session.saveState()) {
            case SAVING -> editingBadge.setText("saving…");
            case FAILED -> editingBadge.setText("not saved — " + session.saveFailure());
            default -> editingBadge.setText(dirty ? "edited · unsaved" : "editable");
        }
        editingBadge.setTooltip(new Tooltip(switch (session.saveState()) {
            case SAVING -> "Writing the buffer to the working tree";
            case FAILED -> "The last save failed, so these edits exist only here: "
                    + session.saveFailure();
            case DIRTY -> "Edited since the last save (Ctrl+S)";
            case CLEAN -> "The buffer matches the file on disk";
        }));

        // A failed save is worse than merely unsaved, and carries its own style so it
        // does not read as the ordinary "you have typed something" state.
        styleFlag(editingBadge, "diff-editing-dirty", dirty || session.lastSaveFailed());
        styleFlag(editingBadge, "diff-editing-failed", session.lastSaveFailed());
        // Nothing to discard while the edits are on their way to disk: they land anyway,
        // and the session waits for them; see EditorSession.confirmDiscard.
        btnCancelEdit.setDisable(session.isSaving());
    }

    /**
     * Refreshes the caret and selection cells. Cheap enough to run on every arrow key.
     *
     * <p>Read from {@code getCurrentParagraph()}/{@code getCaretColumn()} rather than
     * {@code offsetToPosition}, which would walk the document to answer the same
     * question.
     */
    private void updateCaretStatus() {
        if (!editing()) {
            return;
        }
        footer.show(statusNow(false));
    }

    /**
     * Refreshes the cells that need a look at the whole document - line endings and the
     * indentation the file actually uses.
     *
     * <p>Called from {@link #afterContentRendered()}, which every render path exits
     * through, and from the keystroke debounce. Not from the caret listener: scanning the
     * document on every arrow key is the difference between a footer and a stutter.
     */
    private void updateDocumentStatus() {
        footer.show(statusNow(true));
    }

    /** The editing buffer's indentation, as of the last whole-document look. */
    private String indentSummaryCache = "";

    /**
     * What the footer should currently be showing.
     *
     * <p>The line-ending cell is the awkward one. It has to describe the <em>document</em>,
     * and in Diff view the buffer is not the document: a unified diff's own scaffolding
     * lines carry no CR while the context lines lifted out of a CRLF file do, so counting
     * the buffer reports "Mixed" for every CRLF file in the repository. Only an editing
     * buffer or File view holds the real thing; everywhere else the cell is hidden rather
     * than guessed at.
     */
    /**
     * @param scanDocument whether to look at the whole document again; false from the
     *                     caret listener, which reuses the last scan's indentation
     */
    private EditorStatusFooter.Status statusNow(boolean scanDocument) {
        boolean hasFile = !currentFilePath.get().isEmpty();
        if (!hasFile) {
            return EditorStatusFooter.Status.idle();
        }

        // Copying the buffer is itself a pass over the document, so the caret path must
        // not ask for it: it used to, on every arrow key, and then scanned the copy for
        // its indentation as well.
        boolean editing = editing();
        String document = editing
                ? (scanDocument ? area.getText() : null)
                : (isFileView() ? fileContent() : null);
        com.jfx.git.core.LineEndings endings;
        com.jfx.git.core.LineEndings dominant;
        com.jfx.git.core.LineEndings.Counts counts;
        if (editing) {
            // The buffer is LF-only by construction, so counting it would report "LF" for
            // every CRLF file being edited - while the save writes CRLF. What matters while
            // editing is what will reach the disk, and that is the file's own convention.
            endings = session.endings();
            dominant = session.dominantEnding();
            counts = session.endingCounts();
        } else if (document == null) {
            endings = null;
            dominant = null;
            counts = null;
        } else {
            // Three passes over the whole file, kept per document: every render in File
            // view ends here, display toggles included.
            Object[] facts = derive("endings", () -> {
                var detected = com.jfx.git.core.LineEndings.detect(document);
                return new Object[]{detected,
                        detected == com.jfx.git.core.LineEndings.MIXED
                                ? com.jfx.git.core.LineEndings.dominant(document) : null,
                        com.jfx.git.core.LineEndings.count(document)};
            }, document);
            endings = (com.jfx.git.core.LineEndings) facts[0];
            dominant = (com.jfx.git.core.LineEndings) facts[1];
            counts = (com.jfx.git.core.LineEndings.Counts) facts[2];
        }

        int line = 1;
        int column = 1;
        int selectedChars = 0;
        int selectedLines = 0;
        String indent = "";
        if (editing) {
            line = area.getCurrentParagraph() + 1;
            column = area.getCaretColumn() + 1;
            var selection = area.getSelection();
            selectedChars = selection.getLength();
            if (selectedChars > 0) {
                selectedLines = lineAt(selection.getEnd()) - lineAt(selection.getStart()) + 1;
            }
            if (scanDocument) {
                indentSummaryCache = indentSummary(document);
            }
            indent = indentSummaryCache;
        }

        return new EditorStatusFooter.Status(true, editing, line, column,
                selectedChars, selectedLines, indent, endings, dominant, counts,
                session.externalChangePending());
    }

    /**
     * How long typing has to pause before the buffer is parked.
     *
     * <p>Longer than the highlight debounce on purpose. Two seconds is one write per pause
     * rather than one per word, and a crash costs a sentence.
     */
    private static final Duration DRAFT_DEBOUNCE = Duration.ofSeconds(2);

    /** The recovery offer, pinned over the top of the content; see {@link EditorSession#offerDraft}. */
    private final HBox draftBanner = new HBox(UiScale.STEP);
    private final Label draftBannerText = new Label();

    /**
     * How {@code text} indents: {@code "Tabs"}, {@code "Spaces: 4"}, or empty when the
     * document offers no evidence either way.
     *
     * <p>Read off the document rather than off a preference, for the same reason
     * {@link #indentUnitFor} is: a file indented with tabs should keep being indented with
     * tabs regardless of what the last file did.
     *
     * <p>Bounded at {@link #INDENT_SCAN_LINES} - the answer from the first few thousand
     * lines is the answer.
     */
    static String indentSummary(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int tabLed = 0;
        java.util.TreeSet<Integer> depths = new java.util.TreeSet<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length && i < INDENT_SCAN_LINES; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            char first = line.charAt(0);
            if (first == '\t') {
                tabLed++;
            } else if (first == ' ') {
                int spaces = 0;
                while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
                // A line that is nothing but spaces says nothing about indentation.
                if (spaces < line.length()) depths.add(spaces);
            }
        }

        if (tabLed > depths.size()) {
            return "Tabs";
        }
        if (depths.isEmpty()) {
            return tabLed > 0 ? "Tabs" : "";
        }
        return "Spaces: " + smallestStep(depths);
    }

    /** Lines scanned before {@link #indentSummary} stops looking. */
    private static final int INDENT_SCAN_LINES = 5_000;

    /**
     * The smallest gap between consecutive indent depths, which is the indent width.
     *
     * <p>Taking the shallowest depth outright would misread a file whose first indented
     * line happens to be a deeply wrapped argument list.
     */
    private static int smallestStep(java.util.TreeSet<Integer> depths) {
        int step = depths.first();
        Integer previous = null;
        for (int depth : depths) {
            if (previous != null && depth - previous > 0) {
                step = Math.min(step, depth - previous);
            }
            previous = depth;
        }
        return Math.max(1, Math.min(8, step));
    }

    /** Adds or removes one style class without duplicating it. */
    private static void styleFlag(javafx.scene.Node node, String styleClass, boolean on) {
        if (on) {
            if (!node.getStyleClass().contains(styleClass)) node.getStyleClass().add(styleClass);
        } else {
            node.getStyleClass().remove(styleClass);
        }
    }

    /**
     * Whether this key press is the editor's save shortcut, so it must not also reach
     * the text buffer.
     *
     * <p>Plain {@code Ctrl+S}; {@code shortcut} is JavaFX's name for the platform's command
     * key, which is Ctrl on every platform this ships on. A seam rather than inline in the
     * filter so a test can ask without synthesising a KeyEvent.
     */
    static boolean handleSaveShortcut(javafx.scene.input.KeyCode code,
                                      boolean controlDown, boolean shortcutDown) {
        return (controlDown || shortcutDown)
                && code == javafx.scene.input.KeyCode.S;
    }

    /**
     * The text of the paragraph the caret sits in.
     *
     * <p>While editing, whitespace markers are off (beginEditing turns them off for the
     * whole session), so the display text is the source text and no remapping applies.
     */
    private String currentLineText() {
        return area.getParagraph(area.getCurrentParagraph()).getText();
    }

    /**
     * What one press of Tab means on this line: a tab when the file already indents
     * with tabs, four spaces otherwise.
     *
     * <p>Reading the file rather than a preference keeps mixed-indent repositories from
     * being re-indented under the editor.
     */
    static String indentUnitFor(String line) {
        String leading = leadingWhitespace(line);
        return leading.indexOf('\t') >= 0 ? "\t" : "    ";
    }

    /** Spaces per indent level, and how many Shift+Tab takes off at a time. */
    private static final int INDENT_WIDTH = 4;

    /**
     * Whether the selection spans more than one line, which is what turns Tab from
     * typing a character into indenting a block.
     */
    private boolean hasMultiLineSelection() {
        var selection = area.getSelection();
        if (selection.getLength() == 0) return false;
        return lineAt(selection.getStart()) != lineAt(selection.getEnd());
    }

    /** The paragraph index {@code offset} falls in. */
    private int lineAt(int offset) {
        return area.offsetToPosition(offset, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
    }

    /**
     * Indents every line the selection touches.
     *
     * <p>Tab used to append an indent at the caret whatever was selected, so on a selected
     * block it replaced the block with four spaces.
     */
    private void indentSelection() {
        editSelectedLines(line -> indentUnitFor(line) + line);
    }

    /**
     * Removes one indent level from every line the selection touches.
     *
     * <p>Shift+Tab used to fall through to the plain Tab case and insert four
     * <em>more</em> spaces, which is the opposite of what the gesture means everywhere
     * else.
     */
    private void outdentSelection() {
        editSelectedLines(DiffViewer::withoutOneIndent);
    }

    /** {@code line} with one leading indent level removed, if it has one. */
    static String withoutOneIndent(String line) {
        if (line == null || line.isEmpty()) return line;
        if (line.charAt(0) == '\t') return line.substring(1);
        int spaces = 0;
        while (spaces < INDENT_WIDTH && spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return line.substring(spaces);
    }

    /**
     * Rewrites every line the selection touches through {@code rewrite}, as one edit.
     *
     * <p>A single {@code replaceText} rather than one call per line, so the whole gesture
     * is one undo step: a block outdent that needs thirty presses of Ctrl+Z to reverse is
     * its own small kind of data loss. The selection is put back over the rewritten lines
     * so the block can be indented again straight away.
     */
    private void editSelectedLines(java.util.function.UnaryOperator<String> rewrite) {
        var selection = area.getSelection();
        int firstLine = lineAt(selection.getStart());
        int lastLine = selection.getLength() == 0 ? firstLine : lineAt(selection.getEnd());
        // A selection ending exactly at a line start covers nothing of that line, and
        // indenting it would surprise anyone who dragged to the end of the line above.
        if (lastLine > firstLine && area.getAbsolutePosition(lastLine, 0) == selection.getEnd()) {
            lastLine--;
        }

        int from = area.getAbsolutePosition(firstLine, 0);
        int to = area.getAbsolutePosition(lastLine, area.getParagraph(lastLine).length());

        StringBuilder rewritten = new StringBuilder();
        for (int line = firstLine; line <= lastLine; line++) {
            if (line > firstLine) rewritten.append('\n');
            rewritten.append(rewrite.apply(area.getParagraph(line).getText()));
        }
        area.replaceText(from, to, rewritten.toString());
        area.selectRange(from, from + rewritten.length());
    }

    /**
     * What Enter inserts: the newline plus the current line's own indentation, so an
     * open brace does not drop the next line back to column zero.
     */
    static String autoIndentAfter(String line) {
        return "\n" + leadingWhitespace(line);
    }

    private static String leadingWhitespace(String line) {
        if (line == null) {
            return "";
        }
        int i = 0;
        while (i < line.length()
                && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    /**
     * Replaces the buffer wholesale, for tests that play the typist.
     *
     * <p>Package-private like {@link #handleSaveShortcut}: driving real key events
     * through Monocle for what is a one-line mutation would buy flakiness, not fidelity.
     * Only meaningful while editing; the caller supplies the same debounced follow-up
     * ({@link #reapplyHighlightingWhileEditing()}, {@link #updateEditingBadge()}) that
     * the real change subscription would.
     */
    void replaceBuffer(String text) {
        area.replaceText(text);
    }

    /**
     * Presses a key on the editing surface, for tests that verify the shortcut filter.
     *
     * <p>Fired at {@code area} itself: an event aimed higher would walk the parent chain
     * and never descend into the child where the filter lives.
     */
    /** Puts the caret at a line and column, so a test can check what the footer reports. */
    void moveCaretForTest(int line, int column) {
        area.moveTo(line, column);
    }

    void pressKeyForTest(javafx.scene.input.KeyCode code, boolean controlDown) {
        javafx.event.Event.fireEvent(area,
                new javafx.scene.input.KeyEvent(
                        javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "", "", code, false, controlDown, false, false));
    }

    /**
     * Turns the file view into an editor; see {@link EditorSession#begin()}.
     *
     * <p>Switches to File view first if necessary: there is no sensible way to edit a
     * diff. Whitespace markers are turned off for the duration, because the middots
     * and arrows are display substitutions - saving with them visible would write
     * them into the file.
     */
    public void beginEditing() {
        session.begin();
    }

    /**
     * Opens the working copy of {@code shown} in the external application: under the name
     * the file has now when it has been renamed since the revision on screen, since the old
     * name is not on disk to open.
     */
    private void openInWorkingTree(String shown) {
        RenameLookup lookup = renameLookup;
        if (lookup == null || workingCopyExists(shown)) {
            onEditFile.accept(shown);
            return;
        }
        lookup.currentNameOf(shown, renamedTo -> onEditFile.accept(renamedTo == null ? shown : renamedTo));
    }

    /**
     * Whether the path is really on disk, as opposed to readable-but-empty.
     *
     * <p>The supplier answers with empty bytes for both an empty file and a missing one,
     * and the two need different wording - one is editable, the other is not there.
     */
    private boolean workingCopyExists(String path) {
        return onWorkingCopyExists == null || onWorkingCopyExists.test(path);
    }

    /** Answers whether a path exists in the working tree; see {@link EditorSession.WorkingCopy#exists}. */
    public void setWorkingCopyExistsCheck(java.util.function.Predicate<String> check) {
        this.onWorkingCopyExists = check;
    }

    /**
     * Writes the revision currently on screen over the working copy, after saying so; see
     * {@link EditorSession#restoreVersion}.
     *
     * <p>The confirmation states the destination, because "restore" sounds recoverable and
     * this is not: anything uncommitted in that file is replaced.
     */
    public void restoreDisplayedVersion() {
        session.restoreVersion(currentFilePath.get(), fileContent());
    }

    /**
     * Writes the buffer to disk, and stays in the editor; see {@link EditorSession#save()}.
     * The buffer is held until the write is confirmed.
     */
    public void saveEdits() {
        session.save();
    }

    /**
     * Saves and stages in one step, for the change the user knows is finished. Like
     * {@link #saveEdits()} it keeps the session open; {@link #finishEditing()} is how a
     * session ends.
     */
    public void saveAndStage() {
        session.saveAndStage();
    }

    /** Ends the session for a closing tab, leaving no draft behind; see {@link EditorSession#endForClose()}. */
    public void endSessionForClose() {
        session.endForClose();
    }

    /**
     * Parks the buffer now, without waiting out the debounce.
     *
     * <p>For tests. The debounce is two seconds, and sleeping through it in every case
     * that needs a parked draft would add half a minute to the suite for no coverage.
     */
    void writeDraftForTest() {
        session.writeDraft();
    }

    private void buildDraftBanner() {
        draftBanner.getStyleClass().add("diff-draft-banner");
        draftBanner.setAlignment(Pos.CENTER_LEFT);
        draftBanner.setMaxWidth(Double.MAX_VALUE);
        draftBannerText.getStyleClass().add("diff-draft-banner-text");
        draftBannerText.setWrapText(true);

        Button restore = flatButton("Restore draft", "Put the unsaved text back in the editor");
        restore.setOnAction(e -> session.restoreDraft());
        Button compare = flatButton("Compare", "See the draft beside the file on disk");
        compare.setOnAction(e -> session.compareDraft());
        Button dismiss = flatButton("Discard draft", "Forget the unsaved text");
        dismiss.setOnAction(e -> session.dismissDraft());

        draftBanner.getChildren().addAll(draftBannerText, restore, compare, dismiss);
        hideDraftBanner();
    }

    private void hideDraftBanner() {
        draftBanner.setVisible(false);
        draftBanner.setManaged(false);
    }

    /** "14:03 today" / "on 21 September", which is what a recovery offer needs to say. */
    private static String describeWhen(java.time.Instant when) {
        java.time.ZonedDateTime local = when.atZone(java.time.ZoneId.systemDefault());
        java.time.LocalDate today = java.time.LocalDate.now();
        String time = String.format("%02d:%02d", local.getHour(), local.getMinute());
        return local.toLocalDate().equals(today)
                ? time + " today"
                : time + " on " + local.getDayOfMonth() + " "
                        + local.getMonth().getDisplayName(
                                java.time.format.TextStyle.FULL, java.util.Locale.getDefault());
    }

    /**
     * The text this session believes is on disk.
     *
     * <p>The editor's belief, not a fresh read - it moves only when a write is confirmed.
     * That is exactly what makes it usable as the base of a three-way reconciliation, and
     * why a save from inside the application never looks like an external change: the
     * baseline moves at the same instant the file does.
     */
    public String baselineText() {
        return session.baseline();
    }

    /**
     * Whether the file moved under this editing session.
     *
     * <p>A pure question with no dialog attached, so the host can ask it while deciding
     * whether to close a tab or light a warning, without this class owning either UX.
     */
    public boolean workingCopyChangedSinceEditingBegan() {
        return session.workingCopyChangedSinceEditingBegan();
    }

    /**
     * Lights the footer's "changed on disk" cell.
     *
     * <p>Deliberately not a dialog. This is reached when the window regains focus, and a
     * modal that appears because someone alt-tabbed back is hostile - the point is to warn
     * before the next Ctrl+S, not to interrupt at an arbitrary moment.
     */
    public void noteExternalChange() {
        session.noteExternalChange();
    }

    /** Puts the notice out again, once the divergence is resolved one way or the other. */
    public void clearExternalChangeNotice() {
        session.clearExternalChangeNotice();
    }

    /** Runs the reload the external-change prompt offers, without the prompt. */
    void reloadFromDiskForTest() {
        session.reloadFromDisk();
    }

    /**
     * Ends the editing session, keeping what is on screen; see {@link EditorSession#finish()}.
     * A session with unsaved work refuses to end quietly.
     */
    public void finishEditing() {
        session.finish();
    }

    /**
     * Registers the handler that writes the buffer and stages the file, enabling the
     * Save-and-Stage button and context-menu entry alongside Save.
     */
    public void setOnSaveAndStage(FileSaveHandler handler) {
        session.setSaveAndStageHandler(handler);
        updateEditControls();
    }

    /** Leaves edit mode and puts back what was on screen before. */
    public void cancelEditing() {
        session.cancel();
    }

    /** Whether the Edit button applies at all, whichever editor it would use. */
    private boolean editorAvailable() {
        return usesBuiltInEditor()
                ? session.canEdit()
                : onEditFile != null && !currentFilePath.get().isEmpty();
    }

    private static boolean usesBuiltInEditor() {
        return com.jfx.git.core.AppPreferences.editorMode()
                == com.jfx.git.core.AppPreferences.EditorMode.BUILT_IN;
    }

    /**
     * The Edit button's label and tooltip, matching the commercial clients' distinction: editing
     * the working tree itself reads differently from editing a historical, stashed, or
     * compared revision, where Save has to write into the working tree rather than
     * "editing" an immutable historical object - the button says so before the click
     * rather than surprising the user after it. Only meaningful for the built-in editor;
     * the external-editor button keeps its original wording, since {@link FileOpener}'s
     * "Open externally" always needs a real file on disk and that limitation is
     * unaffected by any of this.
     */
    private void applyEditButtonLabel() {
        if (usesBuiltInEditor() && !editableSource) {
            btnEdit.setText("Edit working copy");
            btnEdit.setTooltip(new Tooltip(workingCopyExists(currentFilePath.get())
                    ? "Open the current working-tree copy of this file for editing. "
                            + "The revision shown here is not modified."
                    : "This file is not in the working tree. Editing starts from the "
                            + "revision shown here, and saving recreates the file."));
        } else {
            btnEdit.setText("Edit this file");
            btnEdit.setTooltip(new Tooltip("Edit this file here, then save it back"));
        }
    }

    /** Shows exactly the controls that apply: Edit, or the editing set while editing. */
    private void updateEditControls() {
        applyEditButtonLabel();
        updateSidesLabel();
        // While a diff is in flight the header already names the new file but the pane
        // still holds nothing, so anything that would act on "the current file" is acting
        // on a guess. Disabled rather than hidden: a control that vanishes and comes back
        // during every selection is worse than one that greys out.
        btnEdit.setDisable(loading);
        btnBlame.setDisable(loading || currentFilePath.get().isEmpty() || onBlame == null);
        btnHistory.setDisable(loading || currentFilePath.get().isEmpty() || onHistory == null);
        updateExternalDiff();
        boolean editing = editing();
        show(btnEdit, editorAvailable() && !editing);
        show(btnSave, editing);
        show(btnSaveStage, editing && session.canSaveAndStage());
        show(btnFinishEdit, editing);
        show(btnCancelEdit, editing);
        show(editingBadge, editing);
        // Only offered where it means something: a revision that is not the working copy,
        // with somewhere to write it to.
        show(btnRestoreVersion, !editing && !editableSource && usesBuiltInEditor()
                && session.canSave() && !currentFilePath.get().isEmpty());
        // Whitespace markers are display substitutions - middots for spaces, arrows for
        // tabs - so a buffer showing them would save them into the file. beginEditing
        // turns them off; this keeps them off for the session rather than leaving a
        // toggle there that corrupts the save.
        btnWhitespace.setDisable(editing);
        // Only offered alongside the built-in editor. With an external one chosen the
        // Edit button already opens it, and two buttons doing the same thing is noise.
        show(btnOpenExternally, usesBuiltInEditor()
                && onEditFile != null
                && !currentFilePath.get().isEmpty());
        updateWholeFileControl();
    }

    // ------------------------------------------------------------------
    // The editing session's host side
    // ------------------------------------------------------------------

    /**
     * The text area and the file on it, as {@link EditorSession} sees them. What the
     * session decides, this draws: File view, the working copy in place of a revision,
     * the editing style, the badge and the footer.
     */
    private final class SessionBuffer implements EditorSession.Buffer {
        @Override
        public String path() {
            return currentFilePath.get();
        }

        @Override
        public TextEncoding encoding() {
            return encoding;
        }

        @Override
        public boolean showsWorkingCopy() {
            return editableSource;
        }

        @Override
        public String text() {
            return area.getText();
        }

        @Override
        public void replace(String text) {
            area.replaceText(text);
        }

        @Override
        public boolean showWholeFile() {
            if (!isFileView()) {
                // Selecting the toggle runs its listener synchronously, so by the next
                // line this reflects whatever it just did. Usually that is enough to
                // proceed in the same press: the whole-file content is normally already
                // available (see setFileDiffBytes's 3-arg overload). Only when nothing
                // is available yet - onViewModeChanged doing an asynchronous re-fetch,
                // as FileHistoryView's embedded viewer does - does the user need to
                // press Edit again once it lands.
                btnFileView.setSelected(true);
                if (fileContent() == null) {
                    return false;
                }
            }
            if (showWhitespace) {
                btnWhitespace.setSelected(false);
                showWhitespace = false;
            }
            return true;
        }

        @Override
        public String showForEditing(byte[] workingCopy) {
            if (workingCopy != null) {
                // The override is what pinned File view to the historical revision;
                // replacing it shows the file as it is on disk, which is what Save will be
                // writing over.
                fileContentOverride = encoding.decode(workingCopy);
                fileViewBytesOverride = workingCopy;
                editableSource = true;
            }
            // From here on render() leaves the buffer alone, so this is the last chance to
            // put the file's own text into it - and it matters, because whitespace markers
            // are display substitutions that would otherwise be saved into the file as
            // middots and arrows.
            render();
            // The last moment the document still has its carriage returns: the text area
            // works in paragraphs, a paragraph cannot hold a terminator, and everything
            // after this point sees an LF-only buffer.
            return fileContent();
        }

        @Override
        public void showWorkingCopyOf(String path, byte[] content) {
            showFileBytes(path, content);
            // What is on screen now is the working copy itself.
            editableSource = true;
        }

        @Override
        public void adoptEncoding(TextEncoding recorded) {
            encodingBox.setValue(recorded);
        }

        @Override
        public void setEditing(boolean editing) {
            if (editing) {
                // The area is a display surface until now, and keeps no undo history while
                // it is one (see DiffTextArea): becoming editable is what starts a fresh
                // history, so Ctrl+Z at the start of a session cannot walk back past the
                // file into whatever was shown before it - the diff rows, an earlier file,
                // an empty pane - and leaving the session drops it again.
                editingColours = List.of();
                area.setEditable(true);
                area.getStyleClass().add("diff-editing");
            } else {
                area.setEditable(false);
                area.getStyleClass().remove("diff-editing");
            }
            updateEditControls();
            // Ending, this clears the dirty marker, so the next session starts honest.
            updateEditingBadge();
            if (editing) {
                // The render ran before the session opened, so it did not look at the
                // indentation; the caret listener only reuses what a scan found.
                updateDocumentStatus();
            }
            // A preview open over the session draws the working copy, images included -
            // and once it ends, the revision on screen again.
            if (isPreviewing()) updatePreview();
            if (editing) area.requestFocus();
        }

        @Override
        public void restoreDisplay() {
            // Re-rendered from the file's source, discarding whatever was typed: the bytes
            // cached for it, which a save replaces (see saved), or the working tree.
            render();
        }

        @Override
        public void saved(String document) {
            // Only what is cached is replaced. With nothing cached File view reads the
            // working tree, which has the save in it now; with something cached it drew
            // the file as editing found it, so Done after a save showed the text from
            // before it until a reload happened along.
            if (fileContentOverride != null) {
                fileContentOverride = document;
            }
            // The bytes too, which a change of encoding re-reads, and BINARY dumps -
            // encoded the way the save itself was, against the bytes they replace.
            boolean encodable = encoding != TextEncoding.BINARY;
            if (encodable && fileViewBytesOverride != null) {
                fileViewBytesOverride = encoding.encode(document, fileViewBytesOverride);
            }
            if (encodable && !bytesAreDiff) {
                currentBytes = encoding.encode(document, currentBytes);
            }
            // Whatever was shown when editing began, what is on screen now is the working
            // copy: it is what was just written there.
            editableSource = true;
        }

        @Override
        public void refresh() {
            updateEditingBadge();
            updateDocumentStatus();
        }
    }

    /** The session's questions, as this application's dialogs, and the draft banner. */
    private final class SessionPrompter implements EditorSession.Prompter {
        @Override
        public SaveFirst askToSave(String path, Ending why, String lastFailure) {
            ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
            ButtonType discard = new ButtonType("Discard edits", ButtonBar.ButtonData.NO);
            ButtonType choice = unsavedQuestion(path, why == Ending.FINISHING
                            ? "Save your edits before you stop editing?" : "Save your edits first?",
                    lastFailure)
                    .buttons(save, discard, ButtonType.CANCEL)
                    .danger(discard)
                    .answer();
            if (choice == ButtonType.CANCEL) {
                return SaveFirst.CANCEL;
            }
            return choice == discard ? SaveFirst.DISCARD : SaveFirst.SAVE;
        }

        @Override
        public boolean confirmDiscard(String path, String lastFailure) {
            // The draft goes with them, so nothing can bring them back: Enter is Cancel.
            String where = unsavedWhere(path);
            return com.jfx.git.ui.dialogs.Dialogs.destructive("Discard edits")
                    .title("Discard your unsaved edits?")
                    .subtitle(lastFailure != null ? where + " · the last save failed" : where)
                    .message("Everything typed since the last save is thrown away, and this cannot be"
                            + " undone.")
                    .details(lastFailure)
                    .action("Discard edits")
                    .safeDefault()
                    .ask();
        }

        @Override
        public boolean askToRecreate(String path) {
            return com.jfx.git.ui.dialogs.Dialogs.warn("Save file")
                    .icon(Icons.file())
                    .title("Recreate `" + path + "`?")
                    .subtitle("Deleted or moved while it was open here")
                    .message("It is no longer in the working tree. Saving writes your edits back"
                            + " as the file; Cancel keeps them in the editor.")
                    .action("Recreate the file")
                    .ask();
        }

        @Override
        public ChangedOnDisk askAboutChange(String path) {
            // Both ways out throw one version away, so both are red, and Enter keeps both:
            // nothing takes a snapshot of either the buffer or the other writer's file.
            ButtonType compare = new ButtonType("Compare…", ButtonBar.ButtonData.LEFT);
            ButtonType overwrite = new ButtonType("Overwrite", ButtonBar.ButtonData.YES);
            ButtonType reload = new ButtonType("Discard my edits and reload",
                    ButtonBar.ButtonData.NO);
            ButtonType choice = com.jfx.git.ui.dialogs.Dialogs.warn("Save file")
                    .icon(Icons.file())
                    .title("`" + path + "` changed on disk")
                    .subtitle("Since this editor opened it")
                    .message("Overwriting replaces what was written there with your edits. Compare"
                            + " puts the two side by side to make one version from both.")
                    .buttons(compare, overwrite, reload, ButtonType.CANCEL)
                    .danger(overwrite)
                    .danger(reload)
                    .safeDefault()
                    .size(com.jfx.git.ui.dialogs.JfxDialog.Size.MD)
                    .answer();
            if (choice == compare) {
                return ChangedOnDisk.COMPARE;
            }
            if (choice == overwrite) {
                return ChangedOnDisk.OVERWRITE;
            }
            if (choice == reload) {
                return ChangedOnDisk.RELOAD;
            }
            return ChangedOnDisk.CANCEL;
        }

        /**
         * The merge dialog is the right shape rather than a convenient one, the baseline
         * being what both sides diverged from. It does not compute a merge, which is worth
         * being honest about - it marks the lines the two versions differ on and leaves the
         * result to the reader, and that is still a large improvement on overwriting one of
         * them without asking.
         */
        @Override
        public Optional<String> reconcile(String path, String mine, String onDisk, String baseline) {
            return new com.jfx.git.ui.dialogs.MergeToolDialog(
                    mine, onDisk, baseline, mine,
                    new com.jfx.git.ui.dialogs.MergeToolDialog.Labels(
                            "Compare with the file on disk - " + path,
                            "My edits", "On disk now", "Use this text", false))
                    .resolve();
        }

        /**
         * A banner pinned over the top of the content rather than a dialog: it has to be
         * ignorable, which a modal is not.
         */
        @Override
        public void offerDraft(java.time.Instant savedAt) {
            draftBannerText.setText("An unsaved draft of this file from "
                    + describeWhen(savedAt) + " was recovered.");
            draftBanner.setVisible(true);
            draftBanner.setManaged(true);
        }

        @Override
        public void withdrawDraftOffer() {
            hideDraftBanner();
        }

        @Override
        public Optional<String> compareDraft(String path, String draft, String onDisk) {
            return new com.jfx.git.ui.dialogs.MergeToolDialog(
                    draft, onDisk, onDisk, draft,
                    new com.jfx.git.ui.dialogs.MergeToolDialog.Labels(
                            "Recovered draft - " + path,
                            "Recovered draft", "On disk now", "Use this text", false))
                    .resolve();
        }

        @Override
        public boolean confirmRestore(String path) {
            // The write takes no snapshot first: Enter is Cancel.
            return com.jfx.git.ui.dialogs.Dialogs.destructive("Restore file")
                    .icon(Icons.restore())
                    .title("Restore this version of `" + path + "`?")
                    .subtitle("It replaces the working copy")
                    .message("Anything uncommitted in that file is overwritten, and this cannot be"
                            + " undone from here.")
                    .action("Restore")
                    .safeDefault()
                    .ask();
        }

        @Override
        public Optional<String> chooseRestoreDestination(String path, String renamedTo) {
            return RestoreDestination.ask("Restore this version of `" + path + "`?", null,
                    path, renamedTo);
        }
    }

    /**
     * What the unsaved-work prompts say, so they all say the same thing: the file in the
     * subtitle, and whether the last save failed - git's or the disk's own words for why
     * behind Show Details.
     *
     * @param lastFailure why the last save failed, or {@code null} when it did not
     */
    private static com.jfx.git.ui.dialogs.Dialogs.Message unsavedQuestion(String path, String question,
                                                                          String lastFailure) {
        var ask = com.jfx.git.ui.dialogs.Dialogs.confirm("Unsaved edits")
                .icon(Icons.edit())
                .title(question);
        if (lastFailure != null) {
            return ask.subtitle(unsavedWhere(path) + " · the last save failed")
                    .message("Your edits exist only here, in the editor. Discarded edits cannot be"
                            + " brought back.")
                    .details(lastFailure);
        }
        return ask.subtitle(unsavedWhere(path) + " · not saved yet")
                .message("Discarded edits cannot be brought back.");
    }

    private static String unsavedWhere(String path) {
        return path == null || path.isEmpty() ? "This file" : "`" + path + "`";
    }

    /**
     * The working tree, as the host's content supplier and existence check read it. Only a
     * host with a save handler opens a session, and its supplier is the working tree's -
     * see {@link #setFileContentSupplier}.
     */
    private final class SessionWorkingCopy implements EditorSession.WorkingCopy {
        @Override
        public boolean readable() {
            return fileContentSupplier != null;
        }

        @Override
        public boolean exists(String path) {
            return workingCopyExists(path);
        }

        @Override
        public byte[] read(String path) {
            Function<String, byte[]> supplier = fileContentSupplier;
            return supplier == null ? null : supplier.apply(path);
        }

        /**
         * A stat of the file in the repository's folder, where the host named one: the
         * supplier cannot say how large a file is without reading all of it.
         */
        @Override
        public long size(String path) {
            java.io.File root = repository.get();
            if (root == null || path == null || path.isEmpty()) {
                return -1;
            }
            java.io.File file = new java.io.File(root, path);
            return file.isFile() ? file.length() : -1;
        }
    }

    /**
     * Disabled with nothing to reveal from (same source the expander itself needs), or
     * in a mode the toggle has no meaning for: File view already shows the whole file
     * with no diff markers, and Blame has no hunks to reveal in the first place.
     */
    private void updateWholeFileControl() {
        boolean noMoreContext = !diff.canExpand() || mode == ViewMode.FILE || mode == ViewMode.BLAME;
        btnContext10.setDisable(noMoreContext);
        btnContextAll.setDisable(noMoreContext);
        // Hidden rather than merely disabled in the whole-file views: a row of greyed-out
        // controls that can never apply in the mode the user just chose is clutter, not
        // information. Wrap and Blame stay, being the only two that still mean something.
        if (diffOnlyTools != null) {
            show(diffOnlyTools, !isFileView());
            updateToolbarOverflow();
        }
    }


    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /**
     * Rows past which a diff is held back behind a confirmation.
     *
     * <p>A regenerated lockfile or a vendored bundle can be hundreds of thousands of
     * lines. Virtualization keeps scrolling cheap, but building the row list, lexing it
     * and styling it is linear work on the FX thread, and at that size it is long enough
     * to look like a hang.
     */
    private static final int LARGE_DIFF_ROWS = 20_000;

    /** Set once the user has asked for the current oversized diff anyway. */
    private boolean overrideLargeDiff = false;

    private void render() {
        updateEditControls();
        clearImageDiff();

        // Binary is not a decoding of the bytes but a refusal to decode them, so it
        // replaces the pane outright rather than feeding the diff or file view.
        if (encoding == TextEncoding.BINARY) {
            showSingleSurface();
            byte[] fileBytes = mode == ViewMode.FILE ? fileViewBytes() : currentBytes;
            byte[] dumped = fileBytes == null ? new byte[0] : fileBytes;
            renderRows(derive("hex", () -> DiffRows.plain(splitLines(TextEncoding.hexDump(dumped))),
                    dumped), GutterMode.NONE);
            placeholder.setText(fileBytes == null ? "File content is not available here."
                    : dumped.length == 0 ? "Select a file to view its bytes" : "");
            afterContentRendered();
            return;
        }

        // An LFS pointer's oid/size lines are genuine text to Git - never flagged
        // binary - so without this they render as if they were the file's real
        // content, or as a real (if tiny and meaningless) text diff of it.
        String lfsNotice = lfsNotice();
        if (lfsNotice != null) {
            showSingleSurface();
            setRows(LoadedDiff.Rows.EMPTY);
            area.setRows(List.of(), index -> false);
            placeholder.setText(lfsNotice);
            afterContentRendered();
            return;
        }

        // An image that Git calls binary is still a change worth seeing.
        if (mode != ViewMode.FILE && mode != ViewMode.BLAME && renderImageDiff()) {
            return;
        }

        switch (mode) {
            case BLAME -> {
                showSingleSurface();
                List<com.jfx.git.core.BlameLine> blamed = blameLines;
                renderRows(derive("blame", () -> DiffRows.plain(blamed.stream()
                        .map(com.jfx.git.core.BlameLine::text).toList()), blamed), GutterMode.BLAME);
                placeholder.setText(blameLines.isEmpty() ? "No blame information for this file." : "");
                afterContentRendered();
            }
            case FILE -> {
                showSingleSurface();
                if (editing()) {
                    // The buffer is the document now, not a picture of cached bytes.
                    // Re-filling it from fileContent() here is exactly what made pressing
                    // Wrap - or any other display toggle, since they all end up in
                    // render() - delete whatever had been typed and report the result as
                    // clean. Display settings apply to the surface in place instead.
                    applyDisplaySettingsWhileEditing();
                    afterContentRendered();
                } else {
                    String content = fileContent();
                    renderRows(content == null ? List.of()
                                    : derive("file", () -> DiffRows.fileContent(content), content),
                            GutterMode.NEW_ONLY);
                    placeholder.setText(content == null
                            ? "File content is not available here." : "");
                    afterContentRendered();
                }
            }
            case SPLIT -> renderSplit();
            case UNIFIED -> {
                showSingleSurface();
                // Kept from the last render when nothing it is built from has changed:
                // Wrap, whitespace markers and the other display toggles all re-render,
                // and each one rebuilt every row and re-ran the word diff over them.
                // Expanders, Whole file and the extra context are the loaded diff's to
                // offer: they need the file behind it, which it reads when first asked.
                LoadedDiff shown = diff;
                boolean whole = wholeFile;
                int extra = extraContext;
                Map<Integer, Integer> revealed = revealedAbove;
                LoadedDiff.Rows unified = derive("unified",
                        () -> shown.unified(whole, extra, revealed), shown, whole, extra, revealed);
                if (holdBackLargeDiff(unified.size())) {
                    afterContentRendered();
                    return;
                }
                setRows(unified);
                fill(area, rows, GutterMode.BOTH, inlineFor(rows));
                placeholder.setText(emptyDiffMessage());
                updateStickyHeader();
                afterContentRendered();
            }
        }
    }

    /**
     * What to say when there are no hunks.
     *
     * <p>Most of the time that means a rename, a chmod, a binary file or a submodule
     * pointer move - all real changes that produce no hunks. Reporting every one of them
     * as "no changes" is the thing that makes a viewer look broken.
     */
    /**
     * Refuses to render an enormous diff until the user asks for it.
     *
     * <p>Offering the choice is the point: the alternative is either a frozen window or
     * a viewer that silently refuses to show a file the user has every right to read.
     */
    private boolean holdBackLargeDiff(int rowCount) {
        if (overrideLargeDiff || rowCount <= LARGE_DIFF_ROWS) {
            showAnyway.setVisible(false);
            showAnyway.setManaged(false);
            return false;
        }
        setRows(LoadedDiff.Rows.EMPTY);
        area.setRows(List.of(), index -> false);
        placeholder.setText(String.format(
                "This diff is %,d lines. Rendering it will take a moment.", rowCount));
        showAnyway.setVisible(true);
        showAnyway.setManaged(true);
        return true;
    }

    /**
     * A notice for content that is, or (for a diff) was or has become, an LFS pointer
     * file - or {@code null} when nothing on screen is LFS-related, so normal
     * rendering should proceed untouched.
     *
     * <p>{@code BLAME} is deliberately not covered: {@link #blameLines} is a separate
     * annotated-source view with nothing that reduces to the diff's hunks or {@link
     * #fileContent()}, so there is nothing here for an LFS pointer to hide behind.
     */
    private String lfsNotice() {
        if (mode == ViewMode.FILE) {
            return LfsPointerParser.describeFile(LfsPointerParser.parse(fileContent()));
        }
        if (mode == ViewMode.UNIFIED || mode == ViewMode.SPLIT) {
            return LfsPointerParser.describeDiff(
                    LfsPointerParser.oldSide(diff.hunks()), LfsPointerParser.newSide(diff.hunks()));
        }
        return null;
    }

    /**
     * Draws the two revisions of an image, if that is what this is.
     *
     * @return true when the image view took over the pane
     */
    private boolean renderImageDiff() {
        String path = currentFilePath.get();
        if (imageSupplier == null || path.isEmpty() || !ImageDiffView.isImage(path)) {
            return false;
        }
        // Only when Git declined to diff it: a path that merely looks like an image but
        // produced a normal text diff should keep the text diff.
        if (!diff.hunks().isEmpty() || diff.text().isBlank() || !diff.summary().binary()) {
            return false;
        }

        // The supplier captures the image revisions fetched with this diff on the Git
        // worker. Display toggles may rebuild the view, but do not perform Git I/O; a
        // replacement supplier invalidates this cache when another request is accepted.
        if (!path.equals(imageViewPath) || imageViewBytes != currentBytes) {
            ImageDiffView.Blobs blobs = imageSupplier.apply(path);
            imageViewPath = path;
            imageViewBytes = currentBytes;
            // Null remembers "nothing to draw" as well, so that is not asked again either.
            imageView = blobs == null || (!blobs.hasBefore() && !blobs.hasAfter())
                    ? null : new ImageDiffView(path, blobs);
        }
        if (imageView == null) {
            return false;
        }

        showSingleSurface();
        area.setRows(List.of(), index -> false);
        setRows(LoadedDiff.Rows.EMPTY);
        placeholder.setText("");

        viewport.getChildren().removeIf(node -> node instanceof ImageDiffView);
        ImageDiffView view = imageView;
        StackPane.setAlignment(view, Pos.TOP_CENTER);
        viewport.getChildren().add(viewport.getChildren().indexOf(overlay), view);
        afterContentRendered();
        return true;
    }

    /** The image pane for {@link #imageViewPath} at {@link #imageViewBytes}; null when there was nothing to draw. */
    private ImageDiffView imageView;
    private String imageViewPath;
    /** The diff buffer the image pane was built for, compared by identity. */
    private byte[] imageViewBytes;

    /** Removes any image pane left from a previous file. */
    private void clearImageDiff() {
        viewport.getChildren().removeIf(node -> node instanceof ImageDiffView);
    }

    /** Drops a rendered pair when its supplier now represents another revision. */
    private void invalidateImageDiff() {
        clearImageDiff();
        imageView = null;
        imageViewPath = null;
        imageViewBytes = null;
    }

    private String emptyDiffMessage() {
        if (!diff.hunks().isEmpty()) {
            return "";
        }
        if (diff.text().isBlank()) {
            // Two different situations produce no diff text, and telling a user who has
            // just clicked a file to "select a file" is the wrong one of them. An empty
            // diff for a named path means git found nothing between the two sides it was
            // asked about - most often because the row was listed as unstaged but the
            // change has since been staged, which is what happens whenever the
            // repository is touched outside this window.
            String path = currentFilePath.get();
            if (path == null || path.isEmpty()) {
                return "Select a file to view its diff";
            }
            if (ignoreWhitespace) {
                // The likeliest reason by far, and the one thing on screen that says so
                // is a small toggle - so the message says it.
                return "No differences to show for " + path + " once whitespace is ignored.\n\n"
                        + "The change only moves spaces, tabs or line breaks. Turn off"
                        + " Ignore whitespace to see it.";
            }
            return "No differences to show for " + path + ".\n\n"
                    + "The change may already be staged, or the file may have changed"
                    + " outside Lyra. Press F5 to bring the file list up to date.";
        }
        DiffParser.Summary summary = diff.summary();
        if (summary.submoduleFrom() != null) {
            String to = summary.submoduleTo() == null ? "(removed)" : shortHash(summary.submoduleTo());
            return "Submodule moved from " + shortHash(summary.submoduleFrom()) + " to " + to;
        }
        String description = DiffParser.describe(summary);
        if (summary.binary()) {
            description += "\n\n" + currentBytes.length + " bytes of diff output."
                    + "\nChoose Binary from the encoding menu to inspect the bytes.";
        }
        return description;
    }

    private static String shortHash(String hash) {
        return hash.length() > 8 ? hash.substring(0, 8) : hash;
    }

    /** Whole-file text, or {@code null} when no source for it is available. */
    private String fileContent() {
        if (fileContentOverride != null) {
            return fileContentOverride;
        }
        String path = currentFilePath.get();
        if (fileContentSupplier == null || path.isEmpty()) {
            return null;
        }
        byte[] bytes = fileContentSupplier.apply(path);
        if (bytes == null) {
            return null;
        }
        // Read every time - the working tree can change under a view that is not
        // reloaded - but decoded once per content. A render in File view asks for this
        // three or four times (the LFS check, the rows, the footer, the preview), and a
        // display toggle re-renders; comparing bytes is a fraction of decoding them, and
        // handing back the same String is what lets the rows built from it be reused.
        if (path.equals(contentCachePath) && encoding == contentCacheEncoding
                && java.util.Arrays.equals(bytes, contentCacheBytes)) {
            return contentCacheText;
        }
        String text = encoding.decode(bytes);
        contentCachePath = path;
        contentCacheEncoding = encoding;
        contentCacheBytes = bytes;
        contentCacheText = text;
        return text;
    }

    /** Raw source for File view, independent of the bytes that hold the diff patch. */
    private byte[] fileViewBytes() {
        if (fileViewBytesOverride != null) {
            return fileViewBytesOverride;
        }
        if (!bytesAreDiff) {
            return currentBytes;
        }
        String path = currentFilePath.get();
        return fileContentSupplier == null || path.isEmpty()
                ? null : fileContentSupplier.apply(path);
    }

    /** The last working-tree read {@link #fileContent()} decoded, and what it decoded to. */
    private String contentCachePath;
    private TextEncoding contentCacheEncoding;
    private byte[] contentCacheBytes;
    private String contentCacheText;

    /** Points the viewport at the single area, building nothing else. */
    private void showSingleSurface() {
        if (splitPane != null) {
            contentRow.getChildren().remove(splitPane);
        }
        if (!contentRow.getChildren().contains(areaScroll)) {
            contentRow.getChildren().add(0, areaScroll);
        }
        splitSurfaceVisible = false;
        findBar.retarget();
    }

    /** Fills {@link #area} with {@code rows} and gives every paragraph its gutter. */
    /**
     * Re-applies the display-only settings to the live editing buffer.
     *
     * <p>Everything here is a property of the surface rather than of its contents, so
     * none of it disturbs the text, the caret, the selection or the undo history. The one
     * setting deliberately absent is whitespace markers: those are display substitutions
     * that would be written into the file, which is why {@link #beginEditing()} turns
     * them off and {@link #updateEditControls()} keeps the toggle disabled for the
     * duration.
     */
    private void applyDisplaySettingsWhileEditing() {
        area.setWrapText(wrapLines);
        placeholder.setText("");
    }

    private void renderRows(List<Row> rows, GutterMode gutterMode) {
        setRows(LoadedDiff.Rows.of(rows));
        fill(area, this.rows, gutterMode, Map.of());
    }

    /**
     * Makes {@code index} the rows on screen. The one way {@link #rows} changes, so the
     * index asked about rows by position is always the index of the rows shown.
     */
    private void setRows(LoadedDiff.Rows index) {
        this.rowIndex = index;
        this.rows = index.list();
    }

    /** The index of {@code surfaceRows}: one of the two kept, or one made for the asking. */
    private LoadedDiff.Rows indexOf(List<Row> surfaceRows) {
        if (surfaceRows == rowIndex.list()) {
            return rowIndex;
        }
        return surfaceRows == leftRowIndex.list() ? leftRowIndex : LoadedDiff.Rows.of(surfaceRows);
    }

    private void fill(DiffTextArea target, List<Row> rows, GutterMode gutterMode,
                      Map<Integer, List<InlineDiff.Segment>> inline) {
        measureGutter(rows);
        target.setWrapText(wrapLines);
        // Whatever the lane is still colouring for this surface describes the text about
        // to be replaced.
        long generation = nextStyleGeneration(target);
        boolean colour = encoding != TextEncoding.BINARY;
        String path = colour && syntaxEnabled ? currentFilePath.get() : null;

        // Colours already known go into the document itself: the ones this surface showed
        // for these very rows (a display toggle re-renders unchanged rows), or, with the
        // lane synchronous, the whole file lexed here and now.
        SyntaxResult known = colour ? rememberedSyntax(target, rows, path) : null;
        boolean lexFailed = false;
        if (known == null && colour && HighlightLane.isSynchronous()) {
            try {
                known = lexRows(highlighter, path, rows, true);
                rememberSyntax(target, rows, path, known);
            } catch (RuntimeException e) {
                // As the lane does: colours are a nicety, and the text goes up without them.
                java.util.logging.Logger.getLogger(DiffViewer.class.getName())
                        .log(java.util.logging.Level.WARNING, "Syntax highlighting failed", e);
                lexFailed = true;
            }
        }
        List<List<DiffTextArea.StyleRange>> syntax = known == null ? null : known.ranges();

        // One document edit for the text, every paragraph style and every character
        // style; see DiffTextArea.setContent.
        target.setContent(rows.stream().map(Row::text).toList(),
                // Markers belong on content, not on the "@@" scaffolding around it.
                index -> showWhitespace && index < rows.size() && rows.get(index).kind() == Kind.CODE,
                index -> rows.get(index).styleClass(),
                index -> layered(rows.get(index), inline.get(index),
                        syntax != null && index < syntax.size() ? syntax.get(index) : null));
        target.setParagraphGraphicFactory(index -> buildGutter(rows, index, gutterMode));
        if (target == area) {
            // The colours just landed on the surface the minimap is a picture of.
            minimap.markDirty();
        }

        if (!colour) {
            // A hex dump of a .java file is not Java.
            showSyntaxNotice(SyntaxResult.NONE);
        } else if (known != null) {
            showSyntaxNotice(known);
        } else if (!lexFailed) {
            colourInChunks(target, rows, inline, path, generation);
        }
    }

    /**
     * Every style layer of one row, in the order they stack: syntax colours the text, and
     * the word diff tints behind it. The paragraph's own background, which says the line
     * changed, is its paragraph style. Keeping them in separate CSS namespaces is what
     * lets all three show at once instead of the last one applied winning.
     */
    private static List<DiffTextArea.StyleRange> layered(Row row, List<InlineDiff.Segment> segments,
                                                         List<DiffTextArea.StyleRange> syntax) {
        if (segments == null || segments.isEmpty()) {
            return syntax == null ? List.of() : syntax;
        }
        List<DiffTextArea.StyleRange> ranges = new ArrayList<>(
                (syntax == null ? 0 : syntax.size()) + segments.size());
        if (syntax != null) {
            ranges.addAll(syntax);
        }
        String style = row.isAdd() ? "diff-inline-add" : "diff-inline-delete";
        for (InlineDiff.Segment segment : segments) {
            ranges.add(new DiffTextArea.StyleRange(segment.start(), segment.end(), style));
        }
        return ranges;
    }

    // ------------------------------------------------------------------
    // Syntax colours, a screenful first
    // ------------------------------------------------------------------

    /** Rows lexed and handed over per delivery once the first screen is coloured. */
    static final int CHUNK_ROWS = 1000;
    /** The first delivery: enough to cover a tall viewport at the top of a file, and quick. */
    static final int FIRST_CHUNK_ROWS = 250;

    /**
     * One delivery from the lane: the language (every delivery carries it, so the first
     * one can name it), a refusal, or a run of rows' colours.
     *
     * @param provisional lexed from a fresh state rather than from the rows above, so
     *                    right unless a comment or string opened above is still open;
     *                    the in-order chunk covering the same rows replaces it
     * @param last        nothing more follows
     */
    private record SyntaxChunk(String language, String refusal, int from,
                               List<List<DiffTextArea.StyleRange>> ranges,
                               boolean provisional, boolean last) {
    }

    /**
     * Colours {@code rows} from the lane: the rows on screen first, then the whole
     * document in order, a chunk at a time, each chunk one restyle of its run of rows.
     *
     * <p>The lexer's state has to run from the top of the file, so the screen a reader
     * opened at line 40,000 cannot be coloured exactly before the 40,000 lines above it
     * are lexed. It is coloured from a fresh state at once - right for everything but the
     * inside of a construct opened above the screen - and corrected when the in-order
     * pass reaches it. Delivered a chunk at a time, the first colours of a long file
     * arrive in milliseconds where they used to wait for its last line.
     *
     * <p>Every delivery is checked against the surface's generation, so colours for text
     * that has since been replaced are dropped, as before.
     */
    private void colourInChunks(DiffTextArea target, List<Row> rows,
                                Map<Integer, List<InlineDiff.Segment>> inline,
                                String path, long generation) {
        int total = rows.size();
        // Where the viewport is now: for a re-render of the same file, where the reader is.
        // Read without forcing a layout, so before the first one this is simply the top.
        int firstVisible = Math.max(0, Math.min(total - 1, (int) target.firstVisibleRow()));
        int visibleRows = (int) Math.ceil(Math.max(1, target.getHeight())
                / Math.max(1, target.rowHeight())) + 2;
        boolean keep = total <= SYNTAX_MEMO_MAX_ROWS;
        List<List<DiffTextArea.StyleRange>> assembled = keep ? new ArrayList<>(total) : null;
        HighlightLane.<SyntaxChunk>stream(target,
                sink -> lexInChunks(highlighter, path, rows, firstVisible, visibleRows, sink),
                chunk -> {
                    if (!isCurrentStyleGeneration(target, generation)) {
                        return;
                    }
                    if (target == area && editing()) {
                        // An editing session began after this was asked for - beginEditing
                        // renders first - and the user may already be typing. These colours
                        // describe the text as it was loaded, through the area's load-time
                        // mapping; applied to a buffer that has since changed they land on
                        // the wrong characters, or on paragraphs that no longer exist.
                        // Colour what is there now instead (which also retires this run).
                        reapplyHighlightingWhileEditing();
                        return;
                    }
                    showSyntaxNotice(new SyntaxResult(chunk.language(), chunk.refusal(), null));
                    if (chunk.ranges() != null) {
                        List<List<DiffTextArea.StyleRange>> styled = new ArrayList<>(chunk.ranges().size());
                        for (int k = 0; k < chunk.ranges().size(); k++) {
                            int row = chunk.from() + k;
                            styled.add(layered(rows.get(row), inline.get(row), chunk.ranges().get(k)));
                        }
                        target.restyleRows(chunk.from(), styled);
                        if (target == area) {
                            minimap.markDirty();
                        }
                        if (assembled != null && !chunk.provisional()) {
                            assembled.addAll(chunk.ranges());
                        }
                    }
                    if (chunk.last() && assembled != null) {
                        rememberSyntax(target, rows, path, new SyntaxResult(chunk.language(), chunk.refusal(),
                                chunk.language() == null || chunk.refusal() != null ? null : assembled));
                    }
                });
    }

    /** The lane's half of {@link #colourInChunks}. */
    private static void lexInChunks(Highlighter highlighter, String path, List<Row> rows,
                                    int firstVisible, int visibleRows,
                                    HighlightLane.Sink<SyntaxChunk> sink) {
        ScopedTokenizer tokenizer = path == null ? null : Highlighter.tokenizerForPath(path);
        if (tokenizer == null) {
            sink.publish(new SyntaxChunk(null, null, 0, null, false, true));
            return;
        }
        String language = tokenizer.language();
        String refusal = Highlighter.refusalForChunked(rows.stream().map(Row::text).toList());
        if (refusal != null || rows.isEmpty()) {
            sink.publish(new SyntaxChunk(language, refusal, 0, null, false, true));
            return;
        }
        int total = rows.size();
        if (firstVisible + visibleRows > FIRST_CHUNK_ROWS) {
            int end = Math.min(total, firstVisible + visibleRows);
            List<List<DiffTextArea.StyleRange>> screen = new ArrayList<>(end - firstVisible);
            lexRange(highlighter, tokenizer, rows, firstVisible, end, tokenizer.initialState(), screen);
            sink.publish(new SyntaxChunk(language, null, firstVisible, screen, true, false));
        }
        Object state = tokenizer.initialState();
        int from = 0;
        while (from < total) {
            if (sink.superseded()) {
                return;
            }
            int end = Math.min(total, from + (from == 0 ? FIRST_CHUNK_ROWS : CHUNK_ROWS));
            List<List<DiffTextArea.StyleRange>> chunk = new ArrayList<>(end - from);
            state = lexRange(highlighter, tokenizer, rows, from, end, state, chunk);
            sink.publish(new SyntaxChunk(language, null, from, chunk, false, end == total));
            from = end;
        }
    }

    /**
     * The colours remembered per surface for the rows it last showed, so a re-render of
     * the same rows - a display toggle - puts them straight into the document instead of
     * lexing and restyling again. Bounded by {@link #SYNTAX_MEMO_MAX_ROWS}: past that the
     * per-row lists cost more memory than the re-lex costs time, and the lexer's own line
     * cache answers most of it anyway.
     */
    private final Map<DiffTextArea, SyntaxMemo> syntaxMemo = new java.util.HashMap<>();

    private record SyntaxMemo(List<Row> rows, String path, SyntaxResult result) {
    }

    static final int SYNTAX_MEMO_MAX_ROWS = 20_000;

    private SyntaxResult rememberedSyntax(DiffTextArea target, List<Row> rows, String path) {
        SyntaxMemo memo = syntaxMemo.get(target);
        return memo != null && memo.rows() == rows && java.util.Objects.equals(memo.path(), path)
                ? memo.result() : null;
    }

    private void rememberSyntax(DiffTextArea target, List<Row> rows, String path, SyntaxResult result) {
        if (rows.size() <= SYNTAX_MEMO_MAX_ROWS) {
            syntaxMemo.put(target, new SyntaxMemo(rows, path, result));
        } else {
            syntaxMemo.remove(target);
        }
    }

    /** Key for the generation counter each surface carries in its properties. */
    private static final Object STYLE_GENERATION = new Object();

    /** Marks {@code target} as redrawn, so answers asked for before now are stale. */
    private static long nextStyleGeneration(DiffTextArea target) {
        Object last = target.getProperties().get(STYLE_GENERATION);
        long next = last instanceof Long value ? value + 1 : 1;
        target.getProperties().put(STYLE_GENERATION, next);
        return next;
    }

    private static boolean isCurrentStyleGeneration(DiffTextArea target, long generation) {
        return Long.valueOf(generation).equals(target.getProperties().get(STYLE_GENERATION));
    }

    /**
     * What the lane found: the language, why it declined, and the colours.
     *
     * @param language the grammar's display name, or null when the path has none
     * @param refusal  why nothing was coloured although a grammar exists, or null
     * @param ranges   per-row colours, or null for none
     */
    private record SyntaxResult(String language, String refusal,
                                List<List<DiffTextArea.StyleRange>> ranges) {
        static final SyntaxResult NONE = new SyntaxResult(null, null, null);
    }

    private void showSyntaxNotice(SyntaxResult result) {
        if (result.language() == null) {
            syntaxNotice.setText("");
            show(syntaxNotice, false);
            return;
        }
        syntaxNotice.setText(result.refusal() != null
                ? "syntax off · " + result.refusal()
                : result.language() + " · TextMate");
        show(syntaxNotice, true);
    }

    /**
     * Colours for {@code rows}, all at once.
     *
     * <p>The lexer's state runs from one code row to the next and starts again at every
     * header and expander, where the file's own continuity breaks. A line past
     * {@link Highlighter#MAX_LINE_LENGTH} is left plain and the state restarts after it.
     *
     * @param path    null for no highlighting at all
     * @param chunked whether to apply the bounds of a chunked colouring (the render's,
     *                however it is delivered) or the at-once ones (the editing re-lex,
     *                which recolours the whole buffer after every pause in typing)
     */
    private static SyntaxResult lexRows(Highlighter highlighter, String path, List<Row> rows,
                                        boolean chunked) {
        ScopedTokenizer tokenizer = path == null ? null : Highlighter.tokenizerForPath(path);
        if (tokenizer == null) {
            return SyntaxResult.NONE;
        }
        List<String> texts = rows.stream().map(Row::text).toList();
        String refusal = chunked ? Highlighter.refusalForChunked(texts) : Highlighter.refusal(texts);
        if (refusal != null) {
            return new SyntaxResult(tokenizer.language(), refusal, null);
        }
        List<List<DiffTextArea.StyleRange>> ranges = new ArrayList<>(rows.size());
        lexRange(highlighter, tokenizer, rows, 0, rows.size(), tokenizer.initialState(), ranges);
        return new SyntaxResult(tokenizer.language(), null, ranges);
    }

    /**
     * Lexes rows {@code from} to {@code to - 1} starting in {@code state}, adding one list
     * per row to {@code into}.
     *
     * @return the state after the last row, for the run that continues from it
     */
    private static Object lexRange(Highlighter highlighter, ScopedTokenizer tokenizer, List<Row> rows,
                                   int from, int to, Object state,
                                   List<List<DiffTextArea.StyleRange>> into) {
        for (int i = from; i < to; i++) {
            Row row = rows.get(i);
            switch (row.kind()) {
                case CODE -> {
                    if (!Highlighter.lexable(row.text())) {
                        into.add(List.of());
                        state = tokenizer.initialState();
                        continue;
                    }
                    ScopedTokenizer.Lexed lexed = highlighter.lexScoped(tokenizer, row.text(), state);
                    List<DiffTextArea.StyleRange> rowRanges = new ArrayList<>();
                    for (ScopedTokenizer.Span span : lexed.spans()) {
                        for (String styleClass : span.styleClasses()) {
                            rowRanges.add(new DiffTextArea.StyleRange(
                                    span.start(), span.end(), styleClass));
                        }
                    }
                    into.add(rowRanges);
                    state = lexed.stateOut();
                }
                case HUNK_HEADER, FILE_HEADER, EXPANDER -> {
                    into.add(List.of());
                    state = tokenizer.initialState();
                }
                default -> into.add(List.of());
            }
        }
        return state;
    }

    /**
     * The colours applied to each paragraph while editing, so a re-lex can repaint only
     * the paragraphs whose colours changed.
     */
    private List<List<DiffTextArea.StyleRange>> editingColours = List.of();

    /**
     * Past this many changed paragraphs a re-lex is applied as one restyle of the run
     * they span rather than one edit per paragraph: the first re-lex of a session
     * repaints every paragraph, and a refusal clears every one.
     */
    private static final int EDITING_RESTYLE_IN_ONE = 32;

    /**
     * Re-colours the buffer from its current text while the user is typing, without
     * touching the text itself.
     *
     * <p>The lexing runs on the highlighting lane, and the lexer cache answers every line
     * whose text and incoming state are unchanged, so what is actually re-lexed is the
     * edited line and whatever its change carries on into - an unclosed comment, say.
     * Only paragraphs whose colours differ from what is on screen are repainted. The
     * answer is dropped if the buffer has changed again since it was asked for.
     *
     * <p>Applied in the buffer's own offsets: there are no whitespace markers while
     * editing, and the area's display mapping describes the text as it was loaded, not as
     * it has since been typed.
     */
    private void reapplyHighlightingWhileEditing() {
        String text = area.getText();
        List<Row> rows = DiffRows.fileContent(text);
        long generation = nextStyleGeneration(area);
        String path = syntaxEnabled ? currentFilePath.get() : null;
        HighlightLane.submit(area, () -> lexRows(highlighter, path, rows, false), result -> {
            if (!isCurrentStyleGeneration(area, generation) || !editing()
                    || !text.equals(area.getText())) {
                return;
            }
            showSyntaxNotice(result);
            List<List<DiffTextArea.StyleRange>> colours = result.ranges() == null
                    ? java.util.Collections.nCopies(rows.size(), List.of()) : result.ranges();
            int paragraphs = Math.min(area.getParagraphs().size(), colours.size());
            List<Integer> changed = new ArrayList<>();
            for (int i = 0; i < paragraphs; i++) {
                if (i < editingColours.size() && editingColours.get(i).equals(colours.get(i))) {
                    continue;
                }
                changed.add(i);
            }
            if (changed.size() > EDITING_RESTYLE_IN_ONE) {
                int first = changed.get(0);
                int last = changed.get(changed.size() - 1);
                area.restyleParagraphs(first, colours.subList(first, last + 1));
            } else {
                for (int i : changed) {
                    CodeStyler.apply(area, i, colours.get(i));
                }
            }
            editingColours = colours;
            minimap.markDirty();
        });
    }

    /** Cut/Copy/Paste/Select All, since RichTextFX supplies none of these by default. */
    private ContextMenu buildEditContextMenu() {
        MenuItem cut = new MenuItem("Cut");
        MenuItem copy = new MenuItem("Copy");
        MenuItem paste = new MenuItem("Paste");
        MenuItem selectAll = new MenuItem("Select All");
        MenuItem save = new MenuItem("Save");
        MenuItem saveAndStage = new MenuItem("Save and stage");

        cut.setOnAction(e -> area.cut());
        // area.copy(), not a default action: it is overridden to copy the real source
        // text rather than the whitespace-marker glyphs shown on screen.
        copy.setOnAction(e -> area.copy());
        paste.setOnAction(e -> area.paste());
        selectAll.setOnAction(e -> area.selectAll());
        save.setOnAction(e -> saveEdits());
        saveAndStage.setOnAction(e -> saveAndStage());

        ContextMenu menu = new ContextMenu(cut, copy, paste, new SeparatorMenuItem(),
                save, saveAndStage, new SeparatorMenuItem(), selectAll);
        menu.setOnShowing(e -> {
            boolean hasSelection = !area.getSelectedText().isEmpty();
            cut.setDisable(!area.isEditable() || !hasSelection);
            copy.setDisable(!hasSelection);
            paste.setDisable(!area.isEditable() || !Clipboard.getSystemClipboard().hasString());
            selectAll.setDisable(area.getLength() == 0);
            save.setVisible(editing());
            saveAndStage.setVisible(editing() && session.canSaveAndStage());
        });
        return menu;
    }


    /**
     * Swaps red/green for blue/amber.
     *
     * <p>Red against green is the single most common way a diff fails a reader: roughly
     * one man in twelve cannot separate the two reliably. Blue and amber differ in hue
     * <em>and</em> in lightness, so they survive every common form of colour blindness
     * and greyscale printing besides.
     */
    public void setColorblindPalette(boolean enabled) {
        getStyleClass().remove("diff-palette-cb");
        if (enabled) {
            getStyleClass().add("diff-palette-cb");
        }
        // The minimap's tints and text colours are read off this palette.
        minimap.invalidateColours();
        minimap.markDirty();
    }

    public boolean isColorblindPalette() {
        return getStyleClass().contains("diff-palette-cb");
    }

    /**
     * Adopts the saved diff preferences.
     *
     * <p>Called at construction and again whenever the preferences dialog changes
     * something, so a setting takes effect on the diff already on screen rather than
     * only on the next one opened.
     */
    public void applyPreferences() {
        setColorblindPalette(com.jfx.git.core.AppPreferences.colourblindPalette());

        ViewMode preferred = com.jfx.git.core.AppPreferences.splitDiffByDefault()
                ? ViewMode.SPLIT
                : ViewMode.UNIFIED;
        if (diffLayout != preferred) {
            diffLayout = preferred;
            // Selecting the toggle re-renders; doing it directly would leave the
            // segmented control showing the old choice.
            if (preferred == ViewMode.SPLIT) {
                btnSplit.setSelected(true);
            } else {
                btnUnified.setSelected(true);
            }
        }

        boolean preferredWholeFile = com.jfx.git.core.AppPreferences.wholeFileByDefault();
        if (wholeFile != preferredWholeFile) {
            // Through the group, whose listener applies the choice and re-renders.
            (preferredWholeFile ? btnContextAll : btnContext3).setSelected(true);
        }

        scrollMap.setStripWidth(com.jfx.git.core.AppPreferences.diffScrollMapWidth());

        boolean scrollBeyond = com.jfx.git.core.AppPreferences.scrollBeyondLastLine();
        area.setScrollBeyondLastLine(scrollBeyond);
        if (leftArea != null) {
            leftArea.setScrollBeyondLastLine(scrollBeyond);
            rightArea.setScrollBeyondLastLine(scrollBeyond);
        }

        minimapEnabled = com.jfx.git.core.AppPreferences.editorMinimap();
        updateMinimap();
    }

    /**
     * Tightens or loosens row spacing.
     *
     * <p>Worth offering now that row height comes from the content rather than from a
     * constant: how much of a diff fits on screen is a real trade against readability,
     * and which side of it you want depends on the display.
     */
    public void setCompactDensity(boolean compact) {
        getStyleClass().remove("diff-compact");
        if (compact) {
            getStyleClass().add("diff-compact");
        }
    }

    /** Turns syntax colouring off, leaving the diff's own colouring in place. */
    public void setSyntaxHighlightingEnabled(boolean enabled) {
        this.syntaxEnabled = enabled;
        render();
    }

    /**
     * Word-level highlighting within changed lines.
     *
     * <p>Held as a switch because it is the one part of the render path that guesses:
     * it decides which removed line replaced which added one. When that guess is wrong
     * the highlight is noise, and turning it off leaves plain line colouring behind.
     */
    public void setInlineDiffEnabled(boolean enabled) {
        this.inlineDiffEnabled = enabled;
        render();
    }

    private Map<Integer, List<InlineDiff.Segment>> inlineFor(List<Row> rows) {
        return inlineDiffEnabled ? derive("inline", () -> InlineDiff.forUnified(rows), rows) : Map.of();
    }

    // ------------------------------------------------------------------
    // What a render derives, kept for the next one
    // ------------------------------------------------------------------

    /** One remembered derivation: what it was computed from, and the answer. */
    private record Derived(Object[] key, Object value) {
    }

    /**
     * The last answer per kind of derivation - rows, the word diff, the hex dump. One
     * entry each, so the whole cache is a handful of references to things the screen is
     * already showing; dropped with the file (see {@link #forgetDerived()}).
     */
    private final Map<String, Derived> derived = new java.util.HashMap<>();

    /**
     * {@code compute}'s answer, or the one remembered for the same {@code key}.
     *
     * <p>Key parts are compared by identity - a list, a map, a byte buffer is the same
     * input only if it is the same object, which is exactly what a re-render of unchanged
     * content hands back - except values (strings, numbers, booleans), compared by value.
     */
    @SuppressWarnings("unchecked")
    private <T> T derive(String kind, java.util.function.Supplier<T> compute, Object... key) {
        Derived last = derived.get(kind);
        if (last != null && sameKey(last.key(), key)) {
            return (T) last.value();
        }
        T value = compute.get();
        derived.put(kind, new Derived(key, value));
        return value;
    }

    private static boolean sameKey(Object[] a, Object[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            Object x = a[i];
            Object y = b[i];
            if (x == y) {
                continue;
            }
            boolean value = x instanceof String || x instanceof Number || x instanceof Boolean
                    || x instanceof Enum<?>;
            if (!value || !x.equals(y)) {
                return false;
            }
        }
        return true;
    }

    /** Drops everything derived from the file that was on screen. */
    private void forgetDerived() {
        derived.clear();
        syntaxMemo.clear();
        contentCachePath = null;
        contentCacheBytes = null;
        contentCacheText = null;
        imageViewPath = null;
        imageViewBytes = null;
        imageView = null;
        ImageDiffView.clearCache();
        stickyFor = null;
        if (markdownView != null) {
            markdownView.forgetSource();
        }
    }

    /**
     * Builds the two-column view.
     *
     * <p>Each column is its own virtualized surface, so both stay cheap on a long file;
     * their scroll positions are tied together so the rows opposite each other are the
     * ones the reader is comparing.
     */
    private void renderSplit() {
        if (splitPane == null) {
            leftArea = new DiffTextArea();
            rightArea = new DiffTextArea();
            // Both columns, or the rows stop lining up at the end of the file.
            boolean scrollBeyond = com.jfx.git.core.AppPreferences.scrollBeyondLastLine();
            leftArea.setScrollBeyondLastLine(scrollBeyond);
            rightArea.setScrollBeyondLastLine(scrollBeyond);

            DiffScrollPane leftScroll = new DiffScrollPane(leftArea);
            rightScroll = new DiffScrollPane(rightArea);
            // One scrollbar for two columns that always move together: the scroll map
            // beside the right column. Neither pane has a vertical bar of its own.
            // Each column carries the name of the version it shows, for as long as it is
            // on screen: scrolled past the first hunk header, nothing else says which
            // side is which.
            leftColumnHeader.getStyleClass().add("diff-split-header");
            rightColumnHeader.getStyleClass().add("diff-split-header");
            leftColumnHeader.setMaxWidth(Double.MAX_VALUE);
            rightColumnHeader.setMaxWidth(Double.MAX_VALUE);
            javafx.scene.layout.VBox leftColumn =
                    new javafx.scene.layout.VBox(leftColumnHeader, leftScroll);
            javafx.scene.layout.VBox rightColumn =
                    new javafx.scene.layout.VBox(rightColumnHeader, rightScroll);
            javafx.scene.layout.VBox.setVgrow(leftScroll, Priority.ALWAYS);
            javafx.scene.layout.VBox.setVgrow(rightScroll, Priority.ALWAYS);
            HBox.setHgrow(leftColumn, Priority.ALWAYS);
            HBox.setHgrow(rightColumn, Priority.ALWAYS);
            // Equal halves: without a shared preference the wider column's content would
            // claim more of the row.
            leftColumn.setPrefWidth(0);
            rightColumn.setPrefWidth(0);
            leftColumn.setMaxWidth(Double.MAX_VALUE);
            rightColumn.setMaxWidth(Double.MAX_VALUE);

            bindScroll(leftArea, rightArea);
            bindScroll(rightArea, leftArea);

            // Split places its bars against the right-hand column, so that is the surface
            // whose scrolling they follow. Selection is a different matter: a deletion can
            // only be selected on the left, so both columns feed the staging buttons.
            rightArea.estimatedScrollYProperty().addListener((obs, old, value) -> scheduleScrollFollow());
            rightArea.selectionProperty().addListener((obs, old, value) -> layoutHunkActions());
            leftArea.selectionProperty().addListener((obs, old, value) -> layoutHunkActions());

            HBox columns = new HBox(1, leftColumn, rightColumn);
            columns.getStyleClass().add("diff-split");
            // The row this goes into gives its space to whichever child asks to grow, and
            // areaScroll - the surface split replaces - asks. Without the same request
            // here, split view was laid out at its preferred width: two columns squeezed
            // to a strip against the left edge with the rest of the row left empty, which
            // looks exactly like a viewer that failed to render anything.
            HBox.setHgrow(columns, Priority.ALWAYS);
            columns.setMaxWidth(Double.MAX_VALUE);
            splitPane = columns;
        }

        LoadedDiff shown = diff;
        boolean whole = wholeFile;
        int extra = extraContext;
        LoadedDiff.SplitRows split = derive("split", () -> shown.split(whole, extra), shown, whole, extra);
        // Split never guarded against an oversized diff the way Unified does; "whole
        // file" is what turns that from theoretical (hunks are small) to likely (a
        // fully revealed large file can be tens of thousands of rows in each column).
        if (holdBackLargeDiff(Math.max(split.left().size(), split.right().size()))) {
            showSingleSurface();
            afterContentRendered();
            return;
        }

        contentRow.getChildren().remove(areaScroll);
        if (!contentRow.getChildren().contains(splitPane)) {
            contentRow.getChildren().add(0, splitPane);
        }
        splitSurfaceVisible = true;
        findBar.retarget();

        leftRowIndex = split.left();
        leftRows = split.left().list();
        setRows(split.right());
        Map<Integer, List<InlineDiff.Segment>>[] inline = inlineDiffEnabled
                ? derive("inlineSplit", () -> InlineDiff.forSplit(leftRows, rows), split)
                : null;
        fill(leftArea, leftRows, GutterMode.OLD_ONLY, inline == null ? Map.of() : inline[0]);
        fill(rightArea, rows, GutterMode.NEW_ONLY, inline == null ? Map.of() : inline[1]);
        placeholder.setText(emptyDiffMessage());
        afterContentRendered();
    }

    /**
     * Everything that has to be re-derived once the surface's content has changed.
     *
     * <p>Both of these depend on the rows now on screen, and both were previously easy
     * to forget at one of the render paths' many exits; keeping them together means a
     * new path only has to remember one call.
     */
    private void afterContentRendered() {
        // New content: whatever step was in flight is over.
        pendingJumpRow = -1;
        updateDocumentStatus();
        updateScrollMap();
        layoutHunkActions();
        // Fewer than two hunks and there is nowhere to step to: one hunk is already where
        // the view opens, and none means the buttons address nothing at all.
        boolean canStep = diff.hunks().size() > 1 && !isFileView();
        hunkNavButtons.forEach(button -> button.setDisable(!canStep));
        updateChangeCounter();
        updateSidesLabel();
        show(whitespaceIgnoredChip, ignoreWhitespace && !isFileView()
                && !currentFilePath.get().isEmpty());
        updatePreview();
        updateMinimap();
    }

    /**
     * The minimap is a File view thing: a diff's rows are hunks with gaps between them,
     * and a blame's are mostly its own gutter, so neither reads as a picture of a file.
     * Off too while the Markdown preview stands in for the source.
     */
    private void updateMinimap() {
        boolean wanted = minimapEnabled && mode == ViewMode.FILE && !splitSurfaceVisible && !isPreviewing();
        show(minimap, wanted);
        if (wanted) {
            minimap.markDirty();
        }
    }

    /** The minimap, for tests. */
    Minimap minimap() {
        return minimap;
    }

    // ------------------------------------------------------------------
    // Markdown preview
    // ------------------------------------------------------------------

    /** Whether the file on screen is one the preview is offered for, in whichever view. */
    private boolean previewAvailable() {
        return !loading && encoding != TextEncoding.BINARY && MarkdownView.isMarkdown(currentFilePath.get());
    }

    /** True while the rendered Markdown is showing in place of the source. */
    public boolean isPreviewing() {
        return markdownView != null && markdownView.isVisible();
    }

    /**
     * Shows the Preview segment where it applies, and the page or the source as asked.
     * The choice is kept from file to file - a reader going through the docs folder wants
     * each one rendered - but means nothing for a file that is not Markdown.
     */
    private void updatePreview() {
        boolean available = previewAvailable();
        show(btnPreview, available);
        boolean wanted = available && previewWanted && mode == ViewMode.FILE;
        // The switch says what is on screen. Preview is selected only while the page is
        // showing; otherwise whichever of the other two the mode is.
        updatingToggle = true;
        if (wanted) {
            btnPreview.setSelected(true);
        } else if (btnPreview.isSelected()) {
            (isFileView() ? btnFileView : btnDiffView).setSelected(true);
        }
        updatingToggle = false;
        if (wanted && markdownView == null) {
            markdownView = new MarkdownView();
            viewport.getChildren().add(viewport.getChildren().indexOf(contentRow) + 1, markdownView);
        }
        if (markdownView == null) {
            return;
        }
        boolean was = isPreviewing();
        show(markdownView, wanted);
        contentRow.setVisible(!wanted);
        overlay.setVisible(!wanted);
        if (wanted) {
            String source = editing() ? area.getText() : fileContent();
            markdownView.setImageSource(readerRepository, imageReader());
            markdownView.render(source == null ? "" : source, currentFilePath.get());
        }
        if (was != wanted) {
            // An open search follows what is on screen: the page, or the source again.
            findBar.retarget();
        }
    }

    /** The preview, for tests; {@code null} until one has been shown. */
    MarkdownView markdownView() {
        return markdownView;
    }

    /** The preview toggle, for tests. */
    ToggleButton previewToggle() {
        return btnPreview;
    }

    /** Points the scrollbar diff-map at whichever surface is actually visible. */
    private void updateScrollMap() {
        if (splitSurfaceVisible) {
            scrollMap.attachTo(rightArea);
            scrollMap.setRows(rows, leftRows);
            minimap.setChangedRows(Set.of(), Set.of());
            return;
        }
        scrollMap.attachTo(area);
        // File and blame views render the whole file as plain context, so their rows
        // carry no +/- for the map to read - it has to be told where the changes are. The
        // diff is there only when it describes what is shown: File view of its newer side,
        // or the blamed commit's.
        if (isFileView() && !diff.hunks().isEmpty() && !rows.isEmpty()) {
            LoadedDiff.Marks marks = diff.marks(rows.size());
            scrollMap.setMarkedRows(marks.additions(), marks.deletions(), rows.size());
            // The same rows, tinted on the minimap.
            minimap.setChangedRows(marks.additions(), marks.deletions());
        } else {
            scrollMap.setRows(rows);
            minimap.setChangedRows(Set.of(), Set.of());
        }
    }

    /** Makes {@code follower} track {@code driver}'s vertical scroll position. */
    /**
     * Keeps the follower on the same rows as the driver.
     *
     * <p>By row, not by pixel. The columns hold the same number of rows, but with Wrap on
     * a long line wraps on one side only, so the same row sits at a different height in
     * each column and a pixel offset drifts further out of step the further down the
     * file it goes - a deletion ends up beside some unrelated line.
     */
    private void bindScroll(DiffTextArea driver, DiffTextArea follower) {
        driver.estimatedScrollYProperty().addListener((obs, old, value) -> {
            if (syncingScroll || value == null) {
                return;
            }
            if (wrapLines) {
                // After the pass, not inside it: this fires while the driver is still
                // laying its cells out, and a row's height read then is not where the
                // row ends up. It used to come right by accident - the old scroll pane's
                // bar wrote the position back and ran this again, settled.
                scheduleAlign(driver, follower);
                return;
            }
            syncingScroll = true;
            try {
                // Unwrapped, every row is one line high in both columns, so the same
                // pixel is the same row - and the plain scroll is the one Flowless
                // never has to resolve against a cell list that is still changing.
                follower.scrollYToPixel(value);
            } catch (RuntimeException notLaidOutYet) {
                // Flowless answers "which cell is where" by laying out, and a layout run
                // while its cell list is still catching up with replaced text asks for a
                // cell that does not exist yet. The next scroll lines the columns up.
            } finally {
                syncingScroll = false;
            }
        });
    }

    /** Whether a wrapped-split alignment is already queued for the end of this pulse. */
    private boolean alignScheduled;

    /** Aligns {@code follower} to {@code driver} once the current layout pass is over; one per pulse. */
    private void scheduleAlign(DiffTextArea driver, DiffTextArea follower) {
        if (alignScheduled) {
            return;
        }
        alignScheduled = true;
        javafx.application.Platform.runLater(() -> {
            alignScheduled = false;
            syncingScroll = true;
            try {
                alignRows(driver, follower);
            } catch (RuntimeException notLaidOutYet) {
                // The next scroll lines the columns up.
            } finally {
                syncingScroll = false;
            }
        });
    }

    /**
     * Puts the follower's copy of the driver's second visible row at the same height.
     *
     * <p>The second, because the first is usually cut off at the top, and RichTextFX
     * reports a cut-off paragraph's bounds clipped to the viewport - its real top cannot
     * be read. The second row's top is exact, and placing it at the same distance from
     * the viewport's top lines every row above and below it up too.
     */
    static void alignRows(DiffTextArea driver, DiffTextArea follower) {
        // Mid-render one column has its new rows and the other still has the old ones;
        // there is no row to line up until both are filled, and the fill ends with a
        // scroll of its own that syncs them then.
        if (driver.getParagraphs().size() != follower.getParagraphs().size()) {
            return;
        }
        int anchor;
        double fromTop;
        try {
            int first = driver.firstVisibleParToAllParIndex();
            anchor = first + 1;
            if (anchor >= driver.getParagraphs().size()
                    || anchor >= follower.getParagraphs().size()) {
                follower.scrollYToPixel(driver.getEstimatedScrollY());
                return;
            }
            var bounds = driver.getParagraphBoundsOnScreen(anchor);
            var area = driver.localToScreen(driver.getBoundsInLocal());
            if (bounds.isEmpty() || area == null) {
                follower.scrollYToPixel(driver.getEstimatedScrollY());
                return;
            }
            fromTop = bounds.get().getMinY() - area.getMinY();
        } catch (RuntimeException beforeFirstLayout) {
            follower.scrollYToPixel(driver.getEstimatedScrollY());
            return;
        }
        double height = follower.getHeight();
        if (height <= 0) {
            return;
        }
        // A viewport-sized region starting fromTop above the anchor: showing it places
        // the anchor exactly fromTop below the follower's top edge.
        try {
            follower.showParagraphRegion(anchor,
                    new javafx.geometry.BoundingBox(0, -fromTop, 1, height));
        } catch (RuntimeException notLaidOutYet) {
            // Flowless asks for a cell that does not exist yet while its list is still
            // catching up with replaced text; the next scroll event lines them up.
        }
    }

    private enum GutterMode { BOTH, OLD_ONLY, NEW_ONLY, BLAME, NONE }

    private Node buildGutter(List<Row> rows, int index, GutterMode mode) {
        Row row = index >= 0 && index < rows.size() ? rows.get(index) : null;

        HBox box = new HBox();
        box.getStyleClass().add("diff-gutter");
        box.setAlignment(mode == GutterMode.BLAME ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);

        if (mode == GutterMode.BLAME) {
            box.getChildren().addAll(blameGutter(index));
            return box;
        }
        if (mode == GutterMode.NONE) {
            return box;
        }

        // An expander is a control, so it gets a real node in the gutter rather than
        // relying on hit-testing the text: the gutter receives the click directly.
        if (row != null && row.kind() == Kind.EXPANDER) {
            // Same reasoning as the close control above: a Label with a mouse handler is
            // invisible to the keyboard and announces itself as text. Expanding hidden
            // context is one of the few things in this pane a reader genuinely needs, so
            // it is a real button with a name of its own.
            String what = "Show " + row.hiddenLines() + " more line"
                    + (row.hiddenLines() == 1 ? "" : "s");
            Button caret = Icons.button(Icons.unfold(), what, what);
            caret.getStyleClass().add("diff-expander-toggle");
            int hunkIndex = row.hunkIndex();
            caret.setOnAction(e -> expandAbove(hunkIndex));
            box.getChildren().add(caret);
            return box;
        }

        // Headers span the file rather than sitting at a line, so their gutter stays
        // blank rather than showing a number that means nothing.
        boolean numbered = row != null && row.kind() == Kind.CODE;
        if (mode == GutterMode.BOTH || mode == GutterMode.OLD_ONLY) {
            box.getChildren().add(gutterNumber(numbered ? row.oldLine() : null));
        }
        if (mode == GutterMode.BOTH || mode == GutterMode.NEW_ONLY) {
            box.getChildren().add(gutterNumber(numbered ? row.newLine() : null));
        }

        Label marker = new Label(numbered ? String.valueOf(row.marker()) : " ");
        marker.getStyleClass().add("diff-marker");
        box.getChildren().add(marker);

        if (row != null && row.isAdd()) {
            box.getStyleClass().add("diff-gutter-add");
        } else if (row != null && row.isDelete()) {
            box.getStyleClass().add("diff-gutter-delete");
        }
        return box;
    }

    /** Shared by every blame band; see {@link #blameGutter}. */
    private final Tooltip blameTooltip = new Tooltip();

    /** Commit, date and line number for a blame row, repeated only where the commit changes. */
    private List<Node> blameGutter(int index) {
        com.jfx.git.core.BlameLine row = index < blameLines.size() ? blameLines.get(index) : null;

        Label message = new Label(row == null ? "" : row.message());
        message.getStyleClass().add("blame-message");
        Label date = new Label(row == null ? "" : DateDisplay.compact(row.when()));
        date.getStyleClass().add("blame-date");
        // gutterNumber, not a bare Label: it pins the column to the width of the widest
        // number in the file. Left to size itself, the number column would be narrower on
        // line 9 than on line 10, and every blame message after it would start at a
        // different x - which only became visible once the number moved to the front.
        Label number = gutterNumber(index + 1);

        // Repeating the same commit on every line is noise; show it only where it changes.
        boolean sameAsPrevious = index > 0 && row != null
                && blameLines.get(index - 1).hash().equals(row.hash());
        if (sameAsPrevious) {
            message.setText("");
            date.setText("");
        }

        // Blame is read to find boundaries: where one commit's work ends and another's
        // begins. A stable colour band per commit shows that at a glance, and the prose
        // moves to a tooltip so it stops competing with the code.
        Region band = new Region();
        band.getStyleClass().add("blame-band");
        if (row != null) {
            band.setStyle("-fx-background-color: "
                    + bandColours.getOrDefault(row.hash(), bandColour(0)) + ";");
            // One tooltip for every band, its text filled in as the pointer arrives. A
            // Tooltip is a popup window of its own, and one per row was built for every
            // cell that scrolled into view.
            band.setOnMouseEntered(e -> blameTooltip.setText(
                    row.message() + "\n" + row.author() + " · " + DateDisplay.absolute(row.when())
                            + " · " + row.hash()));
            Tooltip.install(band, blameTooltip);
        }
        // Line number first, band last - so the number sits in the same place whether
        // blame is on or off, and only the blame prose appears between it and the code.
        // Ordered the other way round (band first, number last) the number jumped across
        // the width of two text columns every time the toggle was flipped, which reads as
        // the whole gutter being rebuilt rather than one column being added to it.
        return List.of(number, message, date, band);
    }

    /**
     * A stable colour for a commit: the same hash always gets the same band.
     *
     * <p>The hue alone used to carry all the variation, at a flat 45% saturation and 65%
     * brightness. Measured over 2,000 sample hashes that hue is spread beautifully - 357
     * of 360 values used, every 30-degree sector within a few percent of even - but every
     * band still came out the same washed-out pastel, so a wall of them read as one muted
     * family rather than as distinct commits. Saturation and brightness now vary too,
     * off bits deliberately decorrelated from the hue (see {@link #mix}), which takes the
     * palette from 360 possible bands to 4,320 and lifts the floor to a saturation that
     * actually reads as colour on a 4px strip.
     */
    static String bandColour(int index) {
        int hue = (int) Math.round(index * GOLDEN_ANGLE) % 360;
        int saturation = MIN_BAND_SATURATION + (index % 3) * 13;
        int brightness = MIN_BAND_BRIGHTNESS + ((index / 3) % 3) * 10;
        return String.format(java.util.Locale.ROOT,
                "hsb(%d, %d%%, %d%%)", hue, saturation, brightness);
    }

    /**
     * 137.5 degrees - the angle that never lets successive steps land near each other,
     * which is exactly the property the band needs and the one a hash cannot offer.
     */
    private static final double GOLDEN_ANGLE = 137.50776405003785;

    /** Enough colour to register as a hue rather than as grey, on a 4px-wide band. */
    static final int MIN_BAND_SATURATION = 60;
    /** Dark enough to stay distinct on a light theme, light enough to show on a dark one. */
    static final int MIN_BAND_BRIGHTNESS = 58;

    /**
     * The band colour for each commit in the blame, keyed by hash.
     *
     * <p>Rebuilt per blame rather than derived from the hash, because the question a
     * reader actually asks of this column is "where does one commit's work stop and the
     * next begin", and that is answered by neighbouring bands being far apart in colour -
     * which hashing cannot promise. Two commits whose hashes happened to fall a few
     * degrees apart drew two near-identical stripes right against each other, and a file
     * touched by a handful of commits could come out looking like one flat colour no
     * matter how saturated each band was on its own.
     */
    private Map<String, String> bandColours = Map.of();

    /** Assigns each commit a band in the order it first appears down the file. */
    private void assignBandColours() {
        Map<String, String> colours = new java.util.HashMap<>();
        int next = 0;
        for (com.jfx.git.core.BlameLine line : blameLines) {
            if (!colours.containsKey(line.hash())) {
                colours.put(line.hash(), bandColour(next++));
            }
        }
        bandColours = colours;
    }

    private Label gutterNumber(Integer value) {
        Label label = new Label(value == null ? "" : String.valueOf(value));
        label.getStyleClass().add("diff-line-number");
        // The stylesheet cannot know how many digits this file needs, and a fixed width
        // clips a five-digit line number. Size the column to the widest number present.
        double width = 12 + gutterDigits * 8.0;
        label.setMinWidth(width);
        label.setPrefWidth(width);
        return label;
    }

    /** Digits in the largest line number on screen, which sets the gutter's width. */
    private void measureGutter(List<Row> rows) {
        int widest = 1;
        for (Row row : rows) {
            widest = Math.max(widest, row.oldLine() == null ? 0 : row.oldLine());
            widest = Math.max(widest, row.newLine() == null ? 0 : row.newLine());
        }
        gutterDigits = String.valueOf(widest).length();
    }

    private static List<String> splitLines(String text) {
        return List.of(text.split("\n", -1));
    }

    // ------------------------------------------------------------------
    // Context expansion
    // ------------------------------------------------------------------

    private void handleClick(double x, double y) {
        int paragraph = paragraphAt(area, x, y);
        if (paragraph < 0 || paragraph >= rows.size()) {
            return;
        }
        Row row = rows.get(paragraph);
        if (row.kind() == Kind.EXPANDER) {
            expandAbove(row.hunkIndex());
        }
    }

    /** Reveals another {@link #EXPAND_STEP} lines of the gap above hunk {@code index}. */
    private void expandAbove(int index) {
        revealedAbove = diff.expand(revealedAbove, index, EXPAND_STEP);
        revealedFor = diff;
        render();
    }

    /**
     * Names the hunk and the definition the top of the viewport sits inside.
     *
     * <p>Scrolling into the middle of a long hunk otherwise loses the one piece of
     * context that says what is being changed.
     */
    private void updateStickyHeader() {
        updateStickyHeader(true);
    }

    /**
     * @param exact ask RichTextFX for the first visible paragraph, which lays the flow out
     *              first - right after a render, when the cells on hand may still be the
     *              old content's; false from the scroll path, where the flow has just been
     *              laid out and its cells can be read as they stand
     */
    private void updateStickyHeader(boolean exact) {
        if (mode != ViewMode.UNIFIED || rows.isEmpty()) {
            setSticky(null);
            return;
        }
        int first;
        if (exact) {
            try {
                first = area.firstVisibleParToAllParIndex();
            } catch (Exception e) {
                return; // before the first layout there is no visible paragraph
            }
        } else {
            first = (int) area.firstVisibleRow();
        }
        if (first <= 0 || first >= rows.size()) {
            setSticky(null); // the real header is on screen; a copy of it is noise
            return;
        }
        Row row = rows.get(first);
        if (row.hunkIndex() < 0 || row.hunkIndex() >= diff.hunks().size() || row.kind() == Kind.HUNK_HEADER) {
            setSticky(null);
            return;
        }

        // The same row at the top gives the same header. The symbol is a regex scan of up
        // to 400 lines upwards, and a scroll pulse that moved a few pixels asked for it
        // again every time. Asked again once the file behind the diff has been read,
        // which is when there is a definition to name.
        boolean fileRead = diff.newSideRead();
        if (stickyFor != rows || stickyRow != first || stickyDiff != diff || stickyFileRead != fileRead) {
            StringBuilder text = new StringBuilder(diff.hunks().get(row.hunkIndex()).header());
            String symbol = diff.enclosingSymbol(rowIndex, first);
            if (symbol != null) {
                text.append("   ").append(symbol);
            }
            stickyFor = rows;
            stickyRow = first;
            stickyDiff = diff;
            stickyFileRead = fileRead;
            stickyText = text.toString();
        }
        setSticky(stickyText);
    }

    /** What {@link #stickyText} was worked out for; see {@link #updateStickyHeader(boolean)}. */
    private List<Row> stickyFor;
    private int stickyRow = -1;
    private LoadedDiff stickyDiff;
    private boolean stickyFileRead;
    private String stickyText;

    // ------------------------------------------------------------------
    // Following the scroll
    // ------------------------------------------------------------------

    /** Whether a scroll has moved the viewport since the overlays last followed it. */
    private boolean scrollFollowPending;

    /**
     * Run after every layout pass of the scene this viewer is in; a no-op unless the
     * viewport moved. Registered in the constructor, against whichever scene holds it.
     */
    private final Runnable scrollFollow = this::followScroll;

    /**
     * Asks for the sticky header, the hunk bars and the change counter to follow the
     * viewport, once, after the current layout pass.
     *
     * <p>The scroll position changes several times in a pulse - Flowless re-estimates as
     * it measures cells - and each change ran all three, each asking RichTextFX for the
     * rows on screen, which lays the flow out again from inside its own layout. Now the
     * first change asks, and the work runs once: after the scene's layout pass in the same
     * pulse, so the bars move with the text rather than a frame behind it, or from the
     * event queue when there is no pulse (a test laying the scene out by hand).
     */
    private void scheduleScrollFollow() {
        if (scrollFollowPending) {
            return;
        }
        scrollFollowPending = true;
        javafx.application.Platform.runLater(this::followScroll);
    }

    private void followScroll() {
        if (!scrollFollowPending) {
            return;
        }
        scrollFollowPending = false;
        // From the cells the layout just placed: no forced layout, and rows looked at
        // only where they are on screen.
        updateStickyHeader(false);
        layoutHunkActions(false);
        updateChangeCounter(false);
    }

    private void setSticky(String text) {
        boolean show = text != null && !text.isBlank();
        stickyHeader.setText(show ? text : "");
        stickyHeader.setVisible(show);
        stickyHeader.setManaged(show);
    }

    // ------------------------------------------------------------------
    // Hunk actions
    // ------------------------------------------------------------------

    /**
     * Puts an action bar on every hunk currently on screen.
     *
     * <p>Re-run whenever what is on screen changes - a fresh render, a scroll, a resize,
     * or a change of selection (which is what decides whether "Stage lines" applies).
     * Cheap regardless of the diff's size: only the rows on screen are visited, and
     * {@link #rowTopIfVisible} answers only for rows RichTextFX has actually laid out, so
     * the loop places a handful of bars on a thousand-hunk file, and the pool means
     * scrolling reuses the same nodes rather than churning new ones.
     */
    private void layoutHunkActions() {
        layoutHunkActions(true);
    }

    /**
     * @param exact see {@link #updateStickyHeader(boolean)}: true asks RichTextFX which
     *              rows are on screen, laying the flow out to answer; false reads the
     *              cells the last layout left, which is all a scroll pulse needs
     */
    private void layoutHunkActions(boolean exact) {
        DiffTextArea surface = splitSurfaceVisible ? rightArea : area;
        boolean offered = surface != null && !rows.isEmpty() && !isFileView()
                && onHunkAction != null && diff.offersHunkActions();

        int used = 0;
        int[] visible = offered ? visibleRows(surface, exact) : null;
        if (visible != null) {
            // Only the rows on screen are looked at, and a hunk gets a bar when its first
            // row is one of them. This used to walk every hunk and, for each, scan the rows
            // from the top for its first one - hunks times rows on every scroll pulse,
            // five million steps for a thousand-hunk diff.
            LoadedDiff.Rows index = rowIndex;
            for (int i = visible[0]; i <= visible[1] && i < rows.size(); i++) {
                int hunkIndex = rows.get(i).hunkIndex();
                if (hunkIndex < 0 || hunkIndex >= diff.hunks().size() || index.firstRowOf(hunkIndex) != i) {
                    continue;
                }
                OptionalDouble top = rowTopIfVisible(surface, i);
                if (top.isEmpty()) {
                    continue;
                }
                HunkBar bar = hunkBarAt(used++);
                configureHunkBar(bar, hunkIndex);
                // Before its own CSS is applied the box measures without the buttons'
                // real font and padding, so it reports a width well short of what it
                // ends up occupying - and being unmanaged, nothing later corrects the
                // position derived from it, leaving the bar hanging off the right edge.
                bar.box.applyCss();
                bar.box.autosize();
                double width = bar.box.getWidth();
                bar.box.relocate(Math.max(0, overlay.getWidth() - width - rightInset()),
                        Math.max(0, top.getAsDouble()));
                bar.box.setVisible(true);
            }
        }
        // Whatever the pool still holds beyond what this pass needed belongs to hunks
        // that have scrolled away; hidden rather than removed, to be reused on the way back.
        for (int i = used; i < hunkBars.size(); i++) {
            hunkBars.get(i).box.setVisible(false);
        }
    }

    /** Breathing room between the action bar and whatever sits to the right of it. */
    private static final double HUNK_ACTION_GAP = 10;

    /**
     * How far in from the overlay's right edge an action bar must sit.
     *
     * <p>The overlay spans the whole viewport, which is wider than the text: the
     * {@link DiffScrollMap} - the surface's scrollbar - runs down the right of it. A flat
     * inset ignored it, so the bar's right end lay over the scrollbar - close enough to
     * grab when reaching for the thumb, and a misfired "Discard hunk" throws work away.
     * Measured rather than assumed, because the map's width is a user preference.
     */
    private double rightInset() {
        double inset = HUNK_ACTION_GAP;
        if (scrollMap.isVisible()) {
            inset += scrollMap.getWidth();
        }
        if (minimap.isVisible()) {
            inset += minimap.getWidth();
        }
        return inset;
    }

    /** The pooled bar at {@code index}, created and added to the overlay on first use. */
    private HunkBar hunkBarAt(int index) {
        while (hunkBars.size() <= index) {
            HunkBar bar = new HunkBar();
            bar.box.setVisible(false);
            hunkBars.add(bar);
            overlay.getChildren().add(bar.box);
        }
        return hunkBars.get(index);
    }

    /**
     * Every changed line of {@code hunkIndex} the user has selected, from whichever
     * column they selected it in.
     *
     * <p>Split view puts deletions on the left and additions on the right, so consulting
     * only the right-hand column - which is where the action bars sit - meant a deletion
     * could not be chosen at all. The two sides are separate text areas with separate
     * selections, so the answer is the union of both.
     */
    private Set<Integer> selectionFor(int hunkIndex) {
        if (!splitSurfaceVisible) {
            return selectedChangedLines(area, rows, hunkIndex);
        }
        Set<Integer> both = new java.util.HashSet<>(
                selectedChangedLines(rightArea, rows, hunkIndex));
        both.addAll(selectedChangedLines(leftArea, leftRows, hunkIndex));
        return both;
    }

    /**
     * Puts on {@code bar} what the diff offers for hunk {@code hunkIndex}: its actions, each
     * on the button for its place, or the note saying why there are none.
     *
     * <p>Which actions, and what they say, is the loaded diff's answer, from its kind and
     * its lines. Each button runs an action built from that same diff, so a click can never
     * pair one diff's hunk with another's encoding or summary.
     */
    private void configureHunkBar(HunkBar bar, int hunkIndex) {
        // Line actions only when there is a selection to act on, so the buttons never sit
        // there inviting a click that would do nothing.
        Set<Integer> selected = diff.offersLineActions() ? selectionFor(hunkIndex) : Set.of();
        LoadedDiff.Offer offer = diff.actionsFor(hunkIndex, selected);
        bar.note.setText(offer.note());
        show(bar.note, !offer.note().isEmpty());
        place(bar.stageLines, offer.find(false, HunkAction.Type.STAGE, HunkAction.Type.UNSTAGE));
        place(bar.stage, offer.find(true, HunkAction.Type.STAGE, HunkAction.Type.UNSTAGE));
        place(bar.revert, offer.find(true, HunkAction.Type.DISCARD, HunkAction.Type.REVERT));
        place(bar.discardLines, offer.find(false, HunkAction.Type.DISCARD));
    }

    /** Shows {@code button} as {@code action}, or hides it when there is none. */
    private void place(Button button, HunkAction action) {
        show(button, action != null);
        if (action == null) {
            return;
        }
        button.setText(action.label());
        if (button.getTooltip() != null) {
            button.getTooltip().setText(action.tooltip());
        }
        Consumer<HunkAction> runner = onHunkAction;
        button.setOnAction(e -> {
            if (runner != null) {
                runner.accept(action);
            }
        });
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /**
     * The changed lines of {@code hunkIndex} covered by the current text selection.
     *
     * <p>Indices are into {@code hunk.lines()}, which is what {@link HunkSelection} works
     * in. Context lines are left out: selecting them stages nothing, and counting them
     * would make the button promise more than it does.
     */
    private Set<Integer> selectedChangedLines(DiffTextArea surface, List<Row> surfaceRows,
                                              int hunkIndex) {
        if (surface.getSelection().getLength() == 0) {
            return Set.of();
        }

        int first = surface.offsetToPosition(surface.getSelection().getStart(),
                DiffTextArea.Bias.Forward).getMajor();
        int last = surface.offsetToPosition(surface.getSelection().getEnd(),
                DiffTextArea.Bias.Backward).getMajor();
        // By the line each row was built from, not by counting paragraphs down from the
        // hunk header - that only works in unified view: split pads the shorter of each
        // deletion/addition run, so on the right-hand column the arithmetic landed on the
        // deletion above the addition the user had selected, and Stage line staged the
        // wrong change.
        return indexOf(surfaceRows).changedLines(hunkIndex, first, last);
    }

    /**
     * Where row {@code i} - a hunk's first - sits in overlay coordinates, or empty when it
     * is not on screen at all.
     *
     * <p>An absent result is the useful case rather than a failure: RichTextFX lays out
     * only the paragraphs actually in view, so "no bounds" means precisely "this hunk is
     * scrolled away and needs no bar" - which is what keeps {@link #layoutHunkActions}
     * bounded by the viewport rather than by the size of the diff. An earlier version
     * returned 0 for this case, which was harmless while only one bar was ever placed
     * and would have stacked every off-screen hunk's bar at the top now.
     */
    private OptionalDouble rowTopIfVisible(DiffTextArea surface, int i) {
        if (i < 0 || i >= surface.getParagraphs().size()) {
            // Rows ahead of the text: the surface is mid-refill.
            return OptionalDouble.empty();
        }
        int start = surface.getAbsolutePosition(i, 0);
        try {
            Optional<Bounds> bounds = surface.getCharacterBoundsOnScreen(start, start + 1);
            return bounds.isPresent()
                    ? OptionalDouble.of(overlay.screenToLocal(bounds.get()).getMinY())
                    : OptionalDouble.empty();
        } catch (RuntimeException notLaidOutYet) {
            // RichTextFX answers this question by forcing a Flowless layout, and a
            // layout run while the cell list is still catching up with replaced text
            // asks for a cell that does not exist yet - Flowless calls Optional.get()
            // on it and the NoSuchElementException comes out here, through a
            // getCharacterBoundsOnScreen that never got to return anything.
            //
            // Checking the returned Optional, which this did, cannot cover that: the
            // throw is inside the call rather than at it. Selecting a file in the
            // staging list was enough to hit it, because the selection listener that
            // triggers this fires during the text replacement rather than after it.
            //
            // Empty is the right answer, not a workaround. It already means "this
            // hunk is not on screen, so do not place a bar for it", and a hunk whose
            // position cannot be determined is exactly that. Every later scroll,
            // selection and render calls this again, so a bar missed during one
            // half-finished layout is placed by the next pass.
            //
            // paragraphAt(), ten lines below, has guarded the same fragility since it
            // was written - "hit testing before the first layout". This is the same
            // hazard reached through a different RichTextFX entry point.
            return OptionalDouble.empty();
        }
    }

    /**
     * The first and last rows on {@code surface}'s screen, or null when that cannot be
     * known yet.
     *
     * @param exact through RichTextFX, which lays the flow out to answer - right after new
     *              content, when the cells on hand may still be the old content's; or off
     *              the cells the last layout left, which is what a scroll pulse wants
     */
    private int[] visibleRows(DiffTextArea surface, boolean exact) {
        int first;
        int last;
        if (exact) {
            try {
                first = surface.firstVisibleParToAllParIndex();
                last = surface.lastVisibleParToAllParIndex();
            } catch (RuntimeException notLaidOutYet) {
                // The same hazard rowTopIfVisible describes; the next pass places the bars.
                return null;
            }
        } else {
            first = (int) surface.firstVisibleRow();
            last = surface.lastVisibleIndex();
        }
        if (last < 0 || first < 0) {
            return null;
        }
        return new int[]{first, last};
    }

    private int paragraphAt(DiffTextArea surface, double x, double y) {
        try {
            int offset = surface.hit(x, y).getInsertionIndex();
            return surface.offsetToPosition(offset, DiffTextArea.Bias.Forward).getMajor();
        } catch (Exception e) {
            return -1; // hit testing before the first layout
        }
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    /** Scrolls to the hunk at {@code index}, wrapping at either end. */
    private void jumpToHunk(int index) {
        if (diff.hunks().isEmpty()) {
            return;
        }
        currentHunkIndex = Math.floorMod(index, diff.hunks().size());

        DiffTextArea surface = mode == ViewMode.SPLIT ? rightArea : area;
        // One field serves both layouts, which the ternary this replaced obscured by
        // branching on the mode and returning the same thing either way. In split,
        // renderSplit assigns rows = split.right() and leftRows = split.left(), so rows
        // is already the right column - the surface chosen just above. Anyone reading the
        // old line would reasonably have "corrected" it to use leftRows and broken hunk
        // navigation in split view.
        List<Row> surfaceRows = rows;
        if (surface == null) {
            return;
        }
        pendingJumpRow = -1;
        int i = indexOf(surfaceRows).firstRowOf(currentHunkIndex);
        if (i >= 0) {
            try {
                // Already at the top: nothing will scroll, so nothing is in flight.
                pendingJumpRow = surface.firstVisibleParToAllParIndex() == i ? -1 : i;
            } catch (RuntimeException beforeFirstLayout) {
                pendingJumpRow = i;
            }
            surface.showParagraphAtTop(i);
        }
        // Say where we are going, not where the viewport still is: the scroll above
        // lands on the next layout pass, and reading the rows on screen now would find
        // the hunk we just left and name that one instead.
        showChangeCounter();
    }

    /**
     * The row a Next/Previous step is scrolling to, until it is on screen; -1 otherwise.
     *
     * <p>Flowless reports the rows on screen from the middle of its own layout pass,
     * and at that moment they are still the rows being left: following the viewport then
     * would snap the counter back to the hunk just stepped away from. While a jump is in
     * flight the counter names its destination; once that row is on screen, the viewport
     * is trusted again.
     */
    private int pendingJumpRow = -1;

    /**
     * Steps to the next ({@code +1}) or previous ({@code -1}) change, as the arrow
     * buttons do. For the window's keyboard shortcut; does nothing where they are off.
     */
    public void stepChange(int delta) {
        if (diff.hunks().size() < 2 || isFileView() || currentFilePath.get().isEmpty()) {
            return;
        }
        if (currentHunkIndex < 0) {
            jumpToHunk(delta > 0 ? 0 : diff.hunks().size() - 1);
        } else {
            jumpToHunk(currentHunkIndex + delta);
        }
    }

    /**
     * "Change 2 of 5", following the reader.
     *
     * <p>Scrolling moves the count to whichever change is at the top of the view - but
     * not while the one last stepped to is still on screen. The last change often cannot
     * be scrolled to the top, and snapping the count back to the one above it would make
     * Next step to the same change again, for ever.
     */
    private void updateChangeCounter() {
        updateChangeCounter(true);
    }

    /** @param exact see {@link #layoutHunkActions(boolean)} */
    private void updateChangeCounter(boolean exact) {
        int total = diff.hunks().size();
        if (total == 0 || isFileView()) {
            changeCounter.setText("");
            return;
        }
        if (currentHunkIndex < 0 || currentHunkIndex >= total) {
            // A diff opens at its top, so the first change is the one in view.
            currentHunkIndex = 0;
        }
        DiffTextArea surface = splitSurfaceVisible ? rightArea : area;
        int[] visible = surface == null || rows.isEmpty() ? null : visibleRows(surface, exact);
        if (visible != null) {
            try {
                int first = visible[0];
                int last = Math.min(visible[1], rows.size() - 1);
                if (pendingJumpRow >= 0) {
                    if (first <= pendingJumpRow && pendingJumpRow <= last) {
                        pendingJumpRow = -1;
                    } else {
                        showChangeCounter();
                        return;
                    }
                }
                if (!hunkShown(currentHunkIndex, first, last)) {
                    for (int i = Math.max(0, first); i <= last; i++) {
                        int hunk = rows.get(i).hunkIndex();
                        if (hunk >= 0 && hunk < total) {
                            currentHunkIndex = hunk;
                            break;
                        }
                    }
                }
            } catch (RuntimeException beforeFirstLayout) {
                // Nothing laid out yet; keep whatever was last known.
            }
        }
        showChangeCounter();
    }

    /** Writes {@link #currentHunkIndex} into the counter, as it stands. */
    private void showChangeCounter() {
        int total = diff.hunks().size();
        if (total == 0 || isFileView()) {
            changeCounter.setText("");
            return;
        }
        changeCounter.setText(currentHunkIndex < 0 || currentHunkIndex >= total
                ? total + (total == 1 ? " change" : " changes")
                : "Change " + (currentHunkIndex + 1) + " of " + total);
    }

    private boolean hunkShown(int hunk, int first, int last) {
        if (hunk < 0) {
            return false;
        }
        for (int i = Math.max(0, first); i <= last && i < rows.size(); i++) {
            if (rows.get(i).hunkIndex() == hunk) {
                return true;
            }
        }
        return false;
    }

    /** What the Change counter says; for tests. */
    String changeCounterText() {
        return changeCounter.getText();
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static Region grow() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static Button flatButton(String text, String tooltip) {
        Button btn = new Button(text);
        btn.getStyleClass().add("diff-tool-button");
        btn.setTooltip(new Tooltip(tooltip));
        // A word in a button is the whole of what it says: when the row runs short the
        // path beside it gives way, not the label ("B...", "Hi..." told nobody anything).
        btn.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return btn;
    }

    /**
     * The diff controls as menu items, each firing the control it stands for.
     *
     * <p>Delegating to the buttons rather than duplicating their handlers keeps one
     * implementation of every action: a toggle's state lives on the toggle whether or not
     * the toggle is currently on screen, and the menu item reads and flips that.
     */
    private MenuButton buildOverflowMenu(Button prev, Button next,
                                         ToggleButton unified, ToggleButton split,
                                         List<ToggleButton> context, ToggleButton ignoreWhitespace,
                                         ToggleButton whitespace, Button copy) {
        MenuButton menu = new MenuButton("View options");
        menu.getStyleClass().add("diff-overflow-menu");
        menu.setTooltip(new Tooltip("Diff navigation and display options"));

        menu.getItems().addAll(
                fires("Previous change", prev),
                fires("Next change", next),
                new SeparatorMenuItem(),
                mirrors("Unified view", unified),
                mirrors("Split view", split),
                new SeparatorMenuItem());
        ToggleGroup contextChoices = new ToggleGroup();
        String[] contextNames = {"3 lines of context", "10 lines of context", "Whole file"};
        for (int i = 0; i < context.size(); i++) {
            menu.getItems().add(chooses(contextNames[i], context.get(i), contextChoices));
        }
        MenuItem copyPathItem = new MenuItem("Copy path");
        copyPathItem.setOnAction(e -> copyPath());
        menu.getItems().addAll(
                new SeparatorMenuItem(),
                mirrors("Ignore whitespace changes", ignoreWhitespace),
                mirrors("Show whitespace characters", whitespace),
                new SeparatorMenuItem(),
                fires("Copy the diff", copy),
                copyPathItem);
        menu.setVisible(false);
        menu.setManaged(false);
        return menu;
    }

    /** A menu item that presses {@code button}. */
    private static MenuItem fires(String text, Button button) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> button.fire());
        item.disableProperty().bind(button.disabledProperty());
        return item;
    }

    /** One choice of a group, as a radio item that selects {@code toggle}. */
    private static javafx.scene.control.RadioMenuItem chooses(String text, ToggleButton toggle,
                                                              ToggleGroup group) {
        javafx.scene.control.RadioMenuItem item = new javafx.scene.control.RadioMenuItem(text);
        item.setToggleGroup(group);
        item.setSelected(toggle.isSelected());
        toggle.selectedProperty().addListener((obs, old, on) -> item.setSelected(on));
        item.disableProperty().bind(toggle.disabledProperty());
        item.setOnAction(e -> {
            if (!toggle.isSelected()) {
                toggle.fire();
            }
        });
        return item;
    }

    /** A checkable menu item showing, and flipping, {@code toggle}'s state. */
    private static CheckMenuItem mirrors(String text, ToggleButton toggle) {
        CheckMenuItem item = new CheckMenuItem(text);
        item.setSelected(toggle.isSelected());
        toggle.selectedProperty().addListener((obs, old, on) -> item.setSelected(on));
        item.setOnAction(e -> {
            // fire() rather than setSelected(): the toggle's own handler is what applies
            // the change, and a ToggleGroup member also needs the group told.
            if (toggle.isSelected() != item.isSelected()) {
                toggle.setSelected(item.isSelected());
                toggle.fire();
            }
        });
        return item;
    }

    /**
     * Shows the diff controls on the row when they fit, and in the menu when they do not.
     *
     * <p>The row is set to {@code minWidth 0} so that it clips rather than forcing the
     * enclosing SplitPane wider. Clipping is the right trade for the pane and the wrong
     * one for the user: at 640px the controls simply ran off the right-hand edge, with
     * nothing on screen to say that navigation and the whitespace toggles were there at
     * all. Measuring what the row needs and folding the secondary half into a labelled
     * menu keeps every action reachable at every width.
     *
     * <p>The measurement deliberately always counts {@code diffOnlyTools}, whether or not
     * it is currently shown, so the decision cannot depend on its own outcome and flip
     * back and forth on a single resize.
     */
    private void updateToolbarOverflow() {
        if (toolbarRow == null || overflowMenu == null || diffOnlyTools == null) {
            return;
        }
        boolean applicable = !isFileView();
        if (!applicable) {
            // Nothing to overflow: these controls do not apply to a whole file or a blame.
            show(diffOnlyTools, false);
            show(overflowMenu, false);
            return;
        }
        double available = toolbarRow.getWidth();
        if (available <= 0) {
            return;
        }

        double needed = diffOnlyTools.prefWidth(-1);
        int counted = 1;
        for (javafx.scene.Node child : toolbarRow.getChildren()) {
            if (child == diffOnlyTools || child == overflowMenu || !child.isManaged()) {
                continue;
            }
            needed += child.prefWidth(-1);
            counted++;
        }
        needed += toolbarRow.getSpacing() * Math.max(0, counted - 1);

        boolean collapse = needed > available;
        show(diffOnlyTools, !collapse);
        show(overflowMenu, collapse);
    }

    private static ToggleButton segmentedToggle(String text, ToggleGroup group, String tooltip) {
        ToggleButton btn = new ToggleButton(text);
        btn.setToggleGroup(group);
        btn.getStyleClass().add("diff-segment");
        btn.setTooltip(new Tooltip(tooltip));
        return btn;
    }
}
