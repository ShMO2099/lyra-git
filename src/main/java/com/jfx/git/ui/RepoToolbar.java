package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitService;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.RepositoryCommands;
import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.ui.dialogs.Dialogs;
import com.jfx.git.ui.dialogs.JfxDialog;
import com.jfx.git.ui.dialogs.UndoPreviewDialog;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.io.File;
import java.util.List;
import java.util.function.Supplier;
import com.jfx.git.model.PullMode;

/**
 * Repository action bar: the branch switcher, fetch/pull/push, branch and stash actions,
 * undo and redo, and - when the repository has a host - its pull requests and issues.
 *
 * <h2>Two widths</h2>
 * Each button is an icon over a one-word caption while there is room for that, and the
 * icon alone when there is not. The bar used to have one layout and let the captions
 * truncate - "Pull" became "..." at the width of a laptop screen - before handing the
 * overflow to a chevron menu. A caption that cannot be read is worse than none, so the
 * bar now drops all of them together and keeps every action on screen.
 */
public class RepoToolbar extends ToolBar {

    /** Where a button's caption and its count are kept, apart from what it currently shows. */
    private static final String CAPTION = "tool-caption";
    private static final String BADGE = "tool-badge";
    /** Slack before captions come back, so a window dragged across the boundary does not flicker. */
    private static final double HYSTERESIS = 24;

    private final com.jfx.git.core.GitBackend gitService;
    private final StatusBar statusBar;
    /** Runs what the buttons start; see {@link OperationRunner}. */
    private final OperationRunner operations;

    private final ComboBox<String> branchSelector = new ComboBox<>();
    /** Guards the selector's listener while we repopulate it programmatically. */
    private boolean updatingSelector = false;

    /** Multi-step undo history, read from the reflog on every refresh. */
    private final com.jfx.git.core.ActionStack actionStack = new com.jfx.git.core.ActionStack();
    private final SplitMenuButton btnUndo;
    private final Button btnRedo;
    /** Whether the buttons are showing icons only; see the class comment. */
    private boolean compact;
    /** How wide the bar's contents were with captions on, the last time that was measured. */
    private double expandedWidth;
    /** How wide they are with the captions off, measured while they are; 0 before that. */
    private double compactWidth;
    private final Region spacer = new Region();
    /** Between the provider buttons and Terminal; hidden with them. */
    private final Region providerDivider = divider();
    /** Held so a refresh can turn it off while the stash is empty. */
    private final Button btnPop;
    /** Held so a refresh can name them after the provider, or turn them off. */
    private final Button btnPullRequests;
    private final Button btnIssues;

