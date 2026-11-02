package com.jfx.git.ui;

import com.jfx.git.core.GitTaskRunner;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import com.jfx.git.model.CommitInfo;

/**
 * Full-width file history: the commits that touched a file on the left, and that
 * commit's content on the right.
 *
 * <p>Blame is a toggle on this same view rather than a separate screen - it swaps the
 * right-hand pane between the commit's diff and the file annotated line by line.
 */
public class FileHistoryView extends BorderPane {

    /**
     * Commits read per page. Not a cap: when a page comes back full the footer offers the
     * next one. It used to be a silent limit, and the list simply ended at commit 200
     * under a footer claiming "End of History" (G7).
     */
    static final int HISTORY_PAGE = 200;

    private int historyLimit = HISTORY_PAGE;

    /** Shown when the history really has ended. */
    private final Label footer = new Label("End of History");
    /** Shown instead when the last read filled its page, so there may be more. */
    private final Button loadOlder = new Button("Load older commits");

    private final com.jfx.git.core.GitBackend gitService;
    private final StatusBar statusBar;
    /** Runs Revert hunk and the blame read; see {@link OperationRunner}. */
    private final OperationRunner operations;
    private final String path;

    private final ListView<CommitInfo> commitList = new ListView<>();
    private final DiffViewer diffViewer = new DiffViewer();
    private final ToggleButton btnBlame;

