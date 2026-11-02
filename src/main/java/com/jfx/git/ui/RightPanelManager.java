package com.jfx.git.ui;

import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.RepositoryCommands;
import com.jfx.git.ui.dialogs.MergeToolDialog;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.SignatureStatus;

import java.util.List;
import java.util.Set;
import com.jfx.git.model.BisectStatus;
import com.jfx.git.model.StagingState;
import com.jfx.git.model.StashOptions;

/**
 * Drives the right-hand panel: either the staging area (when the WIP row is
 * selected) or the details of a selected commit.
 */
public class RightPanelManager {

    private final com.jfx.git.core.GitBackend gitService;
    /** Ignore rules; created lazily because the work tree exists only once a repo opens. */
    private com.jfx.git.core.WorkspaceFiles workspaceFiles;
    /** The detached-HEAD warning, built once and toggled; see {@link #updateDetachedHeadBanner()}. */
    private VBox detachedBanner;
    /** Guards the banner query against a checkout racing a refresh. */
    private long bannerRequestId;
    /** The bisect status banner, rebuilt each time since its buttons depend on session state. */
    private VBox bisectBanner;
    /** Guards the bisect banner query the same way {@link #bannerRequestId} guards the other one. */
    private long bisectBannerRequestId;
    /** The stopped-operation banner (rebase, merge, cherry-pick...), rebuilt per state. */
    private VBox operationBanner;
    /** Guards the operation banner query the same way. */
    private long operationBannerRequestId;
    /**
     * The paths that were conflicted at some point in the operation now stopped, so the
     * Staged list can tag the ones resolved since. Kept for one operation - one merge, one
     * rebase step - and started again when it ends or moves on; see {@link #operationKey}.
     */
    private final Set<String> conflictedThisOperation = new java.util.HashSet<>();
    /** Which operation {@link #conflictedThisOperation} belongs to: its kind and where it stopped. */
    private String operationKey = "";
    /** Told of every accepted operation-state answer; the graph's banner listens. */
    private java.util.function.Consumer<OperationSummary> onOperationState = summary -> { };
    /**
     * The last headline resolved on the worker, and what it was resolved from; reused while
     * the operation is unchanged, so a staging refresh does not list every branch again.
     */
    private volatile HeadlineCache headlineCache;
    /** The "Resolve next" notification on show, so a newer offer replaces it. */
    private javafx.scene.Node nextConflictOffer;
    /** Guards the async signature query against a later commit's answer landing after this row moved on. */
    private long signatureBadgeRequestId;
    /** Guards the async commit-message query the same way, so a late body never lands under another commit. */
    private long commitMessageRequestId;
    /**
     * The keys the commit view's signature and message lookups share, so selecting the next
     * commit drops the last one's that have not started (GitTaskRunner.queryLatest). Both
     * used to run for every commit passed on the way: holding the arrow key through 300
     * commits queued 370 lookups - a git subprocess each for the signatures - and the panel
     * and "Load older commits" waited behind them. The request ids above still guard
     * against one that had started.
     */
    private final Object signatureLookups = new Object();
    private final Object messageLookups = new Object();
    /** The LFS-cannot-smudge warning, built once and toggled; see {@link #updateLfsBanner()}. */
    private VBox lfsBanner;
    /** Guards the LFS banner query the same way {@link #bannerRequestId} guards the detached-HEAD one. */
    private long lfsBannerRequestId;
    private final DetailPanel panel;
    private final DiffViewer diffViewer;
    private final StatusBar statusBar;
    /** Runs every operation the panel starts; see {@link OperationRunner}. */
    private final OperationRunner operations;

    /**
     * The commit box's message, options and decisions, kept across rebuilds so typing a
     * message survives staging a file; the controls built for it are bound to it.
     */
    private final CommitComposer composer;

    /**
     * The half-written stash name, kept for the same reason as the commit message.
     *
     * <p>Staging or unstaging anything rebuilds this whole panel, and a composer that
     * loses what was typed into it whenever the file list moves is a composer nobody
     * finishes a sentence in.
     */
    private String pendingStashMessage = "";

    /** The stash composer's three options, kept across rebuilds like its name. */
    private boolean stashIncludeUntracked = true;
    private boolean stashKeepIndex;
    private boolean stashOnlySelected;

    /**
     * Which composer the footer is showing.
     *
     * <p>On the manager rather than the toggle, so it survives the rebuild that follows
     * every stage, unstage and commit - the same reason {@link #showingWorkspaceTree}
     * lives here.
     */
    private boolean showingStashComposer;

    /**
     * Bumped on every {@link #showStagingArea()} call, so a slower, older query that
     * lands after a newer one has already rendered can recognise itself as stale.
     *
     * <p>{@code showStagingArea()} can be requested twice in close succession - staging a
     * file refreshes the panel, and if that also changes the WIP row's selection state
     * (cleared then reselected, e.g.), the resulting listener fires a second, independent
     * request. Each runs its own background git-status read and its own later rebuild.
     * Nothing about the {@link GitTaskRunner} ties the two together, so whichever
     * happens to finish last wins, even if it is the older of the two - discarding
     * whatever the newer, already-visible panel was showing, including any commit
     * message the user had already started typing into it.
     */
    private long stagingRequestId = 0;

    /**
     * Which diff request the viewer is currently showing the answer to.
     *
     * <p>Diffs are loaded off the FX thread and applied whenever they come back, so
     * clicking down a file list faster than the diffs load used to leave whichever
     * request happened to finish last on screen - under the name of the file actually
     * selected, with Stage hunk and Discard hunk enabled against it. The same guard the
     * staging list already uses: a result is applied only if nothing newer has been
     * asked for since.
     */
    private long diffRequestId = 0;

    /**
     * The key every diff load of this panel shares, so a newer one drops an older one
     * that has not started (GitTaskRunner.queryLatest). Arrowing through twenty files used
     * to queue twenty diff reads and show the last only after the nineteen nobody was
     * looking at any more; the request id below still guards against one that had started.
     */
    private final Object diffLoads = new Object();

    /** What the last staging query returned; the panel is built entirely from it. */
    private StagingState stagingState;

    /**
     * {@link #stagingState}'s conflicting paths, as the set the rows and the selection
     * listeners look up. A field rather than a capture, so a refresh that updates the panel
     * in place reaches every reader at once.
     */
    private Set<String> stagingConflicts = Set.of();

    /** The branch HEAD is on, from the page load that cached it - never asked for here. */
    private final java.util.function.Supplier<String> currentBranch;

    /**
     * The live commit box's controls, when the staging area is on screen; null otherwise.
     * Held so a keyboard shortcut can put the caret in it.
     */
    private ComposerControls composerControls;
    /** The commit button on screen, so a Commit setting can relabel it; null with the composer. */
    private Button liveCommitButton;

    /**
     * Bumped whenever a setting on the Commit page changes, so every commit box on screen
     * follows it at once: the button gains or loses "and push", an empty summary gains or
     * loses its issue key. A counter rather than an event, so a listener is all a panel
     * needs, and a weak one, so a closed tab's panel is not kept alive by it.
     */
    private static final javafx.beans.property.IntegerProperty COMMIT_SETTINGS =
            new javafx.beans.property.SimpleIntegerProperty();

    /** Held by the panel so the weak registration lives exactly as long as it does. */
    private final javafx.beans.value.ChangeListener<Number> onCommitSettings =
            (o, was, now) -> applyCommitSettings();

    /** Tells every commit box that a Commit setting changed. Called by the Settings tab's Commit page. */
    public static void commitSettingsChanged() {
        if (!javafx.application.Platform.isFxApplicationThread()) {
            javafx.application.Platform.runLater(RightPanelManager::commitSettingsChanged);
            return;
        }
        COMMIT_SETTINGS.set(COMMIT_SETTINGS.get() + 1);
    }

    /** Brings the composer on screen in line with the Commit settings. */
    private void applyCommitSettings() {
        if (liveCommitButton != null && liveStaging != null) {
            updateCommitButton(liveCommitButton, liveStaging.stagedAll.size());
        }
        composer.settingsChanged();
    }

    /**
     * The staging panel's live lists and filter, while it is on screen.
     *
     * <p>Read by the next {@link #buildStagingUI} before it replaces them, so a rebuild -
     * after staging a hunk, a save, a return to the window - puts the user back where
     * they were instead of on an unfiltered, unselected list with the open file closed.
     * Null whenever the panel shows something else, so coming back to the working tree
     * from a commit starts fresh.
     */
    private ListView<String> liveUnstagedList;
    private ListView<String> liveStagedList;
    /** The conflicted files, listed apart from the other unstaged changes. */
    private ListView<String> liveConflictedList;
    private javafx.scene.control.TextField liveStagingFilter;

    /**
     * True while a rebuild re-selects the row that was selected before it. The list
     * listeners then leave an editing session alone instead of reloading the file under it.
     */
    private boolean restoringStagingSelection;

    /**
     * What a staging rebuild carries over from the panel it replaces. The commit box's
     * options are the composer's; see {@link CommitComposer} for how long each lasts.
     */
    private record StagingSnapshot(String filter, String selectedPath, boolean selectedUnstaged) {
        static final StagingSnapshot EMPTY = new StagingSnapshot("", null, false);
    }

    /**
     * Whether the panel is showing the whole repository rather than only what changed.
     *
     * <p>Kept on the manager rather than the toggle so the choice survives the panel
     * being rebuilt after every stage, unstage and commit.
     */
    private boolean showingWorkspaceTree;
    private WorkspaceTreeView workspaceTree;

    /**
     * Whether a commit's changed files are shown as a folder tree rather than as paths.
     *
     * <p>Kept here rather than on the toggle, so walking the graph does not silently
     * reset the choice on every commit the user clicks.
     */
    private boolean showingFileTree;

    /**
     * Whether the working tree's own lists are shown as folders rather than paths.
     *
     * <p>Separate from {@link #showingFileTree}, which is the commit view's choice. They
     * answer different questions - "how do I read this commit" and "how do I work through
     * what I have changed" - and sharing one flag would make picking one silently change
     * the other.
     */
    private boolean showingStagingTree;

    /** Opens the file-history view; receives the path and whether to start on blame. */
    private java.util.function.BiConsumer<String, Boolean> onShowFileHistory;

    /**
     * Selects a commit in the graph by hash.
     *
     * <p>Set by the tab that owns the list. Without it the parent links in the details
     * pane would have nowhere to go, which is the state the panel was in before: it could
     * name a commit's parents but not take you to one.
     */
    private java.util.function.Consumer<String> onSelectCommit;

    /** Last diff shown, so it can be recomputed when the whitespace option changes. */
    private String lastDiffPath;
    /** What the last diff compares, so a replay offers the same hunk actions. */
    private DiffKind lastDiffKind = DiffKind.COMPARISON;
    private java.util.function.Supplier<byte[]> lastDiffSupplier;
    /** Reads the image pair for the last diff on the same worker request as its patch. */
    private java.util.function.Supplier<ImageDiffView.Blobs> lastImageSourceSupplier;
    /**
     * The whole-file content behind the last diff, when there was one.
     *
     * <p>Remembered alongside the diff because a reload that replays only the diff loses
     * it: File view on a commit, a stash or a comparison then falls back to the working
     * tree while still labelled as that revision. Only the ignore-whitespace toggle hit
     * that once; a reload on every return to the window made it routine.
     */
    private java.util.function.Supplier<byte[]> lastWholeFileSupplier;
    /** How the last load reads other paths of its revision - for a preview's images; null for the working tree. */
    private java.util.function.Function<String, byte[]> lastRevisionReader;
    /**
     * Whether {@link #lastWholeFileSupplier} is also what File view shows, or only the
     * diff's new side. A staged diff is the second kind: its context comes from the
     * index, but the file a user views and edits from it is the working copy.
     */
    private boolean lastWholeFileIsView = true;

    /**
     * Undoes whichever list selection is currently driving the diff viewer.
     *
     * <p>Set at the point a selection listener opens a file, so that closing the
     * viewer can clear it. Without this, closing the viewer and re-clicking the same,
     * still-selected row does nothing: the row's {@code selectedItemProperty} does not
     * change, so the listener that opens it never fires again.
     */
    private Runnable clearActiveSelection = () -> {};

    /**
     * @param currentBranch the branch HEAD is on, cached by whoever loaded the page - see
     *                      {@link #fillRefChips}. Never asks git itself: this panel runs on
     *                      the FX thread, and on the CLI engine that question is a
     *                      subprocess.
     */
    public RightPanelManager(OperationRunner operations,
                             DetailPanel panel,
                             DiffViewer diffViewer,
                             java.util.function.Supplier<String> currentBranch) {
        this.currentBranch = currentBranch;
        this.operations = operations;
        com.jfx.git.core.GitBackend gitService = operations.repository();
        this.gitService = gitService;
        this.panel = panel;
        this.diffViewer = diffViewer;
        diffViewer.setOnExternalDiff(this::openExternalDiff);
        // Every diff this panel shows says what it compares, and that decides which hunk
        // actions it offers; this only runs them.
        diffViewer.setOnHunkAction(this::runHunkAction);
        // A child process the first time it is asked; not something for a cell factory.
        // On the other lane: a process that is not repository work, and on the first tab
        // it sat ahead of the repository's own open.
        GitTaskRunner.queryDetached(com.jfx.git.core.NativeGitRunner::isGitLfsInstalled,
                installed -> lfsAvailable = installed, error -> { });
        this.statusBar = operations.statusBar();
        this.composer = new CommitComposer(CommitComposer.Preferences.of(gitService::getWorkTree),
                CommitComposer.Memory.of(gitService::getDirectory), new ComposerReads());
        COMMIT_SETTINGS.addListener(new javafx.beans.value.WeakChangeListener<>(onCommitSettings));

        diffViewer.setOnIgnoreWhitespaceChanged(ignore -> reloadDiff());
    }

    // ------------------------------------------------------------------
    // Actions reachable from the keyboard as well as the panel
    // ------------------------------------------------------------------

    /**
     * Stages everything but the conflicts - what {@code Ctrl+Shift+S}, the palette's
     * "Git: Stage all" and the Staged section's empty-state button all mean.
     *
     * <p>Callable whether or not the staging panel is on screen, because the shortcut
     * that reaches it is bound to the window rather than to the panel's buttons.
     *
     * <p>Staging a conflicted file is how git is told it is resolved, so a plain
     * {@code git add -A} in the middle of a merge marked every conflict resolved with its
     * {@code <<<<<<<} markers still in it, said nothing, and left the commit button
     * enabled to record them. The conflicts are left where they are, as the Unstaged
     * section's own Stage all leaves them; marking one resolved is a gesture of its own,
     * under Conflicted files, which asks first about a file that still holds markers.
     */
    public void stageAllFiles() {
        operations.operation("Staging all", OperationRunner.Refresh.EVERYTHING)
                .afterwards(result -> {
                    if (stagingOnScreen()) showStagingArea();
                })
                .run(() -> stageExceptConflicts(null).result());
    }

    /**
     * What a Stage all did: git's result, and the paths it left alone because they were
     * conflicted when it ran.
     */
    private record StagedAll(GitResult result, Set<String> conflicts) {
    }

    /**
     * Stages {@code paths} - or, when null, everything changed - except whatever is
     * conflicted when this runs: {@code git add -A} when nothing is, otherwise each other
     * path in turn.
     *
     * <p>The conflicts are read here, on the worker, rather than taken from the panel.
     * The shortcut works with the panel showing something else, and a merge started in a
     * terminal since the panel last looked has conflicts it never saw: its own Stage all,
     * believing every unstaged path plain, ran {@code git add -A} over them.
     */
    private StagedAll stageExceptConflicts(List<String> paths) {
        StagingState state = gitService.getStagingState();
        Set<String> conflicts = state.conflicting();
        if (paths == null && conflicts.isEmpty()) {
            return new StagedAll(gitService.stageAll(), conflicts);
        }
        List<String> plain = (paths == null ? state.unstaged() : paths).stream()
                .filter(path -> !conflicts.contains(path)).toList();
        String what = plain.isEmpty() ? "Nothing staged"
                : "Staged " + plain.size() + (plain.size() == 1 ? " file" : " files");
        String done = conflicts.isEmpty() ? what : what + " - " + conflictsLeft(conflicts.size());
        return new StagedAll(plain.isEmpty() ? GitResult.ok(done) : stageEach(plain, done), conflicts);
    }

    /** "1 conflict left", "3 conflicts left". */
    private static String conflictsLeft(int conflicts) {
        return conflicts == 1 ? "1 conflict left" : conflicts + " conflicts left";
    }

    public void unstageAllFiles() {
        operations.operation("Unstaging all", OperationRunner.Refresh.EVERYTHING)
                .afterwards(result -> {
                    if (stagingOnScreen()) showStagingArea();
                })
                .run(gitService::unstageAll);
    }

    /**
     * Switches whatever the diff panel is currently showing into the built-in editor —
     * the Command Palette's route to the same gesture the panel's own Edit button
     * offers. {@code beginEditing} already no-ops when there is nothing editable open
     * (no file selected, a stash/comparison view, a binary file), so this needs no
     * guard of its own.
     */
    public void editCurrentFile() {
        diffViewer.beginEditing();
    }

    /** Git's subject/body convention; shared with every other message editor. */
    private static String joinedCommitMessage(String summary, String description) {
        return MessageEditor.join(summary, description);
    }

    /** The inverse join: splits an existing message back into summary and description. */
    private static MessageEditor.Parts splitCommitMessage(String text) {
        return MessageEditor.split(text);
    }

    /**
     * Finishes whichever composer is on screen: commits, or stashes.
     *
     * <p>What {@code Ctrl+Enter} is bound to. It cannot be {@link #commitStaged()}
     * directly, because that binding is installed as a scene-level event filter -
     * {@link Shortcuts#installOn} explains why - which runs <em>before</em> the focused
     * control, so a handler on the stash composer's own fields never sees the key. Left
     * pointed at committing, typing a stash name and pressing the key that finishes
     * every other composer in the application would commit instead.
     */
    public void submitComposer() {
        if (showingStashComposer) {
            if (stashAction == null) {
                // The panel is showing a commit rather than the composer - clicking a
                // graph row is enough to get here. Bring the composer back rather than
                // committing, which is not what the tab the user last chose said this
                // key would do.
                showStagingArea();
                return;
            }
            stashAction.run();
            return;
        }
        commitStaged();
    }

    /**
     * Stashes what the stash composer currently holds.
     *
     * <p>Held as a field because the button that runs it is rebuilt on every refresh,
     * while the keyboard binding that also needs it is installed once, on the window.
     * Null whenever the staging panel is not the thing on screen.
     */
    private Runnable stashAction;

    /** Commits, and pushes afterwards when the Commit settings say so. */
    public void commitStaged() {
        press(false);
    }

    /**
     * Commits and then pushes, whatever the setting says - what {@code Ctrl+Shift+Enter}
     * means.
     */
    public void commitAndPush() {
        press(true);
    }

    /** A press of the Commit button, from the button or a shortcut, with the staged count of the moment. */
    private void press(boolean andPush) {
        int staged = stagedCount();
        submit(composer.request(andPush, staged), () -> composer.refusal(staged));
    }

    /**
     * What is staged now: the lists' count while they are on screen - a stage click moves a
     * row before git has answered - and the last staging read's otherwise.
     */
    private int stagedCount() {
        LiveStaging live = liveStaging;
        if (live != null) return live.stagedAll.size();
        return stagingState == null ? 0 : stagingState.staged().size();
    }

    /**
     * Runs a press of the commit box, as the composer turned it into a request: stages
     * everything but the conflicts first when asked, then commits, and pushes afterwards
     * when asked. No request means the press commits nothing - what the button would say
     * when it cannot be pressed, or that there is no message - and that is said instead.
     *
     * <p>Stage all and commit stops while anything is conflicted. Staging the conflicts is
     * what let git commit them: one keystroke recorded a merge with {@code <<<<<<<} markers
     * in every file that had any. Staging all but the conflicts, as Stage all does, leaves a
     * commit git refuses anyway, so this stops before either and says what the commit
     * button says. The conflicts are counted on the worker, not taken from the panel.
     */
    private void submit(java.util.Optional<CommitComposer.CommitRequest> pressed,
                        java.util.function.Supplier<String> refusal) {
        if (pressed.isEmpty()) {
            statusBar.error(refusal.get());
            // Brings the box on screen when the press came from elsewhere, so what it says
            // is there to read.
            focusCommitMessage();
            return;
        }
        CommitComposer.CommitRequest request = pressed.get();
        if (!request.stageAllFirst()) {
            commit(request, com.jfx.git.core.CommitChecks.Options.fromPreferences(), null);
            return;
        }
        // Nothing is redrawn: the commit that follows refreshes everything, and a refusal
        // changed nothing.
        operations.operation("Staging all", OperationRunner.Refresh.NONE)
                // Said nothing when it worked, as before: "Committing" takes the bar at once.
                .showSuccess(result -> { })
                .onSuccess(result -> commit(request, com.jfx.git.core.CommitChecks.Options.fromPreferences(), null))
                .run(() -> {
                    int conflicts = gitService.getConflictingFiles().size();
                    return conflicts > 0 ? GitResult.failed(CommitComposer.resolveConflictsFirst(conflicts))
                            : gitService.stageAll();
                });
    }

    /**
     * What the worker came back with: the commit's result, or the findings that stopped it
     * before it was attempted.
     *
     * @param publishedTo for an amend that is to be pushed, where the commit it replaced
     *                    was already published - read before the amend moved HEAD
     */
    private record CommitAttempt(GitResult result, List<com.jfx.git.core.CommitChecks.Finding> findings,
                                 String publishedTo) {
    }

    /**
     * Runs the checks (unless {@code checks} is null), {@code before} (unless null), and the
     * commit, on the worker; then reports, pushes, or shows what the checks found.
     *
     * <p>Checks that find something stop the commit before it is attempted: a failure, to
     * the log, shown as the findings to decide on rather than as an error.
     *
     * <p>The tab is redrawn for what changed something: the commit, or files staged or
     * unstaged on the way to it - Stage all first, the findings' Unstage. A commit git
     * refused, or one the checks stopped, changed nothing, and redrawing for it read the
     * whole repository again for nothing.
     */
    private void commit(CommitComposer.CommitRequest request, com.jfx.git.core.CommitChecks.Options checks,
                        java.util.function.Supplier<GitResult> before) {
        java.util.concurrent.atomic.AtomicReference<CommitAttempt> made =
                new java.util.concurrent.atomic.AtomicReference<>();
        operations.operation("Committing", OperationRunner.Refresh.EVERYTHING)
                .showFailure(result -> {
                    CommitAttempt attempt = made.get();
                    if (attempt == null || attempt.result() != null) return false;
                    offerCheckFindings(request, attempt.findings());
                    return true;
                })
                .refreshWhen(result -> result.isOk() || request.stageAllFirst() || before != null)
                // Amend and Skip hooks are one-shot. Left ticked, the next commit was folded
                // into this one and its message overwritten, and every later commit skipped
                // the team's hooks. Cleared before the refresh, which then finds a box for a
                // new commit.
                .onSuccess(result -> composer.committed(request))
                .afterwards(result -> {
                    if (result.isOk() && request.push()) pushCommitted(made.get().publishedTo());
                })
                .run(() -> {
                    CommitAttempt attempt = attempt(request, checks, before);
                    made.set(attempt);
                    return attempt.result() != null ? attempt.result()
                            : GitResult.failed("The checks before committing found "
                                    + attempt.findings().size() + " thing(s) to look at");
                });
    }

    /** The checks, {@code before} and the commit, on the worker. */
    private CommitAttempt attempt(CommitComposer.CommitRequest request, com.jfx.git.core.CommitChecks.Options checks,
                                  java.util.function.Supplier<GitResult> before) {
        // Not gated on checks.any(): a commit that finishes a merge is looked over for
        // conflict markers with every check turned off.
        if (checks != null) {
            var findings = com.jfx.git.core.CommitChecks.run(gitService, checks);
            if (!findings.isEmpty()) {
                return new CommitAttempt(null, findings, null);
            }
        }
        if (before != null) {
            GitResult prepared = before.get();
            if (!prepared.isOk()) return new CommitAttempt(prepared, List.of(), null);
        }
        String toCommit = request.message();
        if (request.signOff()) {
            // Resolved here, on the worker, at the moment of committing: the identity
            // is a git config read (a subprocess on the default engine), and it is the
            // one the commit itself is about to be made with.
            String identity = gitService.getCommitIdentity();
            if (identity == null) {
                return new CommitAttempt(GitResult.failed("Sign off needs a commit identity. Set user.name and "
                        + "user.email (Profiles, or git config), or untick \"Sign off\"."), List.of(), null);
            }
            toCommit = request.signedOffBy(identity);
        }
        // Asked before the amend replaces HEAD: afterwards nothing points at the old one.
        String publishedTo = request.amend() && request.push() ? publishedAs() : null;
        return new CommitAttempt(gitService.commit(toCommit, request.amend(), request.skipHooks()),
                List.of(), publishedTo);
    }

    /** After a commit landed and the tab was refreshed: the push its request asked for. */
    private void pushCommitted(String publishedTo) {
        if (publishedTo != null) {
            // The amend replaced a commit that is already on the remote; pushing it now
            // needs a force, which is never something to do on the user's behalf.
            statusBar.warn("Committed, not pushed: the amended commit was already on " + publishedTo
                    + ", so pushing it needs a force push");
            return;
        }
        RepositoryCommands commands = new RepositoryCommands(gitService);
        operations.operation("Pushing", OperationRunner.Refresh.EVERYTHING)
                // The commit landed; only the push did not. Git's own words - "failed to
                // push some refs" - read as though nothing had been committed, and the
                // composer the message was typed in is already empty.
                .reportFailureAfter("Committed locally, but the push failed")
                .run(() -> commands.push(null, false, false, false));
    }

    /**
     * Shows what the pre-commit checks found, and commits from there if asked: after
     * unstaging the files the user picked, and handing the large ones they picked to LFS.
     * Cancel commits nothing and leaves the box - message and options - as it was: the
     * stopped commit redrew nothing to reset them.
     */
    private void offerCheckFindings(CommitComposer.CommitRequest request,
                                    List<com.jfx.git.core.CommitChecks.Finding> findings) {
        new com.jfx.git.ui.dialogs.CommitChecksDialog(findings, lfsAvailable, RightPanelManager::lfsPatternFor)
                .showAndWait()
                .ifPresentOrElse(resolution -> {
                    var actions = new java.util.LinkedHashMap<>(resolution.actions());
                    commit(request, null, actions.isEmpty() ? null : () -> applyResolution(actions));
                }, () -> statusBar.info("Nothing was committed"));
    }

    /** Unstages, or moves to LFS, each file the findings dialog was told to. Runs on the worker. */
    private GitResult applyResolution(java.util.Map<String,com.jfx.git.ui.dialogs.CommitChecksDialog.Action> actions) {
        for (var entry : actions.entrySet()) {
            String path = entry.getKey();
            GitResult result;
            if (entry.getValue() == com.jfx.git.ui.dialogs.CommitChecksDialog.Action.UNSTAGE) {
                result = gitService.unstageFile(path);
            } else {
                // The same two steps as the LFS menu's "Track all ... files", and then the
                // file staged again so the index holds the pointer rather than the content.
                result = gitService.lfsTrack(lfsPatternFor(path));
                if (result.isOk()) result = gitService.stageFile(".gitattributes");
                if (result.isOk()) result = gitService.stageFile(path);
            }
            if (!result.isOk()) return result;
        }
        return GitResult.ok("Staged changes updated");
    }

    /** What LFS should track for {@code path}: its type, or the file itself when it has none. */
    private static String lfsPatternFor(String path) {
        String byType = lfsTypePattern(path);
        return byType != null ? byType : path;
    }

    /**
     * The commit an amend would fold into, and whether it is already published.
     *
     * @param publishedTo where HEAD is already published - its upstream, or a
     *                    remote-tracking branch pointing at it - or {@code null} when it
     *                    is only in this clone
     */
    private record AmendTarget(String message, String publishedTo) { }

    /** Runs on the worker. */
    private AmendTarget readAmendTarget() {
        String message = gitService.getHeadCommitMessage();
        return new AmendTarget(message == null ? "" : message, publishedAs());
    }

    /**
     * Where HEAD is already published, or {@code null}. Amending a published commit
     * rewrites one other people may already have, and pushing it needs a force.
     *
     * <p>Two checks, because {@code getBranchTracking} leaves out a branch that is level
     * with its upstream - so the sidebar draws no zero beside it - and level is exactly
     * the state straight after a push. Asking it alone, the warning showed only for a
     * branch that was behind its upstream, and never for the commit just pushed. A
     * remote-tracking branch pointing at HEAD itself covers that case, and one the user
     * pushed somewhere other than the upstream too. Runs on the worker.
     */
    private String publishedAs() {
        String branch = gitService.getCurrentBranch();
        if (branch != null && !branch.isBlank()) {
            int[] tracking = gitService.getBranchTracking().get(branch);
            // Behind the upstream and not ahead of it: HEAD is in the upstream's history.
            if (tracking != null && tracking.length > 0 && tracking[0] == 0) {
                return "its upstream";
            }
        }
        String head = gitService.getHeadHash();
        if (head == null || head.isBlank()) return null;
        for (com.jfx.git.model.RefInfo remote : gitService.getRemoteBranches()) {
            if (head.equals(remote.targetHash())) {
                return remote.shortName();
            }
        }
        return null;
    }

    /**
     * Asks who to credit, offering recent authors; the composer adds them to the
     * description as a {@code Co-authored-by:} trailer.
     */
    private void addCoAuthor() {
        com.jfx.git.ui.dialogs.CoAuthorDialog.ask(gitService, composer::addCoAuthor, statusBar::error);
    }

    // ------------------------------------------------------------------
    // Message history (Alt+Up, Alt+Down and the history menu)
    // ------------------------------------------------------------------

    /** Steps through the messages committed here: {@code +1} older, {@code -1} newer. */
    private void stepHistory(int step) {
        if (!composer.stepHistory(step)) {
            statusBar.info("No commit messages to recall yet");
        }
    }

