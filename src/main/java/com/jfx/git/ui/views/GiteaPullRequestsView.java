package com.jfx.git.ui.views;

import com.jfx.git.core.BuildStatus;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.ListPage;
import com.jfx.git.core.gitea.GiteaClient;
import com.jfx.git.core.gitea.GiteaPullRequest;
import com.jfx.git.core.gitea.GiteaRepoRef;
import com.jfx.git.core.gitea.GiteaResult;
import com.jfx.git.core.gitea.GiteaReview;
import com.jfx.git.core.gitea.GiteaReviewComment;
import com.jfx.git.core.gitea.GiteaSession;
import com.jfx.git.core.hosting.HostingProvider;
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
import java.util.function.Function;

/**
 * Open pull requests for a repository whose {@code origin} is on the configured Gitea or
 * Forgejo server (Codeberg unless another is set), with checkout, merge, review comments
 * and a new pull request.
 *
 * <h2>Why a copy of the GitHub view</h2>
 * Gitea's API was modelled on GitHub's and {@link GiteaClient} keeps GitHub's method
 * shapes, so this is {@link GitHubPullRequestsView} on the same shared pieces -
 * {@link ProviderListing}, {@link PullRequestCheckout}, {@link PrReviewView} and
 * {@link CreatePullRequestDialog}. A provider-neutral view was considered and left out
 * of scope: the places the two differ are exactly the ones below, and each is small.
 * <ul>
 *   <li><b>Checkout</b>: Gitea publishes every pull request's head as
 *       {@code refs/pull/{n}/head}, as GitHub does, so
 *       {@link GitBackend#checkoutPullRequest} works unchanged - forks included.</li>
 *   <li><b>CI</b>: one combined commit status per head ({@link GiteaClient#getCommitStatus})
 *       rather than GitHub's check runs.</li>
 *   <li><b>Merge</b>: Gitea's five styles, and the head the user saw goes with the
 *       request so a push since then is refused rather than merged unseen.</li>
 *   <li><b>Review comments</b>: Gitea has no stand-alone line comment; each is posted as
 *       a one-comment review, which {@link GiteaClient#createReviewComment} hides.</li>
 * </ul>
 *
 * <p>Shown inside the repository's tab rather than in a window of its own; see
 * {@link ProviderView}.
 */
public class GiteaPullRequestsView extends ProviderView {

    private static final String PROVIDER = HostingProvider.GITEA.displayName();

    /**
     * Gitea's {@code Do} values ({@link GiteaClient#MERGE_STYLES}) in its own order, named
     * for what each leaves in the history. A repository can switch any of them off; the
     * server's refusal then says so.
     */
    static final List<MergeStrategyDialog.Choice> MERGE_STRATEGIES = List.of(
            new MergeStrategyDialog.Choice("Create a merge commit", "merge"),
            new MergeStrategyDialog.Choice("Rebase and fast-forward", "rebase"),
            new MergeStrategyDialog.Choice("Rebase and create a merge commit", "rebase-merge"),
            new MergeStrategyDialog.Choice("Squash and merge", "squash"),
            new MergeStrategyDialog.Choice("Fast-forward only", "fast-forward-only"));

    private final GitBackend gitService;
    private final StatusBar statusBar;
    /** Runs checkouts and review preparation; a merge made on the server refreshes through it too. */
    private final OperationRunner operations;
    /** Where a review opens: the repository tab, on top of this list. */
    private final Consumer<RepoView> showView;
    private final GiteaClient gitea;

    private final ListView<GiteaPullRequest> entries = new ListView<>();
    private final Button checkout = new Button("Checkout");
    private final Button merge = new Button("Merge…");
    private final Button review = new Button("Review…");
    private final Button newPullRequest = new Button("New pull request…");
    private final TextField filter = new TextField();
    private final ProviderListing<GiteaPullRequest> listing;
    /** The screen: heading, filter, list and actions, built once. */
    private final VBox content;
    /** The origin's {@code owner/repo}, once read; null until then or when it is not on the Gitea server. */
    private GiteaRepoRef ref;
    /** A request to open filled in, waiting for the list to know its repository; see {@link #prefill}. */
    private CreatePullRequestDialog.Draft pendingDraft;

    /**
     * @param originUrl the origin remote's URL as the caller already read it off the FX
     *                  thread, or null to have the view read it on the repository lane
     * @param showView  shows a review in the repository's tab, over this list
     */
    public GiteaPullRequestsView(OperationRunner operations, String originUrl, Consumer<RepoView> showView) {
        this(operations, originUrl, new GiteaClient(), showView);
    }

    /** Package-visible seam: a test points this at a local fake server instead of the real API. */
    GiteaPullRequestsView(OperationRunner operations, String originUrl, GiteaClient gitea) {
        this(operations, originUrl, gitea, view -> { });
    }