    public FileHistoryView(OperationRunner operations,
                           String path,
                           boolean blameOn,
                           Runnable onClose,
                           java.util.function.Consumer<String> onEditFile) {
        this.operations = operations;
        this.gitService = operations.repository();
        this.statusBar = operations.statusBar();
        this.path = path;

        getStyleClass().add("file-history");

        // ---- Header ----
        Label title = new Label("File History: " + path);
        title.getStyleClass().add("file-history-title");

        // The encoding selector is deliberately NOT re-parented here any more. It used to
        // be, because setEmbedded(true) hides the viewer's title bar and this header was
        // then the only one on screen - but the viewer has a status footer now, which is
        // visible embedded or not, and that is where the control lives. One owner, one
        // place, both contexts.

        // A Button, not a Label with a mouse handler: a Label is not focusable, is not
        // reachable by Tab, does not activate on Enter or Space, and announces itself to a
        // screen reader as a piece of text. Same fix as the diff viewer's own close
        // control; this was the second copy of it.
        Button close = Icons.button(Icons.close(), "Close file history", "Close file history");
        close.getStyleClass().add("diff-close");
        close.setOnAction(e -> onClose.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(10, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("file-history-header");
        setTop(header);

        // ---- Commit list ----
        commitList.getStyleClass().add("file-history-list");
        commitList.setCellFactory(lv -> new CommitCell());
        commitList.setPlaceholder(new Label("Loading history…"));
        commitList.getSelectionModel().selectedItemProperty().addListener((obs, old, commit) -> {
            if (commit != null) {
                // Each commit's diff is a new loaded diff of a fixed revision, so what was
                // revealed in the last one does not carry over even though the path does.
                showCommit(commit);
            }
        });

        footer.getStyleClass().add("file-history-footer");
        footer.setMaxWidth(Double.MAX_VALUE);
        footer.setAlignment(Pos.CENTER);
        loadOlder.getStyleClass().add("file-history-footer");
        loadOlder.setMaxWidth(Double.MAX_VALUE);
        loadOlder.setOnAction(e -> {
            historyLimit += HISTORY_PAGE;
            load();
        });
        showFooter(false);

        VBox left = new VBox(commitList, footer, loadOlder);
        VBox.setVgrow(commitList, Priority.ALWAYS);
        left.setMinWidth(0);

        // ---- Right: the viewer, with blame as a toggle ----
        diffViewer.setEmbedded(true);
        // This viewer is a second instance, so it needs its own handler - without one
        // the "Open externally" path here did nothing at all. That path always opens
        // the current working copy, which is why the built-in editor's button here
        // reads "Edit in Working Directory" instead: this viewer never calls
        // setEditable(true), so it always shows history, and Save always writes the
        // edit into the current working tree rather than "editing" the historical
        // object on screen, which is immutable.
        //
        // And the working copy is today's file whichever row is selected. The viewer
        // names a row's file as that commit had it, so a row from before a rename handed
        // over the old name - a file that is not on disk - and the open failed.
        diffViewer.setOnEditFile(onEditFile == null ? null : shown -> onEditFile.accept(this.path));
        // Expanding a hunk's context needs the file as it stood at the selected commit,
        // not as it stands now.
        diffViewer.setFileContentSupplier(this::contentAtSelectedCommit);
        diffViewer.setReaderRepository(gitService);
        diffViewer.setImageSupplier(filePath -> imageSides);
        // A commit's diff offers Revert hunk: that commit's change undone in the working
        // tree, leaving an uncommitted edit - the same thing commercial clients produce.
        diffViewer.setOnHunkAction(this::runHunkAction);
        // Ignoring whitespace changes what git reports, so the diff must be refetched.
        diffViewer.setOnIgnoreWhitespaceChanged(ignore -> refreshRightPane());

        btnBlame = new ToggleButton("Blame");
        btnBlame.getStyleClass().add("diff-segment");
        btnBlame.setTooltip(new Tooltip("Annotate each line with the commit that changed it"));
        btnBlame.setSelected(blameOn);
        btnBlame.setOnAction(e -> refreshRightPane());

        // Registered after btnBlame exists, because it turns it off - switching File/Diff
        // View by hand must load that mode's content for the selected commit, or the pane
        // just goes blank. Blame annotates a whole file line by line, so there is nothing
        // for it to annotate in a diff, and commercial clients drop the control outright in Diff
        // View. Without clearing it here the toggle looked broken: showCommit consults
        // blame before the view mode, so asking for Diff view re-entered blame and
        // syncToggles immediately put the File view button back.
        diffViewer.setOnViewModeChanged(fileView -> {
            if (!fileView) {
                btnBlame.setSelected(false);
            }
            refreshRightPane();
        });

        HBox blameBar = new HBox(btnBlame);
        blameBar.setAlignment(Pos.CENTER_RIGHT);
        blameBar.getStyleClass().add("file-history-blame-bar");

        VBox right = new VBox(blameBar, diffViewer);
        VBox.setVgrow(diffViewer, Priority.ALWAYS);
        right.setMinWidth(0);

        SplitPane split = new SplitPane(left, right);
        split.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        SplitPane.setResizableWithParent(left, false);
        left.setPrefWidth(360);
        setCenter(split);

        // A divider fraction set before layout is ignored, leaving the list at the
        // default half-width; pin it to a pixel width on the first real layout.
        split.widthProperty().addListener(new javafx.beans.value.ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Number> obs,
                                Number oldWidth, Number newWidth) {
                if (newWidth.doubleValue() > 0) {
                    split.setDividerPositions(Math.min(0.5, 360.0 / newWidth.doubleValue()));
                    split.widthProperty().removeListener(this);
                }
            }
        });

        load();
    }

