package com.jfx.git.ui.dialogs;

import com.jfx.git.LyraApp;
import com.jfx.git.core.AppPreferences;
import com.jfx.git.core.CommitChecks;
import com.jfx.git.core.CrashReporter;
import com.jfx.git.core.EngineDoctor;
import com.jfx.git.core.PushLease;
import com.jfx.git.core.RebaseTodo;
import com.jfx.git.core.WorkspaceFiles;
import com.jfx.git.core.bitbucket.BitbucketClient;
import com.jfx.git.core.bitbucket.BitbucketSession;
import com.jfx.git.core.bitbucket.BitbucketUser;
import com.jfx.git.core.gitea.GiteaClient;
import com.jfx.git.core.gitea.GiteaSession;
import com.jfx.git.core.gitea.GiteaUser;
import com.jfx.git.core.github.GitHubClient;
import com.jfx.git.core.github.GitHubSession;
import com.jfx.git.core.github.GitHubUser;
import com.jfx.git.core.gitlab.GitLabClient;
import com.jfx.git.core.gitlab.GitLabSession;
import com.jfx.git.core.gitlab.GitLabUser;
import com.jfx.git.core.hosting.FakeOAuthServer;
import com.jfx.git.core.hosting.HostingProvider;
import com.jfx.git.core.hosting.OAuthDeviceFlow;
import com.jfx.git.core.hosting.OAuthLoopbackFlow;
import com.jfx.git.core.hosting.OAuthSpec;
import com.jfx.git.core.hosting.OAuthTokenClient;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.StashEntry;
import com.jfx.git.testsupport.MainWindowFixture;
import com.jfx.git.ui.CommandPalette;
import com.jfx.git.ui.DialogOwner;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RepoTab;
import com.jfx.git.ui.RobotWait;
import com.jfx.git.ui.ThemeManager;
import com.jfx.git.ui.ToastHost;

