package com.jfx.git.ui.views;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.ListPage;
import com.jfx.git.core.UrlOpener;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubIssue;
import com.jfx.git.core.github.GitHubRepoRef;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.dialogs.JfxDialog;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.Locale;

/**
 * Open issues for the current repository's {@code origin} remote (Wave 4, §4.3
 * Integrations Phase C of the parity plan, completing GitHub's own arc after Phase A's
 * repo browsing and Phase B's PR list/checkout). In-app creation, commenting, and
 * closing remain out of scope for this first pass - "Open in browser" is the only
 * action offered, the same conservative choice Phase A and B each made for their own
 * next-biggest piece of scope.
 *
 * <p>Requires both a connected {@link GitHubSession} and an {@code origin} remote that
 * actually resolves to a GitHub {@code owner/repo} ({@link GitHubRepoRef}); either
 * missing is reported in the list's placeholder rather than treated as an error, the
 * same way {@link GitHubPullRequestsView} (which this is modelled on directly) and
 * {@link ReflogView} both do.
 *
 * <p>Shown inside the repository's tab rather than in a window of its own; see
 * {@link ProviderView}.
 */
public class GitHubIssuesView extends ProviderView {

    private final GitBackend gitService;
    private final StatusBar statusBar;
    private final GitHubClient gitHub;

    private final ListView<GitHubIssue> entries = new ListView<>();
    private final Button openInBrowser = new Button("Open in browser");
    private final TextField filter = new TextField();
    private final ProviderListing<GitHubIssue> listing;
    /** The screen: heading, filter, list and actions, built once. */
    private final VBox content;

    /**
     * @param originUrl the origin remote's URL as the caller already read it off the FX
     *                  thread, or null to have the view read it on the repository lane
     */
    public GitHubIssuesView(GitBackend gitService, String originUrl, StatusBar statusBar) {
        this(gitService, originUrl, statusBar, new GitHubClient());
    }

    /** Package-visible seam: a test points this at a local fake server instead of the real API. */
    GitHubIssuesView(GitBackend gitService, StatusBar statusBar, GitHubClient gitHub) {
        this(gitService, null, statusBar, gitHub);
    }

    GitHubIssuesView(GitBackend gitService, String originUrl, StatusBar statusBar, GitHubClient gitHub) {
        super("Issues");
        this.gitService = gitService;
        this.statusBar = statusBar;
        this.gitHub = gitHub;

        filter.setPromptText("Filter by title, author or label…");
        filter.getStyleClass().add("sidebar-filter");
        listing = new ProviderListing<>(entries, filter, "issues", "GitHub",
                GitHubIssue::number, GitHubIssuesView::matches);
        listing.knownOrigin(originUrl);
        entries.setCellFactory(view -> new EntryCell());

        openInBrowser.getStyleClass().add("btn-primary");
        openInBrowser.setDisable(true);
        openInBrowser.setOnAction(e -> openSelected());
        entries.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, item) -> openInBrowser.setDisable(item == null));

        // The heading, the list and the action row, assembled the way every other
        // provider browser assembles them rather than with this view's own spacing.
        content = JfxDialog.browser(
                "Open issues for this repository's origin remote.",
                filter, listing.area(), openInBrowser);

        load();
    }

    /** Waits for a GitHub connection, reads the origin, then shows the newest page; see {@link ProviderListing}. */
    private void load() {
        listing.whenConnected(GitHubSession::isConnected, () -> listing.withOrigin(gitService, url -> {
            var found = GitHubRepoRef.fromRemoteUrl(url);
            if (found.isEmpty()) {
                listing.placeholder("This repository's origin remote is not a GitHub repository.");
                return;
            }
            GitHubRepoRef repo = found.get();
            listing.load(page -> {
                var result = gitHub.listIssuesPage(GitHubSession.token(), repo.owner(), repo.repo(),
                        page, ListPage.SIZE);
                return result.isOk()
                        ? ProviderListing.Fetched.ok(result.orNull())
                        : ProviderListing.Fetched.failed(result.messageOrNull());
            });
        }));
    }

    /** Title, author or any label, substring, case-insensitive. */
    private static boolean matches(GitHubIssue issue, String needle) {
        if (issue.title() != null && issue.title().toLowerCase(Locale.ROOT).contains(needle)) return true;
        if (issue.user() != null && issue.user().login() != null
                && issue.user().login().toLowerCase(Locale.ROOT).contains(needle)) return true;
        return issue.labels() != null && issue.labels().stream()
                .anyMatch(label -> label.name() != null && label.name().toLowerCase(Locale.ROOT).contains(needle));
    }

    private void openSelected() {
        GitHubIssue issue = entries.getSelectionModel().getSelectedItem();
        if (issue == null) {
            return;
        }
        // The detached lane: opening a browser touches no repository, and on the
        // repository lane it queues behind whatever fetch or rebase is running -
        // a click that does nothing visible until unrelated git work finishes.
        GitTaskRunner.runDetached(() -> UrlOpener.open(issue.htmlUrl()), statusBar::report);
    }

    @Override
    public VBox content() {
        return content;
    }

    @Override
    ProviderListing<?> listing() {
        return listing;
    }

    /** Package-visible: the listing, for tests of paging and closing. */
    ProviderListing<GitHubIssue> listingForTest() {
        return listing;
    }

    /** Kept package-visible for tests that need the loaded rows. */
    java.util.List<GitHubIssue> loadedEntries() {
        return java.util.List.copyOf(entries.getItems());
    }

    /**
     * Package-visible: a test selects a row and fires the open action directly,
     * bypassing the {@link ListView}'s own selection/click mechanics - the same
     * reasoning {@link GitHubPullRequestsView#checkoutForTest} is package-visible for.
     */
    void openForTest(GitHubIssue issue) {
        entries.getSelectionModel().select(issue);
        openSelected();
    }

    /** Package-visible: selects a row without firing the open action - for button-gating tests. */
    void selectForTest(GitHubIssue issue) {
        entries.getSelectionModel().select(issue);
    }

    /** Package-visible: whether the Open in browser button is currently enabled. */
    boolean isOpenInBrowserEnabled() {
        return !openInBrowser.isDisabled();
    }

    /** Number, title, author, and labels. */
    private static final class EntryCell extends ListCell<GitHubIssue> {

        private final Label title = new Label();
        private final Label meta = new Label();
        private final VBox box;

        EntryCell() {
            title.getStyleClass().add("reflog-comment");
            meta.getStyleClass().add("reflog-ids");
            box = new VBox(2, title, meta);
        }

        @Override
        protected void updateItem(GitHubIssue item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            title.setText("#" + item.number() + " " + item.title());
            String author = item.user() == null ? "unknown" : item.user().login();
            String labels = item.labels() == null || item.labels().isEmpty()
                    ? ""
                    : " · " + item.labels().stream().map(GitHubIssue.Label::name)
                            .reduce((a, b) -> a + ", " + b).orElse("");
            meta.setText("opened by " + author + labels);
            setGraphic(box);
        }
    }
}
