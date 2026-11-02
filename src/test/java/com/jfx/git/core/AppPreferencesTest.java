package com.jfx.git.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The part of the settings that can be got wrong quietly is the custom editor
 * command: a path with spaces, or a missing {@code $FILE}, produces an editor that
 * opens on the wrong thing rather than an error.
 */
class AppPreferencesTest {

    @AfterEach
    void restoreDefaults() {
        AppPreferences.setEditorMode(AppPreferences.EditorMode.BUILT_IN);
        AppPreferences.setEditorCommand("");
        AppPreferences.setSplitDiffByDefault(false);
        AppPreferences.setWholeFileByDefault(false);
        AppPreferences.setDiffScrollMapWidth(0);
        AppPreferences.setScrollBeyondLastLine(true);
        AppPreferences.setEditorMinimap(true);
        AppPreferences.setColourblindPalette(false);
        AppPreferences.setConfirmDiscard(true);
        AppPreferences.setGraphHiddenBranches(null);
        AppPreferences.setGraphSoloBranch(null);
        AppPreferences.setHiddenStashes(null);
        AppPreferences.setInitialGraphCommits(0);
        AppPreferences.setShowAllCommitsInGraph(false);
        AppPreferences.setDefaultBranchName(null);
        AppPreferences.setAutoFetchIntervalMinutes(0);
        AppPreferences.setAutoUpdateSubmodules(false);
        // Back to "never chosen" and the real platform question, not to an explicit false:
        // the next test starts the way a fresh install does.
        Settings.remove("ui.reduceMotion");
        AppPreferences.usePlatformReducedMotion(null);
    }

    @Test
    void theBuiltInEditorIsTheDefault() {
        AppPreferences.setEditorCommand("");
        assertEquals(AppPreferences.EditorMode.BUILT_IN, AppPreferences.editorMode());
    }

    @Test
    void theEditorChoiceIsRemembered() {
        AppPreferences.setEditorMode(AppPreferences.EditorMode.SYSTEM_DEFAULT);
        assertEquals(AppPreferences.EditorMode.SYSTEM_DEFAULT, AppPreferences.editorMode());
    }

    @Test
    void everyModeHasALabel() {
        for (AppPreferences.EditorMode mode : AppPreferences.EditorMode.values()) {
            assertFalse(mode.label().isBlank());
            assertEquals(mode.label(), mode.toString());
        }
    }

    @Test
    void theFileTokenIsSubstituted() {
        List<String> command = AppPreferences.buildEditorCommand(
                "code --wait " + AppPreferences.FILE_TOKEN, new File("/tmp/a.java"));

        assertEquals(3, command.size());
        assertEquals("code", command.get(0));
        assertEquals("--wait", command.get(1));
        assertTrue(command.get(2).endsWith("a.java"));
    }

    @Test
    void aCommandWithoutTheTokenGetsThePathAppended() {
        List<String> command = AppPreferences.buildEditorCommand("notepad", new File("a.txt"));

        assertEquals(2, command.size());
        assertEquals("notepad", command.get(0));
        assertTrue(command.get(1).endsWith("a.txt"));
    }

    @Test
    void aQuotedProgramPathStaysOneArgument() {
        List<String> command = AppPreferences.buildEditorCommand(
                "\"C:\\Program Files\\Editor\\ed.exe\" -n " + AppPreferences.FILE_TOKEN,
                new File("a.txt"));

        assertEquals(3, command.size());
        assertEquals("C:\\Program Files\\Editor\\ed.exe", command.get(0));
        assertEquals("-n", command.get(1));
    }

    @Test
    void theTokenCanBeEmbeddedInAnArgument() {
        // Some editors take a "file:line" style argument rather than a bare path.
        List<String> command = AppPreferences.buildEditorCommand(
                "ed --goto " + AppPreferences.FILE_TOKEN + ":1", new File("a.txt"));

        assertEquals(3, command.size());
        assertTrue(command.get(2).endsWith("a.txt:1"), command.get(2));
    }

    @Test
    void anEmptyCommandProducesNothingToRun() {
        assertTrue(AppPreferences.buildEditorCommand("", new File("a.txt")).isEmpty());
        assertTrue(AppPreferences.buildEditorCommand("   ", new File("a.txt")).isEmpty());
        assertTrue(AppPreferences.buildEditorCommand(null, new File("a.txt")).isEmpty());
    }