import com.sun.net.httpserver.HttpServer;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.transform.Transform;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.util.WaitForAsyncUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Screenshots of every dialog, for looking at - and the facts behind whether each one
 * follows the application's theme.
 *
 * <p>Opens each dialog the way the application does - its own constructor, or the method
 * that raises it, over a main window showing a real repository - photographs it in the
 * light and the dark theme, and closes it again with Cancel (or its only button). Nothing
 * is confirmed, so nothing in the repository changes. Output goes to
 * {@code target/dialog-tour/}: one PNG per dialog and theme, {@code facts.tsv}, and an
 * {@code index.html} showing the two themes side by side with what was measured.
 *
 * <pre>
 * mvnw -o test -Dtest=DialogTourTest -DexcludedTestGroups=
 * </pre>
 * Options: {@code -Dtour.scale=1.5} (the output resolution, as on a 150% display), and
 * {@code -Dtour.themes=dark_green,paper} for other themes than the default light and dark
 * (constant names or ids; {@code all} is every built-in theme) - how a new theme's dialogs are
 * looked at.
 *
 * <h2>What is measured, and why</h2>
 * A dialog is its own window with its own scene, so it inherits nothing from the main
 * window unless something hands it the stylesheets. Three things can:
 * {@link ThemeManager#style} (or {@link JfxDialog#shell}, which calls it) puts them on the
 * dialog pane; {@link ThemeManager#register} puts them on a stage's scene; and JavaFX
 * itself binds an owned dialog's scene stylesheets to its owner's, which is the only route
 * the dialogs that pass through nothing but {@link DialogOwner#adopt} have. Each dialog's
 * row says which applied, whether it carries the {@code .jfx-dialog} shell class, the type
 * sizes its header and body actually resolved to, its background, its content padding and
 * its buttons' minimum width - the things that differ between the app's own look and
 * Modena or bare Primer. A last section shows two dialogs again with no main window to own
 * them, which is what separates "themed" from "themed only because it was owned".
 *
 * <p>Tagged {@code tour} and excluded from ordinary runs: it measures nothing it asserts
 * on, except that every dialog in it opened. Headless only - it refuses to run on a real
 * display. It never touches TestFX's shared primary stage: the main window is a stage of
 * its own, closed in teardown.
 */
@Tag("tour")
class DialogTourTest {

    @TempDir
    Path tempDir;

    private final double scale = Double.parseDouble(System.getProperty("tour.scale", "1.5"));
    private final Path out = Path.of("target", "dialog-tour");

    /** The themes photographed: light and dark unless {@code tour.themes} says otherwise. */
    private final List<ThemeManager.Theme> themes = themesToPhotograph();

    private static List<ThemeManager.Theme> themesToPhotograph() {
        String wanted = System.getProperty("tour.themes", "").strip();
        if (wanted.isEmpty()) {
            return List.of(ThemeManager.Theme.LIGHT, ThemeManager.Theme.DARK);
        }
        if (wanted.equalsIgnoreCase("all")) {
            return List.of(ThemeManager.Theme.values());
        }
        List<ThemeManager.Theme> chosen = new ArrayList<>();
        for (String name : wanted.split(",")) {
            ThemeManager.Theme theme = ThemeManager.Theme.fromName(name);
            if (theme == null) {
                throw new IllegalArgumentException("tour.themes: no theme called " + name.strip());
            }
            chosen.add(theme);
        }
        return chosen;
    }

    /** {@code -Dtour.only=reword,merge}: photograph just these, while working on a few dialogs. */
    private final Set<String> only = java.util.Arrays.stream(System.getProperty("tour.only", "").split(","))
            .map(String::strip).filter(id -> !id.isEmpty()).collect(java.util.stream.Collectors.toSet());

    /** One capture: its file name, its window title as the user sees it, and where it is built. */
    private record Spec(String group, String id, String title, String source) {
    }

    /** What was measured on a dialog while it showed. */
    private record Facts(String owner, String sheets, boolean shell, String header, String body,
                         String background, String padding, String buttonMin, String size) {
    }

    private final Map<String, Spec> specs = new LinkedHashMap<>();
    private final Map<String, Map<ThemeManager.Theme, Facts>> facts = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();

    private MainWindowFixture window;
    private Window main;
    private LyraApp app;
    private ThemeManager.Theme theme;
    private ThemeManager.Theme originalTheme;
    private boolean originalReduceMotion;
    private HttpServer providers;
    private FakeOAuthServer oauth;
    private ProviderSignInDialog.Flows previousFlows;

    // What the dialogs are shown with, read from the fixture repository.
    private List<CommitInfo> recent;
    private CommitInfo mergeCommit;
    private StashEntry stash;
    private List<String> remoteOnly;

    @Test
    void tour() throws Exception {
        Assumptions.assumeTrue("Monocle".equalsIgnoreCase(System.getProperty("glass.platform")),
                "the dialog tour renders headless only; it never opens windows on a real display");
        Files.createDirectories(out);
        try (var stale = Files.list(out)) {
            for (Path old : stale.filter(p -> p.toString().endsWith(".png")).toList()) {
                Files.delete(old);
            }
        }

        FxHarness.start();
        originalTheme = FxHarness.call(ThemeManager::current);
        originalReduceMotion = AppPreferences.reduceMotion();
        // A dialog photographed 40ms into its fade is a ghost of itself.
        AppPreferences.setReduceMotion(true);

        window = MainWindowFixture.create(tempDir.resolve("shop"))
                .commitFiles("Validate orders before charging",
                        Map.of("src/main/java/com/shop/payments/OrderValidator.java",
                                bytes("package com.shop.payments;\n\nfinal class OrderValidator {\n}\n")), List.of())
                .commitFiles("Extract tax calculation into TaxPolicy",
                        Map.of("src/main/java/com/shop/payments/TaxPolicy.java",
                                bytes("package com.shop.payments;\n\nfinal class TaxPolicy {\n}\n")), List.of())
                .commitFiles("Fix typos in the payments docs",
                        Map.of("docs/payments.md", bytes("# Payments\n\nCharges go through the gateway.\n")),
                        List.of());
        window.open(1280, 800, ThemeManager.Theme.DARK, true);
        main = window.scene().getWindow();
        FxHarness.run(() -> DialogOwner.useMainWindow(main));
        app = FxHarness.call(LyraApp::new);
        // The application never started, so it has no tab strip of its own; give it the
        // fixture's, and the command palette lists this repository's commands as it would.
        setField(app, "tabPane", window.scene().getRoot());
        readFixture();
        startFakeProviders();

        for (ThemeManager.Theme t : themes) {
            theme = t;
            FxHarness.run(() -> ThemeManager.apply(t));
            window.layOut();
            recordMainWindow();
            dialogClasses();
            windowsAndOverlays();
            adHocForms();
            textInputAndChoice();
            alerts();
            withoutAnOwner();
        }

        writeFacts();
        writeIndex();
        assertTrue(failures.isEmpty(), () -> failures.size() + " dialog(s) did not open:\n  "
                + String.join("\n  ", failures));
    }

    @AfterEach
    void tearDown() {
        if (originalTheme == null) {
            // Skipped before the toolkit started: nothing to undo, and nothing to start.
            return;
        }
        try {
            closeEverythingButMain();
        } catch (RuntimeException ignored) {
            // A dialog stuck open is reported by the test itself; teardown carries on.
        }
        if (previousFlows != null) ProviderSignInDialog.flows = previousFlows;
        GitHubSession.disconnect();
        GitLabSession.disconnect();
        BitbucketSession.disconnect();
        GiteaSession.disconnect();
        if (providers != null) providers.stop(0);
        if (oauth != null) oauth.close();
        FxHarness.run(() -> {
            DialogOwner.useMainWindow(null);
            if (originalTheme != null) ThemeManager.apply(originalTheme);
        });
        AppPreferences.setReduceMotion(originalReduceMotion);
        if (window != null) window.close();
    }

    // ------------------------------------------------------------------
    // The dialogs
    // ------------------------------------------------------------------

    /** The classes under com.jfx.git.ui.dialogs, built with the arguments their callers pass. */
    private void dialogClasses() {
        String g = "Dialog classes (ui/dialogs)";
        var service = window.service();
        String path = "src/main/java/com/shop/payments/PaymentService.java";

        shot(g, "add-remote", "Add remote", "AddRemoteDialog.java:67",
                () -> new AddRemoteDialog(service, () -> { }).show());
        shot(g, "apply-patch", "Apply patch", "ApplyPatchDialog.java:31",
                () -> new ApplyPatchDialog("0001-Round-totals-to-cents.patch", true).show());
        shot(g, "co-author", "Add co-author", "CoAuthorDialog.java:31",
                () -> new CoAuthorDialog(List.of("Grace Hopper <grace@example.com>",
                        "Linus Torvalds <linus@example.com>")).show());
        shot(g, "commit-checks", "Before you commit", "CommitChecksDialog.java:53",
                () -> new CommitChecksDialog(List.of(
                        new CommitChecks.Finding(CommitChecks.Kind.SECRET, "config/payments.properties",
                                "AWS access key", 12, 0),
                        new CommitChecks.Finding(CommitChecks.Kind.LARGE_FILE, "assets/demo/checkout.mp4",
                                "48 MB", 0, 48_000_000L)),
                        true, p -> "*.mp4").show());
        shot(g, "create-pull-request", "New pull request", "CreatePullRequestDialog.java:89",
                () -> new CreatePullRequestDialog("pull request",
                        List.of("main", "release/2.1", "feature/search", MainWindowFixture.LONG_BRANCH),
                        MainWindowFixture.LONG_BRANCH, service, service::getCommitsInRange).show());
        shot(g, "edit-remote", "Edit remote", "EditRemoteDialog.java:44",
                () -> new EditRemoteDialog("origin", "https://github.com/shop/demo.git",
                        "git@github.com:shop/demo.git").show());
        shot(g, "generate-ssh-key", "Generate SSH key", "GenerateSshKeyDialog.java:81",
                () -> new GenerateSshKeyDialog("id_ed25519_lyra", "ada@example.com").show());
        shot(g, "git-flow-setup", "Git Flow", "GitFlowSetupDialog.java:37",
                () -> new GitFlowSetupDialog(List.of("main", "develop", "release/2.1"), null).show());
        withProviders(() -> {
            shot(g, "picker-github", "Browse GitHub repositories", "GitHubRepoPickerDialog.java:42",
                    () -> new GitHubRepoPickerDialog(new GitHubClient(provider())).show(), DialogTourTest::listFilled);
            shot(g, "picker-gitlab", "Browse GitLab projects", "GitLabRepoPickerDialog.java:40",
                    () -> new GitLabRepoPickerDialog(new GitLabClient(provider())).show(), DialogTourTest::listFilled);
            shot(g, "picker-bitbucket", "Browse Bitbucket repositories", "BitbucketRepoPickerDialog.java:41",
                    () -> new BitbucketRepoPickerDialog(new BitbucketClient(provider())).show(),
                    DialogTourTest::listFilled);
            shot(g, "picker-gitea", "Browse Gitea repositories", "GiteaRepoPickerDialog.java:41",
                    () -> new GiteaRepoPickerDialog(new GiteaClient(provider())).show(), DialogTourTest::listFilled);
        });
        shot(g, "interactive-rebase", "Interactive rebase", "InteractiveRebaseDialog.java:155",
                () -> new InteractiveRebaseDialog(RebaseTodo.fromNewestFirst(recent)).show());
        // The same plan with an action of each colour, the middle row selected: what the
        // pills, the fold's target, the move buttons and the summary look like in use.
        shot(g, "interactive-rebase-plan", "Interactive rebase, plan edited", "InteractiveRebaseDialog.java:155",
                () -> {
                    RebaseTodo plan = RebaseTodo.fromNewestFirst(recent);
                    InteractiveRebaseDialog dialog = new InteractiveRebaseDialog(plan
                            .withAction(0, RebaseTodo.Action.REWORD)
                            .withMessage(0, "Validate orders and their totals before charging")
                            .withAction(1, RebaseTodo.Action.SQUASH)
                            .withAction(2, RebaseTodo.Action.DROP));
                    dialog.show();
                    dialog.selectForTest(1);
                });
        shot(g, "merge", "Merge", "MergeDialog.java:47",
                () -> new MergeDialog("feature/payments/refunds", "main").show());
        shot(g, "merge-strategy", "Merge (pull request)", "MergeStrategyDialog.java:28",
                () -> new MergeStrategyDialog("#42 Round totals to cents", List.of(
                        new MergeStrategyDialog.Choice("Create a merge commit", "merge"),
                        new MergeStrategyDialog.Choice("Squash and merge", "squash"),
                        new MergeStrategyDialog.Choice("Rebase and merge", "rebase"))).show());
        shot(g, "merge-tool", "Resolve conflict (merge tool)", "MergeToolDialog.java:278",
                () -> conflict(path).show());
        shot(g, "merge-tool-base", "Resolve conflict, base shown", "MergeToolDialog.java:278",
                () -> {
                    MergeToolDialog dialog = conflict(path);
                    dialog.showBaseForTest(true);
                    // Part way through: the imports picked line by line, keeping one copy
                    // of the line both sides kept, with an external tool configured.
                    dialog.tickForTest(true, 0, 0);
                    dialog.tickForTest(true, 0, 1);
                    dialog.tickForTest(false, 0, 0);
                    dialog.offerExternalTool(() -> { });
                    dialog.show();
                });
        shot(g, "merge-tool-compare", "Compare with the file on disk", "DiffViewer.java:3166",
                () -> new MergeToolDialog(PAYMENT_OURS, PAYMENT_BASE, PAYMENT_BASE, PAYMENT_OURS,
                        new MergeToolDialog.Labels("Compare with the file on disk - " + path,
                                "My edits", "On disk now", "Use this text", false)).show());
        shot(g, "new-repository", "New repository", "NewRepositoryDialog.java:99",
                () -> new NewRepositoryDialog(tempDir.resolve("new-shop").toFile(),
                        NewRepositoryDialog.Purpose.CREATE, () -> { }).show());
        shot(g, "new-repository-init", "Not a Git repository", "NewRepositoryDialog.java:99",
                () -> new NewRepositoryDialog(tempDir.resolve("plain-folder").toFile(),
                        NewRepositoryDialog.Purpose.NOT_A_REPOSITORY, () -> { }).show());
        shot(g, "new-tag", "New tag", "NewTagDialog.java:46",
                () -> new NewTagDialog("On `" + recent.get(0).getShortHash() + "` · "
                        + recent.get(0).getSubject()).show());
        shot(g, "push-to", "Push to", "PushToDialog.java:41",
                () -> new PushToDialog(MainWindowFixture.LONG_BRANCH, List.of("origin", "upstream"),
                        "origin/" + MainWindowFixture.LONG_BRANCH).show());
        shot(g, "review-comment", "Add review comment", "ReviewCommentComposeDialog.java:24",
                () -> new ReviewCommentComposeDialog(true).show());
        shot(g, "reword", "Reword commit", "RewordDialog.java:29",
                () -> new RewordDialog(recent.get(0)).offerCoAuthors(window.service(), message -> { }).show());
        shot(g, "set-upstream", "Set upstream", "SetUpstreamDialog.java:36",
                () -> new SetUpstreamDialog("feature/search",
                        List.of("origin/main", "origin/feature/search", "upstream/main"), null).show());
        shot(g, "sparse-checkout", "Sparse checkout", "SparseCheckoutDialog.java:44",
                () -> new SparseCheckoutDialog(List.of("assets", "docs", "scripts", "src"),
                        List.of(), false).show());
        shot(g, "squash-message", "Squash commits", "SquashMessageDialog.java:31",
                () -> new SquashMessageDialog(recent).show());
        shot(g, "stash-branch", "Branch from stash", "StashBranchDialog.java:25",
                () -> new StashBranchDialog(stash).show());
        shot(g, "stash-options", "Stash", "StashOptionsDialog.java:29",
                () -> new StashOptionsDialog().show());
        shot(g, "undo-preview", "Undo (preview)", "UndoPreviewDialog.java:122",
                () -> UndoPreviewDialog.confirm(service, UndoPreviewDialog.Origin.UNDO,
                        "Reset main to " + recent.get(2).getShortHash(), recent.get(2).getHash(), ok -> { }));
    }

    /** Windows that are a Stage rather than a Dialog, and what shows inside the main window. */
    private void windowsAndOverlays() {
        String g = "Windows and overlays";
        shot(g, "command-palette", "Command palette", "CommandPalette.java:31",
                () -> new CommandPalette(app).show());
        shot(g, "provider-sign-in", "Sign in to GitHub", "ProviderSignInDialog.java:88",
                () -> new ProviderSignInDialog(HostingProvider.GITHUB, URI.create("https://github.com"),
                        deviceSpec()).show(),
                w -> !text(w, ".sign-in-code").isBlank());

        // A toast is not a window: it is drawn in the main window's corner.
        Spec toast = spec(g, "toast-warning", "Notification (toast) over the main window", "ToastHost.java:61");
        try {
            FxHarness.run(() -> window.tab().getStatusBar().warn(
                    "Push to origin/main was rejected\nhint: Updates were rejected because the remote contains"
                            + " work that you do not have locally.\nhint: Integrate the remote changes before"
                            + " pushing again."));
            window.layOut();
            WaitForAsyncUtils.waitForFxEvents();
            snap(main, toast.id());
            record(toast, measure(main));
        } catch (RuntimeException | Error e) {
            fail(toast, e);
        } finally {
            FxHarness.run(() -> {
                ToastHost host = (ToastHost) field(window.tab(), "toastHost");
                new ArrayList<>(host.shown()).forEach(host::dismiss);
            });
        }
        shot(g, "status-details", "Details (from a toast or the status bar)", "StatusBar.java:809",
                () -> invoke(window.tab().getStatusBar(), "showDetails", PUSH_REJECTED));
    }

    /** Dialogs assembled inline - a Dialog, a GridPane and a result converter - rather than a class. */
    private void adHocForms() {
        String g = "Inline forms (new Dialog<>())";
        RepoTab tab = window.tab();
        Object credentials = field(com.jfx.git.core.CredentialsManager.class, "INSTANCE");
        URIish github = uri("https://github.com/shop/demo.git");

        Window clone = shot(g, "clone", "Clone repository", "LyraApp.java:776",
                app::cloneRepoDialog, null, true);
        if (clone != null) {
            // OK with nothing filled in: the form's own validation, under its fields. The
            // form stays open, so it is photographed again rather than a new window.
            Spec invalid = spec(g, "clone-invalid", "Clone - missing URL (inline error)", "LyraApp.java:845");
            try {
                FxHarness.run(() -> press(clone, "OK"));
                settle(clone);
                snap(clone, invalid.id());
                record(invalid, measure(clone));
            } catch (RuntimeException | Error e) {
                fail(invalid, e);
            } finally {
                close(clone);
            }
        }
        shot(g, "credentials-login", "Sign in (HTTPS login)", "CredentialsManager.java:1509",
                () -> invoke(credentials, "showDialog", github));
        shot(g, "credentials-passphrase", "SSH key passphrase", "CredentialsManager.java:717",
                () -> invoke(credentials, "promptSecret", "Passphrase for /home/ada/.ssh/id_ed25519"));
        shot(g, "askpass-secret", "SSH key (askpass)", "CredentialsManager.java:1307",
                () -> invoke(com.jfx.git.core.CredentialsManager.class, "secretDialog", "SSH key",
                        "Enter passphrase for key '/home/ada/.ssh/id_ed25519':"));
        shot(g, "askpass-plain", "Sign in, username only (askpass)", "CredentialsManager.java:1307",
                () -> invoke(com.jfx.git.core.CredentialsManager.class, "plainDialog",
                        "Username for 'https://github.com':"));
        shot(g, "add-submodule", "Add submodule", "SidebarPane.java:2761",
                () -> invoke(tab.getSidebar(), "promptAddSubmodule"));
        shot(g, "add-worktree", "Add worktree", "SidebarPane.java:2952",
                () -> invoke(tab.getSidebar(), "promptAddWorktree", "feature/search"));
        Object integrations = FxHarness.call(com.jfx.git.ui.settings.IntegrationsPane::new);
        @SuppressWarnings("unchecked")
        Map<HostingProvider, Object> rows = (Map<HostingProvider, Object>) field(integrations, "rows");
        shot(g, "integrations-token", "Connect GitHub (token)", "IntegrationsPane.java:586",
                () -> invoke(rows.get(HostingProvider.GITHUB), "promptForToken"));
        shot(g, "integrations-server", "GitLab server", "IntegrationsPane.java:819",
                () -> invoke(rows.get(HostingProvider.GITLAB), "promptForServer"));
        shot(g, "gitlab-merge", "Merge (GitLab merge request)", "GitLabMergeRequestsView.java:214",
                () -> invoke(com.jfx.git.ui.views.GitLabMergeRequestsView.class, "confirmSquash",
                        "!17 Round totals to cents"));
    }

    /** TextInputDialog and ChoiceDialog, JavaFX's stock forms. */
    private void textInputAndChoice() {
        String g = "Text input and choice (TextInputDialog / ChoiceDialog)";
        RepoTab tab = window.tab();
        shot(g, "new-branch-sidebar", "New branch (sidebar)", "SidebarPane.java:2128",
                () -> invoke(tab.getSidebar(), "promptNewBranch", "main"));
        shot(g, "rename-branch", "Rename branch", "SidebarPane.java:1357",
                () -> fireLocalBranchItem("Rename…"));
        shot(g, "rename-remote", "Rename remote", "SidebarPane.java:2020",
                () -> invoke(tab.getSidebar(), "promptRenameRemote", "origin"));
        shot(g, "new-branch-toolbar", "New branch (toolbar)", "RepoToolbar.java:563",
                () -> tab.getToolbar().promptNewBranch());
        shot(g, "rename-tab", "Rename tab", "LyraApp.java:1276",
                () -> invoke(app, "promptRenameTab", tab));
        shot(g, "palette-create-file", "Create file (palette)", "CommandPalette.java:344",
                () -> invoke(CommandPalette.class, "promptCreateFile", tab));
        shot(g, "palette-ignore", "Ignore a path (palette)", "CommandPalette.java:362",
                () -> invoke(CommandPalette.class, "promptIgnore", tab));
        shot(g, "workspace-new-file", "New file (repository files)", "WorkspaceTreeView.java:384",
                () -> invoke(workspaceTree(), "promptNewFile", "src/main/java/com/shop"));
        shot(g, "choose-mainline", "Revert merge commit (choose parent)", "GitGraphCell.java:1887",
                () -> invoke(graphCell(), "chooseMainline", mergeCommit, "Revert"));
    }

    /** Confirmations, warnings and messages: Alert, most passing through DialogOwner.adopt alone. */
    private void alerts() {
        String g = "Alerts (confirmations, warnings, messages)";
        RepoTab tab = window.tab();
        Object sidebar = tab.getSidebar();
        Object credentials = com.jfx.git.core.CredentialsManager.class;
        URIish github = uri("git@github.com:shop/demo.git");

        shot(g, "delete-branch", "Delete branch (confirm)", "SidebarPane.java:2141",
                () -> invoke(sidebar, "confirmDeleteBranch", "feature/search"));
        shot(g, "delete-remote-branch", "Delete remote branch", "SidebarPane.java:1784",
                () -> invoke(sidebar, "confirmDeleteRemoteBranch", "origin/main"));
        shot(g, "remove-remote", "Remove remote", "SidebarPane.java:2067",
                () -> invoke(sidebar, "confirmRemoveRemote", "origin"));
        shot(g, "move-tag", "Move tag to HEAD (not fast-forward)", "SidebarPane.java:2333",
                () -> invoke(sidebar, "confirmMoveTagToHead", "v1.0", rev("HEAD"), rev("v1.0^{commit}"), false));
        shot(g, "orphaned-commit", "Detached HEAD would be left (warning)", "SidebarPane.java:3259",
                () -> invoke(sidebar, "confirmLeaveOrphanedCommit"));
        shot(g, "force-push", "Force push - pull first? (warning)", "RepoToolbar.java:532",
                () -> invoke(tab.getToolbar(), "confirmForcePush", new PushLease("origin", "main", "main",
                        rev("origin/main"), false, remoteOnly, false)));
        shot(g, "unknown-host", "Unknown host (information)", "RepoToolbar.java:610",
                () -> invoke(tab.getToolbar(), "explainUnknownHost",
                        "https://git.example.com/shop/demo.git", "pull requests"));
        shot(g, "auto-stash", "Stash and pull (custom content)", "AutoStashQuestion.java:47",
                () -> invoke(com.jfx.git.ui.AutoStashQuestion.class, "askWithDialog",
                        com.jfx.git.core.AutoStash.Operation.PULL));
        shot(g, "restore-from-commit", "Restore file from commit", "RightPanelManager.java:5294",
                () -> invoke(tab.getRightPanel(), "confirmRestoreFromCommit", recent.get(1).getHash(),
                        "src/main/java/com/shop/payments/PaymentService.java", null));
        shot(g, "restore-renamed", "Restore a renamed file (three answers)", "RestoreDestination.java:81",
                () -> invoke(tab.getRightPanel(), "confirmRestoreFromCommit", recent.get(1).getHash(),
                        "README.md", "docs/README.md"));
        shot(g, "rewrite-pushed", "These commits were pushed (warning)", "CommitHistoryEdits.java:228",
                () -> invoke(com.jfx.git.ui.CommitHistoryEdits.class, "confirmRewritePushed",
                        recent.get(2), "Reword"));
        shot(g, "workspace-delete", "Delete from disk (warning)", "WorkspaceTreeView.java:432",
                () -> invoke(workspaceTree(), "confirmDelete",
                        new WorkspaceFiles.Entry("docs/CHANGELOG.md", "CHANGELOG.md", false)));
        shot(g, "merge-tool-unresolved", "Unresolved conflict markers (from the merge tool)",
                "MergeToolDialog.java:1078",
                () -> invoke(conflict("src/main/java/com/shop/payments/PaymentService.java"),
                        "confirmUnresolvedMarkers"));
        shot(g, "pr-checkout-diverged", "Check out a diverged pull request", "PullRequestCheckout.java:116",
                () -> invoke(com.jfx.git.ui.views.PullRequestCheckout.class, "askWithAlert",
                        MainWindowFixture.LONG_BRANCH, "pull request #42", 2));
        shot(g, "ssh-host-key", "SSH host key (verify)", "CredentialsManager.java:669",
                () -> invoke(credentials, "confirmDialog", github,
                        "The authenticity of host 'github.com' can't be established.\n"
                                + "ED25519 key fingerprint is SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU.\n"
                                + "Are you sure you want to continue connecting?"));
        shot(g, "ssh-host-key-changed", "SSH host key changed (error)", "CredentialsManager.java:507",
                () -> invoke(credentials, "refuseChangedHostKey", github, new CredentialItem[]{
                        new CredentialItem.InformationalMessage(
                                "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!\n"
                                        + "The ED25519 key sent by github.com is SHA256:nThbg6kXUpJWGl7E1IGOCspRomTxdCARLviKw6E5SY8.")}));
        shot(g, "integrations-error", "Could not connect (error)", "IntegrationsPane.java:348",
                () -> invoke(com.jfx.git.ui.settings.IntegrationsPane.class, "showConnectError",
                        "GitHub", "401 Bad credentials"));
        // One question now: the confirmation that followed it is folded in. Never pressed
        // here - this prompt trusts through the real global config.
        shot(g, "trust-folder", "Repository owned by another user (warning)", "TrustFolderPrompt.java:105",
                () -> new com.jfx.git.ui.TrustFolderPrompt(tempDir.resolve("shared").toFile(), DUBIOUS, () -> { })
                        .show());
        shot(g, "crash-report", "Something went wrong (error)", "LyraApp.java:535",
                () -> invoke(app, "showCrashReport", new CrashReporter.Report(
                        "NullPointerException in GraphPageCache.rowAt", CRASH_DETAILS)));
        shot(g, "engine-doctor", "Engine doctor (report)", "PreferencesPages.java:868",
                () -> invoke(construct("com.jfx.git.ui.settings.PreferencesPages", null, null, null),
                        "showDoctorReport", doctorReport()));
        shot(g, "about", "About", "About.java:19", com.jfx.git.ui.help.About::showAboutDialog);
    }

    /**
     * Two dialogs with no main window registered: one themed through its own pane
     * ({@link JfxDialog#shell}) and one that relies on its owner (an Alert passed only to
     * {@link DialogOwner#adopt}). Before the main window exists - and in any window opened
     * without one - the second has nothing to inherit.
     */
    private void withoutAnOwner() {
        String g = "Without an owner (before the main window exists)";
        FxHarness.run(() -> DialogOwner.useMainWindow(null));
        try {
            shot(g, "unowned-shell", "New tag, no owner (JfxDialog.shell)", "NewTagDialog.java:46",
                    () -> new NewTagDialog("On `" + recent.get(0).getShortHash() + "` · "
                        + recent.get(0).getSubject()).show());
            shot(g, "unowned-adopt-only", "Delete branch, no owner (adopt only)", "SidebarPane.java:2141",
                    () -> invoke(window.tab().getSidebar(), "confirmDeleteBranch", "feature/search"));
        } finally {
            FxHarness.run(() -> DialogOwner.useMainWindow(main));
        }
    }

    // ------------------------------------------------------------------
    // Capturing
    // ------------------------------------------------------------------

    /** The window the last {@code keepOpen} capture left showing. */
    private Window lastShown;

    private void shot(String group, String id, String title, String source, Runnable open) {
        shot(group, id, title, source, open, null, false);
    }

    private void shot(String group, String id, String title, String source, Runnable open,
                      Predicate<Window> ready) {
        shot(group, id, title, source, open, ready, false);
    }

    /**
     * Opens a dialog with {@code open} - which may block in {@code showAndWait}, as the
     * application's own code does - waits for its window, photographs it and measures it.
     *
     * @param ready    what has to be true before it is photographed, for a dialog that
     *                 fills itself in after opening; null for none
     * @param keepOpen leave it showing (in {@link #lastShown}) for a capture that follows
     *                 from one of its buttons
     * @return the window, or null when it did not open
     */
    private Window shot(String group, String id, String title, String source, Runnable open,
                        Predicate<Window> ready, boolean keepOpen) {
        if (!only.isEmpty() && !only.contains(id)) {
            return null;
        }
        Spec spec = spec(group, id, title, source);
        lastShown = null;
        Set<Window> before = FxHarness.call(() -> new HashSet<>(Window.getWindows()));
        Throwable[] thrown = new Throwable[1];
        Platform.runLater(() -> {
            try {
                open.run();
            } catch (Throwable t) {
                thrown[0] = t;
            }
        });
        Window[] found = new Window[1];
        try {
            RobotWait.untilOnFx(id + " never opened", () -> {
                found[0] = newWindow(before);
                return found[0] != null || thrown[0] != null;
            });
            if (found[0] == null) {
                throw new IllegalStateException(id + " failed before it opened", thrown[0]);
            }
            if (ready != null) {
                RobotWait.untilOnFx(id + " never finished filling in", () -> ready.test(found[0]));
            }
            settle(found[0]);
            snap(found[0], id);
            record(spec, measure(found[0]));
        } catch (RuntimeException | Error e) {
            fail(spec, e);
            closeEverythingButMain();
            return null;
        }
        if (keepOpen) {
            lastShown = found[0];
        } else {
            close(found[0]);
        }
        return found[0];
    }

    private Spec spec(String group, String id, String title, String source) {
        return specs.computeIfAbsent(id, k -> new Spec(group, id, title, source));
    }

    private void fail(Spec spec, Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        failures.add(spec.id() + " (" + name(theme) + "): " + e.getMessage()
                + (cause != e ? " <- " + cause : ""));
    }

    private void record(Spec spec, Facts measured) {
        facts.computeIfAbsent(spec.id(), k -> new LinkedHashMap<>()).put(theme, measured);
    }

    /** A showing window that was not there before and is not a menu, tooltip or popup. */
    private static Window newWindow(Set<Window> before) {
        for (Window w : new ArrayList<>(Window.getWindows())) {
            if (w.isShowing() && !before.contains(w) && !(w instanceof PopupWindow) && w.getScene() != null) {
                return w;
            }
        }
        return null;
    }

    /** Lets queued work land - git callbacks, deferred focus, a second layout pass for new skins. */
    private static void settle(Window w) {
        RobotWait.drainGitWork();
        for (int i = 0; i < 2; i++) {
            FxHarness.run(() -> {
                Parent root = w.getScene().getRoot();
                root.applyCss();
                root.layout();
            });
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    /** Closes a dialog the way a user who changed their mind would: Cancel, No, Close, or its one button. */
    private static void close(Window w) {
        FxHarness.run(() -> {
            if (!w.isShowing()) return;
            if (w.getScene().getRoot() instanceof DialogPane pane) {
                ButtonType pick = buttonWith(pane, ButtonBar.ButtonData.CANCEL_CLOSE);
                if (pick == null) pick = buttonWith(pane, ButtonBar.ButtonData.NO);
                if (pick == null && pane.getButtonTypes().size() == 1) pick = pane.getButtonTypes().get(0);
                if (pick != null && pane.lookupButton(pick) instanceof Button button && !button.isDisabled()) {
                    button.fire();
                }
            }
        });
        WaitForAsyncUtils.waitForFxEvents();
        FxHarness.run(() -> {
            if (w.isShowing()) w.hide();
        });
        RobotWait.untilOnFx("a dialog would not close", () -> !w.isShowing());
    }

    private static ButtonType buttonWith(DialogPane pane, ButtonBar.ButtonData data) {
        for (ButtonType type : pane.getButtonTypes()) {
            if (type.getButtonData() == data) return type;
        }
        return null;
    }

    /** Fires the button labelled {@code text} in {@code w}'s dialog pane. On the FX thread. */
    private static void press(Window w, String text) {
        DialogPane pane = (DialogPane) w.getScene().getRoot();
        for (ButtonType type : pane.getButtonTypes()) {
            if (text.equals(type.getText())) {
                ((Button) pane.lookupButton(type)).fire();
                return;
            }
        }
        throw new IllegalStateException("no button labelled " + text);
    }

    private void closeEverythingButMain() {
        List<Window> open = FxHarness.call(() -> new ArrayList<>(Window.getWindows()).stream()
                .filter(w -> w != main && w.isShowing())
                .toList());
        for (Window w : open) {
            if (w instanceof PopupWindow) {
                FxHarness.run(w::hide);
            } else {
                close(w);
            }
        }
    }

    private void snap(Window w, String id) {
        File file = out.resolve(id + "-" + name(theme) + ".png").toFile();
        BufferedImage image = FxHarness.call(() -> {
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setTransform(Transform.scale(scale, scale));
            WritableImage shot = w.getScene().getRoot().snapshot(parameters, null);
            int width = (int) shot.getWidth();
            int height = (int) shot.getHeight();
            BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            var pixels = shot.getPixelReader();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    buffered.setRGB(x, y, pixels.getArgb(x, y));
                }
            }
            return buffered;
        });
        try {
            ImageIO.write(image, "png", file);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // Measuring
    // ------------------------------------------------------------------

    private Facts measure(Window w) {
        return FxHarness.call(() -> {
            Scene scene = w.getScene();
            Parent root = scene.getRoot();
            Window ownerWindow = w instanceof Stage s ? s.getOwner()
                    : w instanceof PopupWindow p ? p.getOwnerWindow() : null;
            String owner = w == main ? "(is the main window)"
                    : ownerWindow == null ? "none" : ownerWindow == main ? "main window" : "other window";

            boolean own = hasTokens(root.getStylesheets());
            boolean onScene = hasTokens(scene.getStylesheets());
            // Only a Dialog's window is bound to its owner's stylesheets; a Stage that
            // registered itself merely ends up with an equal list.
            boolean inherited = onScene && root instanceof DialogPane && ownerWindow != null
                    && ownerWindow.getScene() != null
                    && scene.getStylesheets().equals(ownerWindow.getScene().getStylesheets());
            String sheets = own && onScene ? "pane (style/shell) + " + (inherited ? "inherited" : "scene")
                    : own ? "pane only (style/shell)"
                    : onScene ? (inherited ? "inherited from owner" : "scene (register)")
                    : "none - Primer user-agent only";

            Labeled header = null;
            Labeled body = null;
            // The main window's reference text: a branch row in the sidebar.
            for (Node node : w == main ? root.lookupAll(".tree-cell") : Set.<Node>of()) {
                if (node instanceof Labeled cell && cell.getText() != null && !cell.getText().isBlank()) {
                    body = cell;
                    break;
                }
            }
            for (Node node : root.lookupAll(".label")) {
                if (!(node instanceof Labeled label) || !node.isVisible() || label.getText() == null
                        || label.getText().isBlank()) {
                    continue;
                }
                if (within(node, "header-panel")) {
                    if (header == null) header = label;
                } else if (!within(node, "button-bar") && body == null && w != main) {
                    body = label;
                }
            }

            String padding = "-";
            Node content = root.lookup(".content");
            if (root instanceof DialogPane && content instanceof Region region) {
                var insets = region.getPadding();
                padding = trim(insets.getTop()) + "/" + trim(insets.getLeft());
            }
            String buttonMin = "-";
            Node button = root.lookup(".button-bar .button");
            if (button instanceof Region region) {
                buttonMin = region.getMinWidth() < 0 ? "auto" : trim(region.getMinWidth());
            }
            // What the dialog asked for, beside what it got: a preset the pane could not
            // reach is a cap somewhere in the stylesheets, not the dialog's choice. A
            // preset that pins only the width leaves the height to the content, so only
            // a pinned height is compared.
            String size = trim(scene.getWidth()) + "x" + trim(scene.getHeight());
            if (root instanceof Region region && region.getPrefWidth() > 0
                    && (region.getPrefWidth() != scene.getWidth()
                        || (region.getPrefHeight() > 0 && region.getPrefHeight() != scene.getHeight()))) {
                size += " (asked " + trim(region.getPrefWidth()) + "x" + trim(region.getPrefHeight())
                        + ", max width " + trim(region.maxWidth(-1)) + ")";
            }
            return new Facts(owner, sheets, root.getStyleClass().contains("jfx-dialog"),
                    font(header), font(body), background(root), padding, buttonMin, size);
        });
    }

    /** The main window, measured the same way: what a dialog is being compared with. */
    private void recordMainWindow() {
        Spec spec = spec("Reference", "main-window", "Main window (the reference)", "RepoTab");
        record(spec, measure(main));
        snap(main, spec.id());
    }

    private static boolean hasTokens(List<String> sheets) {
        return sheets.stream().anyMatch(s -> s.contains("/css/theme-"));
    }

    private static boolean within(Node node, String styleClass) {
        for (Node n = node.getParent(); n != null; n = n.getParent()) {
            if (n.getStyleClass().contains(styleClass)) return true;
        }
        return false;
    }

    private static String font(Labeled label) {
        if (label == null) return "-";
        return trim(label.getFont().getSize()) + "px " + label.getFont().getFamily()
                + " " + hex(label.getTextFill());
    }

    private static String background(Parent root) {
        if (root instanceof Region region && region.getBackground() != null
                && !region.getBackground().getFills().isEmpty()) {
            return hex(region.getBackground().getFills().get(region.getBackground().getFills().size() - 1).getFill());
        }
        return "none";
    }

    private static String hex(Paint paint) {
        if (paint instanceof Color c) {
            return String.format(Locale.ROOT, "#%02x%02x%02x", Math.round(c.getRed() * 255),
                    Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
        }
        return paint == null ? "none" : paint.getClass().getSimpleName();
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    private void writeFacts() throws Exception {
        StringBuilder tsv = new StringBuilder(
                "group\tid\ttitle\tsource\ttheme\towner\tstylesheets\tjfx-dialog\theader\tbody\tbackground"
                        + "\tcontent padding\tbutton min\tsize\n");
        for (Spec spec : specs.values()) {
            Map<ThemeManager.Theme, Facts> byTheme = facts.getOrDefault(spec.id(), Map.of());
            for (var entry : byTheme.entrySet()) {
                Facts f = entry.getValue();
                tsv.append(String.join("\t", spec.group(), spec.id(), spec.title(), spec.source(),
                        name(entry.getKey()), f.owner(), f.sheets(), String.valueOf(f.shell()), f.header(),
                        f.body(), f.background(), f.padding(), f.buttonMin(), f.size())).append('\n');
            }
        }
        Files.writeString(out.resolve("facts.tsv"), tsv, StandardCharsets.UTF_8);
    }

    /** One row per dialog: what it is, what was measured, and the two themes side by side. */
    private void writeIndex() throws Exception {
        StringBuilder html = new StringBuilder("""
                <!doctype html><meta charset=utf-8><title>Dialog tour</title>
                <style>
                body{font:13px system-ui,sans-serif;background:#8a8f98;margin:16px;color:#111}
                h1{font-size:20px}h2{font-size:16px;margin:28px 0 8px;padding:6px 8px;background:#e8eaee}
                table{border-collapse:collapse;width:100%}td{vertical-align:top;padding:6px;border-top:1px solid #6d727a}
                td.meta{width:22%;background:#f4f5f7}td.shot{min-width:240px}img{max-width:100%;border:1px solid #333;display:block}
                code{font:12px ui-monospace,Consolas,monospace}dl{margin:6px 0 0}dt{font-weight:600;margin-top:4px}dd{margin:0}
                .fail{background:#fde2e1;padding:8px}
                </style>
                <h1>Dialog tour</h1>
                <p>Each dialog opened through the application's own code over a main window showing a
                fixture repository, then cancelled, once in each theme photographed (""" + themes.stream()
                .map(DialogTourTest::name).toList() + """
                ). The measured facts are from the dark theme where there is one, else the
                first; <code>facts.tsv</code> has all of them.</p>
                """);
        if (!failures.isEmpty()) {
            html.append("<div class=fail><b>Did not open:</b><ul>");
            failures.forEach(f -> html.append("<li><code>").append(escape(f)).append("</code></li>"));
            html.append("</ul></div>");
        }
        String group = null;
        for (Spec spec : specs.values()) {
            if (!spec.group().equals(group)) {
                if (group != null) html.append("</table>");
                group = spec.group();
                html.append("<h2>").append(escape(group)).append("</h2><table>");
            }
            Map<ThemeManager.Theme, Facts> measured = facts.getOrDefault(spec.id(), Map.of());
            Facts f = measured.getOrDefault(ThemeManager.Theme.DARK,
                    measured.values().stream().findFirst().orElse(null));
            html.append("<tr><td class=meta><b>").append(escape(spec.title())).append("</b><br><code>")
                    .append(escape(spec.id())).append("</code><br><code>").append(escape(spec.source()))
                    .append("</code>");
            if (f != null) {
                html.append("<dl>")
                        .append(dd("Owner", f.owner()))
                        .append(dd("Stylesheets", f.sheets()))
                        .append(dd(".jfx-dialog shell", f.shell() ? "yes" : "no"))
                        .append(dd("Header type", f.header()))
                        .append(dd("Body type", f.body()))
                        .append(dd("Background", f.background()))
                        .append(dd("Content padding (top/left)", f.padding()))
                        .append(dd("Button min width", f.buttonMin()))
                        .append(dd("Size", f.size()))
                        .append("</dl>");
            }
            html.append("</td>");
            for (ThemeManager.Theme t : themes) {
                String png = spec.id() + "-" + name(t) + ".png";
                html.append("<td class=shot>");
                if (Files.exists(out.resolve(png))) {
                    html.append("<a href=\"").append(png).append("\"><img loading=lazy src=\"").append(png)
                            .append("\" alt=\"").append(escape(spec.title())).append(" - ").append(name(t))
                            .append("\"></a>");
                } else {
                    html.append("<i>not captured</i>");
                }
                html.append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</table>");
        Files.writeString(out.resolve("index.html"), html, StandardCharsets.UTF_8);
    }

    private static String dd(String term, String value) {
        return "<dt>" + escape(term) + "</dt><dd><code>" + escape(value) + "</code></dd>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String name(ThemeManager.Theme t) {
        return t == null ? "?" : t.name().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Fixture data
    // ------------------------------------------------------------------

    private void readFixture() throws Exception {
        var service = window.service();
        RobotWait.drainGitWork();
        recent = service.getCommitsInRange(rev("HEAD~3"), rev("HEAD"), 10);
        mergeCommit = service.getCommitsInRange(rev("HEAD~8"), rev("HEAD"), 50).stream()
                .filter(c -> c.getParentHashes().size() > 1)
                .findFirst().orElseThrow(() -> new AssertionError("the fixture has no merge commit"));
        stash = service.getStashEntries().get(0);
        remoteOnly = service.getCommitsInRange(rev("HEAD"), rev("origin/main"), 5).stream()
                .map(c -> c.getShortHash() + " " + c.getSubject())
                .toList();
    }

    private String rev(String revision) {
        try (Git git = Git.open(window.workTree().toFile())) {
            return git.getRepository().resolve(revision).name();
        } catch (Exception e) {
            throw new IllegalStateException("cannot resolve " + revision, e);
        }
    }

    /** One HTTP server answering as all four providers' repository lists. */
    private void startFakeProviders() throws Exception {
        providers = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        respond("/user/repos", """
                [{"full_name":"ada/shop","name":"shop","owner":{"login":"ada"},"private":false,
                  "clone_url":"https://example.com/ada/shop.git","ssh_url":"git@example.com:ada/shop.git",
                  "description":"The demo shop"},
                 {"full_name":"ada/payments-gateway","name":"payments-gateway","owner":{"login":"ada"},"private":true,
                  "clone_url":"https://example.com/ada/payments-gateway.git","ssh_url":"git@example.com:ada/payments-gateway.git",
                  "description":"Card and wallet gateway"},
                 {"full_name":"shop-org/storefront","name":"storefront","owner":{"login":"shop-org"},"private":false,
                  "clone_url":"https://example.com/shop-org/storefront.git","ssh_url":"git@example.com:shop-org/storefront.git",
                  "description":""},
                 {"full_name":"shop-org/infrastructure","name":"infrastructure","owner":{"login":"shop-org"},"private":true,
                  "clone_url":"https://example.com/shop-org/infrastructure.git","ssh_url":"git@example.com:shop-org/infrastructure.git",
                  "description":"Terraform"}]""");
        respond("/projects", """
                [{"path_with_namespace":"shop/storefront","http_url_to_repo":"https://gitlab.example.com/shop/storefront.git",
                  "ssh_url_to_repo":"git@gitlab.example.com:shop/storefront.git","description":"Web shop","visibility":"public"},
                 {"path_with_namespace":"shop/payments","http_url_to_repo":"https://gitlab.example.com/shop/payments.git",
                  "ssh_url_to_repo":"git@gitlab.example.com:shop/payments.git","description":null,"visibility":"private"},
                 {"path_with_namespace":"ada/dotfiles","http_url_to_repo":"https://gitlab.example.com/ada/dotfiles.git",
                  "ssh_url_to_repo":"git@gitlab.example.com:ada/dotfiles.git","description":"","visibility":"private"}]""");
        respond("/workspaces", """
                {"values":[{"slug":"shop"}]}""");
        respond("/repositories/shop", """
                {"values":[{"full_name":"shop/storefront","is_private":false,"description":"Web shop",
                  "links":{"clone":[{"name":"https","href":"https://bitbucket.org/shop/storefront.git"}]}},
                 {"full_name":"shop/payments","is_private":true,"description":null,
                  "links":{"clone":[{"name":"https","href":"https://bitbucket.org/shop/payments.git"}]}}]}""");
        providers.start();

        oauth = new FakeOAuthServer();
        oauth.on("/login/device/code", 200, "{\"device_code\":\"dc\",\"user_code\":\"WDJB-MJHT\","
                + "\"verification_uri\":\"https://github.com/login/device\",\"expires_in\":900,\"interval\":5}");
        oauth.on("/login/oauth/access_token", 200, "{\"error\":\"authorization_pending\"}");
        previousFlows = ProviderSignInDialog.flows;
        ProviderSignInDialog.flows = new ProviderSignInDialog.Flows() {
            @Override
            public OAuthDeviceFlow device(OAuthSpec spec) {
                // Polls slowly and never succeeds: the dialog stays on its code until closed.
                return new OAuthDeviceFlow(spec, new OAuthTokenClient(), interval -> Thread.sleep(250));
            }

            @Override
            public OAuthLoopbackFlow loopback(OAuthSpec spec) {
                throw new UnsupportedOperationException("the tour shows the device flow");
            }
        };
    }

    private void respond(String path, String body) {
        providers.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private URI provider() {
        return URI.create("http://localhost:" + providers.getAddress().getPort());
    }

    private OAuthSpec deviceSpec() {
        return new OAuthSpec(OAuthSpec.Flow.DEVICE, oauth.uri("/login/device/code"), null,
                oauth.uri("/login/oauth/access_token"), null, "repo read:org workflow", "app-id", null, 0, false);
    }

    /** Connected to every provider while {@code captures} run, and to none afterwards. */
    private void withProviders(Runnable captures) {
        GitHubSession.connect("a-token", new GitHubUser("ada", null));
        GitLabSession.connect("a-token", new GitLabUser("ada", null));
        BitbucketSession.connect("a-token", new BitbucketUser("ada", "Ada Lovelace"));
        GiteaSession.connect("a-token", new GiteaUser("ada", null));
        try {
            captures.run();
        } finally {
            GitHubSession.disconnect();
            GitLabSession.disconnect();
            BitbucketSession.disconnect();
            GiteaSession.disconnect();
        }
    }

    private static boolean listFilled(Window w) {
        for (Node node : w.getScene().getRoot().lookupAll(".list-view")) {
            if (node instanceof ListView<?> list && !list.getItems().isEmpty()) return true;
        }
        return false;
    }

    private static String text(Window w, String selector) {
        Node node = w.getScene().getRoot().lookup(selector);
        return node instanceof Label label && label.isVisible() && label.getText() != null ? label.getText() : "";
    }

    /** A conflict in PaymentService: imports and the charge method both changed on each side. */
    private static MergeToolDialog conflict(String path) {
        return new MergeToolDialog(path, PAYMENT_OURS, PAYMENT_THEIRS, PAYMENT_BASE, PAYMENT_MERGED,
                "main", "feature/payments/refunds");
    }

    /** The repository-files tree, built as the right panel builds it. */
    private Object workspaceTree() {
        RepoTab tab = window.tab();
        return new com.jfx.git.ui.WorkspaceTreeView(
                com.jfx.git.ui.OperationRunner.of(tab.getGitService(), tab.getStatusBar(), () -> { }), p -> { });
    }

    /** A graph row's cell: the commit menu's dialogs are its methods. */
    private Object graphCell() {
        for (Node node : window.tab().getCommitList().lookupAll(".list-cell")) {
            if (node instanceof com.jfx.git.graph.GitGraphCell cell) return cell;
        }
        throw new IllegalStateException("the graph has no cells");
    }

    /** Fires an item of a LOCAL branch row's own context menu, as a right-click then a click would. */
    private void fireLocalBranchItem(String text) {
        Node tree = FxHarness.lookupAll(window.tab().getSidebar(), "branch-tree").get(0);
        for (Node node : tree.lookupAll(".tree-cell")) {
            if (node instanceof Control cell && cell.getContextMenu() != null) {
                MenuItem item = find(cell.getContextMenu().getItems(), text);
                if (item != null && "search".equals(((Labeled) cell).getText())) {
                    item.fire();
                    return;
                }
            }
        }
        // Not the row the tour hoped for: any local branch row will do.
        for (Node node : tree.lookupAll(".tree-cell")) {
            if (node instanceof Control cell && cell.getContextMenu() != null) {
                MenuItem item = find(cell.getContextMenu().getItems(), text);
                if (item != null) {
                    item.fire();
                    return;
                }
            }
        }
        throw new IllegalStateException("no LOCAL branch row offers " + text);
    }

    private static MenuItem find(List<MenuItem> items, String text) {
        for (MenuItem item : items) {
            if (text.equals(item.getText())) return item;
            if (item instanceof Menu menu) {
                MenuItem inner = find(menu.getItems(), text);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    private static EngineDoctor.Report doctorReport() {
        Map<String, String> head = new LinkedHashMap<>();
        head.put("JGit", "3f2a9c1");
        head.put("Native git", "3f2a9c1");
        head.put("libgit2", "3f2a9c1");
        Map<String, String> status = new LinkedHashMap<>();
        status.put("JGit", "3 modified, 1 staged, 1 untracked");
        status.put("Native git", "3 modified, 1 staged, 1 untracked");
        status.put("libgit2", "3 modified, 1 staged, 2 untracked");
        Map<String, String> branches = new LinkedHashMap<>();
        branches.put("JGit", "12 local, 1 remote");
        branches.put("Native git", "12 local, 1 remote");
        branches.put("libgit2", "12 local, 1 remote");
        return new EngineDoctor.Report(List.of("JGit", "Native git", "libgit2"), List.of(
                new EngineDoctor.Check("HEAD commit", head),
                new EngineDoctor.Check("Working tree status", status),
                new EngineDoctor.Check("Branches", branches)), null);
    }

    private static URIish uri(String s) {
        try {
            return new URIish(s);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Reaching the methods that raise a dialog
    // ------------------------------------------------------------------

    /**
     * Calls {@code name} on {@code target} (a class, for a static method) whatever its
     * visibility. Most dialogs are raised by a private method of the view they belong to,
     * and the tour calls that rather than rebuilding a look-alike that could drift.
     */
    private static Object invoke(Object target, String name, Object... args) {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        Object receiver = target instanceof Class<?> ? null : target;
        for (Class<?> k = type; k != null; k = k.getSuperclass()) {
            for (Method method : k.getDeclaredMethods()) {
                if (method.getName().equals(name) && accepts(method.getParameterTypes(), args)) {
                    method.setAccessible(true);
                    try {
                        return method.invoke(receiver, args);
                    } catch (InvocationTargetException e) {
                        throw new IllegalStateException(name + " threw " + e.getCause(), e.getCause());
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        throw new IllegalArgumentException("no " + name + " taking " + args.length + " argument(s) on " + type.getName());
    }

    private static Object construct(String className, Object... args) {
        try {
            for (Constructor<?> constructor : Class.forName(className).getDeclaredConstructors()) {
                if (accepts(constructor.getParameterTypes(), args)) {
                    constructor.setAccessible(true);
                    return constructor.newInstance(args);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalArgumentException("no constructor of " + className + " for " + args.length + " argument(s)");
    }

    private static Object field(Object target, String name) {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        Object receiver = target instanceof Class<?> ? null : target;
        for (Class<?> k = type; k != null; k = k.getSuperclass()) {
            try {
                Field field = k.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(receiver);
            } catch (NoSuchFieldException next) {
                // Declared further up.
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("no field " + name + " on " + type.getName());
    }

    private static void setField(Object target, String name, Object value) {
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field field = k.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException next) {
                // Declared further up.
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("no field " + name + " on " + target.getClass().getName());
    }

    private static boolean accepts(Class<?>[] parameters, Object[] args) {
        if (parameters.length != args.length) return false;
        for (int i = 0; i < parameters.length; i++) {
            Class<?> wanted = parameters[i].isPrimitive()
                    ? MethodType.methodType(parameters[i]).wrap().returnType()
                    : parameters[i];
            if (args[i] == null ? parameters[i].isPrimitive() : !wanted.isInstance(args[i])) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------

    private static final String PAYMENT_BASE = """
            package com.shop.payments;

            import java.math.BigDecimal;
            import java.util.Objects;

            public class PaymentService {
                private final Gateway gateway;

                public PaymentService(Gateway gateway) {
                    this.gateway = Objects.requireNonNull(gateway);
                }

                public Receipt charge(Order order) {
                    BigDecimal total = order.total();
                    return gateway.charge(order.customer(), total);
                }
            }
            """;

    private static final String PAYMENT_OURS = """
            package com.shop.payments;

            import java.math.BigDecimal;
            import java.util.Objects;
            import com.shop.audit.AuditLog;

            public class PaymentService {
                private final Gateway gateway;
                private final AuditLog audit = new AuditLog("payments");

                public PaymentService(Gateway gateway) {
                    this.gateway = Objects.requireNonNull(gateway);
                }

                public Receipt charge(Order order) {
                    validate(order);
                    BigDecimal total = order.total();
                    audit.record(order.id(), total);
                    return gateway.charge(order.customer(), total);
                }
            }
            """;

    private static final String PAYMENT_THEIRS = """
            package com.shop.payments;

            import java.math.BigDecimal;
            import java.math.RoundingMode;
            import java.util.Objects;

            public class PaymentService {
                private final Gateway gateway;

                public PaymentService(Gateway gateway) {
                    this.gateway = Objects.requireNonNull(gateway);
                }

                public Receipt charge(Order order) {
                    BigDecimal total = order.total().setScale(2, RoundingMode.HALF_EVEN);
                    Receipt receipt = gateway.charge(order.customer(), total);
                    refunds.register(receipt);
                    return receipt;
                }
            }
            """;

    private static final String PAYMENT_MERGED = """
            package com.shop.payments;

            import java.math.BigDecimal;
            <<<<<<< main
            import java.util.Objects;
            import com.shop.audit.AuditLog;
            =======
            import java.math.RoundingMode;
            import java.util.Objects;
            >>>>>>> feature/payments/refunds

            public class PaymentService {
                private final Gateway gateway;
                private final AuditLog audit = new AuditLog("payments");

                public PaymentService(Gateway gateway) {
                    this.gateway = Objects.requireNonNull(gateway);
                }

                public Receipt charge(Order order) {
            <<<<<<< main
                    validate(order);
                    BigDecimal total = order.total();
                    audit.record(order.id(), total);
                    return gateway.charge(order.customer(), total);
            =======
                    BigDecimal total = order.total().setScale(2, RoundingMode.HALF_EVEN);
                    Receipt receipt = gateway.charge(order.customer(), total);
                    refunds.register(receipt);
                    return receipt;
            >>>>>>> feature/payments/refunds
                }
            }
            """;

    private static final String PUSH_REJECTED = """
            To https://github.com/shop/demo.git
             ! [rejected]        main -> main (fetch first)
            error: failed to push some refs to 'https://github.com/shop/demo.git'
            hint: Updates were rejected because the remote contains work that you do not
            hint: have locally. This is usually caused by another repository pushing to
            hint: the same ref. If you want to integrate the remote changes, use
            hint: 'git pull' before pushing again.
            hint: See the 'Note about fast-forwards' in 'git push --help' for details.
            """;

    private static final String DUBIOUS = """
            fatal: detected dubious ownership in repository at 'C:/Users/Shared/shop'
            'C:/Users/Shared/shop' is owned by:
                BUILTIN/Administrators (S-1-5-32-544)
            but the current user is:
                DESKTOP-7Q2/ada (S-1-5-21-1004336348-1177238915-682003330-1001)
            To add an exception for this directory, call:

                git config --global --add safe.directory C:/Users/Shared/shop""";

    private static final String CRASH_DETAILS = """
            java.lang.NullPointerException: Cannot invoke "GitGraphRow.getCommit()" because "row" is null
                at com.jfx.git.ui.GraphPageCache.rowAt(GraphPageCache.java:212)
                at com.jfx.git.graph.GitGraphCell.updateItem(GitGraphCell.java:388)
                at javafx.scene.control.ListCell.updateItem(ListCell.java:481)
                at javafx.scene.control.skin.VirtualFlow.setCellIndex(VirtualFlow.java:1813)
            Lyra 1.4.0 - Java 25.0.4 - JavaFX 23.0.2 - Windows 11""";
}
