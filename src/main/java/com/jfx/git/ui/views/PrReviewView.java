package com.jfx.git.ui.views;

import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.TextEncoding;
import com.jfx.git.model.ReviewComment;
import com.jfx.git.ui.DiffKind;
import com.jfx.git.ui.DiffParser;
import com.jfx.git.ui.DiffRows;
import com.jfx.git.ui.LoadedDiff;
import com.jfx.git.ui.DiffScrollMap;
import com.jfx.git.ui.DiffTextArea;
import com.jfx.git.ui.RepoView;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.dialogs.ReviewCommentComposeDialog;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.jfx.git.ui.DiffScrollPane;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Views a pull/merge request's diff, file by file, with existing review comments
 * shown against the lines they are anchored to and a way to add a new one - Wave 6
 * item #18, "in-app PR review/suggest-changes/commenting UI".
 *
 * <p>Provider-agnostic on purpose: the diff itself is computed and rendered entirely
 * locally (the caller already fetched the PR/MR's base and head commits and diffed
 * between them with the ordinary {@code GitBackend} methods every other diff view in
 * this app uses - a provider's REST API is never asked for diff content, only for
 * comment metadata), and {@link Backend} is the one seam a caller adapts to whichever
 * provider it is talking to. This mirrors {@code IntegrationDialog}'s {@code
 * ProviderRow} - one shared shape, a small adapter per provider - rather than
 * tripling the whole diff-rendering machinery the way the PR/MR list views
 * themselves deliberately do for their own, much smaller and more divergent bodies.
 *
 * <p>Deliberately not a full syntax-highlighted, side-by-side {@code DiffViewer}: that
 * class is tightly coupled to local-file editing/staging/blame, none of which applies
 * to reviewing someone else's not-yet-merged diff. This uses {@link DiffTextArea}
 * (read-only by construction) directly with a plain unified layout and a lightweight
 * gutter, the same {@code setParagraphGraphicFactory} technique {@code DiffViewer}
 * itself uses for its own gutter - reusing the pure, JavaFX-free {@link DiffParser}/
 * {@link DiffRows} data layer without the rest of that class's scope.
 */
public class PrReviewView implements RepoView {

    /** One file's raw {@code diff} output between the PR/MR's base and head commits. */
    public record ReviewFile(String path, byte[] diffBytes) {
    }

    /** What a provider-specific operation reports back, normalized away from that provider's own result type. */
    public record Outcome<T>(boolean ok, T value, String message) {
        public static <T> Outcome<T> ok(T value) {
            return new Outcome<>(true, value, null);
        }

        public static <T> Outcome<T> failed(String message) {
            return new Outcome<>(false, null, message);
        }
    }

    /**
     * The one seam a caller adapts to its own provider - fetching and posting comments
     * are the only two operations that actually differ by provider; everything else in
     * this view (the diff, the file list, the compose form) is identical regardless
     * of which one is talking. Both methods do their own work asynchronously and are
     * expected to deliver {@code onDone} back on the FX thread, the same contract
     * {@code GitTaskRunner.query}/{@code .run} already guarantee - this view never
     * touches a background thread itself.
     */
    public interface Backend {
        void fetchComments(Consumer<Outcome<List<ReviewComment>>> onDone);

        void postComment(String path, int line, ReviewComment.Side side, String body,
                          Consumer<Outcome<Void>> onDone);

        /** False for Bitbucket, which has no suggested-change markdown syntax to render one. */
        boolean supportsSuggestions();
    }

    private final String title;
    /** The screen - the changed files beside the diff - built once. */
    private final VBox content;
    private final Backend backend;
    private final StatusBar statusBar;
    private final Map<String, List<DiffParser.Hunk>> hunksByPath = new LinkedHashMap<>();
    private final ListView<String> fileList = new ListView<>();
    private final DiffTextArea area = new DiffTextArea();
    private final DiffScrollPane diffScroll = new DiffScrollPane(area);
    private final DiffScrollMap scrollMap = new DiffScrollMap();
    private final ToggleButton wholeFileToggle =
            new ToggleButton("Whole file", com.jfx.git.ui.Icons.expand());
    private final Label placeholder = new Label("Select a file to view its diff.");

    private List<DiffRows.Row> currentRows = List.of();
    private String currentPath;
    private List<ReviewComment> allComments = new ArrayList<>();
    /** Reads a path's whole current/head-side content; {@code null} until the caller supplies one. */
    private Function<String, String> fileContentSupplier;
    /** The repository {@link #fileContentSupplier} reads. */
    private com.jfx.git.core.GitBackend fileContentRepository;
    /** Whole-file content already read, by path; a review does not change under it. */
    private final Map<String, String> fileContent = new HashMap<>();
    /** Bumped by every {@link #showFile}, so a file read for an earlier pick is dropped. */
    private long fileRequest;
    /** Set once the view has left the tab; answers arriving later have nowhere to go. */
    private boolean closed;

