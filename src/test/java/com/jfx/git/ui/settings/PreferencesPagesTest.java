package com.jfx.git.ui.settings;

import com.jfx.git.core.AppPreferences;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.SettingsSection;
import com.jfx.git.ui.ThemeManager;

import javafx.scene.Parent;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextField;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A settings screen fails in two ways worth testing: it throws while being built, or
 * a control is shown that does not correspond to anything stored. These are the six
 * pages that were the Preferences window, read as sections of the Settings tab.
 */
class PreferencesPagesTest {

    private SettingsTab tab;

    @AfterEach
    void restoreDefaults() {
        FxHarness.run(() -> {
            AppPreferences.setEditorMode(AppPreferences.EditorMode.BUILT_IN);
            AppPreferences.setEditorCommand("");
            AppPreferences.setDiffToolMode(com.jfx.git.core.ExternalTools.Mode.NONE);
            AppPreferences.setDiffToolCommand("");
            AppPreferences.setMergeToolMode(com.jfx.git.core.ExternalTools.Mode.NONE);
            AppPreferences.setMergeToolCommand("");
            AppPreferences.setTerminalCommand("");
            if (tab != null) tab.dispose();
        });
        FxHarness.closeShownStages();
    }

    /** A Settings tab on screen at {@code section}; that section's page, laid out. Call on the FX thread. */
    private Parent pageOn(SettingsSection section) {
        tab = new SettingsTab();
        TabPane shown = SettingsTabs.show(tab);
        SettingsTabs.at(shown, tab, section);
        return tab.page(section);
    }

    @Test
    void theTerminalCommandIsStoredAsItIsTyped() {
        FxHarness.start();

        FxHarness.run(() -> {
            Parent page = pageOn(SettingsSection.EXTERNAL_TOOLS);

            TextField terminal = (TextField) page.lookup("#terminal-command");
            assertTrue(showing(terminal), "there is always a terminal to choose, so the box is always there");
            assertEquals("", terminal.getText(), "blank means the best one installed");
            terminal.setText("wt -d {dir}");
        });

        assertEquals("wt -d {dir}", AppPreferences.terminalCommand());
    }

    /**
     * A command line means something only for a custom tool. "None" and "the tool git is
     * configured with" need none, and showing an empty box under them asks a question
     * with no answer.
     */
    @Test
    void aToolsCommandIsAskedForOnlyWhenTheToolIsCustom() {
        FxHarness.start();

        FxHarness.run(() -> {
            Parent page = pageOn(SettingsSection.EXTERNAL_TOOLS);

            TextField diff = (TextField) page.lookup("#diff-tool-command");
            TextField merge = (TextField) page.lookup("#merge-tool-command");
            assertFalse(showing(diff), "no tool is chosen yet, so there is no command to give");
            assertFalse(showing(merge));

            @SuppressWarnings("unchecked")
            List<ChoiceBox<Object>> choices = FxHarness
                    .lookupAll(page, "choice-box").stream()
                    .map(n -> (ChoiceBox<Object>) n)
                    .filter(c -> c.getItems().contains(com.jfx.git.core.ExternalTools.Mode.CUSTOM))
                    .toList();
            assertEquals(2, choices.size(), "one choice for the diff tool and one for the merge tool");

            choices.get(0).setValue(com.jfx.git.core.ExternalTools.Mode.CUSTOM);
            assertTrue(showing(diff));
            assertFalse(showing(merge), "the two are chosen separately");
            diff.setText("meld $LOCAL $REMOTE");

            choices.get(1).setValue(com.jfx.git.core.ExternalTools.Mode.GIT_CONFIG);
            assertFalse(showing(merge), "git's own configuration names the tool, so there is nothing to type");
        });

        assertEquals(com.jfx.git.core.ExternalTools.Mode.CUSTOM, AppPreferences.diffToolMode());
        assertEquals("meld $LOCAL $REMOTE", AppPreferences.diffToolCommand());
        assertEquals(com.jfx.git.core.ExternalTools.Mode.GIT_CONFIG, AppPreferences.mergeToolMode());
    }

    @Test
    void theToolChoicesStartOnTheStoredValues() {
        FxHarness.start();
        FxHarness.run(() -> {
            AppPreferences.setMergeToolMode(com.jfx.git.core.ExternalTools.Mode.CUSTOM);
            AppPreferences.setMergeToolCommand("kdiff3 $BASE $LOCAL $REMOTE -o $MERGED");
        });

        FxHarness.run(() -> {
            Parent page = pageOn(SettingsSection.EXTERNAL_TOOLS);

            TextField merge = (TextField) page.lookup("#merge-tool-command");
            assertTrue(showing(merge));
            assertEquals("kdiff3 $BASE $LOCAL $REMOTE -o $MERGED", merge.getText());
        });
    }