    GiteaPullRequestsView(OperationRunner operations, String originUrl, GiteaClient gitea, Consumer<RepoView> showView) {
        super("Pull requests");
        this.operations = operations;
        this.gitService = operations.repository();
        this.statusBar = operations.statusBar();
        this.showView = showView;
        this.gitea = gitea;

        filter.setPromptText("Filter by title or author…");
        filter.getStyleClass().add("sidebar-filter");
        listing = new ProviderListing<>(entries, filter, "pull requests", PROVIDER,
                GiteaPullRequest::number, GiteaPullRequestsView::matches);
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

        content = JfxDialog.browser(
                "Open pull requests for this repository's origin remote on " + PROVIDER + ".",
                filter, listing.area(), newPullRequest, review, merge, checkout);

        load();
    }

    /**
     * Waits for a Gitea connection, reads the origin, then shows the newest page - see
     * {@link ProviderListing} for what each step does while it waits.
     */
    private void load() {
        newPullRequest.setDisable(true);
        listing.whenConnected(GiteaSession::isConnected, () -> listing.withOrigin(gitService, url -> {
            var found = GiteaRepoRef.fromRemoteUrl(url);
            if (found.isEmpty()) {
                listing.placeholder("This repository's origin remote is not on the " + PROVIDER
                        + " server set up in Integrations.");
                return;
            }
            GiteaRepoRef repo = found.get();
            ref = repo;
            newPullRequest.setDisable(false);
            if (pendingDraft != null) newPullRequestSelected();
            // Gitea's pull request list carries no status, so each shown row asks for its
            // head's combined one.
            listing.statuses(pr -> () -> {
                if (pr.head() == null || pr.head().sha() == null) return null;
                var status = gitea.getCommitStatus(GiteaSession.token(), repo.owner(), repo.repo(), pr.head().sha());
                return status.isOk() ? status.orNull() : null;
            });
            listing.load(page -> {
                var result = gitea.listPullRequestsPage(GiteaSession.token(), repo.owner(), repo.repo(),
                        page, ListPage.SIZE);
                return result.isOk()
                        ? ProviderListing.Fetched.ok(result.orNull())
                        : ProviderListing.Fetched.failed(result.messageOrNull());
            });
        }));
    }

