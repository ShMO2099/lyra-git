package com.jfx.git.testsupport;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import com.jfx.git.core.ThreadGuard;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RepoTab;
import com.jfx.git.ui.RobotWait;
import com.jfx.git.ui.ThemeManager;

import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.URIish;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The main window over a repository that looks like real work, with the application's
 * own stylesheets on it - what layout and screenshot tests need and the robot tests do
 * not give them.
 *
 * <p>Robot tests build a bare {@code new Scene(pane, w, h)}, so AtlantaFX and the app's
 * CSS never reach their nodes and nothing they measure is what a user sees. Here the
 * scene is registered with {@link ThemeManager} before anything is measured.
 *
 * <p>The repository is fixed apart from its dates, which count back from now so relative
 * ages read the same on every run: {@code main} one commit ahead of and two behind a bare
 * {@code origin}; a folder of {@code feature/*} branches including the deliberately long
 * {@code feature/login-redesign}; {@code release/2.1} and {@code bugfix/rounding}; two
 * merges; tags; a stash; three modified files, one staged file and one untracked file.
 *
 * <p>Wide scenes are laid out unshown ({@link #layOut}), because Monocle's headless
 * screen is smaller than the 1480-pixel window this has to check.
 */
public final class MainWindowFixture implements AutoCloseable {

    public static final String LONG_BRANCH = "feature/login-redesign";

    private final Path root;
    private final Path work;
    private GitService service;
    private RepoTab tab;
    private Scene scene;
    private Stage stage;
    /** The size the window was last given; an unshown scene keeps the one it was built with. */
    private double width;
    private double height;

    private MainWindowFixture(Path root) {
        this.root = root;
        this.work = root.resolve("demo");
    }

    /** Builds the repository under {@code root}; call {@link #open} to put a window on it. */
    public static MainWindowFixture create(Path root) throws Exception {
        MainWindowFixture fixture = new MainWindowFixture(root);
        try (var ignored = ThreadGuard.buildingFixture()) {
            fixture.buildRepository();
        }
        return fixture;
    }

    /**
     * Adds {@code remoteBranches} branches under {@code origin/team/} and {@code tags}
     * tags, all on HEAD. Call before {@link #open}.
     *
     * <p>A real repository has hundreds of each, and a list that is right for eleven
     * branches says nothing about one that has to hold four hundred: sizing, scrolling
     * and the gaps left behind when a folder closes only show up at that size.
     */
    public MainWindowFixture withManyRefs(int remoteBranches, int tags) throws Exception {
        try (var ignored = ThreadGuard.buildingFixture(); Git git = Git.open(work.toFile())) {
            var repository = git.getRepository();
            var head = repository.resolve("HEAD");
            for (int i = 0; i < remoteBranches; i++) {
                var update = repository.updateRef(String.format("refs/remotes/origin/team/branch-%03d", i));
                update.setNewObjectId(head);
                update.update();
            }
            for (int i = 0; i < tags; i++) {
                var update = repository.updateRef(String.format("refs/tags/build-%03d", i));
                update.setNewObjectId(head);
                update.update();
            }
        }
        return this;
    }

    /**
     * Commits on HEAD: {@code writes} written (path to bytes), {@code deletes} removed.
     * Anything already staged by the fixture goes in too. Call before {@link #open}.
     */
    public MainWindowFixture commitFiles(String message, java.util.Map<String, byte[]> writes,
                                         java.util.List<String> deletes) throws Exception {
        return commitFiles(message, writes, deletes, 1);
    }

    /**
     * As {@link #commitFiles(String, java.util.Map, java.util.List)}, authored
     * {@code authorDaysAgo} days back while committed now - the shape a rebased or
     * cherry-picked commit has, whose author date can be older than its parent's.
     */
    public MainWindowFixture commitFiles(String message, java.util.Map<String, byte[]> writes,
                                         java.util.List<String> deletes, int authorDaysAgo)
            throws Exception {
        try (var ignored = ThreadGuard.buildingFixture(); Git git = Git.open(work.toFile())) {
            for (var entry : writes.entrySet()) {
                Path file = work.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
                git.add().addFilepattern(entry.getKey()).call();
            }
            for (String path : deletes) {
                git.rm().addFilepattern(path).call();
            }
            PersonIdent committer = ident(ADA, 1);
            git.commit().setMessage(message).setAuthor(ident(ADA, authorDaysAgo))
                    .setCommitter(committer).setSign(false).call();
        }
        return this;
    }

    public Path workTree() {
        return work;
    }

    public GitService service() {
        return service;
    }

    public RepoTab tab() {
        return tab;
    }

    public Scene scene() {
        return scene;
    }

    /**
     * Builds the main window at {@code width × height} in {@code theme} and waits for the
     * graph to load. {@code show} puts it in a real stage - only possible at sizes the
     * headless screen can hold; otherwise the scene is laid out without one.
     */
    public MainWindowFixture open(double width, double height, ThemeManager.Theme theme, boolean show) {
        FxHarness.start();
        this.width = width;
        this.height = height;
        service = new GitService();
        service.openRepository(work.toFile());
        // Widths someone dragged are remembered, and the tests share one settings file: a
        // column another test left at 44 pixels would be this window's column too.
        com.jfx.git.core.AppPreferences.setGraphRailWidth(0);
        com.jfx.git.core.AppPreferences.setGraphLaneBudget(0);
        com.jfx.git.core.AppPreferences.setLayoutSidebarWidth(0);
        com.jfx.git.core.AppPreferences.setLayoutDetailWidth(0);
        com.jfx.git.core.AppPreferences.setGraphNodeStyle(com.jfx.git.graph.GraphNodeStyle.AUTHOR);
        FxHarness.run(() -> {
            tab = new RepoTab(new LyraApp(), "demo", service);
            scene = new Scene(new TabPane(tab), width, height);
            ThemeManager.register(scene);
            ThemeManager.apply(theme);
            if (show) {
                stage = new Stage();
                stage.setScene(scene);
                stage.show();
            }
            tab.refresh();
        });
        RobotWait.drainGitWork();
        RobotWait.untilOnFx("the graph never loaded", () -> !tab.getCommitList().getItems().isEmpty());
        RobotWait.drainGitWork();
        layOut();
        return this;
    }

    /** Resizes the window's content and lays it out again. */
    public void resize(double width, double height) {
        this.width = width;
        this.height = height;
        FxHarness.run(() -> {
            if (stage != null) {
                stage.setWidth(width);
                stage.setHeight(height);
            }
        });
        layOut();
    }

    /** CSS and layout, twice: skins created by the first pass need a pass of their own. */
    public void layOut() {
        FxHarness.run(() -> {
            for (int i = 0; i < 2; i++) {
                scene.getRoot().applyCss();
                if (stage == null) {
                    scene.getRoot().resize(width, height);
                }
                scene.getRoot().layout();
            }
        });
    }

    @Override
    public void close() {
        RobotWait.drainGitWork();
        if (tab != null) FxHarness.run(tab::close);
        if (stage != null) FxHarness.run(stage::close);
        if (scene != null) FxHarness.run(() -> ThemeManager.unregister(scene));
        if (service != null) service.close();
    }

    // ------------------------------------------------------------------

    private void buildRepository() throws Exception {
        Path origin = root.resolve("origin.git");
        Git.init().setBare(true).setDirectory(origin.toFile()).setInitialBranch("main").call().close();

        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Ada Lovelace");
            config.setString("user", null, "email", "ada@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            git.remoteAdd().setName("origin").setUri(new URIish(origin.toUri().toString())).call();

            write("README.md", "# Shop\n\nA small demo shop.\n");
            write(".gitignore", "target/\n*.log\n");
            write("src/main/java/com/shop/payments/PaymentService.java", paymentService(""));
            commit(git, "Initial project skeleton", ADA, 30);
            for (int i = 1; i <= 3; i++) {
                append("src/main/java/com/shop/payments/PaymentService.java", "// helper " + i + "\n");
                commit(git, "Add payment helper " + i, ADA, 29 - i);
            }
            git.tag().setName("v1.0").setMessage("First release").setAnnotated(true)
                    .setTagger(ident(ADA, 26)).call();

            branchWith(git, LONG_BRANCH, "docs/login.md", "Redesign the login screen", GRACE, 24);
            branchWith(git, "feature/payments/refunds", "src/main/java/com/shop/payments/RefundService.java",
                    "Add refund service", GRACE, 23);
            branchWith(git, "feature/payments/invoices", "docs/invoices.md", "Sketch invoice docs", LINUS, 22);
            branchWith(git, "feature/search", "docs/search.md", "Outline product search", GRACE, 21);
            branchWith(git, "feature/cart/coupons", "docs/coupons.md", "Plan coupon codes", ADA, 20);
            branchWith(git, "feature/cart/saved", "docs/saved.md", "Save carts for later", LINUS, 19);
            branchWith(git, "feature/accounts", "docs/accounts.md", "Describe account settings", GRACE, 18);
            branchWith(git, "feature/reports", "docs/reports.md", "Weekly sales report", ADA, 17);
            branchWith(git, "bugfix/rounding", "src/main/java/com/shop/payments/Rounding.java",
                    "Round totals to cents with banker's rounding", LINUS, 16);

            git.merge().include(git.getRepository().resolve("feature/payments/refunds"))
                    .setMessage("Merge branch 'feature/payments/refunds'").setFastForward(
                            org.eclipse.jgit.api.MergeCommand.FastForwardMode.NO_FF).call();
            git.merge().include(git.getRepository().resolve("bugfix/rounding"))
                    .setMessage("Merge branch 'bugfix/rounding'").setFastForward(
                            org.eclipse.jgit.api.MergeCommand.FastForwardMode.NO_FF).call();
            git.tag().setName("v1.1").call();
            git.branchCreate().setName("release/2.1").call();

            // Two commits the remote will have and this clone will not: pushed, then undone here.
            append("README.md", "\n## Payments\n\nCharges go through the gateway.\n");
            commit(git, "Document payments", ADA, 6);
            append("README.md", "\n## Refunds\n\nRefunds are logged for audit.\n");
            commit(git, "Document refunds", GRACE, 5);
            git.push().setRemote("origin").add("main").call();
            StoredConfig tracking = git.getRepository().getConfig();
            tracking.setString("branch", "main", "remote", "origin");
            tracking.setString("branch", "main", "merge", "refs/heads/main");
            tracking.save();
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD~2").call();

            // One of its own, so main is both ahead and behind.
            append("src/main/java/com/shop/payments/PaymentService.java", "// audit log\n");
            commit(git, "Log payments for audit", ADA, 2);

            append("README.md", "wip notes\n");
            git.stashCreate().setWorkingDirectoryMessage("Half-finished README rewrite").call();

            write("src/main/java/com/shop/payments/PaymentService.java",
                    Files.readString(work.resolve("src/main/java/com/shop/payments/PaymentService.java"))
                            .replace("return gateway.charge(order.customer(), total);",
                                    "validate(order);\n        return gateway.charge(order.customer(), total);"));
            append(".gitignore", "*.tmp\n");
            append("src/main/java/com/shop/payments/RefundService.java", "// partial refunds\n");
            append("README.md", "- Supports refunds\n");
            git.add().addFilepattern("README.md").call();
            write("docs/CHANGELOG.md", "# Changelog\n");
        }
    }

    private void branchWith(Git git, String branch, String file, String message, String[] who, int daysAgo)
            throws Exception {
        String current = git.getRepository().getBranch();
        git.checkout().setCreateBranch(true).setName(branch).call();
        write(file, "// " + message + "\n");
        commit(git, message, who, daysAgo);
        git.checkout().setName(current).call();
    }

    private void commit(Git git, String message, String[] who, int daysAgo) throws Exception {
        git.add().addFilepattern(".").call();
        PersonIdent ident = ident(who, daysAgo);
        git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).setSign(false).call();
    }

    private static final String[] ADA = {"Ada Lovelace", "ada@example.com"};
    private static final String[] GRACE = {"Grace Hopper", "grace@example.com"};
    private static final String[] LINUS = {"Linus Torvalds", "linus@example.com"};

    private static PersonIdent ident(String[] who, int daysAgo) {
        Instant when = Instant.now().minus(Duration.ofDays(daysAgo)).minus(Duration.ofHours(3));
        return new PersonIdent(who[0], who[1], when, ZoneOffset.UTC);
    }

    private void write(String path, String content) throws Exception {
        Path file = work.resolve(path);
        Files.createDirectories(file.getParent() == null ? work : file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void append(String path, String content) throws Exception {
        Path file = work.resolve(path);
        write(path, (Files.exists(file) ? Files.readString(file) : "") + content);
    }

    private static String paymentService(String extra) {
        return String.join("\n", List.of(
                "package com.shop.payments;",
                "",
                "import java.math.BigDecimal;",
                "import java.util.Objects;",
                "",
                "public class PaymentService {",
                "    private final Gateway gateway;",
                "",
                "    public PaymentService(Gateway gateway) {",
                "        this.gateway = Objects.requireNonNull(gateway);",
                "    }",
                "",
                "    public Receipt charge(Order order) {",
                "        BigDecimal total = order.total();",
                "        return gateway.charge(order.customer(), total);",
                "    }",
                "}",
                extra));
    }
}
