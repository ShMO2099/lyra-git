package com.jfx.git.ui;

import com.jfx.git.core.GitFlow;
import com.jfx.git.core.Worktrees;
import com.jfx.git.graph.GraphVisibility;
import com.jfx.git.model.RefInfo;
import com.jfx.git.model.RefSnapshot;
import com.jfx.git.model.StashEntry;
import com.jfx.git.model.Submodule;
import com.jfx.git.model.SubmoduleState;
import com.jfx.git.ui.SidebarModel.Answer;
import com.jfx.git.ui.SidebarModel.Change;
import com.jfx.git.ui.SidebarModel.Section;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sidebar's decisions, checked without a window: what the one filter keeps in each
 * section, the counts and the "Filtering n / total" line, which sections and folders are
 * open across a typing sequence, and what each redraw has to do.
 */
class SidebarModelTest {

    // ------------------------------------------------------------------
    // A repository, as the model is told about it
    // ------------------------------------------------------------------

    /** A load to change one thing at a time; {@link #load()} hands it over. */
    private static final class Repo {
        String current = "main";
        String head = "h-main";
        final List<RefInfo> locals = new ArrayList<>();
        final List<RefInfo> remotes = new ArrayList<>();
        final List<RefInfo> tags = new ArrayList<>();
        final Map<String, String> upstreams = new LinkedHashMap<>();
        final List<StashEntry> stashes = new ArrayList<>();
        final List<String> remoteNames = new ArrayList<>();
        final Map<String, int[]> tracking = new LinkedHashMap<>();
        Set<String> gone = Set.of();
        List<Submodule> submodules = List.of();
        List<Worktrees.Worktree> worktrees = List.of(worktree("(main)", true));
        GitFlow.Config flow;
        GraphVisibility visibility = GraphVisibility.ALL;
        Set<String> hiddenStashes = Set.of();

        Repo local(String... names) {
            for (String name : names) locals.add(new RefInfo("refs/heads/" + name, name, "h-" + name));
            return this;
        }

        Repo remote(String... names) {
            for (String name : names) remotes.add(new RefInfo("refs/remotes/" + name, name, "h-" + name));
            return this;
        }

        Repo tag(String... names) {
            for (String name : names) tags.add(new RefInfo("refs/tags/" + name, name, "t-" + name));
            return this;
        }

        Repo stash(String message) {
            int index = stashes.size();
            stashes.add(new StashEntry(index, "s" + index, "p" + index, message, "Ada", Instant.EPOCH));
            return this;
        }

        Repo remotes(String... names) {
            remoteNames.addAll(List.of(names));
            return this;
        }

        SidebarModel.Load load() {
            RefSnapshot refs = new RefSnapshot(current, head, locals, remotes, tags, upstreams, stashes, remoteNames);
            return new SidebarModel.Load(refs, new LinkedHashMap<>(tracking), gone, submodules, worktrees, flow,
                    visibility, hiddenStashes);
        }
    }

    private static Worktrees.Worktree worktree(String name, boolean main) {
        return new Worktrees.Worktree(name, new File("/repos/" + name), name, main, false, false);
    }

    private static Submodule submodule(String path) {
        return new Submodule(path, "https://example.com/" + path + ".git", "r", "r", SubmoduleState.CURRENT);
    }

    /** A repository much like the robot tests' fixture: some folders, two remotes, tags, a stash. */
    private static Repo shop() {
        Repo repo = new Repo()
                .local("main", "develop", "feature/payments/invoices", "feature/payments/refunds", "feature/cart")
                .remotes("origin", "upstream")
                .remote("origin/main", "origin/develop", "origin/feature/cart")
                .tag("v1.0", "v1.1")
                .stash("WIP on main: 1a2b3c4 readme rewrite");
        repo.upstreams.put("refs/heads/main", "refs/remotes/origin/main");
        repo.upstreams.put("refs/heads/feature/cart", "refs/remotes/origin/feature/cart");
        return repo;
    }

