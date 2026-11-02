package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.WorkspaceFiles;
import com.jfx.git.ui.dialogs.Dialogs;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The whole repository as a tree, not just what changed.
 *
 * <p>The staging panel answers "what have I touched"; this answers "what is in here",
 * which is the question you have when you want to open a file you have not edited yet.
 *
 * <h2>Loading</h2>
 * Children are read when a node is first expanded rather than up front. A repository with
 * tens of thousands of files would otherwise stall the panel building a tree of which the
 * user will look at a dozen nodes.
 */
public class WorkspaceTreeView extends TreeView<WorkspaceFiles.Entry> {

    private final GitBackend gitService;
    private final StatusBar statusBar;
    private final WorkspaceFiles workspace;
    /** Runs the git actions; the file actions only ask it to redraw. */
    private final OperationRunner operations;

    /** Opens a path in the diff viewer / editor. */
    private final Consumer<String> onOpenFile;

    /** Paths git currently reports as changed, for marking them in the tree. */
    private Set<String> changedPaths = Set.of();

    /** Current name filter; empty means show everything through the normal lazy tree. */
    private String filterText = "";

    public WorkspaceTreeView(OperationRunner operations, Consumer<String> onOpenFile) {
        this.operations = operations;
        this.gitService = operations.repository();
        this.statusBar = operations.statusBar();
        this.onOpenFile = onOpenFile;
        this.workspace = new WorkspaceFiles(gitService.getWorkTree());

        getStyleClass().add("sidebar-list");
        setShowRoot(false);
        setCellFactory(tv -> new EntryCell());

        rebuild();
    }

    /** Reloads the tree from disk, keeping which nodes were expanded. */
    public void rebuild() {
        if (!filterText.isEmpty()) {
            // A refresh must not silently drop the user's active filter.
            setRoot(buildFilteredRoot(filterText.toLowerCase(java.util.Locale.ROOT)));
            return;
        }
        List<String> expanded = collectExpanded();

        var rootEntry = new WorkspaceFiles.Entry("", workspace.workTree() == null
                ? "(no repository)" : workspace.workTree().getName(), true);
        LazyItem root = new LazyItem(rootEntry);
        root.setExpanded(true);
        setRoot(root);

        restoreExpanded(root, Set.copyOf(expanded));
    }

    /**
     * Filters the tree to files whose name contains {@code text} (case-insensitive),
     * keeping every ancestor of a match so the results stay navigable.
     *
     * @return the number of matching files shown; the caller displays this because
     *         "3 files" next to the field is what tells the user the filter bit
     */
    public int applyFilter(String text) {
        this.filterText = text == null ? "" : text.strip();
        if (filterText.isEmpty()) {
            rebuild();
            return countFiles(getRoot());
        }
        TreeItem<WorkspaceFiles.Entry> root =
                buildFilteredRoot(filterText.toLowerCase(java.util.Locale.ROOT));
        setRoot(root);
        return countFiles(root);
    }

    /**
     * Builds a fully materialised (non-lazy) tree of everything matching the needle.
     * Eager by necessity: a lazy tree cannot answer "which files match" without
     * walking them all anyway. Directory listings are plain filesystem reads, so this
     * stays on the FX thread only for the walk itself.
     */
    private TreeItem<WorkspaceFiles.Entry> buildFilteredRoot(String needle) {
        var rootEntry = new WorkspaceFiles.Entry("", workspace.workTree() == null
                ? "(no repository)" : workspace.workTree().getName(), true);
        TreeItem<WorkspaceFiles.Entry> root = new TreeItem<>(rootEntry);
        root.setExpanded(true);
        for (WorkspaceFiles.Entry entry : workspace.children("")) {
            fillFiltered(root, entry, needle);
        }
        return root;
    }

    /** Adds {@code entry} under {@code parent} if it or any descendant matches; true if it did. */
    private boolean fillFiltered(TreeItem<WorkspaceFiles.Entry> parent,
                                 WorkspaceFiles.Entry entry, String needle) {
        if (entry.directory()) {
            TreeItem<WorkspaceFiles.Entry> dir = new TreeItem<>(entry);
            dir.setExpanded(true);
            boolean any = false;
            for (WorkspaceFiles.Entry child : workspace.children(entry.path())) {
                any |= fillFiltered(dir, child, needle);
            }
            if (any) {
                parent.getChildren().add(dir);
            }
            return any;
        }
        if (entry.name().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
            parent.getChildren().add(new TreeItem<>(entry));
            return true;
        }
        return false;
    }

