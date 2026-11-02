package com.jfx.git.ui.views;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.ListPage;
import com.jfx.git.core.UrlOpener;
import com.jfx.git.core.gitlab.GitLabClient;
import com.jfx.git.core.gitlab.GitLabIssue;
import com.jfx.git.core.gitlab.GitLabProjectRef;
import com.jfx.git.core.gitlab.GitLabSession;
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
 * Open issues for the current repository's {@code origin} remote - {@code
 * GitHubIssuesView} copied almost line for line, the GitLab half of the same
 * parity-plan item (Wave 4, §4.3 Integrations Phase C). "Open in browser" is the only
 * action offered, the same conservative scope GitHub's own issue view chose.
 *
 * <p>Requires both a connected {@link GitLabSession} and an {@code origin} remote that
 * actually resolves to a GitLab project ({@link GitLabProjectRef}); either missing is
 * reported in the list's placeholder rather than treated as an error.
 *
 * <p>Shown inside the repository's tab rather than in a window of its own; see
 * {@link ProviderView}.
 */
public class GitLabIssuesView extends ProviderView {

    private final GitBackend gitService;
    private final StatusBar statusBar;
    private final GitLabClient gitLab;

    private final ListView<GitLabIssue> entries = new ListView<>();
    private final Button openInBrowser = new Button("Open in browser");
    private final TextField filter = new TextField();
    private final ProviderListing<GitLabIssue> listing;
    /** The screen: heading, filter, list and actions, built once. */
    private final VBox content;

    /**
     * @param originUrl the origin remote's URL as the caller already read it off the FX
     *                  thread, or null to have the view read it on the repository lane
     */
    public GitLabIssuesView(GitBackend gitService, String originUrl, StatusBar statusBar) {
        this(gitService, originUrl, statusBar, new GitLabClient());
    }

    /** Package-visible seam: a test points this at a local fake server instead of the real API. */
    GitLabIssuesView(GitBackend gitService, StatusBar statusBar, GitLabClient gitLab) {
        this(gitService, null, statusBar, gitLab);
    }

    GitLabIssuesView(GitBackend gitService, String originUrl, StatusBar statusBar, GitLabClient gitLab) {
        super("Issues");
        this.gitService = gitService;
        this.statusBar = statusBar;
        this.gitLab = gitLab;

        filter.setPromptText("Filter by title, author or label…");
        filter.getStyleClass().add("sidebar-filter");
        listing = new ProviderListing<>(entries, filter, "issues", "GitLab",
                GitLabIssue::iid, GitLabIssuesView::matches);
        listing.knownOrigin(originUrl);
        entries.setCellFactory(view -> new EntryCell());

        openInBrowser.getStyleClass().add("btn-primary");
        openInBrowser.setDisable(true);
        openInBrowser.setOnAction(e -> openSelected());
        entries.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, item) -> openInBrowser.setDisable(item == null));

        // Identical to the GitHub view's, which is the point: the two were the same
        // twenty lines written twice, and the copies had already begun to differ in
        // which style class carried the heading.
        content = JfxDialog.browser(
                "Open issues for this repository's origin remote.",
                filter, listing.area(), openInBrowser);

        load();
    }

    /** Waits for a GitLab connection, reads the origin, then shows the newest page; see {@link ProviderListing}. */
    private void load() {
        listing.whenConnected(GitLabSession::isConnected, () -> listing.withOrigin(gitService, url -> {
            var found = GitLabProjectRef.fromRemoteUrl(url);
            if (found.isEmpty()) {
                listing.placeholder("This repository's origin remote is not a GitLab project.");
                return;
            }
            GitLabProjectRef project = found.get();
            listing.load(page -> {
                var result = gitLab.listIssuesPage(GitLabSession.token(), project.encodedPath(),
                        page, ListPage.SIZE);
                return result.isOk()
                        ? ProviderListing.Fetched.ok(result.orNull())
                        : ProviderListing.Fetched.failed(result.messageOrNull());
            });
        }));
    }

    /** Title, author or any label, substring, case-insensitive. */
    private static boolean matches(GitLabIssue issue, String needle) {
        if (issue.title() != null && issue.title().toLowerCase(Locale.ROOT).contains(needle)) return true;
        if (issue.author() != null && issue.author().username() != null
                && issue.author().username().toLowerCase(Locale.ROOT).contains(needle)) return true;
        return issue.labels() != null && issue.labels().stream()
                .anyMatch(label -> label != null && label.toLowerCase(Locale.ROOT).contains(needle));
    }

    private void openSelected() {
        GitLabIssue issue = entries.getSelectionModel().getSelectedItem();
        if (issue == null) {
            return;
        }
        // The detached lane: opening a browser touches no repository, and on the
        // repository lane it queues behind whatever fetch or rebase is running -
        // a click that does nothing visible until unrelated git work finishes.
        GitTaskRunner.runDetached(() -> UrlOpener.open(issue.webUrl()), statusBar::report);
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
    ProviderListing<GitLabIssue> listingForTest() {
        return listing;
    }

    /** Kept package-visible for tests that need the loaded rows. */
    java.util.List<GitLabIssue> loadedEntries() {
        return java.util.List.copyOf(entries.getItems());
    }

    /**
     * Package-visible: a test selects a row and fires the open action directly,
     * bypassing the {@link ListView}'s own selection/click mechanics.
     */
    void openForTest(GitLabIssue issue) {
        entries.getSelectionModel().select(issue);
        openSelected();
    }

    /** Package-visible: selects a row without firing the open action - for button-gating tests. */
    void selectForTest(GitLabIssue issue) {
        entries.getSelectionModel().select(issue);
    }

    /** Package-visible: whether the Open in browser button is currently enabled. */
    boolean isOpenInBrowserEnabled() {
        return !openInBrowser.isDisabled();
    }

    /** Number, title, author, and labels. */
    private static final class EntryCell extends ListCell<GitLabIssue> {

        private final Label title = new Label();
        private final Label meta = new Label();
        private final VBox box;

        EntryCell() {
            title.getStyleClass().add("reflog-comment");
            meta.getStyleClass().add("reflog-ids");
            box = new VBox(2, title, meta);
        }

        @Override
        protected void updateItem(GitLabIssue item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            title.setText("#" + item.iid() + " " + item.title());
            String author = item.author() == null ? "unknown" : item.author().username();
            String labels = item.labels() == null || item.labels().isEmpty()
                    ? ""
                    : " · " + String.join(", ", item.labels());
            meta.setText("opened by " + author + labels);
            setGraphic(box);
        }
    }
}