    /** Matches on title or author, substring, case-insensitive - as the other providers' lists do. */
    private static boolean matches(GiteaPullRequest pr, String needle) {
        if (pr.title() != null && pr.title().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        String author = pr.user() == null ? null : pr.user().login();
        return author != null && author.toLowerCase(Locale.ROOT).contains(needle);
    }

    private void checkoutSelected() {
        GiteaPullRequest pr = entries.getSelectionModel().getSelectedItem();
        if (pr == null) {
            return;
        }
        checkout.setDisable(true);
        PullRequestCheckout.start(operations, "pr-" + pr.number(), "pull request #" + pr.number(),
                reset -> gitService.checkoutPullRequest("origin", pr.number(), reset),
                result -> checkout.setDisable(entries.getSelectionModel().getSelectedItem() == null));
    }

    /**
     * Merges the selected pull request on the server - nothing in the local repository
     * changes until the next fetch or pull. Re-loads the list afterward so a merged pull
     * request drops off it.
     */
    private void mergeSelected() {
        GiteaPullRequest pr = entries.getSelectionModel().getSelectedItem();
        if (pr == null) {
            return;
        }
        new MergeStrategyDialog("#" + pr.number() + " " + pr.title(), MERGE_STRATEGIES)
                .showAndWait()
                .ifPresent(strategy -> doMerge(pr, strategy));
    }

    /**
     * Sends the head this row showed along with the merge. The list can be minutes old,
     * and Gitea merges whatever the branch holds when the request arrives; with the head
     * attached, a push in between is a 409 the user reads ("changed while it was being
     * merged") rather than commits merged that nobody here looked at.
     */
    private void doMerge(GiteaPullRequest pr, String strategy) {
        merge.setDisable(true);
        checkout.setDisable(true);
        statusBar.busy("Merging pull request #" + pr.number());
        GiteaRepoRef repo = refOrRead();
        String seenHead = pr.head() == null ? null : pr.head().sha();
        GitTaskRunner.queryProvider(
                () -> gitea.mergePullRequest(GiteaSession.token(), repo.owner(), repo.repo(),
                        pr.number(), strategy, false, null, null, seenHead),
                result -> {
                    statusBar.idle();
                    if (result.isOk()) {
                        statusBar.info(result.orNull());
                        listing.reload();
                        operations.refresh(OperationRunner.Refresh.EVERYTHING);
                    } else {
                        mergeFailed(result.messageOrNull());
                    }
                },
                error -> {
                    statusBar.idle();
                    mergeFailed(error);
                });
    }

    private void mergeFailed(String message) {
        statusBar.error("Could not merge pull request: " + message);
        checkout.setDisable(entries.getSelectionModel().getSelectedItem() == null);
        merge.setDisable(entries.getSelectionModel().getSelectedItem() == null);
    }

    /**
     * Opens {@link PrReviewView} on the selected pull request's diff, computed locally
     * between its fork point and head the way {@link GitHubPullRequestsView} does; the
     * server is asked only for the review comments. Fetching and diffing is slow, so it
     * runs in the background and the review opens once it is ready.
     */
    private void reviewSelected() {
        GiteaPullRequest pr = entries.getSelectionModel().getSelectedItem();
        if (pr == null || ref == null) {
            return;
        }
        GiteaRepoRef repo = ref;
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
     * Fetches the pull request's head without checking it out, brings the remote-tracking
     * branches up to date so its base is present, then diffs each changed file from the
     * merge-base - what Gitea's own "Files changed" tab shows. See
     * {@code GitHubPullRequestsView.prepareReviewFiles} for why each step is there.
     */
    private PrReviewView.Outcome<List<PrReviewView.ReviewFile>> prepareReviewFiles(GiteaPullRequest pr) {
        var fetch = gitService.fetchRemoteRef("origin", "refs/pull/" + pr.number() + "/head");
        if (!fetch.isOk()) {
            return PrReviewView.Outcome.failed(fetch.getMessage());
        }
        gitService.fetchAll();
        String head = pr.head().sha();
        String base = pr.base() == null ? null : pr.base().sha();
        String forkPoint = base == null ? null : gitService.mergeBase(base, head);
        String from = forkPoint != null ? forkPoint : base;
        if (from == null) {
            return PrReviewView.Outcome.failed("The server did not say which commit pull request #"
                    + pr.number() + " is based on.");
        }
        List<String> paths = gitService.getFilesBetweenCommits(from, head);
        List<PrReviewView.ReviewFile> files = new ArrayList<>();
        for (String path : paths) {
            byte[] diff = gitService.getDiffBytesBetweenCommits(from, head, path, false);
            files.add(new PrReviewView.ReviewFile(path, diff));
        }
        return PrReviewView.Outcome.ok(files);
    }

    /** The one seam {@link PrReviewView} needs adapted per provider - see its own javadoc. */
    private PrReviewView.Backend buildReviewBackend(GiteaPullRequest pr, GiteaRepoRef ref) {
        return new PrReviewView.Backend() {
            @Override
            public void fetchComments(Consumer<PrReviewView.Outcome<List<ReviewComment>>> onDone) {
                GitTaskRunner.queryProvider(
                        () -> gitea.listReviewComments(GiteaSession.token(), ref.owner(), ref.repo(), pr.number()),
                        result -> onDone.accept(result.isOk()
                                ? PrReviewView.Outcome.ok(result.orNull().stream()
                                        .map(GiteaPullRequestsView::toReviewComment).toList())
                                : PrReviewView.Outcome.failed(result.messageOrNull())),
                        error -> onDone.accept(PrReviewView.Outcome.failed(error)));
            }

            @Override
            public void postComment(String path, int line, ReviewComment.Side side, String body,
                                    Consumer<PrReviewView.Outcome<Void>> onDone) {
                String giteaSide = side == ReviewComment.Side.NEW ? "RIGHT" : "LEFT";
                GitTaskRunner.queryProvider(
                        () -> postAnchoredToTheReviewedCommit(path, line, giteaSide, body),
                        result -> onDone.accept(result.isOk()
                                ? PrReviewView.Outcome.ok(null)
                                : PrReviewView.Outcome.failed(result.messageOrNull())),
                        error -> onDone.accept(PrReviewView.Outcome.failed(error)));
            }

            /**
             * Gitea renders a {@code suggestion} block as plain code, with nothing to
             * apply, so the compose dialog does not offer to write one.
             */
            @Override
            public boolean supportsSuggestions() {
                return false;
            }

            /**
             * Posts against the commit the reviewer was reading, for the reason
             * {@code GitHubPullRequestsView} gives at length: the line number belongs to
             * that commit's diff, and re-anchoring it to a newer head could silently land
             * it on different code. The head is re-read only after a refusal, to say the
             * pull request has moved.
             */
            private GiteaResult<GiteaReview> postAnchoredToTheReviewedCommit(
                    String path, int line, String giteaSide, String body) {
                String reviewed = pr.head().sha();
                GiteaResult<GiteaReview> posted = gitea.createReviewComment(
                        GiteaSession.token(), ref.owner(), ref.repo(), pr.number(),
                        reviewed, path, line, giteaSide, body);
                if (posted.isOk()) {
                    return posted;
                }
                GiteaResult<GiteaPullRequest> fresh = gitea.fetchPullRequest(
                        GiteaSession.token(), ref.owner(), ref.repo(), pr.number());
                boolean moved = fresh.isOk() && fresh.orNull() != null
                        && fresh.orNull().head() != null
                        && !reviewed.equals(fresh.orNull().head().sha());
                if (!moved) {
                    return posted;
                }
                return GiteaResult.failed(posted.messageOrNull()
                        + " - the pull request has new commits since this review was"
                        + " opened, and this line is no longer in its diff. Reopen the"
                        + " review to comment on the current version.");
            }
        };
    }

    /** A Gitea review comment, normalized to the shape {@link PrReviewView} renders. */
    private static ReviewComment toReviewComment(GiteaReviewComment comment) {
        ReviewComment.Side side = "LEFT".equals(comment.side()) ? ReviewComment.Side.OLD : ReviewComment.Side.NEW;
        String author = comment.user() == null ? "unknown" : comment.user().login();
        return new ReviewComment(comment.path(), comment.line(), side, author, comment.body());
    }

    /**
     * Opens {@link CreatePullRequestDialog} with the checked-out branch as the source,
     * then creates the pull request on the server and reloads the list.
     */
    private void newPullRequestSelected() {
        if (ref == null) {
            return;
        }
        GiteaRepoRef repo = ref;
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

    private void doCreate(GiteaRepoRef ref, CreatePullRequestDialog.Draft draft) {
        statusBar.busy("Creating pull request");
        GitTaskRunner.queryProvider(
                () -> gitea.createPullRequest(GiteaSession.token(), ref.owner(), ref.repo(),
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
    private GiteaRepoRef refOrRead() {
        return ref != null ? ref : GiteaRepoRef.fromRemoteUrl(gitService.getRemoteUrl("origin")).orElse(null);
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
    ProviderListing<GiteaPullRequest> listingForTest() {
        return listing;
    }

    /** Package-visible: the rows the list shows, filter applied. */
    List<GiteaPullRequest> loadedEntries() {
        return List.copyOf(entries.getItems());
    }

    /** Package-visible: types into the filter field the same way a user would. */
    void filterForTest(String query) {
        filter.setText(query);
    }

    /** Package-visible: selects a row and fires checkout, bypassing the list's own click handling. */
    void checkoutForTest(GiteaPullRequest pr) {
        entries.getSelectionModel().select(pr);
        checkoutSelected();
    }

    /** Package-visible: whether the Checkout button is currently enabled. */
    boolean isCheckoutEnabled() {
        return !checkout.isDisabled();
    }

    /** Package-visible: selects a row and merges it with {@code strategy}, bypassing the strategy popup. */
    void mergeForTest(GiteaPullRequest pr, String strategy) {
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

    /** Package-visible: the real fetch-and-diff preparation, without opening {@link PrReviewView}. */
    PrReviewView.Outcome<List<PrReviewView.ReviewFile>> reviewFilesForTest(GiteaPullRequest pr) {
        return prepareReviewFiles(pr);
    }

    /** Package-visible: the {@link PrReviewView.Backend} this pull request would be reviewed with. */
    PrReviewView.Backend reviewBackendForTest(GiteaPullRequest pr) {
        return buildReviewBackend(pr, refOrRead());
    }

    /** Package-visible: creates a pull request from a filled-in draft, bypassing the compose popup. */
    void createForTest(CreatePullRequestDialog.Draft draft) {
        GiteaRepoRef repo = refOrRead();
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

    /** Number, title, author, draft flag, source and target branches, and CI status. */
    private static final class EntryCell extends ListCell<GiteaPullRequest> {

        private final Function<GiteaPullRequest, BuildStatus> statusOf;
        private final Label statusDot = new Label("●");
        private final Label title = new Label();
        private final Label meta = new Label();
        private final HBox row;

        EntryCell(Function<GiteaPullRequest, BuildStatus> statusOf) {
            this.statusOf = statusOf;
            statusDot.getStyleClass().add("pr-status");
            title.getStyleClass().add("reflog-comment");
            meta.getStyleClass().add("reflog-ids");
            VBox box = new VBox(2, title, meta);
            row = new HBox(6, statusDot, box);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(GiteaPullRequest item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            title.setText("#" + item.number() + (item.draft() ? " (draft) " : " ") + item.title());
            String author = item.user() == null ? "unknown" : item.user().login();
            String from = item.head() == null ? "?" : item.head().ref();
            String into = item.base() == null ? "?" : item.base().ref();
            meta.setText(author + " wants to merge " + from + " into " + into);
            PrStatusIcon.style(statusDot, statusOf.apply(item));
            setGraphic(row);
        }
    }
}