    private static int countFiles(TreeItem<WorkspaceFiles.Entry> item) {
        if (item == null) return 0;
        int n = item.getValue() != null && !item.getValue().directory() ? 1 : 0;
        for (var child : item.getChildren()) {
            n += countFiles(child);
        }
        return n;
    }

    /** Marks which paths differ from HEAD, so the tree can show them like the panel does. */
    public void setChangedPaths(Set<String> changedPaths) {
        this.changedPaths = Set.copyOf(changedPaths);
        refresh();
    }

    // ------------------------------------------------------------------
    // Expansion state
    // ------------------------------------------------------------------

    private List<String> collectExpanded() {
        List<String> paths = new java.util.ArrayList<>();
        if (getRoot() != null) collectExpanded(getRoot(), paths);
        return paths;
    }

    private static void collectExpanded(TreeItem<WorkspaceFiles.Entry> item, List<String> into) {
        if (item.isExpanded() && item.getValue() != null) {
            into.add(item.getValue().path());
        }
        for (var child : item.getChildren()) {
            collectExpanded(child, into);
        }
    }

    private static void restoreExpanded(TreeItem<WorkspaceFiles.Entry> item, Set<String> expanded) {
        if (item.getValue() != null && expanded.contains(item.getValue().path())) {
            item.setExpanded(true);
            for (var child : item.getChildren()) {
                restoreExpanded(child, expanded);
            }
        }
    }

    /**
     * A directory node that reads its children the first time it is expanded.
     *
     * <p>{@code isLeaf} answers from the entry rather than from the child list, so a
     * directory still shows a disclosure arrow before anything has been loaded.
     */
    private final class LazyItem extends TreeItem<WorkspaceFiles.Entry> {
        private boolean loaded;

        LazyItem(WorkspaceFiles.Entry entry) {
            super(entry);
            expandedProperty().addListener((obs, was, isNow) -> {
                if (isNow && !loaded) load();
            });
        }

        @Override
        public boolean isLeaf() {
            return !getValue().directory();
        }

        private void load() {
            loaded = true;
            String path = getValue().path();
            GitTaskRunner.query(gitService,
                    () -> workspace.children(path),
                    children -> {
                        getChildren().setAll(children.stream()
                                .map(e -> (TreeItem<WorkspaceFiles.Entry>) new LazyItem(e))
                                .toList());
                    },
                    statusBar::error);
        }
    }

    // ------------------------------------------------------------------
    // Cells
    // ------------------------------------------------------------------

    /**
     * How many changed files sit anywhere under {@code folder}.
     *
     * <p>A linear scan of the changed set rather than a walk of the tree: the tree is
     * loaded lazily, so most of it does not exist to be counted, and the changed set is
     * the size of one person's uncommitted work.
     */
    int changedFilesUnder(String folder) {
        if (folder == null || folder.isEmpty()) {
            return changedPaths.size();
        }
        String prefix = folder.endsWith("/") ? folder : folder + "/";
        int found = 0;
        for (String changed : changedPaths) {
            if (changed.startsWith(prefix)) {
                found++;
            }
        }
        return found;
    }

    /**
     * A folder row with its tally beside the name.
     *
     * <p>Beside rather than at the right-hand edge: a tree cell lays its graphic out at
     * the graphic's own width, so a badge pushed right with a spacer would sit wherever
     * the name happened to end anyway - and pinning it to the true edge means binding
     * against the viewport width through a moving indent and a scrollbar that comes and
     * goes. The number is worth more than the column.
     */
    private static javafx.scene.Node folderRowWithTally(String name, int changed) {
        javafx.scene.control.Label label = new javafx.scene.control.Label(name);
        javafx.scene.control.Label badge =
                new javafx.scene.control.Label(String.valueOf(changed));
        badge.getStyleClass().addAll("count-badge", "workspace-tally");
        badge.setTooltip(new javafx.scene.control.Tooltip(changed == 1
                ? "1 changed file inside"
                : changed + " changed files inside"));
        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(
                UiScale.STEP, Icons.folder(), label, badge);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        return row;
    }

    private final class EntryCell extends TreeCell<WorkspaceFiles.Entry> {