    @Test
    void splittingHandlesRunsOfWhitespace() {
        assertEquals(List.of("a", "b", "c"), AppPreferences.splitCommand("a   b\tc"));
        assertEquals(List.of(), AppPreferences.splitCommand(""));
    }

    @Test
    void theDiffSettingsRoundTrip() {
        AppPreferences.setSplitDiffByDefault(true);
        AppPreferences.setWholeFileByDefault(true);
        AppPreferences.setColourblindPalette(true);
        AppPreferences.setConfirmDiscard(false);

        assertTrue(AppPreferences.splitDiffByDefault());
        assertTrue(AppPreferences.wholeFileByDefault());
        assertTrue(AppPreferences.colourblindPalette());
        assertFalse(AppPreferences.confirmDiscard());
    }

    @Test
    void wholeFileByDefaultStartsOff() {
        assertFalse(AppPreferences.wholeFileByDefault());
    }

    @Test
    void diffScrollMapWidthRoundTrips() {
        AppPreferences.setDiffScrollMapWidth(30);
        assertEquals(30, AppPreferences.diffScrollMapWidth());
    }

    @Test
    void diffScrollMapWidthHasASensibleWiderThanTheOriginalDefault() {
        assertTrue(AppPreferences.diffScrollMapWidth() > 10,
                "the original fixed 10px read as a hairline; the default should read as wider than that");
    }

    @Test
    void aZeroOrNegativeScrollMapWidthResetsToTheDefault() {
        int defaultWidth = AppPreferences.diffScrollMapWidth();

        AppPreferences.setDiffScrollMapWidth(30);
        AppPreferences.setDiffScrollMapWidth(0);
        assertEquals(defaultWidth, AppPreferences.diffScrollMapWidth(), "0 resets to the default");

        AppPreferences.setDiffScrollMapWidth(30);
        AppPreferences.setDiffScrollMapWidth(-5);
        assertEquals(defaultWidth, AppPreferences.diffScrollMapWidth(), "a negative width also resets to the default");
    }

    @Test
    void discardConfirmationDefaultsToOn() {
        AppPreferences.setConfirmDiscard(false);
        // Unset, as on a fresh install. By name: renaming the key would quietly turn the
        // question back on for everyone who had turned it off.
        Settings.remove("confirm.discard");
        assertTrue(AppPreferences.confirmDiscard(), "a fresh install still asks");
    }

    @Test
    void noBranchesAreHiddenOrSoloedByDefault() {
        assertTrue(AppPreferences.graphHiddenBranches().isEmpty());
        assertNull(AppPreferences.graphSoloBranch());
    }

    @Test
    void hiddenBranchesRoundTrip() {
        AppPreferences.setGraphHiddenBranches(
                java.util.Set.of("refs/heads/old-feature", "refs/heads/experiment"));

        var stored = AppPreferences.graphHiddenBranches();
        assertEquals(2, stored.size());
        assertTrue(stored.contains("refs/heads/old-feature"));
        assertTrue(stored.contains("refs/heads/experiment"));
    }

    @Test
    void clearingTheHiddenSetRemovesTheStoredKeyRatherThanStoringEmpty() {
        AppPreferences.setGraphHiddenBranches(java.util.Set.of("refs/heads/x"));
        AppPreferences.setGraphHiddenBranches(java.util.Set.of());
        assertTrue(AppPreferences.graphHiddenBranches().isEmpty());

        AppPreferences.setGraphHiddenBranches(java.util.Set.of("refs/heads/x"));
        AppPreferences.setGraphHiddenBranches(null);
        assertTrue(AppPreferences.graphHiddenBranches().isEmpty());
    }

    @Test
    void soloBranchRoundTrips() {
        AppPreferences.setGraphSoloBranch("refs/heads/release");
        assertEquals("refs/heads/release", AppPreferences.graphSoloBranch());

        AppPreferences.setGraphSoloBranch(null);
        assertNull(AppPreferences.graphSoloBranch());
    }

    @Test
    void noStashesAreHiddenByDefault() {
        assertTrue(AppPreferences.hiddenStashes().isEmpty());
    }

