package com.jfx.git.ui.views;

import com.jfx.git.core.BuildStatus;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.ListPage;
import com.jfx.git.core.gitlab.GitLabClient;
import com.jfx.git.core.gitlab.GitLabDiscussion;
import com.jfx.git.core.gitlab.GitLabMergeRequest;
import com.jfx.git.core.gitlab.GitLabProjectRef;
import com.jfx.git.core.gitlab.GitLabResult;
import com.jfx.git.core.gitlab.GitLabSession;
import com.jfx.git.model.ReviewComment;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.RepoView;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.dialogs.CreatePullRequestDialog;
import com.jfx.git.ui.dialogs.DialogParts;
import com.jfx.git.ui.dialogs.JfxDialog;
import com.jfx.git.ui.dialogs.MergeStrategyDialog;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Open merge requests for the current repository's {@code origin} remote, with a
 * checkout for whichever one is selected - {@code GitHubPullRequestsView} copied
 * almost line for line, the GitLab half of the same parity-plan item (Wave 4, §4.3).
 *
 * <p>Checkout goes through {@link GitBackend#checkoutRemoteRef} rather than {@link
 * GitBackend#checkoutPullRequest}: GitLab exposes a merge request at {@code
 * refs/merge-requests/<iid>/head}, the general form's job, not GitHub's specific one.
 *
 * <p>Requires both a connected {@link GitLabSession} and an {@code origin} remote that
 * actually resolves to a GitLab project ({@link GitLabProjectRef}); either missing is
 * reported in the list's placeholder rather than treated as an error.
 *
 * <p>Shown inside the repository's tab rather than in a window of its own; see
 * {@link ProviderView}.
 */
public class GitLabMergeRequestsView extends ProviderView {

    private final GitBackend gitService;
    private final StatusBar statusBar;
    /** Runs checkouts and review preparation; a merge made on the server refreshes through it too. */
    private final OperationRunner operations;
    /** Where a review opens: the repository tab, on top of this list. */
    private final Consumer<RepoView> showView;
    private final GitLabClient gitLab;

    private final ListView<GitLabMergeRequest> entries = new ListView<>();
    private final Button checkout = new Button("Checkout");
    private final Button merge = new Button("Merge…");
    private final Button review = new Button("Review…");
    private final Button newMergeRequest = new Button("New merge request…");
    private final TextField filter = new TextField();
    private final ProviderListing<GitLabMergeRequest> listing;
    /** The screen: heading, filter, list and actions, built once. */
    private final VBox content;
    /** The origin's project, once read; null until then or when it is not on GitLab. */
    private GitLabProjectRef ref;
    /** A request to open filled in, waiting for the list to know its repository; see {@link #prefill}. */
    private CreatePullRequestDialog.Draft pendingDraft;

    /**
     * @param originUrl the origin remote's URL as the caller already read it off the FX
     *                  thread, or null to have the view read it on the repository lane
     * @param showView  shows a review in the repository's tab, over this list
     */
    public GitLabMergeRequestsView(OperationRunner operations, String originUrl, Consumer<RepoView> showView) {
        this(operations, originUrl, new GitLabClient(), showView);
    }

    /** Package-visible seam: a test points this at a local fake server instead of the real API. */
    GitLabMergeRequestsView(OperationRunner operations, GitLabClient gitLab) {
        this(operations, null, gitLab, view -> { });
    }

    GitLabMergeRequestsView(OperationRunner operations, String originUrl, GitLabClient gitLab) {
        this(operations, originUrl, gitLab, view -> { });
    }