        @Override
        protected void updateItem(WorkspaceFiles.Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                getStyleClass().removeAll("workspace-changed");
                return;
            }

            // A folder carrying changed work says so, so the branch of the tree worth
            // opening is visible without opening any of it. Only when there is something
            // to report: a tally of zero on every folder is noise the eye has to filter
            // out of the one view whose job is finding a file.
            int changedInside = entry.directory() ? changedFilesUnder(entry.path()) : 0;
            if (changedInside > 0) {
                setText(null);
                setGraphic(folderRowWithTally(entry.name(), changedInside));
            } else {
                setText(entry.name());
                setGraphic(entry.directory() ? Icons.folder() : Icons.file());
            }

            // Changed files are called out here too, so switching to the full tree does
            // not lose the one piece of information the changed-files list was giving.
            getStyleClass().remove("workspace-changed");
            if (!entry.directory() && changedPaths.contains(entry.path())) {
                getStyleClass().add("workspace-changed");
            }

            setContextMenu(buildMenu(entry));
            setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !entry.directory()) {
                    onOpenFile.accept(entry.path());
                }
            });
        }

        private ContextMenu buildMenu(WorkspaceFiles.Entry entry) {
            ContextMenu menu = new ContextMenu();

            if (!entry.directory()) {
                MenuItem open = new MenuItem("Open");
                open.setOnAction(e -> onOpenFile.accept(entry.path()));

                MenuItem stage = new MenuItem("Stage");
                stage.setOnAction(e -> runGit(() -> gitService.stageFile(entry.path()),
                        "Staging " + entry.name()));

                menu.getItems().addAll(open, stage, new SeparatorMenuItem());
            }

            // New files land inside the directory that was clicked, or beside the file.
            String base = entry.directory() ? entry.path() : entry.parent();

            MenuItem newFile = new MenuItem("New file…");
            newFile.setOnAction(e -> promptNewFile(base));

            MenuItem newFolder = new MenuItem("New folder…");
            newFolder.setOnAction(e -> promptNewFolder(base));

            MenuItem delete = new MenuItem("Delete…");
            delete.setOnAction(e -> confirmDelete(entry));

            menu.getItems().addAll(newFile, newFolder, new SeparatorMenuItem(), delete);
            if (entry.directory() && workspace.workTree() != null) {
                // In the folder itself, which is where the command about to be typed
                // is meant to run; the toolbar's button opens the repository's root.
                MenuItem terminal = new MenuItem("Open terminal here");
                terminal.setOnAction(e -> statusBar.report(com.jfx.git.core.TerminalLauncher.open(
                        entry.path().isEmpty() ? workspace.workTree()
                                : new java.io.File(workspace.workTree(), entry.path()))));
                menu.getItems().addAll(new SeparatorMenuItem(), terminal);
            }
            menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().add(buildIgnoreMenu(entry));
            return menu;
        }

        private Menu buildIgnoreMenu(WorkspaceFiles.Entry entry) {
            Menu ignore = new Menu("Ignore");

            MenuItem thisPath = new MenuItem("This " + (entry.directory() ? "folder" : "file"));
            thisPath.setOnAction(e -> applyIgnore(entry.path(), WorkspaceFiles.IgnoreScope.FILE));

            // Only offered when the rule would actually be something: a file with no
            // extension, or one at the repository root, has no such rule to write.
            String extensionRule = WorkspaceFiles.ruleFor(entry.path(),
                    WorkspaceFiles.IgnoreScope.EXTENSION);
            MenuItem byExtension = new MenuItem(extensionRule == null
                    ? "All files of this type (no extension)"
                    : "All " + extensionRule + " files");
            byExtension.setDisable(extensionRule == null);
            byExtension.setOnAction(e -> applyIgnore(entry.path(), WorkspaceFiles.IgnoreScope.EXTENSION));

            String folderRule = WorkspaceFiles.ruleFor(entry.path(), WorkspaceFiles.IgnoreScope.FOLDER);
            MenuItem containingFolder = new MenuItem(folderRule == null
                    ? "Containing folder (already at the root)"
                    : "Containing folder " + folderRule);
            containingFolder.setDisable(folderRule == null);
            containingFolder.setOnAction(e -> applyIgnore(entry.path(), WorkspaceFiles.IgnoreScope.FOLDER));

            ignore.getItems().addAll(thisPath, byExtension, containingFolder);
            return ignore;
        }
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void promptNewFile(String parentPath) {
        Dialogs.prompt("New file")
                .icon(Icons.file())
                .title(parentPath.isEmpty() ? "Create a file in the repository root"
                        : "Create a file in `" + parentPath + "`")
                .label("File name")
                .placeholder("Cart.java")
                .hint("Use / to put it in new folders, which are created: model/Cart.java."
                        + " The file opens in the editor.")
                .validator(name -> newPathProblem(workspace.workTree(), childOf(parentPath, name)))
                .action("Create file")
                .ask()
                .filter(name -> !name.isBlank())
                .ifPresent(name -> {
                    String path = childOf(parentPath, name);
                    GitResult result = workspace.createFile(path);
                    statusBar.report(result);
                    if (result.isOk()) {
                        rebuild();
                        onOpenFile.accept(path);
                        operations.refresh(OperationRunner.Refresh.EVERYTHING);
                    }
                });
    }

    private void promptNewFolder(String parentPath) {
        Dialogs.prompt("New folder")
                .icon(Icons.folder())
                .title(parentPath.isEmpty() ? "Create a folder in the repository root"
                        : "Create a folder in `" + parentPath + "`")
                .label("Folder name")
                .placeholder("model")
                .hint("Use / to create several levels at once: model/dto.")
                .validator(name -> newPathProblem(workspace.workTree(), childOf(parentPath, name)))
                .action("Create folder")
                .ask()
                .filter(name -> !name.isBlank())
                .ifPresent(name -> {
                    GitResult result = workspace.createDirectory(childOf(parentPath, name));
                    statusBar.report(result);
                    if (result.isOk()) rebuild();
                });
    }

    /** {@code name}, trimmed, inside {@code parentPath}; the root's is the empty path. */
    private static String childOf(String parentPath, String name) {
        return parentPath.isEmpty() ? name.trim() : parentPath + "/" + name.trim();
    }

    /**
     * What stops {@code relativePath} being created, or null: a name the file system
     * refuses, a path that leaves the repository, or something already there - the same
     * checks {@link WorkspaceFiles} makes when it creates it, asked as the name is typed.
     * Package-visible for the command palette's Create file.
     */
    static String newPathProblem(java.io.File workTree, String relativePath) {
        try {
            java.nio.file.Path target = com.jfx.git.core.WorktreePaths.resolveForWrite(workTree, relativePath);
            if (java.nio.file.Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return relativePath + " already exists.";
            }
            return null;
        } catch (java.nio.file.InvalidPathException e) {
            return "A name cannot contain any of < > : \" | ? *";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /**
     * Confirms, then deletes.
     *
     * <p>Deleting from disk is not a git operation and the reflog cannot bring it back,
     * so the confirmation says what it is about to lose rather than asking generically,
     * and Enter is Cancel.
     */
    private void confirmDelete(WorkspaceFiles.Entry entry) {
        String path = entry.path();
        int slash = path.lastIndexOf('/');
        String where = slash < 0 ? "Repository root" : "In `" + path.substring(0, slash + 1) + "`";
        boolean changed = changedPaths.contains(path);

        if (Dialogs.destructive("Delete from disk")
                .icon(entry.directory() ? Icons.folder() : Icons.trash())
                .title(entry.directory()
                        ? "Delete the folder `" + entry.name() + "` and everything in it?"
                        : "Delete `" + entry.name() + "` from disk?")
                .subtitle(where + (changed ? " · has uncommitted changes" : ""))
                .message("It is deleted outright, not moved to the Recycle Bin or Trash, and Undo"
                        + " cannot bring it back: this is not a git operation. Only what git has"
                        + " committed can be restored.")
                .action("Delete from disk")
                .safeDefault()
                .ask()) {
            GitResult result = workspace.delete(path);
            statusBar.report(result);
            if (result.isOk()) {
                rebuild();
                operations.refresh(OperationRunner.Refresh.EVERYTHING);
            }
        }
    }

    private void applyIgnore(String path, WorkspaceFiles.IgnoreScope scope) {
        GitResult result = workspace.ignore(path, scope);
        statusBar.report(result);
        if (result.isOk()) {
            rebuild();
            operations.refresh(OperationRunner.Refresh.EVERYTHING);
        }
    }

    private void runGit(java.util.function.Supplier<GitResult> work, String description) {
        operations.run(description, OperationRunner.Refresh.EVERYTHING, work);
    }
}