    @Test
    void hiddenStashesRoundTripByHash() {
        AppPreferences.setHiddenStashes(java.util.Set.of("abc123", "def456"));

        var stored = AppPreferences.hiddenStashes();
        assertEquals(2, stored.size());
        assertTrue(stored.contains("abc123"));
        assertTrue(stored.contains("def456"));
    }

    @Test
    void clearingTheHiddenStashSetRemovesTheStoredKeyRatherThanStoringEmpty() {
        AppPreferences.setHiddenStashes(java.util.Set.of("abc123"));
        AppPreferences.setHiddenStashes(java.util.Set.of());
        assertTrue(AppPreferences.hiddenStashes().isEmpty());

        AppPreferences.setHiddenStashes(java.util.Set.of("abc123"));
        AppPreferences.setHiddenStashes(null);
        assertTrue(AppPreferences.hiddenStashes().isEmpty());
    }

    // ------------------------------------------------------------------
    // General category: repository defaults, graph loading, auto-fetch
    // ------------------------------------------------------------------

    @Test
    void defaultBranchNameDefaultsToMain() {
        assertEquals("main", AppPreferences.defaultBranchName());
    }

    @Test
    void defaultBranchNameRoundTrips() {
        AppPreferences.setDefaultBranchName("trunk");
        assertEquals("trunk", AppPreferences.defaultBranchName());
    }

    @Test
    void clearingTheDefaultBranchNameFallsBackToMain() {
        AppPreferences.setDefaultBranchName("trunk");
        AppPreferences.setDefaultBranchName(null);
        assertEquals("main", AppPreferences.defaultBranchName());

        AppPreferences.setDefaultBranchName("trunk");
        AppPreferences.setDefaultBranchName("  ");
        assertEquals("main", AppPreferences.defaultBranchName());
    }

    @Test
    void initialGraphCommitsDefaultsToTheSharedGraphLimit() {
        assertEquals(GitService.DEFAULT_GRAPH_LIMIT, AppPreferences.initialGraphCommits());
    }

    @Test
    void initialGraphCommitsRoundTrips() {
        AppPreferences.setInitialGraphCommits(250);
        assertEquals(250, AppPreferences.initialGraphCommits());
    }

    @Test
    void aNonPositiveInitialGraphCommitsFallsBackToTheDefault() {
        AppPreferences.setInitialGraphCommits(250);
        AppPreferences.setInitialGraphCommits(0);
        assertEquals(GitService.DEFAULT_GRAPH_LIMIT, AppPreferences.initialGraphCommits());

        AppPreferences.setInitialGraphCommits(250);
        AppPreferences.setInitialGraphCommits(-5);
        assertEquals(GitService.DEFAULT_GRAPH_LIMIT, AppPreferences.initialGraphCommits());
    }

    @Test
    void showAllCommitsInGraphDefaultsToOff() {
        assertFalse(AppPreferences.showAllCommitsInGraph());
    }

    @Test
    void showAllCommitsInGraphRoundTrips() {
        AppPreferences.setShowAllCommitsInGraph(true);
        assertTrue(AppPreferences.showAllCommitsInGraph());
    }

    @Test
    void autoFetchIsOffByDefault() {
        assertEquals(0, AppPreferences.autoFetchIntervalMinutes());
    }

    @Test
    void autoFetchIntervalRoundTrips() {
        AppPreferences.setAutoFetchIntervalMinutes(15);
        assertEquals(15, AppPreferences.autoFetchIntervalMinutes());
    }

    @Test
    void aNegativeAutoFetchIntervalClampsToOff() {
        AppPreferences.setAutoFetchIntervalMinutes(-30);
        assertEquals(0, AppPreferences.autoFetchIntervalMinutes());
    }

    /**
     * Off by default on purpose - unlike auto-fetch, this runs unasked as a side
     * effect of a checkout or pull the user already triggered.
     */
    @Test
    void submoduleAutoUpdateIsOffByDefault() {
        assertFalse(AppPreferences.autoUpdateSubmodules());
    }

    @Test
    void submoduleAutoUpdateRoundTrips() {
        AppPreferences.setAutoUpdateSubmodules(true);
        assertTrue(AppPreferences.autoUpdateSubmodules());

        AppPreferences.setAutoUpdateSubmodules(false);
        assertFalse(AppPreferences.autoUpdateSubmodules());
    }