    GitLabMergeRequestsView(OperationRunner operations, String originUrl, GitLabClient gitLab, Consumer<RepoView> showView) {
        super("Merge requests");
        this.operations = operations;
        this.gitService = operations.repository();
        this.statusBar = operations.statusBar();
        this.showView = showView;
        this.gitLab = gitLab;

        filter.setPromptText("Filter by title or author…");
        filter.getStyleClass().add("sidebar-filter");
        listing = new ProviderListing<>(entries, filter, "merge requests", "GitLab",
                GitLabMergeRequest::iid, GitLabMergeRequestsView::matches);
        listing.knownOrigin(originUrl);
        entries.setCellFactory(view -> new EntryCell(listing::statusOfItem));

        checkout.getStyleClass().add("btn-primary");
        checkout.setDisable(true);
        checkout.setOnAction(e -> checkoutSelected());
        merge.setDisable(true);
        merge.setOnAction(e -> mergeSelected());
        review.setDisable(true);
        review.setOnAction(e -> reviewSelected());
        entries.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            checkout.setDisable(item == null);
            merge.setDisable(item == null);
            review.setDisable(item == null);
        });
        newMergeRequest.setOnAction(e -> newMergeRequestSelected());

        // The leading action opens a new request and the trailing three act on the
        // selection, so browser() puts the spacer between them - the same asymmetry
        // all three providers had built by hand, with their own spacing each.
        content = JfxDialog.browser(
                "Open merge requests for this repository's origin remote.",
                filter, listing.area(), newMergeRequest, review, merge, checkout);

        load();
    }

    /**
     * Waits for a GitLab connection, reads the origin, then shows the newest page - see
     * {@link ProviderListing} for what each step does while it waits.
     */
    private void load() {
        newMergeRequest.setDisable(true);
        listing.whenConnected(GitLabSession::isConnected, () -> listing.withOrigin(gitService, url -> {
            var found = GitLabProjectRef.fromRemoteUrl(url);
            if (found.isEmpty()) {
                listing.placeholder("This repository's origin remote is not a GitLab project.");
                return;
            }
            GitLabProjectRef project = found.get();
            ref = project;
            newMergeRequest.setDisable(false);
            if (pendingDraft != null) newMergeRequestSelected();
            // GitLab's list carries no combined status, so each shown row asks for its own.
            listing.statuses(mr -> () -> {
                if (mr.sha() == null) return null;
                var status = gitLab.getCommitStatus(GitLabSession.token(), project.encodedPath(), mr.sha());
                return status.isOk() ? status.orNull() : null;
            });
            listing.load(page -> {
                var result = gitLab.listMergeRequestsPage(GitLabSession.token(), project.encodedPath(),
                        page, ListPage.SIZE);
                return result.isOk()
                        ? ProviderListing.Fetched.ok(result.orNull())
                        : ProviderListing.Fetched.failed(result.messageOrNull());
            });
        }));
    }

    /** Matches on title or author, substring, case-insensitive - commercial clients' PR/MR filters do no more. */
    private static boolean matches(GitLabMergeRequest mr, String needle) {
        if (mr.title() != null && mr.title().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        String author = mr.author() == null ? null : mr.author().username();
        return author != null && author.toLowerCase(Locale.ROOT).contains(needle);
    }

    private void checkoutSelected() {
        GitLabMergeRequest mr = entries.getSelectionModel().getSelectedItem();
        if (mr == null) {
            return;
        }
        checkout.setDisable(true);
        PullRequestCheckout.start(operations, "mr-" + mr.iid(), "merge request !" + mr.iid(),
                reset -> gitService.checkoutRemoteRef(
                        "origin", "refs/merge-requests/" + mr.iid() + "/head", "mr-" + mr.iid(), reset),
                result -> checkout.setDisable(entries.getSelectionModel().getSelectedItem() == null));
    }

    /**
     * Merges the selected merge request through GitLab's own API - the merge happens
     * entirely server-side, so nothing about the local repository changes until the
     * next fetch or pull. Re-loads the list afterward so a merged MR drops off it, the
     * way it would if GitLab's own UI had merged it instead.
     *
     * <p>Unlike GitHub/Bitbucket there is no strategy to pick here - see the class
     * Javadoc on {@link MergeStrategyDialog} for why - only whether to squash, which
     * this confirms with one toggle rather than reusing that dialog for a choice it was
     * not built to offer.
     */
    private void mergeSelected() {
        GitLabMergeRequest mr = entries.getSelectionModel().getSelectedItem();
        if (mr == null) {
            return;
        }
        confirmSquash("!" + mr.iid() + " " + mr.title()).ifPresent(squash -> doMerge(mr, squash));
    }

    private static java.util.Optional<Boolean> confirmSquash(String subject) {
        return squashDialog(subject).showAndWait();
    }

    /**
     * The merge confirmation, built and not shown: headed like the other providers' merge
     * ({@link MergeStrategyDialog}), with squashing as a toggle that says what it does. The
     * result is whether to squash; empty when cancelled.
     */
    static Dialog<Boolean> squashDialog(String subject) {
        Dialog<Boolean> confirm = new Dialog<>();
        JfxDialog.shell(confirm, JfxDialog.Size.SM, "Merge");
        JfxDialog.header(confirm, Icons.pullRequest(), JfxDialog.Tone.NEUTRAL, "Merge " + subject,
                "On GitLab, by the project's own merge method");
        confirm.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        JfxDialog.verb(confirm, ButtonType.OK, "Merge");
        CheckBox squash = new CheckBox("Squash commits");
        squash.setId(SQUASH_ID);
        confirm.getDialogPane().setContent(DialogParts.toggle(squash,
                "Its commits land as one commit. Unticked, each lands as it was made."));
        confirm.setResultConverter(button -> button == ButtonType.OK ? squash.isSelected() : null);
        return confirm;
    }

    /** The merge confirmation's "Squash commits" toggle, for tests. */
    static final String SQUASH_ID = "gitlab-merge-squash";

    private void doMerge(GitLabMergeRequest mr, boolean squash) {
        merge.setDisable(true);
        checkout.setDisable(true);
        statusBar.busy("Merging merge request !" + mr.iid());
        GitLabProjectRef project = refOrRead();
        GitTaskRunner.queryProvider(
                () -> gitLab.mergeMergeRequest(GitLabSession.token(), project.encodedPath(), mr.iid(), squash),
                result -> {
                    statusBar.idle();
                    if (result.isOk()) {
                        statusBar.info("Merged !" + mr.iid() + " (" + result.orNull() + ")");
                        listing.reload();
                        operations.refresh(OperationRunner.Refresh.EVERYTHING);
                    } else {
                        statusBar.error("Could not merge merge request: " + result.messageOrNull());
                        checkout.setDisable(entries.getSelectionModel().getSelectedItem() == null);
                        merge.setDisable(entries.getSelectionModel().getSelectedItem() == null);
                    }
                },
                error -> {
                    statusBar.idle();
                    statusBar.error("Could not merge merge request: " + error);
                    checkout.setDisable(entries.getSelectionModel().getSelectedItem() == null);
                    merge.setDisable(entries.getSelectionModel().getSelectedItem() == null);
                });
    }

    /** Bundles what the diff needs (the files) with what posting a new comment needs (the refreshed {@code diffRefs}). */
    private record ReviewPrep(GitLabMergeRequest mergeRequest, List<PrReviewView.ReviewFile> files) {
    }

    /**
     * Opens {@link PrReviewView} on the selected merge request's diff - computed
     * entirely locally between its base and head commits with the same {@code
     * GitBackend} diff methods every other diff view in this app uses, GitLab's own
     * REST API asked only for discussion metadata, never for diff content itself.
     */
    private void reviewSelected() {
        GitLabMergeRequest mr = entries.getSelectionModel().getSelectedItem();
        if (mr == null) {
            return;
        }
        if (ref == null) {
            return;
        }
        GitLabProjectRef project = ref;
        operations.operation("Preparing review for merge request !" + mr.iid(), OperationRunner.Refresh.NONE)
                // The review opening is the answer; a failure says what stopped it.
                .showSuccess(result -> { })
                .reportFailureAfter("Could not prepare review")
                .start(steps -> steps.read(() -> prepareReviewFiles(project, mr), outcome -> {
                    if (outcome.ok()) {
                        GitLabMergeRequest full = outcome.value().mergeRequest();
                        PrReviewView review = new PrReviewView("!" + mr.iid() + " " + mr.title(),
                                outcome.value().files(), buildReviewBackend(project, full), statusBar);
                        review.setFileContentSupplier(gitService,
                                path -> gitService.getFileContentAtCommit(full.diffRefs().headSha(), path));
                        openReview(review);
                        steps.finish(com.jfx.git.core.GitResult.ok());
                    } else {
                        steps.finish(com.jfx.git.core.GitResult.failed(outcome.message()));
                    }
                }));
    }

    /**
     * Puts a prepared review on top of this list in the repository's tab, so its Back
     * returns here. Dropped if the list closed while the review was being prepared: the
     * user has gone elsewhere, and a review turning up over wherever that is would be a
     * surprise.
     */
    private void openReview(PrReviewView review) {
        if (listing.isClosed()) return;
        showView.accept(review);
    }

    /**
     * {@code listMergeRequests}' own response may not carry {@code diff_refs} with the
     * same completeness a single merge request's own detail view does, so this
     * re-fetches the merge request by {@code iid} first rather than trusting whatever
     * the already-loaded list row happens to carry - the same shape {@code diff_refs}
     * needs three shas together as one unit for, not just the one {@link
     * GitLabMergeRequest#sha()} the list view already exposes.
     */
    private PrReviewView.Outcome<ReviewPrep> prepareReviewFiles(GitLabProjectRef ref, GitLabMergeRequest mr) {
        var detail = gitLab.getMergeRequest(GitLabSession.token(), ref.encodedPath(), mr.iid());
        if (!detail.isOk()) {
            return PrReviewView.Outcome.failed(detail.messageOrNull());
        }
        GitLabMergeRequest full = detail.orNull();
        if (full.diffRefs() == null) {
            return PrReviewView.Outcome.failed("GitLab did not report this merge request's diff refs");
        }
        var fetch = gitService.fetchRemoteRef("origin", "refs/merge-requests/" + mr.iid() + "/head");
        if (!fetch.isOk()) {
            return PrReviewView.Outcome.failed(fetch.getMessage());
        }
        gitService.fetchAll();
        String baseSha = full.diffRefs().baseSha();
        String headSha = full.diffRefs().headSha();
        List<String> paths = gitService.getFilesBetweenCommits(baseSha, headSha);
        List<PrReviewView.ReviewFile> files = new ArrayList<>();
        for (String path : paths) {
            byte[] diff = gitService.getDiffBytesBetweenCommits(baseSha, headSha, path, false);
            files.add(new PrReviewView.ReviewFile(path, diff));
        }
        return PrReviewView.Outcome.ok(new ReviewPrep(full, files));
    }

    /** The one seam {@link PrReviewView} needs adapted per provider - see its own javadoc. */
    private PrReviewView.Backend buildReviewBackend(GitLabProjectRef ref, GitLabMergeRequest mr) {
        return new PrReviewView.Backend() {
            @Override
            public void fetchComments(java.util.function.Consumer<PrReviewView.Outcome<List<ReviewComment>>> onDone) {
                GitTaskRunner.queryProvider(
                        () -> gitLab.listDiscussions(GitLabSession.token(), ref.encodedPath(), mr.iid()),
                        result -> onDone.accept(result.isOk()
                                ? PrReviewView.Outcome.ok(result.orNull().stream()
                                        .filter(d -> d.position() != null)
                                        .map(GitLabMergeRequestsView::toReviewComment).toList())
                                : PrReviewView.Outcome.failed(result.messageOrNull())),
                        error -> onDone.accept(PrReviewView.Outcome.failed(error)));
            }

            @Override
            public void postComment(String path, int line, ReviewComment.Side side, String body,
                                     java.util.function.Consumer<PrReviewView.Outcome<Void>> onDone) {
                Integer newLine = side == ReviewComment.Side.NEW ? line : null;
                Integer oldLine = side == ReviewComment.Side.OLD ? line : null;
                GitTaskRunner.queryProvider(
                        () -> postAnchoredToTheCurrentHead(path, oldLine, newLine, body),
                        result -> onDone.accept(result.isOk()
                                ? PrReviewView.Outcome.ok(null)
                                : PrReviewView.Outcome.failed(result.messageOrNull())),
                        error -> onDone.accept(PrReviewView.Outcome.failed(error)));
            }

            /**
             * Posts a discussion against the merge request's diff refs <em>now</em>.
             *
             * <p>GitLab anchors a diff note with the three shas in {@code diff_refs}, and
             * they describe the merge request as it was when this view fetched it. The
             * author pushing again - which is exactly what happens while someone reviews -
             * makes them stale, and GitLab rejects the note. Re-reading them immediately
             * before posting narrows that window from the length of a review to the width
             * of one request.
             *
             * <p>A failed re-read leaves the note anchored to what we already had: a
             * transient blip should not throw away a comment the user has written. If the
             * post then fails against refs we know have moved, the message says so rather
             * than leaving GitLab's wording to be interpreted.
             *
             * @see GitHubPullRequestsView the same problem, same shape
             */
            private GitLabResult<GitLabDiscussion> postAnchoredToTheCurrentHead(
                    String path, Integer oldLine, Integer newLine, String body) {
                var openedWith = mr.diffRefs();
                var anchor = openedWith;

                GitLabResult<GitLabMergeRequest> fresh =
                        gitLab.getMergeRequest(GitLabSession.token(), ref.encodedPath(), mr.iid());
                if (fresh.isOk() && fresh.orNull() != null && fresh.orNull().diffRefs() != null) {
                    anchor = fresh.orNull().diffRefs();
                }

                GitLabResult<GitLabDiscussion> posted = gitLab.createDiscussion(
                        GitLabSession.token(), ref.encodedPath(), mr.iid(), body,
                        anchor, path, path, oldLine, newLine);

                if (!posted.isOk() && anchor != openedWith && !anchor.equals(openedWith)) {
                    return GitLabResult.failed(posted.messageOrNull()
                            + " - the merge request has new commits since this review was"
                            + " opened, so this line may no longer be in its diff. Reopen"
                            + " the review to comment on the current version.");
                }
                return posted;
            }

            @Override
            public boolean supportsSuggestions() {
                return true;
            }
        };
    }

    /** GitLab's own diff discussion, normalized to the shape {@link PrReviewView} renders. */
    private static ReviewComment toReviewComment(GitLabDiscussion discussion) {
        var position = discussion.position();
        ReviewComment.Side side = position.newLine() != null ? ReviewComment.Side.NEW : ReviewComment.Side.OLD;
        Integer line = side == ReviewComment.Side.NEW ? position.newLine() : position.oldLine();
        String path = position.newPath() != null ? position.newPath() : position.oldPath();
        var note = discussion.firstNote();
        String author = note.author() == null ? "unknown" : note.author().username();
        return new ReviewComment(path, line, side, author, note.body());
    }

    /**
     * Opens {@link CreatePullRequestDialog} pre-selecting the currently checked-out
     * branch as the source, then creates the merge request through GitLab's own API
     * on confirm and reloads the list so the new entry appears.
     */
    private void newMergeRequestSelected() {
        if (ref == null) {
            return;
        }
        GitLabProjectRef project = ref;
        newMergeRequest.setDisable(true);
        // Branches are read on the repository lane: the FX thread never waits on git.
        GitTaskRunner.query(gitService, CreatePullRequestDialog.Branches.reader(gitService),
                branches -> {
                    newMergeRequest.setDisable(false);
                    if (listing.isClosed()) return;
                    CreatePullRequestDialog dialog = new CreatePullRequestDialog("merge request", branches.names(),
                            branches.current(), gitService, gitService::getCommitsInRange);
                    dialog.prefill(pendingDraft);
                    pendingDraft = null;
                    dialog.showAndWait()
                            .ifPresent(draft -> doCreate(project, draft));
                },
                error -> {
                    newMergeRequest.setDisable(false);
                    statusBar.error("Could not read the branches: " + error);
                });
    }

    private void doCreate(GitLabProjectRef ref, CreatePullRequestDialog.Draft draft) {
        statusBar.busy("Creating merge request");
        GitTaskRunner.queryProvider(
                () -> gitLab.createMergeRequest(GitLabSession.token(), ref.encodedPath(),
                        draft.title(), draft.descriptionOrNull(), draft.sourceBranch(), draft.targetBranch()),
                result -> {
                    statusBar.idle();
                    if (result.isOk()) {
                        statusBar.info("Opened merge request !" + result.orNull().iid());
                        listing.reload();
                    } else {
                        statusBar.error("Could not create merge request: " + result.messageOrNull());
                    }
                },
                error -> {
                    statusBar.idle();
                    statusBar.error("Could not create merge request: " + error);
                });
    }

    /**
     * The origin's project: the one {@link #load} read, or - for a test seam driven
     * before the load lands - read now.
     */
    private GitLabProjectRef refOrRead() {
        return ref != null ? ref : GitLabProjectRef.fromRemoteUrl(gitService.getRemoteUrl("origin")).orElse(null);
    }

    @Override
    public VBox content() {
        return content;
    }

    /**
     * Opens the new-merge request form filled in from {@code draft} - at once if the
     * repository is known, else as soon as the list has read it.
     */
    @Override
    public void prefill(CreatePullRequestDialog.Draft draft) {
        pendingDraft = draft;
        if (ref != null && !newMergeRequest.isDisabled()) newMergeRequestSelected();
    }

    @Override
    ProviderListing<?> listing() {
        return listing;
    }

    /** Package-visible: the listing, for tests of paging and closing. */
    ProviderListing<GitLabMergeRequest> listingForTest() {
        return listing;
    }

    /** Kept package-visible for tests that need the loaded rows. */
    java.util.List<GitLabMergeRequest> loadedEntries() {
        return java.util.List.copyOf(entries.getItems());
    }

    /** Package-visible: types into the filter field the same way a user would. */
    void filterForTest(String query) {
        filter.setText(query);
    }

    /**
     * Package-visible: a test selects a row and fires the checkout action directly,
     * bypassing the {@link ListView}'s own selection/click mechanics.
     */
    void checkoutForTest(GitLabMergeRequest mr) {
        entries.getSelectionModel().select(mr);
        checkoutSelected();
    }

    /** Package-visible: whether the Checkout button is currently enabled. */
    boolean isCheckoutEnabled() {
        return !checkout.isDisabled();
    }

    /**
     * Package-visible: a test selects a row and merges it with a chosen squash setting
     * directly, bypassing both the {@link ListView}'s own selection mechanics and the
     * confirmation popup - the same reasoning {@link #checkoutForTest} already
     * established for this view's other action.
     */
    void mergeForTest(GitLabMergeRequest mr, boolean squash) {
        entries.getSelectionModel().select(mr);
        doMerge(mr, squash);
    }

    /** Package-visible: whether the Merge button is currently enabled. */
    boolean isMergeEnabled() {
        return !merge.isDisabled();
    }

    /** Package-visible: whether the Review… button is currently enabled. */
    boolean isReviewEnabled() {
        return !review.isDisabled();
    }

    /**
     * Package-visible: runs the real fetch-and-diff preparation directly, bypassing
     * {@link PrReviewView} itself entirely - the same reasoning {@link
     * #createForTest} bypasses {@link CreatePullRequestDialog}'s own popup for.
     */
    PrReviewView.Outcome<List<PrReviewView.ReviewFile>> reviewFilesForTest(GitLabMergeRequest mr) {
        var outcome = prepareReviewFiles(refOrRead(), mr);
        return outcome.ok() ? PrReviewView.Outcome.ok(outcome.value().files())
                : PrReviewView.Outcome.failed(outcome.message());
    }

    /** Package-visible: the {@link PrReviewView.Backend} adapter this merge request would be reviewed with. */
    PrReviewView.Backend reviewBackendForTest(GitLabMergeRequest mr) {
        return buildReviewBackend(refOrRead(), mr);
    }

    /**
     * Package-visible: creates a merge request directly from an already-filled {@link
     * CreatePullRequestDialog.Draft}, bypassing the compose popup - the same reasoning
     * {@link #checkoutForTest} already established for this view's other actions.
     */
    void createForTest(CreatePullRequestDialog.Draft draft) {
        GitLabProjectRef project = refOrRead();
        if (project != null) doCreate(project, draft);
    }

    /** Package-visible: whether the "New merge request…" button is currently enabled. */
    boolean isNewMergeRequestEnabled() {
        return !newMergeRequest.isDisabled();
    }

    /** Package-visible: the cached CI status for a loaded merge request, or {@code null} if not fetched yet. */
    BuildStatus statusForTest(int iid) {
        return listing.statusOf(iid);
    }

    /** Number, title, author, draft flag, source→target branches, and CI status. */
    private static final class EntryCell extends ListCell<GitLabMergeRequest> {

        private final java.util.function.Function<GitLabMergeRequest, BuildStatus> statusOf;
        private final Label statusDot = new Label("●");
        private final Label title = new Label();
        private final Label meta = new Label();
        private final HBox row;

        EntryCell(java.util.function.Function<GitLabMergeRequest, BuildStatus> statusOf) {
            this.statusOf = statusOf;
            statusDot.getStyleClass().add("pr-status");
            title.getStyleClass().add("reflog-comment");
            meta.getStyleClass().add("reflog-ids");
            VBox box = new VBox(2, title, meta);
            row = new HBox(6, statusDot, box);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(GitLabMergeRequest item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            title.setText("!" + item.iid() + (item.draft() ? " (draft) " : " ") + item.title());
            String author = item.author() == null ? "unknown" : item.author().username();
            meta.setText(author + " wants to merge " + item.sourceBranch() + " into " + item.targetBranch());
            PrStatusIcon.style(statusDot, statusOf.apply(item));
            setGraphic(row);
        }
    }
}