    private static SidebarModel loaded(Repo repo) {
        SidebarModel model = new SidebarModel();
        model.load(repo.load());
        return model;
    }

    private static Map<Section, Boolean> open(Answer answer) {
        Map<Section, Boolean> open = new EnumMap<>(Section.class);
        for (Section section : Section.values()) open.put(section, answer.open(section));
        return open;
    }

    private static Map<Section, Change> changes(Answer answer) {
        Map<Section, Change> changes = new EnumMap<>(Section.class);
        for (Section section : Section.values()) changes.put(section, answer.change(section));
        return changes;
    }

    // ------------------------------------------------------------------
    // The filter
    // ------------------------------------------------------------------

    @Test
    void noQueryListsEverySectionWhole() {
        Answer answer = loaded(shop()).answer("");
        assertFalse(answer.filtering());
        assertEquals(List.of("main", "develop", "feature/payments/invoices", "feature/payments/refunds",
                "feature/cart"), answer.names(Section.LOCAL));
        assertEquals(List.of("origin/main", "origin/develop", "origin/feature/cart"), answer.names(Section.REMOTE));
        assertEquals(List.of("v1.0", "v1.1"), answer.names(Section.TAGS));
        assertEquals(1, answer.stashes().size());
        assertEquals(5, answer.count(Section.LOCAL));
    }

    @Test
    void theFilterKeepsWhatContainsTheQueryInEverySectionIgnoringCase() {
        Answer answer = loaded(shop()).answer("PAY");
        assertTrue(answer.filtering());
        assertEquals(List.of("feature/payments/invoices", "feature/payments/refunds"), answer.names(Section.LOCAL));
        assertEquals(List.of(), answer.names(Section.REMOTE));
        assertEquals(List.of(), answer.names(Section.TAGS));
        assertEquals(List.of(), answer.stashes());
    }

    @Test
    void aQueryIsTrimmedAndBlankIsNoQuery() {
        SidebarModel model = loaded(shop());
        assertEquals("cart", model.answer("  cart \t").query());
        assertEquals(List.of("feature/cart"), model.answer("  cart ").names(Section.LOCAL));
        assertFalse(model.answer("   ").filtering());
        assertFalse(model.answer(null).filtering());
    }

    @Test
    void aStashMatchesByItsSelectorOrItsMessage() {
        Repo repo = shop().stash("On develop: try the new parser");
        SidebarModel model = loaded(repo);
        assertEquals(List.of("s0"), model.answer("readme rewrite").stashes().stream().map(StashEntry::hash).toList());
        assertEquals(List.of("s1"), model.answer("stash@{1}").stashes().stream().map(StashEntry::hash).toList());
        assertEquals(2, model.answer("stash@").stashes().size());
    }

    @Test
    void submodulesMatchByPathWorktreesByNameAndGitFlowByBranch() {
        Repo repo = shop();
        repo.submodules = List.of(submodule("libs/payments-sdk"), submodule("libs/ui"));
        repo.worktrees = List.of(worktree("(main)", true), worktree("shop-cart", false), worktree("shop-api", false));
        repo.flow = GitFlow.Config.defaults("main");
        Answer answer = loaded(repo).answer("ca");
        assertEquals(List.of(), answer.submodules());
        assertEquals(List.of("shop-cart"), answer.worktrees().stream().map(Worktrees.Worktree::name).toList());
        assertEquals(List.of("feature/cart"), answer.names(Section.GIT_FLOW));

        answer = loaded(repo).answer("payments");
        assertEquals(List.of("libs/payments-sdk"), answer.submodules().stream().map(Submodule::path).toList());
    }

    @Test
    void gitFlowListsTheBranchesItsPrefixesMakeFeaturesReleasesOrHotfixes() {
        Repo repo = shop().local("release/1.2", "hotfix/1.1.1", "hotfix/");
        repo.flow = GitFlow.Config.defaults("main");
        SidebarModel model = loaded(repo);
        assertEquals(List.of("feature/payments/invoices", "feature/payments/refunds", "feature/cart",
                "release/1.2", "hotfix/1.1.1"), model.answer("").names(Section.GIT_FLOW));
        assertEquals(GitFlow.Kind.RELEASE, model.local("release/1.2").flowKind());
        assertNull(model.local("main").flowKind());
    }

