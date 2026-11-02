package com.jfx.git.ui.settings;

import com.jfx.git.core.GitFlow;
import com.jfx.git.core.GitService;
import com.jfx.git.core.RepositoryPreferences;
import com.jfx.git.core.TextEncoding;
import com.jfx.git.model.PullMode;
import com.jfx.git.testsupport.LayoutAssert;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.RobotWait;
import com.jfx.git.ui.SettingsSection;

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings' Repository section, on real repositories: empty with none open, following
 * the picker, and writing what it shows - the per-repository preferences, the commit
 * template, the hooks directory and Git Flow - where git and the application read them.
 */
class RepositoryPageTest {

    @TempDir
    Path tempDir;

    private final List<GitService> services = new ArrayList<>();
    private final List<File> repositories = new ArrayList<>();
    private SettingsTab tab;

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        FxHarness.run(() -> {
            if (tab != null) tab.dispose();
        });
        FxHarness.closeShownStages();
        repositories.forEach(RepositoryPreferences::clear);
        services.forEach(GitService::close);
    }

    /** A repository with one commit on main, opened by the JGit engine. */
    private GitService repository(String name) throws Exception {
        Path dir = tempDir.resolve(name);
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture();
             Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            StoredConfig config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.writeString(dir.resolve("a.txt"), "a\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").setSign(false).call();
        }
        GitService service = new GitService();
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            assertTrue(service.openRepository(dir.toFile()));
        }
        services.add(service);
        repositories.add(dir.toFile());
        return service;
    }

    /** The repository's local config as git would read it now, from disk. */
    private static String localConfig(File workTree, String section, String name) throws Exception {
        try (var repo = new FileRepositoryBuilder().setWorkTree(workTree).setGitDir(new File(workTree, ".git"))
                .build()) {
            return repo.getConfig().getString(section, null, name);
        }
    }

    /** A Settings tab over {@code targets}, shown, at the Repository section; returns the page. */
    private Parent pageOver(List<RepositoryPage.Target> targets, File current) {
        return FxHarness.call(() -> {
            tab = new SettingsTab(null, null, List::of, new RepositoryPage.Repositories() {
                @Override
                public List<RepositoryPage.Target> open() {
                    return targets;
                }

                @Override
                public File current() {
                    return current;
                }
            });
            TabPane shown = SettingsTabs.show(tab);
            SettingsTabs.at(shown, tab, SettingsSection.REPOSITORY);
            return tab.page(SettingsSection.REPOSITORY);
        });
    }

    private Parent pageOn(GitService service) {
        Parent page = pageOver(List.of(RepositoryPage.Target.of(service)), service.getWorkTree());
        // Every part reads the repository as it is built; let those reads land.
        RobotWait.drainGitWork();
        return page;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Node> T find(Parent page, String id) {
        return FxHarness.call(() -> {
            Node node = page.lookup("#" + id);
            assertNotNull(node, "no #" + id + " on the page");
            return (T) node;
        });
    }

    // ------------------------------------------------------------------

    @Test
    void withNoRepositoryOpenThePageSaysSoAndShowsNothingElse() {
        String said = FxHarness.call(() -> {
            tab = new SettingsTab();
            TabPane shown = SettingsTabs.show(tab);
            Parent page = SettingsTabs.at(shown, tab, SettingsSection.REPOSITORY);
            assertNull(page.lookup("#" + RepositoryPage.PICKER_ID), "no repository to pick");
            assertNull(page.lookup("#" + RepositoryPage.PULL_ID));
            return ((Label) tab.page(SettingsSection.REPOSITORY).lookup("#" + RepositoryPage.EMPTY_ID)).getText();
        });
        assertEquals("Open a repository to change its settings.", said);
    }

    @Test
    void thePickerStartsOnTheRepositoryLastWorkedInAndFollowsTheChoice() throws Exception {
        GitService first = repository("first");
        GitService second = repository("second");
        RepositoryPage.Target one = RepositoryPage.Target.of(first);
        RepositoryPage.Target two = RepositoryPage.Target.of(second);
        Parent page = pageOver(List.of(one, two), second.getWorkTree());
        RobotWait.drainGitWork();

        ComboBox<RepositoryPage.Target> picker = find(page, RepositoryPage.PICKER_ID);
        FxHarness.run(() -> {
            assertEquals(List.of(one, two), List.copyOf(picker.getItems()));
            assertSame(two, picker.getValue(), "the repository the user was last in");
        });

        ChoiceBox<Object> secondsPull = find(page, RepositoryPage.PULL_ID);
        FxHarness.run(() -> picker.setValue(one));
        RobotWait.drainGitWork();
        ChoiceBox<Object> firstsPull = find(page, RepositoryPage.PULL_ID);
        assertFalse(secondsPull == firstsPull, "the parts were rebuilt for the other repository");

        FxHarness.run(() -> firstsPull.setValue(PullMode.REBASE));
        assertEquals(java.util.Optional.of(PullMode.REBASE), RepositoryPreferences.pullMode(first.getWorkTree()));
        assertEquals(java.util.Optional.empty(), RepositoryPreferences.pullMode(second.getWorkTree()),
                "written for the repository picked, not the one the page opened on");

        FxHarness.run(() -> tab.selectRepository(second.getWorkTree()));
        FxHarness.run(() -> assertSame(two, picker.getValue(), "opened on a repository, the page moves to it"));
    }

    /** "Use the global settings" forgets every override, the ones this page added included. */
    @Test
    void usingTheGlobalSettingsClearsEveryOverride() throws Exception {
        GitService service = repository("repo");
        File workTree = service.getWorkTree();
        Parent page = pageOn(service);

        CheckBox skip = find(page, RepositoryPage.SKIP_HOOKS_ID);
        ChoiceBox<TextEncoding> encoding = find(page, RepositoryPage.ENCODING_ID);
        Button reset = find(page, RepositoryPage.RESET_ID);
        FxHarness.run(() -> {
            assertTrue(reset.isDisabled(), "nothing to reset yet");
            skip.setSelected(true);
            encoding.setValue(TextEncoding.selectable().stream()
                    .filter(e -> e != TextEncoding.GUESS && e.isAvailable()).findFirst().orElseThrow());
        });
        assertTrue(RepositoryPreferences.skipHooksByDefault(workTree), "written the moment it changes");
        assertFalse(RepositoryPreferences.defaultEncoding(workTree) == TextEncoding.GUESS);
        assertTrue(RepositoryPreferences.hasOverrides(workTree));

        FxHarness.run(() -> {
            assertFalse(reset.isDisabled());
            reset.fire();
            assertFalse(skip.isSelected());
            assertEquals(TextEncoding.GUESS, encoding.getValue());
        });
        assertFalse(RepositoryPreferences.skipHooksByDefault(workTree));
        assertEquals(TextEncoding.GUESS, RepositoryPreferences.defaultEncoding(workTree));
        assertFalse(RepositoryPreferences.hasOverrides(workTree));
    }

    @Test
    void theCommitTemplateIsWrittenAndNamedInTheLocalConfigThenUnset() throws Exception {
        GitService service = repository("repo");
        File workTree = service.getWorkTree();
        Parent page = pageOn(service);

        CheckBox apply = find(page, RepositoryPage.TEMPLATE_APPLY_ID);
        TextField summary = find(page, RepositoryPage.TEMPLATE_SUMMARY_ID);
        TextArea description = find(page, RepositoryPage.TEMPLATE_DESCRIPTION_ID);
        Button save = find(page, RepositoryPage.TEMPLATE_SAVE_ID);
        FxHarness.run(() -> {
            assertFalse(apply.isSelected(), "the repository names no template");
            summary.setText("feat: ");
            description.setText("Why:\n# lines like this are left out");
            save.fire();
        });

        RobotWait.until("commit.template was never set", () -> {
            try {
                return localConfig(workTree, "commit", "template") != null;
            } catch (Exception e) {
                return false;
            }
        });
        File file = new File(localConfig(workTree, "commit", "template"));
        assertEquals(new File(workTree, ".git/" + RepositoryPage.TEMPLATE_FILE).getCanonicalFile(),
                file.getCanonicalFile(), "written beside the repository's own config");
        String expected = "feat:\n\nWhy:\n# lines like this are left out\n";
        assertEquals(expected, Files.readString(file.toPath(), StandardCharsets.UTF_8));
        RobotWait.drainGitWork();
        FxHarness.run(() -> assertTrue(apply.isSelected(), "applied once saved"));
        assertEquals(expected, service.getCommitTemplate(),
                "and git reads it back as the template");

        FxHarness.run(() -> apply.setSelected(false));
        RobotWait.until("commit.template was never unset", () -> {
            try {
                return localConfig(workTree, "commit", "template") == null;
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(file.isFile(), "the file is kept for ticking the box again");
    }

    @Test
    void theHooksDirectoryIsSetAndUnsetInTheLocalConfig() throws Exception {
        GitService service = repository("repo");
        File workTree = service.getWorkTree();
        Parent page = pageOn(service);

        TextField path = find(page, RepositoryPage.HOOKS_PATH_ID);
        Button save = find(page, RepositoryPage.HOOKS_SAVE_ID);
        FxHarness.run(() -> {
            assertEquals("", path.getText(), "blank: the repository's own .git/hooks");
            path.setText("tools/hooks");
            save.fire();
        });
        RobotWait.until("core.hooksPath was never set", () -> {
            try {
                return "tools/hooks".equals(localConfig(workTree, "core", "hooksPath"));
            } catch (Exception e) {
                return false;
            }
        });
        RobotWait.drainGitWork();

        FxHarness.run(() -> {
            path.setText("  ");
            save.fire();
        });
        RobotWait.until("core.hooksPath was never unset", () -> {
            try {
                return localConfig(workTree, "core", "hooksPath") == null;
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    void skippingHooksByDefaultIsKeptForTheRepository() throws Exception {
        GitService service = repository("repo");
        Parent page = pageOn(service);
        CheckBox skip = find(page, RepositoryPage.SKIP_HOOKS_ID);

        FxHarness.run(() -> skip.setSelected(true));
        assertTrue(RepositoryPreferences.skipHooksByDefault(service.getWorkTree()));
        FxHarness.run(() -> skip.setSelected(false));
        assertFalse(RepositoryPreferences.skipHooksByDefault(service.getWorkTree()));
        assertFalse(RepositoryPreferences.hasOverrides(service.getWorkTree()), "off is the same as never set");
    }

    @Test
    void gitFlowIsInitializedFromThePage() throws Exception {
        GitService service = repository("repo");
        Parent page = pageOn(service);

        Button save = find(page, RepositoryPage.FLOW_SAVE_ID);
        ChoiceBox<String> production = find(page, RepositoryPage.FLOW_PRODUCTION_ID);
        TextField develop = find(page, RepositoryPage.FLOW_DEVELOP_ID);
        FxHarness.run(() -> {
            assertEquals("Initialize", save.getText(), "not set up yet");
            assertFalse(save.isDisabled());
            assertEquals("main", production.getValue());
            assertEquals("develop", develop.getText(), "git-flow's own default");
            develop.setText("dev");
            save.fire();
        });

        RobotWait.drainGitWork();
        GitFlow.Config config = new GitFlow(service).config();
        assertNotNull(config, "set up");
        assertEquals("main", config.production());
        assertEquals("dev", config.develop());
        assertTrue(service.getLocalBranches().stream()
                .anyMatch(b -> b.shortName().equals("dev")), "the develop branch was created");
        FxHarness.run(() -> assertEquals("Save", save.getText(), "set up, so the button saves now"));
    }

    /** On a real repository, every part fits: nothing cut short, nothing scrolling sideways. */
    @Test
    void aRepositorysPageFitsTheTab() throws Exception {
        GitService service = repository("repo");
        pageOn(service);

        List<String> problems = FxHarness.call(() -> {
            TabPane shown = (TabPane) tab.getTabPane();
            Parent root = SettingsTabs.at(shown, tab, SettingsSection.REPOSITORY);
            List<String> found = new ArrayList<>(LayoutAssert.truncatedTexts(root));
            for (Node node : LayoutAssert.visibleNodes(root, n -> n instanceof ScrollBar)) {
                ScrollBar bar = (ScrollBar) node;
                if (bar.getOrientation() == Orientation.HORIZONTAL
                        && bar.getVisibleAmount() < bar.getMax() - bar.getMin()) {
                    found.add("scrolls sideways: " + bar.getParent());
                }
            }
            return found;
        });
        LayoutAssert.failWith("the Repository section on a repository", problems);
    }
}