    public PrReviewView(String title, List<ReviewFile> files, Backend backend, StatusBar statusBar) {
        this.backend = backend;
        this.statusBar = statusBar;
        this.title = "Review: " + title;

        for (ReviewFile file : files) {
            // Read as the viewer reads a diff - the encoding worked out from the bytes, not
            // assumed to be UTF-8 - so a Latin-1 or UTF-16 file reviews as legibly here.
            // A review offers no hunk actions; only the hunks are wanted.
            hunksByPath.put(file.path(), LoadedDiff.read(DiffKind.COMPARISON, file.path(),
                    file.diffBytes(), TextEncoding.GUESS, null, false).hunks());
        }
        fileList.getItems().setAll(hunksByPath.keySet());
        fileList.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, path) -> { if (path != null) showFile(path); });
        fileList.setPrefWidth(240);

        area.setParagraphGraphicFactory(this::buildGutter);
        VBox.setVgrow(diffScroll, Priority.ALWAYS);
        HBox.setHgrow(diffScroll, Priority.ALWAYS);

        wholeFileToggle.getStyleClass().add("diff-tool-toggle");
        wholeFileToggle.setTooltip(new Tooltip("Show the entire file, with changes highlighted in place"));
        wholeFileToggle.setDisable(true);
        wholeFileToggle.setOnAction(e -> { if (currentPath != null) showFile(currentPath); });
        HBox diffToolbar = new HBox(wholeFileToggle);

        HBox contentRow = new HBox(diffScroll, scrollMap);
        VBox.setVgrow(contentRow, Priority.ALWAYS);
        VBox diffSide = new VBox(4, diffToolbar, contentRow);
        VBox.setVgrow(diffSide, Priority.ALWAYS);

        SplitPane split = new SplitPane(fileList, diffSide);
        split.setDividerPositions(0.28);
        VBox.setVgrow(split, Priority.ALWAYS);

        content = new VBox(8, placeholder, split);
        content.setPadding(new Insets(10));
        // The diff surface's own styles - the scroll map is the surface's scrollbar, and
        // unstyled it is an invisible one. On the view rather than the scene, as DiffViewer
        // carries them: the main window's scene does not load them.
        try {
            content.getStylesheets().add(
                    getClass().getResource("/diff-styles.css").toExternalForm());
        } catch (Exception ignored) {
            // Styling is optional; the view is still usable without it.
        }

        if (files.isEmpty()) {
            placeholder.setText("This pull/merge request touched no files this app could diff.");
        } else {
            placeholder.setVisible(false);
            placeholder.setManaged(false);
            fileList.getSelectionModel().selectFirst();
        }
        loadComments();
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public VBox content() {
        return content;
    }

    /**
     * Stops listening: a whole file or the comments arriving after Back have no screen to
     * land on. A comment already posted still reports in the status bar - it reached the
     * server either way, and the user should hear so.
     */
    @Override
    public void closed() {
        closed = true;
        fileRequest++;
    }

    /**
     * Supplies a path's whole current/head-side content, enabling the "Whole file"
     * toggle - without it there is nothing for {@link DiffRows#wholeFileUnified} to
     * splice unchanged context in from, the same constraint {@code DiffViewer}'s own
     * expander and whole-file toggle share.
     *
     * @param repository the repository {@code supplier} reads, whose lane it runs on
     */
    public void setFileContentSupplier(com.jfx.git.core.GitBackend repository, Function<String, String> supplier) {
        this.fileContentRepository = repository;
        this.fileContentSupplier = supplier;
        fileContent.clear();
        wholeFileToggle.setDisable(supplier == null);
    }

    /**
     * Shows a file's diff, or the whole file with its changes in place.
     *
     * <p>The whole file is read through the supplier, which is git - a {@code git show}
     * subprocess on the CLI engine. That used to run here, on the FX thread, freezing the
     * review on every pick with the toggle on. It now runs on the repository's lane: the
     * diff shows at once, the whole file replaces it when it arrives, and an answer for a
     * file the reviewer has already moved on from is dropped.
     */
    private void showFile(String path) {
        currentPath = path;
        long request = ++fileRequest;
        List<DiffParser.Hunk> hunks = hunksByPath.get(path);
        if (wholeFileToggle.isSelected() && fileContentSupplier != null) {
            String known = fileContent.get(path);
            if (known != null) {
                render(DiffRows.wholeFileUnified(hunks, List.of(known.split("\n", -1))));
                return;
            }
            Function<String, String> supplier = fileContentSupplier;
            GitTaskRunner.query(fileContentRepository, () -> {
                        String content = supplier.apply(path);
                        return content == null ? "" : content;
                    },
                    content -> {
                        fileContent.put(path, content);
                        if (request == fileRequest) showFile(path);
                    },
                    error -> {
                        if (request == fileRequest) statusBar.error("Could not read " + path + ": " + error);
                    });
        }
        render(DiffRows.unified(hunks));
    }

    private void render(List<DiffRows.Row> rows) {
        currentRows = rows;
        area.setLines(currentRows.stream().map(DiffRows.Row::text).toList(), false);
        scrollMap.attachTo(area);
        scrollMap.setRows(currentRows);
    }

    /**
     * One CI-status-style refresh after comments load: simplest correct way to get the
     * newly-fetched comments reflected in every row's gutter, at the cost of rebuilding
     * the currently-shown file's rows - a PR/MR's own per-file diff is small enough
     * this session's whole diff-rendering approach elsewhere never needed to optimise
     * for a rebuild's cost either.
     */
    private void loadComments() {
        backend.fetchComments(outcome -> {
            if (closed) return;
            if (outcome.ok()) {
                allComments = outcome.value();
                if (currentPath != null) {
                    showFile(currentPath);
                }
            }
        });
    }

    private Node buildGutter(int index) {
        HBox box = new HBox(4);
        box.getStyleClass().add("diff-gutter");
        box.setAlignment(Pos.CENTER_RIGHT);
        if (currentRows == null || index < 0 || index >= currentRows.size()) {
            return box;
        }
        DiffRows.Row row = currentRows.get(index);
        if (row.kind() != DiffRows.Kind.CODE) {
            return box;
        }

        Label oldNum = new Label(row.oldLine() == null ? "" : String.valueOf(row.oldLine()));
        Label newNum = new Label(row.newLine() == null ? "" : String.valueOf(row.newLine()));
        oldNum.getStyleClass().add("diff-gutter-number");
        newNum.getStyleClass().add("diff-gutter-number");
        oldNum.setPrefWidth(36);
        newNum.setPrefWidth(36);
        box.getChildren().addAll(oldNum, newNum);

        List<ReviewComment> here = commentsOn(row);
        if (!here.isEmpty()) {
            Label marker = new Label();
            marker.setGraphic(com.jfx.git.ui.Icons.comment());
            marker.getStyleClass().add("diff-comment-marker");
            String summary = here.stream()
                    .map(c -> c.author() + ": " + c.body())
                    .reduce((a, b) -> a + "\n\n" + b)
                    .orElse("");
            Tooltip.install(marker, new Tooltip(summary));
            box.getChildren().add(marker);
        }

        ContextMenu menu = new ContextMenu();
        MenuItem addComment = new MenuItem("Add review comment…");
        addComment.setOnAction(e -> composeCommentFor(row));
        menu.getItems().add(addComment);
        box.setOnContextMenuRequested(e -> menu.show(box, e.getScreenX(), e.getScreenY()));

        return box;
    }

    /**
     * Which side of the diff a code row's comment belongs to, and which line number
     * within that side - a pure addition has no old line, a pure deletion has no new
     * line, and a context line (present on both) anchors to the new side, matching
     * every one of the three providers' own convention for an unchanged line.
     */
    private static ReviewComment.Side sideOf(DiffRows.Row row) {
        return row.newLine() != null ? ReviewComment.Side.NEW : ReviewComment.Side.OLD;
    }

    private static int lineOf(DiffRows.Row row) {
        ReviewComment.Side side = sideOf(row);
        return side == ReviewComment.Side.NEW ? row.newLine() : row.oldLine();
    }

    private List<ReviewComment> commentsOn(DiffRows.Row row) {
        int line = lineOf(row);
        ReviewComment.Side side = sideOf(row);
        return allComments.stream()
                .filter(c -> currentPath.equals(c.path()) && side == c.side()
                        && c.line() != null && c.line() == line)
                .toList();
    }

    private void composeCommentFor(DiffRows.Row row) {
        new ReviewCommentComposeDialog(backend.supportsSuggestions())
                .showAndWait()
                .ifPresent(body -> submitComment(currentPath, lineOf(row), sideOf(row), body));
    }

    private void submitComment(String path, int line, ReviewComment.Side side, String body) {
        backend.postComment(path, line, side, body, outcome -> {
            if (outcome.ok()) {
                statusBar.info("Comment posted");
                if (!closed) loadComments();
            } else {
                statusBar.error("Could not post the comment: " + outcome.message());
            }
        });
    }

    // ------------------------------------------------------------------
    // Test seams - mirror the *ForTest pattern GitHubPullRequestsView etc. establish
    // ------------------------------------------------------------------

    List<String> filesForTest() {
        return List.copyOf(fileList.getItems());
    }

    void selectFileForTest(String path) {
        fileList.getSelectionModel().select(path);
    }

    List<String> rowTextForTest() {
        return currentRows.stream().map(DiffRows.Row::text).toList();
    }

    /** Flips the Whole file toggle and re-renders, mirroring what {@code fire()} does. */
    void setWholeFileForTest(boolean on) {
        wholeFileToggle.setSelected(on);
        if (currentPath != null) {
            showFile(currentPath);
        }
    }

    boolean isWholeFileDisabledForTest() {
        return wholeFileToggle.isDisabled();
    }

    List<ReviewComment> commentsForTest() {
        return List.copyOf(allComments);
    }

    /** Bypasses the real context menu and compose dialog - selects a row by index and posts directly. */
    void addCommentForTest(int rowIndex, String body) {
        submitComment(currentPath, lineOf(currentRows.get(rowIndex)), sideOf(currentRows.get(rowIndex)), body);
    }

    boolean hasCommentMarkerForTest(int rowIndex) {
        return !commentsOn(currentRows.get(rowIndex)).isEmpty();
    }
}