    /** Passes a preference change on to this view's own diff viewer. */
    public void applyPreferences() {
        diffViewer.applyPreferences();
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private void showFooter(boolean more) {
        footer.setVisible(!more);
        footer.setManaged(!more);
        loadOlder.setVisible(more);
        loadOlder.setManaged(more);
    }

    private void load() {
        int limit = historyLimit;
        CommitInfo keep = commitList.getSelectionModel().getSelectedItem();
        GitTaskRunner.query(gitService,
                () -> gitService.getFileHistoryCommits(path, limit),
                commits -> {
                    commitList.getItems().setAll(commits);
                    commitList.setPlaceholder(new Label("No history for this file."));
                    // A full page means the walk stopped at the limit rather than at the
                    // file's first commit, so say so instead of "End of History".
                    showFooter(commits.size() >= limit);
                    int at = keep == null ? -1 : commits.indexOf(keep);
                    if (at >= 0) {
                        commitList.getSelectionModel().select(at);
                    } else if (!commits.isEmpty()) {
                        commitList.getSelectionModel().select(0);
                    }
                },
                statusBar::error);
    }

    /**
     * What the file was called in {@code commit}. The history follows renames, so an
     * older commit can hold it under a previous name, and asking git about today's name
     * there finds nothing.
     */
    static String pathIn(CommitInfo commit, String fallback) {
        String historical = commit == null ? null : commit.getHistoryPath();
        return historical == null || historical.isBlank() ? fallback : historical;
    }

    /**
     * Loads the pane that matches the current toggles: blame, whole file, or diff.
     */
    /**
     * The selected commit's copy of {@link #path}, read ahead on the worker.
     *
     * <p>Null until it arrives, and null for a path the viewer asks about that is not the
     * one this view is showing - both of which the viewer reads as "no whole-file source",
     * which is true at that moment.
     */
    private byte[] contentAtSelection;

    /** Both sides of the selected commit, for an image path; see above. */
    private ImageDiffView.Blobs imageSides;

    /**
     * The keys this view's per-selection reads share - what the right pane shows, and what
     * is read ahead for it - so stepping to the next commit drops the last one's reads that
     * have not started (GitTaskRunner.queryLatest). Arrowing down the list used to run a
     * diff and a blob read for every commit passed on the way, and show the one asked for
     * only after them.
     */
    private final Object paneLoads = new Object();
    private final Object readAhead = new Object();

    private void showCommit(CommitInfo commit) {
        prefetchWhatTheViewerWillAskFor(commit);
        if (btnBlame.isSelected()) {
            showBlame(commit);
        } else if (diffViewer.isFileView()) {
            showFileContent(commit);
        } else {
            showDiff(commit);
        }
    }

    /** Re-renders the right pane for the current selection after a toggle changes. */
    private void refreshRightPane() {
        CommitInfo selected = commitList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            showCommit(selected);
        }
    }

    /**
     * What the file was called just before {@code commit}, when {@code commit} is the one
     * that renamed it; {@code null} otherwise.
     *
     * <p>Read from the next older row: the list follows the file across renames, so a row
     * below that holds a different name is the file before the move. Without the old name
     * the diff is narrowed to the new one alone, which cannot see where the file came from
     * - the rename read as the whole file appearing, and that one all-lines hunk carried a
     * Revert hunk that would empty the file. A rename on the oldest row loaded has no row
     * below it until older commits are loaded, and keeps the narrowed diff until then.
     */
    private String renamedFrom(CommitInfo commit) {
        var rows = commitList.getItems();
        int at = rows.indexOf(commit);
        if (at < 0 || at + 1 >= rows.size()) {
            return null;
        }
        String before = pathIn(rows.get(at + 1), path);
        return before.equals(pathIn(commit, path)) ? null : before;
    }

    private void showDiff(CommitInfo commit) {
        diffViewer.setComparedSides(DiffViewer.DiffSides.between(
                RightPanelManager.parentLabel(commit), commit.getShortHash()));
        boolean ignoreWhitespace = diffViewer.isIgnoreWhitespace();
        String here = pathIn(commit, path);
        String oldPath = renamedFrom(commit);
        GitTaskRunner.queryLatest(gitService, paneLoads,
                () -> gitService.getDiffBytesForFileInCommit(commit.getHash(), here, oldPath, ignoreWhitespace),
                diff -> {
                    diffViewer.setRevisionReader(revisionOf(commit));
                    diffViewer.setFileDiffBytes(DiffKind.COMMIT, here, diff);
                },
                statusBar::error);
    }