    public RepoToolbar(LyraApp app, OperationRunner operations) {
        this.operations = operations;
        this.gitService = operations.repository();
        this.statusBar = operations.statusBar();
        com.jfx.git.core.GitBackend gitService = this.gitService;

        getStyleClass().add("repo-toolbar");

        Button btnFetch = createButton("Fetch", Icons.fetch(), "Fetch all remotes" + keys(javafx.scene.input.KeyCode.L, false));
        btnFetch.setOnAction(e -> run(btnFetch, gitService::fetchAll, "Fetching"));
        this.btnFetch = btnFetch;

        MenuItem pullFfOnly = new MenuItem("Pull (fast-forward only)");
        pullFfOnly.setOnAction(e -> runPull(null, PullMode.FF_ONLY, "Pulling (ff-only)"));
        MenuItem pullRebase = new MenuItem("Pull (rebase)");
        pullRebase.setOnAction(e -> runPull(null, PullMode.REBASE, "Pulling (rebase)"));
        MenuItem fetchAll = new MenuItem("Fetch all");
        fetchAll.setOnAction(e -> run(gitService::fetchAll, "Fetching"));

        // Pressing the button pulls the ordinary way; the arrow holds the variants. The
        // plain pull is no longer also a menu item, because an action that is both the
        // default and an entry in its own menu reads as two different things.
        btnPull = createSplitButton("Pull", Icons.pull(),
                "Pull from the remote (fast-forward if possible)",
                // The primary click follows the repository's own preference, falling back
                // to the global one: teams differ on merge-versus-rebase, and most people
                // work on repositories from more than one team.
                () -> runPull(btnPull, null, "Pulling"),
                pullFfOnly, pullRebase, fetchAll);

        MenuItem pushTo = new MenuItem("Push to…");
        pushTo.setOnAction(e -> promptPushTo());
        MenuItem pushTags = new MenuItem("Push with tags");
        pushTags.setOnAction(e -> runPush(null, () -> commands().push(null, false, true, false), "Pushing with tags"));
        MenuItem pushForce = new MenuItem("Force push (with lease)");
        pushForce.setOnAction(e -> confirmForcePush());

        btnPush = createSplitButton("Push", Icons.push(),
                "Push the current branch",
                () -> runPush(btnPush, () -> commands().push(null, false, false, false), "Pushing"),
                pushTo, pushTags, pushForce);

        Button btnBranch = createButton("Branch", Icons.branch(), "Create a new branch" + keys(javafx.scene.input.KeyCode.B, false));
        btnBranch.setOnAction(e -> promptNewBranch());

        // A split button like Pull and Push: the click still stashes everything, as it
        // always has, and the options - a name, leaving untracked files, keeping the
        // index - are one step further for the times they are wanted.
        MenuItem stashWithOptions = new MenuItem("Stash with options…");
        stashWithOptions.setOnAction(e -> triggerStashWithOptions());
        SplitMenuButton btnStash = createSplitButton("Stash", Icons.stash(),
                "Stash working-tree changes", this::triggerStash, stashWithOptions);

        // Off until a refresh finds something to pop. Popping an empty stash is an error
        // the user can neither predict nor act on, and a button that only ever fails is
        // worse than one that is visibly unavailable.
        btnPop = createButton("Pop", Icons.pop(), "Apply and drop the latest stash");
        btnPop.setDisable(true);
        btnPop.setOnAction(e -> run(() -> gitService.stashPop(0), "Popping stash"));

        // Undo walks the reflog rather than undoing only the last commit, so it reaches
        // checkouts, resets, merges and rebases too. The label changes to name whatever
        // is next on the stack.
        // A split button, not a plain one: the main action stays a single click, because
        // the point of a 50-step undo is tapping it repeatedly to walk back, and a
        // confirmation on every step would make this application's best feature worse
        // than the single-level undo it beats. The preview lives in the dropdown for
        // when the user wants to know what a step will cost before paying it.
        MenuItem previewUndo = new MenuItem("Preview undo…");
        previewUndo.setOnAction(e -> previewUndo());
        // The reflog sits here because it is the same intent carried further: Undo steps
        // back one move, this reaches any earlier state, including ones a rebase rewrote.
        // It was a button of its own called History, beside a file's History and a
        // commit's history, and it is opened perhaps once a week.
        MenuItem reflog = new MenuItem("Branch history (reflog)…");
        reflog.setOnAction(e -> openReflog());
        btnUndo = createSplitButton("Undo", Icons.undo(), "Nothing to undo",
                this::undo, previewUndo, new SeparatorMenuItem(), reflog);
        // Off when there is nothing to undo, and the menu with it - which is why the
        // reflog is in the View menu and the command palette as well.
        btnUndo.setDisable(true);

        btnRedo = createButton("Redo", Icons.redo(), "Nothing to redo");
        btnRedo.setDisable(true);
        btnRedo.setOnAction(e -> redo());

        // Both start off and are named by the refresh, which is the first moment anything
        // knows which host - if any - this repository came from.
        btnPullRequests = createButton("Pull requests", Icons.pullRequest(),
                "Browse and check out this repository's open pull/merge requests");
        btnPullRequests.setDisable(true);
        btnPullRequests.setOnAction(e -> openPullOrMergeRequests());

        btnIssues = createButton("Issues", Icons.issue(),
                "Browse this repository's open issues");
        btnIssues.setDisable(true);
        btnIssues.setOnAction(e -> openIssues());

        Button btnTerminal = createButton("Terminal", Icons.terminal(),
                "Open a terminal in the repository");
        btnTerminal.setOnAction(e -> openTerminal());

        // At the leading edge: it says where you are, which is the first thing the bar
        // has to answer and the thing every action to its right acts on.
        branchSelector.getStyleClass().add("branch-switcher");
        branchSelector.setMinWidth(120);
        branchSelector.setMaxWidth(220);
        branchSelector.setPromptText("Branch");
        branchSelector.setAccessibleText("Current branch");
        branchSelector.setTooltip(new Tooltip("Switch to another branch"));
        javafx.scene.Node branchMark = Icons.localBranch();
        branchSelector.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setGraphic(empty ? null : branchMark);
            }
        });
        branchSelector.valueProperty().addListener((obs, old, selected) -> {
            if (!updatingSelector && selected != null && !selected.equals(old)) {
                checkoutBranch(selected);
            }
        });

        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Grouped by what the actions do: where you are, remote traffic, making and
        // setting aside work, stepping back. The provider and utility actions sit past
        // the spacer, at the trailing edge - they are the buttons that do not touch the
        // repository, so the window's empty space between them and the ones that do is
        // free separation: Push stops being adjacent to Terminal.
        // What cannot be done before the repository is open; see setOpening.
        repositoryActions.addAll(List.of(branchSelector, btnFetch, btnPull, btnPush,
                btnBranch, btnStash, btnTerminal));

        getItems().addAll(
                branchSelector, divider(),
                btnFetch, btnPull, btnPush, divider(),
                btnBranch, btnStash, btnPop, divider(),
                btnUndo, btnRedo,
                spacer,
                btnPullRequests, btnIssues, providerDivider,
                btnTerminal);

        widthProperty().addListener((obs, old, width) -> fit());
    }

    /** A hairline between two groups of buttons. */
    private static Region divider() {
        Region rule = new Region();
        rule.getStyleClass().add("toolbar-divider");
        return rule;
    }

    /** Where the reflog opens: this repository's tab; see {@link #setViewHost}. */
    private RepoViewHost viewHost = RepoViewHost.NONE;

    /** Set by the tab, so the reflog opens inside it rather than in a window of its own. */
    public void setViewHost(RepoViewHost host) {
        this.viewHost = host == null ? RepoViewHost.NONE : host;
    }

    /** Opens the reflog: every move this branch has made, and a way back to any of them. */
    public void openReflog() {
        viewHost.open(com.jfx.git.ui.views.ReflogView.class,
                () -> new com.jfx.git.ui.views.ReflogView(operations));
    }

    // ------------------------------------------------------------------
    // Captions, and dropping them when the bar is short of room
    // ------------------------------------------------------------------

    /**
     * The button captioned {@code caption}, whatever it is showing at the moment.
     *
     * <p>A button's text changes - with a count, or to nothing in the compact layout - so
     * its caption is kept apart from it. This is how the keyboard map and the tests name
     * a button.
     */
    ButtonBase toolButton(String caption) {
        for (javafx.scene.Node item : getItems()) {
            if (item instanceof ButtonBase button && caption.equals(button.getProperties().get(CAPTION))) {
                return button;
            }
        }
        throw new IllegalArgumentException("no toolbar button captioned " + caption);
    }

    /** The caption {@code button} was given, apart from any count it is showing. */
    static String captionOf(ButtonBase button) {
        return (String) button.getProperties().get(CAPTION);
    }

    boolean isCompact() {
        return compact;
    }

    /** Writes a button's text from its caption, its count and the layout in force. */
    private void relabel(ButtonBase button) {
        String caption = captionOf(button);
        String badge = (String) button.getProperties().get(BADGE);
        if (compact) {
            // The count outlives the caption: it is the reason to press the button.
            button.setText(badge == null ? "" : badge);
            button.setContentDisplay(badge == null ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT);
        } else {
            button.setText(badge == null ? caption : caption + "  " + badge);
            button.setContentDisplay(ContentDisplay.TOP);
        }
        button.setAccessibleText(badge == null ? caption : caption + " " + badge);
    }

    private void relabelAll() {
        for (javafx.scene.Node item : getItems()) {
            if (item instanceof ButtonBase button && button.getProperties().containsKey(CAPTION)) {
                relabel(button);
            }
        }
    }

    /**
     * Chooses between captions and icons for the width the bar has.
     *
     * <p>The captioned width can only be measured while the captions are showing, so it
     * is remembered from the last time they were, and the bar goes back to them once
     * there is that much room and a little over.
     */
    private void fit() {
        double available = getWidth();
        if (available <= 0) {
            return;
        }
        if (!compact) {
            expandedWidth = contentWidth();
            if (expandedWidth > available) {
                setCompact(true);
            }
        } else if (expandedWidth + HYSTERESIS <= available) {
            setCompact(false);
            // Counts and provider names may have changed while the captions were away.
            expandedWidth = contentWidth();
            if (expandedWidth > available) {
                setCompact(true);
            }
        }
        if (compact) {
            double needed = contentWidth();
            if (needed != compactWidth) {
                compactWidth = needed;
                requestLayout();
            }
        }
    }

    /**
     * No narrower than the buttons without their captions. Narrower than that, the bar
     * pushed buttons behind an overflow chevron - the very thing dropping the captions is
     * for - and nothing stopped the tab laying it out so, because its contents, not the
     * bar, decided how narrow the tab could go. The tab's contents now give way to the
     * window instead (see RepoTab), so the bar says how far it can go itself.
     */
    @Override
    protected double computeMinWidth(double height) {
        return Math.max(super.computeMinWidth(height), compactWidth);
    }

    private void setCompact(boolean on) {
        compact = on;
        pseudoClassStateChanged(COMPACT, on);
        relabelAll();
    }

    private static final javafx.css.PseudoClass COMPACT = javafx.css.PseudoClass.getPseudoClass("compact");

    /** What the bar's items need side by side: their preferred widths, the gaps and the padding. */
    private double contentWidth() {
        applyCss();
        double gap = lookup(".container") instanceof HBox box ? box.getSpacing() : 4;
        double width = snappedLeftInset() + snappedRightInset();
        int shown = 0;
        for (javafx.scene.Node item : getItems()) {
            if (!item.isManaged()) {
                continue;
            }
            shown++;
            if (item != spacer) {
                width += item.prefWidth(-1);
            }
        }
        return width + gap * Math.max(0, shown - 1);
    }

    // ------------------------------------------------------------------
    // Undo / redo
    // ------------------------------------------------------------------

    public com.jfx.git.core.ActionStack getActionStack() {
        return actionStack;
    }

    /** Reverses the most recent move, however that move has to be reversed. */
    public void undo() {
        applyMove(actionStack.undoMove(), "Nothing to undo", actionStack::undoApplied, false);
    }

    /**
     * Shows what the next undo would do, and offers to do it.
     *
     * <p>The same operation as {@link #undo()} with the preview in front of it - see
     * {@link UndoPreviewDialog} for what it shows and why a reflog undo can afford to be
     * explicit about it.
     */
    public void previewUndo() {
        applyMove(actionStack.undoMove(), "Nothing to undo", actionStack::undoApplied, true);
    }

    /** Re-applies the move the last undo reversed. */
    public void redo() {
        applyMove(actionStack.redoMove(), "Nothing to redo", actionStack::redoApplied, false);
    }

    /**
     * Performs one undo or redo step.
     *
     * <p>A checkout move is run straight through: it switches branch without moving any
     * pointer, so there is nothing to warn about and uncommitted work travels with it the
     * way it does for any other checkout - and, like any other checkout, one that local
     * changes are in the way of offers auto-stash. A reset move is guarded first, because
     * {@link RepositoryCommands#restoreTo} refuses outright while the tree is dirty — asking the
     * user to confirm a reset that the service will then decline would be a dialog whose
     * "OK" does nothing. See {@link WorkingTreeGuard}, which Branch history's Restore
     * shares.
     *
     * <p>Past the guard, a reset move optionally shows what it is about to discard.
     * {@code preview} is false for the button's own click - the undo has to stay one
     * gesture - and true for the "Preview undo…" menu item. See {@link UndoPreviewDialog}.
     */
    private void applyMove(com.jfx.git.core.ActionStack.Move move,
                           String nothingToDo,
                           Runnable onApplied,
                           boolean preview) {
        if (move == null) {
            statusBar.info(nothingToDo);
            return;
        }
        if (move.isCheckout()) {
            // A local branch is re-entered by name; anything else the reflog recorded as
            // typed (HEAD~1, origin/x, a tag) goes back to the commit it reached then -
            // see ActionStack.Move.checkoutTarget.
            operations.operation(move.label(), OperationRunner.Refresh.EVERYTHING)
                    // A conflict here is the re-apply's: the checkout itself was made.
                    .onResult(result -> {
                        if (result.isOk() || result.isConflict()) onApplied.run();
                    })
                    .withAutoStash(com.jfx.git.core.AutoStash.Operation.CHECKOUT,
                            () -> gitService.checkoutBranch(move.checkoutTarget(
                                    gitService.getLocalBranches().stream()
                                            .map(com.jfx.git.model.RefInfo::shortName)
                                            .toList())));
            return;
        }
        // The guard below refuses a dirty tree, so this snapshot is nearly always a no-op;
        // it is here for the tree that changed between the guard and the reset.
        Runnable reset = () -> operations.operation(move.label(), OperationRunner.Refresh.EVERYTHING)
                .onSuccess(result -> onApplied.run())
                .run(SnapshotGuard.guarded(gitService, "Before moving the branch back",
                        () -> commands().restoreTo(move.resetHash(), move.label())));
        WorkingTreeGuard.whenClean(operations, move.label(), preview
                ? () -> UndoPreviewDialog.confirm(gitService, UndoPreviewDialog.Origin.UNDO,
                        move.label(), move.resetHash(),
                        confirmed -> {
                            if (confirmed) reset.run();
                        })
                : reset);
    }

    /** Re-reads the reflog and relabels the undo and redo buttons. */
    private void refreshActionStack() {
        GitTaskRunner.query(gitService,
                () -> gitService.getReflog(com.jfx.git.core.ActionStack.DEPTH),
                reflog -> {
                    actionStack.refreshFrom(reflog);
                    btnUndo.setDisable(!actionStack.canUndo());
                    btnRedo.setDisable(!actionStack.canRedo());
                    btnUndo.setTooltip(new Tooltip(actionStack.undoDescription() + keys(javafx.scene.input.KeyCode.Z, false)));
                    btnRedo.setTooltip(new Tooltip(actionStack.redoDescription() + keys(javafx.scene.input.KeyCode.Z, true)));
                },
                message -> {
                    // A repository with no commits has no reflog; that is not an error
                    // worth putting in front of the user.
                    btnUndo.setDisable(true);
                    btnRedo.setDisable(true);
                });
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /**
     * Asks before forcing, and asks about something specific.
     *
     * <p>The lease is taken first, off the FX thread, and the same lease is handed to the
     * push. Recomputing it after the dialog would let a background auto-fetch move the
     * remote-tracking ref in between, so the user would be agreeing to overwrite one
     * commit while the push leased another - the caveat git documents for
     * {@code --force-with-lease}. Taking it first also means the dialog can name the
     * commit, and that a branch with no lease available is refused before the user is
     * asked to confirm something that could not be done safely anyway.
     */
    private void confirmForcePush() {
        operations.read("Checking what the remote has",
                () -> gitService.pushLease(null),
                lease -> lease == null ? com.jfx.git.core.RemoteOps.noLeaseExplanation(null) : null,
                lease -> {
                    if (lease.expectsNoRemoteBranch()) {
                        // Nothing on the remote to overwrite, so there is nothing to warn
                        // about: this is publishing a branch, not forcing over one.
                        run(() -> gitService.push(lease.remote(), lease, false, false),
                                "Publishing " + lease.branch());
                        return;
                    }
                    confirmForcePush(lease);
                });
    }

    /**
     * Names what a force push would remove, and asks.
     *
     * <p>The dialog used to show only the leased hash, and the lease alone cannot tell a
     * commit the user replaced on purpose (an amend, a rebase) from one auto-fetch brought
     * in that they never saw. Both are listed now. The second kind - the lease is not
     * reachable from this branch or its reflog - gets a warning and a button that says
     * what it does; only that button, never Enter on a generic OK, lets the push discard
     * work this clone never had.
     */
    private void confirmForcePush(com.jfx.git.core.PushLease lease) {
        String target = lease.remote() + "/" + lease.remoteBranch();
        String seen = shortId(lease.expectedObjectId());
        String refused = "The push is refused if " + target + " has moved since " + seen + ".";
        int lost = lease.wouldDiscard().size();
        String commits = lost == 1 ? "1 commit" : lost + " commits";

        ButtonType go;
        Dialogs.Message confirm = Dialogs.warn("Force push").icon(Icons.push());
        if (lease.integrated()) {
            // Replacing your own rewritten commits: Enter confirms, as it always has.
            go = ButtonType.OK;
            confirm.title("Force push to " + target + "?")
                    .subtitle("It overwrites what this clone last saw there, `" + seen + "`")
                    .message(refused)
                    .action("Force push")
                    .danger(ButtonType.OK);
            if (!lease.wouldDiscard().isEmpty()) {
                confirm.affected("Replaced by your rewritten branch", lease.wouldDiscard());
            }
        } else {
            // Enter must not be how someone else's unseen work is discarded: Cancel is
            // the default here.
            go = new ButtonType("Discard " + commits + " and force push",
                    javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
            confirm.title("Force push over " + commits + " on " + target + "?")
                    .subtitle("Most likely fetched in the background since you last pulled")
                    .message("Forcing discards them from the remote. " + refused)
                    .affected("Removed from " + target, lease.wouldDiscard())
                    .buttons(go, ButtonType.CANCEL)
                    .danger(go)
                    .safeDefault()
                    .size(JfxDialog.Size.MD);
        }
        if (confirm.answer() == go) {
            com.jfx.git.core.PushLease agreed = lease.integrated() ? lease : lease.confirmingDiscard();
            run(() -> gitService.push(agreed.remote(), agreed, false, false), "Force pushing");
        }
    }

    /** Commit ids are shown the way git shows them in this situation: abbreviated. */
    private static String shortId(String objectId) {
        return objectId == null || objectId.length() < 7 ? String.valueOf(objectId)
                : objectId.substring(0, 7);
    }

    /** Fetches every remote; also reachable from the keyboard map. */
    public void fetchAll() {
        run(btnFetch, gitService::fetchAll, "Fetching");
    }

    /** The Fetch button; kept so a shortcut's fetch spins the same button a click does. */
    private Button btnFetch;

    /**
     * Prompts for a branch name and creates it at HEAD, checked out; also reachable from
     * the keyboard map.
     *
     * <p>Where HEAD is and which names are taken are read on the repository lane first,
     * so the prompt can name the starting point and refuse a taken name as it is typed.
     */
    public void promptNewBranch() {
        GitTaskRunner.query(gitService, () -> Head.read(gitService), head -> {
            String at = head.hash() == null ? null : "`" + shortId(head.hash()) + "`";
            String from = !head.detached() && !head.branch().isEmpty()
                    ? "From `" + head.branch() + "`" + (at == null ? "" : " at " + at)
                    : at == null ? null : "From " + at + ", the commit checked out now";
            branchPrompt("New branch", head.branches())
                    .title("Create a branch")
                    .subtitle(from)
                    .hint("It is checked out straight away. Uncommitted changes stay as they are.")
                    .action("Create and check out")
                    .ask()
                    .map(String::trim)
                    .ifPresent(name -> run(() -> gitService.createBranch(name, null, true),
                            "Creating branch " + name));
        }, statusBar::error);
    }

    /** Where HEAD is, and the local branch names already taken; read on the lane. */
    record Head(String branch, boolean detached, String hash, List<String> branches) {
        static Head read(com.jfx.git.core.GitBackend git) {
            boolean detached = git.isHeadDetached();
            String branch = git.getCurrentBranch();
            return new Head(branch == null ? "" : branch, detached, git.getHeadHash(),
                    git.getLocalBranches().stream().map(ref -> ref.shortName()).toList());
        }
    }

    /**
     * A branch-name prompt: the label, an example and the validation every branch prompt
     * shares. The caller adds what it is for - the title, where the branch starts, the verb.
     *
     * @param taken the local branch names already in use
     */
    static Dialogs.Prompt branchPrompt(String windowTitle, java.util.Collection<String> taken) {
        return Dialogs.prompt(windowTitle)
                .icon(Icons.branch())
                .label("Branch name")
                .placeholder("feature/search")
                .validator(text -> branchNameProblem(text, taken));
    }

    /**
     * What is wrong with {@code typed} as a new branch's name - git's own ref-name rules,
     * then a name already in use - or null when nothing is. Trimmed first, as the prompts
     * trim what they create.
     */
    static String branchNameProblem(String typed, java.util.Collection<String> taken) {
        String name = typed == null ? "" : typed.trim();
        if (name.isEmpty()) {
            return null;
        }
        return com.jfx.git.model.RefNames.newBranchNameProblem(name, taken);
    }

    /**
     * What to say when {@code origin} is on no configured provider's server, for the
     * button that was clicked ({@code what} is "pull requests" or "issues").
     *
     * <p>The remote is resolved from its URL, not from which account happens to be
     * connected - see {@link HostingProvider#forRemote}. A host nobody is configured for
     * used to open the GitHub dialog, which could only say "not a GitHub repository". The
     * likely reason is a self-hosted server the user has not pointed an integration at
     * yet, so the message names the host and where to do that. A remote with no host at
     * all - a path on disk - has nothing to configure, and says so instead.
     *
     * <p>Package-visible and free of JavaFX state so a test can pin the wording.
     */
    static String unknownHostMessage(String originUrl, String what) {
        String host = hostOf(originUrl);
        if (host == null) {
            return "origin is not on a hosting server, so there are no " + what + " to browse.";
        }
        return "origin is on " + host + ". Point GitHub Enterprise, GitLab or Gitea at this server"
                + " in Integrations to browse its " + what + ".";
    }

    /** The remote's host, with a web remote's explicit port; null for a path on disk. */
    private static String hostOf(String originUrl) {
        return com.jfx.git.core.RemoteUrl.parse(originUrl)
                .map(r -> r.host() + (r.isWeb() && r.port() > 0 ? ":" + r.port() : ""))
                .orElse(null);
    }

    /**
     * Explains an origin on an unknown host and, when it has one, offers to open
     * Integrations to configure it. The Integrations page in Settings is about accounts and
     * servers, not about this particular repository, so the line about it comes first, here.
     */
    private void explainUnknownHost(String originUrl, String what) {
        String host = hostOf(originUrl);
        boolean issues = "issues".equals(what);
        Dialogs.Message notice = Dialogs.info(issues ? "Issues" : "Pull requests")
                .icon(issues ? Icons.issue() : Icons.pullRequest())
                .message(unknownHostMessage(originUrl, what));
        if (host == null) {
            // A folder on disk: nothing to configure, so nothing to offer but Close. (The
            // URL is shown only here: a web remote's can carry a token.)
            notice.title("No " + what + " to browse")
                    .subtitle(originUrl == null || originUrl.isBlank() ? null : "origin is `" + originUrl + "`")
                    .show();
            return;
        }
        // OK relabelled rather than a button of its own: info() would otherwise call the
        // first default button "Close".
        if (notice.title("No integration is set up for `" + host + "`")
                .buttons(ButtonType.OK, new ButtonType("Not now", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE))
                .action("Open integrations")
                .ask()) {
            AppNavigator.openSettings(SettingsSection.INTEGRATIONS);
        }
    }

    /**
     * The origin remote's URL as the last refresh read it, on the repository lane;
     * {@code null} with {@link #originKnown} false until the first refresh lands.
     */
    private String originUrl;
    private boolean originKnown;

    /**
     * Runs {@code then} with the origin URL - the one the last refresh read, or, before
     * any refresh has landed, one read now on the repository lane.
     *
     * <h2>Why not just ask</h2>
     * Both buttons used to call {@code getRemoteUrl} on the FX thread, which on the CLI
     * engine is a {@code git remote} subprocess: the window froze for as long as it took,
     * and ThreadGuard said so on every click. The refresh already reads the URL off the
     * FX thread to label these buttons, so the click uses that.
     */
    private void withOrigin(java.util.function.Consumer<String> then) {
        if (originKnown) {
            then.accept(originUrl);
            return;
        }
        GitTaskRunner.query(gitService, () -> gitService.getRemoteUrl("origin"), then, statusBar::error);
    }

    /**
     * Where the pull request and issue views open: the repository tab's
     * {@link RepoTab#showView}, set by the tab that owns this toolbar. A hook rather than
     * the tab itself, so the toolbar can show a view and nothing more.
     */
    private java.util.function.Consumer<RepoView> providerViewHost = view -> { };

    /** The views the two buttons last opened, brought back rather than stacked again. */
    private com.jfx.git.ui.views.ProviderView pullRequestsView;
    private com.jfx.git.ui.views.ProviderView issuesView;
    /** The origin each of those was opened for: a changed origin needs a new list. */
    private String pullRequestsOrigin;
    private String issuesOrigin;

    /** Sets where the provider views open; see {@link #providerViewHost}. */
    public void setProviderViewHost(java.util.function.Consumer<RepoView> host) {
        this.providerViewHost = host == null ? view -> { } : host;
    }

    /**
     * Opens whichever provider's pull/merge-request view matches {@code origin} - see
     * {@link HostingProvider#forRemote} - in the repository's tab, or explains an unknown
     * host.
     *
     * <p>Pressed again while that view is still open, it brings the same one back - its
     * list, filter and selection as they were - rather than stacking a second copy that
     * asks the server for everything again.
     */
    private void openPullOrMergeRequests() {
        openPullOrMergeRequests(view -> { });
    }

    /**
     * {@link #openPullOrMergeRequests()}, then {@code then} with the view now showing -
     * which "Create pull request" after a first push uses to open its form filled in.
     */
    private void openPullOrMergeRequests(java.util.function.Consumer<com.jfx.git.ui.views.ProviderView> then) {
        withOrigin(url -> {
            if (pullRequestsView != null && pullRequestsView.isOpen()
                    && java.util.Objects.equals(url, pullRequestsOrigin)) {
                providerViewHost.accept(pullRequestsView);
                then.accept(pullRequestsView);
                return;
            }
            HostingProvider provider = HostingProvider.forRemote(url).orElse(null);
            if (provider == null) {
                explainUnknownHost(url, "pull requests");
                return;
            }
            com.jfx.git.ui.views.ProviderView view = switch (provider) {
                case GITLAB -> new com.jfx.git.ui.views.GitLabMergeRequestsView(
                        operations, url, providerViewHost);
                case BITBUCKET -> new com.jfx.git.ui.views.BitbucketPullRequestsView(
                        operations, url, providerViewHost);
                case GITHUB -> new com.jfx.git.ui.views.GitHubPullRequestsView(
                        operations, url, providerViewHost);
                case GITEA -> new com.jfx.git.ui.views.GiteaPullRequestsView(
                        operations, url, providerViewHost);
            };
            pullRequestsView = view;
            pullRequestsOrigin = url;
            providerViewHost.accept(view);
            then.accept(view);
        });
    }

    /**
     * After a push that published {@code branch} - it had no upstream before - offers to
     * open a pull request for it, filled in ({@link PullRequestPrefill}). Only where there
     * is somewhere to open one: origin on a provider the user is signed in to, and a
     * default branch to propose into that is not this branch.
     */
    private void offerPullRequest(String branch) {
        withOrigin(url -> {
            HostingProvider provider = HostingProvider.forRemote(url).orElse(null);
            if (provider == null || !com.jfx.git.core.hosting.ProviderSessions.isConnected(provider)) {
                return;
            }
            GitTaskRunner.query(gitService, () -> PullRequestPrefill.read(gitService, branch),
                    draft -> {
                        if (draft.targetBranch() == null || draft.targetBranch().equals(branch)) return;
                        boolean gitlab = provider == HostingProvider.GITLAB;
                        statusBar.offer(ToastHost.Kind.SUCCESS, "Published " + branch,
                                List.of(new ToastHost.Action(gitlab ? "Create merge request" : "Create pull request",
                                        () -> openPullOrMergeRequests(view -> view.prefill(draft)))));
                    },
                    error -> { });
        });
    }

    /**
     * Opens whichever provider's issue view matches {@code origin} - see {@link
     * HostingProvider#forRemote} - in the repository's tab, or explains an unknown host.
     * Bitbucket has none to open - its Issue Tracker API was fully removed in August
     * 2026 - so that case reports why instead of opening a view with nothing to show.
     * Pressed again while open, it brings the same view back, as Pull requests does.
     */
    private void openIssues() {
        withOrigin(url -> {
            if (issuesView != null && issuesView.isOpen() && java.util.Objects.equals(url, issuesOrigin)) {
                providerViewHost.accept(issuesView);
                return;
            }
            HostingProvider provider = HostingProvider.forRemote(url).orElse(null);
            if (provider == null) {
                explainUnknownHost(url, "issues");
                return;
            }
            com.jfx.git.ui.views.ProviderView view = switch (provider) {
                case GITLAB -> new com.jfx.git.ui.views.GitLabIssuesView(gitService, url, statusBar);
                case BITBUCKET -> {
                    // Said in the status bar: there is nothing to decide, so nothing to dismiss.
                    statusBar.info("No issues to browse on Bitbucket: it removed its issue tracker API"
                            + " in August 2026");
                    yield null;
                }
                case GITHUB -> new com.jfx.git.ui.views.GitHubIssuesView(gitService, url, statusBar);
                case GITEA -> new com.jfx.git.ui.views.GiteaIssuesView(gitService, url, statusBar);
            };
            if (view == null) return;
            issuesView = view;
            issuesOrigin = url;
            providerViewHost.accept(view);
        });
    }

    /** Package-visible: the origin URL the toolbar would open the provider views with, or null if not yet read. */
    String cachedOriginUrlForTest() {
        return originKnown ? originUrl : null;
    }

    /**
     * Opens a terminal in the working tree: the one named in Preferences, or the best
     * one installed. See {@link com.jfx.git.core.TerminalLauncher}.
     *
     * <p>Public for the command palette.
     */
    public void openTerminal() {
        File dir = gitService.getWorkTree();
        if (dir == null) {
            statusBar.error("No repository open");
            return;
        }
        statusBar.report(com.jfx.git.core.TerminalLauncher.open(dir));
    }

    // ------------------------------------------------------------------
    // Refresh
    // ------------------------------------------------------------------

    /** Repopulates the branch selector and the undo history. */
    public void refresh() {
        refreshActionStack();
        GitTaskRunner.query(gitService,
                // Tracking rides along with the branch list rather than being asked for
                // separately: this already runs on every refresh, and on the CLI engine a
                // second query would be a second subprocess for a two-number answer.
                () -> new BranchState(
                        gitService.getLocalBranches().stream()
                                .map(ref -> ref.shortName()).toArray(String[]::new),
                        gitService.getCurrentBranch(),
                        gitService.getTrackingStatus(),
                        gitService.getStashEntries().size(),
                        gitService.getRemoteUrl("origin")),
                data -> applyBranchState(List.of(data.branches()), data.current(), data.tracking(),
                        data.stashes(), data.originUrl()),
                statusBar::error);
    }

    /** Re-reads only the undo history; the branch state arrives from the sidebar's load. */
    public void refreshUndo() {
        refreshActionStack();
    }

    /**
     * Shows branch state somebody else already read. The sidebar loads the same branches,
     * current branch, tracking and stashes on every refresh; reading them twice was five
     * more git processes per refresh on the CLI engine, all queued ahead of the graph.
     */
    public void applyBranchState(List<String> branches, String current, int[] tracking,
                                 int stashes, String originUrl) {
        updatingSelector = true;
        branchSelector.getItems().setAll(branches);
        if (current != null && !current.isEmpty()) {
            branchSelector.setValue(current);
        } else {
            // A detached HEAD has no current branch: left showing, the branch it was on
            // last would read as the one checked out.
            branchSelector.setValue(null);
        }
        updatingSelector = false;
        showPendingWork(tracking);
        showStashState(stashes);
        showProviderState(originUrl);
    }

    /** Controls that act on the repository, held off while it is still opening. */
    private final List<javafx.scene.Node> repositoryActions = new java.util.ArrayList<>();

    /**
     * Holds the repository's actions off while it opens. Fetch, Push and the rest used to
     * be live before there was a repository behind them, and the switcher said "Branch"
     * as if there were none; now it says what is happening.
     */
    public void setOpening(boolean opening) {
        repositoryActions.forEach(node -> node.setDisable(opening));
        branchSelector.setPromptText(opening ? "Opening…" : "Branch");
    }

    /** What one refresh needs to know about branches, gathered in a single query. */
    private record BranchState(String[] branches, String current, int[] tracking,
                               int stashes, String originUrl) {
    }

    /**
     * Names the provider buttons after the host, and turns them off when there is none.
     *
     * <h2>Two dead ends this closes</h2>
     * A repository with no {@code origin} was opening the GitHub pull-request dialog to
     * fail inside it, back when an unrecognised remote counted as GitHub. And a
     * GitLab repository had a button reading "Pull requests" for a thing GitLab calls a
     * merge request - the label was naming the tool that read the remote rather than the
     * remote it read.
     *
     * <p>Bitbucket's Issues button is off for a different reason: its issue-tracker API
     * was removed, so there is nothing behind the button on that host. The explanation
     * moves from an alert after the click to the tooltip before it.
     */
    private void showProviderState(String originUrl) {
        this.originUrl = originUrl;
        this.originKnown = true;
        boolean hasRemote = originUrl != null && !originUrl.isBlank();
        // Not there at all without a host. They used to stay on the bar, greyed out, in
        // every repository that had never had a remote - two slots of permanent "no".
        for (javafx.scene.Node node : List.of(btnPullRequests, btnIssues, providerDivider)) {
            node.setVisible(hasRemote);
            node.setManaged(hasRemote);
        }
        fit();
        if (!hasRemote) {
            setCaption(btnPullRequests, "Pull requests");
            btnPullRequests.setDisable(true);
            btnPullRequests.setTooltip(new Tooltip(
                    "This repository has no origin remote, so there is nothing to browse"));
            btnIssues.setDisable(true);
            btnIssues.setTooltip(new Tooltip(
                    "This repository has no origin remote, so there is nothing to browse"));
            return;
        }

        HostingProvider provider = HostingProvider.forRemote(originUrl).orElse(null);
        boolean gitlab = provider == HostingProvider.GITLAB;
        setCaption(btnPullRequests, gitlab ? "Merge requests" : "Pull requests");
        btnPullRequests.setDisable(false);
        // Both buttons stay live on an unknown host: the click says which host it is and
        // opens Integrations, which is the way out for a self-hosted server.
        String unknown = hostOf(originUrl) == null
                ? "origin is not on a hosting server"
                : "origin is on a server no integration is set up for";
        btnPullRequests.setTooltip(new Tooltip(provider == null
                ? unknown
                : gitlab
                        ? "Browse and check out this project's open merge requests"
                        : "Browse and check out this repository's open pull requests"));

        boolean issuesAvailable = provider != HostingProvider.BITBUCKET;
        btnIssues.setDisable(!issuesAvailable);
        btnIssues.setTooltip(new Tooltip(provider == null
                ? unknown
                : issuesAvailable
                        ? "Browse this repository's open issues"
                        : "Bitbucket removed its issue tracker API, so there is nothing to read"));
    }

    /** Renames a button: "Pull requests" becomes "Merge requests" on a GitLab remote. */
    private void setCaption(ButtonBase button, String caption) {
        button.getProperties().put(CAPTION, caption);
        relabel(button);
        fit();
    }

    /**
     * Turns Pop on only when there is a stash, and says how many there are.
     *
     * <p>The count goes in the tooltip rather than on the face. Pull and Push carry
     * theirs because the number is the reason to press them - three commits behind is why
     * you pull. Pop always does exactly one thing to exactly one entry, so a number
     * beside it would read as how many it was about to apply.
     */
    private void showStashState(int stashes) {
        btnPop.setDisable(stashes == 0);
        btnPop.setTooltip(new Tooltip(stashes == 0
                ? "Nothing stashed"
                : stashes == 1
                        ? "Apply and drop the stash"
                        : "Apply and drop the latest of " + stashes + " stashes"));
    }

    // Kept for the command palette.
    public void triggerFetchAll() {
        run(gitService::fetchAll, "Fetching");
    }

    public void triggerPull(PullMode mode) {
        runPull(null, mode, "Pulling");
    }

    /** What "Push to…" needs to know before it can ask, read on the git lane. */
    private record PushChoices(String branch, java.util.List<String> remotes, String upstream) {
    }

    /**
     * Pushes the current branch to a remote chosen in a {@link
     * com.jfx.git.ui.dialogs.PushToDialog}, rather than to the one it is configured for.
     *
     * <p>Public for the command palette.
     */
    public void promptPushTo() {
        GitTaskRunner.query(gitService, () -> {
            String branch = gitService.isHeadDetached() ? "" : gitService.getCurrentBranch();
            boolean none = branch == null || branch.isBlank();
            return new PushChoices(none ? "" : branch, gitService.getRemoteNames(),
                    none ? null : gitService.getUpstream(branch));
        }, choices -> {
            if (choices.branch().isEmpty()) {
                statusBar.error("No branch is checked out, so there is nothing to push");
                return;
            }
            if (choices.remotes().isEmpty()) {
                statusBar.error("This repository has no remotes. Add one from the sidebar's remote section.");
                return;
            }
            new com.jfx.git.ui.dialogs.PushToDialog(choices.branch(), choices.remotes(), choices.upstream())
                    .showAndWait()
                    .ifPresent(target -> runPush(null,
                            () -> commands().push(target.remote(), false, false,
                                    target.setUpstream()),
                            "Pushing " + choices.branch() + " to " + target.remote()));
        }, statusBar::error);
    }

    /** The caller-side commands - merge, stash by commit, push - over this toolbar's backend. */
    private RepositoryCommands commands() {
        return new RepositoryCommands(gitService);
    }

    public void triggerPush() {
        runPush(btnPush, () -> commands().push(null, false, false, false), "Pushing");
    }

    public void triggerStash() {
        run(() -> commands().stash(), "Stashing");
    }

    /** Asks for a name and the stash options, then stashes the whole working tree. */
    public void triggerStashWithOptions() {
        new com.jfx.git.ui.dialogs.StashOptionsDialog().showAndWait()
                .ifPresent(options -> run(() -> gitService.stash(options), "Stashing"));
    }

    /** Checks out {@code name}, offering stash and retry when local changes are in the way. */
    public void checkoutBranch(String name) {
        start(null, "Checking out " + name, operation -> operation.withAutoStash(
                com.jfx.git.core.AutoStash.Operation.CHECKOUT, () -> gitService.checkoutBranch(name)), null);
    }

    /**
     * Pulls - in {@code mode}, or this repository's own pull mode when null - offering
     * stash and retry when local changes are in the way.
     */
    private void runPull(ButtonBase owner, PullMode mode, String description) {
        start(owner, description, operation -> operation.withAutoStash(com.jfx.git.core.AutoStash.Operation.PULL,
                () -> gitService.pull(mode == null ? defaultPullMode() : mode)), null);
    }

    /**
     * Pushes, and when this push is the one that published the branch - it had no
     * upstream before and has one now - offers to open a pull request for it.
     */
    private void runPush(ButtonBase owner, Supplier<GitResult> push, String description) {
        java.util.concurrent.atomic.AtomicReference<String> published = new java.util.concurrent.atomic.AtomicReference<>();
        start(owner, description, operation -> operation.run(() -> {
            String branch = gitService.isHeadDetached() ? null : gitService.getCurrentBranch();
            boolean unpublished = branch != null && !branch.isBlank() && gitService.getUpstream(branch) == null;
            GitResult result = push.get();
            if (unpublished && result.isOk() && gitService.getUpstream(branch) != null) {
                published.set(branch);
            }
            return result;
        }), () -> {
            if (published.get() != null) offerPullRequest(published.get());
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void run(Supplier<GitResult> work, String description) {
        run(null, work, description);
    }

    /** What the toolbar is doing now; null when idle. A second request is refused until it is done. */
    private String running;

    /**
     * Runs a repository operation from the toolbar.
     *
     * <p>The whole bar used to grey out while anything ran, so the one button that had
     * been pressed looked exactly like the eight that had not. Now that button spins, the
     * other repository actions wait, and pressing anything again - here, by shortcut or
     * from the palette - says what is already running instead of queueing a second fetch
     * behind the first.
     *
     * @param owner the button the operation belongs to, which shows a spinner; may be null
     */
    private void run(ButtonBase owner, Supplier<GitResult> work, String description) {
        start(owner, description, operation -> operation.run(work), null);
    }

    /**
     * What {@link #run(ButtonBase, Supplier, String)} does, with how the operation is started
     * left to {@code launch} - plainly, or with auto-stash offered - and {@code afterwards}
     * run once it has been reported and the tab refreshed.
     */
    private void start(ButtonBase owner, String description,
                       java.util.function.Consumer<OperationRunner.Operation> launch,
                       Runnable afterwards) {
        if (running != null) {
            statusBar.info(running + " is still running");
            return;
        }
        running = description;
        javafx.scene.Node icon = owner == null ? null : owner.getGraphic();
        if (owner != null) {
            javafx.scene.control.ProgressIndicator spinner = new javafx.scene.control.ProgressIndicator();
            spinner.getStyleClass().add("tool-spinner");
            spinner.setPrefSize(18, 18);
            spinner.setMaxSize(18, 18);
            owner.setGraphic(spinner);
        }
        repositoryActions.forEach(node -> {
            if (node != owner) node.setDisable(true);
        });
        OperationRunner.Operation operation = operations.operation(description, OperationRunner.Refresh.EVERYTHING)
                .onResult(result -> {
                    running = null;
                    if (owner != null) owner.setGraphic(icon);
                    repositoryActions.forEach(node -> node.setDisable(false));
                });
        if (afterwards != null) operation.afterwards(result -> afterwards.run());
        launch.accept(operation);
    }

    /** Whether a toolbar operation is in flight, for tests. */
    boolean isRunning() {
        return running != null;
    }

    /**
     * " (Ctrl+L)" - the key a tooltip's action also has, written the way this platform
     * writes it. Shortcuts were only discoverable from the cheat sheet.
     */
    static String keys(javafx.scene.input.KeyCode code, boolean shift) {
        javafx.scene.input.KeyCombination combination = shift
                ? new javafx.scene.input.KeyCodeCombination(code, javafx.scene.input.KeyCombination.SHORTCUT_DOWN,
                        javafx.scene.input.KeyCombination.SHIFT_DOWN)
                : new javafx.scene.input.KeyCodeCombination(code, javafx.scene.input.KeyCombination.SHORTCUT_DOWN);
        return " (" + combination.getDisplayText() + ")";
    }

    private Button createButton(String text, javafx.scene.Node icon, String tooltip) {
        Button btn = new Button();
        dress(btn, text, icon, tooltip);
        return btn;
    }

    /**
     * The caption is the button's own text, over its icon.
     *
     * <p>It used to be a Label inside the graphic, with the button itself set to show
     * its graphic only. Nothing could then put a count on the button - the code that
     * tried set a text that was never displayed - and nothing could drop the captions
     * together when the bar ran short.
     */
    private void dress(ButtonBase button, String caption, javafx.scene.Node icon, String tooltip) {
        button.setGraphic(icon);
        button.getStyleClass().add("tool-button");
        button.getProperties().put(CAPTION, caption);
        button.setTooltip(new Tooltip(tooltip));
        // Never narrower than what it says: the bar drops captions as a whole instead.
        button.setMinWidth(Region.USE_PREF_SIZE);
        relabel(button);
    }

    /**
     * A button whose main area performs the obvious action and whose arrow offers the
     * variants.
     *
     * <p>Push and "Push…" used to be two buttons side by side, which asked the user to
     * tell two adjacent arrow glyphs apart in order to find force-push. One control with
     * a default and a menu is the same set of actions with the relationship between them
     * shown rather than implied.
     */
    /** This repository's pull mode, or the global default. */
    private PullMode defaultPullMode() {
        return com.jfx.git.core.RepositoryPreferences.effectivePullMode(gitService.getWorkTree());
    }

    /**
     * Pull and Push, held so a refresh can say how much work is waiting on each.
     *
     * <p>The toolbar had no way to tell you a pull was worth making. Every button looked
     * the same whether the branch was up to date or eleven commits behind, so the only
     * way to find out was to press one and read the status bar afterwards - which means
     * the answer arrived after the decision.
     */
    private SplitMenuButton btnPull;
    private SplitMenuButton btnPush;

    /**
     * Puts the ahead/behind counts on the two buttons they belong to.
     *
     * <p>The arrows match the sidebar's, which already writes tracking as
     * {@code ↑2 ↓3}, so the toolbar is not teaching a second notation for the same fact.
     * A button with work waiting also takes a colour - the accent for commits to send,
     * the attention colour for commits to bring in, as in the sidebar. It used to take a
     * solid fill, which made the two largest, loudest things in the window a pair of
     * buttons that were merely available.
     *
     * @param tracking {@code [ahead, behind]}, as {@code getTrackingStatus} reports it
     */
    private void showPendingWork(int[] tracking) {
        int ahead = tracking != null && tracking.length > 0 ? tracking[0] : 0;
        int behind = tracking != null && tracking.length > 1 ? tracking[1] : 0;
        applyPending(btnPull, behind, "↓", "sync-behind");
        applyPending(btnPush, ahead, "↑", "sync-ahead");
        fit();
    }

    private void applyPending(SplitMenuButton button, int count, String arrow, String direction) {
        if (button == null) {
            return;
        }
        button.getProperties().put(BADGE, count > 0 ? arrow + count : null);
        button.getStyleClass().removeAll("sync-pending", direction);
        if (count > 0) {
            button.getStyleClass().addAll("sync-pending", direction);
        }
        relabel(button);
    }

    private SplitMenuButton createSplitButton(String text, javafx.scene.Node icon, String tooltip,
                                              Runnable primary, MenuItem... items) {
        SplitMenuButton btn = new SplitMenuButton(items);
        dress(btn, text, icon, tooltip);
        btn.setOnAction(e -> primary.run());
        return btn;
    }
}