    /**
     * Lower-casing can make a name longer - a dotted capital I becomes two characters - so
     * an index found in the lowered copy cut the original in the wrong place, or past its end.
     */
    @Test
    void aMatchIsFoundInTheNameItselfNotInALowerCasedCopy() {
        String name = "release/İstanbul";
        int at = SidebarModel.matchAt(name, "stanbul");
        assertEquals("stanbul", name.substring(at, at + "stanbul".length()));
        assertEquals(-1, SidebarModel.matchAt(name, "zebra"));
        assertEquals(-1, SidebarModel.matchAt(null, "x"));
        assertEquals(-1, SidebarModel.matchAt(name, ""));
        assertTrue(SidebarModel.matches(name, ""), "no query matches everything");
    }

    // ------------------------------------------------------------------
    // Counts and the status line
    // ------------------------------------------------------------------

    @Test
    void whileFilteringEachBadgeCountsWhatMatchedAndOtherwiseEverything() {
        SidebarModel model = loaded(shop());
        Answer all = model.answer("");
        assertEquals(5, all.count(Section.LOCAL));
        assertEquals(3, all.count(Section.REMOTE));
        assertEquals(2, all.count(Section.TAGS));
        assertEquals(1, all.count(Section.STASHES));

        Answer filtered = model.answer("main");
        assertEquals(1, filtered.count(Section.LOCAL));
        assertEquals(1, filtered.count(Section.REMOTE));
        assertEquals(0, filtered.count(Section.TAGS));
        assertEquals(1, filtered.count(Section.STASHES), "the stash was made on main");
    }

    @Test
    void theStatusLineAddsUpEverySectionTheFilterNarrows() {
        Answer answer = loaded(shop()).answer("main");
        // local main, origin/main, the stash "WIP on main"; of 5 + 3 + 2 + 1.
        assertEquals("Filtering 3 / 11", answer.status());
    }

    @Test
    void theStatusLineLeavesOutGitFlowWhoseRowsAreLocalBranchesCountedAlready() {
        Repo repo = shop();
        repo.flow = GitFlow.Config.defaults("main");
        Answer answer = loaded(repo).answer("cart");
        assertEquals(1, answer.count(Section.GIT_FLOW));
        // feature/cart and origin/feature/cart, of 5 + 3 + 2 + 1: not the Git Flow row again.
        assertEquals("Filtering 2 / 11", answer.status());
    }

    @Test
    void theStatusLineCountsWorktreesOnlyWhenThereIsASectionForThem() {
        Repo repo = shop();
        repo.worktrees = List.of(worktree("(main)", true));
        assertEquals("Filtering 0 / 11", loaded(repo).answer("main)").status(),
                "the main tree alone has no section, so its match is not counted");

        repo.worktrees = List.of(worktree("(main)", true), worktree("shop-cart", false));
        assertEquals("Filtering 1 / 13", loaded(repo).answer("main)").status());
    }

    @Test
    void theStatusLineCountsSubmodules() {
        Repo repo = shop();
        repo.submodules = List.of(submodule("libs/main-sdk"), submodule("libs/ui"));
        // main, origin/main, the stash, libs/main-sdk; of 5 + 3 + 2 + 1 + 2.
        assertEquals("Filtering 4 / 13", loaded(repo).answer("main").status());
    }

    @Test
    void submodulesWorktreesAndGitFlowAreThereOnlyWithSomethingInThem() {
        Repo repo = shop();
        SidebarModel model = loaded(repo);
        assertFalse(model.present(Section.SUBMODULES));
        assertFalse(model.present(Section.WORKTREES), "the main tree alone");
        assertFalse(model.present(Section.GIT_FLOW));
        assertTrue(model.present(Section.LOCAL) && model.present(Section.STASHES));

        repo.submodules = List.of(submodule("libs/ui"));
        repo.worktrees = List.of(worktree("(main)", true), worktree("shop-cart", false));
        repo.flow = GitFlow.Config.defaults("main");
        model.load(repo.load());
        assertTrue(model.present(Section.SUBMODULES));
        assertTrue(model.present(Section.WORKTREES));
        assertTrue(model.present(Section.GIT_FLOW));
    }