    /**
     * Reads any path as it was at {@code commit}: what a Markdown preview of this file's
     * history draws its images from. The viewer's content supplier answers only for the
     * file itself, so without this every image there was its alt text.
     */
    private java.util.function.Function<String, byte[]> revisionOf(CommitInfo commit) {
        String hash = commit.getHash();
        return other -> gitService.getFileBytesAtCommit(hash, other);
    }

    /**
     * Runs "Revert hunk", the one action a commit's diff offers: undoes that hunk's change
     * in the working tree. History itself is untouched; the result is an uncommitted edit
     * the user can review and commit.
     */
    private void runHunkAction(HunkAction action) {
        if (action.type() != HunkAction.Type.REVERT) {
            return;
        }
        // Into the file as it is called now: the hunk may come from a commit that held it
        // under an older name, but the working copy is under today's - so that is the name
        // the confirmation gives and the edit goes into. It used to give the commit's, a
        // file that is not there.
        CommitInfo selected = commitList.getSelectionModel().getSelectedItem();
        String from = selected == null ? "From a past commit" : "From `" + selected.getShortHash() + "`";
        String renamed = action.path().equals(path) ? "" : " · called `" + action.path() + "` then";
        // Into the working tree, which may put a WIP row on the graph.
        HunkRevert.confirmAndRun(operations, action, path, from + renamed, null,
                OperationRunner.Refresh.EVERYTHING);
    }

    /**
     * The file as the selected commit itself left it.
     *
     * <p>Called on the FX thread from the viewer's expander, so it reads a blob straight
     * out of the object database rather than going through the task runner - the cost is
     * one object read and the caller already has the row list waiting on it.
     *
     * <p>Deliberately the commit's own tree, not its parent's: {@code diffViewer} is
     * showing {@code git show <selected commit>}, whose hunk headers' new-side line
     * numbers - what {@link DiffRows#addContextAbove} and {@link DiffRows#addTrailingContext}
     * index revealed context by - describe the commit's own content, not the content
     * before it. Handing back the parent's blob here silently fed those two methods the
     * wrong side, off by each hunk's own add/delete drift, for every file whose history
     * has more than a trivial one-line-for-one-line edit.
     */
    private byte[] contentAtSelectedCommit(String filePath) {
        CommitInfo selected = commitList.getSelectionModel().getSelectedItem();
        return pathIn(selected, path).equals(filePath) ? contentAtSelection : null;
    }

    /**
     * Fetches what the viewer's own seams will ask for synchronously, before they ask.
     *
     * <h2>Why this is pre-fetched rather than fetched on demand</h2>
     * {@code DiffViewer} takes its whole-file content and its image blobs as
     * {@code Function}s and calls them on the FX thread - from the context expander, the
     * whole-file toggle, the image renderer. That is fine for the main panel, whose
     * supplier reads a working-tree file, and not fine here, where the same seam means
     * {@code git show} against a historical commit: a subprocess on the thread that draws,
     * on the CLI engine.
     *
     * <p>Converting the seam to a callback would mean restructuring three rendering paths
     * inside a 2,400-line viewer whose alignment arithmetic is the most delicate code in
     * the project. Reading ahead costs one blob per selection in a view that exists to
     * show one file's history, and the read is on the worker with everything else.
     *
     * <p>Not ready yet means the supplier answers {@code null}, which the viewer already
     * treats as "no source for the whole file" - the expander stays collapsed rather than
     * blocking. It becomes available a moment later, which is what it should have done
     * from the start.
     */
    private void prefetchWhatTheViewerWillAskFor(CommitInfo commit) {
        contentAtSelection = null;
        imageSides = null;
        String here = pathIn(commit, path);
        // A read that had already started when the selection moved on still lands; it is
        // kept only if its commit is the one selected, or the viewer would be handed the
        // previous commit's copy of a path both share.
        if (ImageDiffView.isImage(here)) {
            GitTaskRunner.queryLatest(gitService, readAhead,
                    () -> gitService.getCommitSides(commit, here),
                    sides -> {
                        if (isSelected(commit)) imageSides = new ImageDiffView.Blobs(sides[0], sides[1]);
                    },
                    statusBar::error);
        } else {
            GitTaskRunner.queryLatest(gitService, readAhead,
                    () -> gitService.getFileBytesAtCommit(commit.getHash(), here),
                    bytes -> {
                        if (isSelected(commit)) contentAtSelection = bytes;
                    },
                    statusBar::error);
        }
    }

