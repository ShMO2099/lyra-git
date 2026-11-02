package com.jfx.git.ui;

import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.Refusal;
import com.jfx.git.core.RepositoryCommands;
import com.jfx.git.ui.dialogs.DialogParts;
import com.jfx.git.ui.dialogs.Dialogs;
import com.jfx.git.ui.dialogs.JfxDialog;

import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Supplier;
import com.jfx.git.model.RefNames;
import com.jfx.git.model.RefSnapshot;
import com.jfx.git.model.Submodule;
import com.jfx.git.model.SubmoduleState;
import com.jfx.git.ui.SidebarModel.Section;

/**
 * Left-hand navigation: local branches, remote branches, tags and stashes.
 *
 * <p>Branches are shown as a tree so that {@code feature/x} and {@code feature/y}
 * nest under one folder. Each node carries its real ref name; the previous version
 * rendered {@code "emoji " + name} and recovered it with {@code substring(3)}, which
 * for remotes produced {@code origin/origin/main} and could never check out.
 *
 * <p>This pane draws the sections, their cells and menus, and runs every action. What
 * each section lists for the filter, the counts and the "Filtering n / total" line, which
 * sections and folders are open, and whether a redraw rebuilds a section or only redraws
 * its rows in place are decided by {@link SidebarModel}; Hide &amp; Solo's edits are made
 * by {@link HideSolo}.
 */
public class SidebarPane extends VBox {

    /**
     * The narrowest the sidebar goes. A branch row carries a name, an ahead/behind pair and
     * a hover action, and below about this the row cannot show a name and its tracking
     * counts at once - and a sidebar that cannot do that is just taking up space.
     */
    public static final double MIN_WIDTH = 210;

    private final com.jfx.git.core.GitBackend gitService;
    private final StatusBar statusBar;
    /** Runs everything the pane starts; see {@link OperationRunner}. */
    private final OperationRunner operations;

    /**
     * Told which branch the pointer is over, so the graph can fade the rest.
     *
     * <p>A name, or null when the pointer leaves. The sidebar deliberately does not hold
     * the graph's highlight itself: it knows what is under the cursor, and that is the
     * whole of its contribution.
     */
    private java.util.function.Consumer<String> onPreviewBranch = name -> { };

    private final TreeView<BranchTree.Node> localTree = new TreeView<>();
    private final TreeView<BranchTree.Node> remoteTree = new TreeView<>();
    private final ListView<String> tagsList = new ListView<>();
    private final ListView<com.jfx.git.model.StashEntry> stashList = new ListView<>();
    private final ListView<Submodule> submoduleList = new ListView<>();
    private final ListView<com.jfx.git.core.Worktrees.Worktree> worktreeList = new ListView<>();
    /** The features, releases and hotfixes under way; see {@link com.jfx.git.core.GitFlow}. */
    private final ListView<String> flowList = new ListView<>();

    private final javafx.scene.control.Label localCount = countBadge();
    private final javafx.scene.control.Label remoteCount = countBadge();
    private final javafx.scene.control.Label tagCount = countBadge();
    // STASHES, SUBMODULES and WORKTREES were built as plain TitledPanes: no count, and
    // the control's own title styling rather than .sidebar-section-title, so half the
    // sidebar's headings were a different size and weight from the other half.
    private final javafx.scene.control.Label stashCount = countBadge();
    private final javafx.scene.control.Label submoduleCount = countBadge();
    private final javafx.scene.control.Label worktreeCount = countBadge();
    private final javafx.scene.control.Label flowCount = countBadge();

    private static javafx.scene.control.Label countBadge() {
        javafx.scene.control.Label label = new javafx.scene.control.Label();
        label.getStyleClass().add("count-badge");
        // No pill until there is a number: an empty grey pill during loading read as a
        // count that had failed, not one still coming.
        label.visibleProperty().bind(label.textProperty().isNotEmpty());
        label.managedProperty().bind(label.visibleProperty());
        return label;
    }

    /**
     * What the sidebar's load found that the toolbar shows too; see {@link #setOnLoaded}.
     * The current branch is {@code refs}' - the same snapshot the sections were drawn from.
     */
    public interface BranchListener {
        void loaded(List<String> locals, RefSnapshot refs, int[] tracking, int stashes, String originUrl);
    }

    private BranchListener onLoaded;

    /** Shown in the remote section when there are no remotes at all. */
    private final javafx.scene.control.Button addRemoteButton = new javafx.scene.control.Button("Add a remote…");

    /** Hands each refresh's branch state on, so the toolbar need not read it again. */
    public void setOnLoaded(BranchListener listener) {
        this.onLoaded = listener;
    }

    private final TitledPane localPane;
    private final TitledPane remotePane;
    private final TitledPane tagsPane;
    private final TitledPane stashPane;
    private final TitledPane submodulePane;
    private final TitledPane worktreePane;
    private final TitledPane flowPane;
    /** Each section's pane, for the decisions the model makes by section. */
    private final java.util.EnumMap<Section, TitledPane> panes = new java.util.EnumMap<>(Section.class);

    /** This repository's Git Flow settings, or null when it is not set up for it. */
    private com.jfx.git.core.GitFlow.Config flowConfig;
    /**
     * Whether {@link #flowConfig} is current. The settings are six configuration keys,
     * which on the CLI engine is six subprocesses; they change when this application
     * changes them, so they are read once and again after that, not on every refresh.
     */
    private volatile boolean flowConfigKnown;

    /** Opens another repository in its own tab; used to step into a submodule. */
    private final java.util.function.Consumer<java.io.File> onOpenRepository;

    /** Moves the graph's own selection to a commit, given its hash. */
    private final java.util.function.Consumer<String> onSelectCommit;

    /**
     * What the sidebar shows, decided: the rows of each section for the filter, the counts,
     * which sections and folders are open, and what each redraw has to do. This pane draws it.
     */
    private final SidebarModel model = new SidebarModel();

    /** Hide &amp; Solo's edits, written where the graph's walk reads them. */
    private final HideSolo hideSolo = new HideSolo(HideSolo.PREFERENCES);

    /** The newest ref snapshot, from the graph page that landed last; see {@link #showRefs}. */
    private RefSnapshot refs = RefSnapshot.EMPTY;
    /** Whether a snapshot has arrived, so an empty one is still news. */
    private boolean refsKnown;
    /**
     * Whether refs are being read directly, the page having failed ({@link #readRefsDirectly}):
     * until they land, an own read that lands draws nothing, as what it would be drawn
     * beside is the last page's refs, not the repository's now.
     */
    private boolean refsPending;
    /** Whether {@link #refs} came while an own read was out, and is not drawn yet. */
    private boolean refsUndrawn;
    /** This sidebar's own last read, or null before the first lands. */
    private OwnRead own;
    /** Own reads asked for and not landed yet. */
    private int reading;

    /**
     * One filter for the whole sidebar, as commercial clients have: every section narrows to what
     * matches it at once. There were three - branches, remote branches, tags - each shown
     * only past fifteen rows, so finding "deepep" meant knowing which section to search.
     */
    private final javafx.scene.control.TextField sidebarFilter = new javafx.scene.control.TextField();
    /** "Filtering 53 / 655", with the way out beside it; shown only while filtering. */
    private final javafx.scene.control.Label filterStatus = new javafx.scene.control.Label();
    private final javafx.scene.control.Button clearFilter = new javafx.scene.control.Button("Clear filter");
    private javafx.scene.layout.HBox filterStatusRow;

    /** Selects a stash's row in the graph. */
    private java.util.function.Consumer<com.jfx.git.model.StashEntry> onRevealStash = stash -> { };

    /** Where the repository's tools open: this repository's tab; see {@link #setViewHost}. */
    private RepoViewHost viewHost = RepoViewHost.NONE;

    /** Set by the tab, so LFS, hooks and the repository's settings open inside it. */
    public void setViewHost(RepoViewHost host) {
        this.viewHost = host == null ? RepoViewHost.NONE : host;
    }

    /** What a click on a stash does: the tab selects its row in the graph. */
    public void setOnRevealStash(java.util.function.Consumer<com.jfx.git.model.StashEntry> reveal) {
        this.onRevealStash = reveal == null ? stash -> { } : reveal;
    }

    /** Selects a hidden ref's commit in the graph, if a shown branch reaches it. */
    private java.util.function.Consumer<String> onRevealHiddenRef = hash -> { };

    /**
     * What a click on a branch or tag Hide/Solo excludes does: the tab selects its commit
     * when a branch that is shown reaches it, paging history in if need be, and otherwise
     * says it is hidden - never reading all of history looking for a tip the walk leaves
     * out by design.
     */
    public void setOnRevealHiddenRef(java.util.function.Consumer<String> reveal) {
        this.onRevealHiddenRef = reveal == null ? hash -> { } : reveal;
    }

    /**
     * The local branch names from the last sidebar load, for anything on the FX thread
     * that needs them.
     *
     * <p>The command palette rebuilds itself on every Ctrl+P and used to ask the backend
     * for this list each time - a {@code git for-each-ref} subprocess on the thread that
     * draws, to populate a menu. The sidebar has already loaded exactly this on its
     * worker; there is no reason for a second answer to the same question.
     */
    public List<String> cachedLocalBranches() {
        return model.localNames();
    }

    /** The checked-out branch from the same load, blank when HEAD is detached. */
    public String cachedCurrentBranch() {
        return currentBranch();
    }

    /** The current branch the sections were drawn with, blank while HEAD is detached. */
    private String currentBranch() {
        return model.currentBranch();
    }

    /** What dropping one branch on another offers; shared with the graph's ref chips. */
    private final RefDropMenu refDrops;

    /** The remotes from the last sidebar load; for the command palette, as {@link #cachedLocalBranches}. */
    public List<String> cachedRemoteNames() {
        return model.remoteNames();
    }

    public SidebarPane(OperationRunner operations,
                       java.util.function.Consumer<java.io.File> onOpenRepository,
                       java.util.function.Consumer<String> onSelectCommit,
                       java.util.function.Consumer<String> onPreviewBranch) {
        this.operations = operations;
        this.gitService = operations.repository();
        this.statusBar = operations.statusBar();
        this.refDrops = new RefDropMenu(operations);
        refDrops.setRemoteNames(model::remoteNames);
        this.onOpenRepository = onOpenRepository;
        this.onSelectCommit = onSelectCommit;
        this.onPreviewBranch = onPreviewBranch == null
                ? name -> { }
                : onPreviewBranch;

        localPane = buildLocalPane();
        remotePane = buildRemotePane();
        tagsPane = buildTagsPane();
        stashPane = buildStashPane();
        submodulePane = buildSubmodulePane();
        worktreePane = buildWorktreePane();
        // Hidden until a load says there are any - most repositories have none, and the
        // two sections appearing then vanishing was the sidebar jumping on every open.
        for (TitledPane pane : List.of(submodulePane, worktreePane)) {
            pane.setVisible(false);
            pane.setManaged(false);
        }
        flowPane = buildFlowPane();
        panes.put(Section.LOCAL, localPane);
        panes.put(Section.REMOTE, remotePane);
        panes.put(Section.TAGS, tagsPane);
        panes.put(Section.STASHES, stashPane);
        panes.put(Section.GIT_FLOW, flowPane);
        panes.put(Section.SUBMODULES, submodulePane);
        panes.put(Section.WORKTREES, worktreePane);

        // Every section stays visible and independently collapsible, the way
        // commercial clients stack LOCAL / REMOTE / TAGS rather than hiding all but one.
        // Open or closed as the model has them - and it hears of every change, by the
        // user or by anything else, so the filter can put back how the user had them.
        panes.forEach((section, pane) -> {
            pane.setExpanded(model.open(section));
            pane.expandedProperty().addListener((obs, was, now) -> model.opened(section, now));
        });
        // Absent until the repository is set up for Git Flow, which most are not.
        flowPane.setVisible(false);
        flowPane.setManaged(false);

        VBox stack = new VBox(localPane, remotePane, tagsPane, stashPane, flowPane,
                submodulePane, worktreePane);
        stack.getStyleClass().add("sidebar-sections");

        ScrollPane scroll = new ScrollPane(stack);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sidebar-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        sidebarScroll = scroll;
        sectionStack = stack;
        // The sections share the sidebar's height, so they follow it. Deferred: the
        // viewport's bounds are set while the sidebar is being laid out, and a size
        // changed from inside a layout pass is not acted on until something else asks
        // for a layout - which is how the heights came to be one step behind.
        scroll.viewportBoundsProperty().addListener((obs, old, bounds) -> {
            if (old == null || old.getHeight() != bounds.getHeight()) {
                javafx.application.Platform.runLater(this::resizeAll);
            }
        });
        followRows(localPane, localTree);
        followRows(remotePane, remoteTree);
        followRows(tagsPane, tagsList);
        followRows(stashPane, stashList);
        followRows(submodulePane, submoduleList);
        followRows(worktreePane, worktreeList);
        followRows(flowPane, flowList);
        resizeAll();

        // The filter sits above the sections, outside the scroll pane whose viewport they
        // share - so it never scrolls away, and never counts against their height.
        getChildren().addAll(buildFilterHeader(), scroll);
        // A branch row carries a name, an ahead/behind pair and a hover action. At 240
        // the name was the only part with any give, so anything longer than
        // "feat/activity-log" arrived elided while the columns beside it kept their
        // width. The floor rises with it; see MIN_WIDTH.
        setMinWidth(MIN_WIDTH);
        setPrefWidth(280);
    }

    /**
     * One section of the sidebar: its pane, the tree or list inside it, and how many rows
     * the tree or list is showing.
     */
    private record SectionSize(TitledPane pane, javafx.scene.control.Control rowsControl,
                               java.util.function.IntSupplier rows) {
        double wanted() {
            return Math.max(1, rows.getAsInt()) * UiScale.ROW_HEIGHT + 8;
        }

        /** The least an open section is given: three rows, or all of it if it is shorter. */
        double least() {
            return Math.min(wanted(), 3 * UiScale.ROW_HEIGHT + 8);
        }
    }

    private final List<SectionSize> sections = new java.util.ArrayList<>();

    /** The padding under a section's content: {@code .sidebar-sections .titled-pane > .content}. */
    private static final double SECTION_CONTENT_PADDING = 4;

    /** The sidebar's own scroll pane; its viewport is the height the sections share. */
    private ScrollPane sidebarScroll;
    /** The stack of section panes inside it. */
    private VBox sectionStack;

    /**
     * Shares the sidebar's height among its open sections.
     *
     * <p>The sidebar does not scroll. A section whose rows fit is exactly as tall as its
     * rows; what is left over is divided among the sections that are longer than their
     * share, and each of those scrolls inside itself. So a repository with eleven
     * branches shows eleven rows and no scrollbar anywhere, and one with four hundred
     * remote branches and two hundred tags shows two lists that each fill their half of
     * the sidebar with one scrollbar apiece.
     *
     * <p>It has been three other things. A fixed 260 pixels per section, which reserved a
     * box that size to show one branch. Then the rows' height up to a cap of ten, which
     * made LOCAL scroll inside a third of the sidebar. Then up to a cap of forty with the
     * whole sidebar scrolling as a page - under which a 200-tag list was a slot taller
     * than the sidebar, scrolling inside a sidebar that was scrolling too: two scrollbars
     * side by side, and no way to see a list's heading and its end at once.
     */
    private void resizeAll() {
        if (sidebarScroll == null) {
            return;
        }
        // Only sections that are there: SUBMODULES and WORKTREES are taken off the
        // sidebar entirely when the repository has none.
        List<SectionSize> shown = sections.stream().filter(s -> s.pane().isManaged()).toList();
        List<SectionSize> open = shown.stream().filter(s -> s.pane().isExpanded()).toList();
        double viewport = sidebarScroll.getViewportBounds().getHeight();
        if (viewport <= 0) {
            // Not laid out yet: every section its own height, to be shared out once
            // there is a height to share.
            for (SectionSize section : open) {
                apply(section, section.wanted());
            }
            return;
        }

        double room = Math.floor(Math.max(0, viewport - chrome(shown, open)));
        // Shortest first, each given what it asks for or an equal share of what is
        // left, whichever is less - so the short ones are satisfied exactly and the
        // long ones split the remainder evenly.
        List<SectionSize> shortestFirst = new java.util.ArrayList<>(open);
        shortestFirst.sort(java.util.Comparator.comparingDouble(SectionSize::wanted));
        double remaining = room;
        for (int i = 0; i < shortestFirst.size(); i++) {
            SectionSize section = shortestFirst.get(i);
            double share = remaining / (shortestFirst.size() - i);
            double given = Math.floor(Math.max(section.least(), Math.min(section.wanted(), share)));
            apply(section, given);
            remaining = Math.max(0, remaining - given);
        }
    }

    private static void apply(SectionSize section, double height) {
        javafx.scene.control.Control control = section.rowsControl();
        control.setMinHeight(Math.min(height, section.least()));
        control.setPrefHeight(height);
        control.setMaxHeight(height);
    }

    /**
     * What the headings, filter boxes and padding take, so the rest can go to rows.
     *
     * <p>Added up from the parts: each shown section's title, and for an open one whatever
     * its content holds besides the rows (a filter box, the gap under it) and the padding
     * round that. Not the pane's own preferred height - that includes how far open the
     * pane's skin believes it is, and the skin hears about a section opening <i>after</i>
     * this does, so a section just opened still reported the height of a closed one and
     * its heading and filter went uncounted.
     */
    private double chrome(List<SectionSize> shown, List<SectionSize> open) {
        double chrome = sectionStack == null ? 0
                : sectionStack.snappedTopInset() + sectionStack.snappedBottomInset()
                        + sectionStack.getSpacing() * Math.max(0, shown.size() - 1);
        for (SectionSize section : shown) {
            TitledPane pane = section.pane();
            javafx.scene.Node title = skinPart(pane, 0, ".title");
            // Before the first layout there is no title node to ask; a heading is about
            // thirty pixels, and the first layout replaces the guess.
            // Snapped, as the layout snaps it: a heading that prefers 31.3 pixels is laid
            // out at 32, and four of those are the difference between fitting and not.
            chrome += title == null ? 30 : pane.snapSizeY(title.prefHeight(-1));
            // And the pane's own edge: the hairline that separates one section from the next.
            chrome += pane.snappedTopInset() + pane.snappedBottomInset();
            if (!open.contains(section)) {
                continue;
            }
            javafx.scene.Node content = pane.getContent();
            double rows = Math.max(0, section.rowsControl().prefHeight(-1));
            chrome += content == null ? 0 : Math.max(0, content.prefHeight(-1) - rows);
            // The padding round the content. Read off the node once its CSS has been
            // applied; a section opened this instant has not had that pass yet and
            // reports none, so never less than what the stylesheet gives it.
            double padding = skinPart(pane, 1, ".content") instanceof javafx.scene.layout.Region holder
                    ? holder.snappedTopInset() + holder.snappedBottomInset() : 0;
            chrome += Math.max(SECTION_CONTENT_PADDING, padding);
        }
        return chrome;
    }