    @Test
    void aRepositoryWithNoRemoteOffersToAddOne() {
        assertFalse(loaded(shop()).offersAddRemote());
        assertTrue(loaded(new Repo().local("main")).offersAddRemote());
    }

    @Test
    void beforeTheFirstLoadThereIsNothingToCount() {
        SidebarModel model = new SidebarModel();
        assertFalse(model.loaded());
        assertEquals(List.of(), model.localNames());
        assertEquals("", model.currentBranch());
        model.load(shop().load());
        assertTrue(model.loaded());
    }

    // ------------------------------------------------------------------
    // Open sections, across a typing sequence
    // ------------------------------------------------------------------

    @Test
    void localAndGitFlowStartOpenAndTheRestClosed() {
        SidebarModel model = loaded(shop());
        Map<Section, Boolean> open = open(model.answer(""));
        assertEquals(Map.of(Section.LOCAL, true, Section.REMOTE, false, Section.TAGS, false,
                Section.STASHES, false, Section.GIT_FLOW, true, Section.SUBMODULES, false,
                Section.WORKTREES, false), open);
    }

    @Test
    void filteringOpensTheSectionsWithMatchesAndClosesTheRest() {
        SidebarModel model = loaded(shop());
        Answer answer = model.answer("main");
        assertTrue(answer.open(Section.LOCAL));
        assertTrue(answer.open(Section.REMOTE), "a closed section with a match opens");
        assertFalse(answer.open(Section.TAGS));
        assertTrue(answer.open(Section.STASHES));
        assertFalse(answer.open(Section.GIT_FLOW), "no match there: closed");
    }

    @Test
    void clearingTheFilterPutsBackHowTheUserHadEverySection() {
        SidebarModel model = loaded(shop());
        model.answer("");
        model.opened(Section.TAGS, true);
        model.opened(Section.LOCAL, false);
        Map<Section, Boolean> before = open(model.answer(""));

        model.answer("m");
        model.answer("ma");
        model.opened(Section.REMOTE, false);
        model.answer("main");
        assertEquals(before, open(model.answer("")), "as before filtering, whatever happened while filtering");
    }

    @Test
    void aSectionTheUserClosesWhileFilteringStaysClosedThroughRefreshes() {
        Repo repo = shop();
        SidebarModel model = loaded(repo);
        model.answer("main");
        model.opened(Section.LOCAL, false);

        model.load(repo.load());
        assertFalse(model.answer("main").open(Section.LOCAL), "a refresh answers the same query again");
        repo.tracking.put("main", new int[]{1, 0});
        model.load(repo.load());
        assertFalse(model.answer("main").open(Section.LOCAL), "and so does one that brings new facts");
    }

    @Test
    void aNewQueryDecidesTheSectionsAgain() {
        SidebarModel model = loaded(shop());
        model.answer("ma");
        model.opened(Section.LOCAL, false);
        model.opened(Section.TAGS, true);
        Answer answer = model.answer("mai");
        assertTrue(answer.open(Section.LOCAL), "typing on is a new query: the section with matches opens");
        assertFalse(answer.open(Section.TAGS), "and the one without closes");
    }

    @Test
    void aRefreshThatBringsASectionItsFirstMatchOpensIt() {
        Repo repo = shop();
        SidebarModel model = loaded(repo);
        assertFalse(model.answer("zebra").open(Section.LOCAL), "precondition: nothing matches, so it is closed");

        repo.local("feature/zebra");
        model.load(repo.load());
        assertTrue(model.answer("zebra").open(Section.LOCAL));
    }

