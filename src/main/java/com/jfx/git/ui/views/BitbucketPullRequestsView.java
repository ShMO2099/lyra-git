package com.jfx.git.ui.views;

import com.jfx.git.core.BuildStatus;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.ListPage;
import com.jfx.git.core.bitbucket.BitbucketClient;
import com.jfx.git.core.bitbucket.BitbucketComment;
import com.jfx.git.core.bitbucket.BitbucketPullRequest;
import com.jfx.git.core.bitbucket.BitbucketRepoRef;
import com.jfx.git.core.bitbucket.BitbucketSession;
import com.jfx.git.model.ReviewComment;
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
 * checkout for whichever one is selected - {@code GitHubPullRequestsView} copied
 * almost line for line, the Bitbucket half of the same parity-plan item (Wave 4,
 * §4.3). Bitbucket has no draft-PR concept, so unlike the GitHub/GitLab versions this
 * cell shows no draft indicator.
 *
 * <p>Checkout goes through {@link GitBackend#checkoutRemoteRef}, the general form:
 * Bitbucket publishes no numbered ref for a PR the way GitHub ({@code refs/pull}) or
 * GitLab ({@code refs/merge-requests}) do, so the source branch is fetched by name
 * instead - from {@code origin} when {@link BitbucketPullRequest#isFromFork()} is
 * false, or directly from the fork's own clone URL when it is true, verified to work
 * with no remote configured for that URL at all.
 *
 * <p>Requires both a connected {@link BitbucketSession} and an {@code origin} remote
 * that actually resolves to a Bitbucket repository ({@link BitbucketRepoRef}); either
 * missing is reported in the list's placeholder rather than treated as an error.
 *
 * <p>Shown inside the repository's tab rather than in a window of its own; see
 * {@link ProviderView}.
 */
public class BitbucketPullRequestsView extends ProviderView {

    private final GitBackend gitService;
    private final StatusBar statusBar;
    /** Runs checkouts and review preparation; a merge made on the server refreshes through it too. */
    private final OperationRunner operations;
    /** Where a review opens: the repository tab, on top of this list. */
    private final Consumer<RepoView> showView;
    private final BitbucketClient bitbucket;

    private static final List<MergeStrategyDialog.Choice> MERGE_STRATEGIES = List.of(
            new MergeStrategyDialog.Choice("Merge commit", "merge_commit"),
            new MergeStrategyDialog.Choice("Squash", "squash"),
            new MergeStrategyDialog.Choice("Fast forward", "fast_forward"));

    private final ListView<BitbucketPullRequest> entries = new ListView<>();
    private final Button checkout = new Button("Checkout");
    private final Button merge = new Button("Merge…");
    private final Button review = new Button("Review…");
    private final Button newPullRequest = new Button("New pull request…");
    private final TextField filter = new TextField();
    private final ProviderListing<BitbucketPullRequest> listing;
    /** The screen: heading, filter, list and actions, built once. */
    private final VBox content;
    /** The origin's repository, once read; null until then or when it is not on Bitbucket. */
    private BitbucketRepoRef ref;
    /** A request to open filled in, waiting for the list to know its repository; see {@link #prefill}. */
    private CreatePullRequestDialog.Draft pendingDraft;

    /**
     * @param originUrl the origin remote's URL as the caller already read it off the FX
     *                  thread, or null to have the view read it on the repository lane
     * @param showView  shows a review in the repository's tab, over this list
     */
    public BitbucketPullRequestsView(OperationRunner operations, String originUrl, Consumer<RepoView> showView) {
        this(operations, originUrl, new BitbucketClient(), showView);
    }

    /** Package-visible seam: a test points this at a local fake server instead of the real API. */
    BitbucketPullRequestsView(
            OperationRunner operations, BitbucketClient bitbucket) {
        this(operations, null, bitbucket, view -> { });
    }

    BitbucketPullRequestsView(OperationRunner operations, String originUrl, BitbucketClient bitbucket) {
        this(operations, originUrl, bitbucket, view -> { });
    }

    BitbucketPullRequestsView(OperationRunner operations, String originUrl, BitbucketClient bitbucket, Consumer<RepoView> showView) {
        super("Pull requests");
        this.operations = operations;
        this.gitService = operations.repository();
        this.statusBar = operations.statusBar();
        this.showView = showView;
        this.bitbucket = bitbucket;

        filter.setPromptText("Filter by title or author…");
        filter.getStyleClass().add("sidebar-filter");
        listing = new ProviderListing<>(entries, filter, "pull requests", "Bitbucket",
                BitbucketPullRequest::id, BitbucketPullRequestsView::matches);
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
     * Waits for a Bitbucket connection, reads the origin, then shows the newest page -
     * see {@link ProviderListing} for what each step does while it waits.
     */
    private void load() {
        newPullRequest.setDisable(true);
        listing.whenConnected(BitbucketSession::isConnected, () -> listing.withOrigin(gitService, url -> {
            var found = BitbucketRepoRef.fromRemoteUrl(url);
            if (found.isEmpty()) {
                listing.placeholder("This repository's origin remote is not a Bitbucket repository.");
                return;
            }
            BitbucketRepoRef repo = found.get();
            ref = repo;
            newPullRequest.setDisable(false);
            if (pendingDraft != null) newPullRequestSelected();
            // Bitbucket's list carries no combined status, so each shown row asks for its own.
            listing.statuses(pr -> () -> {
                if (pr.source() == null || pr.source().commit() == null) return null;
                var status = bitbucket.getCommitStatus(BitbucketSession.token(), repo.workspace(), repo.repoSlug(),
                        pr.source().commit().hash());
                return status.isOk() ? status.orNull() : null;
            });
            listing.load(page -> {
                var result = bitbucket.listPullRequestsPage(BitbucketSession.token(), repo.workspace(),
                        repo.repoSlug(), page, ListPage.SIZE);
                return result.isOk()
                        ? ProviderListing.Fetched.ok(result.orNull())
                        : ProviderListing.Fetched.failed(result.messageOrNull());
            });
        }));
    }

    /** Matches on title or author, substring, case-insensitive - commercial clients' PR/MR filters do no more. */
    private static boolean matches(BitbucketPullRequest pr, String needle) {
        if (pr.title() != null && pr.title().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        String author = pr.author() == null ? null : pr.author().handle();
        return author != null && author.toLowerCase(Locale.ROOT).contains(needle);
    }

    private void checkoutSelected() {
        BitbucketPullRequest pr = entries.getSelectionModel().getSelectedItem();
        if (pr == null) {
            return;
        }
        checkout.setDisable(true);
        String remoteNameOrUrl = pr.isFromFork() ? pr.source().repository().cloneUrl() : "origin";
        String branch = pr.source().branch().name();
        PullRequestCheckout.start(operations, "pr-" + pr.id(), "pull request #" + pr.id(),
                reset -> gitService.checkoutRemoteRef(
                        remoteNameOrUrl, "refs/heads/" + branch, "pr-" + pr.id(), reset),
                result -> checkout.setDisable(entries.getSelectionModel().getSelectedItem() == null));
    }

    /**
     * Merges the selected pull request through Bitbucket's own API - the merge
     * happens entirely server-side, so nothing about the local repository changes
     * until the next fetch or pull. Re-loads the list afterward so a merged PR drops
     * off it, the way it would if Bitbucket's own UI had merged it instead.
     */
    private void mergeSelected() {
        BitbucketPullRequest pr = entries.getSelectionModel().getSelectedItem();
        if (pr == null) {
            return;
        }
        new MergeStrategyDialog("#" + pr.id() + " " + pr.title(), MERGE_STRATEGIES)
                .showAndWait()
                .ifPresent(strategy -> doMerge(pr, strategy));
    }

    private void doMerge(BitbucketPullRequest pr, String strategy) {
        merge.setDisable(true);
        checkout.setDisable(true);
        statusBar.busy("Merging pull request #" + pr.id());
        BitbucketRepoRef repo = refOrRead();
        GitTaskRunner.queryProvider(
                () -> bitbucket.mergePullRequest(BitbucketSession.token(), repo.workspace(),
                        repo.repoSlug(), pr.id(), strategy),
                result -> {
                    statusBar.idle();
                    if (result.isOk()) {
                        statusBar.info("Merged #" + pr.id());
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
     * GitBackend} diff methods every other diff view in this app uses. Both shas are
     * already on the loaded {@link BitbucketPullRequest} itself ({@code source.commit}/
     * {@code destination.commit}) - unlike GitLab, no extra detail fetch is needed
     * first. Bitbucket's own API is asked only for comment metadata, never for diff
     * content itself.
     */
    private void reviewSelected() {
        BitbucketPullRequest pr = entries.getSelectionModel().getSelectedItem();
        if (pr == null) {
            return;
        }
        if (ref == null) {
            return;
        }
        BitbucketRepoRef repo = ref;
        operations.operation("Preparing review for pull request #" + pr.id(), OperationRunner.Refresh.NONE)
                // The review opening is the answer; a failure says what stopped it.
                .showSuccess(result -> { })
                .reportFailureAfter("Could not prepare review")
                .start(steps -> steps.read(() -> prepareReviewFiles(pr), outcome -> {
                    if (outcome.ok()) {
                        PrReviewView review = new PrReviewView("#" + pr.id() + " " + pr.title(),
                                outcome.value(), buildReviewBackend(pr, repo), statusBar);
                        // prepareReviewFiles already refused to reach here at all unless
                        // source.commit was present.
                        review.setFileContentSupplier(gitService,
                                path -> gitService.getFileContentAtCommit(pr.source().commit().hash(), path));
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
     * Fetches the PR's source branch locally without checking anything out - from
     * {@code origin} when the source is the same repository, or directly from the
     * fork's own clone URL when it is not, the same distinction {@link
     * #checkoutSelected} already draws.
     */
    private PrReviewView.Outcome<List<PrReviewView.ReviewFile>> prepareReviewFiles(BitbucketPullRequest pr) {
        // Both source and destination commits are usually present, but fetchStatuses
        // above already treats a null source.commit as a real possibility rather than
        // assuming it - destination.commit is checked the same defensive way here.
        if (pr.source() == null || pr.source().commit() == null
                || pr.destination() == null || pr.destination().commit() == null) {
            return PrReviewView.Outcome.failed(
                    "Bitbucket did not report this pull request's source and destination commits");
        }
        String remoteNameOrUrl = pr.isFromFork() ? pr.source().repository().cloneUrl() : "origin";
        String branch = pr.source().branch().name();
        var fetch = gitService.fetchRemoteRef(remoteNameOrUrl, "refs/heads/" + branch);
        if (!fetch.isOk()) {
            return PrReviewView.Outcome.failed(fetch.getMessage());
        }
        gitService.fetchAll();
        // From the merge-base, as Bitbucket shows it: diffing from the destination's tip
        // showed everything merged there since the branch point as reverts in this PR.
        String headSha = pr.source().commit().hash();
        String forkPoint = gitService.mergeBase(pr.destination().commit().hash(), headSha);
        String baseSha = forkPoint != null ? forkPoint : pr.destination().commit().hash();
        List<String> paths = gitService.getFilesBetweenCommits(baseSha, headSha);
        List<PrReviewView.ReviewFile> files = new ArrayList<>();
        for (String path : paths) {
            byte[] diff = gitService.getDiffBytesBetweenCommits(baseSha, headSha, path, false);
            files.add(new PrReviewView.ReviewFile(path, diff));
        }
        return PrReviewView.Outcome.ok(files);
    }

    /**
     * The one seam {@link PrReviewView} needs adapted per provider - see its own
     * javadoc. {@link PrReviewView.Backend#supportsSuggestions} is {@code false}
     * here: Bitbucket has no <code>```suggestion</code>-style markdown syntax the way
     * GitHub/GitLab do, so there is nothing for this provider to render one from.
     */
    private PrReviewView.Backend buildReviewBackend(BitbucketPullRequest pr, BitbucketRepoRef ref) {
        return new PrReviewView.Backend() {
            @Override
            public void fetchComments(java.util.function.Consumer<PrReviewView.Outcome<List<ReviewComment>>> onDone) {
                GitTaskRunner.queryProvider(
                        () -> bitbucket.listComments(BitbucketSession.token(), ref.workspace(), ref.repoSlug(), pr.id()),
                        result -> onDone.accept(result.isOk()
                                ? PrReviewView.Outcome.ok(result.orNull().stream()
                                        .filter(c -> c.inline() != null)
                                        .map(BitbucketPullRequestsView::toReviewComment).toList())
                                : PrReviewView.Outcome.failed(result.messageOrNull())),
                        error -> onDone.accept(PrReviewView.Outcome.failed(error)));
            }

            @Override
            public void postComment(String path, int line, ReviewComment.Side side, String body,
                                     java.util.function.Consumer<PrReviewView.Outcome<Void>> onDone) {
                Integer newLine = side == ReviewComment.Side.NEW ? line : null;
                Integer oldLine = side == ReviewComment.Side.OLD ? line : null;
                GitTaskRunner.queryProvider(
                        () -> bitbucket.createComment(BitbucketSession.token(), ref.workspace(), ref.repoSlug(),
                                pr.id(), path, newLine, oldLine, body),
                        result -> onDone.accept(result.isOk()
                                ? PrReviewView.Outcome.ok(null)
                                : PrReviewView.Outcome.failed(result.messageOrNull())),
                        error -> onDone.accept(PrReviewView.Outcome.failed(error)));
            }

            @Override
            public boolean supportsSuggestions() {
                return false;
            }
        };
    }

    /** Bitbucket's own inline comment, normalized to the shape {@link PrReviewView} renders. */
    private static ReviewComment toReviewComment(BitbucketComment comment) {
        ReviewComment.Side side = comment.inline().to() != null ? ReviewComment.Side.NEW : ReviewComment.Side.OLD;
        Integer line = side == ReviewComment.Side.NEW ? comment.inline().to() : comment.inline().from();
        String author = comment.user() == null ? "unknown" : comment.user().handle();
        return new ReviewComment(comment.inline().path(), line, side, author, comment.content().raw());
    }

    /**
     * Opens {@link CreatePullRequestDialog} pre-selecting the currently checked-out
     * branch as the source, then creates the pull request through Bitbucket's own API
     * on confirm and reloads the list so the new entry appears.
     */
    private void newPullRequestSelected() {
        if (ref == null) {
            return;
        }
        BitbucketRepoRef repo = ref;
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

    private void doCreate(BitbucketRepoRef ref, CreatePullRequestDialog.Draft draft) {
        statusBar.busy("Creating pull request");
        GitTaskRunner.queryProvider(
                () -> bitbucket.createPullRequest(BitbucketSession.token(), ref.workspace(), ref.repoSlug(),
                        draft.title(), draft.descriptionOrNull(), draft.sourceBranch(), draft.targetBranch()),
                result -> {
                    statusBar.idle();
                    if (result.isOk()) {
                        statusBar.info("Opened pull request #" + result.orNull().id());
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
     * The origin's repository: the one {@link #load} read, or - for a test seam driven
     * before the load lands - read now.
     */
    private BitbucketRepoRef refOrRead() {
        return ref != null ? ref : BitbucketRepoRef.fromRemoteUrl(gitService.getRemoteUrl("origin")).orElse(null);
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
    ProviderListing<BitbucketPullRequest> listingForTest() {
        return listing;
    }

    /** Kept package-visible for tests that need the loaded rows. */
    java.util.List<BitbucketPullRequest> loadedEntries() {
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
    void checkoutForTest(BitbucketPullRequest pr) {
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
    void mergeForTest(BitbucketPullRequest pr, String strategy) {
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
    PrReviewView.Outcome<List<PrReviewView.ReviewFile>> reviewFilesForTest(BitbucketPullRequest pr) {
        return prepareReviewFiles(pr);
    }

    /** Package-visible: the {@link PrReviewView.Backend} adapter this PR would be reviewed with. */
    PrReviewView.Backend reviewBackendForTest(BitbucketPullRequest pr) {
        return buildReviewBackend(pr, refOrRead());
    }

    /**
     * Package-visible: creates a pull request directly from an already-filled {@link
     * CreatePullRequestDialog.Draft}, bypassing the compose popup - the same reasoning
     * {@link #checkoutForTest} already established for this view's other actions.
     */
    void createForTest(CreatePullRequestDialog.Draft draft) {
        BitbucketRepoRef repo = refOrRead();
        if (repo != null) doCreate(repo, draft);
    }

    /** Package-visible: whether the "New pull request…" button is currently enabled. */
    boolean isNewPullRequestEnabled() {
        return !newPullRequest.isDisabled();
    }

    /** Package-visible: the cached CI status for a loaded pull request, or {@code null} if not fetched yet. */
    BuildStatus statusForTest(int id) {
        return listing.statusOf(id);
    }

    /** Number, title, author, source→target branches, and CI status - a fork's PR names its own repository too. */
    private static final class EntryCell extends ListCell<BitbucketPullRequest> {

        private final java.util.function.Function<BitbucketPullRequest, BuildStatus> statusOf;
        private final Label statusDot = new Label("●");
        private final Label title = new Label();
        private final Label meta = new Label();
        private final HBox row;

        EntryCell(java.util.function.Function<BitbucketPullRequest, BuildStatus> statusOf) {
            this.statusOf = statusOf;
            statusDot.getStyleClass().add("pr-status");
            title.getStyleClass().add("reflog-comment");
            meta.getStyleClass().add("reflog-ids");
            VBox box = new VBox(2, title, meta);
            row = new HBox(6, statusDot, box);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(BitbucketPullRequest item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            title.setText("#" + item.id() + " " + item.title());
            String author = item.author() == null ? "unknown" : item.author().handle();
            String source = item.isFromFork()
                    ? item.source().repository().fullName() + ":" + item.source().branch().name()
                    : item.source().branch().name();
            meta.setText(author + " wants to merge " + source + " into " + item.destination().branch().name());
            PrStatusIcon.style(statusDot, statusOf.apply(item));
            setGraphic(row);
        }
    }
}