    /** Every section is listed, in order, under its own name - and every page builds. */
    @Test
    void everySectionIsListedAndEveryPageBuilds() {
        FxHarness.start();

        List<String> listed = FxHarness.call(() -> {
            tab = new SettingsTab();
            for (SettingsSection section : SettingsSection.values()) {
                assertNotNull(tab.page(section), section + " should build");
            }
            return SettingsTabs.nav(tab).getItems().stream().map(SettingsSection::label).toList();
        });

        assertEquals(Arrays.stream(SettingsSection.values()).map(SettingsSection::label).toList(), listed);
        assertEquals(List.of("General", "Appearance", "Git Engine", "External Tools", "Diff", "SSH"),
                listed.subList(0, 6), "the six pages that were Preferences come first, as they were");
    }

    @Test
    void theSshPageOffersToForgetCredentials() {
        FxHarness.start();

        boolean present = FxHarness.call(() -> FxHarness.lookupAll(pageOn(SettingsSection.SSH), "button").stream()
                .filter(n -> n instanceof javafx.scene.control.Button)
                .map(n -> ((javafx.scene.control.Button) n).getText())
                .anyMatch("Forget all credentials"::equals));

        assertTrue(present,
                "the SSH page must expose CredentialsManager.clear() to the user");
    }

    /** Forgetting says it is done in the window's status line: there is nothing to answer. */
    @Test
    void forgettingCredentialsIsSaidWithoutADialog() {
        FxHarness.start();
        List<String> said = new java.util.ArrayList<>();

        int windowsAfter = FxHarness.call(() -> {
            tab = new SettingsTab(null, null, List::of, null, said::add);
            TabPane shown = SettingsTabs.show(tab);
            SettingsTabs.at(shown, tab, SettingsSection.SSH);
            int before = javafx.stage.Window.getWindows().size();
            FxHarness.lookupAll(tab.page(SettingsSection.SSH), "button").stream()
                    .filter(n -> n instanceof javafx.scene.control.Button b
                            && "Forget all credentials".equals(b.getText()))
                    .map(javafx.scene.control.Button.class::cast)
                    .findFirst().orElseThrow().fire();
            return javafx.stage.Window.getWindows().size() - before;
        });

        assertEquals(List.of("Cached credentials forgotten: the next fetch or push will prompt again"), said);
        assertEquals(0, windowsAfter, "and no dialog opened");
    }

    @Test
    void theEditorChoiceStartsOnTheStoredValue() {
        FxHarness.start();
        FxHarness.run(() -> AppPreferences.setEditorMode(AppPreferences.EditorMode.SYSTEM_DEFAULT));

        Object selected = FxHarness.call(() -> {
            ChoiceBox<?> mode = (ChoiceBox<?>) FxHarness
                    .lookupAll(pageOn(SettingsSection.EXTERNAL_TOOLS), "choice-box").get(0);
            return mode.getValue();
        });

        assertEquals(AppPreferences.EditorMode.SYSTEM_DEFAULT, selected);
    }

    @Test
    void changingTheEditorCommandIsStoredAsItIsTyped() {
        FxHarness.start();

        FxHarness.run(() -> {
            TextField command = (TextField) FxHarness
                    .lookupAll(pageOn(SettingsSection.EXTERNAL_TOOLS), "text-field").get(0);
            command.setText("code --wait " + AppPreferences.FILE_TOKEN);
        });

        assertEquals("code --wait " + AppPreferences.FILE_TOKEN, AppPreferences.editorCommand());
    }

    @Test
    void theCustomCommandRowIsHiddenUnlessACustomEditorIsChosen() {
        FxHarness.start();
        FxHarness.run(() -> AppPreferences.setEditorMode(AppPreferences.EditorMode.BUILT_IN));

        // The editor's command box: the first field on the page. The page holds others -
        // the terminal's is always shown - so "no field is showing" is not what this is about.
        boolean visible = FxHarness.call(() ->
                showing(FxHarness.lookupAll(pageOn(SettingsSection.EXTERNAL_TOOLS), "text-field").get(0)));

        assertFalse(visible, "the command box only applies to a custom editor");
    }