    /**
     * A section's title or content node, found once and kept.
     *
     * <p>{@code lookup} parses its selector and walks the pane's skin on every call, and
     * this ran for every section on every resize - every folder opened, every keystroke
     * in the filter, every refresh. A pane's skin keeps the same two nodes for its life;
     * one that is replaced (a skin swap) is found again because it leaves the pane.
     */
    private javafx.scene.Node skinPart(TitledPane pane, int which, String selector) {
        javafx.scene.Node[] parts = skinParts.computeIfAbsent(pane, p -> new javafx.scene.Node[2]);
        javafx.scene.Node part = parts[which];
        if (part == null || !isInside(part, pane)) {
            part = pane.lookup(selector);
            parts[which] = part;
        }
        return part;
    }

    private static boolean isInside(javafx.scene.Node node, javafx.scene.Parent ancestor) {
        for (javafx.scene.Parent p = node.getParent(); p != null; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private final java.util.Map<TitledPane, javafx.scene.Node[]> skinParts = new java.util.IdentityHashMap<>();

    private void addSection(TitledPane pane, javafx.scene.control.Control rowsControl,
                            java.util.function.IntSupplier rows) {
        sections.add(new SectionSize(pane, rowsControl, rows));
        // Opening or closing a section changes what the others have to share. The
        // slide animation is off: mid-slide the stack is briefly taller than the
        // sidebar, which flashes a scrollbar at every click.
        pane.setAnimated(false);
        pane.expandedProperty().addListener((obs, was, now) -> resizeAll());
        pane.managedProperty().addListener((obs, was, now) -> resizeAll());
    }

    /** Keeps {@code tree} as tall as the rows it shows: branches and the open folders above them. */
    private void followRows(TitledPane pane, TreeView<BranchTree.Node> tree) {
        addSection(pane, tree, () -> visibleRows(tree.getRoot()));
        javafx.event.EventHandler<TreeItem.TreeModificationEvent<BranchTree.Node>> onChange =
                e -> resizeAll();
        tree.rootProperty().addListener((obs, old, root) -> {
            if (old != null) {
                old.removeEventHandler(TreeItem.<BranchTree.Node>expandedItemCountChangeEvent(), onChange);
            }
            if (root != null) {
                // Every expand, collapse, addition and removal anywhere under the root
                // arrives here, at the moment it happens rather than at the next layout.
                root.addEventHandler(TreeItem.<BranchTree.Node>expandedItemCountChangeEvent(), onChange);
            }
            resizeAll();
        });
    }

    /** Keeps {@code list} as tall as its items. */
    private void followRows(TitledPane pane, ListView<?> list) {
        addSection(pane, list, () -> list.getItems().size());
        javafx.beans.InvalidationListener onChange = o -> resizeAll();
        list.getItems().addListener(onChange);
        list.itemsProperty().addListener((obs, old, items) -> {
            if (old != null) old.removeListener(onChange);
            if (items != null) items.addListener(onChange);
            resizeAll();
        });
    }

    /** The rows under {@code item} that are on show: its children, and theirs where a folder is open. */
    private static int visibleRows(TreeItem<?> item) {
        if (item == null) {
            return 0;
        }
        int rows = 0;
        for (TreeItem<?> child : item.getChildren()) {
            rows++;
            if (child.isExpanded()) {
                rows += visibleRows(child);
            }
        }
        return rows;
    }

    /** The one filter, and beneath it how much it matched and the way to stop filtering. */
    private javafx.scene.Node buildFilterHeader() {
        String keys = new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.F,
                javafx.scene.input.KeyCombination.SHORTCUT_DOWN, javafx.scene.input.KeyCombination.ALT_DOWN)
                .getDisplayText();
        sidebarFilter.setPromptText("Filter (" + keys + ")");
        sidebarFilter.getStyleClass().addAll("sidebar-filter", "sidebar-global-filter");
        // A pause in typing, not every keystroke: each pass refilters and redraws every
        // section, which on five thousand remote branches is a frame or two per
        // character - for intermediate queries nobody reads. Emptying the box is a single
        // step rather than the start of a burst, so it puts everything back at once.
        filterPause.setOnFinished(e -> applyQuery());
        sidebarFilter.textProperty().addListener((obs, old, text) -> {
            if (text == null || text.strip().isEmpty()) {
                filterPause.stop();
                applyQuery();
            } else {
                filterPause.playFromStart();
            }
        });
        // Esc empties the box first; a second Esc is the window's.
        sidebarFilter.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && !sidebarFilter.getText().isEmpty()) {
                sidebarFilter.clear();
                e.consume();
            }
        });
        filterStatus.getStyleClass().addAll("t-caption", "sidebar-filter-status");
        clearFilter.getStyleClass().addAll("btn-sm", "btn-ghost", "sidebar-filter-clear");
        clearFilter.setOnAction(e -> sidebarFilter.clear());
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, Priority.ALWAYS);
        filterStatusRow = new javafx.scene.layout.HBox(UiScale.STEP, filterStatus, spacer, clearFilter);
        filterStatusRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        filterStatusRow.setVisible(false);
        filterStatusRow.setManaged(false);
        VBox header = new VBox(UiScale.SNUG, sidebarFilter, filterStatusRow);
        header.getStyleClass().add("sidebar-filter-header");
        return header;
    }

    /**
     * Puts the caret in the sidebar's filter. Ctrl+Alt+F arrives by way of
     * {@code RepoTab.focusSidebarFilter}, which shows a hidden sidebar first.
     */
    public void focusFilter() {
        sidebarFilter.requestFocus();
        sidebarFilter.selectAll();
    }

    /** The sidebar's filter, for tests. */
    javafx.scene.control.TextField filterField() {
        return sidebarFilter;
    }

    /** Waits for typing to pause; see {@link #buildFilterHeader}. */
    private final javafx.animation.PauseTransition filterPause =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(FILTER_DELAY_MILLIS));

    /** How long the filter waits for typing to pause. */
    static final int FILTER_DELAY_MILLIS = 120;

    /**
     * The query the sections are drawn for: what was typed, as of the last pause.
     *
     * <p>Not the field's live text. Between a keystroke and the pause the lists still
     * hold the last query's matches, and a row redrawn in that gap - a scroll, a hover -
     * must mark what its list was filtered by, not what is half-typed.
     */
    private String appliedQuery = "";

    /** Takes up whatever is in the filter box now. */
    private void applyQuery() {
        String text = sidebarFilter.getText();
        appliedQuery = text == null ? "" : text.strip();
        applyFilter();
    }

    /** Whether typed text is still waiting for the pause; for tests to wait on. */
    boolean filterPending() {
        return filterPause.getStatus() == javafx.animation.Animation.Status.RUNNING;
    }

    /** What is typed in the filter, trimmed; empty when not filtering. */
    private String query() {
        return appliedQuery;
    }

    /**
     * A name with the filter's match marked: before, the match, after - as three labels,
     * so the match can be coloured. Built once per cell and refilled; a row redrawn on
     * every scroll tick used to make a box and three labels for it each time.
     */
    private static final class MarkedName {
        final javafx.scene.control.Label before;
        final javafx.scene.control.Label hit;
        final javafx.scene.control.Label after;
        final javafx.scene.layout.HBox box;

        MarkedName(String styleClass) {
            before = part(styleClass);
            // Both ends give way when the row is narrow, the head from its start, so a
            // match near the end of a long name stays in view: with the head pinned to its
            // full width it pushed the match, the tracking counts and the eye off the row.
            before.setMinWidth(0);
            before.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
            hit = part(styleClass);
            hit.getStyleClass().add("filter-match");
            after = part(styleClass);
            after.setMinWidth(0);
            box = new javafx.scene.layout.HBox(before, hit, after);
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            box.setMinWidth(0);
            box.getStyleClass().add("filter-marked");
        }

        /** Marks {@code q} in {@code text}; false, touching nothing, when there is no match. */
        boolean fill(String text, String q) {
            int at = q.isEmpty() ? -1 : SidebarModel.matchAt(text, q);
            if (at < 0) return false;
            before.setText(text.substring(0, at));
            hit.setText(text.substring(at, at + q.length()));
            after.setText(text.substring(at + q.length()));
            return true;
        }

        private static javafx.scene.control.Label part(String styleClass) {
            javafx.scene.control.Label label = new javafx.scene.control.Label();
            if (styleClass != null) label.getStyleClass().add(styleClass);
            label.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            return label;
        }
    }

    /**
     * A row's icon and name: the cell's own text when there is nothing to mark, a row
     * with the filter's match marked when there is. One per cell, refilled per row.
     */
    private final class NamedRow {
        private final javafx.scene.Node icon;
        private final MarkedName marked = new MarkedName(null);
        private final javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(UiScale.SNUG);

        NamedRow(javafx.scene.Node icon) {
            this.icon = icon;
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        }

        void show(javafx.scene.control.Cell<?> cell, String text) {
            if (!marked.fill(text, query())) {
                cell.setText(text);
                cell.setGraphic(icon);
                return;
            }
            cell.setText(null);
            // The icon may have been the cell's own graphic last time; a node has one
            // parent, so it is put back into the row each time the row is used.
            if (row.getChildren().size() != 2 || row.getChildren().get(0) != icon) {
                row.getChildren().setAll(icon, marked.box);
            }
            cell.setGraphic(row);
        }
    }

    /**
     * Draws every section from the model through the filter: the trees and lists, the
     * counts, the "Filtering n / total" line, and which sections are open.
     *
     * <p>Each section is drawn only as far as the model says it changed. Every auto-fetch,
     * every watcher event and every keystroke came through here and rebuilt both branch
     * trees - a TreeItem per branch, sorted - and re-set and refreshed every list, which on
     * five thousand remote branches and a thousand tags was most of a frame and twenty
     * megabytes of garbage for a refresh that found nothing new. New rows rebuild a section;
     * new facts only redraw the rows on screen.
     */
    private void applyFilter() {
        if (!model.loaded()) return;
        SidebarModel.Answer answer = model.answer(query());

        switch (answer.change(Section.LOCAL)) {
            case REBUILD -> localTree.setRoot(localRoot(answer.names(Section.LOCAL)));
            case REDRAW -> redrawRows(localTree);
            case NONE -> { }
        }
        switch (answer.change(Section.REMOTE)) {
            case REBUILD -> remoteTree.setRoot(remoteRoot(answer));
            case REDRAW -> redrawRows(remoteTree);
            case NONE -> { }
        }
        redraw(tagsList, answer.names(Section.TAGS), answer.change(Section.TAGS));
        redraw(stashList, answer.stashes(), answer.change(Section.STASHES));
        redraw(submoduleList, answer.submodules(), answer.change(Section.SUBMODULES));
        redraw(worktreeList, answer.worktrees(), answer.change(Section.WORKTREES));
        redraw(flowList, answer.names(Section.GIT_FLOW), answer.change(Section.GIT_FLOW));

        boolean filtering = answer.filtering();
        badge(localCount, answer.count(Section.LOCAL), filtering);
        badge(remoteCount, answer.count(Section.REMOTE), filtering);
        badge(tagCount, answer.count(Section.TAGS), filtering);
        badge(stashCount, answer.count(Section.STASHES), filtering);
        badge(submoduleCount, answer.count(Section.SUBMODULES), filtering);
        badge(worktreeCount, answer.count(Section.WORKTREES), filtering);
        badge(flowCount, answer.count(Section.GIT_FLOW), filtering);

        filterStatus.setText(answer.status());
        filterStatusRow.setVisible(filtering);
        filterStatusRow.setManaged(filtering);

        // A section the model already has as it is set to is left alone: setting it the
        // same is no change.
        panes.forEach((section, pane) -> pane.setExpanded(answer.open(section)));
        resizeAll();
    }

    /**
     * Sets {@code list}'s rows when the model says they changed, or redraws the rows on
     * screen when only what its cells read did.
     */
    private <T> void redraw(ListView<T> list, List<T> items, SidebarModel.Change change) {
        switch (change) {
            case REBUILD -> list.getItems().setAll(items);
            case REDRAW -> redrawRows(list);
            case NONE -> { }
        }
    }

    /** A row that can draw itself again in place; see {@link #redrawRows}. */
    private interface SidebarRow {
        /** The tree or list the row belongs to now. */
        javafx.scene.control.Control owner();

        void redraw();
    }

    /** Every row cell made, held weakly: a list drops its cells when it is replaced. */
    private final java.util.Set<SidebarRow> sidebarRows =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * Fills the rows of {@code owner} again from what the sidebar knows now, keeping the
     * cells it has. Not {@code refresh()}, which throws every cell away and builds new ones -
     * and these cells build their nodes and menus once, in their constructors.
     */
    private void redrawRows(javafx.scene.control.Control owner) {
        for (SidebarRow row : List.copyOf(sidebarRows)) {
            if (row.owner() == owner) {
                row.redraw();
            }
        }
    }

    /** Each badge's filter mark, made once: a fresh icon per badge per keystroke was garbage. */
    private final java.util.Map<javafx.scene.control.Label, javafx.scene.Node> filterMarks =
            new java.util.IdentityHashMap<>();

    /** A section's count, marked as filtered while filtering; the model says which count. */
    private void badge(javafx.scene.control.Label badge, int count, boolean filtering) {
        badge.setText(String.valueOf(count));
        boolean marked = badge.getStyleClass().contains("count-filtered");
        if (filtering) {
            if (!marked) badge.getStyleClass().add("count-filtered");
            badge.setGraphic(filterMarks.computeIfAbsent(badge, b -> Icons.filter()));
        } else {
            if (marked) badge.getStyleClass().remove("count-filtered");
            badge.setGraphic(null);
        }
    }

    /** The local tree, each folder open or closed as the model remembers it. */
    private TreeItem<BranchTree.Node> localRoot(List<String> names) {
        TreeItem<BranchTree.Node> root = BranchTree.build(names, List.of(),
                path -> model.folderOpen(Section.LOCAL, path));
        rememberFolders(root, Section.LOCAL);
        return root;
    }

    /** Tells the model each folder the user opens or closes; it ignores those the filter opens. */
    private void rememberFolders(TreeItem<BranchTree.Node> parent, Section tree) {
        for (TreeItem<BranchTree.Node> child : parent.getChildren()) {
            if (child.getChildren().isEmpty()) continue;
            String path = BranchTree.pathOf(child);
            child.expandedProperty().addListener((obs, was, now) -> model.folderToggled(tree, path, now));
            rememberFolders(child, tree);
        }
    }

    /**
     * A section title with its count beside it.
     *
     * <p>A graphic rather than the pane's own text, so the number can be a badge: "REMOTE
     * 412" as a title with a digit appended reads as two things, and the digit is the part
     * that gets scanned.
     */
    private static javafx.scene.layout.HBox sectionTitle(String title, javafx.scene.control.Label badge) {
        javafx.scene.control.Label label = new javafx.scene.control.Label(title);
        label.getStyleClass().add("sidebar-section-title");
        javafx.scene.layout.HBox box = new javafx.scene.layout.HBox(UiScale.STEP, label, badge);
        box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return box;
    }

    // ------------------------------------------------------------------
    // Local branches
    // ------------------------------------------------------------------

    private TitledPane buildLocalPane() {
        localTree.getStyleClass().add("branch-tree");
        localTree.setShowRoot(false);
        localTree.setCellFactory(tv -> previewOnHover(new LocalBranchCell()));

        ContextMenu paneMenu = new ContextMenu();
        MenuItem newBranch = new MenuItem("New branch…");
        newBranch.setOnAction(e -> promptNewBranch(currentBranch()));
        // Merged branches, and ones whose branch on the remote is gone, in one list.
        MenuItem cleanUp = new MenuItem("Clean up branches…");
        cleanUp.setOnAction(e -> openBranchCleanup());

        MenuItem sparseCheckout = new MenuItem("Sparse Checkout…");
        sparseCheckout.setOnAction(e -> openSparseCheckoutDialog());

        // This repository's own settings, its hooks and its housekeeping: the Repository
        // section of the Settings tab, opened on this repository.
        MenuItem repoSettings = new MenuItem("Repository settings…");
        repoSettings.setOnAction(e -> openRepositorySettings());

        // Bulk Hide & Solo, the commercial clients' right-click-the-section-header shortcut for
        // "hide/show everything in here" rather than one branch at a time. The branch
        // you are standing on is excluded from "Hide all" the same way it is exempted
        // from the per-branch toggle (see LocalBranchCell.branchGraphic).
        MenuItem hideAll = new MenuItem("Hide all branches from graph");
        hideAll.setOnAction(e -> hideAllFromGraph(model.hideable(Section.LOCAL)));
        MenuItem showAll = new MenuItem("Show all branches in graph");
        showAll.setOnAction(e -> showAllInGraph(model.fullNames(Section.LOCAL)));
        // The way in for a repository that is not set up yet, where there is no
        // GIT FLOW section to right-click.
        MenuItem gitFlow = new MenuItem("Git Flow…");
        gitFlow.setOnAction(e -> promptFlowSetup());
        MenuItem lfs = new MenuItem("Git LFS…");
        lfs.setOnAction(e -> promptLfs());
        // The SUBMODULES section hides itself until the repository has one, so its own
        // "Add submodule…" cannot add the first.
        MenuItem addSubmodule = new MenuItem("Add submodule…");
        addSubmodule.setOnAction(e -> promptAddSubmodule());
        paneMenu.getItems().addAll(newBranch, cleanUp, new SeparatorMenuItem(), sparseCheckout,
                repoSettings, gitFlow, lfs, addSubmodule, new SeparatorMenuItem(), hideAll, showAll);
        localTree.setContextMenu(paneMenu);

        localTree.setFixedCellSize(UiScale.ROW_HEIGHT);

        TitledPane pane = new TitledPane();
        pane.setGraphic(sectionTitle("LOCAL", localCount));
        pane.setContent(localTree);
        pane.setAnimated(false);
        return pane;
    }

    /**
     * Makes a branch row fade the graph while the pointer is on it.
     *
     * <p>Registered on the cell once rather than inside {@code updateItem}: cells are
     * recycled constantly, and a handler that reads {@link TreeCell#getItem()} when the
     * event arrives is always talking about the row actually under the cursor.
     */
    private <C extends TreeCell<BranchTree.Node>> C previewOnHover(C cell) {
        cell.setOnMouseEntered(e -> {
            BranchTree.Node node = cell.getItem();
            onPreviewBranch.accept(node == null || node.isFolder() ? null : node.fullName());
        });
        cell.setOnMouseExited(e -> onPreviewBranch.accept(null));
        return cell;
    }

    /**
     * One local branch, or a folder of them.
     *
     * <h2>Built once, filled per row</h2>
     * A tree recycles a screenful of cells and calls {@link #updateItem} on them for every
     * row that scrolls past or redraws. This cell used to build its row there - a box, an
     * icon, labels, the eye button with its own tooltip - plus a tooltip for the row and a
     * fourteen-item context menu, every time. Everything is made once per cell now, the way
     * {@code GitGraphCell} does it, and every action reads the row the cell is showing when
     * it fires.
     */
    private class LocalBranchCell extends TreeCell<BranchTree.Node> implements SidebarRow {
        @Override
        public javafx.scene.control.Control owner() {
            return getTreeView();
        }

        @Override
        public void redraw() {
            if (!isEmpty() && getItem() != null) {
                updateItem(getItem(), false);
            }
        }

        private final javafx.scene.Node currentMark = Icons.currentBranch();
        private final javafx.scene.Node localMark = Icons.localBranch();
        private final javafx.scene.Node folderMark = Icons.folder();
        private final javafx.scene.control.Label name = new javafx.scene.control.Label();
        private final MarkedName marked = new MarkedName("branch-name");
        private final javafx.scene.control.Label ahead = trackLabel("track-ahead");
        private final javafx.scene.control.Label behind = trackLabel("track-behind");
        /** "gone": the upstream was deleted on the remote, so this is probably done with. */
        private final javafx.scene.control.Label gone = goneLabel();
        private final javafx.scene.Node eyeOpen = Icons.eyeOpen();
        private final javafx.scene.Node eyeClosed = Icons.eyeClosed();
        private final javafx.scene.control.Button eye;
        private final javafx.scene.control.Tooltip eyeTip = new javafx.scene.control.Tooltip();
        private final javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(UiScale.SNUG);
        private final javafx.scene.control.Tooltip rowTip = new javafx.scene.control.Tooltip();
        private LocalBranchMenu menu;

        LocalBranchCell() {
            // A row is as wide as the sidebar and no wider. Left to itself a cell asks for
            // the width of its longest name, and the tree answers with a horizontal
            // scrollbar; a name that does not fit is shortened instead, and is in the
            // tooltip in full.
            setPrefWidth(0);
            sidebarRows.add(this);

            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            row.getStyleClass().add("branch-row");
            name.getStyleClass().add("branch-name");
            name.setMinWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);
            javafx.scene.layout.HBox.setHgrow(name, Priority.ALWAYS);
            javafx.scene.layout.HBox.setHgrow(marked.box, Priority.ALWAYS);

            eye = Icons.button(eyeOpen, "", null);
            eye.getStyleClass().addAll("btn-sm", "branch-eye-toggle");
            eye.setTooltip(eyeTip);
            eye.setOnAction(e -> {
                String full = currentFullName();
                if (full != null) toggleHideFromGraph(model.local(full).fullName());
            });

            rowTip.setOnShowing(e -> {
                String full = currentFullName();
                rowTip.setText(full == null ? "" : full);
            });

            setOnMouseClicked(e -> {
                String full = currentFullName();
                if (full == null) return;
                if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY || onAButton(e)) return;
                if (e.getClickCount() == 1) {
                    // As in commercial clients: the branch's tip, selected in the graph.
                    SidebarModel.LocalRow row = model.local(full);
                    reveal(row.tip(), row.fullName());
                } else if (e.getClickCount() == 2 && !full.equals(currentBranch())) {
                    checkoutWithOrphanGuard(full, "Checking out " + full);
                }
            });
            // Registered once; reads the branch at the moment of the gesture, and a folder
            // row - no branch - neither drags nor takes a drop.
            installRefDrag(this, () -> {
                String full = currentFullName();
                return full == null ? null : RefDropMenu.Ref.local(full);
            });
        }

        /** The branch on this row, or null for a folder or an empty row. */
        private String currentFullName() {
            BranchTree.Node node = isEmpty() ? null : getItem();
            return node == null || (node.isFolder() && node.fullName() == null) ? null : node.fullName();
        }

        @Override
        protected void updateItem(BranchTree.Node node, boolean empty) {
            super.updateItem(node, empty);
            setClass(this, "branch-current", false);
            setClass(this, "branch-hidden", false);
            setClass(this, "branch-solo", false);

            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                setContextMenu(null);
                setClass(this, "branch-folder", false);
                return;
            }

            if (node.isFolder() && node.fullName() == null) {
                setText(node.label());
                setGraphic(folderMark);
                setClass(this, "branch-folder", true);
                setTooltip(null);
                setContextMenu(null);
                return;
            }
            setClass(this, "branch-folder", false);

            SidebarModel.LocalRow branch = model.local(node.fullName());
            // The whole row is the graphic: the name has to sit between the mark and the
            // controls at the trailing edge, and a cell's own text can only come after
            // its graphic - which is how the eye came to stand between the mark and the
            // name, pushing the name off the row.
            setText(null);
            fillRow(node.label(), branch);
            setGraphic(row);
            setTooltip(rowTip);
            if (branch.current()) {
                setClass(this, "branch-current", true);
            } else if (branch.solo()) {
                setClass(this, "branch-solo", true);
            } else if (branch.hidden()) {
                setClass(this, "branch-hidden", true);
            }

            if (menu == null) {
                menu = new LocalBranchMenu(this::currentFullName);
            }
            menu.update(branch);
            setContextMenu(menu.menu);
        }

        /**
         * One branch: its mark, its name, how far it is from its upstream, and the eye.
         *
         * <p>Blue ahead, amber behind: work you have that origin does not is yours to
         * push, and work origin has that you do not is the half that will cost you a
         * merge. The status bar has shown this for the current branch for a while; there
         * is no reason the other branches should have to be checked out to find out.
         *
         * <p>The name is the part that gives way when the sidebar is narrow. The counts
         * and the eye keep their place at the trailing edge.
         */
        private void fillRow(String label, SidebarModel.LocalRow branch) {
            String full = branch.name();
            parts.clear();
            parts.add(branch.current() ? currentMark : localMark);
            if (marked.fill(label, query())) {
                parts.add(marked.box);
            } else {
                name.setText(label);
                parts.add(name);
            }
            if (branch.ahead() > 0) {
                ahead.setText(UP_ARROW + branch.ahead());
                parts.add(ahead);
            }
            if (branch.behind() > 0) {
                behind.setText(DOWN_ARROW + branch.behind());
                parts.add(behind);
            }
            if (branch.gone()) {
                String upstream = branch.upstream();
                gone.getTooltip().setText((upstream == null ? "Its upstream" : upstream)
                        + " has been deleted on the remote. Clean up branches lists it with the others.");
                parts.add(gone);
            }
            // Hide & Solo does not apply to the branch you are standing on - hiding your
            // own checked-out branch from the graph you are looking at is not a state
            // the commercial clients' toggle offers either. Everything else gets the eye, shown
            // when the row is pointed at and whenever the branch is hidden - then it is
            // the only thing saying why the branch is missing from the graph.
            if (branch.hideable()) {
                boolean hidden = branch.hidden();
                String what = hidden ? "Show " + full + " in the graph" : "Hide " + full + " from the graph";
                eye.setGraphic(hidden ? eyeClosed : eyeOpen);
                eye.setAccessibleText(what);
                eyeTip.setText(what);
                parts.add(eye);
            }
            // Only when the shape changes: re-adding a node re-applies its CSS, and most
            // rows a recycled cell moves through have the same parts as the last.
            if (!row.getChildren().equals(parts)) {
                row.getChildren().setAll(parts);
            }
        }

        /** The row's parts for the branch being drawn; reused. */
        private final java.util.List<javafx.scene.Node> parts = new java.util.ArrayList<>(6);
    }

    /** Ahead and behind, as the arrows the rows put before the counts. */
    private static final String UP_ARROW = String.valueOf((char) 0x2191);
    private static final String DOWN_ARROW = String.valueOf((char) 0x2193);

    private static javafx.scene.control.Label goneLabel() {
        javafx.scene.control.Label label = trackLabel("branch-gone");
        label.setText("gone");
        label.setTooltip(new javafx.scene.control.Tooltip());
        return label;
    }

    private static javafx.scene.control.Label trackLabel(String styleClass) {
        javafx.scene.control.Label label = new javafx.scene.control.Label();
        label.getStyleClass().add(styleClass);
        label.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return label;
    }

    /** Adds or removes one style class, only when that changes anything. */
    private static void setClass(javafx.scene.Node node, String styleClass, boolean on) {
        var classes = node.getStyleClass();
        boolean present = classes.contains(styleClass);
        if (on && !present) {
            classes.add(styleClass);
        } else if (!on && present) {
            classes.remove(styleClass);
        }
    }

    /**
     * Makes a branch row something that can be dragged, and dropped on.
     *
     * <p>The same for local and remote rows, and for the graph's ref chips: what is on
     * the dragboard is a {@link RefDropMenu.Ref}, so a branch dragged out of the graph
     * can be dropped here and the other way about, and text dragged in from somewhere
     * else is not mistaken for a branch.
     *
     * <p>Installed once per cell, reading the branch from the cell when the gesture
     * happens: cells are recycled, and handlers installed per row were four lambdas per
     * row per scroll tick. A row with no branch - a folder, nothing - leaves the events
     * alone, as having no handlers did.
     */
    private void installRefDrag(javafx.scene.control.Cell<?> cell, Supplier<RefDropMenu.Ref> rowRef) {
        cell.setOnDragDetected(e -> {
            RefDropMenu.Ref ref = rowRef.get();
            if (ref == null) return;
            Dragboard db = cell.startDragAndDrop(TransferMode.ANY);
            ClipboardContent content = new ClipboardContent();
            content.putString(ref.encode());
            db.setContent(content);
            RefDropMenu.dragImage(db, cell);
            e.consume();
        });
        cell.setOnDragOver(e -> {
            RefDropMenu.Ref ref = rowRef.get();
            if (ref == null) return;
            if (e.getGestureSource() != cell
                    && RefDropMenu.accepts(RefDropMenu.Ref.decode(e.getDragboard().getString()), ref)) {
                e.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                RefDropMenu.markTarget(cell, true);
            }
            e.consume();
        });
        cell.setOnDragExited(e -> RefDropMenu.markTarget(cell, false));
        cell.setOnDragDropped(e -> {
            RefDropMenu.Ref ref = rowRef.get();
            if (ref == null) return;
            RefDropMenu.markTarget(cell, false);
            RefDropMenu.Ref source = RefDropMenu.Ref.decode(e.getDragboard().getString());
            boolean ok = RefDropMenu.accepts(source, ref);
            if (ok) {
                refDrops.show(cell, e.getScreenX(), e.getScreenY(), source, ref, currentBranch());
            }
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    /**
     * A local branch row's right-click menu, built once per cell.
     *
     * <p>{@link #update} settles what it says for the row being drawn - a reader of the
     * menu sees the right labels without opening it - and the actions ask the cell which
     * branch it shows when they fire, so a recycled cell cannot act on an earlier row.
     */
    private final class LocalBranchMenu {
        final ContextMenu menu = new ContextMenu();
        private final Supplier<String> branch;
        private final MenuItem checkout = new MenuItem("Checkout");
        private final MenuItem merge = new MenuItem();
        private final MenuItem mergeWith = new MenuItem();
        private final MenuItem rebase = new MenuItem("Rebase current onto this");
        private final MenuItem setUpstream = new MenuItem();
        private final MenuItem unsetUpstream = new MenuItem();
        private final MenuItem delete = new MenuItem("Delete…");
        private final SeparatorMenuItem beforeFinish = new SeparatorMenuItem();
        private final MenuItem finish = new MenuItem();
        private final SeparatorMenuItem beforeHide = new SeparatorMenuItem();
        private final MenuItem hide = new MenuItem();
        private final MenuItem solo = new MenuItem();
        /** The items every branch has; the flow and Hide & Solo items follow as they apply. */
        private final List<MenuItem> always;

        LocalBranchMenu(Supplier<String> branch) {
            this.branch = branch;
            checkout.setOnAction(e -> on(b -> checkoutWithOrphanGuard(b, "Checking out " + b)));

            MenuItem newFrom = new MenuItem("New branch from here…");
            newFrom.setOnAction(e -> on(SidebarPane.this::promptNewBranch));

            merge.setOnAction(e -> on(b -> merge(() -> commands().merge(b, false), "Merging " + b)));
            mergeWith.setOnAction(e -> on(SidebarPane.this::promptMerge));
            rebase.setOnAction(e -> on(SidebarPane.this::startInteractiveRebaseOnto));

            // The WORKTREES section hides itself until a second tree exists, so this is
            // the entry point that gets a user their first one.
            MenuItem worktree = new MenuItem("New worktree from this branch…");
            worktree.setOnAction(e -> on(SidebarPane.this::promptAddWorktree));

            MenuItem rename = new MenuItem("Rename…");
            rename.setOnAction(e -> on(SidebarPane.this::promptRenameBranch));

            delete.setOnAction(e -> on(SidebarPane.this::confirmDeleteBranch));

            setUpstream.setOnAction(e -> on(SidebarPane.this::promptSetUpstream));
            unsetUpstream.setOnAction(e -> on(b -> run(() -> gitService.unsetUpstream(b),
                    "Clearing the upstream of " + b)));

            finish.setOnAction(e -> on(SidebarPane.this::confirmFinishFlow));
            hide.setOnAction(e -> on(b -> toggleHideFromGraph(model.local(b).fullName())));
            solo.setOnAction(e -> on(b -> toggleSolo(model.local(b).fullName())));

            always = List.of(checkout, newFrom, new SeparatorMenuItem(),
                    merge, mergeWith, rebase, new SeparatorMenuItem(), worktree,
                    new SeparatorMenuItem(), setUpstream, unsetUpstream,
                    new SeparatorMenuItem(), rename, delete);
            menu.getItems().setAll(always);
        }

        private void on(java.util.function.Consumer<String> action) {
            String b = branch.get();
            if (b != null) action.accept(b);
        }

        void update(SidebarModel.LocalRow branch) {
            boolean isCurrent = branch.current();
            String current = currentBranch();
            String into = "Merge into " + (current.isEmpty() ? "current" : current);
            checkout.setDisable(isCurrent);
            merge.setText(into);
            merge.setDisable(isCurrent);
            // The merge item with the ellipsis: mode and message chosen in a MergeDialog.
            // The plain "Merge into …" beside it stays one click, for the merge merge.ff
            // would make anyway.
            mergeWith.setText(into + " with options…");
            mergeWith.setDisable(isCurrent);
            rebase.setDisable(isCurrent);
            delete.setDisable(isCurrent);

            // What Pull merges from and what the arrows beside the name are counted
            // against. Naming it in the item is the only place the sidebar says which
            // branch that is.
            String upstream = branch.upstream();
            setUpstream.setText(upstream == null ? "Set upstream…" : "Change upstream…");
            setUpstream.setDisable(model.remoteBranchNames().isEmpty());
            unsetUpstream.setText(upstream == null ? "Stop tracking" : "Stop tracking " + upstream);
            unsetUpstream.setDisable(upstream == null);

            com.jfx.git.core.GitFlow.Kind flowKind = branch.flowKind();
            if (flowKind != null) {
                finish.setText("Finish " + flowKind.word() + "…");
            }
            // Hide & Solo do not apply to the branch you are standing on (see fillRow).
            if (branch.hideable()) {
                hide.setText(branch.hidden() ? "Show in graph" : "Hide from graph");
                solo.setText(branch.solo() ? "Clear solo" : "Solo (show only this branch)");
            }
            // Present or absent rather than hidden: what a row offers is what its menu
            // holds, and only the items that apply are in it.
            int wanted = always.size() + (flowKind != null ? 2 : 0) + (isCurrent ? 0 : 3);
            var items = menu.getItems();
            boolean shaped = items.size() == wanted
                    && items.contains(finish) == (flowKind != null)
                    && items.contains(hide) == !isCurrent;
            if (!shaped) {
                List<MenuItem> next = new java.util.ArrayList<>(always);
                if (flowKind != null) next.addAll(List.of(beforeFinish, finish));
                if (!isCurrent) next.addAll(List.of(beforeHide, hide, solo));
                items.setAll(next);
            }
        }
    }

    /**
     * Asks how to merge {@code source} into the current branch, then does it - offering
     * stash and retry if local changes are in the way, as every merge item does.
     *
     * <p>Public for the command palette's "Merge … into current…" entries and the status
     * bar's conflict warning, which reach the same dialog and the same reporting as the
     * sidebar's menu.
     */
    public void promptMerge(String source) {
        new com.jfx.git.ui.dialogs.MergeDialog(source, currentBranch()).showAndWait()
                .ifPresent(choice -> merge(
                        () -> gitService.merge(source, choice.mode(), choice.messageOrNull()),
                        (choice.mode() == com.jfx.git.model.MergeMode.SQUASH ? "Squashing " : "Merging ")
                                + source));
    }

    /**
     * "Rebase current onto this" for {@code upstream}: the interactive-rebase editor first,
     * as from the branch's own menu. Public for the status bar's conflict warning.
     */
    public void promptRebaseOnto(String upstream) {
        startInteractiveRebaseOnto(upstream);
    }

    /** Opens the working-tree snapshots in this repository's tab. */
    public void openSnapshots() {
        com.jfx.git.ui.views.SnapshotsView.open(viewHost, operations);
    }

    // Hide & Solo: every row carries its ref's full name from the snapshot, which is what
    // the store and GraphVisibility key by; HideSolo makes the edit, and the refresh that
    // follows draws it.

    /** Flips one ref's hidden state. Soloing another ref already hides everything else. */
    private void toggleHideFromGraph(String fullRefName) {
        hideSolo.toggleHidden(fullRefName);
        operations.refresh(OperationRunner.Refresh.EVERYTHING);
    }

    /** Solos {@code fullRefName}, or clears solo if it already is the one soloed. */
    private void toggleSolo(String fullRefName) {
        hideSolo.toggleSolo(fullRefName);
        operations.refresh(OperationRunner.Refresh.EVERYTHING);
    }

    /** Bulk hide, for a section header's "Hide all" - one settings write rather than one per ref. */
    private void hideAllFromGraph(java.util.Collection<String> fullRefNames) {
        hideSolo.hideAll(fullRefNames);
        operations.refresh(OperationRunner.Refresh.EVERYTHING);
    }

    /** Bulk show, for a section header's "Show all" - clears only this section's refs. */
    private void showAllInGraph(java.util.Collection<String> fullRefNames) {
        hideSolo.showAll(fullRefNames);
        operations.refresh(OperationRunner.Refresh.EVERYTHING);
    }

    /** Opens Git LFS for this repository, in its tab. Public for the command palette. */
    public void promptLfs() {
        viewHost.open(com.jfx.git.ui.views.LfsView.class,
                () -> new com.jfx.git.ui.views.LfsView(gitService,
                        () -> operations.refresh(OperationRunner.Refresh.EVERYTHING)));
    }

    /**
     * Opens the branch cleanup in this repository's tab: merged branches, and ones whose
     * upstream has gone. See {@link com.jfx.git.ui.views.BranchCleanupView}.
     */
    public void openBranchCleanup() {
        viewHost.open(com.jfx.git.ui.views.BranchCleanupView.class,
                () -> new com.jfx.git.ui.views.BranchCleanupView(operations));
    }

    /**
     * Opens this repository's own settings - the Repository section of the Settings
     * tab, on this repository. Public for the command palette.
     *
     * <p>The work tree is read here, on the FX thread: it is the path the backend was
     * opened with, held in memory, not a git call.
     */
    public void openRepositorySettings() {
        if (!AppNavigator.openRepositorySettings(gitService.getWorkTree())) {
            statusBar.error("Repository settings open in the main window's Settings tab");
        }
    }

    // ------------------------------------------------------------------
    // Git Flow
    // ------------------------------------------------------------------

    private TitledPane buildFlowPane() {
        flowList.getStyleClass().add("sidebar-list");
        flowList.setFixedCellSize(UiScale.ROW_HEIGHT);
        flowList.setCellFactory(lv -> new FlowBranchCell());

        ContextMenu paneMenu = new ContextMenu();
        for (com.jfx.git.core.GitFlow.Kind kind : com.jfx.git.core.GitFlow.Kind.values()) {
            MenuItem start = new MenuItem("Start " + kind.word() + "…");
            start.setOnAction(e -> promptStartFlow(kind));
            paneMenu.getItems().add(start);
        }
        MenuItem settings = new MenuItem("Git Flow settings…");
        settings.setOnAction(e -> promptFlowSetup());
        paneMenu.getItems().addAll(new SeparatorMenuItem(), settings);
        flowList.setContextMenu(paneMenu);

        TitledPane pane = new TitledPane();
        pane.setContent(flowList);
        pane.setGraphic(sectionTitle("GIT FLOW", flowCount));
        pane.setAnimated(false);
        return pane;
    }

    /** A feature, release or hotfix under way; built once per cell, like the branch rows. */
    private final class FlowBranchCell extends ListCell<String> implements SidebarRow {
        @Override
        public javafx.scene.control.Control owner() {
            return getListView();
        }

        @Override
        public void redraw() {
            if (!isEmpty() && getItem() != null) {
                updateItem(getItem(), false);
            }
        }

        private final javafx.scene.Node currentMark = Icons.currentBranch();
        private final javafx.scene.Node localMark = Icons.localBranch();
        private final ContextMenu menu;
        private final MenuItem checkout = new MenuItem("Checkout");
        private final MenuItem finish = new MenuItem();

        FlowBranchCell() {
            checkout.setOnAction(e -> {
                String branch = getItem();
                if (branch != null) checkoutWithOrphanGuard(branch, "Checking out " + branch);
            });
            finish.setOnAction(e -> {
                String branch = getItem();
                if (branch != null) confirmFinishFlow(branch);
            });
            menu = new ContextMenu(checkout, finish);
            sidebarRows.add(this);
            setOnMouseClicked(e -> {
                String branch = isEmpty() ? null : getItem();
                if (branch != null && e.getClickCount() == 2 && !branch.equals(currentBranch())) {
                    checkoutWithOrphanGuard(branch, "Checking out " + branch);
                }
            });
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            setText(item);
            setGraphic(item.equals(currentBranch()) ? currentMark : localMark);
            com.jfx.git.core.GitFlow.Kind kind = com.jfx.git.core.GitFlow.kindOf(flowConfig, item);
            checkout.setDisable(item.equals(currentBranch()));
            finish.setText("Finish " + (kind == null ? "branch" : kind.word()) + "…");
            setContextMenu(menu);
        }
    }

    /** This repository's Git Flow settings from the last load, or null. For the command palette. */
    public com.jfx.git.core.GitFlow.Config cachedFlowConfig() {
        return flowConfig;
    }

    /**
     * Sets the repository up for Git Flow, or changes its settings.
     *
     * <p>Public for the command palette.
     */
    public void promptFlowSetup() {
        if (model.localNames().isEmpty()) {
            statusBar.error("Git Flow needs a branch to release from, and this repository has no commits yet");
            return;
        }
        new com.jfx.git.ui.dialogs.GitFlowSetupDialog(model.localNames(), flowConfig).showAndWait().ifPresent(config -> {
            operations.operation("Setting up Git Flow", OperationRunner.Refresh.EVERYTHING)
                    // Read again by the refresh this starts: the settings have just changed.
                    .onResult(result -> flowConfigKnown = false)
                    .run(() -> new com.jfx.git.core.GitFlow(gitService).init(config));
        });
    }

    /**
     * Asks for a name and starts a feature, release or hotfix.
     *
     * <p>Public for the command palette.
     */
    public void promptStartFlow(com.jfx.git.core.GitFlow.Kind kind) {
        if (flowConfig == null) {
            statusBar.error("This repository is not set up for Git Flow yet. Set it up from the LOCAL"
                    + " section's menu.");
            return;
        }
        boolean feature = kind == com.jfx.git.core.GitFlow.Kind.FEATURE;
        String base = kind == com.jfx.git.core.GitFlow.Kind.HOTFIX ? flowConfig.production() : flowConfig.develop();
        String prefix = flowConfig.prefix(kind);
        Dialogs.prompt("Start " + kind.word())
                .icon(Icons.branch())
                .title("Start a " + kind.word())
                .subtitle("From `" + base + "`")
                .label(feature ? "Name" : "Version")
                .placeholder(feature ? "search-filters" : kind == com.jfx.git.core.GitFlow.Kind.HOTFIX ? "1.4.1" : "1.5.0")
                .hint("Creates " + prefix + "<" + (feature ? "name" : "version") + "> from " + base
                        + " and checks it out.")
                // The name is the end of a branch name, so the branch rules are its rules.
                .validator(text -> branchNameProblem(prefix + text.trim(), null))
                .action("Start " + kind.word())
                .ask()
                .map(String::trim)
                // The new branch is checked out: a checkout, so it offers auto-stash too.
                .ifPresent(name -> checkout(() -> new com.jfx.git.core.GitFlow(gitService).start(kind, name),
                        "Starting " + kind.word() + " " + name));
    }

    /**
     * Says what finishing {@code branch} will do, step by step, and does it on a yes.
     *
     * <p>Several branches move and one is deleted, so the steps are shown first rather
     * than discovered afterwards. Public for the command palette.
     */
    public void confirmFinishFlow(String branch) {
        GitTaskRunner.query(gitService, () -> new com.jfx.git.core.GitFlow(gitService).finishPlan(branch), plan -> {
            List<String> steps = plan.steps();
            if (steps.isEmpty()) {
                statusBar.error(branch + " is not a feature, release or hotfix branch");
                return;
            }
            com.jfx.git.core.GitFlow.Kind kind = com.jfx.git.core.GitFlow.kindOf(flowConfig, branch);
            String word = kind == null ? "branch" : kind.word();
            List<String> numbered = new java.util.ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                numbered.add((i + 1) + ". " + steps.get(i));
            }
            // Finishing auto-stashes local changes before its first step and puts them back
            // after its last (GitFlow.finish) - said here, where the steps are.
            String message = plan.stashesFirst()
                    ? "Your uncommitted changes go into a stash first and are re-applied when finishing"
                            + " is done. If a merge conflicts it stops there: resolve it, commit, and finish"
                            + " again to carry on. Your changes wait in the stash until you pop them."
                    : "If a merge conflicts it stops there: resolve it, commit, and finish again to carry on.";
            if (Dialogs.confirm("Finish " + word)
                    .icon(Icons.merge())
                    .title("Finish `" + branch + "`?")
                    .subtitle("Git Flow " + word)
                    .message(message)
                    .affected("STEPS", numbered)
                    .action((plan.stashesFirst() ? "Stash and finish " : "Finish ") + word)
                    .size(JfxDialog.Size.MD)
                    .ask()) {
                run(() -> new com.jfx.git.core.GitFlow(gitService).finish(branch, null), "Finishing " + branch);
            }
        }, statusBar::error);
    }

    // ------------------------------------------------------------------
    // Remotes and upstreams
    // ------------------------------------------------------------------

    /**
     * Asks which remote branch {@code branch} should track, then sets it.
     *
     * <p>Public for the command palette's "Set upstream of current branch…".
     */
    public void promptSetUpstream(String branch) {
        if (branch == null || branch.isBlank()) {
            statusBar.error("No branch is checked out, so there is nothing to set an upstream for");
            return;
        }
        new com.jfx.git.ui.dialogs.SetUpstreamDialog(branch, model.remoteBranchNames(), model.remoteNames(),
                        model.upstreams().get(branch))
                .showAndWait()
                .ifPresent(chosen -> run(() -> gitService.setUpstream(branch, chosen),
                        "Setting the upstream of " + branch));
    }

    /**
     * The remote a remote-tracking branch belongs to: the longest configured name its
     * short name starts with, since a remote may have a slash in its own name; see
     * {@link RefNames#remoteOf}.
     */
    private String remoteOf(String remoteBranch) {
        return RefNames.remoteOf(remoteBranch, model.remoteNames());
    }

    /**
     * Deleting a branch on a shared remote takes it away from everyone who fetches from
     * there, and nobody but someone who still has the commits can put it back - so this
     * asks, and the button that goes ahead says what it does.
     */
    private void confirmDeleteRemoteBranch(String remoteBranch) {
        String remote = remoteOf(remoteBranch);
        String branch = RefNames.branchOf(remoteBranch, model.remoteNames());
        // Enter cancels: nothing here can put the branch back on the remote.
        if (Dialogs.destructive("Delete remote branch")
                .icon(Icons.remoteBranch())
                .title("Delete `" + branch + "` from " + remote + "?")
                .subtitle("Remote branch `" + remoteBranch + "`")
                .message("This removes the branch on " + remote + " for everyone who fetches from there."
                        + " Your local branches are not touched.")
                .action("Delete from " + remote)
                .safeDefault()
                .ask()) {
            run(() -> gitService.deleteRemoteBranch(remote, branch), "Deleting " + branch + " from " + remote);
        }
    }

    /** Fetches one remote. Public for the command palette's "Fetch …" entries. */
    public void fetchRemote(String remote) {
        run(() -> gitService.fetchRemote(remote), "Fetching " + remote);
    }

    /** Opens the add-remote dialog. Public for the command palette. */
    public void promptAddRemote() {
        new com.jfx.git.ui.dialogs.AddRemoteDialog(gitService,
                () -> operations.refresh(OperationRunner.Refresh.EVERYTHING)).showAndWait();
    }

    /** The menu on a remote's own row: the remote itself, not one of its branches. */
    /**
     * A remote, a folder under one, or a remote branch.
     *
     * <p>Built once per cell like {@link LocalBranchCell}: this one made a nine-item menu
     * per row and registered its hover preview again on every update.
     */
    private final class RemoteBranchCell extends TreeCell<BranchTree.Node> implements SidebarRow {
        @Override
        public javafx.scene.control.Control owner() {
            return getTreeView();
        }

        @Override
        public void redraw() {
            if (!isEmpty() && getItem() != null) {
                updateItem(getItem(), false);
            }
        }

        private final javafx.scene.Node remoteMark = Icons.remote();
        private final javafx.scene.Node folderMark = Icons.folder();
        private final NamedRow branchRow = new NamedRow(Icons.remoteBranch());
        /** The remote a remote's row stands for; read by its menu's actions. */
        private String remote;
        private ContextMenu remoteMenu;
        private RemoteBranchMenu branchMenu;

        RemoteBranchCell() {
            // As for local rows: the sidebar's width, with the name shortened to fit.
            setPrefWidth(0);
            sidebarRows.add(this);
            previewOnHover(this);
            installRefDrag(this, () -> {
                String full = currentBranchName();
                return full == null ? null : RefDropMenu.Ref.remote(full);
            });
            setOnMouseClicked(e -> {
                String full = currentBranchName();
                if (full == null || e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
                if (e.getClickCount() == 1) {
                    SidebarModel.RemoteRow row = model.remote(full);
                    reveal(row.tip(), row.fullName());
                } else if (e.getClickCount() == 2) {
                    checkoutRemote(full);
                }
            });
        }

        /** The remote branch on this row, or null for a folder or an empty row. */
        private String currentBranchName() {
            BranchTree.Node node = isEmpty() ? null : getItem();
            return node == null || (node.isFolder() && node.fullName() == null) ? null : node.fullName();
        }

        @Override
        protected void updateItem(BranchTree.Node node, boolean empty) {
            super.updateItem(node, empty);
            setClass(this, "branch-hidden", false);
            setClass(this, "branch-solo", false);
            if (empty || node == null) {
                setClass(this, "branch-folder", false);
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }

            if (node.isFolder() && node.fullName() == null) {
                setText(node.label());
                setClass(this, "branch-folder", true);
                // A folder whose path is a remote's name is the remote: the one row
                // that stands for it, and so the one its own actions hang from.
                String path = BranchTree.pathOf(getTreeItem());
                if (model.remoteNames().contains(path)) {
                    remote = path;
                    setGraphic(remoteMark);
                    if (remoteMenu == null) {
                        remoteMenu = buildRemoteMenu(() -> remote);
                    }
                    ((MenuItem) remoteMenu.getItems().get(0)).setText("Fetch " + path);
                    setContextMenu(remoteMenu);
                } else {
                    remote = null;
                    setGraphic(folderMark);
                    setContextMenu(null);
                }
                return;
            }
            setClass(this, "branch-folder", false);
            remote = null;

            SidebarModel.RemoteRow branch = model.remote(node.fullName());
            branchRow.show(this, node.label());
            if (branch.solo()) {
                setClass(this, "branch-solo", true);
            } else if (branch.hidden()) {
                setClass(this, "branch-hidden", true);
            }
            if (branchMenu == null) {
                branchMenu = new RemoteBranchMenu(this::currentBranchName);
            }
            branchMenu.update(branch);
            setContextMenu(branchMenu.menu);
        }
    }

    /** A remote branch row's menu, built once per cell; see {@link LocalBranchMenu}. */
    private final class RemoteBranchMenu {
        final ContextMenu menu = new ContextMenu();
        private final Supplier<String> branch;
        private final MenuItem mergeWith = new MenuItem();
        private final MenuItem track = new MenuItem();
        private final MenuItem hide = new MenuItem();
        private final MenuItem solo = new MenuItem();

        RemoteBranchMenu(Supplier<String> branch) {
            this.branch = branch;
            MenuItem checkout = new MenuItem("Checkout as local branch");
            checkout.setOnAction(e -> on(SidebarPane.this::checkoutRemote));
            MenuItem merge = new MenuItem("Merge into current");
            merge.setOnAction(e -> on(full -> merge(() -> commands().merge(full, false), "Merging " + full)));
            mergeWith.setOnAction(e -> on(SidebarPane.this::promptMerge));
            track.setOnAction(e -> on(full -> {
                String into = currentBranch();
                run(() -> gitService.setUpstream(into, full), "Setting the upstream of " + into);
            }));
            MenuItem deleteRemote = new MenuItem("Delete from remote…");
            deleteRemote.setOnAction(e -> on(SidebarPane.this::confirmDeleteRemoteBranch));
            hide.setOnAction(e -> on(full -> toggleHideFromGraph(model.remote(full).fullName())));
            solo.setOnAction(e -> on(full -> toggleSolo(model.remote(full).fullName())));
            menu.getItems().addAll(checkout, merge, mergeWith, new SeparatorMenuItem(),
                    track, deleteRemote, new SeparatorMenuItem(), hide, solo);
        }

        private void on(java.util.function.Consumer<String> action) {
            String full = branch.get();
            if (full != null) action.accept(full);
        }

        void update(SidebarModel.RemoteRow branch) {
            String current = currentBranch();
            mergeWith.setText("Merge into "
                    + (current.isEmpty() ? "current" : current) + " with options…");
            track.setText(current.isEmpty()
                    ? "Set as upstream of current branch"
                    : "Set as upstream of " + current);
            track.setDisable(current.isEmpty() || branch.upstreamOfCurrent());
            hide.setText(branch.hidden() ? "Show in graph" : "Hide from graph");
            solo.setText(branch.solo() ? "Clear solo" : "Solo (show only this branch)");
        }
    }

    /** A remote's own menu - fetch it, edit, rename, remove - acting on whichever remote the row shows. */
    private ContextMenu buildRemoteMenu(Supplier<String> remote) {
        MenuItem fetch = new MenuItem();
        fetch.setOnAction(e -> {
            String r = remote.get();
            if (r != null) fetchRemote(r);
        });

        MenuItem edit = new MenuItem("Edit URL…");
        edit.setOnAction(e -> {
            String r = remote.get();
            if (r != null) promptEditRemote(r);
        });

        MenuItem rename = new MenuItem("Rename…");
        rename.setOnAction(e -> {
            String r = remote.get();
            if (r != null) promptRenameRemote(r);
        });

        MenuItem remove = new MenuItem("Remove…");
        remove.setOnAction(e -> {
            String r = remote.get();
            if (r != null) confirmRemoveRemote(r);
        });

        return new ContextMenu(fetch, new SeparatorMenuItem(), edit, rename, remove);
    }

    /**
     * Shows a remote's URLs for editing and applies whichever of them changed.
     *
     * <p>The URLs are read on the git lane before the dialog opens: on the CLI engine
     * each is a subprocess, and this is called from a menu on the FX thread.
     *
     * <p>Public for the command palette's "Edit remote …" entries.
     */
    public void promptEditRemote(String remote) {
        GitTaskRunner.query(gitService,
                () -> new String[]{gitService.getRemoteUrl(remote), gitService.getRemotePushUrl(remote)},
                urls -> new com.jfx.git.ui.dialogs.EditRemoteDialog(remote, urls[0], urls[1])
                        .showAndWait()
                        .ifPresent(edited -> applyRemoteUrls(remote, urls[0], urls[1], edited)),
                statusBar::error);
    }

    private void applyRemoteUrls(String remote, String oldFetch, String oldPush,
                                 com.jfx.git.ui.dialogs.EditRemoteDialog.Urls edited) {
        boolean fetchChanged = !edited.fetchUrl().equals(oldFetch == null ? "" : oldFetch);
        boolean pushChanged = !edited.pushUrl().equals(oldPush == null ? "" : oldPush);
        if (!fetchChanged && !pushChanged) {
            return;
        }
        run(() -> {
            if (fetchChanged) {
                GitResult result = gitService.setRemoteUrl(remote, edited.fetchUrl(), false);
                if (!result.isOk() || !pushChanged) return result;
            }
            return gitService.setRemoteUrl(remote, edited.pushUrl(), true);
        }, "Updating " + remote);
    }

    private void promptRenameRemote(String remote) {
        Dialogs.prompt("Rename remote")
                .icon(Icons.remote())
                .title("Rename `" + remote + "`")
                .subtitle("Remote")
                .label("New name")
                .initial(remote)
                .placeholder("upstream")
                .hint("Its remote branches are renamed with it, and local branches that track them follow.")
                .validator(text -> remoteNameProblem(text, remote))
                // A remote's name is one word, not a path: all of it is what gets replaced.
                .select(Dialogs.Selection.ALL)
                .action("Rename remote")
                .ask()
                .map(String::trim)
                .filter(n -> !n.isEmpty() && !n.equals(remote))
                .ifPresent(newName -> operations.operation("Renaming " + remote, OperationRunner.Refresh.EVERYTHING)
                        .onSuccess(result -> hideSolo.remoteRenamed(remote, newName))
                        .run(() -> gitService.renameRemote(remote, newName)));
    }

    /**
     * What is wrong with {@code typed} as a new name for {@code current}, or null; "" for
     * the name it has. The engines' own rule: it has to work as {@code refs/remotes/<name>/x}.
     */
    private String remoteNameProblem(String typed, String current) {
        String name = typed.trim();
        if (name.equals(current)) {
            return "";
        }
        if (name.startsWith("-") || !org.eclipse.jgit.lib.Repository.isValidRefName(
                org.eclipse.jgit.lib.Constants.R_REMOTES + name + "/x")) {
            return "Not a valid remote name: no spaces, \"..\", \"~\", \"^\", \":\", \"?\", \"*\" or \"[\".";
        }
        if (model.remoteNames().contains(name)) {
            return "There is already a remote called " + name + ".";
        }
        return null;
    }

    /**
     * Reads the remote's URL first, so the question can show it: adding the remote again
     * with that URL is the way back.
     */
    private void confirmRemoveRemote(String remote) {
        GitTaskRunner.query(gitService, () -> gitService.getRemoteUrl(remote),
                url -> askRemoveRemote(remote, url),
                error -> askRemoveRemote(remote, null));
    }

    private void askRemoveRemote(String remote, String url) {
        List<String> tracking = model.upstreams().entrySet().stream()
                .filter(e -> remote.equals(remoteOf(e.getValue())))
                .map(java.util.Map.Entry::getKey)
                .sorted()
                .toList();
        String untracked = tracking.isEmpty() ? ""
                : tracking.size() == 1
                        ? " " + tracking.get(0) + " keeps its commits but stops tracking it."
                        : " " + tracking.size() + " local branches keep their commits but stop tracking it.";
        Dialogs.Message confirm = Dialogs.destructive("Remove remote")
                .icon(Icons.remote())
                .title("Remove the remote `" + remote + "`?")
                .subtitle("From this clone only")
                .message("This clone forgets " + remote + " and its remote branches. Nothing is deleted on "
                        + remote + " itself." + untracked)
                .action("Remove " + remote);
        if (url != null && !url.isBlank()) {
            confirm.code("TO ADD IT BACK LATER", url).size(JfxDialog.Size.MD);
        }
        if (confirm.ask()) {
            run(() -> gitService.removeRemote(remote), "Removing " + remote);
        }
    }

    /**
     * The items that send a tag to, or delete it from, a remote.
     *
     * <p>Both used to go to {@code origin} whatever the repository's remotes were called:
     * a clone whose only remote is {@code github} could not push a tag at all, and a fork
     * workflow could only ever tag the fork. With one remote there is nothing to choose
     * and the items act on it; with several each becomes a submenu naming them.
     *
     * @param tag the tag the row shows when the item fires; a tag row's menu outlives
     *            any one tag, since the cell it belongs to is recycled
     */
    private List<MenuItem> tagRemoteItems(Supplier<String> tag) {
        List<String> remoteNames = model.remoteNames();
        if (remoteNames.isEmpty()) {
            MenuItem push = new MenuItem("Push tag");
            push.setDisable(true);
            MenuItem delete = new MenuItem("Delete tag from remote…");
            delete.setDisable(true);
            return List.of(push, delete);
        }
        if (remoteNames.size() == 1) {
            String remote = remoteNames.get(0);
            MenuItem push = new MenuItem("Push tag");
            push.setOnAction(e -> onTag(tag, t -> pushTag(remote, t)));
            MenuItem delete = new MenuItem("Delete tag from remote…");
            delete.setOnAction(e -> onTag(tag, t -> confirmDeleteRemoteTag(remote, t)));
            return List.of(push, delete);
        }
        javafx.scene.control.Menu push = new javafx.scene.control.Menu("Push tag to");
        javafx.scene.control.Menu delete = new javafx.scene.control.Menu("Delete tag from");
        for (String remote : remoteNames) {
            MenuItem to = new MenuItem(remote);
            to.setOnAction(e -> onTag(tag, t -> pushTag(remote, t)));
            push.getItems().add(to);
            MenuItem from = new MenuItem(remote + "…");
            from.setOnAction(e -> onTag(tag, t -> confirmDeleteRemoteTag(remote, t)));
            delete.getItems().add(from);
        }
        return List.of(push, delete);
    }

    private static void onTag(Supplier<String> tag, java.util.function.Consumer<String> action) {
        String t = tag.get();
        if (t != null) action.accept(t);
    }

    private void pushTag(String remote, String tag) {
        run(() -> gitService.pushTag(remote, tag), "Pushing tag " + tag + " to " + remote);
    }

    /**
     * Names a branch that starts at {@code startPoint} - a branch or a tag, or HEAD when
     * blank - and checks it out, which the verb says.
     */
    private void promptNewBranch(String startPoint) {
        boolean fromHead = startPoint == null || startPoint.isBlank();
        String current = currentBranch();
        String from = fromHead ? (current.isBlank() ? "HEAD" : current) : startPoint;
        String tip = fromHead ? null : startTip(startPoint);
        Dialogs.prompt("New branch")
                .icon(Icons.branch())
                .title("Create a branch")
                .subtitle("From `" + from + "`" + (tip == null ? "" : " at `" + shortId(tip) + "`"))
                .label("Branch name")
                .placeholder("feature/search")
                .validator(text -> branchNameProblem(text, null))
                .action("Create and check out")
                .ask()
                .map(String::trim)
                // Checked out straight away: a checkout, so it offers auto-stash too.
                .ifPresent(name -> checkout(() -> gitService.createBranch(name, startPoint, true),
                        "Creating branch " + name));
    }

    /** A local branch's new name; the part after its last slash is selected, the part a rename changes. */
    private void promptRenameBranch(String branch) {
        String upstream = model.upstreams().get(branch);
        Dialogs.prompt("Rename branch")
                .icon(Icons.branch())
                .title("Rename `" + branch + "`")
                .subtitle(upstream == null ? "Local branch" : "Local branch · tracks `" + upstream + "`")
                .label("New name")
                .initial(branch)
                .hint(upstream == null ? null
                        : upstream + " keeps its name, and the renamed branch goes on tracking it.")
                .validator(text -> branchNameProblem(text, branch))
                .select(Dialogs.Selection.LAST_SEGMENT)
                .action("Rename branch")
                .ask()
                .map(String::trim)
                .filter(name -> !name.equals(branch))
                .ifPresent(name -> operations.operation("Renaming branch", OperationRunner.Refresh.EVERYTHING)
                        // Hidden or soloed, it stays so under its new name.
                        .onSuccess(result -> hideSolo.refRenamed(RefNames.localFull(branch), RefNames.localFull(name)))
                        .run(() -> gitService.renameBranch(branch, name)));
    }

    /**
     * What is wrong with {@code typed} as the name of a local branch, for a prompt to show
     * under its field; null when nothing is. {@code current} is the name being changed, if
     * any: typing it back is not wrong, only nothing to do, so it disables the action
     * without a message.
     *
     * <p>The branch list is the last sidebar load's, so git still has the final say.
     */
    private String branchNameProblem(String typed, String current) {
        String name = typed.trim();
        if (name.equals(current)) {
            return "";
        }
        return RefNames.newBranchNameProblem(name, model.localNames());
    }

    /** The commit a new branch from {@code startPoint} starts at: a local branch's tip, else a tag's. */
    private String startTip(String startPoint) {
        String tip = model.local(startPoint).tip();
        return tip != null ? tip : model.tag(startPoint).tip();
    }

    /** A commit id as git abbreviates it in messages. */
    private static String shortId(String hash) {
        return hash == null || hash.length() <= 7 ? String.valueOf(hash) : hash.substring(0, 7);
    }

    private void confirmDeleteBranch(String name) {
        // Enter deletes: the toast that reports it carries Undo.
        if (com.jfx.git.ui.dialogs.Dialogs.destructive("Delete branch")
                .icon(Icons.branch())
                .title("Delete " + name + "?")
                .subtitle("Local branch")
                .message("The notification that follows has an Undo button for a few seconds.")
                .action("Delete branch")
                .ask()) {
            deleteBranchWithUndo(name, false);
        }
    }

    /**
     * Deletes a branch, keeping the commit it pointed at so the deletion can be undone
     * from the notification that reports it - a delete is the action most often regretted
     * a second after it is confirmed.
     */
    void deleteBranchWithUndo(String name, boolean force) {
        // The branch as it was, upstream included: git deletes the branch's configuration
        // with it, and an Undo that put back only the commit gave back a branch that
        // tracked nothing.
        java.util.concurrent.atomic.AtomicReference<com.jfx.git.core.DeletedBranch> before =
                new java.util.concurrent.atomic.AtomicReference<>();
        OperationRunner.Operation delete = operations.operation(
                        (force ? "Force-deleting " : "Deleting ") + name, OperationRunner.Refresh.EVERYTHING)
                .showSuccess(result -> {
                    com.jfx.git.core.DeletedBranch deleted = before.get();
                    if (deleted != null) {
                        statusBar.offerUndo("Deleted branch " + name, () ->
                                run(() -> deleted.restore(gitService), "Restoring branch " + name));
                    } else {
                        statusBar.report(result);
                    }
                })
                // A refusal deletes nothing, so nothing is read again for it: the forced
                // delete it offers is an operation of its own, which refreshes.
                .refreshWhen(GitResult::isOk);
        // Unmerged branches need an explicit force, mirroring git itself; a forced delete
        // that fails is reported like any other failure.
        if (!force) delete.showFailure(result -> offerForceDelete(name, result));
        delete.run(() -> {
            before.set(com.jfx.git.core.DeletedBranch.capture(gitService, name));
            return gitService.deleteBranch(name, force);
        });
    }

    /** Shows git's refusal to delete {@code name} as the question whether to force it, and forces it on a yes. */
    private boolean offerForceDelete(String name, GitResult result) {
        // Enter still forces: the toast that reports it carries Undo, as for the plain delete.
        String said = result.getMessage() == null ? "" : result.getMessage();
        boolean unmerged = result.getRefusal() instanceof Refusal.NotFullyMerged;
        if (Dialogs.destructive("Delete branch")
                .icon(Icons.branch())
                .title("Delete `" + name + "` anyway?")
                .subtitle(unmerged ? "Not fully merged" : "Git refused to delete it")
                .message((unmerged
                        ? "It has commits that are not merged, which deleting it can leave on no branch."
                        : "Git's reason is under Show Details. Forcing deletes it all the same.")
                        + " The notification that follows has an Undo button for a few seconds.")
                .details(said)
                .action("Force delete")
                .ask()) {
            deleteBranchWithUndo(name, true);
        }
        return true;
    }

    /**
     * Deletes a tag the same way: the object it pointed at is kept, and Undo points a tag
     * of the same name back at the exact object it held - including an annotated tag's
     * message, tagger and signature.
     */
    private void deleteTagWithUndo(String tag) {
        java.util.concurrent.atomic.AtomicReference<String> target = new java.util.concurrent.atomic.AtomicReference<>();
        operations.operation("Deleting tag " + tag, OperationRunner.Refresh.EVERYTHING)
                .showSuccess(result -> {
                    String object = target.get();
                    if (object != null) {
                        statusBar.offerUndo("Deleted tag " + tag, () ->
                                run(() -> gitService.restoreTagRef(tag, object), "Restoring tag " + tag));
                    } else {
                        statusBar.report(result);
                    }
                })
                .run(() -> {
                    target.set(gitService.getRawTagObjectId(tag));
                    return gitService.deleteTag(tag);
                });
    }

    /**
     * Removing a tag from a shared remote is visible to everyone who fetches it and
     * cannot be undone by anyone but re-pushing it themselves, so this confirms first
     * the same way a force-push does - unlike the local-only "Delete tag", which is
     * trivially reversible by recreating the tag.
     *
     * @param remote the remote to delete it from; see {@link #tagRemoteItems}
     */
    private void confirmDeleteRemoteTag(String remote, String tag) {
        // Enter deletes: the local tag stays, and pushing it again puts it back.
        if (Dialogs.destructive("Delete tag from remote")
                .icon(Icons.tag())
                .title("Delete tag `" + tag + "` from " + remote + "?")
                .subtitle("Your local tag is not touched")
                .message("This removes it from " + remote + " for everyone who fetches from there."
                        + " Pushing your " + tag + " again puts it back.")
                .action("Delete from " + remote)
                .ask()) {
            run(() -> gitService.deleteRemoteTag(remote, tag), "Deleting " + tag + " from " + remote);
        }
    }

    /**
     * Gathers the repository's top-level directories (cone mode's own restriction -
     * only whole top-level folders are selectable, top-level files are always kept
     * regardless) and the current sparse-checkout state, then hands both to {@link
     * com.jfx.git.ui.dialogs.SparseCheckoutDialog} and performs whichever of Apply/
     * Disable the user picks - through {@link #run}, the same async-then-refresh path
     * every other mutation in this pane already uses, since narrowing or restoring the
     * working tree is real, potentially slow git work and must not block the FX thread.
     * An engine with no sparse-checkout support (JGit) is not special-cased here: it
     * refuses with its own clear message ({@code GitBackend.setSparseCheckoutPaths}'s
     * default), which {@link #run} already reports the same way any other failure is.
     *
     * <p>The three <em>reads</em> that feed the dialog run on the worker too, which they
     * did not until the thread guard pointed at them. The paragraph above was written
     * about the mutation and was true of it, while immediately below it a whole-tree
     * {@code diff-tree} plus two {@code sparse-checkout} calls ran on the FX thread every
     * time the dialog was opened - the largest single freeze the guard found. Reading the
     * tree of a large repository is not cheaper than writing to it.
     */
    private void openSparseCheckoutDialog() {
        operations.read("Reading the working tree layout", this::loadSparseCheckoutState,
                this::showSparseCheckoutDialog);
    }

    /** What the sparse-checkout dialog needs, gathered in one worker pass. */
    private record SparseCheckoutState(List<String> topLevelDirectories,
                                       List<String> current,
                                       boolean enabled) {
    }

    private SparseCheckoutState loadSparseCheckoutState() {
        List<String> topLevelDirectories = gitService.getTopLevelDirectoriesAtCommit("HEAD");
        return new SparseCheckoutState(topLevelDirectories,
                gitService.getSparseCheckoutPaths(),
                gitService.isSparseCheckoutEnabled());
    }

    private void showSparseCheckoutDialog(SparseCheckoutState state) {
        new com.jfx.git.ui.dialogs.SparseCheckoutDialog(
                state.topLevelDirectories(), state.current(), state.enabled())
                .showAndWait()
                .ifPresent(result -> {
                    if (result instanceof com.jfx.git.ui.dialogs.SparseCheckoutDialog.Apply apply) {
                        run(() -> gitService.setSparseCheckoutPaths(apply.paths()), "Applying sparse checkout");
                    } else if (result instanceof com.jfx.git.ui.dialogs.SparseCheckoutDialog.Disable) {
                        run(gitService::disableSparseCheckout, "Disabling sparse checkout");
                    }
                });
    }

    /**
     * "Fast-forward when possible, else delete+recreate" (the tags article's own
     * documented move behaviour): always a real delete-and-recreate under the hood -
     * an annotated tag's object is immutable, so nothing can be repointed in place -
     * but the confirmation differs. A fast-forward (HEAD is a descendant of the tag's
     * current commit) is the everyday "roll this tag forward" case and gets one plain
     * confirmation; anything else is a real, more surprising rewrite - the tag moving
     * away from its own history - and says so before proceeding, the same way an
     * unmerged branch delete needs an extra, more explicit confirmation.
     */
    private void confirmMoveTagToHead(String tag) {
        // The three reads are git processes on the CLI engine; they run on the worker and
        // the confirmation follows when they are back.
        GitTaskRunner.query(gitService, () -> {
                    String head = gitService.getHeadHash();
                    String current = gitService.getTags().stream()
                            .filter(r -> r.shortName().equals(tag))
                            .map(com.jfx.git.model.RefInfo::targetHash)
                            .findFirst().orElse(null);
                    return new TagMove(head, current, gitService.isTagMoveFastForward(tag, "HEAD"));
                },
                move -> confirmMoveTagToHead(tag, move.head(), move.current(), move.fastForward()),
                statusBar::error);
    }

    private record TagMove(String head, String current, boolean fastForward) {
    }

    private void confirmMoveTagToHead(String tag, String headHash, String currentTarget, boolean fastForward) {
        if (headHash != null && headHash.equals(currentTarget)) {
            // A fact, not a question: said in the status bar rather than in a dialog
            // that has to be dismissed before anything else can be done.
            statusBar.info("Tag " + tag + " already points at HEAD, so there is nothing to move");
            return;
        }

        // The commit it marks now is named either way: putting it back means finding that.
        String now = currentTarget == null ? "" : " It marks " + shortId(currentTarget) + " now.";
        String head = headHash == null ? "HEAD" : "HEAD, `" + shortId(headHash) + "`,";
        Dialogs.Message confirm = fastForward
                ? Dialogs.confirm("Move tag")
                        .subtitle(head + " is further along its history")
                        .message("A fast-forward: the tag moves on along the history it already marks." + now)
                : Dialogs.warn("Move tag")
                        .subtitle(head + " is not on its history")
                        .message("This is not a fast-forward: HEAD is not a descendant of the commit " + tag
                                + " marks, so the tag would move away from its own history." + now);
        if (confirm.icon(Icons.tag()).title("Move `" + tag + "` to HEAD?").action("Move tag").ask()) {
            run(() -> gitService.moveTag(tag, "HEAD"), "Moving tag " + tag + " to HEAD");
        }
    }

    // ------------------------------------------------------------------
    // Remote branches
    // ------------------------------------------------------------------

    private TitledPane buildRemotePane() {
        remoteTree.getStyleClass().add("branch-tree");
        remoteTree.setShowRoot(false);
        remoteTree.setCellFactory(tv -> new RemoteBranchCell());
        remoteTree.setFixedCellSize(UiScale.ROW_HEIGHT);

        ContextMenu remotePaneMenu = new ContextMenu();
        MenuItem addRemote = new MenuItem("Add remote…");
        addRemote.setOnAction(e -> promptAddRemote());
        MenuItem hideAllRemotes = new MenuItem("Hide all remote branches from graph");
        hideAllRemotes.setOnAction(e -> hideAllFromGraph(model.hideable(Section.REMOTE)));
        MenuItem showAllRemotes = new MenuItem("Show all remote branches in graph");
        showAllRemotes.setOnAction(e -> showAllInGraph(model.fullNames(Section.REMOTE)));
        remotePaneMenu.getItems().addAll(addRemote, new SeparatorMenuItem(), hideAllRemotes, showAllRemotes);
        remoteTree.setContextMenu(remotePaneMenu);

        TitledPane pane = new TitledPane();
        pane.setGraphic(sectionTitle("REMOTE", remoteCount));
        VBox content = new VBox(UiScale.SNUG, remoteTree);
        VBox.setVgrow(remoteTree, Priority.ALWAYS);
        // A repository with no remote says how to get one, where the remotes would be.
        addRemoteButton.getStyleClass().addAll("btn-sm", "sidebar-empty-action");
        addRemoteButton.setOnAction(e -> promptAddRemote());
        addRemoteButton.setVisible(false);
        addRemoteButton.setManaged(false);
        content.getChildren().add(addRemoteButton);
        pane.setContent(content);
        pane.setAnimated(false);
        return pane;
    }

    // ------------------------------------------------------------------
    // Tags
    // ------------------------------------------------------------------

    private TitledPane buildTagsPane() {
        tagsList.getStyleClass().add("sidebar-list");
        tagsList.setFixedCellSize(UiScale.ROW_HEIGHT);
        tagsList.setCellFactory(lv -> new TagCell());

        ContextMenu paneMenu = new ContextMenu();
        MenuItem newTag = new MenuItem("New tag on HEAD…");
        newTag.setOnAction(e -> new com.jfx.git.ui.dialogs.NewTagDialog("On HEAD")
                .showAndWait()
                .filter(r -> !r.name().isBlank())
                .ifPresent(r -> run(() -> gitService.createTag(r.name(), r.messageOrNull(), "HEAD"),
                        "Creating tag " + r.name())));
        MenuItem hideAllTags = new MenuItem("Hide all tags from graph");
        hideAllTags.setOnAction(e -> hideAllFromGraph(model.hideable(Section.TAGS)));
        MenuItem showAllTags = new MenuItem("Show all tags in graph");
        showAllTags.setOnAction(e -> showAllInGraph(model.fullNames(Section.TAGS)));
        paneMenu.getItems().addAll(newTag, new SeparatorMenuItem(), hideAllTags, showAllTags);
        tagsList.setContextMenu(paneMenu);

        TitledPane pane = new TitledPane();
        pane.setGraphic(sectionTitle("TAGS", tagCount));
        pane.setContent(tagsList);
        pane.setAnimated(false);
        return pane;
    }

    // ------------------------------------------------------------------
    // Stashes
    // ------------------------------------------------------------------

    private TitledPane buildStashPane() {
        stashList.getStyleClass().add("sidebar-list");
        stashList.setCellFactory(lv -> new StashCell());

        stashList.setFixedCellSize(UiScale.ROW_HEIGHT);

        ContextMenu stashPaneMenu = new ContextMenu();
        MenuItem hideAllStashes = new MenuItem("Hide all stashes from graph");
        hideAllStashes.setOnAction(e -> hideAllStashesFromGraph(model.stashHashes()));
        MenuItem showAllStashes = new MenuItem("Show all stashes in graph");
        showAllStashes.setOnAction(e -> showAllStashesInGraph(model.stashHashes()));
        MenuItem snapshots = new MenuItem("Working-tree snapshots…");
        snapshots.setOnAction(e -> openSnapshots());
        stashPaneMenu.getItems().addAll(hideAllStashes, showAllStashes, new SeparatorMenuItem(), snapshots);
        stashList.setContextMenu(stashPaneMenu);

        TitledPane pane = new TitledPane();
        pane.setContent(stashList);
        pane.setGraphic(sectionTitle("STASHES", stashCount));
        pane.setAnimated(false);
        return pane;
    }

    /**
     * One tag. Built once per cell like the branch rows: a thousand-tag list redrew its
     * rows with a ten-item menu each, on every scroll tick and every refresh.
     */
    private final class TagCell extends ListCell<String> implements SidebarRow {
        @Override
        public javafx.scene.control.Control owner() {
            return getListView();
        }

        @Override
        public void redraw() {
            if (!isEmpty() && getItem() != null) {
                updateItem(getItem(), false);
            }
        }

        private final NamedRow named = new NamedRow(Icons.tag());
        private final ContextMenu menu = new ContextMenu();
        private final MenuItem hide = new MenuItem();
        private final MenuItem solo = new MenuItem();
        /** The items that reach the remotes, and the remotes they were made for. */
        private List<MenuItem> onRemote = List.of();
        private List<String> onRemoteFor;

        TagCell() {
            Supplier<String> tag = () -> isEmpty() ? null : getItem();
            // One click or two, the tag's commit: a tag has nothing to check out in
            // place the way a branch does (that is "Checkout tag").
            setOnMouseClicked(e -> {
                String item = tag.get();
                if (item != null && e.getButton() == javafx.scene.input.MouseButton.PRIMARY
                        && e.getClickCount() <= 2) {
                    SidebarModel.TagRow row = model.tag(item);
                    reveal(row.tip(), row.fullName());
                }
            });
            MenuItem checkout = new MenuItem("Checkout tag");
            checkout.setOnAction(e -> onTag(tag, t -> checkoutWithOrphanGuard(t, "Checking out " + t)));
            MenuItem branchHere = new MenuItem("New branch from here…");
            branchHere.setOnAction(e -> onTag(tag, SidebarPane.this::promptNewBranch));
            MenuItem moveToHead = new MenuItem("Move to HEAD…");
            moveToHead.setOnAction(e -> onTag(tag, SidebarPane.this::confirmMoveTagToHead));
            MenuItem delete = new MenuItem("Delete tag");
            delete.setOnAction(e -> onTag(tag, SidebarPane.this::deleteTagWithUndo));
            hide.setOnAction(e -> onTag(tag, t -> toggleHideFromGraph(model.tag(t).fullName())));
            solo.setOnAction(e -> onTag(tag, t -> toggleSolo(model.tag(t).fullName())));
            onRemote = List.of(new MenuItem(), new MenuItem());
            sidebarRows.add(this);
            menu.getItems().addAll(checkout, branchHere, moveToHead, new SeparatorMenuItem(),
                    onRemote.get(0), delete, onRemote.get(1), new SeparatorMenuItem(), hide, solo);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setClass(this, "branch-hidden", false);
            setClass(this, "branch-solo", false);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            SidebarModel.TagRow row = model.tag(item);
            named.show(this, item);
            if (row.solo()) {
                setClass(this, "branch-solo", true);
            } else if (row.hidden()) {
                setClass(this, "branch-hidden", true);
            }
            // The push and delete items name the remotes, so they are made again when
            // the remotes change - not per row.
            List<String> remoteNames = model.remoteNames();
            if (!remoteNames.equals(onRemoteFor)) {
                List<MenuItem> fresh = tagRemoteItems(() -> isEmpty() ? null : getItem());
                var items = menu.getItems();
                items.set(items.indexOf(onRemote.get(0)), fresh.get(0));
                items.set(items.indexOf(onRemote.get(1)), fresh.get(1));
                onRemote = fresh;
                onRemoteFor = remoteNames;
            }
            hide.setText(row.hidden() ? "Show in graph" : "Hide from graph");
            solo.setText(row.solo() ? "Clear solo" : "Solo (show only this tag)");
            setContextMenu(menu);
        }
    }

    /** One stash; built once per cell, like the branch rows. */
    private final class StashCell extends ListCell<com.jfx.git.model.StashEntry> implements SidebarRow {
        @Override
        public javafx.scene.control.Control owner() {
            return getListView();
        }

        @Override
        public void redraw() {
            if (!isEmpty() && getItem() != null) {
                updateItem(getItem(), false);
            }
        }

        private final NamedRow named = new NamedRow(Icons.stashMark());
        private final javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip();
        private final ContextMenu menu = new ContextMenu();
        private final MenuItem hide = new MenuItem();

        StashCell() {
            Supplier<com.jfx.git.model.StashEntry> current = () -> isEmpty() ? null : getItem();
            // As in commercial clients: the stash, selected in the graph where it was made.
            setOnMouseClicked(e -> {
                var stash = current.get();
                if (stash != null && e.getButton() == javafx.scene.input.MouseButton.PRIMARY
                        && e.getClickCount() == 1) {
                    onRevealStash.accept(stash);
                }
            });
            tip.setOnShowing(e -> {
                var stash = current.get();
                tip.setText(stash == null ? "" : stash.message());
            });
            // The hash, not the index: stash@{n} is a position git renumbers on every pop
            // or drop, and this row's number was read when it was drawn. See
            // RepositoryCommands.stashPop(String).
            MenuItem pop = new MenuItem("Pop (apply and drop)");
            pop.setOnAction(e -> onStash(current, s -> run(() -> commands().stashPop(s.hash()), "Popping stash")));
            MenuItem apply = new MenuItem("Apply (keep stash)");
            apply.setOnAction(e -> onStash(current, s -> run(() -> commands().stashApply(s.hash()), "Applying stash")));
            // Confirmed here as well as on the graph's identical menu. Dropping a stash
            // discards the only copy of that work, and which of the two lists the user
            // happened to right-click is not a reason to ask or not ask.
            MenuItem drop = new MenuItem("Drop…");
            // Enter cancels: nothing in the application brings a dropped stash back.
            drop.setOnAction(e -> onStash(current, s -> {
                if (Dialogs.destructive("Drop stash")
                        .icon(Icons.stash())
                        .title("Drop `" + s.selector() + "`?")
                        // Its message is free text: a backtick in it is not markup.
                        .subtitle(s.shortDescription().replace('`', '\''))
                        .message("The changes it holds are discarded, and this cannot be undone.")
                        .action("Drop stash")
                        .safeDefault()
                        .ask()) {
                    run(() -> commands().stashDrop(s.hash()), "Dropping stash");
                }
            }));
            // No Solo for a stash: solo isolates a ref's own reachable history, and a
            // stash is not reachable from anything - only Hide (a pure display
            // suppression) applies (see AppPreferences.hiddenStashes()).
            hide.setOnAction(e -> onStash(current, s -> toggleStashHidden(s.hash())));
            // By hash like the others, resolved to stash@{n} on the worker.
            MenuItem branch = new MenuItem("Create branch from stash…");
            branch.setOnAction(e -> onStash(current, s -> new com.jfx.git.ui.dialogs.StashBranchDialog(s)
                    .showAndWait()
                    .ifPresent(name -> run(() -> commands().stashBranch(name, s.hash()),
                            "Creating " + name + " from " + s.selector()))));
            // Beside the stash because it is the other place uncommitted work is kept.
            MenuItem snapshots = new MenuItem("Working-tree snapshots…");
            snapshots.setOnAction(e -> openSnapshots());
            menu.getItems().addAll(pop, apply, branch, new SeparatorMenuItem(), drop,
                    new SeparatorMenuItem(), hide, snapshots);
            sidebarRows.add(this);
        }

        @Override
        protected void updateItem(com.jfx.git.model.StashEntry stash, boolean empty) {
            super.updateItem(stash, empty);
            setClass(this, "branch-hidden", false);
            if (empty || stash == null) {
                // A recycled row must not keep the last stash's mark and hover.
                setText(null);
                setGraphic(null);
                setTooltip(null);
                setContextMenu(null);
                return;
            }
            // The selector alone said nothing: git's message is the only way to tell two
            // stashes apart, so it rides along after the selector, with the full text on
            // hover.
            named.show(this, stash.selector() + "  " + stash.shortDescription());
            setTooltip(tip);
            boolean hidden = model.stashHidden(stash.hash());
            setClass(this, "branch-hidden", hidden);
            hide.setText(hidden ? "Show in graph" : "Hide from graph");
            setContextMenu(menu);
        }
    }

    private static void onStash(Supplier<com.jfx.git.model.StashEntry> stash,
                                java.util.function.Consumer<com.jfx.git.model.StashEntry> action) {
        var s = stash.get();
        if (s != null) action.accept(s);
    }

    private void toggleStashHidden(String hash) {
        hideSolo.toggleStashHidden(hash);
        operations.refresh(OperationRunner.Refresh.EVERYTHING);
    }

    private void hideAllStashesFromGraph(java.util.Collection<String> hashes) {
        hideSolo.hideStashes(hashes);
        operations.refresh(OperationRunner.Refresh.EVERYTHING);
    }

    private void showAllStashesInGraph(java.util.Collection<String> hashes) {
        hideSolo.showStashes(hashes);
        operations.refresh(OperationRunner.Refresh.EVERYTHING);
    }

    // ------------------------------------------------------------------
    // Submodules
    // ------------------------------------------------------------------

    private TitledPane buildSubmodulePane() {
        submoduleList.getStyleClass().add("sidebar-list");
        submoduleList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Submodule item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove("submodule-stale");

                if (empty || item == null) {
                    setText(null);
                    setContextMenu(null);
                    setTooltip(null);
                    return;
                }

                // The state matters more than the hash here: a submodule nobody
                // initialised looks identical to a healthy one without it.
                setText(item.path() + "   " + item.state().label());
                if (item.state().needsUpdate()) {
                    getStyleClass().add("submodule-stale");
                }
                setTooltip(new javafx.scene.control.Tooltip(
                        (item.url() == null ? "no URL in .gitmodules" : item.url())
                                + "\nsuperproject points at " + item.shortRecorded()
                                + "\nchecked out " + item.shortCheckedOut()));
                setContextMenu(buildSubmoduleMenu(item));
            }
        });

        ContextMenu paneMenu = new ContextMenu();
        MenuItem add = new MenuItem("Add submodule…");
        add.setOnAction(e -> promptAddSubmodule());
        MenuItem updateAll = new MenuItem("Init / update all");
        updateAll.setOnAction(e -> run(() -> gitService.updateSubmodules(List.of()),
                "Updating submodules"));
        MenuItem sync = new MenuItem("Sync URLs from .gitmodules");
        sync.setOnAction(e -> run(gitService::syncSubmodules, "Syncing submodule URLs"));
        paneMenu.getItems().addAll(add, new SeparatorMenuItem(), updateAll, sync);
        submoduleList.setContextMenu(paneMenu);

        TitledPane pane = new TitledPane();
        pane.setContent(submoduleList);
        pane.setGraphic(sectionTitle("SUBMODULES", submoduleCount));
        pane.setAnimated(false);
        return pane;
    }

    private ContextMenu buildSubmoduleMenu(Submodule submodule) {
        ContextMenu menu = new ContextMenu();

        MenuItem update = new MenuItem("Init / update");
        // Nothing to do when the checkout already matches the recorded commit.
        update.setDisable(!submodule.state().needsUpdate());
        update.setOnAction(e -> run(() -> gitService.updateSubmodules(List.of(submodule.path())),
                "Updating " + submodule.path()));

        MenuItem open = new MenuItem("Open in new tab");
        java.io.File workTree = gitService.submoduleWorkTree(submodule.path());
        // An uninitialised submodule is an empty directory - there is no repository
        // to open yet, so offering it would only produce an error.
        open.setDisable(workTree == null || onOpenRepository == null);
        open.setOnAction(e -> onOpenRepository.accept(workTree));

        MenuItem terminal = new MenuItem("Open terminal here");
        terminal.setDisable(workTree == null);
        terminal.setOnAction(e -> statusBar.report(com.jfx.git.core.TerminalLauncher.open(workTree)));

        MenuItem sync = new MenuItem("Sync URL");
        sync.setOnAction(e -> run(gitService::syncSubmodules, "Syncing submodule URLs"));

        MenuItem deinit = new MenuItem("Deinitialise…");
        deinit.setDisable(submodule.state() == SubmoduleState.UNINITIALIZED);
        deinit.setOnAction(e -> confirmDeinit(submodule));

        menu.getItems().addAll(update, open, terminal, new SeparatorMenuItem(), sync, deinit);
        return menu;
    }

    private void confirmDeinit(Submodule submodule) {
        String path = submodule.path();
        // Enter deinitialises: what was committed comes back with Init / update.
        if (!Dialogs.destructive("Deinitialise submodule")
                .icon(Icons.folder())
                .title("Deinitialise `" + path + "`?")
                .subtitle("Submodule")
                .message("Its working copy is removed. It stays registered in .gitmodules, and Init / update"
                        + " checks it out again.")
                .action("Deinitialise")
                .ask()) {
            return;
        }
        operations.operation("Deinitialising " + path, OperationRunner.Refresh.EVERYTHING)
                .showFailure(result -> {
                    // Local edits inside the submodule need an explicit second yes, the same
                    // shape as force-deleting an unmerged branch - but nothing brings them
                    // back, so Enter cancels.
                    String said = result.getMessage() == null ? "" : result.getMessage();
                    if (Dialogs.destructive("Deinitialise submodule")
                            .icon(Icons.folder())
                            .title("Deinitialise `" + path + "` anyway?")
                            .subtitle(result.getRefusal() instanceof Refusal.LocalModifications
                                    ? "Its working copy has changes that are not committed"
                                    : "Git refused to deinitialise it")
                            .message("Forcing removes the working copy with everything in it, and changes that were"
                                    + " not committed cannot be recovered.")
                            .details(said)
                            .action("Deinitialise anyway")
                            .safeDefault()
                            .ask()) {
                        run(() -> gitService.deinitSubmodule(path, true), "Removing " + path);
                    }
                    return true;
                })
                // Refused, it removed nothing; the forced one refreshes for itself.
                .refreshWhen(GitResult::isOk)
                .run(() -> gitService.deinitSubmodule(path, false));
    }

    /** The add-submodule form's fields, by id: tests find them, and the error under each, by these. */
    public static final String SUBMODULE_URL_ID = "submodule-url";
    public static final String SUBMODULE_PATH_ID = "submodule-path";

    /**
     * Opens the add-submodule form. Public for the command palette.
     *
     * <p>The add runs with the form open ({@link JfxDialog#actInPlace}). Git can refuse it -
     * a folder already taken, a URL that is not a repository - and the refusal then lands
     * on the field it is about, with what was typed still there, rather than in the status
     * bar after the form had gone. Closed while it runs, the add carries on and reports in
     * the status bar as it used to.
     */
    public void promptAddSubmodule() {
        javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
        com.jfx.git.ui.DialogOwner.adopt(dialog);
        JfxDialog.shell(dialog, JfxDialog.Size.SM, "Add submodule");
        JfxDialog.header(dialog, Icons.cloneRepo(), JfxDialog.Tone.NEUTRAL, "Add a submodule",
                "Another repository, cloned into a folder of this one").ruled();
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        JfxDialog.verb(dialog, ButtonType.OK, "Add submodule");
        dialog.setResultConverter(button -> null);

        java.io.File workTree = gitService.getWorkTree();
        javafx.scene.control.TextField url = new javafx.scene.control.TextField();
        url.setId(SUBMODULE_URL_ID);
        url.setPromptText("https://github.com/owner/library.git");
        DialogParts.Field urlField = DialogParts.field("Repository URL", url)
                .hint("HTTPS or SSH, as for a clone.");
        javafx.scene.control.TextField path = new javafx.scene.control.TextField();
        path.setId(SUBMODULE_PATH_ID);
        path.setPromptText("libs/library");
        javafx.scene.control.Button browse = new javafx.scene.control.Button("Browse…");
        browse.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        DialogParts.Field pathField = DialogParts.field("Folder", path).trailing(browse)
                .hint("From the top of this repository. It is made, and the repository cloned into it.");
        dialog.getDialogPane().setContent(DialogParts.stack(urlField, pathField));

        javafx.scene.control.Button add =
                (javafx.scene.control.Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        // The last URL segment is almost always the folder wanted, so it is filled in, and
        // follows the URL until the user types their own.
        url.textProperty().addListener((obs, old, text) -> {
            urlField.error(null);
            if (path.getText().isBlank() || path.getText().equals(defaultPathFor(old))) {
                path.setText(defaultPathFor(text));
            }
        });
        path.textProperty().addListener((obs, old, text) -> {
            String problem = submodulePathProblem(workTree, text);
            pathField.error(problem);
            if (!dialog.getDialogPane().getContent().isDisabled()) {
                add.setDisable(problem != null);
            }
        });

        browse.setOnAction(e -> {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("Folder for the submodule");
            if (workTree != null && workTree.isDirectory()) {
                chooser.setInitialDirectory(workTree);
            }
            java.io.File chosen = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (chosen == null) return;
            String inside = insideRepository(workTree, chosen);
            if (inside == null) {
                pathField.error("Choose a folder inside this repository.");
                return;
            }
            // A folder with things in it is where the submodule goes, not the submodule -
            // unless it is a repository already, which git adds as it is.
            String name = defaultPathFor(url.getText());
            boolean holds = hasFiles(chosen) && !new java.io.File(chosen, ".git").exists();
            path.setText(holds && !name.isEmpty() ? (inside.isEmpty() ? name : inside + "/" + name) : inside);
        });

        JfxDialog.actInPlace(dialog, ButtonType.OK, "Adding the submodule…", progress -> {
            String source = trimmed(url);
            String folder = trimmed(path).replace('\\', '/');
            urlField.error(source.isEmpty() ? "Paste the URL of the repository to add." : null);
            pathField.error(folder.isEmpty() ? "Name the folder it goes in." : submodulePathProblem(workTree, folder));
            if (!urlField.error().isEmpty() || !pathField.error().isEmpty()) {
                progress.failed("");
                (source.isEmpty() ? url : path).requestFocus();
                return;
            }
            progress.say("Cloning " + defaultPathFor(source) + " into " + folder + "…");
            boolean[] told = {false};
            operations.operation("Adding submodule " + folder, OperationRunner.Refresh.EVERYTHING)
                    .showFailure(result -> {
                        told[0] = true;
                        // Closed while it ran, the outcome goes where it can still be seen.
                        if (progress.cancelled()) return false;
                        Refusal.Field about = result.getRefusal() instanceof Refusal.BadInput bad ? bad.field() : null;
                        refused(progress, result.getMessage(), "Could not add the submodule.",
                                about == Refusal.Field.URL ? urlField
                                        : about == Refusal.Field.PATH ? pathField : null);
                        return true;
                    })
                    .onResult(result -> releaseForm(progress, result, told[0]))
                    // Refused with the form open, nothing was added: the form says so, and
                    // the tab is not read again for it.
                    .refreshWhen(result -> result.isOk() || progress.cancelled())
                    .run(() -> gitService.addSubmodule(source, folder));
        });
        dialog.show();
    }

    /** What git would refuse about {@code path} as the folder a submodule goes in, or null for nothing typed. */
    static String submodulePathProblem(java.io.File workTree, String path) {
        String p = path == null ? "" : path.trim().replace('\\', '/');
        if (p.isEmpty()) return null;
        if (p.startsWith("/") || p.matches("^[A-Za-z]:.*")) {
            return "A path from the top of this repository, such as libs/library.";
        }
        List<String> parts = java.util.Arrays.stream(p.split("/+"))
                .filter(s -> !s.isEmpty() && !s.equals(".")).toList();
        if (parts.contains("..")) return "The folder has to be inside this repository.";
        if (parts.isEmpty()) return "Name a folder inside this repository.";
        if (parts.get(0).equals(".git")) return "Not inside .git: that folder is git's own.";
        String inside = String.join("/", parts);
        if (workTree != null) {
            java.io.File dir = new java.io.File(workTree, inside);
            if (dir.isFile()) return inside + " is a file. Name a folder.";
            // A repository there already is one git adds as it is.
            if (hasFiles(dir) && !new java.io.File(dir, ".git").exists()) {
                return inside + " already exists and has files in it.";
            }
        }
        return null;
    }

    /** {@code dir} as a path from the top of {@code workTree}, "/" between its parts; null when outside it. */
    static String insideRepository(java.io.File workTree, java.io.File dir) {
        if (workTree == null || dir == null) return null;
        java.nio.file.Path root = workTree.toPath().toAbsolutePath().normalize();
        java.nio.file.Path inside = dir.toPath().toAbsolutePath().normalize();
        return inside.startsWith(root) ? root.relativize(inside).toString().replace('\\', '/') : null;
    }

    /**
     * Ends a form's work by how its operation ended: closes the form once it worked, or once
     * the form was closed meanwhile, and hands it back when the work was cancelled before
     * it started. Such an operation never reaches the failure the form shows itself -
     * {@code told} says whether one did - and the form used to wait for it, locked, with
     * "Cloning…" in its footer.
     */
    private static void releaseForm(JfxDialog.Progress progress, GitResult result, boolean told) {
        if (result.isOk() || progress.cancelled()) {
            progress.succeeded();
        } else if (!told) {
            progress.failed("Cancelled before it started.");
        }
    }

    /**
     * Says git's refusal with the form still open: on the field it is about when it is about
     * one, with {@code outcome} at the left of the footer; otherwise the reason there alone.
     */
    private static void refused(JfxDialog.Progress progress, String raw, String outcome, DialogParts.Field about) {
        String said = refusal(raw);
        if (about != null) {
            about.error(said);
            progress.failed(outcome);
        } else {
            progress.failed(said);
        }
    }

    /** Git's refusal in a sentence: the line that says what was wrong, without its "fatal:". */
    static String refusal(String raw) {
        String line = StatusBar.failureSummary(raw == null ? "" : raw).strip();
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("fatal:") || lower.startsWith("error:")) {
            line = line.substring(line.indexOf(':') + 1).strip();
        }
        return line.isEmpty() ? "Git gave no reason." : Character.toUpperCase(line.charAt(0)) + line.substring(1);
    }

    /** Whether {@code dir} is a folder with something in it. One that cannot be read is left for git to judge. */
    static boolean hasFiles(java.io.File dir) {
        if (dir == null || !dir.isDirectory()) return false;
        try (var entries = java.nio.file.Files.newDirectoryStream(dir.toPath())) {
            return entries.iterator().hasNext();
        } catch (java.io.IOException | RuntimeException unreadable) {
            return false;
        }
    }

    private static String trimmed(javafx.scene.control.TextField field) {
        return field.getText() == null ? "" : field.getText().trim();
    }

    /**
     * The folder a worktree of {@code branch} gets: the repository's name and the branch's,
     * the convention {@code git worktree add ../repo-feature} follows.
     */
    private String worktreeFolderName(String branch) {
        java.io.File workTree = gitService.getWorkTree();
        return (workTree == null ? "worktree" : workTree.getName()) + "-" + sanitiseForPath(branch);
    }

    /** "feature/login" is two directories, not one name; flatten it. */
    static String sanitiseForPath(String branch) {
        return branch == null ? "" : branch.replaceAll("[\\\\/:*?\"<>|]+", "-");
    }

    /** "https://host/team/lib.git" -> "lib". Blank when there is nothing to go on. */
    static String defaultPathFor(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) return "";
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf(':'));
        String name = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    // ------------------------------------------------------------------
    // Worktrees
    // ------------------------------------------------------------------

    private TitledPane buildWorktreePane() {
        worktreeList.getStyleClass().add("sidebar-list");
        worktreeList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(com.jfx.git.core.Worktrees.Worktree item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("worktree-main", "worktree-missing");

                if (empty || item == null) {
                    setText(null);
                    setContextMenu(null);
                    setTooltip(null);
                    setOnMouseClicked(null);
                    return;
                }

                setText(item.name() + "   " + item.describe());
                if (item.main()) {
                    getStyleClass().add("worktree-main");
                } else if (item.missing()) {
                    getStyleClass().add("worktree-missing");
                }
                setTooltip(new javafx.scene.control.Tooltip(item.path().getPath()));
                setContextMenu(buildWorktreeMenu(item));

                setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2 && !item.main() && !item.missing()
                            && onOpenRepository != null) {
                        onOpenRepository.accept(item.path());
                    }
                });
            }
        });

        ContextMenu paneMenu = new ContextMenu();
        MenuItem add = new MenuItem("Add worktree…");
        add.setOnAction(e -> promptAddWorktree());
        MenuItem prune = new MenuItem("Prune missing worktrees");
        prune.setOnAction(e -> run(gitService::pruneWorktrees, "Pruning worktrees"));
        paneMenu.getItems().addAll(add, new SeparatorMenuItem(), prune);
        worktreeList.setContextMenu(paneMenu);

        TitledPane pane = new TitledPane();
        pane.setContent(worktreeList);
        pane.setGraphic(sectionTitle("WORKTREES", worktreeCount));
        pane.setAnimated(false);
        return pane;
    }

    private ContextMenu buildWorktreeMenu(com.jfx.git.core.Worktrees.Worktree worktree) {
        ContextMenu menu = new ContextMenu();

        MenuItem open = new MenuItem("Open in new tab");
        // The main worktree is the tab you are already in.
        open.setDisable(worktree.main() || worktree.missing() || onOpenRepository == null);
        open.setOnAction(e -> onOpenRepository.accept(worktree.path()));

        // A second working tree is usually there to run something in: a build of
        // another branch, a long test. This is the short way to it.
        MenuItem terminal = new MenuItem("Open terminal here");
        terminal.setDisable(worktree.missing());
        terminal.setOnAction(e -> statusBar.report(
                com.jfx.git.core.TerminalLauncher.open(worktree.path())));

        MenuItem lock = new MenuItem(worktree.locked() ? "Unlock" : "Lock");
        lock.setDisable(worktree.main());
        lock.setOnAction(e -> run(
                () -> gitService.setWorktreeLocked(worktree.path(), !worktree.locked()),
                (worktree.locked() ? "Unlocking " : "Locking ") + worktree.name()));

        MenuItem remove = new MenuItem("Remove…");
        remove.setDisable(worktree.main());
        remove.setOnAction(e -> confirmRemoveWorktree(worktree));

        MenuItem prune = new MenuItem("Prune missing worktrees");
        prune.setOnAction(e -> run(gitService::pruneWorktrees, "Pruning worktrees"));

        menu.getItems().addAll(open, terminal, new SeparatorMenuItem(), lock, remove, prune);
        return menu;
    }

    private void confirmRemoveWorktree(com.jfx.git.core.Worktrees.Worktree worktree) {
        // Enter removes: git refuses a tree with changes in it, so what goes is committed.
        if (!Dialogs.destructive("Remove worktree")
                .icon(Icons.folder())
                .title("Remove the worktree `" + worktree.name() + "`?")
                .subtitle("`" + worktree.path() + "`")
                .message("Its folder is deleted. Commits made there stay in the repository.")
                .action("Remove worktree")
                .size(JfxDialog.Size.MD)
                .ask()) {
            return;
        }
        operations.operation("Removing " + worktree.name(), OperationRunner.Refresh.EVERYTHING)
                .showFailure(result -> {
                    // git refuses while the tree is dirty; offer the force it suggests. What
                    // is forced away is not committed anywhere, so Enter cancels.
                    String said = result.getMessage() == null ? "" : result.getMessage();
                    if (Dialogs.destructive("Remove worktree")
                            .icon(Icons.folder())
                            .title("Remove `" + worktree.name() + "` anyway?")
                            .subtitle(result.getRefusal() instanceof Refusal.LocalModifications
                                    ? "It has changes that are not committed"
                                    : "Git refused to remove it")
                            .message("Forcing deletes its folder with everything in it, and changes that were not"
                                    + " committed cannot be recovered.")
                            .details(said)
                            .action("Remove anyway")
                            .safeDefault()
                            .ask()) {
                        run(() -> gitService.removeWorktree(worktree.path(), true), "Removing " + worktree.name());
                    }
                    return true;
                })
                // Refused, it removed nothing; the forced one refreshes for itself.
                .refreshWhen(GitResult::isOk)
                .run(() -> gitService.removeWorktree(worktree.path(), false));
    }

    private void promptAddWorktree() {
        promptAddWorktree("");
    }

    /** The add-worktree form's controls, by id: tests find them, and the error under each, by these. */
    public static final String WORKTREE_BRANCH_ID = "worktree-branch";
    public static final String WORKTREE_CREATE_ID = "worktree-create";
    public static final String WORKTREE_PATH_ID = "worktree-path";

    /**
     * Opens the add-worktree form, on {@code forBranch} when it came from a branch's menu.
     *
     * <p>What git would refuse is said as it is typed - a branch checked out already, one to
     * create that exists, a folder with things in it - and the button waits for it. What
     * it refuses anyway lands on the field it is about, with the form still open
     * ({@link JfxDialog#actInPlace}).
     *
     * <p>The folder follows the branch, a sibling of the repository named after both, until
     * one is typed or chosen. Browse to a folder with things in it and the worktree goes
     * inside it; browse to an empty one and the worktree is that folder.
     */
    private void promptAddWorktree(String forBranch) {
        javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
        com.jfx.git.ui.DialogOwner.adopt(dialog);
        JfxDialog.shell(dialog, JfxDialog.Size.MD, "Add worktree");
        JfxDialog.header(dialog, Icons.folder(), JfxDialog.Tone.NEUTRAL, "Add a worktree",
                "This repository checked out a second time, in a folder of its own").ruled();
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        JfxDialog.verb(dialog, ButtonType.OK, "Add worktree");
        dialog.setResultConverter(button -> null);

        java.io.File workTree = gitService.getWorkTree();
        java.io.File[] parent = {workTree == null ? null : workTree.getAbsoluteFile().getParentFile()};

        javafx.scene.control.TextField branch = new javafx.scene.control.TextField(forBranch);
        branch.setId(WORKTREE_BRANCH_ID);
        branch.setPromptText("feature/search");
        DialogParts.Field branchField = DialogParts.field("Branch", branch);
        javafx.scene.control.CheckBox create = new javafx.scene.control.CheckBox("Create this branch");
        create.setId(WORKTREE_CREATE_ID);
        javafx.scene.Node createRow = DialogParts.toggle(create, "Makes a new branch of that name at the commit "
                + "checked out here, and checks it out in the new folder. Unticked, the branch has to exist.");

        javafx.scene.control.TextField path = new javafx.scene.control.TextField();
        path.setId(WORKTREE_PATH_ID);
        path.setPromptText(parent[0] == null ? "" : new java.io.File(parent[0], worktreeFolderName("feature")).getPath());
        javafx.scene.control.Button browse = new javafx.scene.control.Button("Browse…");
        browse.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        DialogParts.Field pathField = DialogParts.field("Folder", path).trailing(browse);
        dialog.getDialogPane().setContent(DialogParts.stack(branchField, createRow, pathField));

        javafx.scene.control.Button add =
                (javafx.scene.control.Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        Supplier<java.io.File> folder = () -> worktreeFolder(trimmed(path), workTree);
        Runnable decide = () -> {
            if (dialog.getDialogPane().getContent().isDisabled()) {
                return; // adding: the button is the add's until it ends
            }
            add.setDisable(worktreePathProblem(folder.get()) != null
                    || worktreeBranchProblem(trimmed(branch), create.isSelected()) != null);
        };
        Runnable checkBranch = () -> {
            branchField.error(worktreeBranchProblem(trimmed(branch), create.isSelected()));
            decide.run();
        };
        Runnable checkPath = () -> {
            java.io.File dir = folder.get();
            pathField.error(worktreePathProblem(dir));
            pathField.hint(worktreePathHint(dir, trimmed(path)));
            decide.run();
        };

        String[] filled = {""};
        Runnable follow = () -> {
            String typed = trimmed(path);
            if (typed.isEmpty() || typed.equals(filled[0])) {
                String name = trimmed(branch);
                filled[0] = name.isEmpty() || parent[0] == null ? ""
                        : new java.io.File(parent[0], worktreeFolderName(name)).getPath();
                path.setText(filled[0]);
            }
        };
        branch.textProperty().addListener((obs, old, text) -> {
            follow.run();
            checkBranch.run();
        });
        create.selectedProperty().addListener((obs, was, is) -> checkBranch.run());
        path.textProperty().addListener((obs, old, text) -> checkPath.run());
        follow.run();
        checkBranch.run();
        checkPath.run();

        browse.setOnAction(e -> {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("Folder for the new worktree");
            for (java.io.File f = folder.get(); f != null; f = f.getParentFile()) {
                if (f.isDirectory()) {
                    chooser.setInitialDirectory(f);
                    break;
                }
            }
            if (chooser.getInitialDirectory() == null && parent[0] != null && parent[0].isDirectory()) {
                chooser.setInitialDirectory(parent[0]);
            }
            java.io.File chosen = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
            if (chosen == null) return;
            if (!hasFiles(chosen)) {
                // An empty folder, made for it: the worktree is that folder.
                filled[0] = "";
                path.setText(chosen.getPath());
            } else {
                // A folder with things in it is where the worktree goes, not the worktree.
                parent[0] = chosen;
                String name = trimmed(branch);
                filled[0] = name.isEmpty() ? chosen.getPath()
                        : new java.io.File(chosen, worktreeFolderName(name)).getPath();
                path.setText(filled[0]);
            }
        });

        JfxDialog.actInPlace(dialog, ButtonType.OK, "Adding the worktree…", progress -> {
            java.io.File dir = folder.get();
            String name = trimmed(branch);
            boolean creating = create.isSelected();
            branchField.error(name.isEmpty() ? "Name the branch to check out there."
                    : worktreeBranchProblem(name, creating));
            pathField.error(dir == null ? "Choose the folder for the new worktree." : worktreePathProblem(dir));
            if (!branchField.error().isEmpty() || !pathField.error().isEmpty()) {
                progress.failed("");
                decide.run();
                (!branchField.error().isEmpty() ? branch : path).requestFocus();
                return;
            }
            // The whole path, for a folder typed as a bare name: where it went, in the field.
            path.setText(dir.getPath());
            progress.say("Checking out " + name + " in " + dir.getName() + "…");
            boolean[] told = {false};
            operations.operation("Adding worktree " + dir.getName(), OperationRunner.Refresh.EVERYTHING)
                    .showFailure(result -> {
                        told[0] = true;
                        // Closed while it ran, the outcome goes where it can still be seen.
                        if (progress.cancelled()) return false;
                        Refusal.Field about = result.getRefusal() instanceof Refusal.BadInput bad ? bad.field() : null;
                        refused(progress, result.getMessage(), "Could not add the worktree.",
                                about == Refusal.Field.BRANCH ? branchField
                                        : about == Refusal.Field.PATH ? pathField : null);
                        decide.run();
                        return true;
                    })
                    .onResult(result -> {
                        releaseForm(progress, result, told[0]);
                        if (!told[0] && !result.isOk()) decide.run();
                    })
                    // Refused with the form open, nothing was added: the form says so, and
                    // the tab is not read again for it.
                    .refreshWhen(result -> result.isOk() || progress.cancelled())
                    .run(() -> gitService.addWorktree(dir, name, creating));
        });
        dialog.show();
    }

    /** What git would refuse about {@code name} as the new worktree's branch, as far as the sidebar knows. */
    private String worktreeBranchProblem(String name, boolean create) {
        return worktreeBranchProblem(name, create, model.loaded() ? model.localNames() : null,
                model.remoteBranchNames(), model.remoteNames(), model.worktrees());
    }

    /**
     * What git would refuse about checking {@code name} out in a new worktree, or null.
     *
     * @param locals      the local branches, or null while they are not known - then only
     *                    the name itself is checked, and git says the rest
     * @param remoteNames the configured remotes, which decide each remote branch's branch part
     */
    static String worktreeBranchProblem(String name, boolean create, List<String> locals, List<String> remotes,
                                        List<String> remoteNames,
                                        List<com.jfx.git.core.Worktrees.Worktree> worktrees) {
        String b = name == null ? "" : name.trim();
        if (b.isEmpty()) return null;
        String problem = RefNames.branchNameProblem(b);
        if (problem != null) return problem;
        if (locals == null) return null;
        boolean exists = locals.contains(b);
        if (create) {
            return exists ? "A branch named " + b + " exists already. Untick \"Create this branch\" to check it out."
                    : null;
        }
        if (!exists) {
            // Git makes one tracking a remote branch of that name, as checkout does.
            boolean onRemote = remotes != null
                    && remotes.stream().anyMatch(r -> b.equals(RefNames.branchOf(r, remoteNames)));
            return onRemote ? null : "There is no branch named " + b + ". Tick \"Create this branch\" to make one.";
        }
        for (com.jfx.git.core.Worktrees.Worktree worktree : worktrees == null
                ? List.<com.jfx.git.core.Worktrees.Worktree>of() : worktrees) {
            if (!worktree.detached() && b.equals(worktree.branch())) {
                // Git checks a branch out in one worktree at a time.
                return b + " is checked out in " + worktree.path().getName() + " already. Name a new branch"
                        + " and tick \"Create this branch\".";
            }
        }
        return null;
    }

    /** The folder the form names: the path as typed when it is a whole one, else from the top of the repository, as git reads it. */
    static java.io.File worktreeFolder(String typed, java.io.File workTree) {
        String text = typed == null ? "" : typed.trim();
        if (text.isEmpty()) return null;
        java.io.File dir = new java.io.File(text);
        return (dir.isAbsolute() || workTree == null ? dir : new java.io.File(workTree, text)).getAbsoluteFile();
    }

    /** What keeps {@code dir} from taking a worktree, or null: a file there, or a folder with things in it. */
    static String worktreePathProblem(java.io.File dir) {
        if (dir == null) return null;
        String name = dir.getName().isEmpty() ? dir.getPath() : dir.getName();
        if (dir.isFile()) return name + " is a file. Choose a folder.";
        if (hasFiles(dir)) return name + " already exists and has files in it. Choose a new folder, or an empty one.";
        return null;
    }

    /** The folder the worktree makes, named under the field; where it is too, for one typed from the repository. */
    static String worktreePathHint(java.io.File dir, String typed) {
        if (dir == null || worktreePathProblem(dir) != null) return "A new folder, or an empty one.";
        boolean whole = typed != null && new java.io.File(typed.trim()).isAbsolute();
        String where = whole || dir.getParentFile() == null ? "" : " in " + dir.getParentFile().getPath();
        return (dir.isDirectory() ? "Checks out into the empty folder " : "Creates the folder ")
                + dir.getName() + where + ".";
    }

    // ------------------------------------------------------------------
    // Refresh
    // ------------------------------------------------------------------

    /**
     * The refs of the page that just landed: the branches, tags, stashes, remotes,
     * upstreams and current branch the sections are drawn from (ADR 0002).
     *
     * <p>Handed every refreshed page, whether or not the graph replaced the one on screen:
     * a page that draws the same can come with refs that do not - a new remote, a branch
     * whose tip is further down. Drawn at once unless this sidebar's own read is still on
     * its way, in which case that read's landing draws it; so one refresh draws once.
     */
    public void showRefs(RefSnapshot snapshot) {
        // Read directly for a page that failed: drawn even when it is the same, since the
        // own read that landed meanwhile waited for it.
        boolean awaited = refsPending;
        refsPending = false;
        if (snapshot == null) {
            // Nothing after all: what waited for it is drawn with the refs there are.
            drawUndrawnRefs();
            return;
        }
        if (!awaited && refsKnown && snapshot.equals(refs)) return;
        refs = snapshot;
        refsKnown = true;
        if (reading == 0 && own != null) {
            draw();
        } else {
            refsUndrawn = true;
        }
    }

    /**
     * The page this refresh loads could not be read, so no refs come with it: reads them
     * directly, on the repository lane, and draws the sections - and the toolbar, from
     * {@link #setOnLoaded} - from those (ADR 0002).
     *
     * <p>Drawn from no snapshot at all, the sidebar listed no branch to check out or delete
     * - the one the history could not be read at included - and offered to add a remote
     * beside the ones there were; drawn from the last page's, it listed a branch deleted
     * since. Only for a page that failed: a refresh whose page lands still reads the refs
     * once.
     */
    public void readRefsDirectly() {
        refsPending = true;
        GitTaskRunner.query(gitService, gitService::getRefSnapshot, this::showRefs, error -> {
            refsPending = false;
            statusBar.error(error);
            // The last refs there were, beside an own read that waited for these.
            drawUndrawnRefs();
        }).onDropped(() -> {
            refsPending = false;
            drawUndrawnRefs();
        });
    }

    /**
     * Reloads what only the sidebar shows - tracking counts, gone branches, submodules,
     * worktrees, the Git Flow settings and the origin URL - off the FX thread, then draws
     * every section with the newest ref snapshot.
     *
     * <p>Never with none: until a page - or the direct read a failed page asks for - has
     * brought refs, there is nothing to draw the sections from, and the snapshot's landing
     * draws them.
     */
    public void refresh() {
        reading++;
        GitTaskRunner.query(gitService, this::readOwn, read -> {
            reading--;
            own = read;
            if (refsKnown && !refsPending) {
                draw();
            } else {
                refsUndrawn = refsKnown;
            }
        }, error -> {
            reading--;
            statusBar.error(error);
            drawUndrawnRefs();
        }).onDropped(() -> {
            // Never run - cancelled from the status bar before it started - so it never
            // answers: over all the same, or no snapshot after it would be drawn again.
            reading--;
            drawUndrawnRefs();
        });
    }

    /**
     * Draws refs that came while an own read was out, once none is: the read that was to
     * draw them failed, or never ran. The last own read is what they are drawn beside.
     */
    private void drawUndrawnRefs() {
        if (refsUndrawn && reading == 0 && own != null && !refsPending) draw();
    }

    /** Draws every section from the newest snapshot and this sidebar's own last read. */
    private void draw() {
        refsUndrawn = false;
        OwnRead read = own;
        RefSnapshot snapshot = refs;
        flowConfig = read.flowConfig();
        // Hide & Solo as stored now, read once per refresh rather than per row: every
        // branch, tag and stash row asked the settings as it was drawn - a parse of the
        // stored list per row per scroll tick - and they change only through actions that
        // refresh the sidebar.
        model.load(new SidebarModel.Load(snapshot, read.branchTracking(), read.gone(), read.submodules(),
                read.worktrees(), flowConfig, hideSolo.visibility(), hideSolo.hiddenStashes()));

        // Most repositories have no submodules, only the main worktree and no Git Flow;
        // those sections are there only when the model says there is something in them.
        // The first submodule is added from the LOCAL section's menu or the command
        // palette, since the hidden section's own menu cannot be reached.
        for (Section section : List.of(Section.SUBMODULES, Section.WORKTREES, Section.GIT_FLOW)) {
            boolean present = model.present(section);
            panes.get(section).setVisible(present);
            panes.get(section).setManaged(present);
        }

        // Every section drawn through the filter; each takes the height its own
        // contents need, so one branch stops reserving a screenful of empty box.
        applyFilter();

        statusBar.setBranch(snapshot.headLabel(), read.tracking());
        boolean noRemotes = model.offersAddRemote();
        addRemoteButton.setVisible(noRemotes);
        addRemoteButton.setManaged(noRemotes);
        if (onLoaded != null) {
            onLoaded.loaded(model.localNames(), snapshot, read.tracking(),
                    snapshot.stashes().size(), read.originUrl());
        }
    }

    /**
     * What only the sidebar reads: the refs themselves come from the graph page's
     * snapshot (see {@link #showRefs}).
     */
    private record OwnRead(int[] tracking,
                           List<Submodule> submodules,
                           List<com.jfx.git.core.Worktrees.Worktree> worktrees,
                           java.util.Map<String, int[]> branchTracking,
                           com.jfx.git.core.GitFlow.Config flowConfig,
                           String originUrl,
                           java.util.Set<String> gone) {
    }

    /**
     * The remote tree: every remote as a folder, its branches under it.
     *
     * <p>Each remote one folder, slashes in its name and all; which folders are open, and
     * which empty ones are listed, the model says (see {@link SidebarModel#folderOpen} and
     * {@link SidebarModel.Answer#remoteFolders}).
     */
    private TreeItem<BranchTree.Node> remoteRoot(SidebarModel.Answer answer) {
        TreeItem<BranchTree.Node> root = BranchTree.build(answer.names(Section.REMOTE), answer.remoteRoots(),
                answer.remoteFolders(), path -> model.folderOpen(Section.REMOTE, path));
        rememberFolders(root, Section.REMOTE);
        return root;
    }

    /**
     * Selects {@code hash} in the graph - the tip of {@code fullRef}, a branch or a tag.
     *
     * <p>A ref Hide/Solo excludes goes the other way: its tip is not a start of the walk,
     * by the user's own choice, so it is on the graph only when a branch that is shown
     * reaches it too, and the tab asks that before paging history in to find it.
     */
    private void reveal(String hash, String fullRef) {
        if (hash == null || hash.isBlank()) return;
        // The walk's own rule, as stored now, so what a click looks for is what the graph starts from.
        if (hideSolo.visibility().excludesName(fullRef)) {
            onRevealHiddenRef.accept(hash);
        } else if (onSelectCommit != null) {
            onSelectCommit.accept(hash);
        }
    }

    /** Whether a click landed on a button inside the row - the eye - rather than on the row. */
    private static boolean onAButton(javafx.scene.input.MouseEvent e) {
        for (Object n = e.getTarget(); n instanceof javafx.scene.Node node; n = node.getParent()) {
            if (node instanceof javafx.scene.control.ButtonBase) return true;
            if (node instanceof javafx.scene.control.Cell<?>) return false;
        }
        return false;
    }

    /** The Git Flow settings, read from the repository only when they may have changed. */
    private com.jfx.git.core.GitFlow.Config loadFlowConfig() {
        if (flowConfigKnown) {
            return flowConfig;
        }
        com.jfx.git.core.GitFlow.Config loaded = new com.jfx.git.core.GitFlow(gitService).config();
        flowConfigKnown = true;
        return loaded;
    }

    /**
     * The sidebar's own read. Not the refs: the branches, tags, stashes, remotes, upstreams
     * and current branch were read once already, by the graph page this refresh loads, and
     * arrive with it (see {@link #showRefs}).
     */
    private OwnRead readOwn() {
        return new OwnRead(
                gitService.getTrackingStatus(),
                gitService.getSubmodules(),
                gitService.getWorktrees(),
                // One call for every branch, gathered here so the cells never touch git.
                gitService.getBranchTracking(),
                loadFlowConfig(),
                // For the toolbar's provider buttons, which used to read it themselves.
                gitService.getRemoteUrl("origin"),
                // The same for-each-ref the counts come from, on the CLI engine.
                gitService.getGoneBranches());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void run(Supplier<GitResult> work, String description) {
        operations.run(description, OperationRunner.Refresh.EVERYTHING, work);
    }

    /** "Rebase current onto this": the editor first, as from the graph. See {@link RefDropMenu}. */
    private void startInteractiveRebaseOnto(String upstream) {
        refDrops.rebaseCurrentOnto(upstream);
    }

    /**
     * Checks out {@code ref}, but first asks when doing so would strand the current
     * detached-HEAD commit with no branch or tag left pointing at it — the moment
     * the commercial clients' warning fires. Attached HEAD, or a detached commit some other
     * ref still reaches, checks out immediately with no extra round trip visible to
     * the user beyond the usual busy/idle flash.
     */
    private void checkoutWithOrphanGuard(String ref, String description) {
        GitTaskRunner.query(gitService, gitService::isDetachedHeadOrphanRisk, atRisk -> {
            if (atRisk && !confirmLeaveOrphanedCommit()) {
                return;
            }
            checkout(ref, description);
        }, error -> checkout(ref, description));
    }

    /**
     * Checks out {@code ref}, offering to stash, check out and re-apply when local changes
     * are in the way; see {@link com.jfx.git.core.AutoStash}.
     */
    private void checkout(String ref, String description) {
        checkout(() -> gitService.checkoutBranch(ref), description);
    }

    /**
     * A remote branch checked out as a local one, with the same offer when local changes
     * are in the way: refused, it used to leave the user only git's refusal.
     */
    private void checkoutRemote(String remoteBranch) {
        checkout(() -> gitService.checkoutRemoteBranch(remoteBranch), "Checking out " + remoteBranch);
    }

    /** The caller-side commands - merge, stash by commit, push - over this sidebar's backend. */
    private RepositoryCommands commands() {
        return new RepositoryCommands(gitService);
    }

    private void checkout(Supplier<GitResult> work, String description) {
        withAutoStash(com.jfx.git.core.AutoStash.Operation.CHECKOUT, work, description);
    }

    /**
     * A merge into the current branch, from any of the sidebar's merge items, with the
     * same offer: git's refusal over local changes comes back as stash, merge, re-apply.
     */
    private void merge(Supplier<GitResult> work, String description) {
        withAutoStash(com.jfx.git.core.AutoStash.Operation.MERGE, work, description);
    }

    private void withAutoStash(com.jfx.git.core.AutoStash.Operation operation, Supplier<GitResult> work,
                               String description) {
        operations.operation(description, OperationRunner.Refresh.EVERYTHING).withAutoStash(operation, work);
    }

    /**
     * The commit itself is not shown here (the caller already knows which repository
     * state HEAD is in; naming it again would just repeat the banner) — the point is
     * the choice, not the diagnosis.
     *
     * <p>Enter cancels: this is the second thought on a checkout already asked for.
     */
    private boolean confirmLeaveOrphanedCommit() {
        return Dialogs.warn("Detached HEAD")
                .icon(Icons.commit())
                .title("Leave this commit behind?")
                .subtitle("Detached HEAD · no branch or tag points at it")
                .message("Once you check out, only the reflog remembers it, and git can delete it when that"
                        + " entry expires. To keep it, cancel and give it a branch with Create branch here.")
                .action("Check out anyway")
                .safeDefault()
                .ask();
    }
}
