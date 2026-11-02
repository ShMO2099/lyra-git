package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitBackendFactory;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.SwitchableGitBackend;
import com.jfx.git.graph.GitGraphCell;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.graph.HistoryWindow;

import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Button;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.Locale;

/**
 * One open repository: toolbar, branch sidebar, commit graph, detail panel and status bar.
 */
public class RepoTab extends Tab {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(RepoTab.class.getName());

    // ------------------------------------------------------------------
    // Floors: how small each pane goes, and so how small the window goes
    // ------------------------------------------------------------------

    /**
     * The narrowest the details panel goes. The widest thing it holds is a file row - a
     * status letter, a path, its line counts and a hover action - and below this they
     * run into each other.
     */
    public static final double DETAIL_MIN_WIDTH = 340;

    /** A split pane's divider, with a little over for a theme that draws it wider. */
    private static final double SPLIT_DIVIDER = 6;

    /**
     * Room in the graph's floor for commit subjects: about twenty characters, enough to
     * tell one commit from the next. The subject is the column that gives way, so without
     * it the graph at its floor was a column of ellipses. A history with many lanes still
     * spends some of it on lanes - the graph draws up to a third of its width of them
     * when it can - and the lane divider trades one for the other.
     */
    private static final double SUBJECT_FLOOR = 120;

    /**
     * The shortest the layout goes: the toolbar and the status bar, and between them the
     * sidebar's filter with all five section headings and a few rows of the open one -
     * which leaves the graph its filter bar, its column titles and seven or eight commits.
     */
    private static final double MIN_HEIGHT = 380;

    /**
     * The narrowest the graph goes: its fixed columns at their narrowest - the ref column at
     * the density's width, the lanes it never draws fewer of, author, age and hash - plus
     * {@link #SUBJECT_FLOOR}. Read from the density and node style in use, so a wider
     * density raises it.
     */
    public static double graphFloor() {
        com.jfx.git.graph.GraphDensity density = com.jfx.git.graph.GraphDensity.current();
        com.jfx.git.graph.GraphNodeStyle style = com.jfx.git.graph.GraphNodeStyle.current();
        return density.railWidth()
                + density.graphWidth(com.jfx.git.graph.GraphDensity.MIN_DRAWN_LANES, style,
                        com.jfx.git.graph.GraphDensity.MAX_DRAWN_LANES)
                + (style.needsAuthorColumn() ? density.authorWidth() : 0)
                + DateDisplay.graphAgeWidth(density.ageWidth())
                + density.hashWidth()
                + SUBJECT_FLOOR;
    }

    /**
     * The smallest the repository layout can be and still show all of itself, in layout
     * pixels: the sidebar, the graph and the details panel side by side at their floors,
     * and {@link #MIN_HEIGHT}. The main window's minimum is built on this - see
     * {@link MainWindowSize} - so a pane that raises its floor raises the window's too.
     */
    public static MainWindowSize.Size minimumSize() {
        double width = SidebarPane.MIN_WIDTH + SPLIT_DIVIDER + graphFloor() + SPLIT_DIVIDER + DETAIL_MIN_WIDTH;
        return new MainWindowSize.Size(width, MIN_HEIGHT);
    }

    private final SwitchableGitBackend gitService;

    private final RepoToolbar toolBar;
    private final SidebarPane sidebarPane;
    private final ListView<GitGraphRow> commitListView;
    private final RightPanelManager rightPanelManager;
    /** Held so a save can ask which encoding the content was decoded under. */
    private final DiffViewer diffViewer;
    private final StatusBar statusBar;
    /** Runs every operation started in this tab; see {@link OperationRunner}. */
    private final OperationRunner operations;
    private final TextField searchField;
    /**
     * Results of a search over the whole history (Enter in the filter box), under the
     * filter bar. The instant filter above still works on the loaded rows as it did.
     */
    private final HistorySearchPanel historySearch;
    private final com.jfx.git.core.AutoFetchScheduler autoFetchScheduler;
    /** Snapshots the working tree on the interval Settings gives; see WorkingTreeSnapshots. */
    private final com.jfx.git.core.SnapshotScheduler snapshotScheduler;
    /**
     * HEAD, the refs and the base-branch patterns as they were for the last conflict check,
     * so a refresh that moved none of them does not run the merge again. FX thread only.
     */
    private String conflictKey;

    /**
     * What an empty graph says, which depends on why it is empty.
     *
     * <p>Two quite different situations produce no rows: a repository with no commits
     * yet - which is what "New repository" hands you, so it is the first thing a new user
     * sees - and a filter that matched nothing. One message for both would be wrong half
     * the time, and "No commits yet" in front of someone whose search simply missed is
     * the more alarming half.
     */
    private final Label emptyGraphMessage = new Label();

    /**
     * The strip over the graph's rows while a merge, rebase, cherry-pick or revert is
     * stopped: "Merge of feature into main stopped: 2 conflicting files". Commercial clients show
     * an orange row there for the same reason - the graph is what a repository is looked
     * at for, and nothing in it said the repository was half-way through something, apart
     * from a WIP row that looks the same as any other day's. Clicked, it opens the
     * uncommitted changes, where the conflicts are listed.
     *
     * <p>Driven by the right panel's in-progress banner refresh (see
     * {@link RightPanelManager#setOnOperationState}), so the two always agree.
     */
    private final Button operationStrip = new Button();

    /**
     * What the graph shows before its first page: a spinner and the repository's name.
     * "No commits yet" in its place made every repository look empty until it loaded.
     */
    private final Label openingLabel = new Label("Opening repository…");
    private final javafx.scene.layout.VBox openingPlaceholder = new javafx.scene.layout.VBox(
            UiScale.GAP, new javafx.scene.control.ProgressIndicator(), openingLabel);
    /** The way out of a search, beside its count. */
    private final javafx.scene.control.Button clearSearchInline =
            Icons.button(Icons.close(), "Clear search", "Clear search");
    /** Under "No commit matches this search": the way out of the search. */
    private final javafx.scene.control.Button clearSearchButton = new javafx.scene.control.Button("Clear search");
    /** The empty graph: what is missing, and - after a search - the way back. */
    private final javafx.scene.layout.VBox emptyGraphBox = new javafx.scene.layout.VBox(UiScale.STEP);
    /** What the tab knows to badge itself with: dirty, ahead, behind. */
    private boolean dirty;
    private int[] tracking = new int[2];
    /** The window's title while this tab is the one showing: "repository — branch". */
    private final javafx.beans.property.ReadOnlyStringWrapper windowTitle =
            new javafx.beans.property.ReadOnlyStringWrapper("");
    private String branchName = "";

    /** Whether the status bar is showing this tab's open as busy. */
    private boolean openingBusy;
    /** Holds the external-change check off while the first snapshot is being taken. */
    private boolean seedingSnapshot;
    /** Where this tab's notifications appear. */
    private ToastHost toastHost;
    /** Whether a reload after an outside change is showing as background activity. */
    private boolean externalRefreshShown;

    private static final String NO_COMMITS_YET =
            "No commits yet. Make one from the panel on the right.";
    private static final String NOTHING_MATCHED =
            "No commit matches this search.";

    /** How many of the loaded commits the current filter matches. */
    private final Label searchHits = new Label();
    /** Warns that a filter is running against history that has not been fully walked. */
    private final Label searchTruncated = new Label();
    /** Which fields the filter looks at; at least one is always on. */
    private final ToggleButton scopeMessage = new ToggleButton("message");
    private final ToggleButton scopeAuthor = new ToggleButton("author");
    private final ToggleButton scopeHash = new ToggleButton("hash");

    /**
     * What a search does to non-matches (§2.5): off — the default — they dim in place
     * and nothing leaves the graph; on, the old behaviour narrows the list. Filtering
     * is the opt-in mode because it hides history, which is exactly what a search
     * should not do by surprise.
     */
    private final ToggleButton filterModeToggle = new ToggleButton("filter");

    /** Normal browsing UI (sidebar + graph + details), hidden while file history is open. */
    private SplitPane browseArea;
    private StackPane contentStack;
    private FileHistoryView openHistory;

    /** Screens shown in place of the graph, newest last; see {@link #showView}. */
    private final java.util.Deque<RepoView> views = new java.util.ArrayDeque<>();
    /** The header bar and the top view's content, while there is one. */
    private BorderPane viewHost;
    /**
     * Search in files, kept once made: opening a result closes the view to show the
     * file, and Ctrl+Shift+F brings the same results back rather than an empty form.
     */
    private com.jfx.git.ui.views.SearchInFilesView searchInFiles;

    /** Held so the collapse shortcuts can move their dividers. */
    private SplitPane browseSplit;
    private SplitPane mainSplit;

    /** The commit filter's options; the column chooser is added to their end. */
    private FlowPane filterRow;

    /** The repository folder's own name, shown unless the user has renamed the tab. */
    private String folderTitle = "";
    /** See {@link #getRequestedDirectory()}. */
    private File requestedDirectory;
    /**
     * The name the user gave this tab, or null to use the folder's.
     *
     * <p>Kept separate from the text rather than simply overwriting it, so reopening or
     * reloading the repository does not quietly undo the rename.
     */
    private String alias;
    private DetailPanel detailPanel;
    /** Divider positions from before a collapse, so restoring returns to them. */
    private double savedSidebarDivider;
    private double savedDetailDivider;

    /**
     * Repaints the graph when the theme changes.
     *
     * <p>A field rather than a lambda passed straight to {@code addListener}: the theme
     * property holds it weakly, so the only thing keeping it alive is this reference, and
     * it dies with the tab.
     */
    private final javafx.beans.value.ChangeListener<ThemeManager.Theme> themeListener;

    /**
     * Repaints the lanes when the zoom changes, for the same reason as the theme listener
     * and held for the same reason: the property is static and outlives the tab.
     */
    private final javafx.beans.value.ChangeListener<Number> zoomListener;

    /**
     * Says so when git refused the account connected in Integrations and the user's own
     * credentials were used instead - otherwise the change of account is invisible. Only
     * the tab in front says it; registered weakly, as {@link #zoomListener} is.
     */
    private final javafx.beans.value.ChangeListener<com.jfx.git.core.hosting.HostingProvider> gitLoginRefusedListener =
            (obs, old, provider) -> {
                if (provider == null || !isSelected() || toastHost == null) return;
                toastHost.show(ToastHost.Kind.WARNING, provider.displayName()
                                + " refused the connected account for git, so your own git credentials"
                                + " were used instead. Connect it again in Integrations to use it.",
                        ToastHost.LONG, java.util.List.of());
            };

    /**
     * Column widths that depend on what is loaded rather than on the density.
     *
     * <p>Owned by the tab because they are a property of the page: the header and every
     * row read the same two numbers, which is the only way the titles stay over their
     * columns.
     */
    private final com.jfx.git.graph.GraphMetrics graphMetrics;

    /**
     * Which commits the current selection makes relevant, so the rest can be faded.
     *
     * <p>Owned by the tab and shared with every cell, for the same reason the metrics are:
     * it is a property of the page and the selection, not of a row.
     */
    private final com.jfx.git.graph.GraphHighlight graphHighlight =
            new com.jfx.git.graph.GraphHighlight();

    /**
     * The page of history the graph shows, and every rule about replacing it: refresh,
     * the page kept from the last session, older pages, paging in to reach a commit, and
     * the filter's output. This tab only shows what it decides; see {@link HistoryView}.
     */
    private final HistoryWindow history = new HistoryWindow(new RepositoryHistory(), new HistoryView(),
            graphHighlight, HistoryWindow::preferredFirstPage);

    /** The author the graph is narrowed to, or null for everyone. */
    private String authorFilter;


    /**
     * The strip holding the column titles and the gear.
     *
     * <p>Held so hiding a column can replace the header. Both the titles and the rows read
     * the column set once, when they are built, so refreshing the list alone would leave
     * the headings describing the previous set of columns.
     */
    private StackPane graphHeaderRow;

    /** The draggable boundary between the graph and the messages. */
    private com.jfx.git.graph.LaneDivider laneDivider;

    public RepoTab(LyraApp app, String title) {
        // Not GitBackendFactory.createDefault(): choosing an engine probes git, and this
        // runs on the FX thread. The engine is chosen when the repository opens, on the
        // lane; see SwitchableGitBackend.deferred().
        this(app, title, SwitchableGitBackend.deferred());
    }