    @Test
    void aRefreshThatTakesASectionsLastMatchClosesIt() {
        Repo repo = shop();
        SidebarModel model = loaded(repo);
        assertTrue(model.answer("v1.1").open(Section.TAGS));

        repo.tags.removeIf(t -> t.shortName().equals("v1.1"));
        model.load(repo.load());
        assertFalse(model.answer("v1.1").open(Section.TAGS));
    }

    @Test
    void aSectionOpenedByHandWhileItsMatchesComeAndGoIsOnlyForcedWhenTheyCrossZero() {
        Repo repo = shop();
        SidebarModel model = loaded(repo);
        assertTrue(model.answer("feature").open(Section.LOCAL));
        model.opened(Section.LOCAL, false);

        repo.local("feature/search");
        model.load(repo.load());
        assertFalse(model.answer("feature").open(Section.LOCAL), "three matches to four is not news");
    }

    @Test
    void theSectionsOpenedBeforeTheFirstLoadAreWhatClearingPutsBack() {
        SidebarModel model = new SidebarModel();
        model.opened(Section.REMOTE, true);
        model.load(shop().load());
        model.answer("tag-that-matches-nothing");
        assertFalse(model.open(Section.REMOTE));
        assertTrue(model.answer("").open(Section.REMOTE));
    }

    // ------------------------------------------------------------------
    // Folder memory
    // ------------------------------------------------------------------

    @Test
    void localFoldersStartOpenAndRemoteFoldersClosed() {
        SidebarModel model = loaded(shop());
        model.answer("");
        assertTrue(model.folderOpen(Section.LOCAL, "feature"));
        assertTrue(model.folderOpen(Section.LOCAL, "feature/payments"));
        assertFalse(model.folderOpen(Section.REMOTE, "origin"));
    }

    @Test
    void aFolderStaysAsTheUserLeftItThroughRefreshes() {
        Repo repo = shop();
        SidebarModel model = loaded(repo);
        model.answer("");
        model.folderToggled(Section.LOCAL, "feature", false);
        model.folderToggled(Section.REMOTE, "origin", true);

        repo.local("feature/search");
        model.load(repo.load());
        model.answer("");
        assertFalse(model.folderOpen(Section.LOCAL, "feature"));
        assertTrue(model.folderOpen(Section.REMOTE, "origin"));
        assertFalse(model.folderOpen(Section.REMOTE, "upstream"), "each folder by its own path");
    }

    @Test
    void whileFilteringEveryFolderIsOpenAndClearingPutsTheUsersChoiceBack() {
        SidebarModel model = loaded(shop());
        model.answer("");
        model.folderToggled(Section.LOCAL, "feature", false);

        model.answer("cart");
        assertTrue(model.folderOpen(Section.LOCAL, "feature"), "a match inside a closed folder is a match nobody sees");
        assertTrue(model.folderOpen(Section.REMOTE, "origin"));
        // The filter opening them all, or the user closing one under it, is not a choice to keep.
        model.folderToggled(Section.LOCAL, "feature", true);
        model.folderToggled(Section.REMOTE, "origin", true);

        model.answer("");
        assertFalse(model.folderOpen(Section.LOCAL, "feature"));
        assertFalse(model.folderOpen(Section.REMOTE, "origin"));
    }

    @Test
    void theRemoteTreeShowsEveryRemoteButNoEmptyOneWhileFiltering() {
        Repo repo = shop();
        SidebarModel model = loaded(repo);
        Answer all = model.answer("");
        assertEquals(List.of("origin", "upstream"), all.remoteRoots());
        assertEquals(List.of("origin", "upstream"), all.remoteFolders(), "upstream has nothing fetched, and is listed");

        Answer filtered = model.answer("main");
        assertEquals(List.of("origin", "upstream"), filtered.remoteRoots(), "a slash-named remote stays one folder");
        assertEquals(List.of(), filtered.remoteFolders(), "an empty folder matches nothing");
    }

    // ------------------------------------------------------------------
    // Rows and facts
    // ------------------------------------------------------------------