    /**
     * A node is only really on screen if every ancestor is visible too - it is the row
     * that gets hidden here, not the field itself.
     */
    private static boolean showing(javafx.scene.Node node) {
        for (javafx.scene.Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private String labelsOn(SettingsSection section) {
        return FxHarness.call(() -> {
            StringBuilder all = new StringBuilder();
            for (javafx.scene.Node node : FxHarness.lookupAll(pageOn(section), "label")) {
                all.append(((javafx.scene.control.Label) node).getText()).append('\n');
            }
            return all.toString();
        });
    }

    @Test
    void theSshPageReportsSomething() {
        FxHarness.start();

        String text = labelsOn(SettingsSection.SSH);

        assertNotNull(text);
        assertTrue(text.contains("ssh-agent") || text.contains("SSH"), text);
    }

    /**
     * The engine page's LFS line is environment-dependent (whether git-lfs happens to
     * be installed and initialised on the machine running this test), so this checks
     * only what is true regardless of that state: the line exists, and it says plainly
     * that JGit cannot materialise LFS content whatever the machine's own setup is.
     */
    @Test
    void theGitEnginePageNamesWhichEngineCanMaterializeLfsContent() {
        FxHarness.start();

        String text = labelsOn(SettingsSection.GIT_ENGINE);

        assertTrue(text.contains("Git LFS"), text);
        assertTrue(text.contains("JGit cannot materialise LFS content"), text);
    }

    @Test
    void theUpdateFeedAndItsSwitchAreStoredAsTheyAreChanged() {
        FxHarness.start();
        String before = AppPreferences.updateFeedUrl();
        boolean enabled = AppPreferences.updateCheckEnabled();
        try {
            FxHarness.run(() -> {
                Parent page = pageOn(SettingsSection.GENERAL);

                TextField feed = (TextField) page.lookup("#update-feed");
                feed.setText(" https://example.com/latest ");
                FxHarness.lookupAll(page, "check-box").stream()
                        .map(n -> (javafx.scene.control.CheckBox) n)
                        .filter(box -> box.getText().startsWith("Look for a newer version"))
                        .findFirst().orElseThrow().setSelected(false);
            });

            assertEquals("https://example.com/latest", AppPreferences.updateFeedUrl(), "stored trimmed");
            assertFalse(AppPreferences.updateCheckEnabled());
        } finally {
            AppPreferences.setUpdateFeedUrl(before);
            AppPreferences.setUpdateCheckEnabled(enabled);
        }
    }

    /**
     * A theme of the user's own is chosen beside the built-in one, applied at once, and
     * what is wrong with it is said under the picker rather than discovered by squinting.
     */
    @Test
    void aCustomThemeIsListedAppliedAndItsProblemsShown(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        FxHarness.start();
        // The themes folder sits beside the settings file, so moving one moves the other.
        com.jfx.git.core.Settings.useFile(dir.resolve("settings.properties"));
        try {
            java.nio.file.Files.createDirectories(dir.resolve("themes"));
            java.nio.file.Files.writeString(dir.resolve("themes").resolve("mine.css"),
                    "/* base: light */ .root { -jfx-attention: #010203; -jfx-nope: red; }");

            FxHarness.run(() -> {
                Parent page = pageOn(SettingsSection.APPEARANCE);

                @SuppressWarnings("unchecked")
                ChoiceBox<String> custom = (ChoiceBox<String>) page.lookup("#custom-theme");
                javafx.scene.control.Label problems = (javafx.scene.control.Label)
                        page.lookup("#custom-theme-problems");
                assertEquals(List.of("None", "mine"), List.copyOf(custom.getItems()));
                assertEquals("None", custom.getValue());
                assertFalse(problems.isVisible(), "nothing chosen, nothing to report");

                custom.setValue("mine");
                assertEquals("mine", ThemeManager.currentUserTheme().name(), "applied as it is chosen");
                assertEquals(ThemeManager.Theme.LIGHT, ThemeManager.current(), "over the theme it names");
                assertTrue(problems.isVisible());
                assertTrue(problems.getText().contains("-jfx-nope"), problems.getText());

                @SuppressWarnings("unchecked")
                ChoiceBox<ThemeManager.Theme> builtIn = FxHarness
                        .lookupAll(page, "choice-box").stream()
                        .map(n -> (ChoiceBox<ThemeManager.Theme>) n)
                        .filter(c -> c.getItems().contains(ThemeManager.Theme.LIGHT))
                        .findFirst().orElseThrow();
                assertEquals(ThemeManager.Theme.LIGHT, builtIn.getValue(),
                        "the built-in choice follows the theme the custom one is laid over");

                builtIn.setValue(ThemeManager.Theme.DARK);
                assertEquals("None", custom.getValue(), "a built-in theme chosen by hand is that theme as it is");
                assertTrue(ThemeManager.currentUserTheme() == null);
                assertFalse(problems.isVisible());
            });
        } finally {
            FxHarness.run(() -> ThemeManager.apply(ThemeManager.Theme.DARK));
            com.jfx.git.core.Settings.useFile(null);
        }
    }

    /**
     * Every built-in theme is in the picker, the dark ones above a rule and the light ones
     * below it, each named for what it is; choosing one applies it to a window that is
     * already open, and remembers it.
     */
    @Test
    void theBuiltInPickerListsEveryThemeGroupedAndAppliesTheChosenOneLive(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        FxHarness.start();
        com.jfx.git.core.Settings.useFile(dir.resolve("settings.properties"));
        try {
            FxHarness.run(() -> {
                ThemeManager.apply(ThemeManager.Theme.DARK);
                Parent page = pageOn(SettingsSection.APPEARANCE);

                @SuppressWarnings("unchecked")
                ChoiceBox<Object> builtIn = (ChoiceBox<Object>) page.lookup("#built-in-theme");
                List<Object> items = List.copyOf(builtIn.getItems());

                // Every theme once, in order, and a single rule between the two groups.
                assertEquals(List.of(ThemeManager.Theme.values()),
                        items.stream().filter(ThemeManager.Theme.class::isInstance)
                                .map(ThemeManager.Theme.class::cast).toList());
                int rule = -1;
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i) instanceof javafx.scene.control.Separator) {
                        assertEquals(-1, rule, "more than one rule");
                        rule = i;
                    }
                }
                assertTrue(rule > 0 && rule < items.size() - 1, "no rule between the groups");
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i) instanceof ThemeManager.Theme theme) {
                        assertEquals(i < rule ? ThemeManager.Base.DARK : ThemeManager.Base.LIGHT, theme.base(),
                                theme + " is in the wrong group");
                        assertTrue(builtIn.getConverter().toString(theme).toLowerCase().contains(theme.base().id()),
                                "the picker does not say " + theme + " is " + theme.base().id());
                    }
                }
                assertEquals(ThemeManager.Theme.DARK, builtIn.getValue());