    public RepoTab(LyraApp app, String title, GitBackend backend) {
        super(title);
        // Everything below is handed this wrapper rather than the engine itself, so
        // switching engines later swaps one field instead of rebuilding the tab.
        this.gitService = backend instanceof SwitchableGitBackend s ? s : new SwitchableGitBackend(backend);
        this.statusBar = new StatusBar();
        statusBar.countWaitingOn(gitService);
        this.operations = new OperationRunner(gitService, statusBar, new OperationRunner.Screen() {
            @Override
            public void refresh(OperationRunner.Refresh scope) {
                refreshAfterOperation(scope);
            }

            @Override
            public void recordOwnChange(OperationRunner.Refresh scope) {
                RepoTab.this.recordOwnChange(scope);
            }
        });
        // A fetch that moved remote-tracking refs has to show: the Pull badge, the remote
        // branches and the graph used to stay stale until the window next gained focus.
        // The external-change check reloads only when refs actually moved.
        this.autoFetchScheduler = new com.jfx.git.core.AutoFetchScheduler(
                gitService, when -> {
                    statusBar.reportAutoFetch(when);
                    refreshIfChangedExternally();
                    // A fetch is what moves the base branch; queued after the check above,
                    // so it sees the refs that check found.
                    checkConflicts();
                });
        autoFetchScheduler.applyPreference();
        this.snapshotScheduler = new com.jfx.git.core.SnapshotScheduler(gitService);
        statusBar.setSnapshotsOpener(this::openSnapshots);
        // A background fetch shows as a quiet spinner, not as a message: nobody asked for it.
        autoFetchScheduler.setOnActivity(running -> statusBar.backgroundActivity("Fetching in the background", running));

        BorderPane root = new BorderPane();

        toolBar = new RepoToolbar(app, operations);
        // Pull requests, issues and the reflog open here, in place of the graph, not in
        // windows. The provider lists keep their own (one per origin) and push reviews
        // over themselves; the tools open by kind.
        toolBar.setProviderViewHost(this::showView);
        toolBar.setViewHost(this::openView);
        root.setTop(toolBar);
        root.setBottom(statusBar);

        // --- Centre: commit graph with a filter box ---
        commitListView = new ListView<>();
        // A method reference rather than a lambda over rightPanelManager: the field is
        // not assigned until further down the constructor.
        this.graphMetrics = new com.jfx.git.graph.GraphMetrics(
                com.jfx.git.graph.GraphDensity.current());
        // A width the user dragged to is a deliberate act, so it is restored before the
        // first page is laid out rather than snapping into place after it.
        int savedLanes = com.jfx.git.core.AppPreferences.graphLaneBudget();
        if (savedLanes > 0) {
            graphMetrics.setLaneBudget(savedLanes);
        }
        double savedRail = com.jfx.git.core.AppPreferences.graphRailWidth();
        if (savedRail > 0) {
            graphMetrics.setRailWidth(savedRail);
        }
        commitListView.setCellFactory(graphCellFactory());
        commitListView.setMinWidth(200);
        // An empty graph was a blank panel. Two quite different things produce one: a
        // repository with no commits yet - which is what "New repository" hands you, so
        // it is the first thing a new user sees - and a filter that matched nothing. The
        // count beside the search box distinguishes them once you know to look, and this
        // says it where the answer is missing.
        emptyGraphMessage.getStyleClass().add("t-caption");
        emptyGraphMessage.setWrapText(true);
        emptyGraphMessage.setText(NO_COMMITS_YET);
        openingPlaceholder.setAlignment(javafx.geometry.Pos.CENTER);
        openingPlaceholder.getStyleClass().add("graph-opening");
        openingLabel.getStyleClass().add("t-caption");
        ((javafx.scene.control.ProgressIndicator) openingPlaceholder.getChildren().get(0))
                .setMaxSize(UiScale.SECTION * 2, UiScale.SECTION * 2);
        // The empty message replaces this once a page has been drawn; see firstPageDrawn.
        commitListView.setPlaceholder(openingPlaceholder);
        clearSearchButton.getStyleClass().add("btn-sm");
        clearSearchButton.setVisible(false);
        clearSearchButton.setManaged(false);
        emptyGraphBox.getChildren().setAll(emptyGraphMessage, clearSearchButton);
        emptyGraphBox.setAlignment(javafx.geometry.Pos.CENTER);
        emptyGraphBox.getStyleClass().add("graph-empty");
        commitListView.getStyleClass().add("commit-graph");
        // Thousands of loaded commits shrank the default thumb to a 12px fleck.
        MinThumbScrollBarSkin.installOn(commitListView);
        // Every graph row is exactly this tall, so the list can do arithmetic instead of
        // measuring: scrollbar extent and the index at a given offset both become
        // divisions rather than a walk over realised cells. Read from the density, which
        // is what decides the height - the two must not drift apart.
        commitListView.setFixedCellSize(com.jfx.git.graph.GraphDensity.current().rowHeight());

        // The graph is painted onto a Canvas, and a Canvas keeps whatever was last drawn
        // on it: unlike every styled node in the app it does not follow a stylesheet swap.
        // Without this the lanes would stay in the old theme's colours until some
        // unrelated refresh happened to repaint them.
        //
        // Held as a field and registered weakly: the property is static and outlives every
        // tab that reads it, so a strong listener would pin closed tabs in memory.
        this.themeListener = (obs, old, theme) -> {
            com.jfx.git.graph.GitGraphCell.clearBackgroundCache();
            commitListView.refresh();
        };
        ThemeManager.currentProperty().addListener(
                new javafx.beans.value.WeakChangeListener<>(themeListener));

        // The lane drawing is a canvas, and its buffer is sized for the zoom in force when
        // the row was painted. Changing the zoom therefore has to repaint the graph, or
        // the lanes stay at the old resolution and are magnified into softness - which is
        // the whole thing the buffer scaling exists to avoid.
        this.zoomListener = (obs, old, value) -> commitListView.refresh();
        UiZoom.percentProperty().addListener(
                new javafx.beans.value.WeakChangeListener<>(zoomListener));
        VBox.setVgrow(commitListView, Priority.ALWAYS);

        searchField = new TextField();
        clearSearchButton.setOnAction(e -> searchField.clear());
        searchField.setPromptText("Filter commits… (Enter searches all history)");
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, old, text) -> scheduleFilter(text));
        // Typing filters what is loaded, instantly; Enter asks the engine about all of
        // history. Two gestures because they cost very different amounts: the first is a
        // pass over rows in memory, the second may walk and diff every commit there is.
        searchField.setOnAction(e -> searchAllHistory());

        historySearch = new HistorySearchPanel(gitService);
        historySearch.setOnOpen(this::jumpToCommit);

        VBox filterBar = buildFilterBar();

        // Column titles, laid out from the same definition the rows use so they sit over
        // their data rather than near it.
        var graphHeader = new com.jfx.git.graph.GraphHeader(
                com.jfx.git.graph.GraphDensity.current(), graphMetrics);
        graphHeader.alignWith(commitListView);

        // A gear beside the titles, as well as the right-click the header itself carries:
        // a right-click that is the only way in is a feature nobody finds.
        Button columnsGear = Icons.button(Icons.settings(), "Choose columns",
                "Choose which columns to show");
        columnsGear.getStyleClass().addAll("btn-sm", "graph-columns-gear");
        // Goes through the header so the gear and the right-click share one menu, and a
        // second click replaces the open one rather than stacking another on top of it.
        columnsGear.setOnAction(e -> currentGraphHeader().showColumnMenu(columnsGear));

        // Both the header and the rows read the column set when they are built, so the
        // whole graph is rebuilt when it changes - refreshing the list alone would leave
        // the titles describing the previous set of columns.
        graphHeader.setOnColumnsChanged(this::rebuildGraphColumns);
        graphHeader.setAuthorFilter(authorFilterModel());

        // On the filter row, at its trailing end - not over the header strip, where it
        // sat on top of the last column's title and read "SH" with a gear through it.
        filterRow.getChildren().add(columnsGear);

        StackPane headerRow = new StackPane(graphHeader);
        this.graphHeaderRow = headerRow;

        // The lane budget follows the width the list actually has. Rows are rebuilt only
        // when the budget itself moves, which takes a whole lane's worth of resize - so
        // dragging a divider does not refresh the graph on every pixel.
        commitListView.widthProperty().addListener((obs, old, width) -> {
            if (graphMetrics.setAvailableWidth(width.doubleValue())) {
                commitListView.refresh();
            }
        });

        // The divider stands over the header and the list together, so the boundary can
        // be taken anywhere down the graph rather than only in the twenty-two pixels of
        // heading that names it.
        VBox graphStack = new VBox(headerRow, commitListView);
        VBox.setVgrow(commitListView, Priority.ALWAYS);

        laneDivider = new com.jfx.git.graph.LaneDivider(
                com.jfx.git.graph.GraphDensity.current(), graphMetrics, graphMetrics.nodeStyle());
        laneDivider.setOnBudgetCommitted(this::laneBudgetCommitted);

        com.jfx.git.graph.RailDivider railDivider = new com.jfx.git.graph.RailDivider(
                com.jfx.git.graph.GraphDensity.current(), graphMetrics);
        railDivider.setOnWidthCommitted(() -> com.jfx.git.core.AppPreferences.setGraphRailWidth(
                graphMetrics.railWidthOverride()));

        StackPane graphArea = new StackPane(graphStack, railDivider, laneDivider);
        StackPane.setAlignment(laneDivider, Pos.CENTER_LEFT);
        StackPane.setAlignment(railDivider, Pos.CENTER_LEFT);

        operationStrip.getStyleClass().add("graph-operation-banner");
        operationStrip.setGraphic(Icons.warning());
        operationStrip.setMaxWidth(Double.MAX_VALUE);
        operationStrip.setAlignment(Pos.CENTER_LEFT);
        operationStrip.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        operationStrip.setOnAction(e -> selectWorkingTreeRow());
        show(operationStrip, false);

        VBox centre = new VBox(UiScale.SNUG, filterBar, historySearch, operationStrip, graphArea);
        VBox.setVgrow(graphArea, Priority.ALWAYS);
        VBox.setVgrow(commitListView, Priority.ALWAYS);
        // The graph's floor, so the divider beside the sidebar stops there. The split
        // shares out its width by the divider's position, and at the window's minimum that
        // gave the sidebar more than its floor and the graph less than its own.
        centre.setMinWidth(graphFloor());

        // --- Right: commit details / staging area ---
        // One frame for all four modes the panel has, so it always says which one is on
        // screen rather than simply becoming different.
        DetailPanel rightTopDetails = new DetailPanel();
        // An explicit width is more reliable than a divider percentage, which the
        // SplitPane silently clamps while the tab is still being laid out.
        // The widest thing this panel has to hold is a file row: a status letter, a path,
        // its line counts and a hover action. At 430 those were competing, which is what
        // pushed the Stage button off the end of a long path. 480 gives the path room
        // before anything has to elide.
        rightTopDetails.setPrefWidth(480);
        rightTopDetails.setMinWidth(DETAIL_MIN_WIDTH);
        // Free to be shorter than its content, which then runs off its foot. The split
        // holds each pane in a centring wrapper, so a panel kept at its minimum - 500
        // pixels with the commit form showing, more beside a long message - stood out of
        // a shorter window at both ends, and its heading was the part cut off.
        rightTopDetails.setMinHeight(0);

        diffViewer = new DiffViewer();
        diffViewer.setRepository(gitService::getWorkTree);
        diffViewer.setFileDiff("", "");
        diffViewer.setOnEditFile(this::openInEditor);
        diffViewer.setOnSaveFile(this::saveEditedFile);
        diffViewer.setOnSaveAndStage(this::saveAndStageEditedFile);
        diffViewer.setFileContentSupplier(gitService::getWorkingTreeBytes);
        diffViewer.setReaderRepository(gitService);
        // Drafts are keyed by repository, which the viewer has no way to know. Only the
        // main viewer gets one: FileHistoryView's embedded copy edits the same working
        // copy, and two stores fighting over one file is a bug rather than a backup.
        diffViewer.setDraftStore(new DiffViewer.DraftStore() {
            @Override
            public void save(String path, String text, String baseline, String encoding) {
                com.jfx.git.core.EditorDrafts.save(
                        gitService.getWorkTree(), path, text, baseline, encoding);
            }

            @Override
            public java.util.Optional<com.jfx.git.core.EditorDrafts.Draft> find(String path) {
                return com.jfx.git.core.EditorDrafts.find(gitService.getWorkTree(), path);
            }

            @Override
            public void discard(String path) {
                com.jfx.git.core.EditorDrafts.discard(gitService.getWorkTree(), path);
            }
        });
        // The supplier answers with empty bytes for both an empty file and a missing one,
        // and "Edit working copy" has to tell those apart before it offers to edit
        // nothing. See DiffViewer.loadWorkingCopyForEditing.
        diffViewer.setWorkingCopyExistsCheck(path -> {
            java.io.File workTree = gitService.getWorkTree();
            return workTree != null && new java.io.File(workTree, path).isFile();
        });

        rightPanelManager = new RightPanelManager(operations, rightTopDetails, diffViewer, this::currentBranch);
        rightPanelManager.setOnOperationState(this::showOperationStrip);

        // Blame and History open the same view; they differ only in the initial toggle.
        diffViewer.setOnBlame(p -> openFileHistory(p, true));
        diffViewer.setOnHistory(p -> openFileHistory(p, false));
        rightPanelManager.setOnShowFileHistory(this::openFileHistory);
        rightPanelManager.setViewHost(this::openView);
        // Lets the parent links in the details pane move the graph's selection.
        rightPanelManager.setOnSelectCommit(this::selectCommitByHash);

        // Stepping into a submodule opens it as a repository in its own tab.
        sidebarPane = new SidebarPane(operations, app::openRepository,
                this::selectCommitByHash,
                // Hovering a branch in the sidebar fades the commits it does not contain,
                // the same preview its ref chip gives in the graph. The sidebar is where
                // a branch you have not found on the page yet is listed, so it is the
                // more useful of the two places to ask the question from.
                name -> graphHighlight.hoverBranchNamed(name));
        // The toolbar shows what the sidebar's load already read, instead of reading it again.
        sidebarPane.setOnRevealStash(this::revealStash);
        sidebarPane.setViewHost(this::openView);
        sidebarPane.setOnRevealHiddenRef(hash -> revealCommit(hash, true));
        // The branch from the snapshot the sidebar drew from: the graph page's.
        sidebarPane.setOnLoaded((locals, refs, tracking, stashes, originUrl) -> {
            toolBar.applyBranchState(locals, refs.currentBranch(), tracking, stashes, originUrl);
            this.branchName = refs.headLabel();
            this.tracking = tracking == null ? new int[2] : tracking;
            updateTabBadge();
            updateWindowTitle();
        });

        // Multiple selection drives squash, compare and multi-commit cherry-pick — and,
        // since diff-on-selection (§3.5), the detail panel itself: two or more commits
        // selected shows their end-to-end comparison. One listener sees the whole
        // selection so routing and fading can never disagree about what is chosen.
        commitListView.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        commitListView.getSelectionModel().getSelectedItems()
                .addListener((javafx.collections.ListChangeListener<GitGraphRow>) c ->
                        routeSelection());

        // Sidebar and graph share the left region.
        SplitPane browseSplit = new SplitPane(sidebarPane, centre);
        browseSplit.setOrientation(Orientation.HORIZONTAL);
        SplitPane.setResizableWithParent(sidebarPane, false);

        // Opening a file lays the diff over the whole left region - sidebar and graph
        // included - which is how commercial clients give the diff its width, rather than
        // squeezing it into the right-hand column.
        StackPane leftRegion = new StackPane(browseSplit, diffViewer);
        // Was 0, which let the graph and the diff be squeezed to nothing between two
        // panels that both have floors of their own - drag either divider far enough and
        // the middle simply disappeared. This is the sidebar's floor plus the graph's; the
        // window's own minimum is the sum of the three (minimumSize), so the dividers stop
        // rather than the content vanishing.
        //
        // It was 420 - less than the graph's fixed columns needed beside the sidebar - and
        // the split inside, which will not go below its own minimum, overflowed it. This
        // pane centred the overflow, which slid the sidebar off the window's left edge.
        // The floor now covers the graph's columns, and should anything still overflow -
        // a ref column dragged wide - it runs off the right, under the details, instead.
        leftRegion.setMinWidth(SidebarPane.MIN_WIDTH + SPLIT_DIVIDER + graphFloor());
        StackPane.setAlignment(browseSplit, Pos.TOP_LEFT);
        diffViewer.setVisible(false);
        diffViewer.setManaged(false);
        diffViewer.filePathProperty().addListener((obs, old, path) -> {
            boolean showDiff = path != null && !path.isEmpty();
            boolean wasShowing = old != null && !old.isEmpty();
            if (showDiff != wasShowing) {
                // What replaces what is the point of the motion: the file arriving over
                // the history, the history coming back.
                Motion.fadeIn(showDiff ? diffViewer : browseSplit);
            }
            diffViewer.setVisible(showDiff);
            diffViewer.setManaged(showDiff);
            browseSplit.setVisible(!showDiff);
            browseSplit.setManaged(!showDiff);
            if (!showDiff) {
                // Otherwise re-clicking the same, still-selected row after closing the
                // viewer does nothing: its selectedItemProperty would not change.
                rightPanelManager.clearFileSelectionAfterClose();
                // Focus was inside the viewer that just disappeared. Left there, it lands
                // nowhere, and the next key - J, K, Esc - does nothing; the history is
                // what the user came back to, so that is where the keyboard goes.
                javafx.scene.Scene scene = diffViewer.getScene();
                javafx.scene.Node focus = scene == null ? null : scene.getFocusOwner();
                if (focus == null || isInside(focus, diffViewer)) {
                    commitListView.requestFocus();
                }
            }
        });

        SplitPane mainSplit = new SplitPane(leftRegion, rightTopDetails);
        mainSplit.setOrientation(Orientation.HORIZONTAL);
        SplitPane.setResizableWithParent(rightTopDetails, false);

        // File history takes over the entire content area - including the right-hand
        // detail panel - the way commercial clients present it.
        this.browseArea = mainSplit;
        contentStack = new StackPane(mainSplit);
        // Free to be smaller than what it holds would like, and clipped to what it is
        // given. The details panel would rather be taller than most windows - a long
        // commit message holds its height - and that minimum passed straight up through
        // the split and the tab, which then stood taller than the window: the status bar
        // went off the bottom, at ordinary window sizes as well as small ones. Now the
        // bars keep their places and the panel's foot is cut off instead.
        //
        // Across, the split keeps its floor (minimumSize) and runs off the right, so in a
        // window narrower than that - one held to a screen too small for the floor - the
        // toolbar still fits the window and drops its captions rather than its buttons.
        mainSplit.setMinHeight(0);
        contentStack.setMinSize(0, 0);
        StackPane.setAlignment(mainSplit, Pos.TOP_LEFT);
        javafx.scene.shape.Rectangle contentClip = new javafx.scene.shape.Rectangle();
        contentClip.widthProperty().bind(contentStack.widthProperty());
        contentClip.heightProperty().bind(contentStack.heightProperty());
        contentStack.setClip(contentClip);
        root.setCenter(contentStack);
        // Notifications float over the bottom-right of the tab, above the status bar.
        toastHost = new ToastHost();
        StackPane layered = new StackPane(root, toastHost);
        StackPane.setAlignment(toastHost, javafx.geometry.Pos.BOTTOM_RIGHT);
        StackPane.setMargin(toastHost, new javafx.geometry.Insets(0, UiScale.EDGE, UiScale.SECTION * 2, 0));
        statusBar.setToastHost(toastHost);
        com.jfx.git.core.IntegrationEvents.gitLoginRefusedProperty().addListener(
                new javafx.beans.value.WeakChangeListener<>(gitLoginRefusedListener));
        setContent(layered);

        this.browseSplit = browseSplit;
        this.mainSplit = mainSplit;
        this.detailPanel = rightTopDetails;

        // Size both dividers in pixels rather than fractions, from the window they are
        // in: a width someone dragged to if there is one, otherwise a share of the window
        // within limits. They were a fixed 250 and 430, which is fine on a wide screen
        // and leaves the graph 420 of 1100 pixels on a laptop.
        onFirstLayout(mainSplit, () -> setDividerFromRightWidth(mainSplit,
                detailWidthFor(mainSplit.getWidth())));
        onFirstLayout(browseSplit, () -> setDividerFromLeftWidth(browseSplit,
                sidebarWidthFor(mainSplit.getWidth() > 0 ? mainSplit.getWidth() : browseSplit.getWidth())));

        // A released divider is a width someone chose. One filter on the outer pane sees
        // both dividers, since the inner pane is inside it. Dragging is the only thing
        // that saves: the positions also move when the window is resized, and that is
        // nobody's decision.
        mainSplit.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
            if (onDivider(e.getTarget())) {
                rememberPanelWidths();
            }
        });
    }

    /** The sidebar's width in a window {@code window} pixels wide, when nobody has set one. */
    static double defaultSidebarWidth(double window) {
        return Math.max(210, Math.min(280, window * 0.18));
    }

    /** The details panel's width in a window {@code window} pixels wide, when nobody has set one. */
    static double defaultDetailWidth(double window) {
        return Math.max(360, Math.min(480, window * 0.32));
    }

    private static double sidebarWidthFor(double window) {
        double saved = com.jfx.git.core.AppPreferences.layoutSidebarWidth();
        // A remembered width from a larger window must not swallow a smaller one.
        return saved > 0 ? Math.min(saved, window * 0.4) : defaultSidebarWidth(window);
    }

    private static double detailWidthFor(double window) {
        double saved = com.jfx.git.core.AppPreferences.layoutDetailWidth();
        return saved > 0 ? Math.min(saved, window * 0.6) : defaultDetailWidth(window);
    }

    private static boolean onDivider(javafx.event.EventTarget target) {
        for (javafx.scene.Node node = target instanceof javafx.scene.Node n ? n : null;
                node != null; node = node.getParent()) {
            if (node.getStyleClass().contains("split-pane-divider")) {
                return true;
            }
        }
        return false;
    }

    /** Stores the two panels' widths as they stand; a collapsed panel is not a width. */
    private void rememberPanelWidths() {
        if (sidebarPane.isVisible() && sidebarPane.getWidth() > 0) {
            com.jfx.git.core.AppPreferences.setLayoutSidebarWidth(sidebarPane.getWidth());
        }
        if (detailPanel.isVisible() && detailPanel.getWidth() > 0) {
            com.jfx.git.core.AppPreferences.setLayoutDetailWidth(detailPanel.getWidth());
        }
    }

    // ------------------------------------------------------------------
    // Actions the keyboard map drives
    // ------------------------------------------------------------------

    /** Shows the two-way diff between two commits picked in the graph. */
    private void compareCommits(com.jfx.git.model.CommitInfo from, com.jfx.git.model.CommitInfo to) {
        rightPanelManager.showComparison(from, to);
    }

    /**
     * Rebuilds the graph after a column was shown or hidden.
     *
     * <p>The header is replaced rather than updated: its titles and its column
     * constraints are both fixed when it is constructed, and reaching in to change them
     * from outside would be a second place that has to know the column layout.
     * {@code refresh()} then discards every realised cell, so the rows come back from the
     * cell factory built against the new set.
     */
    private void rebuildGraphColumns() {
        if (graphHeaderRow == null) {
            return;
        }
        var header = new com.jfx.git.graph.GraphHeader(
                com.jfx.git.graph.GraphDensity.current(), graphMetrics);
        header.alignWith(commitListView);
        // The replacement inherits the hook, which is what lets the callback live on the
        // header instead of on the class - and so lets two open repositories each rebuild
        // their own graph.
        header.setOnColumnsChanged(this::rebuildGraphColumns);
        header.setAuthorFilter(authorFilterModel());
        graphHeaderRow.getChildren().set(0, header);

        // Replacing the factory, not refresh(). A cell reads the column set once, in its
        // constructor, and refresh() only re-runs updateItem on the cells that already
        // exist - so it repopulated the very columns the user had just hidden and left
        // them on screen under a header that had correctly dropped their titles.
        // Assigning the factory discards every realised cell and builds new ones.
        commitListView.setCellFactory(graphCellFactory());
    }

    /**
     * Builds graph rows.
     *
     * <p>A method rather than a lambda at the one call site because it has two: the
     * initial build, and every column change — which works by replacing the factory.
     */
    /**
     * Recomputes which commits the selection makes relevant, and repaints.
     *
     * <p>{@code refresh()} is the right call here, unlike for a column change: the fade is
     * read in {@code updateItem}, which is exactly what refresh re-runs. A column is read
     * in the cell's constructor, which is why that one has to replace the factory instead.
     *
     * <p>Synthetic rows are left out — the WIP node and stashes are not commits and have
     * no ancestry to trace, so selecting one clears the fade rather than fading the lot.
     */
    /**
     * Recomputes which commits the selection makes relevant.
     *
     * <h2>Neither deferred nor refreshed</h2>
     * Two earlier attempts were both wrong, and in opposite directions. Calling
     * {@code commitListView.refresh()} inside the selection change rebuilds cells while
     * the model's indices are half updated. Deferring that refresh with {@code runLater}
     * fixed the churn and introduced a worse fault: the selection repaints in the pulse
     * the click happened, the fade in the pulse after it, so for one frame a row that
     * should have receded was still at full brightness — which is what a user sees as an
     * unrelated commit flashing.
     *
     * <p>So this only updates the model, synchronously. Every live row is listening to
     * {@link com.jfx.git.graph.GraphHighlight#revisionProperty()} and re-applies its own
     * fade, in this same pulse, without the virtual flow being touched at all. Nothing is
     * ever a frame out of step because nothing waits for a frame.
     *
     * <p>Synthetic rows are left out — the WIP node and stashes are not commits and have
     * no ancestry to trace, so selecting one clears the fade rather than fading the lot.
     */
    /**
     * Routes the current selection to the right detail view.
     *
     * <p>One row behaves exactly as it always did (details, staging area, stash). Two or
     * more real commits show their end-to-end comparison without a context-menu detour.
     * A multi-selection that includes synthetic rows (WIP, stashes, load-more) has no
     * sensible meaning as a group, so it falls back to the focused row's behaviour —
     * which also keeps shift-click ranges that happen to cross the WIP node working.
     */
    private void routeSelection() {
        var rows = commitListView.getSelectionModel().getSelectedItems();
        updateHighlight();

        if (rows.isEmpty()) return;
        GitGraphRow focused = commitListView.getSelectionModel().getSelectedItem();

        if (rows.size() > 1) {
            boolean allCommits = rows.stream().allMatch(r -> r != null && r.getCommit() != null);
            if (allCommits) {
                // In the graph's own order, top row first: that, not the commits' dates,
                // is what says which end of the comparison is the older one.
                var items = commitListView.getItems();
                rightPanelManager.showComparison(
                        commitListView.getSelectionModel().getSelectedIndices().stream()
                                .sorted()
                                .map(i -> items.get(i).getCommit())
                                .toList());
                return;
            }
            if (focused == null) return;
        }

        if (focused == null) focused = rows.get(rows.size() - 1);
        if (focused.isLoadMore()) {
            loadMoreCommits();
        } else if (focused.isWipNode()) {
            rightPanelManager.showStagingArea();
        } else if (focused.isStash()) {
            rightPanelManager.showStashDetails(focused.getStash());
        } else if (focused.getCommit() != null) {
            rightPanelManager.showCommitDetails(focused.getCommit());
            prefetchNeighbours();
        }
    }

    // ------------------------------------------------------------------
    // Keyboard navigation (§3.2) — J/K step, H/L walk edges, Shift+J/K jump
    // between named points, Ctrl+Home/End run to the ends. All route through the
    // selection model, so the fade, routing and detail panel follow for free.
    // ------------------------------------------------------------------

    /** The commit under the selection, or null on a synthetic or empty selection. */
    private com.jfx.git.model.CommitInfo selectedCommit() {
        GitGraphRow row = commitListView.getSelectionModel().getSelectedItem();
        return row == null ? null : row.getCommit();
    }

    private void selectAndScroll(int index) {
        commitListView.getSelectionModel().clearAndSelect(index);
        commitListView.scrollTo(index);
    }

    /**
     * Moves the selection {@code delta} rows of real commit, skipping synthetic rows
     * — stepping onto the WIP node or the load-more stub would fire their side
     * effects (a staging-area rebuild, a history walk) as a side effect of navigating,
     * which is never what a key press means.
     *
     * @param delta +1 moves down (older), -1 up (newer)
     */
    public void navigateRows(int delta) {
        var items = commitListView.getItems();
        if (items.isEmpty()) return;
        int i = commitListView.getSelectionModel().getSelectedIndex();
        if (i < 0) i = delta > 0 ? -1 : items.size();
        int j = i;
        while (true) {
            j += delta;
            if (j < 0 || j >= items.size()) return;
            if (items.get(j).getCommit() != null) {
                selectAndScroll(j);
                return;
            }
        }
    }

    /** H: to the first parent of the selected commit, however far down that is. */
    public void navigateToParent() {
        var commit = selectedCommit();
        if (commit == null || commit.getParentHashes().isEmpty()) return;
        selectByHash(commit.getParentHashes().get(0), true);
    }

    /** L: to the nearest child of the selected commit above it in the graph. */
    public void navigateToChild() {
        var commit = selectedCommit();
        if (commit == null) return;
        String hash = commit.getHash();
        var items = commitListView.getItems();
        for (int j = commitListView.getSelectionModel().getSelectedIndex() - 1; j >= 0; j--) {
            var candidate = items.get(j).getCommit();
            if (candidate != null && candidate.getParentHashes().contains(hash)) {
                selectAndScroll(j);
                return;
            }
        }
    }

    /**
     * Shift+J / Shift+K: jump between commits that carry a ref decoration.
     *
     * <p>The plan called these "within-branch jumps", but per-row branch membership is
     * not data the page carries, and approximating it would guess. Jumping between
     * decorated commits is the same gesture in spirit — skip the anonymous middle of
     * history, land somewhere with a name — and is honest about what it does.
     *
     * @param down true jumps towards older history
     */
    public void navigateToNextRef(boolean down) {
        var items = commitListView.getItems();
        if (items.isEmpty()) return;
        int i = commitListView.getSelectionModel().getSelectedIndex();
        int j = i;
        while (true) {
            j += down ? 1 : -1;
            if (j < 0 || j >= items.size()) return;
            GitGraphRow row = items.get(j);
            if (row.getCommit() != null && !row.getCommit().getRefs().isEmpty()
                    && j != i) {
                selectAndScroll(j);
                return;
            }
        }
    }

    /** Ctrl+Home / Ctrl+End: the newest and oldest loaded commits. */
    public void navigateToEdge(boolean newest) {
        var items = commitListView.getItems();
        if (newest) {
            for (int j = 0; j < items.size(); j++) {
                if (items.get(j).getCommit() != null) {
                    selectAndScroll(j);
                    return;
                }
            }
            return;
        }
        for (int j = items.size() - 1; j >= 0; j--) {
            if (items.get(j).getCommit() != null) {
                selectAndScroll(j);
                return;
            }
        }
    }

    /** The fade driver, for tests that assert what a search or selection dimmed. */
    com.jfx.git.graph.GraphHighlight graphHighlightForTest() {
        return graphHighlight;
    }

    /**
     * Selecting a commit fades nothing.
     *
     * <h2>Why this used to walk the ancestry, and why it no longer does</h2>
     * Clicking a commit used to dim everything outside its ancestry. Something is almost
     * always selected - the working-tree row is, on open - so that fade was the graph's
     * resting state rather than an answer to a question, and it is not what commercial
     * clients do: a commit selected there leaves every other subject at full brightness.
     *
     * <p>The fade now has exactly one trigger, the pointer resting on a branch, which is
     * what makes it read as an answer. This clears whatever a search left behind, so the
     * graph returns to showing everything.
     */
    private void updateHighlight() {
        graphHighlight.clear();
    }

    // ------------------------------------------------------------------
    // Filtering by author
    // ------------------------------------------------------------------

    /** Whether {@code row} survives the current author filter. */
    private boolean matchesAuthor(GitGraphRow row) {
        if (authorFilter == null) {
            return true;
        }
        var commit = row.getCommit();
        return commit != null && authorFilter.equals(commit.getAuthorName());
    }

    /**
     * Every author on the loaded page, in alphabetical order.
     *
     * <p>Taken from the page rather than the repository: the menu offers what the graph
     * can actually narrow to, so choosing a name can never produce an empty graph.
     */
    List<String> authorsOnPage() {
        return history.rows().stream()
                .map(GitGraphRow::getCommit)
                .filter(java.util.Objects::nonNull)
                .map(com.jfx.git.model.CommitInfo::getAuthorName)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /**
     * The header's view of the author filter.
     *
     * <p>A fresh one per header rather than a stored field: the header is replaced
     * whenever the columns change, and each replacement reads the tab's live state
     * through this rather than a copy taken when it was built.
     */
    private com.jfx.git.graph.GraphHeader.AuthorFilter authorFilterModel() {
        return new com.jfx.git.graph.GraphHeader.AuthorFilter() {
            @Override
            public List<String> authors() {
                return authorsOnPage();
            }

            @Override
            public String chosen() {
                return authorFilter;
            }

            @Override
            public void choose(String author) {
                filterByAuthor(author);
            }
        };
    }

    /** The author the graph is narrowed to, or null for everyone. */
    String authorFilter() {
        return authorFilter;
    }

    /** Narrows the graph to {@code author}, or widens it again when null. */
    void filterByAuthor(String author) {
        authorFilter = author == null || author.isBlank() ? null : author;
        applyFilter(searchField.getText());
    }

    /**
     * Remembers a dragged graph width, once the drag has finished.
     *
     * <p>On release rather than on every step. Writing the preference per lane crossed
     * put a file write in the middle of a drag, and rebuilding the list there flickered
     * the whole graph. The rows keep up on their own: each watches the lane width and
     * redraws itself, so the drag costs a repaint of what is on screen and nothing more.
     */
    private void laneBudgetCommitted() {
        com.jfx.git.core.AppPreferences.setGraphLaneBudget(graphMetrics.laneBudget());
    }
    /** The header currently in the strip; it is replaced whenever the columns change. */
    private com.jfx.git.graph.GraphHeader currentGraphHeader() {
        return (com.jfx.git.graph.GraphHeader) graphHeaderRow.getChildren().get(0);
    }

    private javafx.util.Callback<ListView<GitGraphRow>, javafx.scene.control.ListCell<GitGraphRow>>
            graphCellFactory() {
        return lv -> new GitGraphCell(operations,
                this::compareCommits, graphMetrics, graphHighlight, history::ghosts);
    }

    // ------------------------------------------------------------------
    // Tab identity
    // ------------------------------------------------------------------

    /**
     * Renames this tab, or clears the rename when {@code alias} is blank.
     *
     * <p>A blank name puts the folder's name back rather than leaving an empty tab, which
     * would be indistinguishable from every other empty tab in the strip.
     */
    public void setAlias(String alias) {
        this.alias = alias == null || alias.isBlank() ? null : alias.trim();
        applyTitle();
    }

    /** The user's name for this tab, or null when it still shows the folder's. */
    public String alias() {
        return alias;
    }

    /** The repository folder's name, whatever the tab is currently called. */
    public String folderTitle() {
        return folderTitle;
    }

    private void applyTitle() {
        setText(alias != null ? alias : folderTitle);
        updateWindowTitle();
    }

    /**
     * The commit graph itself.
     *
     * <p>Package-private, and here for the robot tests: driving the column menu is only
     * meaningful if the rows underneath can be inspected afterwards, and the bug this
     * seam exists to catch — a column that left the header but not the rows — is
     * invisible from outside the list.
     */
    ListView<GitGraphRow> graphList() {
        return commitListView;
    }

    /** The stopped-operation strip over the graph; for the robot tests. */
    Button operationStrip() {
        return operationStrip;
    }

    /** Shows the strip over the graph while an operation is stopped, and takes it away after. */
    private void showOperationStrip(RightPanelManager.OperationSummary summary) {
        boolean stopped = summary.stopped();
        if (stopped) {
            String text = OperationHeadline.graphBanner(summary.operation(), summary.headline(),
                    summary.conflicts());
            operationStrip.setText(text);
            operationStrip.setAccessibleText(text);
            operationStrip.setTooltip(new javafx.scene.control.Tooltip(text
                    + "\nClick to open the uncommitted changes, where the conflicts are resolved."));
        }
        show(operationStrip, stopped);
    }

    /**
     * Selects the uncommitted-changes row, which brings up the staging panel with the
     * conflicts in it. A stop with a clean tree - an edit step - has no such row, and the
     * panel's own banner already says what to do.
     */
    private void selectWorkingTreeRow() {
        var items = commitListView.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isWipNode()) {
                boolean already = commitListView.getSelectionModel().getSelectedIndices().equals(List.of(i));
                commitListView.getSelectionModel().clearAndSelect(i);
                commitListView.scrollTo(i);
                commitListView.requestFocus();
                if (already) {
                    // No selection change to drive it, and the user asked to see it.
                    rightPanelManager.showStagingArea();
                }
                return;
            }
        }
    }

    /**
     * Puts {@code rows} into the graph as a freshly loaded page would.
     *
     * <p>Package-private, for the robot tests. Setting the list's items alone is not
     * enough and quietly produces a half-built graph: the ancestry walk reads the
     * history window's rows — the unfiltered page, so that an ancestor hidden by the
     * commit filter still counts as related — and a test that only filled the list left
     * them empty, so nothing ever faded.
     */
    void showRows(List<GitGraphRow> rows) {
        // No page load has seeded the branch on this path, so it is read here, on the
        // FX thread - as part of building the test's graph, which is what the scope says.
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            currentBranch();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        history.showRows(rows);
        // The same three things a real page load does. Leaving the metrics out made the
        // graph believe every page was one lane wide, so a test could drag the graph's
        // edge and measure nothing moving.
        graphMetrics.update(history.rows(), currentBranch());
        commitListView.getItems().setAll(history.rows());
        // Filled directly, so the filter must not think the list still holds what it set.
        filledFrom = null;
    }

    /**
     * Esc: closes the open file and goes back to the history, asking first when the file
     * has unsaved edits. Does nothing when no file is open.
     */
    public void closeDiff() {
        // Esc closes the find bar first, when one is open over the file.
        if (diffViewer.isVisible() && diffViewer.closeFind()) {
            return;
        }
        diffViewer.close();
    }

    /**
     * Ctrl+F (and Ctrl+H with {@code replace}): find in the file on screen, or - with no
     * file open - put the caret in the graph's search box.
     */
    public void find(boolean replace) {
        DiffViewer viewer = viewerForKeys();
        if (viewer != null) {
            viewer.showFind(replace);
        } else if (!replace) {
            focusSearch();
        }
    }

    /** Ctrl+G: go to a line of the file on screen. */
    public void goToLine() {
        DiffViewer viewer = viewerForKeys();
        if (viewer != null) {
            viewer.showGoToLine();
        }
    }

    /**
     * The viewer a find or go-to-line key is meant for: the one holding the focus - the
     * main diff, or the one embedded in file history - or else the main one if it is
     * showing a file. {@code null} when no file is on screen.
     */
    private DiffViewer viewerForKeys() {
        javafx.scene.Scene scene = getTabPane() == null ? null : getTabPane().getScene();
        javafx.scene.Node focus = scene == null ? null : scene.getFocusOwner();
        for (javafx.scene.Node n = focus; n != null; n = n.getParent()) {
            if (n instanceof DiffViewer viewer && viewer.isVisible()) {
                return viewer;
            }
        }
        return diffViewer.isVisible() ? diffViewer : null;
    }

    /** The main file viewer, for tests. */
    DiffViewer diffViewer() {
        return diffViewer;
    }

    /** The graph's search box, for tests. */
    TextField searchField() {
        return searchField;
    }

    /** F7 and Shift+F7: the open diff's next or previous change. */
    public void stepChange(int delta) {
        if (diffViewer.isVisible()) {
            diffViewer.stepChange(delta);
        }
    }

    private static boolean isInside(javafx.scene.Node node, javafx.scene.Node ancestor) {
        for (javafx.scene.Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) return true;
        }
        return false;
    }

    /** Puts the caret in the graph filter box. */
    public void focusSearch() {
        searchField.requestFocus();
        searchField.selectAll();
    }

    /**
     * Collapses or restores the branch sidebar.
     *
     * <p>The divider position is remembered so restoring puts it back where the user had
     * it rather than at a default.
     */
    public void toggleSidebar() {
        if (sidebarPane.isManaged()) {
            savedSidebarDivider = browseSplit.getDividerPositions()[0];
            sidebarPane.setVisible(false);
            sidebarPane.setManaged(false);
            browseSplit.setDividerPositions(0);
        } else {
            sidebarPane.setVisible(true);
            sidebarPane.setManaged(true);
            browseSplit.setDividerPositions(savedSidebarDivider > 0 ? savedSidebarDivider : 0.25);
        }
    }

    /**
     * Puts the caret in the sidebar's filter: Ctrl+Alt+F. A hidden sidebar is shown
     * first - focus asked of a node that is not showing is refused, and the shortcut
     * did nothing at all.
     */
    public void focusSidebarFilter() {
        if (!sidebarPane.isManaged()) toggleSidebar();
        sidebarPane.focusFilter();
    }

    /** Collapses or restores the commit detail panel on the right. */
    public void toggleDetailPanel() {
        if (detailPanel.isManaged()) {
            savedDetailDivider = mainSplit.getDividerPositions()[0];
            detailPanel.setVisible(false);
            detailPanel.setManaged(false);
            mainSplit.setDividerPositions(1);
        } else {
            detailPanel.setVisible(true);
            detailPanel.setManaged(true);
            mainSplit.setDividerPositions(savedDetailDivider > 0 ? savedDetailDivider : 0.7);
        }
    }

    public RightPanelManager getRightPanel() {
        return rightPanelManager;
    }

    /**
     * Asks for a patch file and how to apply it, then applies it to this repository.
     * For the File menu and the command palette.
     */
    public void promptApplyPatch() {
        new PatchActions(operations).applyPatch(
                getTabPane() == null || getTabPane().getScene() == null
                        ? null : getTabPane().getScene().getWindow());
    }

    public StatusBar getStatusBar() {
        return statusBar;
    }

    public SidebarPane getSidebar() {
        return sidebarPane;
    }

    public ListView<GitGraphRow> getCommitList() {
        return commitListView;
    }

    /**
     * Ctrl+Shift+F: search in files, in this tab - the same view, with its last results,
     * each time. See {@link com.jfx.git.ui.views.SearchInFilesView}.
     */
    public void openSearchInFiles() {
        if (searchInFiles == null) {
            searchInFiles = new com.jfx.git.ui.views.SearchInFilesView(gitService,
                    () -> {
                        GitGraphRow row = commitListView.getSelectionModel().getSelectedItem();
                        return row == null || row.isWipNode() ? null : row.getCommit();
                    },
                    this::openFileAt);
        }
        com.jfx.git.ui.views.SearchInFilesView view = searchInFiles;
        boolean onTop = currentView() == view;
        openView(com.jfx.git.ui.views.SearchInFilesView.class, () -> view);
        if (onTop && view.focusTarget() != null) {
            view.focusTarget().requestFocus();
        }
    }

    /**
     * Opens {@code path} as a whole file - from the working tree, or as {@code revOrNull}
     * has it - with the caret on {@code line}: where a search result leads.
     *
     * <p>The views give way first, as Back would: the file opens where files open, over
     * the graph. A working-tree file opens editable, as from the file browser; one from a
     * commit is read-only.
     */
    public void openFileAt(String revOrNull, String path, int line) {
        closeViews();
        GitTaskRunner.query(gitService,
                () -> revOrNull == null ? gitService.getWorkingTreeBytes(path)
                        : gitService.getFileBytesAtCommit(revOrNull, path),
                bytes -> {
                    // Whatever list selection opened the last file no longer describes this one.
                    rightPanelManager.clearFileSelectionAfterClose();
                    diffViewer.setExternalDiffTarget(null);
                    // A result at a revision is that commit's file, under that commit's name.
                    diffViewer.setRenameLookup(revOrNull == null ? null
                            : RestoreDestination.lookupFor(gitService, revOrNull, statusBar::error));
                    diffViewer.setRevisionReader(revOrNull == null ? null
                            : other -> gitService.getFileBytesAtCommit(revOrNull, other));
                    diffViewer.setEditable(revOrNull == null);
                    diffViewer.setComparedSides(revOrNull == null
                            ? DiffViewer.DiffSides.WORKING_TREE
                            : new DiffViewer.DiffSides(null, null, shortRevision(revOrNull)));
                    diffViewer.showFileBytes(path, bytes);
                    diffViewer.revealLine(line);
                },
                statusBar::error);
    }

    private static String shortRevision(String revision) {
        return revision.length() == 40 && revision.chars().allMatch(c -> Character.digit(c, 16) >= 0)
                ? revision.substring(0, 7) : revision;
    }

    /**
     * F5: the view in front loads what it shows again, if it has anything to load;
     * otherwise - and with the graph in front - the repository is refreshed.
     */
    public void refreshFromKeyboard() {
        RepoView top = currentView();
        if (top != null && top.refreshView()) {
            return;
        }
        refresh();
    }

    /**
     * Opens the file-history view over the whole content area.
     *
     * @param blame whether to start with blame annotations rather than the diff
     */
    public void openFileHistory(String path, boolean blame) {
        closeFileHistory();
        openHistory = new FileHistoryView(operations, path, blame, this::closeFileHistory, this::openInEditor);
        browseArea.setVisible(false);
        browseArea.setManaged(false);
        contentStack.getChildren().add(openHistory);
    }

    private void closeFileHistory() {
        if (openHistory != null) {
            contentStack.getChildren().remove(openHistory);
            openHistory = null;
        }
        if (views.isEmpty()) {
            browseArea.setVisible(true);
            browseArea.setManaged(true);
        }
    }

    // ------------------------------------------------------------------
    // Views: full screens in place of the graph
    // ------------------------------------------------------------------

    /**
     * Shows {@code view} over this tab's content area, on top of any view already open -
     * its Back returns to that one, the last Back to the graph. See {@link RepoView}.
     *
     * <p>The toolbar and the status bar stay; the sidebar, graph and detail panel give
     * way, as they do for file history.
     *
     * <p>A view already open comes back to the top instead of being stacked twice, and the
     * ones opened over it close - the toolbar's Pull requests pressed while its list is
     * under a review returns to the list, as that review's Back would.
     */
    public void showView(RepoView view) {
        closeFileHistory();
        if (views.contains(view)) {
            while (views.peekLast() != view) {
                closedQuietly(views.pollLast());
            }
            renderTopView();
            return;
        }
        views.addLast(view);
        renderTopView();
        Motion.fadeIn(viewHost);
    }

    /**
     * Shows the view {@code create} makes, unless a view of {@code kind} is already open -
     * then that one is brought to the top of the stack, as it was left. See
     * {@link RepoViewHost}.
     *
     * <p>Brought forward rather than recreated, and without closing the views above it:
     * a bisect run or an LFS fetch in one of them keeps going, and Back from the view
     * returns to whichever was on top before.
     *
     * @return the view now showing
     */
    public RepoView openView(Class<? extends RepoView> kind, java.util.function.Supplier<? extends RepoView> create) {
        RepoView open = views.stream().filter(kind::isInstance).findFirst().orElse(null);
        if (open == null) {
            RepoView view = create.get();
            showView(view);
            return view;
        }
        closeFileHistory();
        if (open != views.peekLast()) {
            views.remove(open);
            views.addLast(open);
            renderTopView();
        }
        return open;
    }

    /** Closes the top view, returning to the one below it or to the graph. */
    public void closeView() {
        RepoView top = views.pollLast();
        if (top == null) return;
        closedQuietly(top);
        renderTopView();
    }

    /** Closes every view, back to the graph. */
    public void closeViews() {
        while (!views.isEmpty()) {
            closedQuietly(views.pollLast());
        }
        renderTopView();
    }

    /** The view on top, or null when the graph is showing. */
    public RepoView currentView() {
        return views.peekLast();
    }

    /** Closes {@code view} wherever it is in the stack, with everything opened from it. */
    public void closeView(RepoView view) {
        if (!views.contains(view)) return;
        while (!views.isEmpty()) {
            RepoView top = views.pollLast();
            closedQuietly(top);
            if (top == view) break;
        }
        renderTopView();
    }

    private static void closedQuietly(RepoView view) {
        try {
            view.closed();
        } catch (RuntimeException e) {
            java.util.logging.Logger.getLogger(RepoTab.class.getName())
                    .log(java.util.logging.Level.WARNING, "A view failed while closing", e);
        }
    }

    /** Puts the top view on screen, or the graph back when there is none. */
    private void renderTopView() {
        if (viewHost != null) {
            contentStack.getChildren().remove(viewHost);
            viewHost = null;
        }
        RepoView top = views.peekLast();
        if (top == null) {
            browseArea.setVisible(true);
            browseArea.setManaged(true);
            if (getTabPane() != null && isSelected()) commitListView.requestFocus();
            return;
        }
        browseArea.setVisible(false);
        browseArea.setManaged(false);
        viewHost = viewFrame(top);
        contentStack.getChildren().add(viewHost);
        // Focus moves into the view, as it did into the dialog it replaces: Escape and the
        // keyboard act on what is in front, not on the graph hidden behind it - onto its
        // filter or query field when it has one, so it can be typed into at once.
        javafx.scene.Node target = top.focusTarget();
        if (target != null) {
            target.requestFocus();
        } else {
            viewHost.requestFocus();
        }
    }

    /**
     * The header bar - Back, the title, the view's own actions - over the view's content.
     * Back names where it goes, so a stack of views never leaves the user guessing.
     */
    private BorderPane viewFrame(RepoView view) {
        java.util.List<RepoView> stack = new java.util.ArrayList<>(views);
        String backTo = stack.size() > 1 ? stack.get(stack.size() - 2).title() : "graph";
        javafx.scene.control.Button back = new javafx.scene.control.Button("← Back to " + backTo);
        back.getStyleClass().add("btn-ghost");
        back.setOnAction(e -> closeView());
        javafx.scene.control.Label title = new javafx.scene.control.Label(view.title());
        title.getStyleClass().add("t-title");
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(UiScale.GAP, back, title, spacer);
        header.getChildren().addAll(view.actions());
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        header.getStyleClass().add("repo-view-header");

        StackPane body = new StackPane(view.content());
        body.getStyleClass().add("repo-view-body");
        BorderPane frame = new BorderPane(body);
        frame.setTop(header);
        frame.getStyleClass().add("repo-view");
        // Escape goes back, as it closed the dialog these were - unless something inside
        // the view used it first (a field clearing itself, a menu closing).
        frame.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && !e.isConsumed()) {
                closeView();
                e.consume();
            } else if (e.getCode() == javafx.scene.input.KeyCode.F5 && !e.isConsumed()
                    && !e.isShortcutDown() && !e.isShiftDown() && !e.isAltDown()) {
                // The application's F5 stands aside while a text field has the focus, and a
                // view's filter usually has it; here F5 still reloads the view.
                if (view.refreshView()) e.consume();
            }
        });
        return frame;
    }

    /** Places the divider so the first pane is {@code pixels} wide. */
    private static void setDividerFromLeftWidth(SplitPane pane, double pixels) {
        double width = pane.getWidth();
        if (width > 0) {
            pane.setDividerPositions(Math.min(0.9, pixels / width));
        }
    }

    /** Places the divider so the last pane is {@code pixels} wide. */
    private static void setDividerFromRightWidth(SplitPane pane, double pixels) {
        double width = pane.getWidth();
        if (width > 0) {
            pane.setDividerPositions(Math.max(0.1, 1.0 - pixels / width));
        }
    }

    /** Runs {@code action} once the pane has a non-zero width. */
    private static void onFirstLayout(SplitPane pane, Runnable action) {
        pane.widthProperty().addListener(new javafx.beans.value.ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Number> obs,
                                Number oldWidth, Number newWidth) {
                if (newWidth.doubleValue() > 0) {
                    action.run();
                    pane.widthProperty().removeListener(this);
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Loading and refreshing
    // ------------------------------------------------------------------

    /**
     * Opens {@code dir} as a repository on a background thread, keeping the FX
     * thread free while JGit reads {@code .git/config} and indexes pack files.
     *
     * <p>{@code onSuccess} is called on the FX thread when the repository is ready.
     * {@code onError} receives a human-readable message if the open fails; the caller
     * is responsible for removing the tab and showing an alert in that case.
     */
    public void loadRepositoryAsync(File dir, Runnable onSuccess,
                                    java.util.function.Consumer<String> onError) {
        requestedDirectory = dir;
        // So a thread dump says whose work a stuck worker is doing.
        GitTaskRunner.nameLane(gitService, dir.getName());
        // Everything says "opening" until the repository is there: the tab, the graph,
        // the switcher, the details, the status bar.
        openingLabel.setText("Opening " + dir.getName() + "…");
        javafx.scene.control.ProgressIndicator spinner = new javafx.scene.control.ProgressIndicator();
        spinner.getStyleClass().add("tab-spinner");
        spinner.setPrefSize(12, 12);
        spinner.setMaxSize(12, 12);
        setGraphic(spinner);
        toolBar.setOpening(true);
        detailPanel.showLoading();
        statusBar.busy("Opening " + dir.getName());
        openingBusy = true;
        // The page this repository showed last time, drawn while the real one loads; see
        // GraphPageCache. Read and laid out on a thread of its own, so it can be on screen
        // before the open has even reached the front of the repository lane.
        GraphPageCache.load(dir, HistoryWindow.Loaded::of, cached -> showCachedPage(dir, cached));
        GitTaskRunner.query(gitService,
                () -> {
                    // openRepository always returns true or throws GitOperationException.
                    gitService.openRepository(dir);
                    // The override is keyed by the repository's root, not by whichever
                    // folder was picked: opening a subfolder must find the same setting
                    // the settings dialog stored against the repository.
                    File root = gitService.getWorkTree() != null ? gitService.getWorkTree() : dir;
                    // A repository that asked for a particular engine gets it, now that
                    // the tab knows which repository it is. Switching after opening
                    // rather than choosing before it, because the override is keyed by
                    // path and the tab's engine was built before the path was known.
                    applyEngineOverride(root);
                    return root;
                },
                root -> {
                    this.folderTitle = root.getName();
                    GitTaskRunner.nameLane(gitService, folderTitle);
                    String title = folderTitle;
                    applyTitle();
                    setTooltip(new javafx.scene.control.Tooltip(root.getAbsolutePath()));
                    startWatching();
                    statusBar.setBackend(gitService, this::switchEngine);
                    // From here on this bar's operations are attributable to a
                    // repository, which is what keeps several open tabs' activity apart.
                    statusBar.setLoggedRepository(title);
                    com.jfx.git.core.ActivityLog.note("Opened repository "
                            + root.getAbsolutePath());
                    // The interval can be this repository's own; it is only knowable now.
                    autoFetchScheduler.applyPreference();
                    snapshotScheduler.applyPreference();
                    toolBar.setOpening(false);
                    refreshAll();
                    seedSnapshot();
                    onSuccess.run();
                },
                error -> {
                    endOpeningBusy();
                    setGraphic(null);
                    toolBar.setOpening(false);
                    onError.accept(error);
                });
    }

    /**
     * Draws the page this repository showed when it was last open, until the real one
     * lands - which leaves it alone when its signature matches, and replaces it when the
     * repository changed in between.
     *
     * <p>Only while the tab is still opening the same folder; the history window draws it
     * only while nothing real has been - a cached page arriving after the live one would
     * put old rows back.
     */
    private void showCachedPage(File dir, HistoryWindow.Loaded cached) {
        if (!dir.equals(requestedDirectory)) return;
        history.showCached(cached);
    }

    /**
     * Records what the first load drew, so the first return to the window compares
     * against it. With nothing recorded, the first focus or tab switch after an open
     * found "a change" - the whole repository - and loaded everything a second time.
     * Queued after the refresh, so it is taken once that has been read.
     */
    private void seedSnapshot() {
        seedingSnapshot = true;
        GitTaskRunner.query(gitService, this::snapshotNow,
                snapshot -> {
                    seedingSnapshot = false;
                    if (lastSeen == null) lastSeen = snapshot;
                },
                error -> seedingSnapshot = false);
    }

    private void endOpeningBusy() {
        if (openingBusy) {
            openingBusy = false;
            statusBar.idle();
        }
    }

    /**
     * The first page is on screen: the loading states give way. A repository with no
     * commits shows the commit panel - the graph tells the reader to make one "from the
     * panel on the right", and that panel used to say "Select a commit" with nothing to select.
     */
    private void firstPageDrawn(boolean empty) {
        commitListView.setPlaceholder(emptyGraphBox);
        setGraphic(null);
        updateTabBadge();
        endOpeningBusy();
        if (empty && (searchField.getText() == null || searchField.getText().isBlank())) {
            rightPanelManager.showStagingArea();
        }
    }

    /**
     * Switches to this repository's own engine, if it asked for one that is not what the
     * tab was built with.
     *
     * <p>Runs on the worker, inside the open, so the tab is never briefly live on the
     * wrong engine. A failed switch is deliberately not fatal: the repository is open and
     * working on the engine it has, which is better than refusing to show it because a
     * stored preference named an engine this machine cannot provide.
     */
    private void applyEngineOverride(File dir) {
        var wanted = com.jfx.git.core.RepositoryPreferences.engine(dir).orElse(null);
        if (wanted == null) return;
        if (!com.jfx.git.core.GitBackendFactory.isEngineAvailable(wanted)) {
            LOGGER.info("Repository asked for " + wanted + ", which is not available here");
            return;
        }
        // Already the right engine: switching would close the repository this method was
        // called from the middle of opening, and open it again, for no change at all.
        // That is a full open-close-open cycle on every launch of such a repository.
        if (com.jfx.git.core.GitBackendFactory.engineOf(gitService) == wanted) return;
        GitResult switched = gitService.switchTo(wanted);
        if (!switched.isOk()) {
            LOGGER.info("Could not honour this repository's engine preference: "
                    + switched.getMessage());
        }
    }

    /**
     * Whether this repository's own settings choose its engine.
     *
     * <p>A global engine change has to leave such a tab alone: the setting was made for
     * this repository - often because the global choice does not work for it - and
     * switching it anyway lasted only until the next open, when the override applied
     * again. Read from what the tab already holds; no engine call.
     */
    public boolean hasEngineOverride() {
        File root = gitService.openedAt();
        return root != null && com.jfx.git.core.RepositoryPreferences.engine(root).isPresent();
    }

    /**
     * Rebuilds this tab's engine and reloads everything it feeds.
     *
     * <p>The swap happens on a worker thread because opening a repository reads packs and
     * config; the refresh that follows is posted back to the FX thread. Collaborators keep
     * the {@link SwitchableGitBackend} they were given, so none of them need rebuilding.
     */
    public void switchEngine(GitBackendFactory.BackendEngine engine) {
        operations.operation("Switching engine to " + engine.label(), OperationRunner.Refresh.EVERYTHING)
                .onResult(result -> {
                    statusBar.updateEngineBadge();
                    // The graph, sidebar and detail panel were all populated by the old
                    // engine; reload rather than trusting them to agree.
                    history.reset();
                })
                .run(() -> gitService.switchTo(engine));
    }

    /** Reloads everything this tab shows; safe to call from anywhere on the FX thread. */
    public void refresh() {
        refreshAll();
    }

    /**
     * What this tab last had reason to believe the repository looked like.
     *
     * <p>The staged and unstaged lists plus HEAD: between them they cover every external
     * change that would make the panel wrong - a stage or unstage from another client, an
     * edit from an editor, a commit or checkout from a terminal.
     */
    /**
     * What the repository looked like the last time this tab drew it.
     *
     * <p>Four independent generations, because a repository can change without any one of
     * them moving. Staging paths and HEAD alone - which is all this used to compare -
     * miss the two commonest cases outright: editing a file that was already modified
     * leaves the status letter at {@code M} and the path list identical, and a fetch that
     * only advances remote-tracking refs touches neither. Both left the panel showing
     * yesterday's repository and reporting nothing wrong.
     *
     * @param refs     every ref and what it points at, so a fetch or a new tag registers
     * @param index    {@code .git/index}'s own size and timestamp, so a stage or unstage
     *                 from elsewhere registers even when it leaves the same paths listed
     * @param worktree size and timestamp of each file currently listed as changed, so an
     *                 M-to-M content edit registers
     */
    private record RepoSnapshot(com.jfx.git.model.StagingState staging, String head,
                                String refs, String index, String worktree) { }

    private RepoSnapshot lastSeen;

    /** Hears changes made outside this application; null when the platform cannot watch. */
    private com.jfx.git.core.RepositoryWatcher watcher;

    /**
     * Starts hearing the repository change on disk - see {@link com.jfx.git.core.RepositoryWatcher}
     * for what it ignores. It only ever asks {@link #refreshIfChangedExternally}, the same
     * question a window focus asks, so a false alarm costs a status scan and nothing on
     * screen moves unless the repository did.
     */
    private void startWatching() {
        stopWatching();
        File root = gitService.getWorkTree();
        if (root == null) return;
        watcher = com.jfx.git.core.RepositoryWatcher.start(gitService, root, gitService.getDirectory(),
                () -> javafx.application.Platform.runLater(() -> {
                    // Only the tab on screen: a background tab is re-checked when it is
                    // selected, and scanning every open repository on every save would
                    // run a status scan in each of them for a change nobody is looking at.
                    if (getTabPane() != null && getTabPane().getSelectionModel().getSelectedItem() == this) {
                        refreshIfChangedExternally();
                    }
                }));
    }

    private void stopWatching() {
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
    }

    /** Guards against a burst of focus events each starting its own status scan. */
    private boolean checkingExternalChanges;

    /**
     * Reloads this tab if the repository changed behind its back.
     *
     * <h2>Why this is needed</h2>
     * Nothing in this application had ever noticed a change it did not make itself. Every
     * refresh hung off an operation completing, so a repository touched from anywhere else
     * - another git client, a terminal, an IDE's own commit button - left the panel
     * showing a snapshot from whenever it was last rebuilt. That is not merely stale: the
     * file lists are stale while the diff a click runs is live, so a file the panel still
     * lists as unstaged produces an empty diff, because the change it is asking about has
     * since been staged and there is genuinely nothing between the work tree and the
     * index. The panel then looks broken while every part of it is working.
     *
     * <p>One status scan and one HEAD read, on the worker, and a full refresh only when
     * the answer differs from what is on screen - so returning to an untouched window
     * costs a subprocess and changes nothing.
     *
     * <p>Work done <em>inside</em> the application moves the repository too, and is not a
     * reason to reload: every operation runs through this tab's {@link OperationRunner},
     * which has the snapshot recorded once it has refreshed - see {@link #recordOwnChange}.
     * Before there was one place to do that, the next check after any operation found a
     * difference and reloaded everything a second time on the way back from another window.
     *
     * <p>What it compares is described on {@link RepoSnapshot}: comparing only the status
     * lists and HEAD, as this once did, is blind to the two changes most likely to arrive
     * from another window.
     */
    public void refreshIfChangedExternally() {
        if (seedingSnapshot || !gitService.isOpen()) {
            return;
        }
        if (checkingExternalChanges) {
            // Asked again while a check is running: the change that prompted this may have
            // landed after that check read the repository, so it is run once more when it
            // finishes rather than dropped - dropped, it waited for the next focus change.
            externalCheckPending = true;
            return;
        }
        checkingExternalChanges = true;
        GitTaskRunner.query(gitService,
                this::snapshotNow,
                snapshot -> {
                    finishExternalCheck();
                    // Selecting a tab asks here: refs its own background fetch moved while
                    // another tab was on screen were never checked for conflicts.
                    File root = gitService.getWorkTree();
                    if (root != null && !conflictKeyOf(snapshot.head(), snapshot.refs(),
                            com.jfx.git.core.RepositoryPreferences.effectiveConflictBasePatterns(root))
                            .equals(conflictKey)) {
                        checkConflicts();
                    }
                    // The file under the editor is asked about whether or not anything else
                    // moved. An operation of this tab records the working tree as it leaves
                    // it - staging another file does - and a formatter that rewrote the file
                    // being edited first is then part of what was "seen": the notice never
                    // lit. Cheap: one file compared with what editing began from.
                    //
                    // The buffer is the user's own work, and a background refresh is not a
                    // reason to touch it. A notice on the footer warns before the next
                    // Ctrl+S; a modal here would fire on alt-tab, which is hostile.
                    if (diffViewer.isEditing() && diffViewer.workingCopyChangedSinceEditingBegan()) {
                        diffViewer.noteExternalChange();
                    }
                    if (snapshot.equals(lastSeen)) {
                        return;
                    }
                    externalReloads++;
                    RepoSnapshot previous = lastSeen;
                    // Recorded before the refresh rather than after: this snapshot is
                    // precisely what the refresh is about to draw.
                    lastSeen = snapshot;
                    if (previous != null
                            && java.util.Objects.equals(previous.head(), snapshot.head())
                            && java.util.Objects.equals(previous.refs(), snapshot.refs())) {
                        // Only the working tree or the index moved - a file saved from an
                        // editor, a stage from a terminal. Nothing the graph, the sidebar,
                        // the reflog or the banners show depends on that, and a full
                        // refresh re-walked the whole page and re-read every ref for it.
                        refreshWorkingState(snapshot.staging());
                    } else {
                        // Something else changed the repository's history: say so, quietly,
                        // until the graph has been redrawn.
                        if (!externalRefreshShown) {
                            externalRefreshShown = true;
                            statusBar.backgroundActivity("Reloading after outside changes", true);
                        }
                        refreshAll();
                    }

                    if (!diffViewer.isEditing()) {
                        // refreshAll rebuilds the sidebar, toolbar and graph and never
                        // re-requested the diff, so a file changed elsewhere kept showing
                        // the version this application last read - beside file lists that
                        // had already moved on.
                        rightPanelManager.reloadDiff();
                    }
                },
                error -> finishExternalCheck());
    }

    /** Set when a check was asked for while one was running; see refreshIfChangedExternally. */
    private boolean externalCheckPending;

    /** How many times {@link #refreshIfChangedExternally} found a change and reloaded. */
    private int externalReloads;

    /** {@link #externalReloads}, for tests. FX thread. */
    int externalReloadsForTest() {
        return externalReloads;
    }

    /** What an operation's runner redraws: see {@link OperationRunner.Refresh}. */
    private void refreshAfterOperation(OperationRunner.Refresh scope) {
        switch (scope) {
            case EVERYTHING -> refreshAll();
            case STAGING -> {
                if (!tabClosed) rightPanelManager.showStagingArea();
            }
            case NONE -> {
            }
        }
    }

    /**
     * Records the repository as an operation of this tab has just left it, so the next
     * {@link #refreshIfChangedExternally} finds nothing new in the application's own change.
     *
     * <p>Read on the lane ahead of the refresh the operation asked for, so nothing that
     * refresh did not draw is taken as seen. Only what the refresh redraws is recorded: an
     * operation that redrew the staging panel alone leaves HEAD and the refs as they were
     * last seen, so a commit from a terminal that landed while it ran still reloads the
     * graph; one that redrew nothing records nothing, so an edit made elsewhere while it
     * ran still reaches the staging panel.
     */
    private void recordOwnChange(OperationRunner.Refresh scope) {
        if (tabClosed || !gitService.isOpen() || scope == OperationRunner.Refresh.NONE) return;
        GitTaskRunner.query(gitService, this::snapshotNow, snapshot -> {
            RepoSnapshot seen = lastSeen;
            if (scope == OperationRunner.Refresh.EVERYTHING) {
                lastSeen = snapshot;
            } else if (seen != null) {
                lastSeen = new RepoSnapshot(snapshot.staging(), seen.head(), seen.refs(),
                        snapshot.index(), snapshot.worktree());
            }
        }, error -> { });
    }

    /** This tab's operation runner, for the parts of the application that start operations in it. */
    public OperationRunner operations() {
        return operations;
    }

    private void finishExternalCheck() {
        checkingExternalChanges = false;
        if (externalCheckPending) {
            externalCheckPending = false;
            refreshIfChangedExternally();
        }
    }

    /**
     * What a change to the working tree or the index alone has to update: whether the WIP
     * row is there, and the staging panel if it is the one on screen. The diff is reloaded
     * by the caller.
     */
    private void refreshWorkingState(com.jfx.git.model.StagingState staging) {
        boolean nowDirty = staging.hasUncommittedChanges();
        if (nowDirty != dirty) {
            // The WIP row appears or goes, and it is laid out with the page.
            refreshGraph();
            return;
        }
        GitGraphRow selected = commitListView.getSelectionModel().getSelectedItem();
        if (selected != null && selected.isWipNode()) {
            rightPanelManager.showStagingArea();
        }
    }

    /**
     * Reads the repository's four generations. Runs on the worker, never the FX thread.
     */
    private RepoSnapshot snapshotNow() {
        var staging = gitService.getStagingState();
        return new RepoSnapshot(staging, headState(),
                refsGeneration(), indexGeneration(), worktreeGeneration(staging));
    }

    /**
     * What {@code .git/HEAD} says: the branch it names, or the commit when detached.
     *
     * <p>Read from the file rather than asked of the engine. Together with
     * {@link #refsGeneration} it registers everything the engine's answer did - a commit on
     * the current branch rewrites that branch's ref file - for a file read instead of a
     * git process on every focus and every watched change.
     */
    private String headState() {
        java.io.File gitDir = gitService.getDirectory();
        if (gitDir == null) return "";
        try {
            return java.nio.file.Files.readString(new java.io.File(gitDir, "HEAD").toPath()).strip();
        } catch (java.io.IOException | RuntimeException missing) {
            return "-";
        }
    }

    /**
     * Every ref, as the files git keeps them in, digested.
     *
     * <p>A fetch that brings in nothing for the current branch still moves
     * {@code refs/remotes/...}, and the graph draws those. So does a tag someone else
     * pushed. Neither changes the status lists or HEAD.
     *
     * <h2>Files, not the engine</h2>
     * This used to list local branches, remote branches and tags through the engine - on
     * the default engine three git processes, on every window focus and every change the
     * watcher heard, to answer "did anything move?". Every ref update rewrites a file under
     * {@code refs/}, {@code packed-refs} or a reftable, so their names, sizes and times
     * answer the same question from a directory walk. A file rewritten with the same
     * content costs one needless refresh, never a missed one. Lock files are left out:
     * they exist only while a write is in progress, and the write's result is what counts.
     */
    private String refsGeneration() {
        java.io.File gitDir = gitService.getDirectory();
        if (gitDir == null) return "";
        java.io.File common = commonDir(gitDir);
        StringBuilder all = new StringBuilder();
        all.append("packed-refs=").append(stat(new java.io.File(common, "packed-refs").toPath())).append(';');
        appendTree(all, common, "refs");
        appendTree(all, common, "reftable");
        if (!common.equals(gitDir)) {
            // A linked worktree keeps a few refs of its own (bisect, for one) beside HEAD.
            appendTree(all, gitDir, "refs");
        }
        return digest(all.toString());
    }

    /** {@code dir/name}'s files, each as "path=size@modified;", in a stable order. */
    private static void appendTree(StringBuilder out, java.io.File dir, String name) {
        java.nio.file.Path root = new java.io.File(dir, name).toPath();
        if (!java.nio.file.Files.isDirectory(root)) return;
        try (var files = java.nio.file.Files.walk(root)) {
            files.filter(java.nio.file.Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(".lock"))
                    // The application's own refs - working-tree snapshots taken on a timer -
                    // are drawn nowhere, so writing one is not a change worth a reload.
                    .filter(path -> !isInternalRef(name, root.relativize(path)))
                    .map(path -> root.relativize(path).toString().replace('\\', '/') + "=" + stat(path))
                    .sorted()
                    .forEach(entry -> out.append(name).append('/').append(entry).append(';'));
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            // Something moved under the walk - itself a change; a different answer says so.
            out.append(name).append("=?").append(System.nanoTime()).append(';');
        }
    }

    /** Whether {@code relative}, under {@code dir}, is one of {@code refs/lyra/}'s files. */
    static boolean isInternalRef(String dir, java.nio.file.Path relative) {
        String internal = com.jfx.git.core.GitBackend.INTERNAL_REF_PREFIX.substring("refs/".length());
        return "refs".equals(dir)
                && (relative.toString().replace('\\', '/') + "/").startsWith(internal);
    }

    /** Where a linked worktree's shared refs live: the {@code commondir} it names, else itself. */
    private static java.io.File commonDir(java.io.File gitDir) {
        java.io.File pointer = new java.io.File(gitDir, "commondir");
        if (!pointer.isFile()) return gitDir;
        try {
            String relative = java.nio.file.Files.readString(pointer.toPath()).strip();
            java.io.File common = new java.io.File(relative);
            return common.isAbsolute() ? common : new java.io.File(gitDir, relative);
        } catch (java.io.IOException e) {
            return gitDir;
        }
    }

    /**
     * The index file's own size and timestamp.
     *
     * <p>Staging a change from a terminal rewrites {@code .git/index} whether or not the
     * resulting path list looks any different from here.
     */
    private String indexGeneration() {
        java.io.File gitDir = gitService.getDirectory();
        if (gitDir == null) return "";
        return stat(new java.io.File(gitDir, "index").toPath());
    }

    /**
     * Size and timestamp of every file currently listed as changed.
     *
     * <p>This is the M-to-M case: a file already modified, edited again from another
     * editor. Its status letter does not move and neither does the path list, so nothing
     * else here would notice. Bounded by the number of changed files rather than by the
     * size of the repository, and it stats them rather than reading them.
     *
     * <p>Timestamps are millisecond-resolution, so a second edit within the same
     * millisecond that also leaves the size unchanged is invisible - the same stat-based
     * assumption git itself makes about its index.
     */
    private String worktreeGeneration(com.jfx.git.model.StagingState staging) {
        java.io.File workTree = gitService.getWorkTree();
        if (workTree == null) return "";
        java.util.TreeSet<String> paths = new java.util.TreeSet<>(staging.unstaged());
        paths.addAll(staging.staged());
        paths.addAll(staging.conflicting());

        StringBuilder all = new StringBuilder();
        for (String path : paths) {
            all.append(path).append('=')
                    .append(stat(new java.io.File(workTree, path).toPath())).append(';');
        }
        return digest(all.toString());
    }

    /** "size@modified" for a path, or "-" when it is not there to be stat-ed. */
    private static String stat(java.nio.file.Path path) {
        try {
            var attributes = java.nio.file.Files.readAttributes(path,
                    java.nio.file.attribute.BasicFileAttributes.class);
            return attributes.size() + "@" + attributes.lastModifiedTime().toMillis();
        } catch (java.io.IOException | RuntimeException missing) {
            // A deleted file is a change like any other, and "-" differs from any stat.
            return "-";
        }
    }

    /** A fixed-size stand-in for a listing that could otherwise be very long. */
    private static String digest(String text) {
        try {
            var sha = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(sha.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            // SHA-256 is required of every JRE; keeping the text is still correct, only
            // larger.
            return text;
        }
    }

    /** Reloads the sidebar, toolbar and graph. All git work happens off the FX thread. */
    private void refreshAll() {
        // Work started before the tab closed can finish after it - close() waits for the
        // lane only so long - and its callback still asks for this: a commit landing after
        // the user closed its tab queued some twenty reads of the repository the close had
        // just released, for a graph and a sidebar nobody can see any more.
        if (tabClosed) return;
        // The graph first: the repository's lane runs its work in order, and the graph is
        // what a repository is looked at for. It used to be queued behind the sidebar, the
        // toolbar and three banner checks.
        refreshGraph();
        // The toolbar's branch state comes with this; see setOnLoaded.
        sidebarPane.refresh();
        toolBar.refreshUndo();
        rightPanelManager.updateDetachedHeadBanner();
        rightPanelManager.updateBisectBanner();
        rightPanelManager.updateLfsBanner();
        // Cheap: a no-op unless the Settings tab changed the interval since the last
        // refresh. Rechecking here rather than only at construction is what lets a
        // change to Auto-Fetch take effect in an already-open tab.
        autoFetchScheduler.applyPreference();
        snapshotScheduler.applyPreference();
        // Last, so it waits behind everything above on the lane; a no-op unless HEAD, the
        // refs or the patterns moved since the last check.
        checkConflicts();
    }

    // ------------------------------------------------------------------
    // Early conflict warning
    // ------------------------------------------------------------------

    /** What a conflict check found, with the inputs it was found for. */
    private record ConflictCheckDone(String key, com.jfx.git.core.ConflictBase.Check check) {
    }

    /**
     * Works out, in the background, whether merging the base branch into this one would
     * conflict, and shows the answer in the status bar - see {@link com.jfx.git.core.ConflictBase}.
     *
     * <p>Only for the tab on screen, and only when HEAD, the refs or the base-branch
     * patterns moved since the last check: the merge is real work, and a background tab is
     * checked when it is selected. Latest-wins, so a burst of refreshes queues one check
     * rather than one each, and never one ahead of the user's own work.
     */
    void checkConflicts() {
        File root = gitService.isOpen() ? gitService.getWorkTree() : null;
        if (root == null || getTabPane() == null || !isSelected()) return;
        if (!com.jfx.git.core.RepositoryPreferences.effectiveConflictWarning(root)) {
            conflictKey = null;
            statusBar.clearConflictWarning();
            return;
        }
        String patterns = com.jfx.git.core.RepositoryPreferences.effectiveConflictBasePatterns(root);
        String known = conflictKey;
        GitTaskRunner.queryLatest(gitService, "conflicts:" + root,
                () -> {
                    String key = conflictKeyOf(headState(), refsGeneration(), patterns);
                    if (key.equals(known)) return null;
                    return new ConflictCheckDone(key, com.jfx.git.core.ConflictBase.check(gitService, patterns));
                },
                done -> {
                    if (done == null) return;
                    conflictKey = done.key();
                    showConflicts(done.check());
                },
                error -> { });
    }

    private static String conflictKeyOf(String head, String refs, String patterns) {
        return head + "|" + refs + "|" + patterns;
    }

    private void showConflicts(com.jfx.git.core.ConflictBase.Check check) {
        if (check == null || !check.prediction().hasConflicts()) {
            statusBar.clearConflictWarning();
            return;
        }
        String base = check.base();
        statusBar.showConflictWarning(base, check.prediction().paths(), () -> {
            String current = history.currentBranch();
            String branch = current == null || current.isBlank() ? "this branch" : current;
            javafx.scene.control.MenuItem merge = new javafx.scene.control.MenuItem(
                    "Merge " + base + " into " + branch + "…");
            merge.setOnAction(e -> sidebarPane.promptMerge(base));
            javafx.scene.control.MenuItem rebase = new javafx.scene.control.MenuItem("Rebase onto " + base + "…");
            rebase.setOnAction(e -> sidebarPane.promptRebaseOnto(base));
            javafx.scene.control.MenuItem settings = new javafx.scene.control.MenuItem("Settings…");
            settings.setOnAction(e -> AppNavigator.openRepositorySettings(gitService.getWorkTree()));
            return java.util.List.of(merge, rebase, new javafx.scene.control.SeparatorMenuItem(), settings);
        });
    }

    /** Opens this repository's working-tree snapshots in place of the graph. */
    public void openSnapshots() {
        com.jfx.git.ui.views.SnapshotsView.open(this::openView, operations);
    }

    private void refreshGraph() {
        history.refresh();
    }

    /**
     * Where the history window's pages come from: this tab's repository, walked on its
     * lane.
     */
    private final class RepositoryHistory implements HistoryWindow.Source {

        @Override
        public HistoryWindow.Request load(HistoryWindow.Walk walk,
                                          java.util.function.Consumer<HistoryWindow.Walked> onLoaded,
                                          java.util.function.Consumer<String> onFailed,
                                          Runnable onDropped) {
            // The current branch comes with the page, in its ref snapshot: read by the same
            // read that laid the page out, not asked for again beside it.
            return heard(GitTaskRunner.query(gitService,
                    () -> walk.run(gitService::getCommitGraphPageVisible, GitTaskRunner::isCancelled),
                    onLoaded, onFailed), onDropped);
        }

        @Override
        public HistoryWindow.Request reaches(String hash, java.util.function.Consumer<Boolean> onAnswer,
                                             java.util.function.Consumer<String> onFailed,
                                             Runnable onDropped) {
            var visibility = com.jfx.git.graph.GraphVisibility.current();
            return heard(GitTaskRunner.query(gitService, () -> gitService.graphReaches(hash, visibility),
                    onAnswer, onFailed), onDropped);
        }

        /**
         * {@code task} as a request, telling {@code onDropped} if it never runs.
         *
         * <p>Submitted the ordinary way, so the status bar - which said busy for it a line
         * earlier - claims it, and its Cancel reaches it. A task cancelled there before it
         * started calls nothing back, and the history window waited on it for good: its busy
         * span never ended, and "load more" ignored every click after.
         */
        private HistoryWindow.Request heard(GitTaskRunner.Task task, Runnable onDropped) {
            task.onDropped(onDropped);
            return task::cancel;
        }

        @Override
        public void keepFirstPage(com.jfx.git.graph.GraphPage page, String branch) {
            File workTree = gitService.getWorkTree();
            if (workTree != null) GraphPageCache.save(workTree, page, branch);
        }
    }

    /**
     * What the history window's decisions mean for this tab: the list, the selection and
     * viewport, the tab's badge and the status bar.
     */
    private final class HistoryView implements HistoryWindow.Listener {

        @Override
        public void pageChanged(HistoryWindow.Change change, boolean first) {
            List<GitGraphRow> rows = history.rows();
            switch (change) {
                case CACHED -> {
                    graphMetrics.setRefs(history.refs());
                    dirty = !rows.isEmpty() && rows.get(0).isWipNode();
                    if (first) firstPageDrawn(rows.isEmpty());
                    applyFilter(searchField.getText());
                    selectFirstRowIfNone();
                }
                case REFRESHED -> {
                    if (first) firstPageDrawn(rows.isEmpty());
                    // What the user was looking at survives the reload: a refresh can happen
                    // under them at any time (a commit, a stage, an engine switch), and
                    // landing back on row 0 reads as the app losing their place.
                    String selectedHash = selectedCommitHash();
                    double scrollAt = graphScrollOffset();
                    applyFilter(searchField.getText());
                    restoreGraphViewport(selectedHash, scrollAt);
                    selectFirstRowIfNone();
                }
                case OLDER -> {
                    // Remembered rather than cleared: scrolling into older history used to
                    // throw away whatever commit was selected, taking the detail panel with
                    // it. The rows already shown come back identical, so the "load more"
                    // row's index is the join after the reload.
                    int keepAt = commitListView.getItems().size() - 1;
                    String selectedHash = selectedCommitHash();
                    applyFilter(searchField.getText());
                    if (!selectByHash(selectedHash, false)
                            && keepAt >= 0 && keepAt < commitListView.getItems().size()) {
                        // Land on the join rather than jumping back to the newest commit.
                        commitListView.scrollTo(keepAt);
                    }
                    statusBar.info("Showing " + rows.size() + " commits");
                }
                case REVEALED -> applyFilter(searchField.getText());
            }
        }

        @Override
        public void refreshed(com.jfx.git.graph.GraphPage page) {
            // Every refreshed page, replaced on screen or not: its refs are the newest.
            graphMetrics.setRefs(page.refs());
            sidebarPane.showRefs(page.refs());
            endExternalRefresh();
            dirty = !page.rows().isEmpty() && page.rows().get(0).isWipNode();
            updateTabBadge();
        }

        @Override
        public void refreshFailed(String error) {
            endExternalRefresh();
            statusBar.error(error);
            if (!history.firstPageShown()) showGraphLoadFailed(error);
            // No page, so no refs with it: the sidebar - and the toolbar, from the sidebar's
            // load - reads them directly rather than drawing from none (ADR 0002), so the
            // branch the history could not be read at can still be checked out or deleted.
            sidebarPane.readRefsDirectly();
        }

        @Override
        public void pagingFailed(String error) {
            statusBar.error(error);
        }

        @Override
        public void busy(String what) {
            statusBar.busy(what);
        }

        @Override
        public void idle() {
            statusBar.idle();
        }
    }

    private void selectFirstRowIfNone() {
        if (!commitListView.getItems().isEmpty() && commitListView.getSelectionModel().isEmpty()) {
            commitListView.getSelectionModel().select(0);
        }
    }

    /**
     * What the tab says about its repository without being opened: a dot for uncommitted
     * changes, and how far ahead and behind its branch is. Commercial clients' tabs say nothing,
     * and four tabs of "is anything waiting in here?" is four clicks.
     */
    private void updateTabBadge() {
        if (!history.firstPageShown()) return;
        int ahead = tracking.length > 0 ? tracking[0] : 0;
        int behind = tracking.length > 1 ? tracking[1] : 0;
        if (!dirty && ahead == 0 && behind == 0) {
            setGraphic(null);
            return;
        }
        javafx.scene.layout.HBox badge = new javafx.scene.layout.HBox(UiScale.SNUG);
        badge.setAlignment(javafx.geometry.Pos.CENTER);
        badge.getStyleClass().add("tab-badge");
        if (dirty) {
            javafx.scene.layout.Region dot = new javafx.scene.layout.Region();
            dot.getStyleClass().add("tab-dirty");
            badge.getChildren().add(dot);
        }
        if (ahead > 0 || behind > 0) {
            Label sync = new Label((ahead > 0 ? "↑" + ahead : "") + (ahead > 0 && behind > 0 ? " " : "")
                    + (behind > 0 ? "↓" + behind : ""));
            sync.getStyleClass().add("tab-sync");
            badge.getChildren().add(sync);
        }
        String said = (dirty ? "uncommitted changes" : "")
                + (ahead > 0 ? (dirty ? ", " : "") + ahead + " to push" : "")
                + (behind > 0 ? (dirty || ahead > 0 ? ", " : "") + behind + " to pull" : "");
        badge.setAccessibleText(said);
        setGraphic(badge);
    }

    private void updateWindowTitle() {
        String name = getText() == null ? "" : getText();
        windowTitle.set(branchName.isEmpty() ? name : name + " — " + branchName);
    }

    /** "repository — branch", for the window's title while this tab is showing. */
    public javafx.beans.property.ReadOnlyStringProperty windowTitleProperty() {
        return windowTitle.getReadOnlyProperty();
    }

    private void endExternalRefresh() {
        if (externalRefreshShown) {
            externalRefreshShown = false;
            statusBar.backgroundActivity("Reloading after outside changes", false);
        }
    }

    /**
     * The history could not be read: says so where the history would be, with a way to
     * try again, instead of leaving the spinner turning over nothing.
     */
    private void showGraphLoadFailed(String error) {
        endOpeningBusy();
        setGraphic(null);
        Label what = new Label("The history could not be read.");
        what.getStyleClass().add("t-label");
        Label why = new Label(error == null ? "" : error.lines().findFirst().orElse(""));
        why.getStyleClass().add("t-caption");
        why.setWrapText(true);
        why.setMaxWidth(420);
        javafx.scene.control.Button retry = new javafx.scene.control.Button("Try again");
        retry.setOnAction(e -> {
            commitListView.setPlaceholder(openingPlaceholder);
            refreshGraph();
        });
        javafx.scene.layout.VBox failed = new javafx.scene.layout.VBox(UiScale.STEP, what, why, retry);
        failed.setAlignment(javafx.geometry.Pos.CENTER);
        failed.getStyleClass().add("graph-load-failed");
        commitListView.setPlaceholder(failed);
    }

    /** Whether a search filters or dims, for tests. */
    ToggleButton filterModeToggleForTest() {
        return filterModeToggle;
    }

    /** This tab's notifications, for tests. */
    ToastHost toastHost() {
        return toastHost;
    }

    /** What a page draws, as one string; see {@link HistoryWindow.Loaded#signatureOf}. */
    static String signatureOf(com.jfx.git.graph.GraphPage page, String branch) {
        return HistoryWindow.Loaded.signatureOf(page, branch);
    }

    /**
     * The cached branch, fetched once if a page has never been loaded.
     *
     * <p>The fallback is for {@code showRows}, which fills the list directly without a
     * load; it costs one call rather than one per keystroke. Every production path seeds
     * the cache from the page load instead, so this is reached only by tests, through
     * {@link #showRows}, which marks the read as fixture setup. Reached from the FX thread
     * anywhere else, {@link com.jfx.git.core.ThreadGuard} reports it.
     */
    private String currentBranch() {
        String branch = history.currentBranch();
        if (branch == null) {
            branch = gitService.getCurrentBranch();
            history.assumeCurrentBranch(branch);
        }
        return branch;
    }

    /**
     * Lists the files of the commits either side of the selection ahead of time, into the
     * engine's commit cache, while the lane is idle - so J/K through history finds the next
     * commit's files already read. Nothing is drawn from it; it only warms the cache, and a
     * prefetch never runs ahead of work that was asked for.
     */
    private void prefetchNeighbours() {
        int at = commitListView.getSelectionModel().getSelectedIndex();
        var rows = commitListView.getItems();
        for (int i : new int[]{at + 1, at - 1}) {
            if (i < 0 || i >= rows.size()) continue;
            var commit = rows.get(i).getCommit();
            if (commit == null || rows.get(i).isSynthetic()) continue;
            String hash = commit.getHash();
            GitTaskRunner.prefetch(gitService, () -> gitService.getChangesInCommit(hash));
        }
    }

    /** The hash of the commit currently selected in the graph, or null. */
    private String selectedCommitHash() {
        GitGraphRow row = commitListView.getSelectionModel().getSelectedItem();
        var commit = row == null ? null : row.getCommit();
        return commit == null ? null : commit.getHash();
    }

    /**
     * Vertical pixel offset of the graph, or {@code -1} when it cannot be read.
     *
     * <p>Read through the scrollbar rather than the skin's internal classes: the rows
     * have a fixed height ({@code setFixedCellSize}), so a pixel value survives the item
     * swap unchanged and can simply be written back.
     */
    private double graphScrollOffset() {
        if (commitListView.getSkin() == null) {
            return -1;
        }
        var scroll = verticalScrollBar();
        return scroll == null ? -1 : scroll.getValue();
    }

    /**
     * The graph's vertical scroll bar, or {@code null} before the skin exists.
     *
     * <p>Looked up as {@code .scroll-bar} and told apart by orientation. The lookup used to
     * ask for {@code .scrollbar}, which no JavaFX scroll bar carries, so it never found
     * one: the scroll position was never saved, and a refresh with nothing selected put
     * the graph back at the top. {@code lookup} ignores the {@code :vertical} pseudo-class,
     * so the orientation is checked rather than trusted to the selector.
     */
    private javafx.scene.control.ScrollBar verticalScrollBar() {
        for (javafx.scene.Node node : commitListView.lookupAll(".scroll-bar")) {
            if (node instanceof javafx.scene.control.ScrollBar bar
                    && bar.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                return bar;
            }
        }
        return null;
    }

    /** Puts the selection, and failing that the viewport, back where they were. */
    private void restoreGraphViewport(String hash, double scrollAt) {
        if (hash != null && selectByHash(hash, false)) {
            return;
        }
        if (scrollAt >= 0) {
            var scroll = verticalScrollBar();
            if (scroll != null) {
                // After a layout pass: writing the value now would be overwritten when
                // the skin clamps it against the not-yet-updated item count. The scene
                // check keeps a queued write from firing after the tab has been torn
                // down - laying out a closed repository's cells during that pulse is
                // exactly the race this must not open.
                javafx.application.Platform.runLater(() -> {
                    if (commitListView.getScene() != null) {
                        scroll.setValue(scrollAt);
                    }
                });
            }
        }
    }

    /**
     * Selects the commit with this hash if it is on screen.
     *
     * @param scroll whether to bring it into view; false keeps the viewport still
     * @return true when the hash was found and selected
     */
    private boolean selectByHash(String hash, boolean scroll) {
        return hash != null && selectRow(isCommit(hash), scroll);
    }

    /** Walks another page of history; see {@link HistoryWindow#loadOlder}. */
    private void loadMoreCommits() {
        history.loadOlder();
    }

    /**
     * The bar above the graph.
     *
     * <p>It was a bare text field whose prompt named three fields it searched, with no way
     * to say which one you meant and no report of what it found. All three are shown: what
     * is being matched, how much matched, and - when the history has only been walked as
     * far as the first page - that "nothing found" is a statement about the walk rather
     * than about the repository.
     */
    private VBox buildFilterBar() {
        javafx.scene.Node glyph = Icons.search();

        Button clear = Icons.button(Icons.close(), "Clear the filter", "Clear the filter");
        clear.getStyleClass().addAll("btn-sm", "row-action");
        clear.setOnAction(e -> {
            searchField.clear();
            historySearch.close();
            searchField.requestFocus();
        });
        clear.visibleProperty().bind(searchField.textProperty().isEmpty().not());
        clear.managedProperty().bind(clear.visibleProperty());

        searchHits.getStyleClass().add("search-hits");
        searchTruncated.getStyleClass().add("search-truncated");

        for (ToggleButton scope : List.of(scopeMessage, scopeAuthor, scopeHash)) {
            scope.getStyleClass().add("scope-chip");
            scope.setSelected(true);
            scope.setOnAction(e -> {
                // Turning the last one off would filter against nothing and silently
                // return no rows, so the final selected chip refuses to be cleared.
                if (!scopeMessage.isSelected() && !scopeAuthor.isSelected() && !scopeHash.isSelected()) {
                    ((ToggleButton) e.getSource()).setSelected(true);
                    return;
                }
                applyFilter(searchField.getText());
            });
        }
        filterModeToggle.getStyleClass().add("scope-chip");
        filterModeToggle.setSelected(false);
        filterModeToggle.setTooltip(new javafx.scene.control.Tooltip(
                "Narrow the list to matches (off: keep every commit, dim the rest)"));
        filterModeToggle.setOnAction(e -> applyFilter(searchField.getText()));

        // The box is the part of this row that gives way. The chips and the button each
        // say one word, and "mess...", "au..." and "Search al..." say nothing: left to
        // share the squeeze equally, every control in the row lost its label at once. In
        // a flow none of them is squeezed - one that does not fit moves to the next line.
        FlowPane options = new StackingRow.Flow(UiScale.SNUG, UiScale.SNUG,
                scopeMessage, scopeAuthor, scopeHash, filterModeToggle);
        searchField.setMinWidth(90);

        // The instant filter can only see what is loaded; this is the way to the rest.
        // Enter in the box does the same - the button is for finding out that it exists.
        Button searchAll = new Button("Search all history");
        searchAll.getStyleClass().add("history-search-button");
        searchAll.setTooltip(new javafx.scene.control.Tooltip(
                "Search every commit, not only the loaded ones (Enter).\n"
                        + "Plain words search messages; prefixes narrow it:\n"
                        + "author:  committer:  hash:  path:  content: (or -G:)\n"
                        + "Quote a value with spaces: author:\"Ada Lovelace\""));
        searchAll.setOnAction(e -> searchAllHistory());

        // Two groups: the box you type in, and what it is matched against. Side by side
        // while they fit; the second drops to a line of its own when they do not, and wraps
        // there when even that is too narrow - an 800-pixel window leaves the graph about
        // 217 pixels, and the options want 360 on one line. The row used to be one line at any
        // width, and paid for it by shortening every control in it at once - or, with the
        // controls refusing to shorten, by being wider than the space between the panels
        // and pushing the graph out from under its own header. Whether they fit is asked
        // again whenever either group changes width, not only when the bar does: typing
        // brings in the clear button, and the options outgrow the room they had.
        // Beside the count, whenever a search is on: the empty-graph message cannot be
        // relied on to offer it, because a dirty repository's WIP row keeps the list from
        // ever being empty.
        clearSearchInline.getStyleClass().addAll("btn-sm", "btn-ghost", "search-clear");
        clearSearchInline.setOnAction(e -> searchField.clear());
        show(clearSearchInline, false);
        HBox box = new HBox(UiScale.STEP, glyph, searchField, searchHits, clearSearchInline);
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        options.getChildren().addAll(clear, searchAll);
        // A snug gap between the chips, a step between them and each button after. As the
        // right margin of what ends a group, so a button that wraps to the start of a line
        // is not indented by it.
        javafx.geometry.Insets endOfGroup = new javafx.geometry.Insets(0, UiScale.STEP - UiScale.SNUG, 0, 0);
        for (javafx.scene.Node last : List.of(filterModeToggle, clear, searchAll)) {
            FlowPane.setMargin(last, endOfGroup);
        }
        options.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        this.filterRow = options;

        StackingRow line = new StackingRow(box, options, FILTER_BOX_COMFORTABLE, UiScale.STEP, UiScale.SNUG);
        VBox bar = new VBox(UiScale.SNUG, line, searchTruncated);
        bar.setMinWidth(0);
        return bar;
    }

    /** The narrowest the commit filter box is left before its options move to their own line. */
    private static final double FILTER_BOX_COMFORTABLE = 220;

    // ------------------------------------------------------------------
    // Searching all of history
    // ------------------------------------------------------------------

    /** Runs the filter box's text as a search over the whole history. */
    private void searchAllHistory() {
        historySearch.search(com.jfx.git.model.CommitSearch.parse(searchField.getText()));
    }

    /** The results panel; package-private for the robot tests. */
    HistorySearchPanel historySearchPanel() {
        return historySearch;
    }

    /**
     * Selects a search result in the graph, paging older history in until it is there.
     *
     * <p>The result's details are shown straight away when it is not already on the page,
     * so picking one never looks like nothing happened while the graph catches up;
     * selecting the row later shows the same details again. A result the graph cannot
     * show at all - on a branch Hide/Solo has hidden, or narrowed away by the filter -
     * keeps its details on the right and says why.
     */
    void jumpToCommit(com.jfx.git.model.CommitInfo commit) {
        if (commit == null) return;
        String hash = commit.getHash();
        if (selectNow(isCommit(hash))) return;
        rightPanelManager.showCommitDetails(commit);
        history.reveal(isCommit(hash), commit.getShortHash(), selectOr(isCommit(hash),
                () -> statusBar.info(notShown(commit.getShortHash()) + " Its details are on the right.")));
    }

    private static java.util.function.Predicate<GitGraphRow> isCommit(String hash) {
        return row -> row.getCommit() != null && hash.equals(row.getCommit().getHash());
    }

    /** Why a commit the user asked for stays unselected. */
    private static String notShown(String shortHash) {
        return "Commit " + shortHash + " is not shown in the graph"
                + " (a hidden branch, or narrowed away by the filter).";
    }

    /**
     * Selects the first row {@code isIt} accepts and brings it into view, paging older
     * history in when it may lie beyond the loaded page; {@code whenNotShown} runs when
     * the graph cannot show it.
     *
     * <p>No paging when the caller knows the walk leaves the row out ({@code mayPage}
     * false: a hidden stash), nor where the history window says paging would find nothing.
     */
    private void reveal(java.util.function.Predicate<GitGraphRow> isIt, String what, boolean mayPage,
                        Runnable whenNotShown) {
        if (selectNow(isIt)) return;
        if (mayPage) {
            history.reveal(isIt, what, selectOr(isIt, whenNotShown));
        } else {
            // Shown on the right instead - which a reveal still paging would replace.
            history.cancelReveal();
            whenNotShown.run();
        }
    }

    /**
     * Selects and scrolls to the first row {@code isIt} accepts when it is on the page,
     * superseding any reveal still paging: the later ask wins. Selected and left at that,
     * the reveal for the click before landed afterwards and took the selection back.
     */
    private boolean selectNow(java.util.function.Predicate<GitGraphRow> isIt) {
        if (!selectRow(isIt)) return false;
        history.cancelReveal();
        return true;
    }

    /** Selects the first row {@code isIt} accepts once paging is over, or runs {@code whenNotShown}. */
    private Runnable selectOr(java.util.function.Predicate<GitGraphRow> isIt, Runnable whenNotShown) {
        return () -> {
            if (!selectRow(isIt)) whenNotShown.run();
        };
    }

    /**
     * Selects a commit in the graph - a branch's tip, a tag's commit, a parent - and
     * brings it into view, paging older history in when it is not loaded yet. Clicking a
     * branch in the sidebar lands here, as it does in commercial clients.
     */
    public void revealCommit(String hash) {
        revealCommit(hash, false);
    }

    /**
     * {@link #revealCommit(String)}, for a commit that may be the tip of a branch or tag
     * Hide/Solo excludes.
     *
     * <p>Such a tip is not a start of the walk, so it is on the graph only when a branch
     * that is shown also reaches it - an old tag on {@code main} under Solo, a hidden
     * branch merged since. The engine is asked that first, and history paged in only when
     * the answer is yes: paging for a tip only the hidden ref reaches read history up to
     * {@link HistoryWindow#REVEAL_LIMIT} and never arrived.
     *
     * @param onExcludedRef whether {@code hash} is the tip of a ref Hide/Solo excludes
     */
    public void revealCommit(String hash, boolean onExcludedRef) {
        if (hash == null || hash.isBlank()) return;
        String shortHash = shortHash(hash);
        var isIt = isCommit(hash);
        if (selectNow(isIt)) return;
        Runnable then = selectOr(isIt, () -> statusBar.info(notShown(shortHash)));
        if (onExcludedRef) {
            history.revealExcludedTip(hash, isIt, shortHash, then);
        } else {
            history.reveal(isIt, shortHash, then);
        }
    }

    private static String shortHash(String hash) {
        return hash.substring(0, Math.min(7, hash.length()));
    }

    /**
     * Selects a stash's row in the graph - where it was made - paging history in to reach
     * it. A stash hidden from the graph, or one the graph cannot place, still has its
     * details shown, with the reason.
     */
    public void revealStash(com.jfx.git.model.StashEntry stash) {
        if (stash == null) return;
        java.util.function.Predicate<GitGraphRow> isIt = row -> row.isStash() && row.getStash() != null
                && stash.hash().equals(row.getStash().hash());
        boolean hidden = com.jfx.git.core.AppPreferences.hiddenStashes().contains(stash.hash());
        reveal(isIt, stash.selector(), !hidden, () -> {
            rightPanelManager.showStashDetails(stash);
            statusBar.info(stash.selector()
                    + (hidden ? " is hidden from the graph" : " is not shown in the graph")
                    + "; its details are on the right.");
        });
    }

    /** Selects and scrolls to the first row {@code wanted} accepts; false when none is loaded. */
    private boolean selectRow(java.util.function.Predicate<GitGraphRow> wanted) {
        return selectRow(wanted, true);
    }

    /**
     * Selects the first row {@code wanted} accepts; false when none is on the page.
     *
     * @param scroll whether to bring it into view; false keeps the viewport still
     */
    private boolean selectRow(java.util.function.Predicate<GitGraphRow> wanted, boolean scroll) {
        var items = commitListView.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (wanted.test(items.get(i))) {
                commitListView.getSelectionModel().clearAndSelect(i);
                if (scroll) commitListView.scrollTo(i);
                return true;
            }
        }
        return false;
    }

    /** How many commits the graph has loaded, for tests. */
    int graphLimitForTest() {
        return history.limit();
    }

    /** Loads only the first {@code limit} commits, for tests of paging history in. */
    void setGraphLimitForTest(int limit) {
        history.setLimitForTest(limit);
    }

    /** Moves the graph's selection to a commit, given its hash. */
    private void selectCommitByHash(String hash) {
        // Paged in if it is older than what is loaded: a parent link and a sidebar click
        // both expect to land on the commit, not on advice to load more.
        revealCommit(hash);
    }

    /**
     * Applies the search box (and author filter) to the graph.
     *
     * <p>With the filter toggle off — the default — a search highlights matches in
     * place: every loaded commit stays in the graph, non-matches dim through the same
     * fade machinery selection uses, and nothing can be lost by mistyping. With it on,
     * the list actually narrows, as it always did.
     */
    /**
     * Above this many rows a keystroke's filter waits for the typing to pause.
     *
     * <p>On a page this size each keystroke in filter mode re-lays out every kept row on
     * the FX thread; typing a word ran that once per letter. Below it the work is small
     * enough to do as each key lands, which keeps the filter feeling instant where it can.
     */
    private static final int FILTER_DEBOUNCE_ROWS = 2_000;

    private final javafx.animation.PauseTransition filterPause =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(120));

    private void scheduleFilter(String text) {
        if (history.rows().size() < FILTER_DEBOUNCE_ROWS) {
            filterPause.stop();
            applyFilter(text);
            return;
        }
        filterPause.setOnFinished(e -> applyFilter(searchField.getText()));
        filterPause.playFromStart();
    }

    /**
     * What the list was last filled from: the rows, whether the "load more" row followed
     * them, and the branch the metrics were measured against. When a keystroke would fill
     * it from the same three - every keystroke of a highlight-mode search, which dims in
     * place rather than narrowing - the fill is skipped.
     */
    private List<GitGraphRow> filledFrom;
    private boolean filledWithMore;
    private String filledForBranch;

    private void applyFilter(String query) {
        boolean searching = query != null && !query.isBlank();
        boolean byAuthor = authorFilter != null;
        // An author filter is already an explicit narrowing gesture, so a query typed
        // on top of one composes with it the way it always did; a lone search is what
        // highlights in place instead of narrowing.
        boolean highlightMode = searching && !filterModeToggle.isSelected() && !byAuthor;
        boolean filtering = !highlightMode && (searching || byAuthor);

        java.util.function.Predicate<GitGraphRow> keep = null;
        if (filtering) {
            var q = searching ? commitMatcher(query) : null;
            // Both have to hold. Choosing an author and then typing should narrow that
            // author's commits, not start again across everyone's.
            keep = row -> row.isWipNode()
                    || ((q == null || (row.getCommit() != null && q.test(row.getCommit())))
                        && matchesAuthor(row));
        }
        HistoryWindow.Shown view = history.filter(keep);
        List<GitGraphRow> shown = view.rows();
        long total = view.total();
        searchHits.setText(filtering ? view.matched() + " / " + total : "");
        show(searchHits, filtering);
        show(clearSearchInline, searching);
        // The placeholder only shows when there are no rows at all, so it has to name
        // whichever of the two reasons applies at that moment.
        emptyGraphMessage.setText(filtering ? NOTHING_MATCHED : NO_COMMITS_YET);
        show(clearSearchButton, filtering);

        boolean partial = view.partial();
        searchTruncated.setText(partial
                ? "Searching the " + total + " commits loaded so far - older history has not been walked."
                : "");
        show(searchTruncated, partial);

        // Before the items, so the cells realised by setAll are laid out at the widths
        // this page needs rather than at the previous page's and then shuffled.
        //
        // Skipped when the list already holds exactly this: a highlight-mode keystroke
        // used to copy the page, re-measure every ref chip and replace every item - which
        // re-ran updateItem on every visible row - to then change only the fade.
        String branch = currentBranch();
        if (shown != filledFrom || history.hasMore() != filledWithMore
                || !java.util.Objects.equals(branch, filledForBranch)) {
            graphMetrics.update(view.items(), branch);
            commitListView.getItems().setAll(view.items());
            filledFrom = shown;
            filledWithMore = history.hasMore();
            filledForBranch = branch;
        }

        refreshGraphFade();
    }

    /**
     * Decides what the fade means right now: an active highlight-mode search wins over
     * the selection's ancestry walk, because the user asked about the query more
     * recently than about the selection. Clearing the query hands the fade straight
     * back without losing the selection's state.
     */
    private void refreshGraphFade() {
        String q = searchField.getText();
        if (q != null && !q.isBlank() && !filterModeToggle.isSelected()) {
            Set<String> matched = new java.util.HashSet<>();
            var matcher = commitMatcher(q);
            for (GitGraphRow row : history.rows()) {
                if (row.getCommit() != null && matcher.test(row.getCommit())) {
                    matched.add(row.getCommit().getHash());
                }
            }
            graphHighlight.search(matched);
        } else {
            updateHighlight();
        }
    }

    /**
     * What the instant filter keeps, decided once per keystroke rather than per row.
     *
     * <p>A query using the history search's prefixes ({@code author:ada}) is read the way
     * the history search reads it, so typing one narrows the loaded rows to what Enter will
     * then find in the rest. Committer, path and content cannot be answered from a loaded
     * row - they need the repository - so a query that uses them leaves the rows alone and
     * waits for Enter, rather than claiming nothing loaded matches.
     */
    private java.util.function.Predicate<com.jfx.git.model.CommitInfo> commitMatcher(String query) {
        if (com.jfx.git.model.CommitSearch.hasPrefix(query)) {
            var parsed = com.jfx.git.model.CommitSearch.parse(query);
            return parsed.locallyDecidable() ? parsed::matchesLocally : commit -> true;
        }
        String q = query.toLowerCase(Locale.ROOT);
        return commit -> matches(commit, q);
    }

    private boolean matches(com.jfx.git.model.CommitInfo commit, String query) {
        if (commit == null) return false;
        if (scopeMessage.isSelected()
                && commit.getSubject().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        if (scopeAuthor.isSelected()
                && commit.getAuthorName().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        return scopeHash.isSelected()
                && commit.getHash().toLowerCase(Locale.ROOT).startsWith(query);
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /**
     * Opens a working-tree file with the platform's default application.
     *
     * <p>Off the FX thread, because launching an editor can take seconds - and the
     * result is reported, because the previous version could not tell a successful
     * launch from a file nothing on the machine knows how to open.
     */
    private void openInEditor(String path) {
        File workTree = gitService.getWorkTree();
        if (workTree == null) {
            statusBar.error("No repository open");
            return;
        }
        File target = new File(workTree, path);

        var opener = fileOpener;
        operations.run("Opening " + path, OperationRunner.Refresh.NONE, () -> opener.apply(target));
    }

    /** What {@link #openInEditor} hands a working-tree file to; see {@link #setFileOpenerForTest}. */
    private java.util.function.Function<File, GitResult> fileOpener =
            com.jfx.git.core.FileOpener::openPreferred;

    /**
     * Replaces what opens a working-tree file externally, for tests: the real one starts the
     * platform's editor, which a headless test must never do, and the file it was given is
     * the thing under test.
     */
    void setFileOpenerForTest(java.util.function.Function<File, GitResult> opener) {
        this.fileOpener = opener;
    }

    /**
     * Writes text edited in the viewer back to the working tree.
     *
     * <p>Encoded with whatever the viewer is decoding under, so a file that was read
     * as UTF-16 is written back as UTF-16 rather than silently converted.
     */
    private void saveEditedFile(String path, String text,
                                java.util.function.Consumer<GitResult> onResult) {
        byte[] bytes = diffViewer.getEncoding().encode(text, gitService.getWorkingTreeBytes(path));
        // The file is now different from HEAD, so the staging lists and the diff behind it
        // are both stale. The viewer hears the result before the refresh: it decides whether
        // to keep holding the buffer, and a refresh that replaced the file first would be
        // deciding for it. A write that failed changed nothing, so it redraws nothing - and
        // records nothing, or a change made elsewhere meanwhile would be taken as seen.
        operations.operation("Saving " + path, OperationRunner.Refresh.EVERYTHING)
                .onResult(onResult)
                .refreshWhen(GitResult::isOk)
                .run(() -> gitService.writeWorkingTreeFile(path, bytes));
    }

    /**
     * Writes text edited in the viewer back to the working tree and stages the file.
     *
     * <p>The one-gesture version of {@link #saveEditedFile}: an edit the user knows is
     * finished becomes a staged change without a trip through the file list. Encoding is
     * identical — whatever the viewer decoded with, against the original bytes.
     */
    private void saveAndStageEditedFile(String path, String text,
                                        java.util.function.Consumer<GitResult> onResult) {
        byte[] bytes = diffViewer.getEncoding().encode(text, gitService.getWorkingTreeBytes(path));
        // The file moved to staged in one step, so both lists are stale - or, when only the
        // stage failed, the working tree moved without it, which is stale all the same. Only
        // a write that failed left nothing to redraw.
        java.util.concurrent.atomic.AtomicBoolean wrote = new java.util.concurrent.atomic.AtomicBoolean();
        operations.operation("Saving and staging " + path, OperationRunner.Refresh.EVERYTHING)
                .onResult(onResult)
                .refreshWhen(result -> wrote.get())
                .run(() -> {
                    GitResult written = gitService.writeWorkingTreeFile(path, bytes);
                    if (!written.isOk()) {
                        return written;
                    }
                    wrote.set(true);
                    return gitService.stageFile(path);
                });
    }

    /** Re-reads the diff preferences after the settings dialog changed them. */
    public void applyPreferences() {
        diffViewer.applyPreferences();
        if (openHistory != null) {
            openHistory.applyPreferences();
        }
        // A new date format can need a wider age column, and a cell fixes its columns
        // when it is built; rebuilt only when the format actually changed.
        if (datesLaidOutFor != DateDisplay.generation()) {
            datesLaidOutFor = DateDisplay.generation();
            rebuildGraphColumns();
        }
    }

    /** The {@link DateDisplay#generation()} the graph's cells were built for. */
    private long datesLaidOutFor = DateDisplay.generation();

    public RepoToolbar getToolbar() {
        return toolBar;
    }

    public GitBackend getGitService() {
        return gitService;
    }

    /**
     * The folder {@link #loadRepositoryAsync} was asked to open, known from the moment it
     * is called - unlike the backend's work tree, which stays {@code null} until the
     * background open finishes. Finding a tab by path has to see a tab that is still
     * loading, or a second open of the same repository slips past it.
     */
    public File getRequestedDirectory() {
        return requestedDirectory;
    }

    /**
     * Names the tab after {@code dir} before anything is opened: the folder as its title,
     * the path as its tooltip. A strip of restored tabs used to read "Loading…" in every
     * slot until each one finished, so none could be told from another.
     */
    public void expect(File dir) {
        requestedDirectory = dir;
        openingLabel.setText("Opening " + dir.getName() + "…");
        folderTitle = dir.getName();
        applyTitle();
        setTooltip(new javafx.scene.control.Tooltip(dir.getAbsolutePath()));
    }

    /** The open this tab is holding back until it is looked at; null once it has run. */
    private Runnable pendingOpen;

    /**
     * Holds the repository's open until the tab is first selected (or the application
     * gets round to it). A restored session reopened every tab at once, on one shared
     * worker, so the tab being looked at waited behind all the others.
     */
    public void deferOpen(Runnable open) {
        this.pendingOpen = open;
    }

    /** Runs a held-back open; false when there was none. */
    public boolean openIfPending() {
        Runnable open = pendingOpen;
        if (open == null) return false;
        pendingOpen = null;
        open.run();
        return true;
    }

    /** Whether this tab's repository has not been asked to open yet. */
    public boolean isPending() {
        return pendingOpen != null;
    }

    /** Whether the built-in editor holds changes that have not been written to disk. */
    public boolean hasUnsavedEdits() {
        return diffViewer.hasUnsavedEdits();
    }

    /** Whether the diff panel is currently in editing mode. */
    public boolean isEditingFile() {
        return diffViewer.isEditing();
    }

    /**
     * Offers to save before this tab's content goes away.
     *
     * <p>Closing the window or the tab used to discard typed changes silently - exactly
     * the data-loss class {@code DiffViewer.leaveEditingForNewContent} guards against for
     * file switches. The save here is deliberately synchronous rather than routed through
     * {@link #saveEditedFile}: the caller is about to release the repository handle, and
     * an async write would race it (or lose to it).
     *
     * @return true to proceed with the close
     */
    public boolean confirmClose() {
        if (!hasUnsavedEdits()) {
            return true;
        }
        ButtonType save = new ButtonType("Save and close", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Discard edits", ButtonBar.ButtonData.NO);
        String path = diffViewer.editingFilePath();
        var choice = com.jfx.git.ui.dialogs.Dialogs.confirm("Unsaved edits")
                .icon(Icons.edit())
                .title("Save your edits before closing?")
                .subtitle(path == null || path.isEmpty() ? "Not saved yet" : "`" + path + "` · not saved yet")
                .message("They exist only in the editor. Discarded edits cannot be brought back.")
                .buttons(save, discard, ButtonType.CANCEL)
                .danger(discard)
                .answer();
        if (choice == ButtonType.CANCEL) {
            return false;
        }
        if (choice == discard) {
            // Ends the session rather than just letting the tab go: its draft would
            // otherwise come back as a "recovered draft" of work the user threw away.
            diffViewer.endSessionForClose();
            return true;
        }

        // Closing is when the user is least likely to be watching, and saveEditedFileSync
        // writes straight past the editor's own checks - so the question the editor would
        // have asked is asked here instead.
        if (diffViewer.workingCopyChangedSinceEditingBegan()) {
            switch (confirmCloseOverExternalChange()) {
                case CANCEL -> {
                    return false;
                }
                case DISCARD -> {
                    diffViewer.endSessionForClose();
                    return true;
                }
                case OVERWRITE -> {
                    // Fall through to the write below.
                }
            }
        }

        // Save and close used to close whatever the write returned, so a read-only file
        // or a full disk closed the only copy of the edits. A failed save vetoes the
        // close instead, and says why, leaving the buffer where the user can act on it.
        GitResult written = saveEditedFileSync();
        if (written.isOk()) {
            diffViewer.endSessionForClose();
            return true;
        }
        String file = diffViewer.editingFilePath();
        com.jfx.git.ui.dialogs.Dialogs.error("Save failed")
                .title(file == null || file.isEmpty() ? "Could not save your edits" : "Could not save `" + file + "`")
                .subtitle(saveFailureReason(written.getMessage()))
                .message("The tab stays open with your edits in it, so nothing is lost. Save from the"
                        + " editor once the problem is fixed.")
                .details(written.getMessage())
                .answer();
        return false;
    }

    /**
     * Why a write failed, without the "Could not save a.txt:" the title already says:
     * the part after the engine's own prefix, or the whole message when it has none.
     */
    static String saveFailureReason(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        int colon = message.indexOf(": ");
        boolean prefixed = message.startsWith("Could not save ") || message.startsWith("Failed to write ");
        return prefixed && colon > 0 ? message.substring(colon + 2) : message;
    }

    /** What to do about a closing tab whose file changed on disk. */
    private enum CloseOverExternalChange {
        /** Write the buffer over whatever is there and close. */
        OVERWRITE,
        /** Close without writing; the file on disk stands. */
        DISCARD,
        /** Do not close. */
        CANCEL
    }

    /**
     * Asks before a closing tab overwrites a file that changed on disk.
     *
     * <p>Deliberately without a Compare option, unlike the editor's own prompt: a modal
     * opened from inside a window-close handler sits over a stage that is already
     * half-closed, which is how a window becomes impossible to dismiss. Cancelling and
     * saving from the editor is the route to a comparison, and the wording says so.
     */
    private CloseOverExternalChange confirmCloseOverExternalChange() {
        ButtonType overwrite = new ButtonType("Overwrite and close", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Discard my edits and close", ButtonBar.ButtonData.NO);
        // As the editor's own prompt: either answer throws a version away for good, so
        // both are red and Enter keeps the tab open.
        ButtonType choice = com.jfx.git.ui.dialogs.Dialogs.warn("Close tab")
                .icon(Icons.file())
                .title("`" + diffViewer.editingFilePath() + "` changed on disk")
                .subtitle("Since this editor opened it")
                .message("Overwriting replaces what was written there with your edits. To compare the"
                        + " two first, cancel and save from the editor, which offers a comparison.")
                .buttons(overwrite, discard, ButtonType.CANCEL)
                .danger(overwrite)
                .danger(discard)
                .safeDefault()
                .size(com.jfx.git.ui.dialogs.JfxDialog.Size.MD)
                .answer();
        if (choice == overwrite) return CloseOverExternalChange.OVERWRITE;
        if (choice == discard) return CloseOverExternalChange.DISCARD;
        return CloseOverExternalChange.CANCEL;
    }

    /**
     * Encodes and writes the editor buffer now, on the calling thread, before a close.
     *
     * @return what the write did, which the caller has to look at
     */
    private GitResult saveEditedFileSync() {
        String path = diffViewer.editingFilePath();
        if (path == null || path.isEmpty()) {
            return GitResult.failed("There is no file to save.");
        }
        byte[] bytes = diffViewer.getEncoding()
                .encode(diffViewer.textForSaving(), gitService.getWorkingTreeBytes(path));
        GitResult result = gitService.writeWorkingTreeFile(path, bytes);
        statusBar.report(result);
        return result;
    }

    /**
     * Releases the repository.
     *
     * <p>The backend is closed <em>on</em> this repository's lane, queued behind whatever
     * is already there, and the lane is retired with it: nothing this tab submits
     * afterwards runs. Stopping the scheduler cancels future ticks but cannot recall a
     * fetch it has already handed over. This used to wait for the lane to drain and then,
     * after five seconds, close the engine from the FX thread regardless - under the fetch
     * still running on it, which then failed on a repository that had been taken away. A
     * close that is a lane task cannot land under another call; see GitBackend's threading
     * note.
     *
     * <p>The wait for it stays, bounded: the repository's files are held open until it
     * runs, and whatever closes a tab - a test cleaning up, the user deleting the folder -
     * expects them released. A lane stuck past the bound closes the engine when it frees.
     * Only this repository's work is waited for: another tab's never delays this close.
     */
    public void close() {
        tabClosed = true;
        // Their closed() cancels what they still have running - listings, polls.
        closeViews();
        stopWatching();
        autoFetchScheduler.shutdown();
        snapshotScheduler.shutdown();
        if (!GitTaskRunner.closeRepository(gitService, gitService::close, CLOSE_DRAIN_TIMEOUT_MS)) {
            com.jfx.git.core.ActivityLog.note("Closing " + folderTitle
                    + " once its background git work finishes");
            return;
        }
        com.jfx.git.core.ActivityLog.note("Closed repository " + folderTitle);
    }

    /** How long a close waits for in-flight git work before leaving it to finish alone. */
    private static final long CLOSE_DRAIN_TIMEOUT_MS = 5_000;

    /** Set by {@link #close}, on the FX thread; see {@link #refreshAll}. */
    private boolean tabClosed;
}