    @Test
    void aCustomEditorWithNoCommandIsReportedRatherThanRun() {
        AppPreferences.setEditorMode(AppPreferences.EditorMode.CUSTOM);
        AppPreferences.setEditorCommand("");

        GitResult result = FileOpener.openPreferred(new File("does-not-matter.txt"));
        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("Settings"), result.getMessage());
    }

    // ------------------------------------------------------------------
    // Code view: scroll past the last line, minimap
    // ------------------------------------------------------------------

    @Test
    void scrollBeyondLastLineIsOnByDefaultLikeEveryCodeEditor() {
        assertTrue(AppPreferences.scrollBeyondLastLine());
    }

    @Test
    void scrollBeyondLastLineRoundTrips() {
        AppPreferences.setScrollBeyondLastLine(false);
        assertFalse(AppPreferences.scrollBeyondLastLine());
        AppPreferences.setScrollBeyondLastLine(true);
        assertTrue(AppPreferences.scrollBeyondLastLine());
    }

    @Test
    void theMinimapIsOnByDefault() {
        assertTrue(AppPreferences.editorMinimap());
    }

    @Test
    void theMinimapPreferenceRoundTrips() {
        AppPreferences.setEditorMinimap(false);
        assertFalse(AppPreferences.editorMinimap());
        AppPreferences.setEditorMinimap(true);
        assertTrue(AppPreferences.editorMinimap());
    }

    // ------------------------------------------------------------------
    // Reduce motion: the system's setting until the user chooses
    // ------------------------------------------------------------------

    @Test
    void reduceMotionFollowsThePlatformUntilTheUserChooses() {
        Settings.remove("ui.reduceMotion");

        AppPreferences.usePlatformReducedMotion(() -> true);
        assertTrue(AppPreferences.reduceMotion(), "the system asks for less motion");

        AppPreferences.usePlatformReducedMotion(() -> false);
        assertFalse(AppPreferences.reduceMotion(), "and now it does not: asked again, not remembered");
    }

    @Test
    void anExplicitYesWinsOverAPlatformThatAsksForMotion() {
        AppPreferences.usePlatformReducedMotion(() -> false);

        AppPreferences.setReduceMotion(true);

        assertTrue(AppPreferences.reduceMotion());
    }

    /**
     * The case a defaulted boolean cannot tell apart from never having chosen: someone who
     * turned motion back on over a system that has it off must not be switched off again.
     */
    @Test
    void anExplicitNoWinsOverAPlatformThatAsksForLess() {
        AppPreferences.usePlatformReducedMotion(() -> true);

        AppPreferences.setReduceMotion(false);

        assertFalse(AppPreferences.reduceMotion());
    }

    @Test
    void thePlatformIsNotAskedOnceTheUserHasChosen() {
        AtomicInteger asked = new AtomicInteger();
        AppPreferences.usePlatformReducedMotion(() -> {
            asked.incrementAndGet();
            return true;
        });

        AppPreferences.setReduceMotion(false);
        AppPreferences.reduceMotion();

        assertEquals(0, asked.get());
    }

    /**
     * By name, as the discard confirmation's is: renaming the key would drop everyone's
     * choice back to the system's, quietly.
     */
    @Test
    void theChoiceIsStoredUnderTheKeyItAlwaysHasBeen() {
        AppPreferences.setReduceMotion(false);
        assertEquals("false", Settings.get("ui.reduceMotion", null));

        AppPreferences.setReduceMotion(true);
        assertEquals("true", Settings.get("ui.reduceMotion", null));
    }

    /**
     * The platform can only be asked on the FX thread, and this is not it: the answer has
     * to be "no", not the exception {@code Platform.getPreferences()} throws anywhere else.
     * Asked the real question - the seam is back to its default - so what is under test is
     * the guard, not a stand-in for it.
     */
    @Test
    void offTheFxThreadTheDefaultIsMotion() {
        Settings.remove("ui.reduceMotion");
        AppPreferences.usePlatformReducedMotion(null);

        assertFalse(AppPreferences.reduceMotion());
    }
}
