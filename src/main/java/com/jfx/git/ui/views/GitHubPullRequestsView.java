package com.jfx.git.ui.views;

import com.jfx.git.core.BuildStatus;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.ListPage;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubPullRequest;
import com.jfx.git.core.github.GitHubRepoRef;
import com.jfx.git.core.github.GitHubResult;
import com.jfx.git.core.github.GitHubReviewComment;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.ui.RepoView;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.dialogs.CreatePullRequestDialog;
import com.jfx.git.ui.dialogs.JfxDialog;
import com.jfx.git.ui.dialogs.MergeStrategyDialog;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
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
 * Open pull requests for the current repository's {@code origin} remote, with a
 * checkout for whichever one is selected (Wave 4, §4.3 Integrations Phase B of the
 * parity plan - PR list/checkout, following on from Phase A's repo browsing on
 * clone). Full PR metadata, build status, and in-app merge remain out of scope for
 * this first pass.
 *
 * <p>Requires both a connected {@link GitHubSession} and an {@code origin} remote that
 * actually resolves to a GitHub {@code owner/repo} ({@link GitHubRepoRef}); either
 * missing is reported in the list's placeholder rather than treated as an error, the
 * same way {@link ReflogView} reports "no history yet" instead of failing.
 *
 * <p>Shown inside the repository's tab rather than in a window of its own; see
 * {@link ProviderView}.
 */
public class GitHubPullRequestsView extends ProviderView {

    private final GitBackend gitService;
    private final StatusBar statusBar;
    /** Runs checkouts and review preparation; a merge made on the server refreshes through it too. */
    private final OperationRunner operations;
    /** Where a review opens: the repository tab, on top of this list. */
    private final Consumer<RepoView> showView;
    private final GitHubClient gitHub;

    private static final List<MergeStrategyDialog.Choice> MERGE_STRATEGIES = List.of(
            new MergeStrategyDialog.Choice("Create a merge commit", "merge"),
            new MergeStrategyDialog.Choice("Squash and merge", "squash"),
            new MergeStrategyDialog.Choice("Rebase and merge", "rebase"));

    private final ListView<GitHubPullRequest> entries = new ListView<>();
    private final Button checkout = new Button("Checkout");
    private final Button merge = new Button("Merge…");
    private final Button review = new Button("Review…");
    private final Button newPullRequest = new Button("New pull request…");
    private final TextField filter = new TextField();
    private final ProviderListing<GitHubPullRequest> listing;
    /** The screen: heading, filter, list and actions, built once. */
    private final VBox content;
    /** The origin's {@code owner/repo}, once read; null until then or when it is not on GitHub. */
    private GitHubRepoRef ref;
    /** A request to open filled in, waiting for the list to know its repository; see {@link #prefill}. */
    private CreatePullRequestDialog.Draft pendingDraft;

    /**
     * @param originUrl the origin remote's URL as the caller already read it off the FX
     *                  thread, or null to have the view read it on the repository lane
     * @param showView  shows a review in the repository's tab, over this list
     */
    public GitHubPullRequestsView(OperationRunner operations, String originUrl, Consumer<RepoView> showView) {
        this(operations, originUrl, new GitHubClient(), showView);
    }

    /** Package-visible seam: a test points this at a local fake server instead of the real API. */
    GitHubPullRequestsView(OperationRunner operations, GitHubClient gitHub) {
        this(operations, null, gitHub, view -> { });
    }

    GitHubPullRequestsView(OperationRunner operations, String originUrl, GitHubClient gitHub) {
        this(operations, originUrl, gitHub, view -> { });
    }