    /** The recent messages as a menu under {@code anchor}; picking one puts it in the composer. */
    private void showHistoryMenu(javafx.scene.Node anchor) {
        List<String> history = composer.history();
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("commit-history-menu");
        if (history.isEmpty()) {
            MenuItem none = new MenuItem("No messages committed here yet");
            none.setDisable(true);
            menu.getItems().add(none);
        }
        for (int i = 0; i < history.size(); i++) {
            int index = i;
            String subject = MessageEditor.split(history.get(i)).summary();
            MenuItem item = new MenuItem(subject.length() > 72 ? subject.substring(0, 69) + "..." : subject);
            item.setMnemonicParsing(false);
            item.setOnAction(e -> composer.recallHistory(index, history));
            menu.getItems().add(item);
        }
        menu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    // ------------------------------------------------------------------
    // The composer's ports
    // ------------------------------------------------------------------

    /**
     * What the composer waits for the repository to answer, read on its lane and answered
     * on the FX thread. A read that fails leaves the box as it is.
     */
    private final class ComposerReads implements CommitComposer.Reader {
        @Override
        public void commitTemplate(java.util.function.Consumer<String> then) {
            GitTaskRunner.query(gitService, gitService::getCommitTemplate, then, error -> { });
        }

        @Override
        public void preparedMessage(boolean squash, java.util.function.Consumer<String> then) {
            GitTaskRunner.query(gitService,
                    () -> squash
                            ? com.jfx.git.model.PreparedMessage.readSquash(gitService.getDirectory())
                            : com.jfx.git.model.PreparedMessage.read(gitService.getDirectory()),
                    then, error -> { });
        }

        @Override
        public void headMessage(java.util.function.Consumer<String> then) {
            // On the worker: on the default engine it is a subprocess.
            GitTaskRunner.query(gitService, gitService::getHeadCommitMessage, then, error -> { });
        }
    }

    /** The commit box's controls, as the composer fills them in. */
    private record ComposerControls(javafx.scene.control.TextField summary, TextArea description,
                                    CheckBox amend, CheckBox skipHooks) implements CommitComposer.Screen {
        @Override
        public void showSummary(String text) {
            summary.setText(text);
        }

        @Override
        public void showDescription(String text) {
            description.setText(text);
        }

        @Override
        public void caretToSummaryEnd() {
            summary.positionCaret(summary.getText().length());
        }

        @Override
        public void showAmend(boolean on) {
            amend.setSelected(on);
        }

        @Override
        public void showSkipHooks(boolean on) {
            skipHooks.setSelected(on);
        }
    }

    /** The commit box has left the screen with the rest of the staging panel. */
    private void forgetComposer() {
        composerControls = null;
        composer.detach();
    }

    /** Stages everything but the conflicts and then commits it, as one gesture; see {@link #submit}. */
    public void stageAllAndCommit() {
        submit(composer.requestStagingAll(), composer::refusalStagingAll);
    }

    /** Brings the staging panel up and puts the caret in the commit message box. */
    public void focusCommitMessage() {
        if (composerControls == null) {
            // The panel is showing something else; build it, then focus once it exists.
            showStagingArea();
            javafx.application.Platform.runLater(() -> {
                if (composerControls != null) {
                    composerControls.summary().requestFocus();
                }
            });
            return;
        }
        composerControls.summary().requestFocus();
    }

    // ------------------------------------------------------------------
    // Staging area
    // ------------------------------------------------------------------

    private void forgetLiveStagingControls() {
        liveCommitButton = null;
        liveUnstagedList = null;
        liveStagedList = null;
        liveConflictedList = null;
        liveStagingFilter = null;
        liveStaging = null;
    }

    /**
     * The parts of the staging panel a refresh changes, while the panel is on screen.
     *
     * <h2>Why the panel is updated rather than rebuilt</h2>
     * Every stage click, every save, every return to the window and every watcher event
     * asked for the staging state and rebuilt the whole panel from it: two lists and two
     * trees with fresh skins, the composer's text controls, a dozen listeners, three
     * background queries. On a two-thousand-file working tree that was a visible hitch per
     * click, and most refreshes found exactly what was already on screen. A refresh now
     * moves the new answer into these controls and leaves everything else - scroll
     * positions, the composer, the open file - where it was.
     */
    private static final class LiveStaging {
        javafx.collections.ObservableList<String> unstagedAll;
        javafx.collections.ObservableList<String> stagedAll;
        ListView<String> unstagedList;
        ListView<String> stagedList;
        ListView<String> conflictedList;
        /** The area the panel's body holds, to tell whether it is still on screen. */
        javafx.scene.Node fileArea;
        /** Sorts the paths between Conflicted and Unstaged again, after the conflicts changed. */
        Runnable applyFilters;
        Button btnStageAll;
        Button btnUnstageAll;
        Button stageEverything;
        Runnable updateCounts;
        Runnable refreshAmendTarget;
    }

    /** The staging panel on screen, or null; see {@link LiveStaging}. */
    private LiveStaging liveStaging;

    /** The panel on screen now, as the next rebuild should restore it. */
    private StagingSnapshot captureStagingSnapshot() {
        if (liveUnstagedList == null || liveStagedList == null) {
            return StagingSnapshot.EMPTY;
        }
        String filter = liveStagingFilter == null || liveStagingFilter.getText() == null
                ? "" : liveStagingFilter.getText();
        String unstagedPick = liveUnstagedList.getSelectionModel().getSelectedItem();
        if (unstagedPick == null && liveConflictedList != null) {
            unstagedPick = liveConflictedList.getSelectionModel().getSelectedItem();
        }
        String stagedPick = liveStagedList.getSelectionModel().getSelectedItem();
        String picked = unstagedPick != null ? unstagedPick : stagedPick;
        // Only a selection that is what the viewer is showing. A row can stay selected
        // after the viewer moved on to something else, and re-selecting it would open a
        // file the user had left.
        if (picked != null && !picked.equals(diffViewer.filePathProperty().get())) {
            picked = null;
        }
        return new StagingSnapshot(filter, picked, unstagedPick != null);
    }

    /**
     * Selects the row that was selected before a rebuild, which reloads its diff.
     *
     * <p>The same side first, since a partly staged file is on both; otherwise wherever
     * the file is now, as when staging its last hunk moved it across.
     *
     * @return whether the file is still in either list, so the viewer should stay open
     */
    private boolean restoreStagingSelection(StagingSnapshot before,
                                            ListView<String> conflictedList,
                                            ListView<String> unstagedList,
                                            ListView<String> stagedList) {
        String path = before.selectedPath();
        if (path == null) {
            return false;
        }
        // The working-tree side first when that is where it was: a conflict still
        // conflicted, or the file as an ordinary change, then wherever it is now.
        List<ListView<String>> order = before.selectedUnstaged()
                ? List.of(conflictedList, unstagedList, stagedList)
                : List.of(stagedList, conflictedList, unstagedList);
        ListView<String> target = order.stream()
                .filter(list -> list.getItems().contains(path))
                .findFirst().orElse(null);
        if (target == null) {
            return false;
        }
        restoringStagingSelection = true;
        try {
            target.getSelectionModel().select(path);
        } finally {
            restoringStagingSelection = false;
        }
        target.scrollTo(path);
        return true;
    }

    /**
     * Whether a list selection should leave the viewer as it is: a rebuild re-selecting
     * the file that is open in the editor. Reloading it would end the session.
     */
    private boolean keepsEditingSession(String path) {
        return restoringStagingSelection
                && diffViewer.isEditing()
                && path.equals(diffViewer.filePathProperty().get());
    }

    public void showStagingArea() {
        // Fire the background query without clearing the panel first.
        // The existing panel stays visible while git status runs, eliminating the
        // blank flash that appeared between the clear and the data arriving.
        long myRequest = ++stagingRequestId;
        GitTaskRunner.query(gitService,
                gitService::getStagingState,
                state -> {
                    if (myRequest == stagingRequestId) {
                        buildStagingUI(state);
                        updateOperationBanner(state);
                    }
                },
                statusBar::error);
    }

    /**
     * Whether {@link #liveStaging} is the panel actually on screen. Another view clears
     * the body and forgets it; the tab's "Loading…" state clears the body without asking,
     * which is what the parent check is for.
     */
    private boolean stagingOnScreen() {
        LiveStaging live = liveStaging;
        return live != null && live.unstagedList == liveUnstagedList
                && live.fileArea.getParent() == panel.body();
    }

    /**
     * Brings the staging panel on screen up to {@code state} without rebuilding it.
     *
     * <p>Does what a rebuild did, in the same order, to the controls already there: the
     * row markers are dropped, the lists take the new paths (only where they differ - each
     * change re-filters, re-folds the tree and re-reads the line counts), every row is
     * redrawn, and the row that was open is selected again so its diff is read afresh. A
     * staged hunk leaves the file in both lists and the state unchanged, and that re-read
     * is the only thing that shows the user what is left of it.
     *
     * @return false when a rebuild is still needed: the repository browser is showing,
     *         and it is built once per panel
     */
    private boolean updateStagingInPlace(StagingState state) {
        LiveStaging live = liveStaging;
        if (showingWorkspaceTree) {
            return false;
        }
        StagingSnapshot before = captureStagingSnapshot();
        boolean sameLists = live.unstagedAll.equals(state.unstaged())
                && live.stagedAll.equals(state.staged());
        boolean same = sameLists && state.equals(stagingState);
        rowActivity.clear();
        stagingState = state;
        stagingConflicts = Set.copyOf(state.conflicting());
        noteConflicts(state);
        // Every read, changed or not: the composer decides what a read of the same box keeps.
        composer.stagingRead(state);

        if (!same) {
            if (!live.unstagedAll.equals(state.unstaged())) {
                live.unstagedAll.setAll(state.unstaged());
            }
            if (!live.stagedAll.equals(state.staged())) {
                live.stagedAll.setAll(state.staged());
            }
            // After the lists: a path that stopped being conflicted moves to Unstaged
            // even when the list of paths itself did not change.
            live.applyFilters.run();
            live.btnStageAll.setDisable(!hasPlainUnstaged(state));
            live.btnUnstageAll.setDisable(state.staged().isEmpty());
            show(live.stageEverything, hasPlainUnstaged(state));
            live.updateCounts.run();
            live.refreshAmendTarget.run();
            // A new branch's issue key, the template, a prepared message - over an empty box.
            composer.fillIn();
        }
        // Every row reads its status, conflict, lock and the merge-tool preference again,
        // as the rebuilt rows did; with the cells reused this is a repaint, not a rebuild.
        repaintStagingRows.run();
        // Counts follow the content, which changes when the paths do not.
        if (sameLists) {
            reloadLineCounts.run();
        }

        boolean restored = false;
        if (before.selectedPath() != null) {
            restoringStagingSelection = true;
            try {
                live.conflictedList.getSelectionModel().clearSelection();
                live.unstagedList.getSelectionModel().clearSelection();
                live.stagedList.getSelectionModel().clearSelection();
            } finally {
                restoringStagingSelection = false;
            }
            restored = restoreStagingSelection(before, live.conflictedList, live.unstagedList, live.stagedList);
        }
        if (!restored && !diffViewer.isEditing()) {
            diffViewer.setFileDiff("", "");
            diffViewer.setEditable(false);
        }
        // See the end of the full build: a clean tree takes the WIP row away.
        if (!state.hasUncommittedChanges()) {
            operations.refresh(OperationRunner.Refresh.EVERYTHING);
        }
        return true;
    }

    /**
     * Remembers the paths conflicted during the operation in progress, for the Staged
     * list's Resolved tag; forgets them all once nothing is in progress.
     */
    private void noteConflicts(StagingState state) {
        if (state.midOperation()) {
            conflictedThisOperation.addAll(state.conflicting());
        } else {
            conflictedThisOperation.clear();
        }
    }

    /** Whether anything unstaged is not a conflict: what Stage all in the Unstaged section stages. */
    private static boolean hasPlainUnstaged(StagingState state) {
        Set<String> conflicting = state.conflicting();
        return state.unstaged().stream().anyMatch(path -> !conflicting.contains(path));
    }

    /** Whether the Staged list tags {@code path} as resolved: conflicted earlier in this operation, not now. */
    private boolean resolvedThisOperation(String path) {
        return conflictedThisOperation.contains(path) && !stagingConflicts.contains(path);
    }

    private void buildStagingUI(StagingState state) {
        if (stagingOnScreen() && updateStagingInPlace(state)) {
            return;
        }
        StagingSnapshot before = captureStagingSnapshot();
        // A rebuild reads the repository afresh, so what it shows is the answer; a marker
        // from before it would describe a list that no longer exists. A move still in
        // flight sets its own state again when it lands.
        rowActivity.clear();
        // Held for updateCommitButton, which runs on every count change - a click, a
        // selection, a cell refresh - and used to ask the backend for both of these each
        // time, on the FX thread. See StagingState.
        this.stagingState = state;
        composer.stagingRead(state);
        List<String> unstaged = state.unstaged();
        List<String> staged = state.staged();
        Set<String> conflicts = Set.copyOf(state.conflicting());
        this.stagingConflicts = conflicts;
        noteConflicts(state);

        int changes = unstaged.size() + staged.size();
        panel.setMode(DetailPanel.Mode.WORKING_TREE, "Working tree", null,
                changes + (changes == 1 ? " change" : " changes"));
        panel.clearBody();

        // --- Unstaged ---
        ListView<String> unstagedList = new ListView<>();
        // The role alongside the look: this panel and the sidebar both use
        // .sidebar-list, so naming the two staging lists is what lets a stylesheet - or
        // a test - say which one it means.
        unstagedList.getStyleClass().addAll("sidebar-list", "staging-unstaged-list");
        unstagedList.setFixedCellSize(UiScale.ROW_HEIGHT);
        // The list the panel mutates, and the view the user sees. Staging moves a path
        // between the two backing lists optimistically; a FilteredList cannot be added to
        // or removed from, so every one of those moves targets the backing list and the
        // view follows through the predicate.
        javafx.collections.ObservableList<String> unstagedAll =
                javafx.collections.FXCollections.observableArrayList(unstaged);
        javafx.collections.transformation.FilteredList<String> unstagedShown =
                new javafx.collections.transformation.FilteredList<>(unstagedAll);
        unstagedList.setItems(unstagedShown);
        unstagedList.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        // An empty list rendered as a bare bordered box a single row tall - see
        // sizeToContent, which floors the height at one row so the section keeps its
        // shape. A box with nothing in it and nothing to say reads as a failure to load.
        unstagedList.setPlaceholder(placeholderLabel("Nothing changed here yet."));

        Label lblUnstaged = new Label("Unstaged");
        lblUnstaged.getStyleClass().add("section-title");
        Label countUnstaged = new Label(String.valueOf(unstaged.size()));
        countUnstaged.getStyleClass().add("count-badge");

        // --- Staged ---
        ListView<String> stagedList = new ListView<>();
        stagedList.getStyleClass().addAll("sidebar-list", "staging-staged-list");
        stagedList.setFixedCellSize(UiScale.ROW_HEIGHT);
        javafx.collections.ObservableList<String> stagedAll =
                javafx.collections.FXCollections.observableArrayList(staged);
        javafx.collections.transformation.FilteredList<String> stagedShown =
                new javafx.collections.transformation.FilteredList<>(stagedAll);
        stagedList.setItems(stagedShown);
        stagedList.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        // Empty, with something to stage: the way to stage it is right there.
        javafx.scene.control.Button stageEverything = new javafx.scene.control.Button("Stage all");
        stageEverything.getStyleClass().addAll("btn-sm", "staged-empty-action");
        stageEverything.setOnAction(e -> stageAllFiles());
        javafx.scene.layout.VBox stagedEmpty = new javafx.scene.layout.VBox(UiScale.STEP,
                placeholderLabel("Nothing staged. Stage a file to commit it."), stageEverything);
        stagedEmpty.setAlignment(Pos.CENTER);
        // Only with something it would stage: it leaves conflicts alone (see stageAllFiles),
        // and a merge where nothing merged cleanly has nothing else. Offered there, right
        // under the conflicts, it read as the way to finish them.
        show(stageEverything, hasPlainUnstaged(state));
        stagedList.setPlaceholder(stagedEmpty);

        Label lblStaged = new Label("Staged");
        lblStaged.getStyleClass().add("section-title");
        Label countStaged = new Label(String.valueOf(staged.size()));
        countStaged.getStyleClass().add("count-badge");

        // --- Conflicted ---
        // Every engine reports a conflicted path as unstaged - it still needs work in the
        // tree - and the panel used to list it there, a red name among ordinary edits.
        // Commercial clients' conflict mode lists the conflicts on their own, above the rest, and
        // so does this. The backing list stays one, seen through two filters, so staging,
        // the optimistic moves and the line counts all work exactly as they did.
        ListView<String> conflictedList = new ListView<>();
        conflictedList.getStyleClass().addAll("sidebar-list", "staging-conflicted-list");
        conflictedList.setFixedCellSize(UiScale.ROW_HEIGHT);
        javafx.collections.transformation.FilteredList<String> conflictedShown =
                new javafx.collections.transformation.FilteredList<>(unstagedAll);
        conflictedList.setItems(conflictedShown);
        conflictedList.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        conflictedList.setPlaceholder(placeholderLabel("No conflicts match the filter."));
        // Enter opens the selected conflict, as a click does.
        conflictedList.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            String picked = conflictedList.getSelectionModel().getSelectedItem();
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER && picked != null
                    && !e.isShortcutDown() && !e.isShiftDown() && !e.isAltDown()) {
                openMergeTool(picked);
                e.consume();
            }
        });
        // The unstaged paths without the conflicts, for the folder tree; the flat list has
        // its own filter, which also carries the text filter.
        javafx.collections.transformation.FilteredList<String> unstagedPlain =
                new javafx.collections.transformation.FilteredList<>(unstagedAll);

        Label lblConflicted = new Label("Conflicted files");
        lblConflicted.getStyleClass().addAll("section-title", "conflicted-title");
        lblConflicted.setGraphic(Icons.warning());
        Label countConflicted = new Label();
        countConflicted.getStyleClass().add("count-badge");
        Button btnMarkAllResolved = new Button("Mark all resolved");
        btnMarkAllResolved.getStyleClass().addAll("btn-ghost", "row-action", "mark-all-resolved");
        btnMarkAllResolved.setMinWidth(Region.USE_PREF_SIZE);
        btnMarkAllResolved.setTooltip(new Tooltip("Stage every conflicted file as it is now, which tells"
                + " git the conflicts are resolved. Files still holding conflict markers are named first."));
        btnMarkAllResolved.setOnAction(e -> markAllResolved(unstagedAll.stream()
                .filter(path -> stagingConflicts.contains(path)).toList()));
        VBox conflictedSection = new VBox(UiScale.SNUG,
                sectionHeader(lblConflicted, countConflicted, btnMarkAllResolved), conflictedList);
        conflictedSection.getStyleClass().add("staging-conflicted-section");

        // Text filter and conflict split, applied together. A new predicate every time:
        // a FilteredList re-sorts only when its predicate changes, and the set of conflicts
        // can change under the same paths.
        String[] filterNeedle = {""};
        Runnable applyFilters = () -> {
            String needle = filterNeedle[0];
            java.util.function.Predicate<String> matches = needle.isEmpty()
                    ? path -> true
                    : path -> path.toLowerCase(java.util.Locale.ROOT).contains(needle);
            unstagedShown.setPredicate(path -> !stagingConflicts.contains(path) && matches.test(path));
            conflictedShown.setPredicate(path -> stagingConflicts.contains(path) && matches.test(path));
            stagedShown.setPredicate(path -> matches.test(path));
            unstagedPlain.setPredicate(path -> !stagingConflicts.contains(path));
        };
        applyFilters.run();

        reloadLineCounts = () -> {
            List<String> unstagedPaths = List.copyOf(unstagedAll);
            List<String> stagedPaths = List.copyOf(stagedAll);
            GitTaskRunner.query(gitService,
                    () -> new LineCounts(
                            gitService.getWorkingTreeLineCounts(false, unstagedPaths),
                            gitService.getWorkingTreeLineCounts(true, stagedPaths)),
                    counts -> {
                        unstagedLineCounts = counts.unstaged();
                        stagedLineCounts = counts.staged();
                        redrawStagingRows(conflictedList, unstagedList, stagedList);
                    },
                    // A count is a nicety on top of a row that is already correct and
                    // already actionable, so a failure to read one is not worth a banner.
                    message -> { });
        };
        reloadLineCounts.run();

        javafx.collections.ListChangeListener<String> countsFollowTheLists =
                change -> scheduleLineCountReload();
        unstagedAll.addListener(countsFollowTheLists);
        stagedAll.addListener(countsFollowTheLists);

        Button btnCommit = new Button();
        btnCommit.getStyleClass().add("btn-primary");
        btnCommit.setMaxWidth(Double.MAX_VALUE);

        // Header counts, list heights and the commit button all follow the same numbers,
        // so they are updated together rather than by three separate listeners that can
        // disagree while a background git operation is still in flight.
        Runnable updateCounts = () -> {
            int nConflicted = (int) unstagedAll.stream().filter(path -> stagingConflicts.contains(path)).count();
            int nUnstaged = unstagedAll.size() - nConflicted;
            int nStaged = stagedAll.size();
            countConflicted.setText(String.valueOf(nConflicted));
            countUnstaged.setText(String.valueOf(nUnstaged));
            countStaged.setText(String.valueOf(nStaged));
            sizeToContent(conflictedList, nConflicted);
            // The few conflicts there usually are stay in view however long the lists below
            // are: they are what the panel is for until they are resolved.
            conflictedList.setMinHeight(Math.min(Math.max(1, nConflicted), 4) * UiScale.ROW_HEIGHT + 2);
            sizeToContent(unstagedList, nUnstaged);
            sizeToContent(stagedList, nStaged);
            // Only while there is something to resolve: the section going is the sign
            // that the last conflict is done.
            show(conflictedSection, nConflicted > 0);
            int total = unstagedAll.size() + nStaged;
            panel.setCount(total + (total == 1 ? " change" : " changes"));
            updateCommitButton(btnCommit, nStaged);
        };

        conflictedList.setCellFactory(lv -> new FileRowCell(true, true, unstagedAll, stagedAll, updateCounts));
        unstagedList.setCellFactory(lv -> new FileRowCell(true, false, unstagedAll, stagedAll, updateCounts));
        stagedList.setCellFactory(lv -> new FileRowCell(false, false, unstagedAll, stagedAll, updateCounts));

        Button btnStageAll = new Button("Stage all");
        btnStageAll.getStyleClass().addAll("btn-ghost", "row-action");
        btnStageAll.setMinWidth(Region.USE_PREF_SIZE);
        btnStageAll.setDisable(!hasPlainUnstaged(state));
        btnStageAll.setOnAction(e -> {
            // Optimistic: move everything immediately. Everything in this section, that
            // is: a conflict is listed above it and marked resolved from there, where a
            // file still holding markers is asked about first.
            java.util.List<String> toMove = unstagedAll.stream()
                    .filter(path -> !stagingConflicts.contains(path)).toList();
            boolean everything = toMove.size() == unstagedAll.size();
            unstagedAll.removeAll(toMove);
            stagedAll.addAll(toMove);
            java.util.Collections.sort(stagedAll);
            updateCounts.run();
            Runnable revert = () -> {
                stagedAll.removeAll(toMove);
                unstagedAll.addAll(toMove);
                java.util.Collections.sort(unstagedAll);
                updateCounts.run();
            };
            // The lists moved already: this redraws nothing unless git disagrees.
            java.util.concurrent.atomic.AtomicReference<StagedAll> stagedNow =
                    new java.util.concurrent.atomic.AtomicReference<>();
            operations.operation("Staging all", OperationRunner.Refresh.NONE)
                    .onResult(result -> {
                        StagedAll done = stagedNow.get();
                        if (!result.isOk() || done == null) {
                            revert.run();
                        } else if (!stagingConflicts.containsAll(done.conflicts())) {
                            // Conflicts the panel has not seen - perhaps moved across as plain
                            // and left where they were: it is showing an older repository, so
                            // it reads this one.
                            showStagingArea();
                        } else if (unstagedAll.isEmpty() && stagedAll.isEmpty()) {
                            operations.refresh(OperationRunner.Refresh.EVERYTHING);
                        }
                    })
                    // Which paths are conflicted is asked again on the worker: the panel's
                    // answer is from its last refresh, and a merge started in a terminal
                    // since then left conflicts it lists as plain changes.
                    .run(() -> {
                        StagedAll done = stageExceptConflicts(everything ? null : toMove);
                        stagedNow.set(done);
                        return done.result();
                    });
        });

        Button btnUnstageAll = new Button("Unstage all");
        btnUnstageAll.getStyleClass().addAll("btn-ghost", "row-action");
        btnUnstageAll.setMinWidth(Region.USE_PREF_SIZE);
        btnUnstageAll.setDisable(staged.isEmpty());
        btnUnstageAll.setOnAction(e -> {
            java.util.List<String> toMove = new java.util.ArrayList<>(stagedAll);
            stagedAll.clear();
            unstagedAll.addAll(toMove);
            java.util.Collections.sort(unstagedAll);
            updateCounts.run();
            operations.operation("Unstaging all", OperationRunner.Refresh.NONE)
                    .onResult(result -> {
                        if (!result.isOk()) {
                            unstagedAll.removeAll(toMove);
                            stagedAll.addAll(toMove);
                            java.util.Collections.sort(stagedAll);
                            updateCounts.run();
                        }
                    })
                    .run(gitService::unstageAll);
        });

        // Path or Tree, for the working tree as well as for a commit. A commit's changed
        // files got this when FilesSection was built and the working tree did not, which
        // left the list you actually work in as the only one that could not be read as
        // folders - and it is the one that reaches seventy entries after a build touches
        // its own output.
        javafx.scene.control.TreeView<FileNode> unstagedTree = stagingTree(
                unstagedPlain, true, unstagedAll, stagedAll, updateCounts);
        javafx.scene.control.TreeView<FileNode> stagedTree = stagingTree(
                stagedAll, false, unstagedAll, stagedAll, updateCounts);

        StackPane unstagedArea = new StackPane(unstagedList, unstagedTree);
        StackPane stagedArea = new StackPane(stagedList, stagedTree);

        ToggleGroup shape = new ToggleGroup();
        ToggleButton pathMode = new ToggleButton("Path");
        pathMode.getStyleClass().addAll("composer-tab", "staging-path-toggle");
        pathMode.setToggleGroup(shape);
        ToggleButton treeMode = new ToggleButton("Tree");
        treeMode.getStyleClass().addAll("composer-tab", "staging-tree-toggle");
        treeMode.setToggleGroup(shape);
        // Always exactly one: clicking the selected half of a two-way switch must not
        // leave the panel showing neither view.
        shape.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) {
                shape.selectToggle(was);
            }
        });

        Runnable applyShape = () -> {
            boolean asTree = treeMode.isSelected();
            showingStagingTree = asTree;
            unstagedList.setVisible(!asTree);
            unstagedList.setManaged(!asTree);
            stagedList.setVisible(!asTree);
            stagedList.setManaged(!asTree);
            unstagedTree.setVisible(asTree);
            unstagedTree.setManaged(asTree);
            stagedTree.setVisible(asTree);
            stagedTree.setManaged(asTree);
        };
        pathMode.setOnAction(e -> applyShape.run());
        treeMode.setOnAction(e -> applyShape.run());
        shape.selectToggle(showingStagingTree ? treeMode : pathMode);
        applyShape.run();

        HBox shapeRow = new HBox(UiScale.TIGHT, pathMode, treeMode);
        shapeRow.setAlignment(Pos.CENTER_LEFT);

        VBox unstagedSection = new VBox(UiScale.SNUG,
                sectionHeader(lblUnstaged, countUnstaged, btnStageAll), unstagedArea);
        VBox stagedSection = new VBox(UiScale.SNUG,
                sectionHeader(lblStaged, countStaged, btnUnstageAll), stagedArea);

        // --- Commit box ---
        // Two fields joined by git's conventional blank line, rather than one box
        // where the convention lived only in the prompt text. Splitting is what makes
        // the summary limit enforceable and the description first-class.
        // The composer holds the text across rebuilds; the first box built for this
        // repository starts from the message left unsent when it was last open. Not in the
        // constructor, because the panel can be made before the repository it belongs to is.
        composer.restoreDraft();
        javafx.scene.control.TextField summaryField = new javafx.scene.control.TextField(composer.summary());
        summaryField.getStyleClass().addAll("composer-summary", "composer-message");
        summaryField.setPromptText("Summary");
        TextArea descriptionArea = new TextArea(composer.description());
        descriptionArea.getStyleClass().add("composer-message");
        descriptionArea.setPromptText("Description (optional)");
        descriptionArea.setPrefRowCount(3);
        descriptionArea.setWrapText(true);

        // What is left of the 72 characters most tools show of a subject, counting down.
        // A soft limit: past it the number goes negative and red, and nothing is blocked -
        // some projects' conventions run longer, and the commit is still theirs to make.
        Label counter = MessageEditor.counter(summaryField);
        // Whatever changes the text - typing, or the composer filling it in - goes back to
        // the composer, which keeps it as the draft unless it only filled it in itself.
        Runnable edited = () -> composer.edited(summaryField.getText(), descriptionArea.getText());
        summaryField.textProperty().addListener((o, was, val) -> edited.run());
        descriptionArea.textProperty().addListener((o, was, val) -> edited.run());

        // The messages committed here before, a click away as well as on Alt+Up.
        Button historyButton = Icons.button(Icons.history(), "Recent commit messages",
                "Recent commit messages (Alt+Up and Alt+Down step through them)");
        historyButton.getStyleClass().add("composer-history");
        historyButton.setOnAction(e -> showHistoryMenu(historyButton));

        HBox.setHgrow(summaryField, Priority.ALWAYS);
        HBox summaryRow = new HBox(UiScale.SNUG, summaryField, counter, historyButton);
        summaryRow.setAlignment(Pos.CENTER_LEFT);

        Region counterSpacer = new Region();
        HBox.setHgrow(counterSpacer, Priority.ALWAYS);
        // The description's placeholder already says what the body is for; a second
        // line under it saying the same thing again was noise in the one place the user
        // is trying to write.
        // Co-authors go into the description as trailers the user can see and edit, not
        // added behind their back at commit time: who gets credit is part of the message.
        Button coAuthor = new Button("Add co-author…");
        coAuthor.getStyleClass().addAll("btn-ghost", "row-action", "composer-co-author");
        coAuthor.setMinWidth(Region.USE_PREF_SIZE);
        coAuthor.setTooltip(new Tooltip("Adds a Co-authored-by: trailer, which GitHub and GitLab "
                + "use to credit a second author"));
        coAuthor.setOnAction(e -> addCoAuthor());
        HBox counterRow = new HBox(UiScale.STEP, coAuthor, counterSpacer);
        counterRow.setAlignment(Pos.CENTER_LEFT);

        CheckBox amend = new CheckBox("Amend");
        // Carried over rather than reset: the rebuild that follows staging a file is not
        // the user changing their mind about amending.
        amend.setSelected(composer.amend());

        // git commit --no-verify. Correct - not merely tolerated - on both engines:
        // JGit's CommitCommand runs a repository's real pre-commit/commit-msg hook
        // scripts when they exist (verified against JGit's own source) and exposes
        // setNoVerify(boolean) to skip them, the same primitive native git's flag maps
        // to. Unlike GPG signing this is not an engine limitation, so it is offered
        // unconditionally rather than gated to one engine.
        CheckBox skipHooks = new CheckBox("Skip hooks");
        // From this repository's default, for the commit being written; see CommitComposer
        // for how long it lasts.
        skipHooks.setSelected(composer.skipHooks());
        skipHooks.selectedProperty().addListener((o, was, now) -> composer.setSkipHooks(now));
        skipHooks.setTooltip(new Tooltip(
                "git commit --no-verify - skips the pre-commit and commit-msg hooks for this commit only"));

        // Ticking amend used to silently replace whatever had been typed with HEAD's
        // message. Naming the commit it will fold into makes that a choice.
        Label amendTarget = new Label();
        amendTarget.getStyleClass().add("amend-target");
        amendTarget.setMaxWidth(Double.MAX_VALUE);
        // Two checkboxes and the commit they would fold into, on one row, meant the
        // subject of the amend was squeezed into whatever was left after both boxes -
        // usually a few words and an ellipsis. The options are one decision and the
        // target is the evidence for it, so they get a line each.
        // git commit --signoff. Applied when committing rather than typed into the
        // description, so it follows whoever actually commits - including after a
        // profile switch - and is never duplicated; see CommitRequest.signedOffBy.
        CheckBox signOffBox = new CheckBox("Sign off");
        signOffBox.setSelected(composer.signOff());
        signOffBox.selectedProperty().addListener((o, was, now) -> composer.setSignOff(now));
        Tooltip signOffTip = new Tooltip("git commit --signoff - adds a Signed-off-by: trailer");
        signOffBox.setTooltip(signOffTip);
        // Who that would be, read on the worker: an includeIf identity needs git to answer.
        GitTaskRunner.query(gitService, gitService::getCommitIdentity,
                identity -> signOffTip.setText(identity == null
                        ? "git commit --signoff - no commit identity is configured, so this "
                          + "cannot sign off yet"
                        : "git commit --signoff - adds Signed-off-by: " + identity),
                error -> { });

        HBox amendOptions = new HBox(UiScale.GAP, amend, skipHooks, signOffBox);
        amendOptions.setAlignment(Pos.CENTER_LEFT);
        amendTarget.setMinWidth(0);
        amendTarget.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        VBox amendRow = new VBox(UiScale.TIGHT, amendOptions, amendTarget);

        // The composer drives these controls from here on. HEAD is unknown to them until
        // its read lands.
        ComposerControls controls = new ComposerControls(summaryField, descriptionArea, amend, skipHooks);
        composer.attach(controls);
        Runnable showAmendTarget = () -> {
            CommitComposer.AmendLine line = composer.amendLine();
            amendTarget.getStyleClass().remove("amend-target-published");
            amendTarget.setGraphic(null);
            // On screen only while Amend is ticked. It used to sit under the checkboxes
            // all the time, as a bare commit subject with nothing saying what it was: a
            // line of someone's last commit message loose in the commit box.
            amendTarget.setVisible(line.shown());
            amendTarget.setManaged(line.shown());
            amendTarget.setText(line.text());
            if (line.published()) {
                amendTarget.getStyleClass().add("amend-target-published");
                amendTarget.setGraphic(Icons.warning());
            }
        };
        showAmendTarget.run();
        // HEAD's message and whether HEAD is already on its upstream, read on the worker:
        // the amend listener used to call getHeadCommitMessage on the FX thread - a
        // subprocess on the default engine, on the thread that draws. Re-run by an in-place
        // refresh as well: a commit, an amend or a reword elsewhere moves HEAD without the
        // panel being rebuilt.
        Runnable refreshAmendTarget = () -> GitTaskRunner.query(gitService,
                this::readAmendTarget,
                info -> {
                    composer.amendTargetRead(info.message(), info.publishedTo());
                    showAmendTarget.run();
                    // The button says whether an amend will be pushed, which depends on this.
                    updateCommitButton(btnCommit, stagedAll.size());
                },
                error -> amendTarget.setText(""));
        refreshAmendTarget.run();

        // Ticking Amend over an empty box fills in HEAD's message, and unticking gives an
        // emptied summary its issue key back; the composer decides, this redraws.
        amend.selectedProperty().addListener((o, old, selected) -> {
            composer.setAmend(selected);
            showAmendTarget.run();
            updateCommitButton(btnCommit, stagedAll.size());
        });

        // Held so the window-level shortcuts drive the same controls the user can see.
        this.composerControls = controls;
        this.liveCommitButton = btnCommit;

        // The issue key first: the template and the prepared message build on it or replace it.
        composer.fillIn();

        btnCommit.setOnAction(e -> commitStaged());
        updateCommitButton(btnCommit, staged.size());

        // Ctrl+Enter commits, as in commercial clients, and Ctrl+Shift+Enter commits and pushes.
        // Both are also bound at window level so they work without the caret being in
        // either message field. Alt+Up and Alt+Down step through earlier messages.
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> composerKeys = e -> {
            if (e.isShortcutDown() && e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                press(e.isShiftDown());
                e.consume();
            } else if (e.isAltDown() && !e.isShortcutDown() && e.getCode() == javafx.scene.input.KeyCode.UP) {
                stepHistory(+1);
                e.consume();
            } else if (e.isAltDown() && !e.isShortcutDown() && e.getCode() == javafx.scene.input.KeyCode.DOWN) {
                stepHistory(-1);
                e.consume();
            }
        };
        summaryField.setOnKeyPressed(composerKeys);
        descriptionArea.setOnKeyPressed(composerKeys);

        VBox commitBody = new VBox(UiScale.STEP,
                summaryRow, descriptionArea, counterRow, amendRow, btnCommit);

        // The other thing this pile of changes can become. Committing and stashing are
        // the two ways to get a dirty working tree out of the way, they take the same
        // input - a name for the work - and commercial clients put them behind one tab strip in
        // this exact corner for that reason. Stashing lived only on a toolbar button
        // that saved everything under "WIP on main: <whatever HEAD was>", which is not a
        // name anyone can find their work by three days later.
        VBox stashBody = buildStashComposer(unstagedList, stagedList);

        // A StackPane takes the height of its tallest child, but only the visible one
        // counts once the other is unmanaged - so switching to Stash, which has fewer
        // controls, shrank the whole footer and moved the file list under the pointer.
        // Both stay managed and the hidden one keeps its space, so the box is the same
        // height whichever tab is showing and the lists above it do not move.
        StackPane composerBody = new StackPane(commitBody, stashBody);
        StackPane.setAlignment(commitBody, Pos.TOP_LEFT);
        StackPane.setAlignment(stashBody, Pos.TOP_LEFT);

        ToggleGroup composerChoice = new ToggleGroup();
        ToggleButton commitTab = new ToggleButton("Commit");
        commitTab.getStyleClass().addAll("composer-tab", "composer-tab-commit");
        commitTab.setToggleGroup(composerChoice);
        ToggleButton stashTab = new ToggleButton("Stash");
        stashTab.getStyleClass().addAll("composer-tab", "composer-tab-stash");
        stashTab.setToggleGroup(composerChoice);
        // A segmented control, so there is always exactly one composer: clicking the
        // selected half of a two-way switch must not leave the footer showing neither.
        composerChoice.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null && was != null) composerChoice.selectToggle(was);
        });

        Runnable applyComposer = () -> {
            boolean stashing = stashTab.isSelected();
            showingStashComposer = stashing;
            // Visibility only. Leaving both managed is what keeps the StackPane at the
            // height of the taller composer regardless of which one is on show.
            commitBody.setVisible(!stashing);
            stashBody.setVisible(stashing);
        };
        commitTab.setOnAction(e -> applyComposer.run());
        stashTab.setOnAction(e -> applyComposer.run());
        composerChoice.selectToggle(showingStashComposer ? stashTab : commitTab);
        applyComposer.run();

        HBox composerTabs = new HBox(UiScale.TIGHT, commitTab, stashTab);
        composerTabs.setAlignment(Pos.CENTER_LEFT);

        VBox commitBox = new VBox(UiScale.STEP, composerTabs, composerBody);
        commitBox.getStyleClass().add("composer");

        // --- Changed files / all files toggle ---
        // The two views answer different questions, so they share the panel rather than
        // sitting in separate places: "what did I touch" and "what is in here".
        ToggleButton allFiles = new ToggleButton("All files");
        allFiles.getStyleClass().addAll("composer-tab", "all-files-toggle");
        allFiles.setMinWidth(Region.USE_PREF_SIZE);
        allFiles.setSelected(showingWorkspaceTree);
        allFiles.setTooltip(new Tooltip(
                "Browse every file in the repository, not only the ones that changed"));

        // One filter over both lists. A commit's changed files got a filter and a
        // Path/Tree toggle when FilesSection was built; the working tree - the list you
        // actually work in, and the one that reaches seventy entries after a Gradle
        // build - got neither. Filtering both at once is right rather than a filter each:
        // "where is that file" is a question about the change, not about which side of
        // the index it happens to be on.
        javafx.scene.control.TextField stagingFilter = new javafx.scene.control.TextField();
        stagingFilter.getStyleClass().addAll("file-filter-field", "staging-filter");
        stagingFilter.setPromptText("Filter changed files…");
        HBox.setHgrow(stagingFilter, Priority.ALWAYS);

        Label stagingMatches = new Label();
        stagingMatches.getStyleClass().add("count-badge");

        stagingFilter.textProperty().addListener((obs, was, text) -> {
            String needle = text == null ? "" : text.strip().toLowerCase(java.util.Locale.ROOT);
            filterNeedle[0] = needle;
            applyFilters.run();
            int shown = conflictedShown.size() + unstagedShown.size() + stagedShown.size();
            stagingMatches.setText(needle.isEmpty()
                    ? "" : shown + (shown == 1 ? " match" : " matches"));
            show(stagingMatches, !needle.isEmpty());
        });
        show(stagingMatches, false);

        HBox stagingFilterBar = new HBox(UiScale.STEP,
                stagingFilter, stagingMatches, shapeRow);
        stagingFilterBar.setAlignment(Pos.CENTER_LEFT);

        VBox changedView = new VBox(UiScale.GAP,
                stagingFilterBar, conflictedSection, unstagedSection, stagedSection);
        VBox.setVgrow(changedView, Priority.ALWAYS);

        VBox workspaceView = new VBox(UiScale.SNUG);
        VBox.setVgrow(workspaceView, Priority.ALWAYS);

        StackPane fileArea = new StackPane(changedView, workspaceView);
        StackPane.setAlignment(changedView, Pos.TOP_LEFT);
        StackPane.setAlignment(workspaceView, Pos.TOP_LEFT);
        VBox.setVgrow(fileArea, Priority.ALWAYS);

        Runnable applyToggle = () -> {
            boolean tree = allFiles.isSelected();
            showingWorkspaceTree = tree;

            changedView.setVisible(!tree);
            changedView.setManaged(!tree);
            workspaceView.setVisible(tree);
            workspaceView.setManaged(tree);

            if (tree && workspaceView.getChildren().isEmpty()) {
                workspaceTree = new WorkspaceTreeView(operations, this::openWorkingTreeFile);
                VBox.setVgrow(workspaceTree, Priority.ALWAYS);
                Label header = new Label("Repository files");
                header.getStyleClass().add("section-title");

                // The Filter Files bar (§3.1): live substring match on file names,
                // with a match count and a one-click way out.
                javafx.scene.control.TextField filterField = new javafx.scene.control.TextField();
                // Named as well as styled. There are now three filter fields in this
                // panel - this one, the staging lists', and the changed-files one - and a
                // lookup for ".file-filter-field" finds whichever the scene graph offers
                // first, which is how a test aimed at this tree ended up typing into the
                // staging filter instead.
                filterField.getStyleClass().addAll("file-filter-field", "workspace-filter");
                filterField.setPromptText("Filter files...");
                Label matchCount = new Label("");
                matchCount.getStyleClass().add("count-badge");
                showOnlyWithText(matchCount);
                javafx.scene.control.Button clearFilter =
                        Icons.button(Icons.close(), "Clear the filter", "Clear the filter");
                clearFilter.getStyleClass().addAll("btn-sm", "row-action");
                clearFilter.setVisible(false);
                clearFilter.setManaged(false);
                Runnable runFilter = () -> {
                    String text = filterField.getText();
                    int matches = workspaceTree.applyFilter(text);
                    boolean active = text != null && !text.isBlank();
                    matchCount.setText(active ? matches + (matches == 1 ? " match" : " matches") : "");
                    clearFilter.setVisible(active);
                    clearFilter.setManaged(active);
                };
                filterField.textProperty().addListener((o, was, now) -> runFilter.run());
                clearFilter.setOnAction(e -> {
                    filterField.clear();
                    filterField.requestFocus();
                });
                Region filterSpacer = new Region();
                HBox.setHgrow(filterSpacer, Priority.ALWAYS);
                HBox filterBar = new HBox(UiScale.STEP, filterField, filterSpacer, matchCount, clearFilter);
                filterBar.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(filterField, Priority.ALWAYS);

                workspaceView.getChildren().addAll(header, filterBar, workspaceTree);
            }
            if (tree && workspaceTree != null) {
                // Carry the change marks over so the tree is not a plainer view.
                Set<String> changed = new java.util.HashSet<>(stagingState.unstaged());
                changed.addAll(stagingState.staged());
                workspaceTree.setChangedPaths(changed);
            }
        };
        allFiles.setOnAction(e -> applyToggle.run());

        // In the header, beside the name of what the panel is showing: it switches the
        // whole body between the changes and the repository's files. It used to have a
        // row to itself at the top of the body, pushed to the far end of it.
        panel.setHeaderAction(allFiles);

        panel.body().getChildren().add(fileArea);
        // Outside the padded body, so the composer runs to the panel's edges the way the
        // status bar does - it is a footer, not another item in the list.
        panel.setFooter(commitBox);
        applyToggle.run();
        updateCounts.run();

        // --- Diff wiring ---
        conflictedList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                unstagedList.getSelectionModel().clearSelection();
                stagedList.getSelectionModel().clearSelection();
                diffViewer.setEditable(true);
                clearActiveSelection = () -> conflictedList.getSelectionModel().clearSelection();
                diffViewer.setComparedSides(DiffViewer.DiffSides.UNSTAGED);
                diffViewer.setExternalDiffTarget(null);
                if (!keepsEditingSession(newVal)) {
                    // As for a conflict anywhere: its diff is the stages against each
                    // other, so no hunk can be staged or reverted. Resolving is the action.
                    showDiff(DiffKind.CONFLICT, newVal,
                            () -> gitService.getUnstagedDiffBytes(
                                    newVal, diffViewer.isIgnoreWhitespace()),
                            workingTreeImageSource(newVal));
                }
            }
        });
        unstagedList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                stagedList.getSelectionModel().clearSelection();
                conflictedList.getSelectionModel().clearSelection();
                boolean conflicted = stagingConflicts.contains(newVal);
                diffViewer.setEditable(true);
                clearActiveSelection = () -> unstagedList.getSelectionModel().clearSelection();
                diffViewer.setComparedSides(DiffViewer.DiffSides.UNSTAGED);
                // Not for a conflict: its "diff" is the stages against each other, and
                // the file holds markers, so there are no two versions to hand over.
                diffViewer.setExternalDiffTarget(conflicted
                        ? null : com.jfx.git.core.ExternalTools.DiffTarget::unstaged);
                if (!keepsEditingSession(newVal)) {
                    // An unmerged path's diff is not the file against the index - JGit
                    // gives one section per conflict stage, git a combined diff - so no
                    // hunk of it can be staged or reverted. Resolving is the action there.
                    showDiff(conflicted ? DiffKind.CONFLICT : DiffKind.UNSTAGED, newVal,
                            () -> gitService.getUnstagedDiffBytes(
                                    newVal, diffViewer.isIgnoreWhitespace()),
                            workingTreeImageSource(newVal));
                }
            }
        });
        stagedList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                unstagedList.getSelectionModel().clearSelection();
                conflictedList.getSelectionModel().clearSelection();
                diffViewer.setEditable(true);
                clearActiveSelection = () -> stagedList.getSelectionModel().clearSelection();
                if (!keepsEditingSession(newVal)) {
                    showStagedDiff(newVal);
                }
            }
        });

        liveUnstagedList = unstagedList;
        liveStagedList = stagedList;
        liveConflictedList = conflictedList;
        liveStagingFilter = stagingFilter;
        LiveStaging live = new LiveStaging();
        live.unstagedAll = unstagedAll;
        live.stagedAll = stagedAll;
        live.unstagedList = unstagedList;
        live.stagedList = stagedList;
        live.conflictedList = conflictedList;
        live.fileArea = fileArea;
        live.applyFilters = applyFilters;
        live.btnStageAll = btnStageAll;
        live.btnUnstageAll = btnUnstageAll;
        live.stageEverything = stageEverything;
        live.updateCounts = updateCounts;
        live.refreshAmendTarget = refreshAmendTarget;
        liveStaging = live;
        repaintStagingRows = () -> redrawStagingRows(
                conflictedList, unstagedList, stagedList, unstagedTree, stagedTree);
        stagingFilter.setText(before.filter());
        if (!restoreStagingSelection(before, conflictedList, unstagedList, stagedList)
                && !diffViewer.isEditing()) {
            // Nothing to go back to. An editing session is still left open: a save is
            // itself what triggers this rebuild, and closing the editor on the user's
            // own Ctrl+S is the opposite of what saving is for.
            diffViewer.setFileDiff("", "");
            diffViewer.setEditable(false);
        }

        // If the working tree is now completely clean (nothing staged, nothing modified)
        // AND there is no merge/cherry-pick/revert still waiting on a commit to finish
        // it, the WIP node needs to vanish from the graph. Trigger a full refresh so the
        // graph row is removed. This covers discard-all and abort-merge without requiring
        // every stage/unstage to pay the cost of a full history re-walk.
        //
        // The repository-state check matters: resolving a conflict by choosing one side
        // verbatim (or a hand-edit that happens to land back on HEAD's exact bytes)
        // leaves every one of these three lists empty even though the merge itself is
        // not done - MERGE_HEAD is still on disk and a commit is still needed. Without
        // it, this refresh fires every time this panel rebuilds during such a merge,
        // which reloads the graph, which reselects the WIP row, which rebuilds this
        // panel again - a self-sustaining refresh loop that repeatedly discards whatever
        // the panel - including a commit message the user was mid-typing - was showing.
        // Read from the state this panel was built from, never from the backend: this runs
        // on the FX thread on every rebuild, and hasUncommittedChanges() is a full
        // git status. StagingState has carried the answer since the round-4 work; this
        // was the one call site left asking twice.
        if (!state.hasUncommittedChanges()) {
            operations.refresh(OperationRunner.Refresh.EVERYTHING);
        }
    }

    /**
     * The stash half of the footer: a name for the work, and the button that saves it.
     *
     * <p>Split into a title and a description for the same reason the commit box is -
     * {@code git stash push -m} takes one string, and the list that string is read back
     * in shows only its first line, so the two are joined with the blank line that makes
     * the first line a subject.
     *
     * <p>Under the name, the three {@code stash push} options: untracked files ({@code
     * -u}, on as it always was), keeping what is staged ({@code --keep-index}), and only
     * the files selected in the two lists above ({@code -- <paths>}). The last two
     * exclude each other - see {@link StashOptions#problem()} - so ticking one unticks
     * the other rather than letting the stash fail.
     */
    private VBox buildStashComposer(ListView<String> unstagedList, ListView<String> stagedList) {
        var parts = splitCommitMessage(pendingStashMessage);

        javafx.scene.control.TextField titleField =
                new javafx.scene.control.TextField(parts.summary());
        titleField.getStyleClass().addAll("composer-summary", "composer-message", "stash-title");
        titleField.setPromptText("Stash title");

        TextArea descriptionField = new TextArea(parts.description());
        descriptionField.getStyleClass().add("composer-message");
        descriptionField.setPromptText("Description (optional)");
        descriptionField.setPrefRowCount(2);
        descriptionField.setWrapText(true);

        Runnable syncPending = () ->
                pendingStashMessage = joinedCommitMessage(titleField.getText(),
                        descriptionField.getText());
        titleField.textProperty().addListener((o, was, now) -> syncPending.run());
        descriptionField.textProperty().addListener((o, was, now) -> syncPending.run());

        CheckBox untracked = new CheckBox("Untracked");
        untracked.getStyleClass().add("stash-include-untracked");
        untracked.setSelected(stashIncludeUntracked);
        untracked.setTooltip(new Tooltip("Include untracked files (-u)"));
        CheckBox keepIndex = new CheckBox("Keep staged");
        keepIndex.getStyleClass().add("stash-keep-index");
        keepIndex.setSelected(stashKeepIndex);
        keepIndex.setTooltip(new Tooltip(
                "Stash everything, but leave what is staged staged (--keep-index)"));
        CheckBox onlySelected = new CheckBox("Selected only");
        onlySelected.getStyleClass().add("stash-only-selected");
        onlySelected.setSelected(stashOnlySelected && !stashKeepIndex);
        onlySelected.setTooltip(new Tooltip(
                "Stash only the files selected in the lists above"));
        javafx.scene.layout.FlowPane optionRow =
                new javafx.scene.layout.FlowPane(UiScale.STEP, UiScale.SNUG, untracked, keepIndex, onlySelected);

        Label hint = new Label();
        hint.getStyleClass().add("composer-hint");
        hint.setWrapText(true);

        // Read at the moment of stashing, and for the hint: the rows selected in either list.
        java.util.function.Supplier<List<String>> selectedPaths = () -> {
            Set<String> paths = new java.util.LinkedHashSet<>(
                    unstagedList.getSelectionModel().getSelectedItems());
            paths.addAll(stagedList.getSelectionModel().getSelectedItems());
            paths.remove(null);
            return List.copyOf(paths);
        };
        Runnable describe = () -> {
            stashIncludeUntracked = untracked.isSelected();
            stashKeepIndex = keepIndex.isSelected();
            stashOnlySelected = onlySelected.isSelected();
            int selected = selectedPaths.get().size();
            String what;
            if (onlySelected.isSelected()) {
                what = selected == 0
                        ? "Select the files to stash in the lists above."
                        : "Only the " + selected + (selected == 1 ? " selected file is" : " selected files are")
                                + " set aside" + (untracked.isSelected() ? "." : ", untracked ones excepted.");
            } else {
                what = "Everything not committed is set aside"
                        + (untracked.isSelected() ? ", including untracked files" : ", except untracked files")
                        + (keepIndex.isSelected() ? "; what is staged also stays staged." : ".");
            }
            hint.setText(what);
        };
        keepIndex.selectedProperty().addListener((_, _, now) -> {
            if (now) onlySelected.setSelected(false);
            describe.run();
        });
        onlySelected.selectedProperty().addListener((_, _, now) -> {
            if (now) keepIndex.setSelected(false);
            describe.run();
        });
        untracked.selectedProperty().addListener((_, _, _) -> describe.run());
        javafx.collections.ListChangeListener<String> selectionChanged = _ -> describe.run();
        unstagedList.getSelectionModel().getSelectedItems().addListener(selectionChanged);
        stagedList.getSelectionModel().getSelectedItems().addListener(selectionChanged);
        describe.run();

        Runnable stash = () -> {
            String message = joinedCommitMessage(titleField.getText(), descriptionField.getText());
            List<String> paths = onlySelected.isSelected() ? selectedPaths.get() : List.of();
            if (onlySelected.isSelected() && paths.isEmpty()) {
                statusBar.error("Select the files to stash, or untick \"Selected only\"");
                return;
            }
            StashOptions options = new StashOptions(message, untracked.isSelected(),
                    keepIndex.isSelected(), paths);
            operations.operation("Stashing changes", OperationRunner.Refresh.EVERYTHING)
                    // A stash that failed left the tree and the stash list as they were.
                    .refreshWhen(GitResult::isOk)
                    // Only on success: a stash that failed because there was nothing to
                    // save must not also throw away the name typed for it.
                    .onSuccess(result -> {
                        pendingStashMessage = "";
                        titleField.clear();
                        descriptionField.clear();
                    })
                    .run(() -> gitService.stash(options));
        };
        // Reachable from the keyboard as well as the button; see submitComposer().
        this.stashAction = stash;

        Button btnStash = new Button("Stash changes");
        btnStash.getStyleClass().add("btn-primary");
        btnStash.setMaxWidth(Double.MAX_VALUE);
        btnStash.setOnAction(e -> stash.run());

        return new VBox(UiScale.STEP, titleField, descriptionField, optionRow, hint, btnStash);
    }

    /**
     * What the folder tallies put in front of their counts.
     *
     * <p>Modified is a tilde - "changed, roughly this many" - beside the plus and minus
     * the added and deleted counts already use. It was a pencil character, which is an
     * icon typed as text: drawn by whichever font had it, and already mangled once into
     * mojibake by an editor's idea of the file's encoding.
     */
    private static final String MODIFIED_MARK = "~";
    private static final String MINUS = "−";

    /**
     * Line counts for the uncommitted work currently on screen, keyed by path.
     *
     * <h2>Beside the list rather than inside it</h2>
     * The staging lists hold plain paths, because staging moves a path between two
     * observable lists and every filter, tree and drag target is built on that. Widening
     * the element type to carry two integers would have rewritten all of it for a pair of
     * labels. A lookup beside the list gets the same rows drawn with the same numbers and
     * leaves the model alone.
     *
     * <p>A path that is absent has no known count - a binary file, or an engine that
     * cannot report them - and its row shows no numbers at all, rather than a {@code +0}
     * that would claim the file changed by nothing.
     */
    private java.util.Map<String, int[]> unstagedLineCounts = java.util.Map.of();
    private java.util.Map<String, int[]> stagedLineCounts = java.util.Map.of();

    /**
     * Re-reads both sides for the panel on screen.
     *
     * <p>Held as a field because staging a file changes both answers at once: the file
     * leaves the working-tree diff and joins the index one, and the new staged count is
     * not the old unstaged count - a file with staged work already in it gains the sum.
     * Rather than guess at the arithmetic, the two rows that moved ask git again.
     */
    private Runnable reloadLineCounts = () -> { };

    /** True while a re-read is already queued for the end of this pulse. */
    private boolean lineCountReloadPending;

    /**
     * Queues at most one re-read per pulse, after everything else in the pulse.
     *
     * <h2>Why this is enough to be correct</h2>
     * Staging a file mutates the two backing lists several times - remove, add, sort -
     * and each of those would otherwise queue its own query. Coalescing to one per pulse
     * also puts the read after the click handler has submitted its write, and reads and
     * writes share a single-threaded repository lane, so the query cannot overtake the
     * write it is meant to observe. That ordering is what lets one hook on the lists
     * replace a call at every site that stages, unstages, discards or drops a file.
     */
    private void scheduleLineCountReload() {
        if (lineCountReloadPending) {
            return;
        }
        lineCountReloadPending = true;
        javafx.application.Platform.runLater(() -> {
            lineCountReloadPending = false;
            reloadLineCounts.run();
        });
    }

    /** Both sides of the answer, gathered in one trip to the repository lane. */
    private record LineCounts(java.util.Map<String, int[]> unstaged,
                              java.util.Map<String, int[]> staged) {
    }

    /**
     * What an empty list says instead of being an empty box.
     *
     * <p>Quiet on purpose: this is the normal state of the staged list most of the time,
     * so it explains without competing with the list that does have something in it.
     */
    private static Label placeholderLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("t-caption");
        label.setWrapText(true);
        return label;
    }

    /**
     * Gives a list exactly the height its rows need, up to a cap.
     *
     * <p>The two lists used to split the panel evenly whatever they held, so three
     * unstaged files and nothing staged produced two half-height boxes, one of them empty.
     */
    private static void sizeToContent(ListView<?> list, int rows) {
        double wanted = Math.max(1, rows) * UiScale.ROW_HEIGHT + 2;
        list.setPrefHeight(Math.min(wanted, UiScale.SECTION_MAX_HEIGHT));
        list.setMinHeight(Math.min(wanted, UiScale.ROW_HEIGHT * 2.0));
    }

    /**
     * Shows what the composer says committing will do, with {@code stagedCount} files staged;
     * see {@link CommitComposer#button}.
     *
     * <p>Nothing here asks the backend. This runs on the FX thread on every count change -
     * each optimistic stage click, each selection, each cell refresh - and it used to ask
     * for the branch and then run a full {@code git status} to answer "is there anything to
     * commit?". On the default engine that is two subprocesses per click, on the thread
     * that draws; both answers were already in the {@link StagingState} that built the panel.
     */
    private void updateCommitButton(Button button, int stagedCount) {
        CommitComposer.CommitButton decided = composer.button(stagedCount);
        button.setText(decided.label());
        button.setDisable(!decided.enabled());
    }

    private HBox sectionHeader(Label title, Label count, Button action) {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(UiScale.STEP, title, count, spacer, action);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * Shows or hides the detached-HEAD warning above whatever the panel is showing.
     *
     * <p>Driven from {@code RepoTab.refreshAll} rather than from panel rebuilds: HEAD
     * only changes through operations that refresh everything (checkout, undo, commit,
     * branch creation), while the panel body is rebuilt on far weaker events — and the
     * staging view does not even exist when the tree is clean, which is exactly when
     * the warning matters most.
     *
     * <p>Safe to call from anywhere on the FX thread, as often as refreshing happens.
     */
    public void updateDetachedHeadBanner() {
        final long request = ++bannerRequestId;
        GitTaskRunner.query(gitService, () -> gitService.isHeadDetached(), detached -> {
            if (request != bannerRequestId) {
                return; // a newer checkout answered already
            }
            var slot = panel.bannerSlot();
            if (detached) {
                if (detachedBanner == null) {
                    detachedBanner = buildDetachedHeadBanner();
                }
                // Added, not setAll: the bisect banner can be showing in the same slot
                // at once, and replacing every child would silently remove it.
                if (!slot.getChildren().contains(detachedBanner)) {
                    slot.getChildren().add(0, detachedBanner);
                }
            } else {
                slot.getChildren().remove(detachedBanner);
            }
        }, error -> { });
    }

    /**
     * The detached-HEAD warning (§3.4): commits made here land on no branch and are
     * one checkout away from being unreachable. The button is the recovery gesture —
     * branch this exact commit, which also re-attaches HEAD.
     */
    private VBox buildDetachedHeadBanner() {
        Label label = new Label("You are in a detached HEAD state. Commits you make here"
                + " are not on any branch and can be lost.");
        label.getStyleClass().add("conflict-banner");
        label.setGraphic(Icons.warning());
        label.setWrapText(true);

        Button createBranch = new Button("Create branch here");
        // Where HEAD is and which names are taken, read on the lane before asking.
        createBranch.setOnAction(e -> GitTaskRunner.query(gitService, () -> RepoToolbar.Head.read(gitService),
                head -> RepoToolbar.branchPrompt("Create branch here", head.branches())
                        .title(head.hash() == null ? "Create a branch here"
                                : "Create a branch at `" + com.jfx.git.core.GitBackend.shortenHash(head.hash()) + "`")
                        .subtitle("The commit checked out now, on no branch")
                        .hint("HEAD moves onto the new branch, so what you commit from here is kept on it.")
                        .action("Create and check out")
                        .ask()
                        .map(String::trim)
                        // Checked out, so HEAD moves: the banner, the graph and the sidebar
                        // all follow, not only the staging panel.
                        .ifPresent(name -> operations.run("Creating branch " + name,
                                OperationRunner.Refresh.EVERYTHING, () -> gitService.createBranch(name, null, true))),
                statusBar::error));

        VBox box = new VBox(6, label, createBranch);
        box.setPadding(new Insets(0, 0, 8, 0));
        return box;
    }

    /**
     * Shows or hides the LFS warning, matching {@link #updateDetachedHeadBanner()}'s
     * shape - both are cheap-to-query, rarely-changing facts about the repository as a
     * whole, not about anything the panel body itself displays.
     *
     * <p>Every LFS-tracked path's committed blob is the pointer text; only a registered
     * smudge filter turns a checkout of it into real content. Whichever engine is
     * active right now not being able to do that (see {@link
     * com.jfx.git.core.GitBackend#canMaterializeLfsContent()}) is not merely "shows
     * pointers instead of files" - if real content was already materialised on disk by
     * an earlier native checkout or an external {@code git lfs pull}, the *next*
     * checkout under an engine that cannot smudge overwrites it back to a pointer. The
     * risk is the same whether that engine is JGit (which can never smudge in this
     * codebase) or native git without {@code git lfs install} having run anywhere on
     * the machine (recoverable, but exactly as clobber-prone until it does) - so the
     * check is against the capability, not against which engine happens to be active.
     */
    public void updateLfsBanner() {
        final long request = ++lfsBannerRequestId;
        GitTaskRunner.query(gitService,
                () -> !gitService.canMaterializeLfsContent()
                        && new RepositoryCommands(gitService).hasAnyLfsTrackedPaths(),
                atRisk -> {
                    if (request != lfsBannerRequestId) {
                        return; // a newer refresh (or an engine switch) answered already
                    }
                    var slot = panel.bannerSlot();
                    if (atRisk) {
                        if (lfsBanner == null) {
                            lfsBanner = buildLfsBanner();
                        }
                        if (!slot.getChildren().contains(lfsBanner)) {
                            slot.getChildren().add(lfsBanner);
                        }
                    } else {
                        slot.getChildren().remove(lfsBanner);
                    }
                }, error -> { });
    }

    /**
     * The LFS warning: this engine cannot smudge, so a checkout here writes (or leaves,
     * or re-writes over already-real content) pointer stubs for every LFS-tracked path.
     */
    private VBox buildLfsBanner() {
        Label label = new Label("This repository uses Git LFS, but the active engine"
                + " cannot fetch or preserve real LFS content on checkout. Tracked files may"
                + " appear as small pointer stubs, and any real content already on disk could"
                + " be overwritten with one. See Settings → Git Engine (Ctrl+,) for details.");
        label.getStyleClass().add("conflict-banner");
        label.setGraphic(Icons.warning());
        label.setWrapText(true);

        // The one cause that can be fixed from here: git-lfs is on the machine and was
        // never set up. On an engine that cannot run it the answer says so instead.
        Button setUp = new Button("Set up LFS here");
        setUp.getStyleClass().add("btn-sm");
        setUp.setOnAction(e -> operations.operation("Setting up Git LFS", OperationRunner.Refresh.NONE)
                .afterwards(result -> updateLfsBanner())
                .run(gitService::lfsInstall));

        VBox box = new VBox(UiScale.SNUG, label, setUp);
        box.setPadding(new Insets(0, 0, 8, 0));
        return box;
    }

    // ------------------------------------------------------------------
    // Git LFS on a file
    // ------------------------------------------------------------------

    /**
     * Who holds an LFS lock on which path, as last asked. Asking is a round trip to the
     * LFS server, so it happens when a lock is taken or released here, or on request -
     * never on a refresh, where it would put a network call behind every staging click.
     */
    private java.util.Map<String, String> lfsLocks = java.util.Map.of();

    /** Whether git-lfs is installed; found out once, off the FX thread. */
    private volatile boolean lfsAvailable;

    private void refreshLfsLocks() {
        operations.read("Reading LFS locks", gitService::lfsLocks, locks -> {
            java.util.Map<String, String> byPath = new java.util.LinkedHashMap<>();
            for (com.jfx.git.model.LfsLock lock : locks) {
                byPath.put(lock.path(), lock.owner());
            }
            lfsLocks = byPath;
            statusBar.info(locks.isEmpty() ? "No files are locked" : locks.size() + " locked file(s)");
            showStagingArea();
        });
    }

    /** Runs a lock or unlock, then re-reads the locks so the list shows what the server now says. */
    private void changeLfsLock(java.util.function.Supplier<GitResult> change, String description) {
        operations.operation(description, OperationRunner.Refresh.NONE)
                .onSuccess(result -> refreshLfsLocks())
                .run(change);
    }

    /**
     * Shows or hides the bisect banner, matching {@link #updateDetachedHeadBanner()}'s
     * shape - the same "query state, guard against a stale answer, toggle a slot child"
     * pattern, once per {@link BisectStatus} field this
     * needs to react to.
     *
     * <p>Rebuilt rather than toggled like the detached-HEAD banner: that one either
     * shows or does not, but this one's message and which buttons apply both change
     * with every mark, so there is no single static node to reveal.
     */
    public void updateBisectBanner() {
        // The stopped-operation banner answers to the same refresh: both describe what the
        // repository is in the middle of, and both change only when HEAD or the state
        // files do.
        updateOperationBanner();
        final long request = ++bisectBannerRequestId;
        GitTaskRunner.query(gitService, () -> gitService.bisectStatus(), status -> {
            if (request != bisectBannerRequestId) {
                return; // a newer mark/skip/reset already answered
            }
            var slot = panel.bannerSlot();
            if (bisectBanner != null) {
                slot.getChildren().remove(bisectBanner);
            }
            if (status.active()) {
                bisectBanner = buildBisectBanner(status);
                slot.getChildren().add(bisectBanner);
            } else {
                bisectBanner = null;
            }
        }, error -> { });
    }

    /**
     * The live bisect status: what to test next (or the answer), and the marks that
     * apply to whatever is on screen right now.
     *
     * <p>Good/Bad/Skip only appear once a real candidate is checked out - marking
     * "HEAD" good or bad before the range narrows to something testable would apply
     * to whatever the working tree happened to be sitting on, not a deliberate choice.
     */
    private VBox buildBisectBanner(BisectStatus status) {
        Label label = new Label(status.message());
        label.getStyleClass().add("conflict-banner");
        label.setGraphic(Icons.warning());
        label.setWrapText(true);

        HBox buttons = new HBox(6);
        if (!status.done() && status.currentCommitHash() != null) {
            Button good = new Button("Mark good");
            good.setOnAction(e -> runBisectAndRefresh(
                    () -> gitService.bisectMarkGood(status.currentCommitHash()), "Marking good"));

            Button bad = new Button("Mark bad");
            bad.setOnAction(e -> runBisectAndRefresh(
                    () -> gitService.bisectMarkBad(status.currentCommitHash()), "Marking bad"));

            Button skip = new Button("Skip");
            skip.setOnAction(e -> runBisectAndRefresh(gitService::bisectSkip, "Skipping"));

            // git bisect run: the tedium a bisect is made of - check out, build, test,
            // click - is exactly what a script is for, and anyone bisecting a real
            // regression has already written the test command down.
            Button runScript = new Button("Run script…");
            runScript.setTooltip(new javafx.scene.control.Tooltip(
                    "Test each candidate automatically with a command of your own"));
            runScript.setOnAction(e -> viewHost.open(com.jfx.git.ui.views.BisectRunView.class,
                    () -> new com.jfx.git.ui.views.BisectRunView(operations)));

            buttons.getChildren().addAll(good, bad, skip, runScript);
        }

        Button reset = new Button(status.done() ? "Done - reset" : "End bisect");
        // Checks the original branch out again: the whole tab follows, banner included.
        reset.setOnAction(e -> operations.run("Ending bisect", OperationRunner.Refresh.EVERYTHING,
                gitService::bisectReset));
        buttons.getChildren().add(reset);

        VBox box = new VBox(6, label, buttons);
        box.setPadding(new Insets(0, 0, 8, 0));
        return box;
    }

    /** Like {@link #runAndRefresh}, but for the mark/skip calls that return a {@code BisectStatus}. */
    private void runBisectAndRefresh(java.util.function.Supplier<BisectStatus> work,
                                     String description) {
        // Marking good/bad/skip checks out a different commit - the graph, the sidebar
        // and this banner all need to see the new HEAD, not just the panel.
        operations.operation(description, OperationRunner.Refresh.EVERYTHING).start(steps ->
                steps.read(work, status -> steps.finish(GitResult.ok(status.message()))));
    }

    /**
     * What the in-progress banner shows, gathered in one worker pass.
     *
     * @param autoStash the auto-stash holding the user's changes from before this merge
     *                  stopped, or {@code null}; see {@link com.jfx.git.core.AutoStash}
     * @param headline the operation in words with its refs named, or null with nothing
     *                 stopped
     */
    private record OperationBannerState(com.jfx.git.model.OperationState operation, int conflicts,
                                        com.jfx.git.model.StashEntry autoStash,
                                        OperationHeadline headline) {
    }

    /**
     * What the in-progress banner was last built from, for the graph's strip.
     *
     * @param headline null when nothing is stopped - nothing in progress, or a bisect
     */
    public record OperationSummary(com.jfx.git.model.OperationState operation, int conflicts,
                                   OperationHeadline headline) {
        /** Whether there is a stopped operation to show. */
        public boolean stopped() {
            return headline != null;
        }
    }

    /**
     * Tells {@code handler} of every operation-state answer the in-progress banner accepts,
     * on the FX thread - the same refresh, so the graph's strip and this banner cannot
     * disagree about whether anything is stopped.
     */
    public void setOnOperationState(java.util.function.Consumer<OperationSummary> handler) {
        this.onOperationState = handler == null ? summary -> { } : handler;
    }

    /** A headline resolved on the worker, and the state it was resolved for. */
    private record HeadlineCache(com.jfx.git.model.OperationState operation, OperationHeadline headline) {
    }

    /**
     * The headline with its refs looked up, on the repository lane: a hash named by the
     * branch whose tip it is - a merge given a message of its own, a rebase onto a commit
     * - and each name drawn as the kind of ref it is. Reused while the state is unchanged.
     */
    private OperationHeadline resolveHeadline(com.jfx.git.model.OperationState operation) {
        HeadlineCache cached = headlineCache;
        if (cached != null && cached.operation().equals(operation)) {
            return cached.headline();
        }
        List<com.jfx.git.model.RefInfo> local = List.of();
        List<com.jfx.git.model.RefInfo> remote = List.of();
        List<com.jfx.git.model.RefInfo> tags = List.of();
        try {
            local = gitService.getLocalBranches();
            remote = gitService.getRemoteBranches();
            tags = gitService.getTags();
        } catch (RuntimeException unreadable) {
            // Names are a nicety: the hashes still say what and where.
        }
        var kind = operation.kind();
        // A cherry-pick or a revert is of a commit, whatever branch happens to end there.
        boolean incomingIsCommit = kind == com.jfx.git.model.OperationState.Kind.CHERRY_PICKING
                || kind == com.jfx.git.model.OperationState.Kind.REVERTING;
        OperationHeadline.Ref incoming = headlineRef(operation.incoming(), !incomingIsCommit, false,
                local, remote, tags);
        // Where a merge, pick or revert lands is HEAD; a detached HEAD stays a commit.
        OperationHeadline.Ref target = headlineRef(operation.target(), operation.isRebase(),
                !operation.isRebase(), local, remote, tags);
        OperationHeadline headline = OperationHeadline.of(operation, incoming, target);
        headlineCache = new HeadlineCache(operation, headline);
        return headline;
    }

    /**
     * One ref for the headline: a hash named after a branch at it when {@code nameCommits},
     * a name drawn as a local branch, remote branch or tag, whichever it is.
     */
    private static OperationHeadline.Ref headlineRef(String value, boolean nameCommits, boolean head,
                                                     List<com.jfx.git.model.RefInfo> local,
                                                     List<com.jfx.git.model.RefInfo> remote,
                                                     List<com.jfx.git.model.RefInfo> tags) {
        if (value == null || value.isBlank()) return null;
        if (com.jfx.git.model.OperationState.isHash(value)) {
            if (nameCommits) {
                for (var ref : local) {
                    if (value.equals(ref.targetHash())) return new OperationHeadline.Ref(ref.shortName(),
                            head ? OperationHeadline.RefKind.HEAD : OperationHeadline.RefKind.LOCAL);
                }
                for (var ref : remote) {
                    if (value.equals(ref.targetHash())) {
                        return new OperationHeadline.Ref(ref.shortName(), OperationHeadline.RefKind.REMOTE);
                    }
                }
            }
            return new OperationHeadline.Ref(value, OperationHeadline.RefKind.COMMIT);
        }
        if (head) return new OperationHeadline.Ref(value, OperationHeadline.RefKind.HEAD);
        if (local.stream().anyMatch(ref -> value.equals(ref.shortName()))) {
            return new OperationHeadline.Ref(value, OperationHeadline.RefKind.LOCAL);
        }
        if (remote.stream().anyMatch(ref -> value.equals(ref.shortName()))) {
            return new OperationHeadline.Ref(value, OperationHeadline.RefKind.REMOTE);
        }
        if (tags.stream().anyMatch(ref -> value.equals(ref.shortName()))) {
            return new OperationHeadline.Ref(value, OperationHeadline.RefKind.TAG);
        }
        return new OperationHeadline.Ref(value, OperationHeadline.RefKind.LOCAL);
    }

    /**
     * Starts the Resolved tags again when the operation ends or moves on - a merge
     * finished, a rebase stopped at its next step - keeping what is conflicted now.
     */
    private void trackOperation(com.jfx.git.model.OperationState operation) {
        String key = operation.inProgress() ? operation.kind() + ":" + operation.stoppedAt() : "";
        if (key.equals(operationKey)) return;
        operationKey = key;
        conflictedThisOperation.clear();
        if (operation.inProgress()) {
            conflictedThisOperation.addAll(stagingConflicts);
        }
        // An offer to resolve the next file belongs to the operation it was made in.
        withdrawNextConflictOffer();
        repaintStagingRows.run();
    }

    /**
     * Shows or hides the in-progress banner - a merge, rebase, cherry-pick, revert or
     * {@code git am} that stopped and is waiting for the user - in the same slot and with
     * the same stale-answer guard as the other banners.
     *
     * <p>Driven from the same full refresh as the bisect banner (see
     * {@link #updateBisectBanner()}) and from staging refreshes, which can change the
     * conflict count without moving HEAD. A rebase stopped at an {@code edit} step, or at
     * a commit that became empty, has a clean tree - no conflicts, often no staging view
     * at all - so the full refresh still owns discovering those stops.
     */
    public void updateOperationBanner() {
        updateOperationBanner(null);
    }

    /**
     * Refreshes the stopped-operation banner after the staging query changed the index.
     * The conflict count comes from that same status result; operation-state I/O still
     * runs on the repository worker, never on the FX thread.
     */
    private void updateOperationBanner(StagingState staging) {
        final long request = ++operationBannerRequestId;
        GitTaskRunner.query(gitService, () -> {
            var operation = gitService.getOperationState();
            int conflicts = !operation.inProgress() ? 0
                    : staging == null ? gitService.getConflictingFiles().size()
                    : staging.conflicting().size();
            // A merge stays on the commit it started from, which is where a stash-and-merge
            // took its stash: the one place left that says where the user's changes went.
            var autoStash = operation.kind() == com.jfx.git.model.OperationState.Kind.MERGING
                    ? com.jfx.git.core.AutoStash.stashKeptAt(gitService.getStashEntries(), gitService.getHeadHash())
                    : null;
            var headline = operation.inProgress() ? resolveHeadline(operation) : null;
            return new OperationBannerState(operation, conflicts, autoStash, headline);
        }, state -> {
            if (request != operationBannerRequestId) {
                return; // a newer refresh answered already
            }
            composer.operationRead(state.operation());
            trackOperation(state.operation());
            if (liveCommitButton != null && liveStaging != null) {
                updateCommitButton(liveCommitButton, liveStaging.stagedAll.size());
            }
            var slot = panel.bannerSlot();
            if (operationBanner != null) {
                slot.getChildren().remove(operationBanner);
                operationBanner = null;
            }
            // Bisect has its own banner with its own buttons.
            var operation = state.operation();
            boolean stopped = operation.inProgress()
                    && operation.kind() != com.jfx.git.model.OperationState.Kind.BISECTING;
            if (stopped) {
                operationBanner = buildOperationBanner(operation, state.conflicts(), state.autoStash(),
                        state.headline());
                slot.getChildren().add(0, operationBanner);
            }
            onOperationState.accept(new OperationSummary(operation, state.conflicts(),
                    stopped ? state.headline() : null));
        }, error -> { });
    }

    /**
     * The banner for a stopped operation: what it is, where it stopped, and the buttons
     * that apply to it - Continue, Skip and Abort, each running that operation's own
     * command on every engine.
     *
     * @param autoStash the auto-stash holding the user's changes from before this merge
     *                  stopped, named so they are not forgotten there; or null
     */
    private VBox buildOperationBanner(com.jfx.git.model.OperationState operation, int conflicts,
                                      com.jfx.git.model.StashEntry autoStash, OperationHeadline headline) {
        String what = operation.label();
        String advice = OperationHeadline.advice(operation, conflicts);
        if (autoStash != null) {
            advice += " Your own changes from before it are in the stash (" + autoStash.selector()
                    + "): pop it once the merge is finished.";
        }
        Label label = new Label(advice);
        label.getStyleClass().add("conflict-banner");
        label.setGraphic(Icons.warning());
        label.setWrapText(true);
        // All its lines, always: the panel is short of height whenever the lists are long,
        // and a VBox short of height squeezed this to one line ending in "mark them ...".
        label.setMinHeight(Region.USE_PREF_SIZE);

        HBox buttons = new HBox(6);
        if (operation.canContinue()) {
            Button cont = new Button(operation.kind() == com.jfx.git.model.OperationState.Kind.MERGING
                    ? "Commit merge" : "Continue");
            cont.setDisable(conflicts > 0);
            cont.setOnAction(e -> continueOperation("Continuing " + what.toLowerCase(java.util.Locale.ROOT)));
            buttons.getChildren().add(cont);
        }
        if (operation.canSkip()) {
            Button skip = new Button("Skip");
            skip.setTooltip(new Tooltip("Leave this commit out and carry on with the rest"));
            skip.setOnAction(e -> runOperationAndRefresh(gitService::skipOperation,
                    "Skipping the stopped commit"));
            buttons.getChildren().add(skip);
        }
        if (operation.canAbort()) {
            // "Abort merge", as the command is: "Abort merging" read as a description, not a verb.
            Button abort = new Button("Abort " + operationNoun(operation));
            abort.setOnAction(e -> {
                String noun = operationNoun(operation);
                // Enter aborts, as it always has: what goes is the operation's own progress,
                // and the operation can be started again from the same place.
                if (com.jfx.git.ui.dialogs.Dialogs.destructive("Abort " + noun)
                        .icon(Icons.undo())
                        .title("Abort the " + noun + "?")
                        .subtitle(headline != null ? headline.text()
                                : operation.stoppedAt() == null ? null
                                : (operation.kind() == com.jfx.git.model.OperationState.Kind.MERGING
                                        ? "Merging `" : "Stopped on `")
                                        + com.jfx.git.core.GitBackend.shortenHash(operation.stoppedAt()) + "`")
                        .message("The changes it made are discarded and you go back to where it started;"
                                + " conflicts you resolved are lost with them. Your other local changes are kept.")
                        .action("Abort " + noun)
                        .ask()) {
                    runOperationAndRefresh(gitService::abortOperation,
                            "Aborting " + what.toLowerCase(java.util.Locale.ROOT));
                }
            });
            buttons.getChildren().add(abort);
        }

        VBox box = new VBox(6);
        if (headline != null) {
            box.getChildren().add(headlineRow(headline));
        }
        box.getChildren().addAll(label, buttons);
        box.getStyleClass().add("operation-banner");
        box.setPadding(new Insets(0, 0, 8, 0));
        return box;
    }

    /**
     * "Merging [feature/simple] into [main]": the words, and the refs as the graph rail's
     * own chips, wrapping onto a second line rather than eliding a branch name.
     */
    private static javafx.scene.Node headlineRow(OperationHeadline headline) {
        StackingRow.Flow row = new StackingRow.Flow(UiScale.SNUG, UiScale.TIGHT);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("operation-headline");
        row.getChildren().add(headlineWord(headline.verb()));
        if (headline.subject() != null) {
            row.getChildren().add(refChip(headline.subject()));
        }
        if (headline.target() != null) {
            row.getChildren().addAll(headlineWord(headline.preposition()), refChip(headline.target()));
        }
        if (headline.progress() != null) {
            row.getChildren().add(headlineWord(headline.progress()));
        }
        row.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
        row.setAccessibleText(headline.text());
        row.setMinHeight(Region.USE_PREF_SIZE);
        return row;
    }

    private static Label headlineWord(String text) {
        Label word = new Label(text);
        word.getStyleClass().add("operation-headline-text");
        word.setMinWidth(Region.USE_PREF_SIZE);
        return word;
    }

    /** One ref of a headline, as a chip the graph rail would draw for it. */
    private static Label refChip(OperationHeadline.Ref ref) {
        Label chip = new Label(ref.label());
        chip.getStyleClass().addAll("ref-chip", ref.styleClass());
        chip.setTooltip(new Tooltip(ref.kind() == OperationHeadline.RefKind.COMMIT
                ? "Commit " + ref.name() : ref.name()));
        chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }

    /** The operation as a thing that can be aborted: "merge", "interactive rebase", "cherry-pick". */
    private static String operationNoun(com.jfx.git.model.OperationState operation) {
        return switch (operation.kind()) {
            case MERGING -> "merge";
            case REBASING, REBASING_APPLY -> operation.interactive() ? "interactive rebase" : "rebase";
            case CHERRY_PICKING -> "cherry-pick";
            case REVERTING -> "revert";
            case APPLYING_MAILBOX -> "patch application";
            default -> operation.label().toLowerCase(java.util.Locale.ROOT);
        };
    }

    /**
     * The banner's Continue - "Commit merge", for a merge: commits what is staged and
     * carries on, so the staged files are looked over for conflict markers first, as the
     * composer's commit looks them over. Only for markers: a rebase or a cherry-pick
     * replays commits that are already history, and a secret or a large file in one was
     * there before this; the markers are the resolution's own.
     */
    private void continueOperation(String description) {
        operations.read(description,
                () -> com.jfx.git.core.CommitChecks.run(gitService,
                        new com.jfx.git.core.CommitChecks.Options(false, false, 0, true)),
                findings -> {
                    if (!findings.isEmpty() && new com.jfx.git.ui.dialogs.CommitChecksDialog(findings,
                            lfsAvailable, RightPanelManager::lfsPatternFor).showAndWait().isEmpty()) {
                        statusBar.info("Nothing was committed");
                        return;
                    }
                    // Marker findings offer nothing to do but go on, so there is no
                    // resolution to apply first.
                    runOperationAndRefresh(gitService::continueOperation, description);
                });
    }

    /**
     * Like {@link #runAndRefresh}, but refreshing everything: continuing, skipping or
     * aborting moves HEAD, so the graph, the sidebar and the banners all change - not
     * just the staging panel.
     */
    private void runOperationAndRefresh(java.util.function.Supplier<GitResult> work, String description) {
        operations.operation(description, OperationRunner.Refresh.EVERYTHING)
                .afterwards(result -> updateOperationBanner())
                .run(work);
    }

    /**
     * One row in the staged/unstaged lists: file name plus the action that moves
     * it to the other list.
     *
     * <p>The cell holds direct references to both ListViews and to the header-count
     * updater so that clicking Stage/Unstage can move the item immediately, without
     * waiting for the git operation to complete (optimistic update). If the git
     * operation fails the move is reversed and an error is reported.
     *
     * <h2>Built once, filled per row</h2>
     * A list recycles a screenful of cells and calls {@link #updateItem} on them for
     * every row that scrolls past. This one used to build its whole row there - a dozen
     * nodes, two tooltips, a context menu with its Ignore and LFS submenus, two hover
     * bindings, and a selection binding that stayed subscribed to the list for good. On a
     * two-thousand-file working tree that was a stall on every scroll tick and a leak on
     * every one of them. The nodes, the tooltip and the menu are now made once per cell,
     * the same way {@code GitGraphCell} does it, and every action reads the row the cell
     * is showing when it fires.
     */
    private class FileRowCell extends ListCell<String> implements StagingRow {
        private final boolean unstagedSide;
        /**
         * A row of the Conflicted files section: a warning mark for its letter, Resolve and
         * Mark resolved for its actions, and a click anywhere else on it opens the merge
         * editor, as in commercial clients.
         */
        private final boolean conflictSide;
        private final javafx.collections.ObservableList<String> unstagedAll;
        private final javafx.collections.ObservableList<String> stagedAll;
        private final Runnable updateCounts;

        private final Label status = new Label();
        private final Tooltip statusTip = new Tooltip();
        private final Label dir = new Label();
        private final Label name = new Label();
        /** Only on the unstaged side: a conflict is resolved from there. */
        private final Button resolve;
        private final Button action;
        private final Label added = new Label();
        private final Label deleted = new Label();
        private final Label locked = new Label();
        /** On a staged row: the file was conflicted earlier in this operation and is resolved now. */
        private final Label resolvedTag = new Label("Resolved");
        private final HBox lineCounts;
        private final HBox actions;
        private final HBox row;
        /** The in-flight or failed marker beside the row, when it has one. */
        private javafx.scene.Node activity;
        private FileRowMenu menu;

        FileRowCell(boolean unstagedSide,
                    boolean conflictSide,
                    javafx.collections.ObservableList<String> unstagedAll,
                    javafx.collections.ObservableList<String> stagedAll,
                    Runnable updateCounts) {
            this.unstagedSide = unstagedSide;
            this.conflictSide = conflictSide;
            this.unstagedAll = unstagedAll;
            this.stagedAll = stagedAll;
            this.updateCounts = updateCounts;

            // The same row shape a commit's changed files get, so a path looks the same
            // whether you are about to stage it or reading what a commit did to it.
            status.getStyleClass().add("status-letter");
            statusTip.setOnShowing(e -> statusTip.setText(
                    describeStatus(stagingStatusOf(getItem()))));
            status.setTooltip(statusTip);

            // Split so the directory can be dimmed and the file name emphasised: in a
            // list of thirty paths sharing a prefix, the basename is the only part being
            // read, and it is the part sitting at the far right of the shared text.
            dir.getStyleClass().add("file-dir");
            // The directory is what gives way when the row is narrow, and it gives way
            // from its own start: "…/ui/DiffViewer.java" keeps the one part being read.
            // Laid out left to right, the leading label takes the space it wants first,
            // so without this the filename is what gets cut - the row ends up showing a
            // shared prefix and eliding the only part that distinguishes it.
            dir.setMinWidth(0);
            dir.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
            name.getStyleClass().add("file-normal");
            // The filename holds its width against the directory beside it, but not
            // against the row itself. Pinned at USE_PREF_SIZE it could not shrink at all,
            // so a long basename - build/tmp/.../BuildConfig.class.uniqueId0 - pushed the
            // hover actions past the viewport and the list grew a horizontal scrollbar
            // with the Stage button somewhere off the right-hand end. A file you cannot
            // stage is worse than a name you cannot fully read.
            name.setMinWidth(0);
            name.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

            HBox pathBox = new HBox(0, dir, name);
            pathBox.setAlignment(Pos.CENTER_LEFT);
            pathBox.setMinWidth(0);
            HBox.setHgrow(pathBox, Priority.ALWAYS);

            // Actions appear on hover rather than on every row at all times. Two hundred
            // changed files otherwise means four hundred buttons competing with the
            // filenames, which are what the list is for. Bound once, here: binding in
            // updateItem added a fresh pair of bindings to this cell for every row it
            // ever showed.
            actions = new HBox(4);
            actions.setAlignment(Pos.CENTER_RIGHT);
            actions.getStyleClass().add("file-actions");
            actions.visibleProperty().bind(hoverProperty().or(selectedProperty()));
            actions.managedProperty().bind(actions.visibleProperty());

            if (unstagedSide) {
                resolve = new Button("Resolve");
                resolve.getStyleClass().addAll("btn-ghost", "row-action");
                resolve.setMinWidth(Region.USE_PREF_SIZE);
                resolve.setOnAction(e -> {
                    String path = getItem();
                    if (path != null) openMergeTool(path);
                });
                actions.getChildren().add(resolve);
            } else {
                resolve = null;
            }

            action = new Button(conflictSide ? "Mark resolved" : unstagedSide ? "Stage" : "Unstage");
            action.getStyleClass().addAll("btn-ghost", "row-action");
            action.setMinWidth(Region.USE_PREF_SIZE);
            action.setOnAction(e -> {
                if (this.conflictSide) {
                    markCurrentResolved();
                } else {
                    moveCurrent();
                }
            });
            actions.getChildren().add(action);

            if (conflictSide) {
                // Marked with the warning rather than a letter: "U" says "unmerged" only to
                // someone who already knows git's status codes.
                status.setGraphic(Icons.warning());
                status.getStyleClass().addAll("status-letter-u", "conflict-row-icon");
                resolve.setTooltip(new Tooltip("Open the merge editor on this file"));
                action.setTooltip(new Tooltip("Stage the file as it is now, which tells git the"
                        + " conflict is resolved"));
                addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, this::openOnClick);
            }
            resolvedTag.getStyleClass().add("resolved-tag");
            resolvedTag.setMinWidth(Region.USE_PREF_SIZE);
            resolvedTag.setTooltip(new Tooltip("Conflicted earlier in this operation, and marked resolved since"));

            // How big the change is, in the same place and the same colours a commit's
            // changed files use. Without it the list says which files changed and gives
            // no sense of scale, so a typo fix and a rewritten module look alike in the
            // one view where the difference decides what to read first.
            added.getStyleClass().add("stat-add");
            deleted.getStyleClass().add("stat-del");
            locked.getStyleClass().addAll("t-caption", "lfs-lock");
            locked.setMinWidth(Region.USE_PREF_SIZE);
            lineCounts = new HBox(5);
            lineCounts.setAlignment(Pos.CENTER_RIGHT);

            row = new HBox(UiScale.STEP, status, pathBox, lineCounts, actions);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("file-row");

            // Sized to the viewport, not to its content. A ListCell defaults to its
            // preferred width, so one long row made the whole list scroll sideways and
            // took the hover actions with it. At 0 the cell takes whatever width the list
            // has and the labels inside elide into it, which is what stops the scrollbar
            // existing rather than merely making it reachable.
            setPrefWidth(0);
            stagingRows.add(this);
        }

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

        /** Stages or unstages the row on show, optimistically; see {@link #moveFile}. */
        private void moveCurrent() {
            String path = getItem();
            if (path != null) {
                moveFile(path, unstagedSide, unstagedAll, stagedAll, updateCounts);
            }
        }

        /** Marks the conflict on show resolved; see {@link #markResolved}. */
        private void markCurrentResolved() {
            String path = getItem();
            if (path != null) {
                markResolved(path);
            }
        }

        /**
         * A plain click on a conflicted row opens the merge editor on it. Not a click on one
         * of the row's own buttons, which do their own thing; not a modified click, which is
         * selecting several rows; and once per click, not again for a double click's second.
         */
        private void openOnClick(javafx.scene.input.MouseEvent e) {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY || e.getClickCount() != 1
                    || !e.isStillSincePress() || e.isShortcutDown() || e.isShiftDown()) {
                return;
            }
            String path = getItem();
            if (path == null || isEmpty() || withinButton(e.getPickResult().getIntersectedNode())) {
                return;
            }
            openMergeTool(path);
        }

        private boolean withinButton(javafx.scene.Node node) {
            for (javafx.scene.Node at = node; at != null && at != this; at = at.getParent()) {
                if (at instanceof javafx.scene.control.ButtonBase) return true;
            }
            return false;
        }

        @Override
        protected void updateItem(String path, boolean empty) {
            super.updateItem(path, empty);
            if (empty || path == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }

            boolean conflicted = stagingConflicts.contains(path);
            if (!conflictSide) {
                setStatusLetter(status, stagingStatusOf(path));
            }

            int slash = path.lastIndexOf('/');
            dir.setText(slash < 0 ? "" : path.substring(0, slash + 1));
            name.setText(slash < 0 ? path : path.substring(slash + 1));
            setClass(name, "file-conflicted", conflicted);
            setClass(name, "file-normal", !conflicted);

            if (resolve != null) {
                setChildren(actions, conflicted ? resolve : null, action);
            }

            int[] lines = (unstagedSide ? unstagedLineCounts : stagedLineCounts).get(path);
            // Not for a conflict: what git counts for an unmerged path is the stages against
            // each other, and "+0 -3" beside a file full of markers says nothing true.
            boolean counted = !conflictSide && lines != null && lines.length == 2;
            if (counted) {
                added.setText("+" + lines[0]);
                deleted.setText(MINUS + lines[1]);
            }
            String lockOwner = lfsLocks.get(path);
            if (lockOwner != null) {
                locked.setText(lockOwner.isBlank() ? "locked" : "locked by " + lockOwner);
            }
            boolean resolvedHere = !unstagedSide && resolvedThisOperation(path);
            // In the row or out of it, not hidden: a row with no count has no count label,
            // as it had when the row was built per update.
            setChildren(lineCounts, resolvedHere ? resolvedTag : null, lockOwner != null ? locked : null,
                    counted ? added : null, counted ? deleted : null);

            // Rare - a move in flight or refused - so built when there is one.
            if (activity != null) {
                row.getChildren().remove(activity);
            }
            activity = rowActivityNode(path, unstagedSide, this::moveCurrent);
            if (activity != null) {
                row.getChildren().add(3, activity);
            }
            RowActivity pending = rowActivity.get(path);
            action.setDisable(activity != null && pending != null && pending.pending());

            if (menu == null) {
                menu = new FileRowMenu(this, unstagedSide);
            }
            menu.update(path, conflicted && unstagedSide);
            setContextMenu(menu.menu);

            setText(null);
            setGraphic(row);
        }
    }

    /** A staging row that can draw itself again in place; see {@link #redrawStagingRows}. */
    private interface StagingRow {
        /** The list or tree the row belongs to now. */
        javafx.scene.control.Control owner();

        void redraw();
    }

    /** Every staging row cell made, held weakly: a list drops its cells when it is replaced. */
    private final Set<StagingRow> stagingRows =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /**
     * Fills the rows of {@code owners} again from what the panel knows now - line counts,
     * locks, a move in flight - keeping the cells they have.
     *
     * <p>Not {@code refresh()}. That throws every cell of the list away and builds new
     * ones, and with rows that build their nodes once per cell it would build them all
     * again - on every stage click, twice: once when the move starts and once when git
     * answers - and once more for each line-count reply.
     */
    private void redrawStagingRows(javafx.scene.control.Control... owners) {
        for (StagingRow row : List.copyOf(stagingRows)) {
            javafx.scene.control.Control owner = row.owner();
            for (javafx.scene.control.Control wanted : owners) {
                if (owner == wanted) {
                    row.redraw();
                    break;
                }
            }
        }
    }

    /** The status letter of a working-tree path, from the state on screen. */
    private char stagingStatusOf(String path) {
        StagingState state = stagingState;
        return path == null || state == null ? 'M' : state.statusOf(path);
    }

    /**
     * A staging row's right-click menu, built once per cell.
     *
     * <p>Most of it is the same for every row, so it is made once and every action asks
     * the cell which file it is showing when it fires. What does differ is settled in two
     * places: what belongs in the menu at all - the external merge tool, the LFS submenu
     * and its labels - when the row is filled, since that is part of what the row offers;
     * and the labels nobody reads until the menu opens - the Ignore rules, "Discard
     * selected (n)" - when it opens, so filling a row never formats them.
     */
    private final class FileRowMenu {
        final ContextMenu menu = new ContextMenu();
        private final ListCell<String> cell;
        private final MenuItem discardSelected;
        private final MenuItem external = new MenuItem("Resolve in external merge tool");
        private final javafx.scene.control.Menu ignore = new javafx.scene.control.Menu("Ignore");
        private final MenuItem ignoreFile = new MenuItem("This file");
        private final MenuItem ignoreType = new MenuItem();
        private final MenuItem ignoreFolder = new MenuItem();
        private final javafx.scene.control.Menu lfs = new javafx.scene.control.Menu("Git LFS");
        private final MenuItem lfsTrackFile = new MenuItem("Track this file");
        private final MenuItem lfsTrackType = new MenuItem();
        private final MenuItem lfsLock = new MenuItem("Lock");
        private final MenuItem lfsUnlock = new MenuItem("Unlock");
        private final MenuItem lfsRefresh = new MenuItem("Refresh locks");
        private final MenuItem history = new MenuItem("View file history");
        private final MenuItem copyPath = new MenuItem("Copy path");
        /** The file the menu was opened on; actions use it rather than a later row. */
        private String path;

        FileRowMenu(FileRowCell cell, boolean unstagedSide) {
            this.cell = cell;
            if (cell.conflictSide) {
                MenuItem open = new MenuItem("Resolve in merge editor");
                open.setOnAction(e -> {
                    if (path != null) openMergeTool(path);
                });
                menu.getItems().add(open);
            }
            MenuItem toggle = new MenuItem(cell.conflictSide ? "Mark resolved"
                    : unstagedSide ? "Stage file" : "Unstage file");
            // Through the row's own button, so a row whose move is still in flight - its
            // button disabled - is not sent back the other way from here either.
            toggle.setOnAction(e -> cell.action.fire());
            menu.getItems().add(toggle);
            if (unstagedSide) {
                // With several rows selected the menu offers the batch form; one row
                // keeps the plain per-file item. Read from the live selection when the
                // menu opens and again when the item fires, because cells are recycled
                // while selection changes underneath them - a menu built for yesterday's
                // selection is how "Discard selected" would silently discard the wrong set.
                discardSelected = new MenuItem();
                discardSelected.setOnAction(e -> discardSelection());
                MenuItem discard = new MenuItem("Discard changes");
                discard.setOnAction(e -> {
                    String target = path;
                    if (target == null) return;
                    if (askToDiscard("Discard all changes to `" + nameOf(target) + "`?",
                            folderOf(target), "Discard changes", null)) {
                        discardGuarded(List.of(target), "Discarding " + target,
                                "Before discarding " + target,
                                () -> gitService.discardChanges(target), OperationRunner.Refresh.STAGING);
                    }
                });
                menu.getItems().addAll(discardSelected, discard);
            } else {
                discardSelected = null;
            }
            external.setOnAction(e -> {
                if (path != null) openExternalMergeTool(path);
            });

            ignoreFile.setOnAction(e -> ignorePath(com.jfx.git.core.WorkspaceFiles.IgnoreScope.FILE));
            ignoreType.setOnAction(e -> ignorePath(com.jfx.git.core.WorkspaceFiles.IgnoreScope.EXTENSION));
            ignoreFolder.setOnAction(e -> ignorePath(com.jfx.git.core.WorkspaceFiles.IgnoreScope.FOLDER));
            ignore.getItems().addAll(ignoreFile, ignoreType, ignoreFolder);
            menu.getItems().add(ignore);

            lfsTrackFile.setOnAction(e -> {
                String target = path;
                if (target != null) {
                    runAndRefresh(() -> gitService.lfsTrack(target), "Tracking " + target + " with LFS");
                }
            });
            lfsTrackType.setOnAction(e -> {
                String byType = path == null ? null : lfsTypePattern(path);
                if (byType != null) {
                    runAndRefresh(() -> gitService.lfsTrack(byType), "Tracking " + byType + " with LFS");
                }
            });
            lfsLock.setOnAction(e -> {
                String target = path;
                if (target != null) changeLfsLock(() -> gitService.lfsLock(target), "Locking " + target);
            });
            lfsUnlock.setOnAction(e -> {
                String target = path;
                if (target != null) {
                    changeLfsLock(() -> gitService.lfsUnlock(target, false), "Unlocking " + target);
                }
            });
            lfsRefresh.setOnAction(e -> refreshLfsLocks());
            lfs.getItems().addAll(lfsTrackFile, lfsTrackType, new javafx.scene.control.SeparatorMenuItem(),
                    lfsLock, lfsUnlock, lfsRefresh);

            history.setOnAction(e -> {
                if (path != null) showFileHistory(path);
            });
            copyPath.setOnAction(e -> {
                if (path != null) copyToClipboard(path, "Path copied");
            });
            menu.getItems().addAll(history, copyPath);

            menu.setOnShowing(e -> {
                path = cell.getItem();
                if (path == null) return;
                if (discardSelected != null) {
                    int selected = selectionOf(cell).size();
                    discardSelected.setVisible(selected > 1);
                    discardSelected.setText("Discard selected (" + selected + " files)");
                }
                String extensionRule = com.jfx.git.core.WorkspaceFiles.ruleFor(
                        path, com.jfx.git.core.WorkspaceFiles.IgnoreScope.EXTENSION);
                ignoreType.setText(extensionRule == null
                        ? "All files of this type (no extension)"
                        : "All " + extensionRule + " files");
                ignoreType.setDisable(extensionRule == null);
                String folderRule = com.jfx.git.core.WorkspaceFiles.ruleFor(
                        path, com.jfx.git.core.WorkspaceFiles.IgnoreScope.FOLDER);
                ignoreFolder.setText(folderRule == null
                        ? "Containing folder (already at the root)"
                        : "Containing folder " + folderRule);
                ignoreFolder.setDisable(folderRule == null);
            });
        }

        /**
         * Settles what the row offers: the external merge tool for a conflict, when one is
         * chosen, and Git LFS when it is installed - with the labels a reader of the menu
         * sees without opening it.
         */
        void update(String rowPath, boolean conflictedHere) {
            path = rowPath;
            boolean offerExternal = conflictedHere && com.jfx.git.core.AppPreferences.mergeToolMode()
                    != com.jfx.git.core.ExternalTools.Mode.NONE;
            place(external, offerExternal, menu.getItems().indexOf(ignore));
            place(lfs, lfsAvailable, menu.getItems().indexOf(history));
            if (lfsAvailable) {
                String byType = lfsTypePattern(rowPath);
                lfsTrackType.setText(byType == null
                        ? "Track all files of this type (no extension)"
                        : "Track all " + byType + " files");
                lfsTrackType.setDisable(byType == null);
                lfsLock.setDisable(lfsLocks.containsKey(rowPath));
            }
        }

        /** Puts {@code item} in the menu just before {@code index}, or takes it out. */
        private void place(MenuItem item, boolean wanted, int index) {
            boolean present = menu.getItems().contains(item);
            if (wanted && !present) {
                menu.getItems().add(index, item);
            } else if (!wanted && present) {
                menu.getItems().remove(item);
            }
        }

        private void ignorePath(com.jfx.git.core.WorkspaceFiles.IgnoreScope scope) {
            if (path != null) applyIgnore(path, scope);
        }

        private void discardSelection() {
            List<String> targets = List.copyOf(selectionOf(cell));
            if (targets.size() < 2) return;
            if (!askToDiscard("Discard all changes in " + targets.size() + " files?", null,
                    "Discard " + targets.size() + " files", targets)) return;
            discardGuarded(targets, "Discarding " + targets.size() + " files",
                    "Before discarding " + targets.size() + " files",
                    () -> {
                        for (String target : targets) {
                            var r = gitService.discardChanges(target);
                            if (!r.isOk()) return r;
                        }
                        return GitResult.ok("Discarded " + targets.size() + " file(s)");
                    },
                    OperationRunner.Refresh.EVERYTHING);
        }
    }

    /** The rows selected in the list {@code cell} belongs to, or none. */
    private static List<String> selectionOf(ListCell<String> cell) {
        ListView<String> list = cell.getListView();
        return list == null ? List.of() : list.getSelectionModel().getSelectedItems();
    }

    /** {@code *.ext} for a path with an extension, or null; what "Track all ... files" tracks. */
    private static String lfsTypePattern(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? "*" + name.substring(dot) : null;
    }

    /**
     * Shows {@code code} on a status-letter label, touching its style classes only when
     * the letter changes: a class list change re-applies CSS to the node, and most rows a
     * recycled cell moves through carry the same letter as the one before.
     */
    private static void setStatusLetter(Label status, char code) {
        status.setText(statusText(code));
        String wanted = statusClass(code);
        var classes = status.getStyleClass();
        if (classes.contains(wanted)) return;
        classes.removeIf(c -> c.startsWith("status-letter-"));
        classes.add(wanted);
    }

    private static final String[] STATUS_TEXT = new String[128];
    private static final String[] STATUS_CLASS = new String[128];

    /** The letter as a string, made once per letter rather than once per row. */
    private static String statusText(char code) {
        if (code >= 128) return String.valueOf(code);
        String text = STATUS_TEXT[code];
        if (text == null) {
            text = String.valueOf(code);
            STATUS_TEXT[code] = text;
        }
        return text;
    }

    private static String statusClass(char code) {
        if (code >= 128) return "status-letter-" + Character.toLowerCase(code);
        String name = STATUS_CLASS[code];
        if (name == null) {
            name = "status-letter-" + Character.toLowerCase(code);
            STATUS_CLASS[code] = name;
        }
        return name;
    }

    /**
     * Makes {@code box} hold exactly the non-null {@code wanted}, in order, touching its
     * children only when they differ: re-adding a node re-applies its CSS, and most rows a
     * recycled cell moves through have the same parts as the one before.
     */
    private static void setChildren(javafx.scene.layout.Pane box, javafx.scene.Node... wanted) {
        var children = box.getChildren();
        int n = 0;
        boolean same = true;
        for (javafx.scene.Node node : wanted) {
            if (node == null) continue;
            if (n >= children.size() || children.get(n) != node) same = false;
            n++;
        }
        if (same && n == children.size()) return;
        List<javafx.scene.Node> next = new java.util.ArrayList<>(n);
        for (javafx.scene.Node node : wanted) {
            if (node != null) next.add(node);
        }
        children.setAll(next);
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
     * Runs a hunk action the diff viewer offers. Which actions a diff offers is its own
     * answer, from its kind; what each one writes is settled in the action, from the same
     * loaded diff as the hunk. This decides only how each runs.
     */
    private void runHunkAction(HunkAction action) {
        switch (action.type()) {
            case STAGE, UNSTAGE -> applyToIndex(action);
            case DISCARD -> discard(action);
            // A commit's hunk, from the commit details: into the working tree as an
            // uncommitted edit, which may put a WIP row on the graph.
            case REVERT -> HunkRevert.confirmAndRun(operations, action, action.path(), folderOf(action.path()),
                    () -> com.jfx.git.ui.views.SnapshotsView.open(viewHost, operations),
                    OperationRunner.Refresh.EVERYTHING);
        }
    }

    /**
     * Puts the "before" side of a hunk, or of some of its lines, back in the working-tree
     * copy, after asking - this throws work away - unless the user said not to ask; see
     * {@link #askToDiscard}.
     */
    private void discard(HunkAction action) {
        String path = action.path();
        String question;
        String verb;
        String busy;
        if (action.wholeHunk()) {
            question = "Discard this hunk in `" + nameOf(path) + "`?";
            verb = "Discard hunk";
            busy = "Reverting hunk in " + path;
        } else {
            int count = action.lineCount();
            question = "Discard " + (count == 1 ? "the selected line" : "the " + count + " selected lines")
                    + " in `" + nameOf(path) + "`?";
            verb = count == 1 ? "Discard line" : "Discard lines";
            busy = "Discarding " + action.linesPhrase() + " in " + path;
        }
        if (askToDiscard(question, folderOf(path), verb, null)) {
            discardGuarded(List.of(path), busy, "Before discarding lines in " + path,
                    () -> gitService.revertHunk(path, action.edit()), OperationRunner.Refresh.STAGING);
        }
    }

    /**
     * Asks before a file's changes - all of them, a hunk's or some lines', in one file or
     * a selection of them - are discarded, unless "Ask before discarding a file's changes"
     * is off. Skipping the question is safe because every caller discards through
     * {@link #discardGuarded}, whose snapshot and Undo bring the work back.
     *
     * <p>"Discard all changes" on the graph's WIP row does not come through here, and asks
     * whatever the setting says; see {@code GitGraphCell.attachWipDiscard}.
     *
     * <p>Enter discards, and the message names the way back, because both are true: see
     * {@link #discardGuarded}. "Don't ask again" is that same setting, turned off when the
     * discard goes ahead with it ticked.
     *
     * @param title    the decision, the object named: "Discard all changes to `b.txt`?"
     * @param subtitle where it applies, or null
     * @param verb     the confirming button: "Discard changes"
     * @param files    the files, listed, when there are several; null for one
     * @return whether to go ahead
     */
    private static boolean askToDiscard(String title, String subtitle, String verb, List<String> files) {
        if (!com.jfx.git.core.AppPreferences.confirmDiscard()) return true;
        CheckBox dontAsk = new CheckBox("Don't ask again");
        dontAsk.getStyleClass().add("discard-dont-ask");
        dontAsk.setTooltip(new Tooltip("Turn the question back on in Settings, under Diff"));
        var ask = com.jfx.git.ui.dialogs.Dialogs.destructive("Discard changes")
                .title(title)
                .subtitle(subtitle)
                .message("A snapshot is taken first, and the notification that follows offers Undo.")
                .action(verb)
                .footerLeft(dontAsk);
        if (files != null) {
            ask.affected("Files", files).size(com.jfx.git.ui.dialogs.JfxDialog.Size.MD);
        }
        boolean go = ask.ask();
        if (go && dontAsk.isSelected()) {
            com.jfx.git.core.AppPreferences.setConfirmDiscard(false);
        }
        return go;
    }

    /** "In `src/app/`" for a file in a folder, "Repository root" for one at the top: a subtitle. */
    private static String folderOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "Repository root" : "In `" + path.substring(0, slash + 1) + "`";
    }

    /** The file's own name, without its folder. */
    private static String nameOf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Discards behind a working-tree snapshot, then offers Undo - which restores the
     * files from that snapshot - and the snapshot list. See {@link SnapshotGuard}.
     */
    private void discardGuarded(List<String> paths, String busy, String reason,
                                java.util.function.Supplier<GitResult> work, OperationRunner.Refresh refresh) {
        SnapshotGuard.discard(operations, busy, reason, paths, work,
                () -> com.jfx.git.ui.views.SnapshotsView.open(viewHost, operations), refresh);
    }

    /**
     * Opens what the diff viewer is showing in the user's own diff tool.
     *
     * <p>On the repository lane, because the committed and staged sides are read out of
     * the repository to be handed over; the tool itself is started and left running.
     */
    private void openExternalDiff(com.jfx.git.core.ExternalTools.DiffTarget target) {
        operations.run("Opening " + target.path() + " in the diff tool", OperationRunner.Refresh.NONE,
                () -> com.jfx.git.core.ExternalTools.openDiff(gitService, target));
    }

    /**
     * Resolves a conflict in the user's own merge tool, and stages the file when the tool
     * leaves it resolved.
     *
     * <p>Staging is asked for rather than assumed when the tool is one this application
     * launched itself. A tool that exits cleanly with no markers left has usually
     * resolved the file - and sometimes has just been closed, with the markers deleted by
     * hand and half the conflict thrown away. Marking a conflict resolved is the user's
     * statement, so it stays theirs. {@code git mergetool} makes that decision itself
     * and stages; then there is nothing left to ask.
     */
    private void openExternalMergeTool(String path) {
        com.jfx.git.core.ExternalTools.MergeResult[] merged = new com.jfx.git.core.ExternalTools.MergeResult[1];
        operations.operation("Waiting for the merge tool to close", OperationRunner.Refresh.STAGING)
                // A tool that could not be run changed nothing, and redrawing the staging
                // panel for it took the user away from whatever they had turned to meanwhile.
                .refreshWhen(result -> merged[0] != null
                        && merged[0].outcome() != com.jfx.git.core.ExternalTools.MergeOutcome.FAILED)
                // A clean close asks whether to mark the file resolved; it says nothing first.
                .showSuccess(result -> {
                    if (merged[0].outcome() != com.jfx.git.core.ExternalTools.MergeOutcome.RESOLVED) {
                        statusBar.info(result.describe());
                    }
                })
                .afterwards(result -> {
                    if (merged[0] != null
                            && merged[0].outcome() == com.jfx.git.core.ExternalTools.MergeOutcome.RESOLVED) {
                        offerToMarkResolved(path);
                    }
                })
                // The tool runs on a thread of its own, with the lane free; the operation
                // lasts until it closes.
                .start(steps -> com.jfx.git.core.ExternalTools.mergeAsync(gitService, path, result -> {
                    merged[0] = result;
                    steps.finish(switch (result.outcome()) {
                        case RESOLVED, RESOLVED_AND_STAGED -> GitResult.ok(result.message());
                        // A warning, which stays until replaced: the tool has just closed over
                        // whatever was being looked at, and a message that fades would be gone
                        // by the time the eye is back on this window.
                        case UNRESOLVED -> GitResult.conflict(result.message(), List.of());
                        case FAILED -> GitResult.failed(result.message());
                    });
                }));
    }

    /** After the merge tool closed with no markers left: asks before staging, which marks the file resolved. */
    private void offerToMarkResolved(String path) {
        ButtonType mark = new ButtonType("Mark as resolved", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType answer = com.jfx.git.ui.dialogs.Dialogs.confirm("Mark as resolved")
                .icon(Icons.check())
                .title("Mark `" + path + "` as resolved?")
                .subtitle("The merge tool closed with no conflict markers left in it")
                .message("It is staged as it is now. A tool closed before it finished leaves"
                        + " no markers either, so look at the file first if you are not sure.")
                .buttons(mark, ButtonType.CANCEL)
                .answer();
        if (answer == mark) {
            runAndRefresh(() -> gitService.stageFile(path), "Marking " + path + " as resolved");
        }
    }

    /** The three conflict stages and the working copy, read together for the merge tool. */
    private record ConflictSides(String base, String ours, String theirs, String merged,
                                 String branch) {}

    /**
     * Reads the conflict on the repository lane, then opens the tool.
     *
     * <p>The five reads used to run in the button's handler, on the FX thread - three
     * index stages, the working copy and the branch - and froze the window for all of them
     * on a large file; and on the composed engines they were JGit reads, which the
     * subprocess-only thread guard never saw.
     */
    private void openMergeTool(String path) {
        GitTaskRunner.query(gitService,
                () -> new ConflictSides(
                        gitService.getConflictStage(path, 1),
                        gitService.getConflictStage(path, 2),
                        gitService.getConflictStage(path, 3),
                        gitService.getWorkingTreeContent(path),
                        gitService.getCurrentBranch()),
                sides -> showMergeTool(path, sides),
                statusBar::error);
    }

    private void showMergeTool(String path, ConflictSides sides) {
        String merged = sides.merged();

        // Named rather than left as "current branch": that wording is merely vague during
        // a merge and actually wrong during a rebase, where the sides are the other way
        // round from what it suggests. The incoming side is named from git's own conflict
        // marker, which already says the merged branch or the commit being replayed.
        MergeToolDialog dialog = new MergeToolDialog(path, sides.ours(), sides.theirs(),
                sides.base(), merged, sides.branch(), MergeToolDialog.incomingLabel(merged));
        // The row menu's "Resolve in external merge tool", from inside the editor too.
        if (com.jfx.git.core.AppPreferences.mergeToolMode() != com.jfx.git.core.ExternalTools.Mode.NONE) {
            dialog.offerExternalTool(() -> openExternalMergeTool(path));
        }
        // The dialog's text has lost the file's carriage returns, as all RichTextFX text
        // does; written as it was, a CRLF file came back LF on every line. The conflicted
        // copy on disk still has them, so it says which convention to write.
        dialog.showAndWait()
                .map(text -> com.jfx.git.core.LineEndings.matchConvention(merged, text))
                .ifPresent(resolved -> {
            if (dialog.savedAsDraft()) {
                // Written, not staged: the index keeps the conflict's stages, so the file
                // stays conflicted and the tool opens on this draft next time.
                runAndRefresh(() -> gitService.writeWorkingTreeFile(path,
                                com.jfx.git.core.TextEncoding.encodeForEdit(
                                        resolved, gitService.getWorkingTreeBytes(path))),
                        "Saving draft of " + path);
            } else {
                resolveAndOfferNext(path, resolved);
            }
        });
    }

    // ------------------------------------------------------------------
    // Working through the conflicts
    // ------------------------------------------------------------------

    /** What resolving one file left: git's answer, and the conflicts still open after it. */
    private record ResolveOutcome(GitResult result, List<String> remaining,
                                  com.jfx.git.model.OperationState operation) {
    }

    /**
     * Resolves {@code path} with the merge editor's result, then says what is left: the
     * next conflicted file, offered rather than opened - the user may want to look at what
     * they just did first - or, with none left, how to finish.
     */
    private void resolveAndOfferNext(String path, String resolved) {
        java.util.concurrent.atomic.AtomicReference<ResolveOutcome> left =
                new java.util.concurrent.atomic.AtomicReference<>();
        operations.operation("Resolving " + path, OperationRunner.Refresh.STAGING)
                .showSuccess(result -> offerNextConflict(path, left.get().remaining(), left.get().operation()))
                .run(() -> {
                    GitResult result = gitService.resolveConflict(path, resolved);
                    if (!result.isOk()) return result;
                    List<String> remaining = new java.util.ArrayList<>(gitService.getConflictingFiles());
                    remaining.remove(path);
                    java.util.Collections.sort(remaining);
                    left.set(new ResolveOutcome(result, remaining, gitService.getOperationState()));
                    return result;
                });
    }

    /**
     * "Resolved a.txt - 1 conflict left · Resolve next: b.txt", or "All conflicts resolved
     * - commit the merge". The offer stays until it is used or dismissed, and a newer one
     * replaces it.
     */
    private void offerNextConflict(String resolvedPath, List<String> remaining,
                                   com.jfx.git.model.OperationState operation) {
        withdrawNextConflictOffer();
        if (remaining.isEmpty()) {
            statusBar.info(OperationHeadline.allResolved(operation));
            return;
        }
        String next = nextConflict(resolvedPath, remaining);
        String left = conflictsLeft(remaining.size());
        nextConflictOffer = statusBar.offer(ToastHost.Kind.INFO,
                "Resolved " + nameOf(resolvedPath) + " - " + left,
                List.of(new ToastHost.Action("Resolve next: " + nameOf(next), () -> openIfConflicted(next))));
    }

    private void withdrawNextConflictOffer() {
        ToastHost toasts = statusBar.toastHost();
        if (nextConflictOffer != null && toasts != null) {
            toasts.dismiss(nextConflictOffer);
        }
        nextConflictOffer = null;
    }

    /**
     * The conflict after {@code resolved} in path order, wrapping round to the first: the
     * order the Conflicted files section lists them in.
     */
    static String nextConflict(String resolved, List<String> sortedRemaining) {
        for (String candidate : sortedRemaining) {
            if (candidate.compareTo(resolved) > 0) return candidate;
        }
        return sortedRemaining.get(0);
    }

    /**
     * Opens the merge editor on {@code path} if it is still conflicted. An offer can outlive
     * its file's conflict - resolved from its row, or outside the application - and the
     * editor on a resolved file has no sides to show.
     */
    private void openIfConflicted(String path) {
        GitTaskRunner.query(gitService, () -> gitService.getConflictingFiles().contains(path),
                still -> {
                    if (still) {
                        openMergeTool(path);
                    } else {
                        statusBar.info(nameOf(path) + " is already resolved");
                    }
                },
                statusBar::error);
    }

    /**
     * Marks one conflicted file resolved: stages it as it is. A file still holding conflict
     * markers is asked about first, in the merge editor's own words - staging
     * {@code <<<<<<<} is how a broken file gets committed as a finished one.
     *
     * <p>Not an optimistic move like Stage: the panel is read again afterwards, because
     * the conflict count and whether Continue is offered change with it.
     */
    private void markResolved(String path) {
        GitTaskRunner.query(gitService, () -> stillMarked(List.of(path)),
                marked -> {
                    if (!marked.isEmpty() && !confirmMarkedResolved(marked, false)) return;
                    runAndRefresh(() -> stageEach(List.of(path), "Marked " + path + " resolved"),
                            "Marking " + path + " resolved");
                },
                statusBar::error);
    }

    /** Mark all resolved: every conflicted file, after naming those that still hold markers. */
    private void markAllResolved(List<String> paths) {
        if (paths.isEmpty()) return;
        GitTaskRunner.query(gitService, () -> stillMarked(paths),
                marked -> {
                    if (!marked.isEmpty() && !confirmMarkedResolved(marked, true)) return;
                    String files = paths.size() == 1 ? nameOf(paths.get(0)) : paths.size() + " files";
                    runAndRefresh(() -> stageEach(paths, "Marked " + files + " resolved"),
                            "Marking " + files + " resolved");
                },
                statusBar::error);
    }

    /** Stages each path in turn, stopping at the first git refuses. */
    private GitResult stageEach(List<String> paths, String done) {
        for (String path : paths) {
            GitResult result = gitService.stageFile(path);
            if (!result.isOk()) return result;
        }
        return GitResult.ok(done);
    }

    /**
     * Which of {@code paths} still have conflict markers in the working tree. A file the
     * resolution deleted has none to look for. Reads files, so on the worker.
     */
    private List<String> stillMarked(List<String> paths) {
        java.io.File workTree = gitService.getWorkTree();
        List<String> marked = new java.util.ArrayList<>();
        if (workTree == null) return marked;
        for (String path : paths) {
            java.io.File file = new java.io.File(workTree, path);
            if (file.isFile() && com.jfx.git.core.ExternalTools.hasConflictMarkers(file)) {
                marked.add(path);
            }
        }
        return marked;
    }

    /**
     * The merge editor's question about a result that still has markers, asked from the
     * panel: Enter cancels, because staging marker text is the mistake this exists to stop.
     *
     * @param list whether to list the files - always for Mark all resolved, which may be
     *             about files the user is not looking at
     */
    private static boolean confirmMarkedResolved(List<String> marked, boolean list) {
        boolean one = marked.size() == 1;
        var ask = com.jfx.git.ui.dialogs.Dialogs.warn("Mark resolved")
                .icon(Icons.merge())
                .title(one ? "Mark `" + nameOf(marked.get(0)) + "` resolved with conflict markers in it?"
                        : "Mark resolved with conflict markers in " + marked.size() + " files?")
                .subtitle(one ? folderOf(marked.get(0)) : null)
                .message("Marking a file resolved stages it exactly as it is, markers included. Only do"
                        + " this if the marker text belongs in the file; otherwise open it in the merge"
                        + " editor first.")
                .buttons(ButtonType.OK, ButtonType.CANCEL)
                .action("Mark resolved anyway")
                .safeDefault();
        if (list) {
            ask.affected("Still have conflict markers", marked);
        }
        return ask.answer() == ButtonType.OK;
    }

    // ------------------------------------------------------------------
    // Commit details
    // ------------------------------------------------------------------

    /**
     * Details of a stash node picked in the graph.
     *
     * <p>A stash is a commit, so the files it holds are read the same way; what differs
     * is that the actions offered are apply/pop/drop rather than the history operations a
     * branch commit gets.
     */
    public void showStashDetails(com.jfx.git.model.StashEntry stash) {
        panel.setMode(DetailPanel.Mode.STASH, "Stash", stash.selector(), null);
        panel.clearBody();
        forgetComposer();
        forgetLiveStagingControls();
        // The stash composer went with it; a keyboard submit must not reach the fields
        // of a footer that is no longer on screen.
        stashAction = null;

        Label description = new Label(stash.shortDescription());
        description.getStyleClass().add("t-title");
        description.setWrapText(true);

        Label meta = new Label(stash.authorName() + "  ·  "
                + com.jfx.git.graph.RelativeTime.of(stash.when())
                + "  ·  " + DateDisplay.absolute(stash.when()));
        meta.getStyleClass().add("t-caption");

        Label hash = new Label(stash.shortHash());
        hash.getStyleClass().add("hash-chip");
        hash.setTooltip(new Tooltip("Copy " + stash.hash()));
        hash.setOnMouseClicked(e -> copyToClipboard(stash.hash(), "Copied " + stash.shortHash()));
        HBox hashRow = new HBox(UiScale.STEP, hash);
        hashRow.setAlignment(Pos.CENTER_LEFT);

        if (stashFiles == null) {
            stashFiles = new FilesSection("Files in this stash");
        }
        FilesSection files = stashFiles;
        files.reset();
        files.setRestoreSource(null);
        java.util.Map<String, String> revisionByPath = new java.util.HashMap<>();
        files.setOnSelect(change -> {
            diffViewer.setEditable(false);
            clearActiveSelection = files::clearSelection;
            String revision = revisionByPath.getOrDefault(change.path(), stash.hash());
            boolean untracked = !revision.equals(stash.hash());
            String parent = untracked ? null : stash.hash() + "^1";
            String beforePath = change.oldPath() == null ? change.path() : change.oldPath();
            diffViewer.setComparedSides(untracked
                    ? DiffViewer.DiffSides.between("Empty tree", revision)
                    : DiffViewer.DiffSides.between(stash.selector() + "^1", stash.selector()));
            diffViewer.setExternalDiffTarget(path -> untracked
                    ? com.jfx.git.core.ExternalTools.DiffTarget.commit(path, null, revision)
                    : com.jfx.git.core.ExternalTools.DiffTarget.between(
                            path, parent, revision));
            diffViewer.setRenameLookup(null);
            showDiff(DiffKind.STASH, change.path(),
                    () -> gitService.getDiffBytesForFileInCommit(revision, change.path(),
                            change.oldPath(), diffViewer.isIgnoreWhitespace()),
                    () -> gitService.getFileBytesAtCommit(revision, change.path()),
                    other -> gitService.getFileBytesAtCommit(revision, other),
                    revisionImageSource(change.path(), parent,
                            untracked ? null : beforePath, revision, change.path()));
        });

        panel.body().getChildren().addAll(
                description, meta, hashRow, divider(), files.header, files.body());
        loadStashChanges(stash, files, revisionByPath);
    }

    /**
     * A two-way comparison between any two commits.
     *
     * <p>Shows what is different between the two end points rather than what each commit
     * changed on the way — comparing two points in history is a question about the states,
     * not about the path between them.
     */
    public void showComparison(CommitInfo from, CommitInfo to) {
        showComparison(from, to, null, false);
    }

    /**
     * @param reopen   the file to open once the list has loaded, or {@code null} to leave
     *                 the diff viewer as it is
     * @param reversed whether the user swapped this comparison away from older → newer
     */
    private void showComparison(CommitInfo from, CommitInfo to, String reopen, boolean reversed) {
        panel.setMode(DetailPanel.Mode.COMPARE, "Comparing",
                from.getShortHash() + " ↔ " + to.getShortHash(), null);
        buildComparisonView(from, to, reopen, reversed);
    }

    /**
     * The diff-on-selection view (§3.5): selecting several commits in the graph shows
     * their end-to-end comparison without a trip through the context menu. It reads from
     * the lowest selected row to the highest, older to newer the way history does; with
     * more than two selected the header says how many.
     *
     * <h2>Graph order, not dates</h2>
     * The ends used to be picked by author date. A rebased or cherry-picked commit keeps
     * the author date it was first written with, so a descendant could sort as the older
     * of the two and the comparison came up backwards - every addition drawn as a
     * deletion, with nothing on screen to say so. The graph is in topological order, so
     * its rows say which commit comes first even when the dates disagree.
     *
     * <p>Rows that are not commits (WIP, stashes, load-more) never reach here — the
     * selection routing falls back to the focused row for those.
     *
     * @param newestFirst the selected commits in the order the graph shows them
     */
    public void showComparison(java.util.List<CommitInfo> newestFirst) {
        if (newestFirst == null || newestFirst.isEmpty()) return;
        if (newestFirst.size() == 1) {
            showCommitDetails(newestFirst.get(0));
            return;
        }
        CommitInfo from = newestFirst.get(newestFirst.size() - 1);
        CommitInfo to = newestFirst.get(0);
        String subtitle = newestFirst.size() == 2
                ? from.getShortHash() + " ↔ " + to.getShortHash()
                : newestFirst.size() + " commits · " + from.getShortHash()
                        + " ↔ " + to.getShortHash();
        panel.setMode(DetailPanel.Mode.COMPARE, "Comparing", subtitle, null);
        buildComparisonView(from, to, null, false);
    }

    /** Everything {@link #showComparison} variants share once the two ends are chosen. */
    private void buildComparisonView(CommitInfo from, CommitInfo to, String reopen,
                                     boolean reversed) {
        panel.clearBody();
        forgetComposer();
        forgetLiveStagingControls();
        // The stash composer went with it; a keyboard submit must not reach the fields
        // of a footer that is no longer on screen.
        stashAction = null;

        Label fromLabel = new Label("from  " + from.getShortHash() + "  " + from.getSubject());
        fromLabel.getStyleClass().add("t-body");
        fromLabel.setWrapText(true);
        Label toLabel = new Label("to    " + to.getShortHash() + "  " + to.getSubject());
        toLabel.getStyleClass().add("t-body");
        toLabel.setWrapText(true);

        // Older → newer is the reading every view opens with. Swapping is a deliberate
        // reversal, and says so for as long as it lasts: a diff read backwards draws every
        // addition as a deletion, and in the plain button's colours it would look like any
        // other comparison. Pressed again it returns to the usual direction.
        Button swap = new Button(reversed ? "Reversed · swap back" : "Swap direction");
        swap.getStyleClass().add("compare-swap");
        if (reversed) swap.getStyleClass().add("compare-swap-reversed");
        swap.setTooltip(new Tooltip(reversed
                ? "Showing newer → older. Swap back to read older → newer."
                : "Read this comparison the other way round, newer → older"));
        // The open file comes along. Rebuilding the list dropped its selection while the
        // viewer kept the old direction's diff on screen - additions where there are now
        // deletions, under a header that had already swapped.
        swap.setOnAction(e -> showComparison(to, from, openDiffPath(), !reversed));

        if (compareFiles == null) {
            compareFiles = new FilesSection("Changed files");
        }
        FilesSection files = compareFiles;
        files.reset();
        files.setRestoreSource(null);
        files.setOnSelect(change -> {
            diffViewer.setEditable(false);
            clearActiveSelection = files::clearSelection;
            diffViewer.setComparedSides(
                    DiffViewer.DiffSides.between(from.getShortHash(), to.getShortHash()));
            diffViewer.setExternalDiffTarget(path -> com.jfx.git.core.ExternalTools.DiffTarget.between(
                    path, from.getHash(), to.getHash()));
            diffViewer.setRenameLookup(null);
            showDiff(DiffKind.COMPARISON, change.path(),
                    () -> gitService.getDiffBytesBetweenCommits(from.getHash(), to.getHash(),
                            change.path(), change.oldPath(), diffViewer.isIgnoreWhitespace()),
                    () -> gitService.getFileBytesAtCommit(to.getHash(), change.path()),
                    other -> gitService.getFileBytesAtCommit(to.getHash(), other),
                    revisionImageSource(change.path(), from.getHash(),
                            change.oldPath() == null ? change.path() : change.oldPath(),
                            to.getHash(), change.path()));
        });

        panel.body().getChildren().addAll(
                fromLabel, toLabel, swap, divider(), files.header, files.body());

        // What differs between the end points, not what either branch did on the way.
        loadChanges(() -> gitService.getChangesBetweenCommits(from.getHash(), to.getHash()),
                files,
                () -> {
                    if (reopen != null) files.select(reopen);
                });
    }

    /**
     * The file the diff viewer is showing from the last list selection, or {@code null}
     * when it is closed or showing something else.
     */
    private String openDiffPath() {
        String shown = diffViewer.filePathProperty().get();
        return lastDiffPath != null && lastDiffPath.equals(shown) ? shown : null;
    }

    /** The read-only views' file sections, each kept for the panel's life; see {@link FilesSection}. */
    private FilesSection stashFiles;
    private FilesSection compareFiles;
    /** The commit view, kept and refilled per selection; see {@link CommitView}. */
    private CommitView commitView;

    public void showCommitDetails(CommitInfo commit) {
        panel.setMode(DetailPanel.Mode.COMMIT, "Commit", commit.getShortHash(), null);
        if (commitView == null) {
            commitView = new CommitView();
        }
        CommitView view = commitView;
        // Stepping from one commit to the next keeps the view where it is and refills
        // it; anything else on screen is cleared away first, as before.
        boolean onScreen = view.files.header.getParent() == panel.body();
        if (!onScreen) {
            panel.clearBody();
        }
        // The commit box is gone from the scene; forget it so a shortcut rebuilds the
        // staging panel instead of typing into a detached control.
        forgetComposer();
        forgetLiveStagingControls();
        // The stash composer went with it; a keyboard submit must not reach the fields
        // of a footer that is no longer on screen.
        stashAction = null;

        view.show(commit);
        FilesSection files = view.files;
        files.reset();
        files.setOnSelect(change -> {
            diffViewer.setEditable(false);
            clearActiveSelection = files::clearSelection;
            diffViewer.setComparedSides(DiffViewer.DiffSides.between(
                    parentLabel(commit), commit.getShortHash()));
            // Against the first parent, as the diff beside it is; a root commit against nothing.
            diffViewer.setExternalDiffTarget(path -> com.jfx.git.core.ExternalTools.DiffTarget.commit(
                    path,
                    commit.getParentHashes().isEmpty() ? null : commit.getParentHashes().get(0),
                    commit.getHash()));
            // The list names files as this commit had them, so Restore this version asks
            // what a file renamed since is called now.
            diffViewer.setRenameLookup(
                    RestoreDestination.lookupFor(gitService, commit.getHash(), statusBar::error));
            // A commit's diff offers Revert hunk, as it does in File history.
            showDiff(DiffKind.COMMIT, change.path(),
                    () -> gitService.getDiffBytesForFileInCommit(commit.getHash(), change.path(),
                            change.oldPath(), diffViewer.isIgnoreWhitespace()),
                    () -> gitService.getFileBytesAtCommit(commit.getHash(), change.path()),
                    other -> gitService.getFileBytesAtCommit(commit.getHash(), other),
                    revisionImageSource(change.path(),
                            commit.getParentHashes().isEmpty() ? null
                                    : commit.getParentHashes().get(0),
                            change.oldPath() == null ? change.path() : change.oldPath(),
                            commit.getHash(), change.path()));
        });

        if (!onScreen) {
            panel.body().getChildren().addAll(view.nodes);
        }

        files.setRestoreSource(commit.getHash());
        loadChanges(() -> gitService.getChangesInCommit(commit.getHash()), files,
                () -> prefetchFirstDiff(commit, files));
    }

    /**
     * A commit's details: who, when, its refs, its hash and parents, its message and its
     * files - built once and refilled for each commit selected.
     *
     * <h2>Why kept</h2>
     * Walking history with J/K selects a commit per keystroke, and each selection built
     * this whole column again: the labels, the message pane with its scroller, and a
     * files section holding a list and a tree with skins of their own. Kept, a step to
     * the next commit is a handful of {@code setText}s and a new list of files.
     */
    private final class CommitView {
        final com.jfx.git.graph.AuthorBadge avatar = new com.jfx.git.graph.AuthorBadge(AVATAR_SIZE);
        final Label authorName = new Label();
        final Label authorMail = new Label();
        final Label when = new Label();
        final Label exact = new Label();
        final javafx.scene.layout.FlowPane refs = new javafx.scene.layout.FlowPane(5, 5);
        final Label hash = new Label();
        final Tooltip hashTip = new Tooltip();
        final HBox identityRow = new HBox(UiScale.STEP);
        final Label subject = new Label();
        final Label body = new Label();
        final VBox messageBox = new VBox(UiScale.SNUG);
        final MenuItem copyMessage = new MenuItem("Copy message");
        final FilesSection files = new FilesSection("Changed files");
        final List<javafx.scene.Node> nodes;
        /** The commit on show, read by the actions when they fire. */
        CommitInfo commit;
        /** Its whole message, once known. */
        String fullMessage;

        CommitView() {
            // Avatar, name, email and age: who made this, and when.
            authorName.getStyleClass().add("identity-name");
            authorMail.getStyleClass().add("identity-mail");
            // Relative first, because that is the part being read: "2 days ago" answers
            // the question, and the timestamp is only there for when it does not.
            when.getStyleClass().add("identity-when");
            exact.getStyleClass().addAll("identity-when", "identity-when-exact");
            HBox whenRow = new HBox(5, when, exact);
            whenRow.setAlignment(Pos.CENTER_LEFT);
            VBox who = new VBox(UiScale.TIGHT, authorName, authorMail, whenRow);
            HBox identity = new HBox(UiScale.GAP, avatar, who);
            identity.setAlignment(Pos.TOP_LEFT);

            // The hash is the field that gets pasted into a terminal; see identityLine.
            hash.getStyleClass().add("hash-chip");
            hash.setTooltip(hashTip);
            hash.setOnMouseClicked(e -> {
                if (commit != null) {
                    copyToClipboard(commit.getHash(), "Copied " + commit.getShortHash());
                }
            });
            identityRow.setAlignment(Pos.CENTER_LEFT);

            subject.getStyleClass().add("t-title");
            subject.setWrapText(true);
            body.getStyleClass().add("commit-body");
            body.setWrapText(true);
            messageBox.getChildren().add(subject);
            ContextMenu menu = new ContextMenu();
            copyMessage.setOnAction(e -> {
                if (fullMessage != null) copyToClipboard(fullMessage, "Message copied");
            });
            MenuItem copySubject = new MenuItem("Copy subject");
            copySubject.setOnAction(e -> {
                if (commit != null) copyToClipboard(commit.getSubject(), "Subject copied");
            });
            menu.getItems().addAll(copyMessage, copySubject);
            messageBox.setOnContextMenuRequested(e -> menu.show(messageBox, e.getScreenX(), e.getScreenY()));

            nodes = List.of(identity, refs, identityRow, divider(),
                    // Its own height, with a drag handle under it: in the column directly,
                    // the files list below took the space and a long message ended in "...".
                    new CommitMessagePane(messageBox),
                    files.header, files.body());
        }

        void show(CommitInfo next) {
            commit = next;
            fullMessage = null;
            avatar.setAuthor(next.getAuthorName(), next.getAuthorEmail());
            authorName.setText(next.getAuthorName());
            authorMail.setText(next.getAuthorEmail());
            when.setText(com.jfx.git.graph.RelativeTime.of(next.getAuthorDate()));
            exact.setText("· " + DateDisplay.absolute(next.getAuthorDate()));
            fillRefChips(refs, next);
            fillIdentityLine(next);
            fillMessage(next);
        }

        /**
         * The hash, and the way back to the commit's parents.
         *
         * <p>Both are things you act on. The hash is the field that gets pasted into a
         * terminal, and the graph already teaches that it is clickable by underlining it
         * on row hover. A parent is the one navigation step this panel could not make, so
         * reading a merge meant going back to the list and finding the row by eye. The
         * parents are a link or two, so they are simply made again.
         *
         * <p>Checking a signature is real work on the native engine (a subprocess call),
         * so it is fetched after the row is already on screen rather than blocking the
         * selection on it - the same reason the files load async. Added only once known
         * and only when there is something to say: an "Unsigned" chip on every ordinary
         * commit would be noise, not information.
         */
        private void fillIdentityLine(CommitInfo next) {
            String full = next.getHash();
            hash.setText(full.length() > 10 ? full.substring(0, 10) : full);
            hashTip.setText("Copy " + full);
            identityRow.getChildren().setAll(hash);
            List<String> parents = next.getParentHashes();
            if (!parents.isEmpty()) {
                Label label = new Label(parents.size() == 1 ? "parent" : "parents");
                label.getStyleClass().add("t-caption");
                identityRow.getChildren().add(label);
                for (String parent : parents) {
                    identityRow.getChildren().add(parentLink(parent));
                }
            }
            long request = ++signatureBadgeRequestId;
            GitTaskRunner.queryLatest(gitService, signatureLookups,
                    () -> gitService.getSignatureStatus(full),
                    status -> {
                        if (request != signatureBadgeRequestId || !status.isSigned()) return;
                        identityRow.getChildren().add(signatureBadge(status));
                    },
                    error -> { });
        }

        /**
         * Subject and body.
         *
         * <p>This was a read-only {@code TextArea}: a control you cannot type into that
         * looks exactly like one you can, reserving four rows of height whether the
         * message was one line or forty. Labels grow to their content and stop. The one
         * thing the text box was genuinely good for - getting the message back out - is
         * kept as an explicit action rather than as a side effect of being a text box.
         *
         * <p>A graph row carries only its subject (G15), so the subject is shown at once
         * and the body is fetched off the FX thread and added when it arrives - the way
         * the signature badge is. "Copy message" waits for it too, rather than copying a
         * subject and calling it the message.
         */
        private void fillMessage(CommitInfo next) {
            subject.setText(next.getSubject());
            messageBox.getChildren().remove(body);
            long request = ++commitMessageRequestId;
            if (next.hasFullMessage()) {
                showFullMessage(next.getFullMessage());
            } else {
                copyMessage.setDisable(true);
                GitTaskRunner.queryLatest(gitService, messageLookups,
                        () -> gitService.getCommitMessage(next.getHash()),
                        full -> {
                            // Another commit selected since: its own request owns the panel.
                            if (request != commitMessageRequestId || full == null) return;
                            showFullMessage(full);
                        },
                        error -> { });
            }
        }

        private void showFullMessage(String full) {
            fullMessage = full;
            String text = CommitInfo.bodyOf(full).strip();
            if (!text.isBlank()) {
                body.setText(text);
                if (!messageBox.getChildren().contains(body)) {
                    messageBox.getChildren().add(body);
                }
            }
            copyMessage.setDisable(false);
        }
    }

    /** A parent's short hash, which selects it in the graph. */
    private Label parentLink(String parent) {
        Label link = new Label(parent.length() >= 7 ? parent.substring(0, 7) : parent);
        link.getStyleClass().add("parent-link");
        link.setTooltip(new Tooltip("Select " + parent + " in the graph"));
        link.setOnMouseClicked(e -> {
            if (onSelectCommit != null) onSelectCommit.accept(parent);
        });
        return link;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Opens a path from the repository browser.
     *
     * <p>A file with uncommitted changes opens as a diff, because that is what there is
     * to look at. One that matches HEAD has no diff at all, so it opens as content — the
     * point of browsing the whole tree is to reach files you have <em>not</em> edited.
     */
    public void openWorkingTreeFile(String path) {
        long myRequest = beginDiffRequest(path);
        GitTaskRunner.queryLatest(gitService, diffLoads,
                () -> {
                    byte[] diff = gitService.getUnstagedDiffBytes(path, diffViewer.isIgnoreWhitespace());
                    if (diff != null && diff.length > 0) {
                        return new Opened(Opened.Kind.UNSTAGED, diff,
                                workingTreeImageSource(path).get());
                    }
                    byte[] staged = gitService.getStagedDiffBytes(path, diffViewer.isIgnoreWhitespace());
                    if (staged != null && staged.length > 0) {
                        return new Opened(Opened.Kind.STAGED, staged, null);
                    }
                    return new Opened(Opened.Kind.FILE, gitService.getWorkingTreeBytes(path), null);
                },
                opened -> {
                    if (myRequest != diffRequestId) return;
                    // The working tree: its images are the working tree's.
                    diffViewer.setRevisionReader(null);
                    lastRevisionReader = null;
                    boolean conflicted = stagingState != null
                            && stagingState.conflicting().contains(path);
                    diffViewer.setEditable(true);
                    switch (opened.kind()) {
                        case UNSTAGED -> {
                            DiffKind kind = conflicted ? DiffKind.CONFLICT : DiffKind.UNSTAGED;
                            diffViewer.setComparedSides(DiffViewer.DiffSides.UNSTAGED);
                            diffViewer.setExternalDiffTarget(conflicted
                                    ? null : com.jfx.git.core.ExternalTools.DiffTarget::unstaged);
                            lastDiffPath = path;
                            lastDiffKind = kind;
                            lastWholeFileSupplier = null;
                            lastDiffSupplier = () ->
                                    gitService.getUnstagedDiffBytes(path, diffViewer.isIgnoreWhitespace());
                            lastImageSourceSupplier = workingTreeImageSource(path);
                            diffViewer.setImageSupplier(ignored -> opened.imageBlobs());
                            diffViewer.setFileDiffBytes(kind, path, opened.bytes());
                        }
                        // Reloaded through the staged route rather than shown from these
                        // bytes, so the context between hunks comes from the index and a
                        // later reload replays a staged diff, not an unstaged one.
                        case STAGED -> showStagedDiff(path);
                        case FILE -> {
                            diffViewer.setComparedSides(DiffViewer.DiffSides.WORKING_TREE);
                            lastDiffPath = null;
                            lastDiffSupplier = null;
                            lastWholeFileSupplier = null;
                            lastImageSourceSupplier = null;
                            diffViewer.setImageSupplier(null);
                            diffViewer.showFileBytes(path, opened.bytes());
                        }
                    }
                },
                message -> diffRequestFailed(myRequest, message));
    }

    /**
     * Clears whichever list selection last opened a file, so that re-selecting the
     * same row after closing the diff viewer is a real selection change and reopens
     * it, rather than a no-op the list's own {@code selectedItemProperty} never fires
     * for. Called from the host when the diff viewer's path becomes empty.
     */
    public void clearFileSelectionAfterClose() {
        Runnable clear = clearActiveSelection;
        clearActiveSelection = () -> {};
        clear.run();
        // Nothing is open, so there is nothing to reload.
        lastDiffPath = null;
        lastDiffSupplier = null;
        lastWholeFileSupplier = null;
        lastRevisionReader = null;
        lastImageSourceSupplier = null;
    }

    /**
     * The older side of a commit's diff: its first parent, which is what the diff is
     * taken against, or a root commit's empty starting point.
     */
    static String parentLabel(CommitInfo commit) {
        List<String> parents = commit.getParentHashes();
        if (parents == null || parents.isEmpty()) {
            return "Empty tree";
        }
        String parent = parents.get(0);
        return parent.length() > 7 ? parent.substring(0, 7) : parent;
    }

    /** What {@link #openWorkingTreeFile} found: a diff on either side, or the file itself. */
    private record Opened(Kind kind, byte[] bytes, ImageDiffView.Blobs imageBlobs) {
        enum Kind { UNSTAGED, STAGED, FILE }
    }

    /**
     * Shows a diff of the working tree, whose context comes from the working tree too.
     *
     * @param kind what it compares, which decides the hunk actions it offers
     */
    private void showDiff(DiffKind kind, String path, java.util.function.Supplier<byte[]> supplier,
                          java.util.function.Supplier<ImageDiffView.Blobs> imageSourceSupplier) {
        // Remembered so toggling ignore-whitespace can recompute the same diff.
        lastDiffPath = path;
        lastDiffKind = kind;
        lastDiffSupplier = supplier;
        lastWholeFileSupplier = null;
        lastRevisionReader = null;
        lastImageSourceSupplier = imageSourceSupplier;
        long myRequest = beginDiffRequest(path);
        GitTaskRunner.queryLatest(gitService, diffLoads,
                () -> new DiffAndWholeFile(supplier.get(), null,
                        imageSourceSupplier == null ? null : imageSourceSupplier.get()),
                diff -> {
                    if (myRequest != diffRequestId) return;
                    diffViewer.setImageSupplier(ignored -> diff.imageBlobs());
                    diffViewer.setRevisionReader(null);
                    diffViewer.setFileDiffBytes(kind, path, diff.diff());
                },
                message -> diffRequestFailed(myRequest, message));
    }

    /** Pairs a diff with the whole file it is against, for {@link #showDiff(DiffKind, String, Supplier, Supplier, Function, Supplier)}. */
    private record DiffAndWholeFile(byte[] diff, byte[] wholeFile,
                                    ImageDiffView.Blobs imageBlobs) {}

    /**
     * Like {@link #showDiff(DiffKind, String, Supplier, Supplier)}, but also supplies the
     * whole-file content this diff is against, so File view and the context between hunks
     * show this historical, stashed, or compared revision rather than the generic
     * supplier's (current working-tree) content.
     */
    private void showDiff(DiffKind kind, String path, java.util.function.Supplier<byte[]> diffSupplier,
                          java.util.function.Supplier<byte[]> wholeFileSupplier,
                          java.util.function.Function<String, byte[]> revisionReader,
                          java.util.function.Supplier<ImageDiffView.Blobs> imageSourceSupplier) {
        showDiff(kind, path, diffSupplier, wholeFileSupplier, true, revisionReader, imageSourceSupplier);
    }

    /**
     * HEAD against the index: the context between hunks is the staged copy. An unmerged
     * path's staged side is a conflict, whose hunks cannot be unstaged one by one.
     */
    private void showStagedDiff(String path) {
        diffViewer.setComparedSides(DiffViewer.DiffSides.STAGED);
        diffViewer.setExternalDiffTarget(com.jfx.git.core.ExternalTools.DiffTarget::staged);
        showDiff(stagingConflicts.contains(path) ? DiffKind.CONFLICT : DiffKind.STAGED, path,
                () -> gitService.getStagedDiffBytes(path, diffViewer.isIgnoreWhitespace()),
                () -> gitService.getStagedBytes(path),
                false, null, stagedImageSource(path));
    }

    /** The index (or HEAD when absent) against the current working-tree file. */
    private java.util.function.Supplier<ImageDiffView.Blobs> workingTreeImageSource(String path) {
        return imageSource(path, () -> {
            byte[][] sides = gitService.getWorkingTreeSides(path);
            return new ImageDiffView.Blobs(sides[0], sides[1]);
        });
    }

    /** HEAD against the index, matching the staged diff's new side. */
    private java.util.function.Supplier<ImageDiffView.Blobs> stagedImageSource(String path) {
        return imageSource(path, () -> new ImageDiffView.Blobs(
                gitService.getFileBytesAtCommit(gitService.getHeadHash(), path),
                gitService.getStagedBytes(path)));
    }

    /** Two historical blobs; {@code beforeRevision == null} means the empty tree. */
    private java.util.function.Supplier<ImageDiffView.Blobs> revisionImageSource(
            String path, String beforeRevision, String beforePath,
            String afterRevision, String afterPath) {
        return imageSource(path, () -> new ImageDiffView.Blobs(
                beforeRevision == null ? new byte[0]
                        : gitService.getFileBytesAtCommit(beforeRevision, beforePath),
                gitService.getFileBytesAtCommit(afterRevision, afterPath)));
    }

    /** Avoids reading image-side blobs for ordinary text files. Called only on a Git worker. */
    private static java.util.function.Supplier<ImageDiffView.Blobs> imageSource(
            String path, java.util.function.Supplier<ImageDiffView.Blobs> source) {
        return ImageDiffView.isImage(path) ? source : () -> null;
    }

    /**
     * @param wholeFileIsView whether File view shows {@code wholeFileSupplier}'s content,
     *                        or keeps the working tree and uses it only for the diff's
     *                        context; see {@link #lastWholeFileIsView}
     * @param kind            what the diff compares, which decides its hunk actions
     * @param revisionReader  reads any other path of the same revision, for a Markdown
     *                        preview's images; {@code null} for the working tree
     */
    private void showDiff(DiffKind kind, String path, java.util.function.Supplier<byte[]> diffSupplier,
                          java.util.function.Supplier<byte[]> wholeFileSupplier,
                          boolean wholeFileIsView,
                          java.util.function.Function<String, byte[]> revisionReader,
                          java.util.function.Supplier<ImageDiffView.Blobs> imageSourceSupplier) {
        lastDiffPath = path;
        lastDiffKind = kind;
        lastDiffSupplier = diffSupplier;
        lastWholeFileSupplier = wholeFileSupplier;
        lastWholeFileIsView = wholeFileIsView;
        lastRevisionReader = revisionReader;
        lastImageSourceSupplier = imageSourceSupplier;
        long myRequest = beginDiffRequest(path);
        GitTaskRunner.queryLatest(gitService, diffLoads,
                () -> new DiffAndWholeFile(diffSupplier.get(), wholeFileSupplier.get(),
                        imageSourceSupplier == null ? null : imageSourceSupplier.get()),
                both -> {
                    if (myRequest != diffRequestId) return;
                    diffViewer.setImageSupplier(ignored -> both.imageBlobs());
                    diffViewer.setRevisionReader(revisionReader);
                    if (wholeFileIsView) {
                        diffViewer.setFileDiffBytes(kind, path, both.diff(), both.wholeFile());
                    } else {
                        diffViewer.setStagedDiffBytes(kind, path, both.diff(), both.wholeFile());
                    }
                },
                message -> diffRequestFailed(myRequest, message));
    }

    /**
     * Claims the viewer for a new diff request and says so on screen.
     *
     * <p>The loading state matters as much as the request id. Leaving the previous file's
     * diff up while the next one loads is not a neutral wait: the actions beside it stage
     * and revert, and they would be aimed at content that is no longer what the user
     * selected.
     */
    private long beginDiffRequest(String path) {
        diffViewer.setImageSupplier(null);
        diffViewer.showLoading(path);
        return ++diffRequestId;
    }

    /** Reports a diff that failed, unless something newer has already replaced it. */
    private void diffRequestFailed(long myRequest, String message) {
        if (myRequest != diffRequestId) return;
        diffViewer.showLoadFailed(message);
        statusBar.error(message);
    }

    /**
     * Re-runs the last diff request, picking up the current whitespace setting.
     *
     * <p>Package-visible because {@link RepoTab} calls it when the repository turns out to
     * have changed behind the window: the file lists were being rebuilt without the diff
     * beside them, so a file edited elsewhere kept showing the version this application
     * last read.
     */
    void reloadDiff() {
        // Only a diff that is still on screen. The last request outlives the viewer being
        // closed, and replaying it reopened a file the user had just put away - over the
        // graph, on nothing more than returning to the window.
        if (lastDiffPath == null || !lastDiffPath.equals(diffViewer.filePathProperty().get())) {
            return;
        }
        if (lastDiffSupplier != null && lastWholeFileSupplier != null) {
            showDiff(lastDiffKind, lastDiffPath, lastDiffSupplier, lastWholeFileSupplier, lastWholeFileIsView,
                    lastRevisionReader, lastImageSourceSupplier);
        } else if (lastDiffSupplier != null) {
            showDiff(lastDiffKind, lastDiffPath, lastDiffSupplier, lastImageSourceSupplier);
        }
    }

    public void setOnShowFileHistory(java.util.function.BiConsumer<String, Boolean> handler) {
        this.onShowFileHistory = handler;
    }

    /** Where "Run script…" opens its view: this repository's tab. */
    private RepoViewHost viewHost = RepoViewHost.NONE;

    /** Set by the tab, so a bisect run is watched inside it rather than in a window. */
    public void setViewHost(RepoViewHost host) {
        this.viewHost = host == null ? RepoViewHost.NONE : host;
    }

    /** Supplies the action that moves the graph's selection to a given commit. */
    public void setOnSelectCommit(java.util.function.Consumer<String> handler) {
        this.onSelectCommit = handler;
    }

    /** Opens the file-history view on the history tab. */
    public void showFileHistory(String path) {
        if (onShowFileHistory != null) {
            onShowFileHistory.accept(path, false);
        }
    }

    /** Opens the same view with blame annotations turned on. */
    public void showBlame(String path) {
        if (onShowFileHistory != null) {
            onShowFileHistory.accept(path, true);
        }
    }

    /**
     * Applies part of a working-tree change to the index, or takes part of a staged one
     * back out.
     *
     * <p>The file legitimately ends up in both lists afterwards - part of it staged, the
     * rest not - so the whole panel is rebuilt rather than the one row updated.
     */
    private void applyToIndex(HunkAction hunkAction) {
        String path = hunkAction.path();
        String action = (hunkAction.type() == HunkAction.Type.STAGE ? "Staged " : "Unstaged ")
                + hunkAction.linesPhrase();
        // Only the index changed, so reloading the staging panel is enough - and a hunk that
        // did not apply changed nothing at all.
        operations.operation(action + " of " + path, OperationRunner.Refresh.STAGING)
                .refreshWhen(GitResult::isOk)
                .run(() -> gitService.applyToIndex(path, hunkAction.edit(), action));
    }

    private void runAndRefresh(java.util.function.Supplier<GitResult> work, String description) {
        // Reload the staging panel only – the commit graph has not changed. If the tree
        // becomes fully clean, buildStagingUI refreshes the rest to remove the WIP node
        // from the graph.
        operations.run(description, OperationRunner.Refresh.STAGING, work);
    }

    // ------------------------------------------------------------------
    // Shared pieces of a details view
    // ------------------------------------------------------------------

    /** Diameter of the author image, matching the graph's expressive row. */
    private static final int AVATAR_SIZE = 34;

    /** Squares in the diffstat bar. */
    private static final int STAT_SQUARES = 8;

    /**
     * A titled list of changed files, with a diffstat beside the title.
     *
     * <p>Identical in all three read-only modes, because they are all answering the same
     * question. They used to build three slightly different versions of it.
     *
     * <h2>Kept, not rebuilt</h2>
     * Each mode holds one of these for as long as the panel lives and refills it for the
     * next commit, stash or comparison. A new one per selection meant a new list and a new
     * tree with new skins and no cells to reuse, so walking history with J/K built two
     * virtual flows per keystroke and threw the last two away.
     */
    private final class FilesSection {
        final Label title = new Label();
        final Label counts = new Label();
        final HBox bar = new HBox(2);
        final HBox header;
        final ListView<com.jfx.git.core.GitBackend.FileChange> list = new ListView<>();
        /** The same files as a folder hierarchy; see {@link #buildTree}. */
        private final javafx.scene.control.TreeView<FileNode> tree = new javafx.scene.control.TreeView<>();
        /** Holds whichever of the two is showing; both exist, one is visible and filled. */
        private final StackPane content;
        private final String titleText;

        /** Drawn instead of the two views when neither has a row to show. */
        private final Label emptyState = placeholderLabel("");

        /** True while {@link #emptyState} is the thing on screen. */
        private boolean showingNothing;
        private final javafx.scene.control.TextField filterField =
                new javafx.scene.control.TextField();
        private final Label matchCount = new Label();
        private final ToggleGroup shape = new ToggleGroup();
        private final ToggleButton pathTab = new ToggleButton("Path");
        private final ToggleButton treeTab = new ToggleButton("Tree");
        private final Button foldButton = new Button("Collapse all");
        /** Every file in the change, before the filter narrows it. */
        private List<com.jfx.git.core.GitBackend.FileChange> all = List.of();
        /** What the filter lets through: what the visible view holds, or will. */
        private List<com.jfx.git.core.GitBackend.FileChange> shown = List.of();
        /**
         * Whether the list or the tree is behind {@link #shown}. Only the shape on screen
         * is filled; the other is filled when it is chosen. A two-thousand-file commit
         * used to build its folder tree on every selection and every filter keystroke
         * for a tree nobody had asked to see.
         */
        private boolean listStale;
        private boolean treeStale;
        /** True from a reset until the files arrive, so the empty state does not claim the commit is empty. */
        private boolean loading;
        /** Bumped for each load, so an answer for an earlier selection is not applied to a later one. */
        long loadToken;
        /** Whether there is anything to filter; the filter bar shows only then. */
        private final javafx.beans.property.BooleanProperty hasFiles =
                new javafx.beans.property.SimpleBooleanProperty();
        /**
         * Waits for typing to pause before filtering. Each keystroke used to re-filter
         * and refill both views at once; on a large commit that was the whole of a
         * frame per character, and a fast typist's intermediate queries are never read.
         */
        private final javafx.animation.PauseTransition filterPause =
                new javafx.animation.PauseTransition(javafx.util.Duration.millis(FILTER_DELAY_MILLIS));
        /** What to open when a row is picked, whichever view the row came from. */
        private java.util.function.Consumer<com.jfx.git.core.GitBackend.FileChange> onSelect =
                change -> { };
        /**
         * The commit a file can be restored from, or null where restoring makes no
         * sense (a stash view is not a state you would copy over the work tree).
         */
        String restoreSource;

        FilesSection(String titleText) {
            this.titleText = titleText;
            title.setText(titleText);
            title.getStyleClass().add("section-title");
            bar.setAlignment(Pos.CENTER_LEFT);

            // Path or Tree: a flat list answers "what did this commit touch", and a tree
            // answers "which parts of the project did it touch" - a hundred-file commit
            // across four modules is unreadable as a list of a hundred paths sharing
            // prefixes, and obvious as four folders.
            pathTab.setToggleGroup(shape);
            treeTab.setToggleGroup(shape);
            pathTab.getStyleClass().addAll("composer-tab",
                    "changed-files-path-toggle");
            treeTab.getStyleClass().addAll("composer-tab",
                    "changed-files-tree-toggle");
            shape.selectedToggleProperty().addListener((o, was, now) -> {
                if (now == null && was != null) shape.selectToggle(was);
            });

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox shapeRow = new HBox(UiScale.TIGHT, pathTab, treeTab);
            shapeRow.setAlignment(Pos.CENTER_LEFT);
            header = new HBox(UiScale.STEP, title, spacer, counts, bar, shapeRow);
            header.setAlignment(Pos.CENTER_LEFT);

            // The second class names the role rather than the look: the panel holds
            // several lists and trees, and both the stylesheet and the robot tests need
            // to be able to say "the changed files one" and mean it.
            list.getStyleClass().addAll("sidebar-list", "changed-files-list");
            list.setFixedCellSize(UiScale.ROW_HEIGHT);
            list.setCellFactory(lv -> new FileChangeCell(this));
            VBox.setVgrow(list, Priority.ALWAYS);

            tree.getStyleClass().addAll("sidebar-list", "changed-files-tree");
            tree.setShowRoot(false);
            tree.setFixedCellSize(UiScale.ROW_HEIGHT);
            tree.setCellFactory(tv -> new FileNodeCell(this));
            VBox.setVgrow(tree, Priority.ALWAYS);

            // One selection at a time across the two views: a row highlighted in the
            // list while a different file's diff is on screen from the tree is a lie
            // about what is being looked at.
            list.getSelectionModel().selectedItemProperty().addListener((o, was, change) -> {
                if (change == null) return;
                tree.getSelectionModel().clearSelection();
                onSelect.accept(change);
            });
            tree.getSelectionModel().selectedItemProperty().addListener((o, was, item) -> {
                if (item == null || item.getValue() == null) return;
                var change = item.getValue().change();
                if (change == null) return; // a folder is not a thing to diff
                list.getSelectionModel().clearSelection();
                onSelect.accept(change);
            });

            filterField.getStyleClass().addAll("file-filter-field", "changed-files-filter");
            filterField.setPromptText("Filter files…");
            filterPause.setOnFinished(e -> applyFilter());
            filterField.textProperty().addListener((o, was, now) -> {
                // Emptying the box is one step, not the start of a burst, and puts every
                // file back at once.
                if (now == null || now.isEmpty()) {
                    filterPause.stop();
                    applyFilter();
                } else {
                    filterPause.playFromStart();
                }
            });
            matchCount.getStyleClass().add("count-badge");
            showOnlyWithText(matchCount);

            foldButton.getStyleClass().addAll("btn-ghost", "row-action");
            foldButton.setMinWidth(Region.USE_PREF_SIZE);
            foldButton.setOnAction(e -> setAllExpanded(!userWantsExpanded));

            // A third layer for "there is nothing here". A TreeView has no placeholder of
            // its own, and a filter that matches nothing left both views drawing an empty
            // bordered box - the same thing a panel that failed to load looks like.
            emptyState.getStyleClass().add("changed-files-empty");
            emptyState.setVisible(false);
            emptyState.setManaged(false);
            content = new StackPane(list, tree, emptyState);
            StackPane.setAlignment(list, Pos.TOP_LEFT);
            StackPane.setAlignment(tree, Pos.TOP_LEFT);
            VBox.setVgrow(content, Priority.ALWAYS);

            pathTab.setOnAction(e -> applyShape());
            treeTab.setOnAction(e -> applyShape());
            shape.selectToggle(showingFileTree ? treeTab : pathTab);
            applyShape();
        }

        /**
         * Readies the section for another commit, stash or comparison: no files, no
         * filter, no selection, folders open, and the Path/Tree choice the panel last
         * made - which another mode's section may have changed since.
         */
        void reset() {
            loadToken++;
            loading = true;
            title.setText(titleText);
            filterPause.stop();
            // Emptied first, so clearing the filter below does not refill the views with
            // the last commit's files on its way to none.
            all = List.of();
            userWantsExpanded = true;
            if (!filterField.getText().isEmpty()) {
                filterField.setText("");
            }
            shape.selectToggle(showingFileTree ? treeTab : pathTab);
            clearSelection();
            setItems(List.of());
        }

        /**
         * The filter bar and the two views, ready to drop below {@link #header}.
         *
         * <p>The filter is hidden until there are enough files for it to be worth
         * reading past: on a three-file commit it is a control that costs a row and
         * saves nothing.
         */
        javafx.scene.Node body() {
            // Built once and handed back thereafter: a node has one parent, and a second
            // call would silently move the two views out of the first caller's layout.
            if (body != null) return body;
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox filterBar = new HBox(UiScale.STEP, filterField, spacer, matchCount, foldButton);
            filterBar.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(filterField, Priority.ALWAYS);
            filterBar.visibleProperty().bind(hasFiles.or(filterField.textProperty().isNotEmpty()));
            filterBar.managedProperty().bind(filterBar.visibleProperty());
            body = new VBox(UiScale.SNUG, filterBar, content);
            VBox.setVgrow(body, Priority.ALWAYS);
            return body;
        }

        /** Built on first request; see {@link #body()}. */
        private VBox body;

        /** What to do when a row is chosen, in either view. */
        void setOnSelect(java.util.function.Consumer<com.jfx.git.core.GitBackend.FileChange> handler) {
            this.onSelect = handler == null ? change -> { } : handler;
        }

        /** Clears whichever view holds the current selection. */
        void clearSelection() {
            list.getSelectionModel().clearSelection();
            tree.getSelectionModel().clearSelection();
        }

        /**
         * Selects a file by path in whichever view is showing, which opens it as a click
         * on its row would. A renamed file also answers to the name it had, so swapping a
         * comparison - where the old and new names trade places - still finds it.
         */
        void select(String path) {
            var match = shown.stream()
                    .filter(c -> path.equals(c.path()))
                    .findFirst()
                    .or(() -> shown.stream()
                            .filter(c -> path.equals(c.oldPath()))
                            .findFirst())
                    .orElse(null);
            if (match == null) return;
            if (treeTab.isSelected()) {
                var item = findTreeItem(tree.getRoot(), match);
                if (item == null) return;
                tree.getSelectionModel().select(item);
                int row = tree.getRow(item);
                if (row >= 0) tree.scrollTo(row);
            } else {
                list.getSelectionModel().select(match);
                list.scrollTo(match);
            }
        }

        private javafx.scene.control.TreeItem<FileNode> findTreeItem(
                javafx.scene.control.TreeItem<FileNode> item,
                com.jfx.git.core.GitBackend.FileChange change) {
            if (item == null) return null;
            if (item.getValue() != null && change.equals(item.getValue().change())) return item;
            for (var child : item.getChildren()) {
                var found = findTreeItem(child, change);
                if (found != null) return found;
            }
            return null;
        }

        void setItems(List<com.jfx.git.core.GitBackend.FileChange> changes) {
            this.all = changes == null ? List.of() : changes;
            hasFiles.set(!all.isEmpty());
            filterPause.stop();
            applyFilter();
        }

        /** The files arrived: from here an empty section means an empty change. */
        /** The first file listed, before any filter, or {@code null} when there are none. */
        com.jfx.git.core.GitBackend.FileChange firstChange() {
            return all.isEmpty() ? null : all.get(0);
        }

        void loaded(List<com.jfx.git.core.GitBackend.FileChange> changes) {
            loading = false;
            setItems(changes);
        }

        private void applyFilter() {
            String query = filterField.getText();
            boolean filtering = query != null && !query.isBlank();
            String needle = filtering
                    ? query.strip().toLowerCase(java.util.Locale.ROOT) : "";
            shown = filtering
                    ? all.stream()
                            .filter(c -> c.path().toLowerCase(java.util.Locale.ROOT).contains(needle))
                            .toList()
                    : all;
            this.filtering = filtering;

            listStale = true;
            treeStale = true;
            matchCount.setText(filtering
                    ? shown.size() + (shown.size() == 1 ? " match" : " matches") : "");

            // Says which kind of nothing this is. "No match" is the user's own filter
            // answering them; "changed no files" is a fact about the commit. Telling them
            // apart is the whole value - an unexplained empty box reads as a failure.
            showingNothing = shown.isEmpty();
            emptyState.setText(!showingNothing || loading ? ""
                    : filtering
                            ? "No file here matches " + query.strip()
                            : "This commit changed no files.");
            applyShape();
        }

        /** Whether the last filter pass had a query; the tree opens itself for one. */
        private boolean filtering;

        private void applyShape() {
            boolean asTree = treeTab.isSelected();
            showingFileTree = asTree;
            // Only the shape on show is filled; the other catches up when it is chosen.
            if (asTree && treeStale) {
                treeStale = false;
                tree.setRoot(buildTree(shown));
                // A filtered tree opens itself: the whole point of narrowing to four files
                // is seeing them, not the four folders they happen to live under. Without
                // overwriting the user's own choice, though - clearing the filter has to
                // give back the collapsed tree they left, not silently reopen it.
                applyExpansion(filtering || userWantsExpanded);
            } else if (!asTree && listStale) {
                listStale = false;
                list.getItems().setAll(shown);
            }
            list.setVisible(!asTree && !showingNothing);
            list.setManaged(!asTree && !showingNothing);
            tree.setVisible(asTree && !showingNothing);
            tree.setManaged(asTree && !showingNothing);
            emptyState.setVisible(showingNothing);
            emptyState.setManaged(showingNothing);
            // Nothing to fold when nothing is drawn.
            foldButton.setVisible(asTree && !showingNothing);
            foldButton.setManaged(foldButton.isVisible());
        }

        /**
         * The user's own answer to "open or folded", independent of the filter.
         *
         * <p>Open by default: a tree that shows only {@code src} until you click it is
         * not showing you a commit's files, it is hiding them.
         */
        private boolean userWantsExpanded = true;

        /** What {@link #foldButton} does: records the choice, then applies it. */
        private void setAllExpanded(boolean expanded) {
            userWantsExpanded = expanded;
            applyExpansion(expanded);
        }

        /**
         * Opens or folds the tree as it stands, without claiming the user asked.
         *
         * <h2>The root is never folded</h2>
         * This tree is drawn with {@code setShowRoot(false)}, so the root is a container
         * the user cannot see and has no row to click. A collapsed {@code TreeItem} hides
         * its children, and hiding the invisible root's children means hiding every row
         * there is - so folding the root turned "Collapse all" into "show an empty box",
         * with no way back except Expand all. Folding starts one level down, at the rows
         * that actually exist on screen.
         */
        private void applyExpansion(boolean expanded) {
            var root = tree.getRoot();
            if (root != null) {
                root.setExpanded(true);
                for (var child : root.getChildren()) {
                    expandAll(child, expanded);
                }
            }
            foldButton.setText(expanded ? "Collapse all" : "Expand all");
        }

        private static void expandAll(javafx.scene.control.TreeItem<FileNode> item, boolean expanded) {
            if (item.isLeaf()) return;
            item.setExpanded(expanded);
            for (var child : item.getChildren()) {
                expandAll(child, expanded);
            }
        }

        /** Offers "restore this file" against {@code commitHash}; see the cell's menu. */
        void setRestoreSource(String commitHash) {
            this.restoreSource = commitHash;
        }
    }

    /** How long the file filters wait for typing to pause; see {@code FilesSection.filterPause}. */
    static final int FILTER_DELAY_MILLIS = 120;

    /**
     * A row in the file tree: either a folder, or a file and the change it carries.
     *
     * <p>Folders keep their own tallies rather than recomputing them per repaint - a
     * tree cell is asked for its content every time it scrolls into view, and walking a
     * subtree on each of those turns a large commit's file list into a stutter.
     */
    private static final class FileNode {
        private final String name;
        private final String path;
        private final com.jfx.git.core.GitBackend.FileChange change;
        private int added;
        private int modified;
        private int deleted;

        FileNode(String name, String path, com.jfx.git.core.GitBackend.FileChange change) {
            this.name = name;
            this.path = path;
            this.change = change;
        }

        String name() { return name; }
        String path() { return path; }
        /** The file's change, or null when this is a folder. */
        com.jfx.git.core.GitBackend.FileChange change() { return change; }
        boolean isFolder() { return change == null; }
        int added() { return added; }
        int modified() { return modified; }
        int deleted() { return deleted; }

        void count(char status) {
            switch (Character.toUpperCase(status)) {
                case 'A', '?' -> added++;
                case 'D' -> deleted++;
                default -> modified++;
            }
        }

        /**
         * The segment shown on the row.
         *
         * <p>Worth defining rather than inheriting: a {@code TreeItem}'s value is what
         * the toolkit falls back to for accessibility text, and it is what shows up in
         * any dump of the tree. The identity hash is useful to nobody.
         */
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Folds a flat list of paths into a folder hierarchy, tallying each folder as it goes.
     *
     * <p>Folders are not collapsed into {@code a/b/c} chains: the tree exists to show
     * where in the project a change landed, and a chain that skips levels makes two
     * commits touching neighbouring modules look like they touched unrelated ones.
     */
    private static javafx.scene.control.TreeItem<FileNode> buildTree(
            List<com.jfx.git.core.GitBackend.FileChange> changes) {
        var root = new javafx.scene.control.TreeItem<>(new FileNode("", "", null));
        var folders = new java.util.HashMap<String, javafx.scene.control.TreeItem<FileNode>>();
        folders.put("", root);

        for (var change : changes) {
            String path = change.path();
            String[] parts = path.split("/");
            var parent = root;
            StringBuilder sofar = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (sofar.length() > 0) sofar.append('/');
                sofar.append(parts[i]);
                String key = sofar.toString();
                var existing = folders.get(key);
                if (existing == null) {
                    existing = new javafx.scene.control.TreeItem<>(
                            new FileNode(parts[i], key, null));
                    existing.setExpanded(true);
                    folders.put(key, existing);
                    parent.getChildren().add(existing);
                }
                existing.getValue().count(change.status());
                parent = existing;
            }
            parent.getChildren().add(new javafx.scene.control.TreeItem<>(
                    new FileNode(parts[parts.length - 1], path, change)));
        }

        sortTree(root);
        return root;
    }

    /** Folders before files, each alphabetical - the order every file browser uses. */
    private static void sortTree(javafx.scene.control.TreeItem<FileNode> item) {
        item.getChildren().sort(java.util.Comparator
                .comparing((javafx.scene.control.TreeItem<FileNode> child) ->
                        child.getValue().isFolder() ? 0 : 1)
                .thenComparing(child -> child.getValue().name(),
                        String.CASE_INSENSITIVE_ORDER));
        for (var child : item.getChildren()) {
            if (!child.getValue().isFolder()) continue;
            sortTree(child);
        }
    }

    /**
     * Reads the first file's diff ahead of the click that usually follows, into the
     * engine's commit cache (SwitchableGitBackend), when the lane has nothing else to do.
     * The same two reads the click makes - the diff and the file it is against - so the
     * click finds both kept and shows them without waiting on git.
     */
    private void prefetchFirstDiff(CommitInfo commit, FilesSection files) {
        var first = files.firstChange();
        if (first == null) return;
        boolean ignoreWhitespace = diffViewer.isIgnoreWhitespace();
        String hash = commit.getHash();
        GitTaskRunner.prefetch(gitService, () -> {
            gitService.getDiffBytesForFileInCommit(hash, first.path(), first.oldPath(), ignoreWhitespace);
            gitService.getFileBytesAtCommit(hash, first.path());
        });
    }

    /** Stash rows and the revision that owns each row's bytes, read together off FX. */
    private record StashFileChanges(
            List<com.jfx.git.core.GitBackend.FileChange> changes,
            java.util.Map<String, String> revisionByPath) { }

    /**
     * A stash's first parent diff contains the tracked working copy. Its third parent is
     * the separate snapshot of untracked files, so include that root commit's additions
     * without changing the meaning of getChangesInCommit for ordinary commits.
     */
    private void loadStashChanges(com.jfx.git.model.StashEntry stash, FilesSection section,
                                  java.util.Map<String, String> fxRevisionByPath) {
        long token = section.loadToken;
        String untrackedRevision = stash.hash() + "^3";
        GitTaskRunner.queryLatest(gitService, section, () -> {
            List<com.jfx.git.core.GitBackend.FileChange> tracked =
                    gitService.getChangesInCommit(stash.hash());
            List<com.jfx.git.core.GitBackend.FileChange> untracked =
                    gitService.getChangesInCommit(untrackedRevision);
            List<com.jfx.git.core.GitBackend.FileChange> changes = new java.util.ArrayList<>(tracked);
            java.util.Map<String, String> revisions = new java.util.LinkedHashMap<>();
            tracked.forEach(change -> revisions.put(change.path(), stash.hash()));
            for (com.jfx.git.core.GitBackend.FileChange change : untracked) {
                // Only additions in U belong in the stash inspector. If a backend gives
                // U a base parent, its deletions describe the absent tracked tree, not
                // files the user stashed as untracked.
                if (change.status() != 'A' || revisions.containsKey(change.path())) continue;
                changes.add(change);
                revisions.put(change.path(), untrackedRevision);
            }
            return new StashFileChanges(List.copyOf(changes), java.util.Map.copyOf(revisions));
        }, result -> {
            if (!isCurrentFilesLoad(section, token)) return;
            fxRevisionByPath.clear();
            fxRevisionByPath.putAll(result.revisionByPath());
            section.loaded(result.changes());
            panel.setCount(result.changes().size()
                    + (result.changes().size() == 1 ? " file" : " files"));
            applyDiffstat(result.changes(), section);
        }, error -> {
            if (!isCurrentFilesLoad(section, token)) return;
            section.title.setText("Could not list files");
            statusBar.error(error);
        });
    }

    /** Fills a {@link FilesSection} on a worker thread and updates the header badge. */
    private void loadChanges(
            java.util.function.Supplier<List<com.jfx.git.core.GitBackend.FileChange>> query,
            FilesSection section) {
        loadChanges(query, section, () -> { });
    }

    /** @param onLoaded runs on the FX thread once the rows are in */
    private void loadChanges(
            java.util.function.Supplier<List<com.jfx.git.core.GitBackend.FileChange>> query,
            FilesSection section, Runnable onLoaded) {
        // The section outlives the selection now, so an answer that arrives after the
        // next commit was picked must not land in it: J/K outruns git on a big history.
        long token = section.loadToken;
        // Latest-wins per section: J/K past a run of commits drops the lists for the
        // ones skipped over before git is asked for them.
        GitTaskRunner.queryLatest(gitService, section,
                query::get,
                changes -> {
                    if (!isCurrentFilesLoad(section, token)) return;
                    section.loaded(changes);
                    panel.setCount(changes.size() + (changes.size() == 1 ? " file" : " files"));
                    applyDiffstat(changes, section);
                    onLoaded.run();
                },
                error -> {
                    if (!isCurrentFilesLoad(section, token)) return;
                    section.title.setText("Could not list files");
                    statusBar.error(error);
                });
    }

    /** A matching request may still finish after navigation has replaced its detail view. */
    private boolean isCurrentFilesLoad(FilesSection section, long token) {
        return token == section.loadToken && section.header.getParent() == panel.body();
    }

    /**
     * Totals the line counts and draws them as a number pair and a proportion bar.
     *
     * <p>Hidden outright when no file reported counts - a bar reading all-green because
     * every number was zero would say something untrue about a binary-only commit.
     */
    private void applyDiffstat(List<com.jfx.git.core.GitBackend.FileChange> changes,
                               FilesSection section) {
        int added = 0;
        int deleted = 0;
        boolean known = false;
        for (var change : changes) {
            if (change.hasCounts()) {
                added += change.added();
                deleted += change.deleted();
                known = true;
            }
        }

        section.bar.getChildren().clear();
        section.counts.getStyleClass().clear();
        if (!known) {
            show(section.counts, false);
            show(section.bar, false);
            return;
        }

        Label addedLabel = new Label("+" + added);
        addedLabel.getStyleClass().add("stat-add");
        Label deletedLabel = new Label("−" + deleted);
        deletedLabel.getStyleClass().add("stat-del");
        section.counts.setGraphic(new HBox(5, addedLabel, deletedLabel));
        show(section.counts, true);
        show(section.bar, true);

        int total = added + deleted;
        int greens = total == 0 ? 0 : (int) Math.round((double) added / total * STAT_SQUARES);
        // A commit that deleted anything keeps at least one red square, and one that added
        // anything keeps at least one green: rounding to zero would report "nothing was
        // removed here", which is a different claim from "not much was".
        if (added > 0 && greens == 0) greens = 1;
        if (deleted > 0 && greens == STAT_SQUARES) greens = STAT_SQUARES - 1;

        for (int i = 0; i < STAT_SQUARES; i++) {
            Region square = new Region();
            square.setMinSize(6, 6);
            square.setPrefSize(6, 6);
            square.getStyleClass().add(i < greens ? "stat-square-add" : "stat-square-del");
            section.bar.getChildren().add(square);
        }
    }

    /**
     * Fills {@code pane} with the refs sitting on this commit.
     *
     * <p>Built from {@link com.jfx.git.graph.RefRail}, the same source the graph rail
     * uses, so a branch looks identical whether you read it in the list or in the panel.
     * The chips were always styled; this view simply never used them. A commit carries a
     * few at most, so they are made afresh for each one.
     */
    private void fillRefChips(javafx.scene.layout.FlowPane pane, CommitInfo commit) {
        // The cached branch, not the backend: this runs on the FX thread on every commit
        // selection, and on the CLI engine getCurrentBranch() is a subprocess.
        // GitGraphCell solved the same problem the same way, through GraphMetrics.
        List<com.jfx.git.graph.RefRail.Chip> chips =
                com.jfx.git.graph.RefRail.chipsOf(commit, currentBranch.get());

        pane.getChildren().clear();
        for (var chip : chips) {
            Label label = new Label(chip.name());
            label.getStyleClass().add("ref-chip");
            switch (chip.kind()) {
                case LOCAL -> label.getStyleClass().add(
                        chip.head() ? "ref-chip-head" : "ref-chip-local");
                case REMOTE -> label.getStyleClass().add("ref-chip-remote");
                case TAG -> label.getStyleClass().add("ref-chip-tag");
            }
            label.setTooltip(new Tooltip(chip.tooltip()));
            pane.getChildren().add(label);
        }
        show(pane, !chips.isEmpty());
    }

    /** A small chip naming how far the signature has actually been checked - see {@link SignatureStatus}. */
    private javafx.scene.Node signatureBadge(SignatureStatus status) {
        Label badge = new Label(status.label());
        badge.setGraphic(status.isProblem() ? Icons.warning() : Icons.check());
        badge.getStyleClass().add(status.isProblem() ? "signature-badge-bad" : "signature-badge");
        return badge;
    }

    /** A hairline between two parts of a panel. */
    private Region divider() {
        Region rule = new Region();
        rule.getStyleClass().add("panel-divider");
        rule.setMinHeight(1);
        rule.setPrefHeight(1);
        rule.setMaxHeight(1);
        return rule;
    }


    private com.jfx.git.core.WorkspaceFiles workspaceFiles() {
        if (workspaceFiles == null) {
            workspaceFiles = new com.jfx.git.core.WorkspaceFiles(gitService.getWorkTree());
        }
        return workspaceFiles;
    }

    private void applyIgnore(String path, com.jfx.git.core.WorkspaceFiles.IgnoreScope scope) {
        var workspace = workspaceFiles();
        // Untracked files vanish from the unstaged list once the rule exists; a rule that was
        // never written changed nothing.
        operations.operation("Ignoring " + path, OperationRunner.Refresh.EVERYTHING)
                .refreshWhen(GitResult::isOk)
                .run(() -> workspace.ignore(path, scope));
    }

    private void copyToClipboard(String text, String confirmation) {
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        statusBar.info(confirmation);
    }

    /**
     * Keeps a badge off the screen while it has nothing to say. A badge is a rounded fill
     * with padding, so an empty one is still drawn - as a small grey dot beside the
     * filter box, which is what a match count of nothing looked like.
     */
    private static void showOnlyWithText(Label badge) {
        badge.visibleProperty().bind(badge.textProperty().isNotEmpty());
        badge.managedProperty().bind(badge.visibleProperty());
    }

    private static void show(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /**
     * A row of the file tree: a folder with its tallies, or a file drawn as it is in the
     * flat list.
     *
     * <p>A file row deliberately shows only its own name - the folders above it are
     * already on screen, and repeating the full path in a tree is the one thing the tree
     * exists to stop doing.
     *
     * <p>Both shapes are built once per cell and the row switches between them; see
     * {@link FileRowCell} for why nothing is built per row.
     */
    private final class FileNodeCell extends javafx.scene.control.TreeCell<FileNode> {
        private final FilesSection section;
        private final FolderRow folder = new FolderRow();
        private final Label status = new Label();
        private final Tooltip statusTip = new Tooltip();
        private final Label name = new Label();
        private final Label added = new Label();
        private final Label deleted = new Label();
        private final HBox counts = new HBox(5);
        private final HBox fileRow;
        private ContextMenu menu;

        FileNodeCell(FilesSection section) {
            this.section = section;
            status.getStyleClass().add("status-letter");
            statusTip.setOnShowing(e -> {
                FileNode node = getItem();
                statusTip.setText(node == null || node.change() == null ? "" : describeChange(node.change()));
            });
            status.setTooltip(statusTip);

            name.getStyleClass().add("file-normal");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            added.getStyleClass().add("stat-add");
            deleted.getStyleClass().add("stat-del");
            counts.setAlignment(Pos.CENTER_RIGHT);

            fileRow = new HBox(UiScale.STEP, status, name, spacer, counts);
            fileRow.setAlignment(Pos.CENTER_LEFT);
            // The full path on hover: the tree shows only the leaf name, and "which
            // Main.java is this" is the one question that costs it.
            Tooltip pathTip = new Tooltip();
            pathTip.setOnShowing(e -> {
                FileNode node = getItem();
                pathTip.setText(node == null ? "" : node.path());
            });
            Tooltip.install(fileRow, pathTip);
        }

        @Override
        protected void updateItem(FileNode node, boolean empty) {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            setText(null);
            if (node.isFolder()) {
                folder.show(node);
                setGraphic(folder.row);
                setContextMenu(null);
                return;
            }
            var change = node.change();
            setStatusLetter(status, change.status());
            name.setText(node.name());
            boolean counted = change.hasCounts();
            if (counted) {
                added.setText("+" + change.added());
                deleted.setText(MINUS + change.deleted());
            }
            setChildren(counts, counted ? added : null, counted ? deleted : null);
            setGraphic(fileRow);
            if (menu == null) {
                menu = fileMenu(section, () -> {
                    FileNode current = getItem();
                    return current == null || current.isFolder() ? null : current.path();
                });
            }
            setContextMenu(menu);
        }
    }

    /**
     * A folder's row in either file tree: its name, then how many files under it were
     * added, changed and removed. Built once per cell and refilled.
     */
    private static final class FolderRow {
        final Label name = new Label();
        final Label modified = new Label();
        final Label added = new Label();
        final Label deleted = new Label();
        final HBox tallies = new HBox(5);
        final HBox row;

        FolderRow() {
            name.getStyleClass().add("file-dir");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            modified.getStyleClass().add("tree-tally-mod");
            added.getStyleClass().add("stat-add");
            deleted.getStyleClass().add("stat-del");
            tallies.setAlignment(Pos.CENTER_RIGHT);
            row = new HBox(UiScale.STEP, name, spacer, tallies);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        void show(FileNode node) {
            name.setText(node.name());
            setChildren(tallies,
                    tally(modified, MODIFIED_MARK, node.modified()),
                    tally(added, "+", node.added()),
                    tally(deleted, MINUS, node.deleted()));
        }

        /** {@code label} showing the count, or null when there is none to show. */
        private static Label tally(Label label, String mark, int count) {
            if (count <= 0) return null;
            label.setText(mark + count);
            return label;
        }
    }

    /**
     * One changed file: how it changed, where it is, and by how much.
     *
     * <p>The directory is dimmed and the basename is not, because in a list of thirty
     * paths sharing a prefix the basename is the only part being read - and it is the part
     * furthest right, behind all the shared text. History and blame appear on hover rather
     * than on every row, for the same reason the staging list hides its buttons.
     *
     * <p>Built once per cell; see {@link FileRowCell} for why nothing is built per row.
     */
    private final class FileChangeCell extends ListCell<com.jfx.git.core.GitBackend.FileChange> {
        private final FilesSection section;
        private final Label status = new Label();
        private final Tooltip statusTip = new Tooltip();
        private final Label dir = new Label();
        private final Label name = new Label();
        private final Label added = new Label();
        private final Label deleted = new Label();
        private final HBox counts = new HBox(5);
        private final HBox row;
        private ContextMenu menu;

        FileChangeCell(FilesSection section) {
            this.section = section;
            status.getStyleClass().add("status-letter");
            statusTip.setOnShowing(e -> {
                var change = getItem();
                statusTip.setText(change == null ? "" : describeChange(change));
            });
            status.setTooltip(statusTip);

            dir.getStyleClass().add("file-dir");
            // The directory is what gives way when the row is narrow, and it gives way
            // from its own start: "…/ui/DiffViewer.java" keeps the one part being read.
            // Laid out left to right, the leading label takes the space it wants first,
            // so without this the filename is what gets cut - the row ends up showing a
            // shared prefix and eliding the only part that distinguishes it.
            dir.setMinWidth(0);
            dir.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
            name.getStyleClass().add("file-normal");
            // The filename holds its width against the directory beside it, but not
            // against the row itself; see FileRowCell.
            name.setMinWidth(0);
            name.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

            HBox pathBox = new HBox(0, dir, name);
            pathBox.setAlignment(Pos.CENTER_LEFT);
            pathBox.setMinWidth(0);
            HBox.setHgrow(pathBox, Priority.ALWAYS);

            added.getStyleClass().add("stat-add");
            deleted.getStyleClass().add("stat-del");
            counts.setAlignment(Pos.CENTER_RIGHT);

            Button history = new Button("History");
            history.getStyleClass().addAll("btn-ghost", "row-action");
            history.setMinWidth(Region.USE_PREF_SIZE);
            history.setOnAction(e -> {
                var change = getItem();
                if (change != null) showFileHistory(change.path());
            });
            Button blame = new Button("Blame");
            blame.getStyleClass().addAll("btn-ghost", "row-action");
            blame.setMinWidth(Region.USE_PREF_SIZE);
            blame.setOnAction(e -> {
                var change = getItem();
                if (change != null) showBlame(change.path());
            });
            HBox actions = new HBox(4, history, blame);
            actions.setAlignment(Pos.CENTER_RIGHT);

            // Counts and actions occupy the same place: hovering swaps one for the other,
            // so neither costs the path any width. Bound once, here, for the cell's life.
            actions.visibleProperty().bind(hoverProperty());
            actions.managedProperty().bind(actions.visibleProperty());
            counts.visibleProperty().bind(hoverProperty().not());
            counts.managedProperty().bind(counts.visibleProperty());

            row = new HBox(UiScale.STEP, status, pathBox, counts, actions);
            row.setAlignment(Pos.CENTER_LEFT);

            // Sized to the viewport, not to its content. A ListCell defaults to its
            // preferred width, so one long row made the whole list scroll sideways and
            // took the hover actions with it. At 0 the cell takes whatever width the list
            // has and the labels inside elide into it, which is what stops the scrollbar
            // existing rather than merely making it reachable.
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(com.jfx.git.core.GitBackend.FileChange change, boolean empty) {
            super.updateItem(change, empty);
            if (empty || change == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }

            String path = change.path();
            setStatusLetter(status, change.status());
            int slash = path.lastIndexOf('/');
            dir.setText(slash < 0 ? "" : path.substring(0, slash + 1));
            name.setText(slash < 0 ? path : path.substring(slash + 1));

            boolean counted = change.hasCounts();
            if (counted) {
                added.setText("+" + change.added());
                deleted.setText(MINUS + change.deleted());
            }
            setChildren(counts, counted ? added : null, counted ? deleted : null);

            if (menu == null) {
                menu = fileMenu(section, () -> {
                    var current = getItem();
                    return current == null ? null : current.path();
                });
            }
            setContextMenu(menu);

            setText(null);
            setGraphic(row);
        }
    }

    /**
     * The right-click menu a changed file gets, in whichever view it is being shown in.
     *
     * <p>Shared rather than duplicated: the list and the tree are two renderings of one
     * set of files, and a file that offers "restore this" in one of them and not the
     * other would make the choice of rendering mean something it does not.
     *
     * <p>One per cell, made the first time the cell shows a file. The file and the commit
     * to restore from are read when the menu opens - the cell has been many rows since the
     * menu was made, and the section many commits.
     *
     * @param currentPath the file the owning cell shows now, or null
     */
    private ContextMenu fileMenu(FilesSection section, java.util.function.Supplier<String> currentPath) {
        ContextMenu menu = new ContextMenu();
        String[] opened = new String[2];
        // Restore-from-here (§3.9): write this commit's blob over the working tree copy.
        // Recreates deleted files; asks first, because it discards whatever uncommitted
        // edits the file currently holds.
        MenuItem restore = new MenuItem("Restore this file");
        javafx.scene.control.SeparatorMenuItem afterRestore = new javafx.scene.control.SeparatorMenuItem();
        restore.setOnAction(e -> {
            String path = opened[0];
            String source = opened[1];
            if (path == null || source == null) return;
            // The row names the file as this commit had it. Whether HEAD still does decides
            // the question, so it is asked of git first; see RestoreDestination.
            GitTaskRunner.query(gitService, () -> RestoreDestination.renamedSince(gitService, source, path),
                    renamedTo -> confirmRestoreFromCommit(source, path, renamedTo),
                    statusBar::error);
        });
        MenuItem historyItem = new MenuItem("View file history");
        historyItem.setOnAction(e -> {
            if (opened[0] != null) showFileHistory(opened[0]);
        });
        MenuItem blameItem = new MenuItem("View blame");
        blameItem.setOnAction(e -> {
            if (opened[0] != null) showBlame(opened[0]);
        });
        MenuItem copyPath = new MenuItem("Copy path");
        copyPath.setOnAction(e -> {
            if (opened[0] != null) copyToClipboard(opened[0], "Path copied");
        });
        menu.getItems().addAll(restore, afterRestore, historyItem, blameItem, copyPath);
        menu.setOnShowing(e -> {
            opened[0] = currentPath.get();
            opened[1] = section.restoreSource;
            // A stash view is not a state you would copy over the work tree.
            boolean restorable = opened[1] != null;
            restore.setVisible(restorable);
            afterRestore.setVisible(restorable);
        });
        return menu;
    }

    /**
     * "Restore this file"'s confirmation, then the restore.
     *
     * @param renamedTo what {@code path} is called at HEAD, when it has been renamed since
     *                  {@code source}; {@code null} asks the plain question
     */
    private void confirmRestoreFromCommit(String source, String path, String renamedTo) {
        String shortHash = source.substring(0, Math.min(7, source.length()));
        String into;
        if (renamedTo == null) {
            // Cancel is the default: no snapshot is taken first, so uncommitted changes the
            // restore writes over are gone for good.
            if (!com.jfx.git.ui.dialogs.Dialogs.destructive("Restore file")
                    .icon(Icons.restore())
                    .title("Restore `" + nameOf(path) + "` from `" + shortHash + "`?")
                    .subtitle(folderOf(path))
                    .message("The file is overwritten with the version from " + shortHash
                            + ". Uncommitted changes to it are lost, and no snapshot is taken first.")
                    .action("Restore file")
                    .safeDefault()
                    .ask()) {
                return;
            }
            into = path;
        } else {
            // The cards say what each destination overwrites; the title says which version.
            var chosen = RestoreDestination.ask("Restore `" + path + "` from `" + shortHash + "`?", null,
                    path, renamedTo);
            if (chosen.isEmpty()) {
                return;
            }
            into = chosen.get();
        }
        runAndRefresh(() -> gitService.restoreFileFromCommit(source, path, into), "Restoring " + into);
    }

    /**
     * What is happening to a staging row: a move in flight, or one that failed.
     *
     * @param toStaged which way the move went
     * @param failed   whether git refused it
     * @param message  why, when it did
     */
    private record RowActivity(boolean toStaged, boolean failed, String message) {
        boolean pending() {
            return !failed;
        }
    }

    /**
     * Staging rows with something to show beside them, by path.
     *
     * <p>Kept here, not on the cells: cells are recycled and rebuild their graphic on
     * every update, so a spinner or an error set on one would land on whichever row it
     * was next reused for.
     */
    private final javafx.collections.ObservableMap<String, RowActivity> rowActivity =
            javafx.collections.FXCollections.observableHashMap();
    /** Repaints the current panel's rows when {@link #rowActivity} changes. */
    private Runnable repaintStagingRows = () -> { };

    {
        // One listener for the manager's lifetime, repainting whichever panel is current.
        rowActivity.addListener((javafx.collections.MapChangeListener<String, RowActivity>)
                change -> repaintStagingRows.run());
    }

    /** The latest move asked for each path; see {@link #moveFile}. */
    private final java.util.Map<String, Long> moveRequests = new java.util.HashMap<>();
    private long moveRequestCounter;

    /**
     * Moves one path between the staging lists, optimistically, and tells git.
     *
     * <p>Extracted so the flat list and the folder tree drive the same code. Two copies of
     * "remove here, add there, sort, and put it back if git disagrees" is how two views of
     * one thing come to disagree with each other.
     */
    private void moveFile(String path, boolean toStaged,
                          javafx.collections.ObservableList<String> unstagedAll,
                          javafx.collections.ObservableList<String> stagedAll,
                          Runnable updateCounts) {
        var from = toStaged ? unstagedAll : stagedAll;
        var to = toStaged ? stagedAll : unstagedAll;
        if (!from.remove(path)) {
            // Already moved - a second click on a row the first one is still moving.
            return;
        }
        to.add(path);
        java.util.Collections.sort(to);
        updateCounts.run();
        long request = ++moveRequestCounter;
        moveRequests.put(path, request);
        rowActivity.put(path, new RowActivity(toStaged, false, null));
        // The row moved already: this redraws nothing unless git disagrees.
        operations.operation((toStaged ? "Staging " : "Unstaging ") + path, OperationRunner.Refresh.NONE)
                .onResult(result -> {
                    Long latestForPath = moveRequests.get(path);
                    boolean latest = latestForPath != null && latestForPath == request;
                    if (latest) {
                        moveRequests.remove(path);
                    }
                    // Only the latest move of a path may put it back. Stage then Unstage in
                    // quick succession, with the stage failing, used to roll back a row the
                    // unstage had already moved - listing the file twice on one side.
                    if (latest) {
                        if (result.isOk()) {
                            rowActivity.remove(path);
                        } else {
                            // Said on the row as well as in the status bar: the row is
                            // where the user was looking, and it has just moved back.
                            rowActivity.put(path, new RowActivity(toStaged, true, result.getMessage()));
                        }
                    }
                    if (!result.isOk() && latest) {
                        to.remove(path);
                        from.add(path);
                        java.util.Collections.sort(from);
                        updateCounts.run();
                    } else if (unstagedAll.isEmpty() && stagedAll.isEmpty()) {
                        operations.refresh(OperationRunner.Refresh.EVERYTHING);
                    }
                })
                .run(() -> toStaged ? gitService.stageFile(path) : gitService.unstageFile(path));
    }

    /**
     * One staging list as a folder tree.
     *
     * <p>Built from the same {@link #buildTree} the commit view uses: each path is wrapped
     * as a {@code FileChange} carrying its status letter, so folders get their roll-up
     * tallies for free and the two trees cannot drift apart. Line counts are not known for
     * a working-tree path, which is what {@code UNKNOWN_COUNT} is for.
     */
    private javafx.scene.control.TreeView<FileNode> stagingTree(
            javafx.collections.ObservableList<String> paths,
            boolean unstagedSide,
            javafx.collections.ObservableList<String> unstagedAll,
            javafx.collections.ObservableList<String> stagedAll,
            Runnable updateCounts) {

        javafx.scene.control.TreeView<FileNode> tree = new javafx.scene.control.TreeView<>();
        tree.getStyleClass().addAll("sidebar-list",
                unstagedSide ? "staging-unstaged-tree" : "staging-staged-tree");
        tree.setShowRoot(false);
        tree.setFixedCellSize(UiScale.ROW_HEIGHT);

        // Only while the tree is the shape on show. Path is the default, and every stage
        // click, filter keystroke and refresh rebuilt a tree nobody could see - a TreeItem
        // per path and per folder, sorted, two thousand of them on a big working tree.
        boolean[] stale = {true};
        Runnable rebuild = () -> {
            if (!tree.isVisible()) {
                stale[0] = true;
                return;
            }
            stale[0] = false;
            StagingState state = stagingState;
            var changes = paths.stream()
                    .map(path -> new com.jfx.git.core.GitBackend.FileChange(
                            path, state == null ? 'M' : state.statusOf(path),
                            com.jfx.git.core.GitBackend.FileChange.UNKNOWN_COUNT,
                            com.jfx.git.core.GitBackend.FileChange.UNKNOWN_COUNT))
                    .toList();
            var root = buildTree(changes);
            expandAllFrom(root);
            tree.setRoot(root);
        };
        // The lists are mutated by staging, by the filter and by refreshes alike; the tree
        // is a projection of them and has no business tracking those separately.
        paths.addListener((javafx.collections.ListChangeListener<String>) change -> rebuild.run());
        tree.visibleProperty().addListener((obs, was, visible) -> {
            if (visible && stale[0]) rebuild.run();
        });
        // Hidden until the panel picks its shape, which builds the tree if it is the one.
        tree.setVisible(false);
        tree.setManaged(false);

        tree.setCellFactory(view -> new StagingNodeCell(
                unstagedSide, unstagedAll, stagedAll, updateCounts));
        return tree;
    }

    /** Opens every folder, so a tree of changed files reads without being unfolded first. */
    private static void expandAllFrom(javafx.scene.control.TreeItem<FileNode> item) {
        if (item.isLeaf()) {
            return;
        }
        item.setExpanded(true);
        for (var child : item.getChildren()) {
            expandAllFrom(child);
        }
    }

    /**
     * A row of the staging tree: a folder with its tallies, or a file with the action that
     * moves it to the other list. Both shapes built once per cell; see {@link FileRowCell}.
     */
    private final class StagingNodeCell extends javafx.scene.control.TreeCell<FileNode> implements StagingRow {
        private final boolean unstagedSide;
        private final javafx.collections.ObservableList<String> unstagedAll;
        private final javafx.collections.ObservableList<String> stagedAll;
        private final Runnable updateCounts;

        private final FolderRow folder = new FolderRow();
        private final Label status = new Label();
        private final Tooltip statusTip = new Tooltip();
        private final Label name = new Label();
        private final Button action;
        private final HBox fileRow;
        /** The in-flight or failed marker, when the row has one. */
        private javafx.scene.Node activity;

        StagingNodeCell(boolean unstagedSide,
                        javafx.collections.ObservableList<String> unstagedAll,
                        javafx.collections.ObservableList<String> stagedAll,
                        Runnable updateCounts) {
            this.unstagedSide = unstagedSide;
            this.unstagedAll = unstagedAll;
            this.stagedAll = stagedAll;
            this.updateCounts = updateCounts;

            status.getStyleClass().add("status-letter");
            statusTip.setOnShowing(e -> {
                FileNode node = getItem();
                statusTip.setText(node == null || node.change() == null
                        ? "" : describeStatus(node.change().status()));
            });
            status.setTooltip(statusTip);

            name.getStyleClass().add("file-normal");
            name.setMinWidth(0);
            name.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            Tooltip pathTip = new Tooltip();
            pathTip.setOnShowing(e -> {
                FileNode node = getItem();
                pathTip.setText(node == null ? "" : node.path());
            });
            Tooltip.install(name, pathTip);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            action = new Button(unstagedSide ? "Stage" : "Unstage");
            action.getStyleClass().addAll("btn-ghost", "row-action");
            action.setMinWidth(Region.USE_PREF_SIZE);
            action.setOnAction(e -> moveCurrent());
            action.visibleProperty().bind(hoverProperty().or(selectedProperty()));
            action.managedProperty().bind(action.visibleProperty());

            fileRow = new HBox(UiScale.STEP, status, name, spacer, action);
            fileRow.setAlignment(Pos.CENTER_LEFT);
            stagingRows.add(this);
        }

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

        private void moveCurrent() {
            FileNode node = getItem();
            if (node != null && !node.isFolder()) {
                moveFile(node.path(), unstagedSide, unstagedAll, stagedAll, updateCounts);
            }
        }

        @Override
        protected void updateItem(FileNode node, boolean empty) {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(null);
            setPrefWidth(0);
            if (node.isFolder()) {
                folder.show(node);
                setGraphic(folder.row);
                return;
            }
            setStatusLetter(status, node.change().status());
            name.setText(node.name());
            if (activity != null) {
                fileRow.getChildren().remove(activity);
            }
            activity = rowActivityNode(node.path(), unstagedSide, this::moveCurrent);
            if (activity != null) {
                fileRow.getChildren().add(3, activity);
            }
            setGraphic(fileRow);
        }
    }

    /**
     * What to show beside a row with a move in flight or failed, or null for nothing.
     *
     * <p>A move shows on the side it went to, where the row is while git works; a failure
     * on the side it came back to, with the reason and a way to try again. Before this a
     * refused stage put the row back without a word on it - the only trace was a line in
     * the status bar, at the other end of the window.
     */
    private javafx.scene.Node rowActivityNode(String path, boolean unstagedSide, Runnable retry) {
        RowActivity activity = rowActivity.get(path);
        if (activity == null) {
            return null;
        }
        // Which list the row is in now: the target while moving, the origin once refused.
        boolean onUnstagedSide = activity.failed() == activity.toStaged();
        if (onUnstagedSide != unstagedSide) {
            return null;
        }
        if (activity.pending()) {
            javafx.scene.control.ProgressIndicator spinner = new javafx.scene.control.ProgressIndicator();
            spinner.getStyleClass().add("row-progress");
            spinner.setPrefSize(14, 14);
            spinner.setMaxSize(14, 14);
            spinner.setAccessibleText((activity.toStaged() ? "Staging " : "Unstaging ") + path);
            return spinner;
        }
        Label failed = new Label();
        failed.setGraphic(Icons.warning());
        failed.getStyleClass().add("row-failed");
        String what = activity.toStaged() ? "Staging" : "Unstaging";
        String why = activity.message() == null || activity.message().isBlank()
                ? what + " failed." : what + " failed: " + activity.message();
        failed.setTooltip(new Tooltip(why));
        failed.setAccessibleText(why);
        Button retryButton = new Button("Retry");
        retryButton.getStyleClass().addAll("btn-ghost", "row-action", "row-retry");
        retryButton.setMinWidth(Region.USE_PREF_SIZE);
        retryButton.setTooltip(new Tooltip(why + " Try again."));
        retryButton.setOnAction(e -> retry.run());
        HBox box = new HBox(4, failed, retryButton);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    /** The tooltip on a file row's letter: what it means, and for a move, from where. */
    static String describeChange(com.jfx.git.core.GitBackend.FileChange change) {
        String meaning = describeStatus(change.status());
        return change.oldPath() == null ? meaning : meaning + " from " + change.oldPath();
    }

    /** What a status letter means, for the tooltip on it. */
    private static String describeStatus(char status) {
        return switch (status) {
            case 'A' -> "Added";
            case 'D' -> "Deleted";
            case 'R' -> "Renamed";
            case 'C' -> "Copied";
            case 'T' -> "Type changed";
            case 'U' -> "Conflicted";
            default -> "Modified";
        };
    }
}