    /** By hash: loading older history rebuilds the list's rows, the same commits among them. */
    private boolean isSelected(CommitInfo commit) {
        CommitInfo selected = commitList.getSelectionModel().getSelectedItem();
        return selected != null && selected.getHash().equals(commit.getHash());
    }

    private void showFileContent(CommitInfo commit) {
        diffViewer.setComparedSides(DiffViewer.DiffSides.between(
                RightPanelManager.parentLabel(commit), commit.getShortHash()));
        String here = pathIn(commit, path);
        GitTaskRunner.queryLatest(gitService, paneLoads,
                () -> gitService.getFileBytesAtCommit(commit, here),
                content -> {
                    diffViewer.setRevisionReader(revisionOf(commit));
                    diffViewer.showFileBytes(here, content);
                },
                statusBar::error);
    }

    /**
     * Blame plus the diff of the commit it was taken at, fetched in one background pass.
     *
     * <p>Only the blame is rendered; the diff is there so the viewer can mark which parts
     * of the file this commit actually touched on its scroll map. Fetched together rather
     * than in a second query so the two can never arrive out of step with each other -
     * two queries racing would let a fast blame for one commit be marked with a slow diff
     * from the commit clicked before it.
     */
    private record BlameAndDiff(java.util.List<com.jfx.git.core.BlameLine> blame, byte[] diff) {
    }

    private void showBlame(CommitInfo commit) {
        String here = pathIn(commit, path);
        String commitId = commit.getHash();
        boolean ignoreWhitespace = diffViewer.isIgnoreWhitespace();
        // The same diff as Diff view, rename included, or a rename marks every line.
        String oldPath = renamedFrom(commit);
        operations.read("Computing blame for " + here,
                () -> new BlameAndDiff(
                        gitService.getBlameLines(here, commitId),
                        gitService.getDiffBytesForFileInCommit(commitId, here, oldPath, ignoreWhitespace)),
                result -> {
                    // Replace the "Computing blame…" text rather than leaving it up.
                    statusBar.info("Blame ready for " + here);
                    diffViewer.showBlame(here, result.blame(), result.diff());
                });
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** Message, relative age and author, plus the abbreviated hash on the right. */
    private static class CommitCell extends ListCell<CommitInfo> {

        private static final double HASH_COLUMN = 78;

        private final Label message = new Label();
        private final Label meta = new Label();
        private final Label hash = new Label();
        private final HBox row;

        CommitCell() {
            message.getStyleClass().add("file-history-message");
            meta.getStyleClass().add("file-history-meta");
            hash.getStyleClass().add("file-history-hash");
            hash.setMinWidth(HASH_COLUMN);

            VBox text = new VBox(2, message, meta);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);

            row = new HBox(10, text, hash);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMinWidth(0);

            // Cap the text against the cell so long subjects ellipsize instead of
            // overflowing and pushing the hash out of view.
            message.maxWidthProperty().bind(widthProperty().subtract(HASH_COLUMN + 34));
            meta.maxWidthProperty().bind(widthProperty().subtract(HASH_COLUMN + 34));
        }

        @Override
        protected void updateItem(CommitInfo commit, boolean empty) {
            super.updateItem(commit, empty);
            if (empty || commit == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            message.setText(commit.getSubject());
            meta.setText(DateDisplay.of(commit.getAuthorDate(), DateDisplay.Style.SPOKEN)
                    + " by " + commit.getAuthorName());
            String h = commit.getHash();
            hash.setText(h.length() >= 6 ? h.substring(0, 6) : h);

            setText(null);
            setGraphic(row);
        }
    }
}