    GitHubPullRequestsView(OperationRunner operations, String originUrl, GitHubClient gitHub, Consumer<RepoView> showView) {
        super("Pull requests");
        this.operations = operations;
        this.gitService = operations.repository();
        this.statusBar = operations.statusBar();
        this.showView = showView;
        this.gitHub = gitHub;

        filter.setPromptText("Filter by title or author…");
        filter.getStyleClass().add("sidebar-filter");
        listing = new ProviderListing<>(entries, filter, "pull requests", "GitHub",
                GitHubPullRequest::number, GitHubPullRequestsView::matches);
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
        newPullRequest.setOnAction(e -> newPullRequestSelected());

        // The leading action opens a new request and the trailing three act on the
        // selection, so browser() puts the spacer between them - the same asymmetry
        // all three providers had built by hand, with their own spacing each.
        content = JfxDialog.browser(
                "Open pull requests for this repository's origin remote.",
                filter, listing.area(), newPullRequest, review, merge, checkout);

        load();
    }

    /**
     * Waits for a GitHub connection, reads the origin, then shows the newest page - see
     * {@link ProviderListing} for what each step does while it waits.
     */
    private void load() {
        newPullRequest.setDisable(true);
        listing.whenConnected(GitHubSession::isConnected, () -> listing.withOrigin(gitService, url -> {
            var found = GitHubRepoRef.fromRemoteUrl(url);
            if (found.isEmpty()) {
                listing.placeholder("This repository's origin remote is not a GitHub repository.");
                return;
            }
            GitHubRepoRef repo = found.get();
            ref = repo;
            newPullRequest.setDisable(false);
            if (pendingDraft != null) newPullRequestSelected();
            // GitHub's list carries no combined status, so each shown row asks for its own.
            listing.statuses(pr -> () -> {
                var status = gitHub.getCheckStatus(GitHubSession.token(), repo.owner(), repo.repo(), pr.head().sha());
                return status.isOk() ? status.orNull() : null;
            });
            listing.load(page -> {
                var result = gitHub.listPullRequestsPage(GitHubSession.token(), repo.owner(), repo.repo(),
                        page, ListPage.SIZE);
                return result.isOk()
                        ? ProviderListing.Fetched.ok(result.orNull())
                        : ProviderListing.Fetched.failed(result.messageOrNull());
            });
        }));
    }