    @Test
    void theFirstAnswerBuildsEverySectionAndTheSameAnswerAgainDrawsNothing() {
        Repo repo = shop();
        SidebarModel model = loaded(repo);
        for (Change change : changes(model.answer("")).values()) assertEquals(Change.REBUILD, change);

        model.load(repo.load());
        for (Change change : changes(model.answer("")).values()) {
            assertEquals(Change.NONE, change, "a refresh that found nothing new");
        }
    }

    @Test
    void newTrackingCountsRedrawTheLocalRowsInPlace() {
        Repo repo = shop();
        SidebarModel model = loaded(repo);
        model.answer("");
        repo.tracking.put("main", new int[]{2, 1});
        model.load(repo.load());
        Map<Section, Change> changes = changes(model.answer(""));
        assertEquals(Change.REDRAW, changes.get(Section.LOCAL));
        assertEquals(Change.NONE, changes.get(Section.REMOTE));
        assertEquals(Change.NONE, changes.get(Section.TAGS));
    }

    @Test
    void aNewBranchRebuildsItsTreeAndLeavesTheOthers() {
        Repo repo = shop();
        SidebarModel model = loaded(repo);
        model.answer("");
        repo.local("feature/search");
        model.load(repo.load());
        Map<Section, Change> changes = changes(model.answer(""));
        assertEquals(Change.REBUILD, changes.get(Section.LOCAL));
        assertEquals(Change.NONE, changes.get(Section.REMOTE));
    }

    @Test
    void typingOnWithTheSameMatchesRedrawsToMarkTheNewQuery() {
        SidebarModel model = loaded(shop());
        model.answer("ref");
        assertEquals(Change.REDRAW, model.answer("refu").change(Section.LOCAL),
                "the same rows, with more of each name marked");
    }

    @Test
    void startingOrStoppingTheFilterRebuildsTheTreesSinceItOpensEveryFolder() {
        SidebarModel model = loaded(new Repo().local("feature/a", "feature/b"));
        model.answer("");
        assertEquals(Change.REBUILD, model.answer("feature").change(Section.LOCAL), "the same names, all folders open");
        assertEquals(Change.REBUILD, model.answer("").change(Section.LOCAL));
    }

    @Test
    void hidingARefRedrawsItsSectionsRows() {
        Repo repo = shop();
        SidebarModel model = loaded(repo);
        model.answer("");
        repo.visibility = new GraphVisibility(Set.of("refs/tags/v1.0"), null);
        model.load(repo.load());
        Map<Section, Change> changes = changes(model.answer(""));
        assertEquals(Change.REDRAW, changes.get(Section.TAGS));
        assertEquals(Change.NONE, changes.get(Section.LOCAL));

        repo.visibility = new GraphVisibility(Set.of("refs/tags/v1.0"), "refs/heads/develop");
        model.load(repo.load());
        changes = changes(model.answer(""));
        assertEquals(Change.REDRAW, changes.get(Section.LOCAL), "a solo is drawn on its row");
        assertEquals(Change.NONE, changes.get(Section.TAGS));
    }

    @Test
    void hidingAStashRedrawsTheStashRows() {
        Repo repo = shop();
        SidebarModel model = loaded(repo);
        model.answer("");
        repo.hiddenStashes = Set.of("s0");
        model.load(repo.load());
        assertEquals(Change.REDRAW, model.answer("").change(Section.STASHES));
        assertTrue(model.stashHidden("s0"));
    }

    @Test
    void anotherCurrentBranchRedrawsTheRowsWhoseMenusNameIt() {
        Repo repo = shop();
        repo.flow = GitFlow.Config.defaults("main");
        SidebarModel model = loaded(repo);
        model.answer("");
        repo.current = "develop";
        model.load(repo.load());
        Map<Section, Change> changes = changes(model.answer(""));
        assertEquals(Change.REDRAW, changes.get(Section.LOCAL));
        assertEquals(Change.REDRAW, changes.get(Section.REMOTE), "\"Merge into develop\"");
        assertEquals(Change.REDRAW, changes.get(Section.GIT_FLOW));
        assertEquals(Change.NONE, changes.get(Section.TAGS));
    }

