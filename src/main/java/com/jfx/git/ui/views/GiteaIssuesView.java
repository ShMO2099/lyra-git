package com.jfx.git.ui.views;

import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.ListPage;
import com.jfx.git.core.UrlOpener;
import com.jfx.git.core.gitea.GiteaClient;
import com.jfx.git.core.gitea.GiteaIssue;
import com.jfx.git.core.gitea.GiteaRepoRef;
import com.jfx.git.core.gitea.GiteaSession;
import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.ui.StatusBar;
import com.jfx.git.ui.dialogs.JfxDialog;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;

/**
 * Open issues for a repository whose {@code origin} is on the configured Gitea or Forgejo
 * server - read-only, with "Open in browser" as the one action, like
 * {@link GitHubIssuesView}, of which this is an adapted copy.
 *
 * <p>Gitea's issue list can leave pull requests out itself ({@code type=issues}), so each
 * page is a full page of issues; {@link GiteaClient#listIssuesPage} also drops any pull
 * request a server sends anyway.
 *
 * <p>Shown inside the repository's tab rather than in a window of its own; see
 * {@link ProviderView}.
 */
public class GiteaIssuesView extends ProviderView {

    private static final String PROVIDER = HostingProvider.GITEA.displayName();

    private final GitBackend gitService;
    private final StatusBar statusBar;
    private final GiteaClient gitea;

    private final ListView<GiteaIssue> entries = new ListView<>();
    private final Button openInBrowser = new Button("Open in browser");
    private final TextField filter = new TextField();
    private final ProviderListing<GiteaIssue> listing;
    /** The screen: heading, filter, list and actions, built once. */
    private final VBox content;

    /**
     * @param originUrl the origin remote's URL as the caller already read it off the FX
     *                  thread, or null to have the view read it on the repository lane
     */
    public GiteaIssuesView(GitBackend gitService, String originUrl, StatusBar statusBar) {
        this(gitService, originUrl, statusBar, new GiteaClient());
    }

    /** Package-visible seam: a test points this at a local fake server instead of the real API. */
    GiteaIssuesView(GitBackend gitService, String originUrl, StatusBar statusBar, GiteaClient gitea) {
        super("Issues");
        this.gitService = gitService;
        this.statusBar = statusBar;
        this.gitea = gitea;

        filter.setPromptText("Filter by title, author or label…");
        filter.getStyleClass().add("sidebar-filter");
        listing = new ProviderListing<>(entries, filter, "issues", PROVIDER,
                GiteaIssue::number, GiteaIssuesView::matches);
        listing.knownOrigin(originUrl);
        entries.setCellFactory(view -> new EntryCell());

        openInBrowser.getStyleClass().add("btn-primary");
        openInBrowser.setDisable(true);
        openInBrowser.setOnAction(e -> openSelected());
        entries.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, item) -> openInBrowser.setDisable(item == null));

        content = JfxDialog.browser(
                "Open issues for this repository's origin remote on " + PROVIDER + ".",
                filter, listing.area(), openInBrowser);

        load();
    }

    /** Waits for a Gitea connection, reads the origin, then shows the newest page; see {@link ProviderListing}. */
    private void load() {
        listing.whenConnected(GiteaSession::isConnected, () -> listing.withOrigin(gitService, url -> {
            var found = GiteaRepoRef.fromRemoteUrl(url);
            if (found.isEmpty()) {
                listing.placeholder("This repository's origin remote is not on the " + PROVIDER
                        + " server set up in Integrations.");
                return;
            }
            GiteaRepoRef repo = found.get();
            listing.load(page -> {
                var result = gitea.listIssuesPage(GiteaSession.token(), repo.owner(), repo.repo(),
                        page, ListPage.SIZE);
                return result.isOk()
                        ? ProviderListing.Fetched.ok(result.orNull())
                        : ProviderListing.Fetched.failed(result.messageOrNull());
            });
        }));
    }

    /** Title, author or any label, substring, case-insensitive. */
    private static boolean matches(GiteaIssue issue, String needle) {
        if (issue.title() != null && issue.title().toLowerCase(Locale.ROOT).contains(needle)) return true;
        if (issue.user() != null && issue.user().login() != null
                && issue.user().login().toLowerCase(Locale.ROOT).contains(needle)) return true;
        return issue.labels() != null && issue.labels().stream()
                .anyMatch(label -> label.name() != null && label.name().toLowerCase(Locale.ROOT).contains(needle));
    }

    private void openSelected() {
        GiteaIssue issue = entries.getSelectionModel().getSelectedItem();
        if (issue == null || issue.htmlUrl() == null) {
            return;
        }
        // The detached lane: opening a browser touches no repository, so it must not
        // queue behind a fetch on the repository lane.
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
    ProviderListing<GiteaIssue> listingForTest() {
        return listing;
    }

    /** Package-visible: the rows the list shows, filter applied. */
    List<GiteaIssue> loadedEntries() {
        return List.copyOf(entries.getItems());
    }

    /** Package-visible: types into the filter field the same way a user would. */
    void filterForTest(String query) {
        filter.setText(query);
    }

    /** Package-visible: selects a row without firing the open action - for button-gating tests. */
    void selectForTest(GiteaIssue issue) {
        entries.getSelectionModel().select(issue);
    }

    /** Package-visible: whether the Open in browser button is currently enabled. */
    boolean isOpenInBrowserEnabled() {
        return !openInBrowser.isDisabled();
    }

    /** Number, title, author, and labels. */
    private static final class EntryCell extends ListCell<GiteaIssue> {

        private final Label title = new Label();
        private final Label meta = new Label();
        private final VBox box;

        EntryCell() {
            title.getStyleClass().add("reflog-comment");
            meta.getStyleClass().add("reflog-ids");
            box = new VBox(2, title, meta);
        }

        @Override
        protected void updateItem(GiteaIssue item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            title.setText("#" + item.number() + " " + item.title());
            String author = item.user() == null ? "unknown" : item.user().login();
            String labels = item.labels() == null || item.labels().isEmpty()
                    ? ""
                    : " · " + item.labels().stream().map(GiteaIssue.Label::name)
                            .reduce((a, b) -> a + ", " + b).orElse("");
            meta.setText("opened by " + author + labels);
            setGraphic(box);
        }
    }
}