                // A window that is already open, wearing a token the themes disagree about.
                javafx.scene.layout.Region ground = new javafx.scene.layout.Region();
                ground.setStyle("-fx-background-color: -jfx-list-bg;");
                javafx.scene.layout.StackPane pane = new javafx.scene.layout.StackPane(ground);
                FxHarness.show(pane, 100, 100);
                ThemeManager.register(pane.getScene());
                pane.applyCss();
                javafx.scene.paint.Color before = (javafx.scene.paint.Color) ground.getBackground().getFills().get(0).getFill();

                builtIn.setValue(ThemeManager.Theme.DARK_GREEN);
                pane.applyCss();
                assertEquals(ThemeManager.Theme.DARK_GREEN, ThemeManager.current());
                assertEquals("DARK_GREEN", com.jfx.git.core.Settings.get("ui.theme", ""));
                javafx.scene.paint.Color after = (javafx.scene.paint.Color) ground.getBackground().getFills().get(0).getFill();
                assertEquals(javafx.scene.paint.Color.web("#0d1117"), before);
                assertEquals(javafx.scene.paint.Color.web("#111216"), after, "the open window took the new theme");
            });
        } finally {
            FxHarness.run(() -> ThemeManager.apply(ThemeManager.Theme.DARK));
            com.jfx.git.core.Settings.useFile(null);
            FxHarness.closeShownStages();
        }
    }

    /** Opening a section that has been opened before shows the same page, not a rebuilt one. */
    @Test
    void aPageIsBuiltOnceAndKept() {
        FxHarness.start();
        FxHarness.run(() -> {
            Parent first = pageOn(SettingsSection.DIFF);
            tab.select(SettingsSection.SSH);
            tab.select(SettingsSection.DIFF);
            assertTrue(first == tab.page(SettingsSection.DIFF), "the page the user left is the page they return to");
            assertEquals(SettingsSection.DIFF, tab.selectedSection());
        });
    }
}