    @Test
    void aNewRemoteRedrawsTheTagsWhosePushItemsNameTheRemotes() {
        Repo repo = shop();
        SidebarModel model = loaded(repo);
        model.answer("");
        repo.remotes("fork");
        model.load(repo.load());
        Map<Section, Change> changes = changes(model.answer(""));
        assertEquals(Change.REDRAW, changes.get(Section.TAGS));
        assertEquals(Change.REBUILD, changes.get(Section.REMOTE), "its empty folder is listed");
    }

    /**
     * The remote tree is grouped by the remotes there are, filtering or not: a remote added
     * while the filter is on regroups the branches it owns. With {@code team} the only
     * remote, {@code team/core/x} is {@code core/x} on {@code team}; once {@code team/core}
     * is added it is {@code x} on that one, and a redraw of the rows left it nested under
     * team and core until the filter changed.
     */
    @Test
    void aRemoteAddedWhileFilteringRegroupsTheRemoteTree() {
        Repo repo = new Repo().local("main").remotes("team").remote("team/core/x");
        SidebarModel model = loaded(repo);
        model.answer("x");
        repo.remotes("team/core");
        model.load(repo.load());

        Answer answer = model.answer("x");
        assertEquals(Change.REBUILD, answer.change(Section.REMOTE), "the folders the branch is in have changed");
        assertEquals(List.of("team", "team/core"), answer.remoteRoots());
    }

    @Test
    void submodulesAndWorktreesAreDrawnOnlyWhenTheirRowsChange() {
        Repo repo = shop();
        repo.submodules = List.of(submodule("libs/ui"));
        SidebarModel model = loaded(repo);
        model.answer("");
        assertEquals(Change.NONE, model.answer("ui").change(Section.SUBMODULES),
                "the same row, and nothing marked in it");
        assertEquals(Change.REBUILD, model.answer("sdk").change(Section.SUBMODULES));
    }

    /**
     * At the size the in-place redraw was measured for - five thousand remote branches - a
     * refresh that found nothing new leaves every section alone, a fetch that moved one tip
     * redraws the rows on screen rather than making the tree anew, and only a new branch
     * rebuilds it.
     */
    @Test
    void fiveThousandRemoteBranchesAreRebuiltOnlyWhenTheBranchesChange() {
        Repo repo = shop();
        for (int i = 0; i < 5_000; i++) repo.remote("origin/agent/branch-" + i);
        SidebarModel model = loaded(repo);
        model.answer("");

        model.load(repo.load());
        assertEquals(Change.NONE, model.answer("").change(Section.REMOTE));

        RefInfo moved = repo.remotes.get(1_000);
        repo.remotes.set(1_000, new RefInfo(moved.fullName(), moved.shortName(), "fetched"));
        model.load(repo.load());
        assertEquals(Change.REDRAW, model.answer("").change(Section.REMOTE), "a tip moved: the same rows");
        assertEquals("fetched", model.remote(moved.shortName()).tip());

        repo.remote("origin/agent/new");
        model.load(repo.load());
        assertEquals(Change.REBUILD, model.answer("").change(Section.REMOTE));
        assertEquals(5_004, model.answer("").count(Section.REMOTE));
    }

    // ------------------------------------------------------------------
    // Rows keep their refs
    // ------------------------------------------------------------------

    @Test
    void aLocalRowCarriesItsRefAndWhatItsRowShows() {
        Repo repo = shop();
        repo.tracking.put("feature/cart", new int[]{3, 2});
        repo.gone = Set.of("feature/cart");
        repo.visibility = new GraphVisibility(Set.of("refs/heads/feature/cart"), "refs/heads/develop");
        SidebarModel model = loaded(repo);

        SidebarModel.LocalRow cart = model.local("feature/cart");
        assertEquals("refs/heads/feature/cart", cart.fullName());
        assertEquals("h-feature/cart", cart.tip());
        assertEquals(3, cart.ahead());
        assertEquals(2, cart.behind());
        assertTrue(cart.gone());
        assertEquals("origin/feature/cart", cart.upstream());
        assertTrue(cart.hidden());
        assertFalse(cart.solo());
        assertFalse(cart.current());

        assertTrue(model.local("develop").solo());
        assertTrue(model.local("main").current());
        assertFalse(model.local("main").hideable(), "Hide & Solo leaves the branch HEAD is on alone");
    }