    /** Matches on title or author, substring, case-insensitive - commercial clients' PR/MR filters do no more. */
    private static boolean matches(GitHubPullRequest pr, String needle) {
        if (pr.title() != null && pr.title().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        String author = pr.user() == null ? null : pr.user().login();
        return author != null && author.toLowerCase(Locale.ROOT).contains(needle);
    }

    private void checkoutSelected() {
        GitHubPullRequest pr = entries.getSelectionModel().getSelectedItem();
        if (pr == null) {
            return;
        }
        checkout.setDisable(true);
        PullRequestCheckout.start(operations, "pr-" + pr.number(), "pull request #" + pr.number(),
                reset -> gitService.checkoutPullRequest("origin", pr.number(), reset),
                result -> checkout.setDisable(entries.getSelectionModel().getSelectedItem() == null));
    }

    /**
     * Merges the selected pull request through GitHub's own API - the merge happens
     * entirely server-side, so nothing about the local repository changes until the
     * next fetch or pull. Re-loads the list afterward so a merged PR drops off it,
     * the way it would if GitHub's own UI had merged it instead.
     */
    private void mergeSelected() {
        GitHubPullRequest pr = entries.getSelectionModel().getSelectedItem();
        if (pr == null) {
            return;
        }
        new MergeStrategyDialog("#" + pr.number() + " " + pr.title(), MERGE_STRATEGIES)
                .showAndWait()
                .ifPresent(strategy -> doMerge(pr, strategy));
    }

    private void doMerge(GitHubPullRequest pr, String strategy) {
        merge.setDisable(true);
        checkout.setDisable(true);
        statusBar.busy("Merging pull request #" + pr.number());
        GitHubRepoRef repo = refOrRead();
        GitTaskRunner.queryProvider(
                () -> gitHub.mergePullRequest(GitHubSession.token(), repo.owner(), repo.repo(),
                        pr.number(), strategy),
                result -> {
                    statusBar.idle();
                    if (result.isOk()) {
                        statusBar.info(result.orNull());
                        listing.reload();
                        operations.refresh(OperationRunner.Refresh.EVERYTHING);
                    } else {
                        statusBar.error("Could not merge pull request: " + result.messageOrNull());
                        checkout.setDisable(entries.getSelectionModel().getSelectedItem() == null);
                        merge.setDisable(entries.getSelectionModel().getSelectedItem() == null);
                    }
                },
                error -> {
                    statusBar.idle();
                    statusBar.error("Could not merge pull request: " + error);
                    checkout.setDisable(entries.getSelectionModel().getSelectedItem() == null);
                    merge.setDisable(entries.getSelectionModel().getSelectedItem() == null);
                });
    }

    /**
     * Opens {@link PrReviewView} on the selected pull request's diff - computed
     * entirely locally between its base and head commits with the same {@code
     * GitBackend} diff methods every other diff view in this app uses, GitHub's own
     * REST API asked only for review comment metadata, never for diff content itself.
     * Fetching both endpoints of that diff first is real, possibly slow work (a
     * network round trip plus one local diff per changed file), so the whole
     * preparation runs in the background and the review only opens once it is ready.
     */
    private void reviewSelected() {
        GitHubPullRequest pr = entries.getSelectionModel().getSelectedItem();
        if (pr == null) {
            return;
        }
        if (ref == null) {
            return;
        }
        GitHubRepoRef repo = ref;
        operations.operation("Preparing review for pull request #" + pr.number(), OperationRunner.Refresh.NONE)
                // The review opening is the answer; a failure says what stopped it.
                .showSuccess(result -> { })
                .reportFailureAfter("Could not prepare review")
                .start(steps -> steps.read(() -> prepareReviewFiles(pr), outcome -> {
                    if (outcome.ok()) {
                        PrReviewView review = new PrReviewView("#" + pr.number() + " " + pr.title(),
                                outcome.value(), buildReviewBackend(pr, repo), statusBar);
                        review.setFileContentSupplier(gitService, path -> gitService.getFileContentAtCommit(pr.head().sha(), path));
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
     * Fetches the PR's head commit locally (without checking anything out - {@link
     * GitBackend#fetchRemoteRef} exists exactly for this) and, best-effort, brings
     * every remote-tracking branch up to date too - a stale local clone's own {@code
     * origin/<base>} might not yet reach the exact commit the PR recorded as its base
     * at open time, and a plain {@code git diff base..head} needs both ends present.
     * A {@code fetchAll} failure here is not itself fatal to the review: if the base
     * commit turns out unresolvable anyway, the diff calls below fail on their own
     * and that failure is what gets reported.
     */
    private PrReviewView.Outcome<List<PrReviewView.ReviewFile>> prepareReviewFiles(GitHubPullRequest pr) {
        var fetch = gitService.fetchRemoteRef("origin", "refs/pull/" + pr.number() + "/head");
        if (!fetch.isOk()) {
            return PrReviewView.Outcome.failed(fetch.getMessage());
        }
        gitService.fetchAll();
        // From the merge-base, as GitHub shows it ("three-dot"). Diffing from the base
        // branch's tip showed every change made on the base since the branch point as a
        // revert inside the pull request.
        String head = pr.head().sha();
        String forkPoint = gitService.mergeBase(pr.base().sha(), head);
        String from = forkPoint != null ? forkPoint : pr.base().sha();
        List<String> paths = gitService.getFilesBetweenCommits(from, head);
        List<PrReviewView.ReviewFile> files = new ArrayList<>();
        for (String path : paths) {
            byte[] diff = gitService.getDiffBytesBetweenCommits(from, head, path, false);
            files.add(new PrReviewView.ReviewFile(path, diff));
        }
        return PrReviewView.Outcome.ok(files);
    }

    /** The one seam {@link PrReviewView} needs adapted per provider - see its own javadoc. */
    private PrReviewView.Backend buildReviewBackend(GitHubPullRequest pr, GitHubRepoRef ref) {
        return new PrReviewView.Backend() {
            @Override
            public void fetchComments(java.util.function.Consumer<PrReviewView.Outcome<
                    List<com.jfx.git.model.ReviewComment>>> onDone) {
                GitTaskRunner.queryProvider(
                        () -> gitHub.listReviewComments(GitHubSession.token(), ref.owner(), ref.repo(), pr.number()),
                        result -> onDone.accept(result.isOk()
                                ? PrReviewView.Outcome.ok(result.orNull().stream()
                                        .map(GitHubPullRequestsView::toReviewComment).toList())
                                : PrReviewView.Outcome.failed(result.messageOrNull())),
                        error -> onDone.accept(PrReviewView.Outcome.failed(error)));
            }

            @Override
            public void postComment(String path, int line, com.jfx.git.model.ReviewComment.Side side, String body,
                                     java.util.function.Consumer<PrReviewView.Outcome<Void>> onDone) {
                String githubSide = side == com.jfx.git.model.ReviewComment.Side.NEW ? "RIGHT" : "LEFT";
                GitTaskRunner.queryProvider(
                        () -> postAnchoredToTheReviewedCommit(path, line, githubSide, body),
                        result -> onDone.accept(result.isOk()
                                ? PrReviewView.Outcome.ok(null)
                                : PrReviewView.Outcome.failed(result.messageOrNull())),
                        error -> onDone.accept(PrReviewView.Outcome.failed(error)));
            }

            @Override
            public boolean supportsSuggestions() {
                return true;
            }

            /**
             * Posts a review comment against the commit the reviewer was actually reading.
             *
             * <h2>Why not the current head</h2>
             * A comment's {@code line} is a position in the diff of {@code commit_id}, and
             * the line numbers here were read from the diff this view is showing - the
             * one for the head the review was opened with. GitHub keeps that commit a
             * member of the pull request after a push, so posting against it is valid: the
             * comment lands on the code the reviewer was looking at and is marked
             * outdated, which is what GitHub's own review UI does with the same situation.
             *
             * <p>Re-anchoring to the newest commit instead would reinterpret those line
             * numbers against a different file. When the line still exists but the content
             * around it has shifted - the common case after a push - the comment attaches
             * to the wrong code and says nothing about it. Trading a visible "outdated"
             * badge for a silent mis-anchor is a bad trade, and the loud failure it was
             * meant to prevent only happens in the rarer case where the line is gone.
             *
             * <p>So the head is still re-read, but only to <em>say</em> that the pull
             * request moved. The comment goes where the reviewer meant it to go.
             *
             * <p>Note this differs from the GitLab view on purpose. GitLab validates a
             * note's position against the merge request's current {@code diff_refs} and
             * rejects stale ones, so there the re-read has to feed the anchor. Matching
             * the two in code shape would mean ignoring what each API actually does.
             */
            private GitHubResult<GitHubReviewComment> postAnchoredToTheReviewedCommit(
                    String path, int line, String githubSide, String body) {
                String reviewed = pr.head().sha();

                GitHubResult<GitHubReviewComment> posted = gitHub.createReviewComment(
                        GitHubSession.token(), ref.owner(), ref.repo(), pr.number(),
                        reviewed, path, line, githubSide, body);
                if (posted.isOk()) {
                    return posted;
                }

                // Only now is it worth a round trip: a rejection is where knowing the pull
                // request has moved changes what the user should do about it.
                GitHubResult<GitHubPullRequest> fresh = gitHub.fetchPullRequest(
                        GitHubSession.token(), ref.owner(), ref.repo(), pr.number());
                boolean moved = fresh.isOk() && fresh.orNull() != null
                        && fresh.orNull().head() != null
                        && !reviewed.equals(fresh.orNull().head().sha());
                if (!moved) {
                    return posted;
                }
                return GitHubResult.failed(posted.messageOrNull()
                        + " - the pull request has new commits since this review was"
                        + " opened, and this line is no longer in its diff. Reopen the"
                        + " review to comment on the current version.");
            }
        };
    }

    /** GitHub's own review comment, normalized to the shape {@link PrReviewView} renders. */
    private static com.jfx.git.model.ReviewComment toReviewComment(GitHubReviewComment comment) {
        com.jfx.git.model.ReviewComment.Side side = "LEFT".equals(comment.side())
                ? com.jfx.git.model.ReviewComment.Side.OLD
                : com.jfx.git.model.ReviewComment.Side.NEW;
        String author = comment.user() == null ? "unknown" : comment.user().login();
        return new com.jfx.git.model.ReviewComment(comment.path(), comment.line(), side, author, comment.body());
    }

    /**
     * Opens {@link CreatePullRequestDialog} pre-selecting the currently checked-out
     * branch as the source, then creates the pull request through GitHub's own API on
     * confirm and reloads the list so the new entry appears.
     */
    private void newPullRequestSelected() {
        if (ref == null) {
            return;
        }
        GitHubRepoRef repo = ref;
        newPullRequest.setDisable(true);
        // Branches are read on the repository lane: the FX thread never waits on git.
        GitTaskRunner.query(gitService, CreatePullRequestDialog.Branches.reader(gitService),
                branches -> {
                    newPullRequest.setDisable(false);
                    if (listing.isClosed()) return;
                    CreatePullRequestDialog dialog = new CreatePullRequestDialog("pull request", branches.names(),
                            branches.current(), gitService, gitService::getCommitsInRange);
                    dialog.prefill(pendingDraft);
                    pendingDraft = null;
                    dialog.showAndWait()
                            .ifPresent(draft -> doCreate(repo, draft));
                },
                error -> {
                    newPullRequest.setDisable(false);
                    statusBar.error("Could not read the branches: " + error);
                });
    }

    private void doCreate(GitHubRepoRef ref, CreatePullRequestDialog.Draft draft) {
        statusBar.busy("Creating pull request");
        GitTaskRunner.queryProvider(
                () -> gitHub.createPullRequest(GitHubSession.token(), ref.owner(), ref.repo(),
                        draft.title(), draft.descriptionOrNull(), draft.sourceBranch(), draft.targetBranch()),
                result -> {
                    statusBar.idle();
                    if (result.isOk()) {
                        statusBar.info("Opened pull request #" + result.orNull().number());
                        listing.reload();
                    } else {
                        statusBar.error("Could not create pull request: " + result.messageOrNull());
                    }
                },
                error -> {
                    statusBar.idle();
                    statusBar.error("Could not create pull request: " + error);
                });
    }

    /**
     * The origin's {@code owner/repo}: the one {@link #load} read, or - for a test seam
     * driven before the load lands - read now.
     */
    private GitHubRepoRef refOrRead() {
        return ref != null ? ref : GitHubRepoRef.fromRemoteUrl(gitService.getRemoteUrl("origin")).orElse(null);
    }

    @Override
    public VBox content() {
        return content;
    }

    /**
     * Opens the new-pull request form filled in from {@code draft} - at once if the
     * repository is known, else as soon as the list has read it.
     */
    @Override
    public void prefill(CreatePullRequestDialog.Draft draft) {
        pendingDraft = draft;
        if (ref != null && !newPullRequest.isDisabled()) newPullRequestSelected();
    }

    @Override
    ProviderListing<?> listing() {
        return listing;
    }

    /** Package-visible: the listing, for tests of paging and closing. */
    ProviderListing<GitHubPullRequest> listingForTest() {
        return listing;
    }

    /** Kept package-visible for tests that need the loaded rows. */
    java.util.List<GitHubPullRequest> loadedEntries() {
        return java.util.List.copyOf(entries.getItems());
    }

    /** Package-visible: selects a row, as a click would. */
    void selectForTest(GitHubPullRequest pr) {
        entries.getSelectionModel().select(pr);
    }

    /** Package-visible: the selected row, or null. */
    GitHubPullRequest selectedForTest() {
        return entries.getSelectionModel().getSelectedItem();
    }

    /** Package-visible: types into the filter field the same way a user would. */
    void filterForTest(String query) {
        filter.setText(query);
    }

    /**
     * Package-visible: a test selects a row and fires the checkout action directly,
     * bypassing the {@link ListView}'s own selection/click mechanics - the same
     * reasoning {@code IntegrationDialog.connectWith} is package-visible for.
     */
    void checkoutForTest(GitHubPullRequest pr) {
        entries.getSelectionModel().select(pr);
        checkoutSelected();
    }

    /** Package-visible: whether the Checkout button is currently enabled. */
    boolean isCheckoutEnabled() {
        return !checkout.isDisabled();
    }

    /**
     * Package-visible: a test selects a row and merges it with a chosen strategy
     * directly, bypassing both the {@link ListView}'s own selection mechanics and the
     * {@link MergeStrategyDialog} popup - the same reasoning {@link #checkoutForTest}
     * already established for this view's other action.
     */
    void mergeForTest(GitHubPullRequest pr, String strategy) {
        entries.getSelectionModel().select(pr);
        doMerge(pr, strategy);
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
    PrReviewView.Outcome<List<PrReviewView.ReviewFile>> reviewFilesForTest(GitHubPullRequest pr) {
        return prepareReviewFiles(pr);
    }

    /** Package-visible: the {@link PrReviewView.Backend} adapter this PR would be reviewed with. */
    PrReviewView.Backend reviewBackendForTest(GitHubPullRequest pr) {
        return buildReviewBackend(pr, refOrRead());
    }

    /**
     * Package-visible: creates a pull request directly from a already-filled {@link
     * CreatePullRequestDialog.Draft}, bypassing the compose popup - the same reasoning
     * {@link #checkoutForTest} already established for this view's other actions.
     */
    void createForTest(CreatePullRequestDialog.Draft draft) {
        GitHubRepoRef repo = refOrRead();
        if (repo != null) doCreate(repo, draft);
    }

    /** Package-visible: whether the "New pull request…" button is currently enabled. */
    boolean isNewPullRequestEnabled() {
        return !newPullRequest.isDisabled();
    }

    /** Package-visible: the cached CI status for a loaded pull request, or {@code null} if not fetched yet. */
    BuildStatus statusForTest(int number) {
        return listing.statusOf(number);
    }

    /** Number, title, author, draft flag, source→target branches, and CI status. */
    private static final class EntryCell extends ListCell<GitHubPullRequest> {

        private final java.util.function.Function<GitHubPullRequest, BuildStatus> statusOf;
        private final Label statusDot = new Label("●");
        private final Label title = new Label();
        private final Label meta = new Label();
        private final HBox row;

        EntryCell(java.util.function.Function<GitHubPullRequest, BuildStatus> statusOf) {
            this.statusOf = statusOf;
            statusDot.getStyleClass().add("pr-status");
            title.getStyleClass().add("reflog-comment");
            meta.getStyleClass().add("reflog-ids");
            VBox box = new VBox(2, title, meta);
            row = new HBox(6, statusDot, box);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(GitHubPullRequest item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            title.setText("#" + item.number() + (item.draft() ? " (draft) " : " ") + item.title());
            String author = item.user() == null ? "unknown" : item.user().login();
            meta.setText(author + " wants to merge " + item.head().ref() + " into " + item.base().ref());
            PrStatusIcon.style(statusDot, statusOf.apply(item));
            setGraphic(row);
        }
    }
}