    @Test
    void aRowKeepsTheFullNameTheSnapshotGaveIt() {
        Repo repo = new Repo();
        repo.locals.add(new RefInfo("refs/heads/main", "main", "h1"));
        repo.remotes.add(new RefInfo("refs/remotes/team/core/topic", "team/core/topic", "h2"));
        repo.remoteNames.add("team/core");
        repo.tags.add(new RefInfo("refs/tags/main", "main", "h3"));
        SidebarModel model = loaded(repo);
        assertEquals("refs/heads/main", model.local("main").fullName());
        assertEquals("refs/remotes/team/core/topic", model.remote("team/core/topic").fullName());
        assertEquals("refs/tags/main", model.tag("main").fullName(), "a tag and a branch of one name are two refs");
    }

    @Test
    void aRemoteRowKnowsWhetherTheCurrentBranchTracksIt() {
        SidebarModel model = loaded(shop());
        assertTrue(model.remote("origin/main").upstreamOfCurrent());
        assertFalse(model.remote("origin/develop").upstreamOfCurrent());

        Repo detached = shop();
        detached.current = "";
        model = loaded(detached);
        assertFalse(model.remote("origin/main").upstreamOfCurrent(), "no current branch tracks anything");
    }

    @Test
    void aNameNotLoadedHasABareRowRatherThanNone() {
        SidebarModel model = new SidebarModel();
        SidebarModel.LocalRow row = model.local("gone-already");
        assertEquals("refs/heads/gone-already", row.fullName());
        assertNull(row.tip());
        assertEquals("refs/remotes/origin/x", model.remote("origin/x").fullName());
        assertEquals("refs/tags/v9", model.tag("v9").fullName());
        assertFalse(model.stashHidden("s0"));
    }

    @Test
    void hideAllLeavesTheCurrentBranchAndShowAllCoversEveryRef() {
        SidebarModel model = loaded(shop());
        assertEquals(List.of("refs/heads/develop", "refs/heads/feature/payments/invoices",
                "refs/heads/feature/payments/refunds", "refs/heads/feature/cart"), model.hideable(Section.LOCAL));
        assertEquals(5, model.fullNames(Section.LOCAL).size());
        assertEquals(List.of("refs/remotes/origin/main", "refs/remotes/origin/develop",
                "refs/remotes/origin/feature/cart"), model.hideable(Section.REMOTE));
        assertEquals(List.of("refs/tags/v1.0", "refs/tags/v1.1"), model.hideable(Section.TAGS));
        assertEquals(List.of("s0"), model.stashHashes());
    }

    @Test
    void withHeadDetachedEveryBranchCanBeHidden() {
        Repo repo = shop();
        repo.current = "";
        SidebarModel model = loaded(repo);
        assertEquals(5, model.hideable(Section.LOCAL).size());
        assertEquals("", model.currentBranch());
    }

    @Test
    void theModelReachesNeitherTheToolkitNorHideAndSolosStore() throws Exception {
        for (String file : List.of("SidebarModel.java", "HideSolo.java")) {
            String text = Files.readString(Path.of("src/main/java/com/jfx/git/ui/" + file), StandardCharsets.UTF_8);
            assertFalse(text.contains("javafx."), file + " reaches into JavaFX");
        }
        String model = Files.readString(Path.of("src/main/java/com/jfx/git/ui/SidebarModel.java"),
                StandardCharsets.UTF_8);
        assertFalse(model.contains("AppPreferences"), "the model is told Hide & Solo; it does not read the settings");
    }
}
