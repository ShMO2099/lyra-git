package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The history window through its interface, with a scripted source in place of the
 * repository: one that holds each request until the test answers it - in any order, or
 * never - one that answers at once, and failures.
 */
class HistoryWindowTest {

    private static final int FIRST_PAGE = 1_000;

    // ------------------------------------------------------------------
    // A history, a source and a listener to drive the window with
    // ------------------------------------------------------------------

    /** A straight line of {@code size} commits, newest first; a page is the newest n. */
    private static final class History {
        final int size;
        String branch = "main";
        /** Changes every commit's subject when bumped, so a reload draws something new. */
        int generation;
        /** A remote whose namesake of the branch sits on the tip too, or null for none. */
        String remote;
        /** Each commit's upstreams, local short name to remote short name; null for unknown. */
        Map<String, String> upstreams;
        final List<Integer> walked = new ArrayList<>();

        History(int size) {
            this.size = size;
        }

        GraphPage page(int limit) {
            walked.add(limit);
            int n = Math.min(limit, size);
            List<GitGraphRow> rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                List<String> parents = i + 1 < size ? List.of(hash(i + 1)) : List.of();
                List<RefInfo> refs = new ArrayList<>();
                if (i == 0) {
                    refs.add(new RefInfo("refs/heads/" + branch, branch, hash(0)));
                    if (remote != null) {
                        refs.add(new RefInfo("refs/remotes/" + remote + "/" + branch, remote + "/" + branch, hash(0)));
                    }
                }
                // The generation is in the tip's parents: the signature reads parents, not
                // subjects, so this is what makes a reload "draw something different".
                if (i == 0 && generation > 0) {
                    parents = new ArrayList<>(parents);
                    parents.add("g" + generation);
                }
                CommitInfo commit = new CommitInfo(hash(i), "subject " + i, "Ada", "ada@example.com",
                        Instant.ofEpochSecond(1_000_000L - i), parents, refs);
                commit.setUpstreams(upstreams);
                rows.add(new GitGraphRow(commit));
            }
            return new GraphPage(rows, limit < size);
        }

        HistoryWindow.Loaded loaded(int limit) {
            return HistoryWindow.Loaded.of(page(limit), branch);
        }
    }

    private static String hash(int i) {
        return String.format("%040d", i);
    }

    private static Predicate<GitGraphRow> commit(int i) {
        String hash = hash(i);
        return row -> row.getCommit() != null && hash.equals(row.getCommit().getHash());
    }

    /** A request the test answers when it chooses. */
    private static final class Pending {
        final HistoryWindow.Walk walk;
        final Consumer<HistoryWindow.Walked> onLoaded;
        final Consumer<String> onFailed;
        final Runnable onDropped;
        boolean cancelled;

        Pending(HistoryWindow.Walk walk, Consumer<HistoryWindow.Walked> onLoaded, Consumer<String> onFailed,
                Runnable onDropped) {
            this.walk = walk;
            this.onLoaded = onLoaded;
            this.onFailed = onFailed;
            this.onDropped = onDropped;
        }
    }

    private static final class Source implements HistoryWindow.Source {
        final History history;
        boolean answerAtOnce;
        final List<Pending> pending = new ArrayList<>();
        final List<String> reachesAsked = new ArrayList<>();
        final List<Consumer<Boolean>> reachAnswers = new ArrayList<>();
        final List<Runnable> reachDrops = new ArrayList<>();
        final List<GraphPage> kept = new ArrayList<>();

        Source(History history) {
            this.history = history;
        }

        @Override
        public HistoryWindow.Request load(HistoryWindow.Walk walk, Consumer<HistoryWindow.Walked> onLoaded,
                                          Consumer<String> onFailed, Runnable onDropped) {
            Pending request = new Pending(walk, onLoaded, onFailed, onDropped);
            if (answerAtOnce) {
                onLoaded.accept(walk.run(history::page, () -> history.branch, () -> false));
            } else {
                pending.add(request);
            }
            return () -> request.cancelled = true;
        }

        @Override
        public HistoryWindow.Request reaches(String hash, Consumer<Boolean> onAnswer, Consumer<String> onFailed,
                                             Runnable onDropped) {
            reachesAsked.add(hash);
            reachAnswers.add(onAnswer);
            reachDrops.add(onDropped);
            return () -> { };
        }

        /**
         * Drops the request before it starts, as the task runner does when someone other
         * than the window cancels it - the status bar's Cancel, aimed at the task it follows.
         */
        void drop(Pending request) {
            pending.remove(request);
            request.onDropped.run();
        }

        @Override
        public void keepFirstPage(GraphPage page, String branch) {
            kept.add(page);
        }

        /** Runs the request as a worker would - cancelled or not - and delivers its answer. */
        void answer(Pending request) {
            pending.remove(request);
            request.onLoaded.accept(request.walk.run(history::page, () -> history.branch,
                    () -> request.cancelled));
        }

        void answerOldest() {
            answer(pending.get(0));
        }

        void answerNewest() {
            answer(pending.get(pending.size() - 1));
        }

        void fail(Pending request, String error) {
            pending.remove(request);
            request.onFailed.accept(error);
        }
    }

    private static final class Events implements HistoryWindow.Listener {
        final List<String> log = new ArrayList<>();
        int busyDepth;
        Runnable onPageChanged = () -> { };

        @Override
        public void pageChanged(HistoryWindow.Change change, boolean first) {
            log.add("page " + change + (first ? " first" : ""));
            onPageChanged.run();
        }

        @Override
        public void refreshed(GraphPage page) {
            log.add("refreshed " + page.rows().size());
        }

        @Override
        public void refreshFailed(String error) {
            log.add("refresh failed " + error);
        }

        @Override
        public void pagingFailed(String error) {
            log.add("paging failed " + error);
        }

        @Override
        public void busy(String what) {
            busyDepth++;
            log.add("busy " + what);
        }

        @Override
        public void idle() {
            busyDepth--;
            assertTrue(busyDepth >= 0, "idle without busy: " + log);
            log.add("idle");
        }

        List<String> pages() {
            return log.stream().filter(e -> e.startsWith("page ")).toList();
        }
    }

    private final History history = new History(10_000);
    private final Source source = new Source(history);
    private final Events events = new Events();
    private final GraphHighlight highlight = new GraphHighlight();
    private int firstPageSize = FIRST_PAGE;
    private final HistoryWindow window = new HistoryWindow(source, events, highlight, () -> firstPageSize);

    /** A window showing its first page. */
    private void opened() {
        window.refresh();
        source.answerOldest();
        events.log.clear();
    }

    // ------------------------------------------------------------------
    // Refresh and the first page
    // ------------------------------------------------------------------

    @Test
    void theFirstRefreshDrawsTheFirstPageAndKeepsItForNextTime() {
        window.refresh();
        assertEquals(1, source.pending.size());
        assertEquals(FIRST_PAGE, source.pending.get(0).walk.limit());
        assertFalse(window.firstPageShown());

        source.answerOldest();

        assertEquals(List.of("refreshed 1000", "page REFRESHED first"), events.log);
        assertTrue(window.firstPageShown());
        assertEquals(FIRST_PAGE, window.rows().size());
        assertTrue(window.hasMore());
        assertEquals("main", window.currentBranch());
        assertEquals(1, source.kept.size(), "the first page is kept for the next opening");
    }

    @Test
    void aRefreshThatDrawsTheSameLeavesThePageAlone() {
        opened();
        List<GitGraphRow> before = window.rows();

        window.refresh();
        source.answerOldest();

        assertEquals(List.of("refreshed 1000"), events.log, "told it came back, but nothing replaced");
        assertSame(before, window.rows());
    }

    @Test
    void aRefreshThatDrawsSomethingNewReplacesThePage() {
        opened();
        history.generation++;

        window.refresh();
        source.answerOldest();

        assertEquals(List.of("refreshed 1000", "page REFRESHED"), events.log);
    }

    @Test
    void onlyTheFirstPageIsKept() {
        opened();
        window.loadOlder();
        source.answerOldest();
        history.generation++;
        source.kept.clear();

        window.refresh();
        source.answerOldest();

        assertEquals(List.of(), source.kept, "a page deeper than the first is not what an opening wants");
    }

    @Test
    void aRefreshFailingIsReported() {
        window.refresh();
        source.fail(source.pending.get(0), "no repository");

        assertEquals(List.of("refresh failed no repository"), events.log);
        assertFalse(window.firstPageShown());
    }

    // ------------------------------------------------------------------
    // The page kept from the last session
    // ------------------------------------------------------------------

    @Test
    void theKeptPageIsDrawnUntilTheRealOneLands() {
        assertTrue(window.showCached(history.loaded(FIRST_PAGE)));
        assertEquals(List.of("page CACHED first"), events.log);
        assertTrue(window.firstPageShown());

        window.refresh();
        source.answerOldest();
        assertEquals(List.of("page CACHED first", "refreshed 1000"), events.log,
                "the real page draws the same, so the kept one stays");

        history.generation++;
        window.refresh();
        source.answerOldest();
        assertEquals("page REFRESHED", events.log.get(events.log.size() - 1),
                "a repository changed since replaces it, as an ordinary refresh");
    }

    @Test
    void aKeptPageArrivingAfterTheRealOneIsIgnored() {
        opened();
        List<GitGraphRow> real = window.rows();
        history.generation++;

        assertFalse(window.showCached(history.loaded(FIRST_PAGE)));

        assertSame(real, window.rows(), "old rows must not go back over the live page");
        assertEquals(List.of(), events.log);
    }

    // ------------------------------------------------------------------
    // Older pages
    // ------------------------------------------------------------------

    @Test
    void loadingOlderWalksAPageFurtherAndSaysItIsBusyMeanwhile() {
        opened();

        window.loadOlder();
        assertEquals(FIRST_PAGE + HistoryWindow.PAGE_STEP, window.limit());
        assertEquals(1, events.busyDepth);
        source.answerOldest();

        assertEquals(List.of("busy Loading older commits", "idle", "page OLDER"), events.log);
        assertEquals(2_000, window.rows().size());
        assertEquals(0, events.busyDepth);
    }

    @Test
    void asksForOlderPagesOnceWhileOneIsLoading() {
        opened();

        window.loadOlder();
        window.loadOlder();

        assertEquals(1, source.pending.size());
        assertEquals(FIRST_PAGE + HistoryWindow.PAGE_STEP, window.limit());
    }

    @Test
    void theListRebuiltForAnOlderPageCannotPageAgain() {
        opened();
        // A rebuild of the list re-selecting the "load more" row lands back here.
        events.onPageChanged = window::loadOlder;

        window.loadOlder();
        source.answerOldest();

        assertEquals(List.of(), source.pending, "the rebuild started another page");
        assertEquals(FIRST_PAGE + HistoryWindow.PAGE_STEP, window.limit());
    }

    @Test
    void anOlderPageFailingSaysSoAndLetsTheUserTryAgain() {
        opened();

        window.loadOlder();
        source.fail(source.pending.get(0), "pack corrupt");

        assertEquals(List.of("busy Loading older commits", "idle", "paging failed pack corrupt"), events.log);
        window.loadOlder();
        assertEquals(1, source.pending.size(), "a failure must not leave paging locked");
    }

    // ------------------------------------------------------------------
    // Stale pages
    // ------------------------------------------------------------------

    @Test
    void aRefreshAskedBeforeLoadMoreIsDroppedWhileTheLongerPageLoads() {
        opened();
        window.refresh();
        window.loadOlder();

        source.answerOldest();

        assertEquals(List.of("busy Loading older commits", "refreshed 1000"), events.log,
                "applied, the shorter page would throw away the rows load-more is bringing");
        assertEquals(1, source.pending.size(), "and not asked again: load-more's page is newer");
        source.answerOldest();
        assertEquals(2_000, window.rows().size());
    }

    @Test
    void aRefreshShorterThanARevealedPageIsAskedAgainAtTheNewDepth() {
        opened();
        window.refresh();
        window.reveal(commit(1_500), "c1500", () -> { });
        Pending reveal = source.pending.get(1);
        source.answer(reveal);
        assertEquals(2_000, window.limit());
        int revealedRows = window.rows().size();

        source.answerOldest();

        assertEquals(revealedRows, window.rows().size(), "the revealed commit's page stays");
        assertEquals(1, source.pending.size());
        assertEquals(2_000, source.pending.get(0).walk.limit(), "asked again at the limit the graph has now");
    }

    /**
     * A "load more" in flight excuses a short refresh only while its own page will be the
     * one shown; once a reveal has landed deeper, that page will be dropped too, and the
     * refresh - which may be for a change the reveal predates - is asked again.
     */
    @Test
    void aShortRefreshIsAskedAgainWhenLoadMoreWillNotBringTheDepthEither() {
        opened();
        window.reveal(commit(3_000), "c3000", () -> { });
        Pending reveal = source.pending.get(0);
        window.refresh();
        window.loadOlder();
        source.answer(reveal);
        assertEquals(4_000, window.limit());

        source.answerOldest();

        assertEquals(2, source.pending.size(), "the load-more, and the refresh asked again");
        assertEquals(4_000, source.pending.get(1).walk.limit());
    }

    @Test
    void anOlderPageShorterThanARevealedOneIsDropped() {
        opened();
        window.loadOlder();
        Pending older = source.pending.get(0);
        window.reveal(commit(3_000), "c3000", () -> { });
        source.answerNewest();
        assertEquals(4_000, window.limit());

        source.answer(older);

        assertEquals(4_000, window.rows().size(), "a 2,000-commit page landing late must not shrink the graph");
        assertEquals(0, events.busyDepth);
        assertFalse(events.pages().contains("page OLDER"));
    }

    // ------------------------------------------------------------------
    // Revealing a commit
    // ------------------------------------------------------------------

    @Test
    void aRevealDoublesTheWalkUntilTheCommitIsThere() {
        opened();
        history.walked.clear();
        int[] ran = {0};

        window.reveal(commit(5_000), "c5000", () -> ran[0]++);
        assertEquals(List.of("busy Loading history to reach c5000"), events.log);
        source.answerOldest();

        assertEquals(List.of(2_000, 4_000, 8_000), history.walked, "one request, doubling on the worker");
        assertEquals(8_000, window.limit());
        assertTrue(window.rows().stream().anyMatch(commit(5_000)));
        assertEquals(1, ran[0]);
        assertEquals(List.of("busy Loading history to reach c5000", "page REVEALED", "idle"), events.log);
    }

    @Test
    void aRevealStopsWhereHistoryEnds() {
        opened();
        history.walked.clear();
        int[] ran = {0};

        window.reveal(row -> false, "nothing", () -> ran[0]++);
        source.answerOldest();

        assertEquals(List.of(2_000, 4_000, 8_000, 16_000), history.walked);
        assertFalse(window.hasMore());
        assertEquals(1, ran[0], "told it is over, so the caller can say the commit is not shown");
    }

    @Test
    void aRevealStopsAtTheCap() {
        opened();
        History deep = new History(HistoryWindow.REVEAL_LIMIT * 2);
        Source deepSource = new Source(deep);
        HistoryWindow deepWindow = new HistoryWindow(deepSource, events, highlight, () -> 60_000);
        deepWindow.refresh();
        deepSource.answerOldest();
        deep.walked.clear();

        deepWindow.reveal(row -> false, "nothing", () -> { });
        deepSource.answerOldest();

        assertEquals(List.of(HistoryWindow.REVEAL_LIMIT), deep.walked);
        assertEquals(HistoryWindow.REVEAL_LIMIT, deepWindow.limit());
        assertTrue(deepWindow.hasMore());
        int[] ran = {0};
        deepWindow.reveal(row -> false, "nothing", () -> ran[0]++);
        assertEquals(List.of(), deepSource.pending, "at the cap, nothing more is walked");
        assertEquals(1, ran[0]);
    }

    @Test
    void aRowAlreadyLoadedIsNotPagedFor() {
        opened();
        int[] ran = {0};

        // Loaded, and only narrowed away by the view's filter.
        window.reveal(commit(10), "c10", () -> ran[0]++);

        assertEquals(List.of(), source.pending, "twice the history would not make the filter show it");
        assertEquals(1, ran[0]);
        assertEquals(List.of(), events.log);
    }

    @Test
    void aNewerRevealCancelsTheOlderAndEndsItsBusySpan() {
        opened();
        int[] ran = {0, 0};

        window.reveal(commit(1_500), "c1500", () -> ran[0]++);
        Pending first = source.pending.get(0);
        window.reveal(commit(3_000), "c3000", () -> ran[1]++);

        assertTrue(first.cancelled, "the older request is cancelled");
        assertEquals(List.of("busy Loading history to reach c1500", "idle",
                "busy Loading history to reach c3000"), events.log);
        source.answerNewest();

        assertEquals(0, events.busyDepth, "the status bar is idle once the newer reveal lands");
        assertEquals(0, ran[0]);
        assertEquals(1, ran[1]);
        // The older request never answers: cancelled before it started, it never ran.
        assertEquals(List.of(first), source.pending);
    }

    @Test
    void anOlderRevealAnsweringAfterTheNewerOneIsIgnored() {
        opened();
        int[] ran = {0, 0};
        window.reveal(commit(5_000), "c5000", () -> ran[0]++);
        Pending first = source.pending.get(0);
        window.reveal(commit(1_500), "c1500", () -> ran[1]++);
        source.answerNewest();
        assertEquals(2_000, window.limit());

        // Already running when it was cancelled: it stops between pages and still answers.
        source.answer(first);

        assertEquals(2_000, window.limit(), "the superseded reveal's page is not applied");
        assertEquals(0, ran[0]);
        assertEquals(1, ran[1]);
        assertEquals(0, events.busyDepth);
    }

    @Test
    void aRevealFailingSaysSoAndEndsItsBusySpan() {
        opened();
        int[] ran = {0};

        window.reveal(commit(5_000), "c5000", () -> ran[0]++);
        source.fail(source.pending.get(0), "disk full");

        assertEquals(List.of("busy Loading history to reach c5000", "idle", "paging failed disk full"), events.log);
        assertEquals(0, ran[0]);
    }

    @Test
    void anExcludedTipIsPagedForOnlyWhenTheGraphReachesIt() {
        opened();
        int[] ran = {0};

        window.revealExcludedTip(hash(5_000), commit(5_000), "c5000", () -> ran[0]++);
        assertEquals(List.of(hash(5_000)), source.reachesAsked);
        assertEquals(List.of("busy Looking for c5000"), events.log);
        source.reachAnswers.get(0).accept(false);

        assertEquals(List.of(), source.pending, "a tip only the hidden ref reaches is not hunted for");
        assertEquals(1, ran[0]);
        assertEquals(0, events.busyDepth);

        window.revealExcludedTip(hash(5_000), commit(5_000), "c5000", () -> ran[0]++);
        source.reachAnswers.get(1).accept(true);
        assertEquals(1, source.pending.size());
        source.answerOldest();
        assertTrue(window.rows().stream().anyMatch(commit(5_000)));
        assertEquals(2, ran[0]);
        assertEquals(0, events.busyDepth);
    }

    /**
     * The answer to "does the graph reach it" for an excluded tip, landing after a newer
     * reveal had started, used to start paging for the tip and cancel the newer one: the
     * click the user made last lost to the one before it.
     */
    @Test
    void aReachAnswerForASupersededRevealStartsNothing() {
        opened();
        int[] ran = {0, 0};

        window.revealExcludedTip(hash(5_000), commit(5_000), "c5000", () -> ran[0]++);
        window.reveal(commit(1_500), "c1500", () -> ran[1]++);
        source.reachAnswers.get(0).accept(true);

        assertEquals(1, source.pending.size(), "only the newer reveal's walk");
        source.answerOldest();
        assertTrue(window.rows().stream().anyMatch(commit(1_500)));
        assertEquals(0, ran[0]);
        assertEquals(1, ran[1]);
        assertEquals(0, events.busyDepth);
    }

    @Test
    void aSourceThatAnswersAtOnceStillPairsBusyWithIdle() {
        source.answerAtOnce = true;
        window.refresh();
        int[] ran = {0};

        window.reveal(commit(5_000), "c5000", () -> ran[0]++);
        window.loadOlder();

        assertEquals(1, ran[0]);
        assertEquals(0, events.busyDepth);
        assertEquals(8_000 + HistoryWindow.PAGE_STEP, window.rows().size());
    }

    // ------------------------------------------------------------------
    // Reset
    // ------------------------------------------------------------------

    @Test
    void aResetDropsBackToTheFirstPageAndCancelsPagingInFlight() {
        opened();
        window.loadOlder();
        Pending older = source.pending.get(0);
        window.reveal(commit(5_000), "c5000", () -> { });
        Pending reveal = source.pending.get(1);
        firstPageSize = 500;

        window.reset();

        assertTrue(older.cancelled);
        assertTrue(reveal.cancelled);
        assertEquals(0, events.busyDepth, "both busy spans end with the reset");
        assertEquals(500, window.limit(), "the first-page size is read again");
        source.answer(older);
        source.answer(reveal);
        assertEquals(FIRST_PAGE, window.rows().size(), "nothing the old engine was walking lands");
    }

    @Test
    void aRefreshAskedBeforeAResetIsIgnoredAndTheNextOneReplacesThePageRegardless() {
        opened();
        window.refresh();
        Pending stale = source.pending.get(0);

        window.reset();
        source.answer(stale);
        assertEquals(List.of(), events.log, "read by the engine the reset replaced");

        window.refresh();
        source.answerOldest();
        assertEquals(List.of("refreshed 1000", "page REFRESHED"), events.log,
                "the page shown came from the old engine: replaced even though it draws the same");
    }

    // ------------------------------------------------------------------
    // The filter and the "load more" row
    // ------------------------------------------------------------------

    @Test
    void unfilteredTheGraphListsThePageThenTheLoadMoreRow() {
        opened();

        HistoryWindow.Shown shown = window.filter(null);

        assertSame(window.rows(), shown.rows());
        assertEquals(FIRST_PAGE + 1, shown.items().size());
        assertTrue(shown.items().get(FIRST_PAGE).isLoadMore());
        assertFalse(shown.filtering());
        assertFalse(shown.partial());
        assertSame(shown, window.filter(null), "a keystroke that dims rather than narrows changes nothing");
    }

    @Test
    void aFilterKeepsItsRowsAndStillOffersOlderHistory() {
        opened();

        HistoryWindow.Shown shown = window.filter(commit(3).or(commit(7)));

        assertEquals(List.of(hash(3), hash(7)),
                shown.rows().stream().map(row -> row.getCommit().getHash()).toList());
        assertTrue(shown.items().get(2).isLoadMore(),
                "a search over a truncated history that found nothing must not look conclusive");
        assertTrue(shown.filtering());
        assertTrue(shown.partial());
        assertEquals(2, shown.matched());
        assertEquals(FIRST_PAGE, shown.total());
    }

    @Test
    void withAllHistoryLoadedThereIsNoLoadMoreRow() {
        History small = new History(10);
        Source smallSource = new Source(small);
        HistoryWindow smallWindow = new HistoryWindow(smallSource, events, highlight, () -> FIRST_PAGE);
        smallWindow.refresh();
        smallSource.answerOldest();

        HistoryWindow.Shown shown = smallWindow.filter(commit(3));

        assertEquals(1, shown.items().size());
        assertFalse(shown.partial(), "nothing unwalked, so the search is conclusive");
    }

    @Test
    void theFilterFollowsTheNewPage() {
        opened();
        window.filter(commit(1_500));
        assertEquals(List.of(), window.shown().rows());

        window.loadOlder();
        source.answerOldest();

        assertEquals(1, window.shown().rows().size(), "the kept filter applies to the page that replaced it");
    }

    // ------------------------------------------------------------------
    // What else follows the page
    // ------------------------------------------------------------------

    @Test
    void everyNewPagePointsTheHighlightAtItself() {
        opened();
        highlight.hoverBranch(hash(1_500));
        assertFalse(highlight.isActive(), "not on the first page");

        window.reveal(commit(1_500), "c1500", () -> { });
        source.answerOldest();

        highlight.hoverBranch(hash(1_500));
        assertTrue(highlight.isActive(), "the revealed page is the one the hover walks");
    }

    @Test
    void theGhostsAndBranchComeFromTheLoad() {
        HistoryWindow.Loaded loaded = history.loaded(FIRST_PAGE);
        window.showCached(loaded);

        assertSame(loaded.ghosts(), window.ghosts(), "worked out where it was loaded, not again on the FX thread");
        assertEquals("main", window.ghosts().branchFor(hash(10)));
        assertEquals("main", window.currentBranch());
    }

    @Test
    void rowsShownDirectlyChangeOnlyTheRows() {
        opened();
        int limit = window.limit();
        List<GitGraphRow> rows = history.page(5).rows();

        window.showRows(rows);

        assertEquals(5, window.rows().size());
        assertNotSame(rows, window.rows());
        assertEquals(limit, window.limit());
        assertTrue(window.hasMore());
        assertEquals("main", window.ghosts().branchFor(hash(3)));
        assertEquals(List.of(), events.log, "the caller fills the list itself");
    }

    @Test
    void aWalkStopsBetweenPagesOnceCancelled() {
        HistoryWindow.Walk walk = HistoryWindow.Walk.doubling(1_000, row -> false);

        HistoryWindow.Walked walked = walk.run(history::page, () -> "main", () -> true);

        assertEquals(2_000, walked.limit());
        assertEquals(List.of(2_000), history.walked);
    }

    // ------------------------------------------------------------------
    // However a paging ends, its busy span ends with it
    // ------------------------------------------------------------------

    /**
     * The status bar's Cancel reaches the task it is following, and a "load more" queued
     * behind a fetch is often that task. Cancelled before it started, it never answered:
     * the spinner turned for the rest of the session and every later "load more" was
     * ignored, waiting on it.
     */
    @Test
    void aLoadMoreCancelledFromElsewhereEndsItsBusySpanAndLetsTheUserTryAgain() {
        opened();

        window.loadOlder();
        source.drop(source.pending.get(0));

        assertEquals(List.of("busy Loading older commits", "idle"), events.log,
                "the span ends; a cancel the user asked for is not reported as a failure");
        assertEquals(FIRST_PAGE, window.limit(), "the depth it asked for was never drawn");
        window.loadOlder();
        assertEquals(1, source.pending.size(), "load more is not locked behind the dropped request");
        assertEquals(FIRST_PAGE + HistoryWindow.PAGE_STEP, source.pending.get(0).walk.limit());
    }

    @Test
    void aRevealCancelledFromElsewhereEndsItsBusySpanAndSelectsNothing() {
        opened();
        int[] ran = {0};

        window.reveal(commit(5_000), "c5000", () -> ran[0]++);
        source.drop(source.pending.get(0));

        assertEquals(List.of("busy Loading history to reach c5000", "idle"), events.log);
        assertEquals(0, ran[0], "cancelled, so nothing is selected for it");
    }

    @Test
    void anExcludedTipsQuestionCancelledFromElsewhereEndsItsBusySpan() {
        opened();
        int[] ran = {0};

        window.revealExcludedTip(hash(5_000), commit(5_000), "c5000", () -> ran[0]++);
        source.reachDrops.get(0).run();

        assertEquals(0, events.busyDepth);
        assertEquals(List.of(), source.pending, "nothing is paged for a question nobody answered");
        assertEquals(0, ran[0]);
    }

    /** A view that throws while drawing the revealed page used to skip the end of the span. */
    @Test
    void aRevealedPageThatFailsToDrawStillEndsItsBusySpan() {
        opened();
        events.onPageChanged = () -> {
            throw new IllegalStateException("the list could not be rebuilt");
        };

        window.reveal(commit(5_000), "c5000", () -> { });
        assertThrows(IllegalStateException.class, source::answerOldest, "the view's failure is not swallowed");

        assertEquals(0, events.busyDepth, "the status bar was left busy for good");
    }

    /** Thrown while drawing, an older page left "load more" ignored for the rest of the session. */
    @Test
    void anOlderPageThatFailsToDrawDoesNotLockLoadMore() {
        opened();
        events.onPageChanged = () -> {
            throw new IllegalStateException("the list could not be rebuilt");
        };
        window.loadOlder();
        assertThrows(IllegalStateException.class, source::answerOldest);
        assertEquals(0, events.busyDepth);
        events.onPageChanged = () -> { };

        window.loadOlder();

        assertEquals(1, source.pending.size(), "a page that failed to draw must not leave paging locked");
    }

    // ------------------------------------------------------------------
    // A reveal against a "load more" still loading
    // ------------------------------------------------------------------

    /**
     * "Load more" raises the depth when it is asked, not when its page is drawn. A reveal
     * landing in between was compared with the raised depth, thrown away as stale, and its
     * commit then reported as not shown - while the longer page, landing later, selected
     * nothing.
     */
    @Test
    void aRevealLandingWhileALoadMoreIsStillLoadingIsDrawn() {
        window.setLimitForTest(500);
        opened();
        boolean[] onPage = {false};

        window.reveal(commit(800), "c800", () -> onPage[0] = window.rows().stream().anyMatch(commit(800)));
        Pending reveal = source.pending.get(0);
        window.loadOlder();
        assertEquals(1_500, window.limit(), "precondition: load more has raised the depth past the reveal's");
        source.answer(reveal);

        assertTrue(onPage[0], "the reveal's page is deeper than the page on screen, so it is drawn");
        source.answerOldest();
        assertEquals(1_500, window.rows().size(), "and the load more's page still follows it");
        assertEquals(0, events.busyDepth);
    }

    /** A selection the view made without paging is newer than the reveal still paging. */
    @Test
    void aRevealCancelledForANewerSelectionDrawsAndSelectsNothing() {
        opened();
        int[] ran = {0};
        window.reveal(commit(5_000), "c5000", () -> ran[0]++);
        Pending reveal = source.pending.get(0);

        window.cancelReveal();

        assertTrue(reveal.cancelled);
        assertEquals(0, events.busyDepth);
        // Already running when it was cancelled: it stops between pages and still answers.
        source.answer(reveal);
        assertEquals(FIRST_PAGE, window.rows().size(), "its page is not drawn");
        assertEquals(0, ran[0], "and what it would have selected is not");
        window.cancelReveal();
        assertEquals(List.of("busy Loading history to reach c5000", "idle"), events.log,
                "with nothing in flight there is nothing to cancel");
    }

    // ------------------------------------------------------------------
    // A refresh dropped for a "load more"
    // ------------------------------------------------------------------

    /**
     * A refresh that lands while a "load more" loads is left to that longer page. When the
     * load failed instead, the commit the refresh was for never reached the graph - though
     * the sidebar had already been handed its refs.
     */
    @Test
    void aRefreshLeftToALoadMoreThatFailsIsAskedAgain() {
        opened();
        history.generation++;
        window.refresh();
        window.loadOlder();
        source.answerOldest();

        source.fail(source.pending.get(0), "pack corrupt");

        assertEquals(1, source.pending.size(), "the refresh is asked again");
        assertEquals(FIRST_PAGE, source.pending.get(0).walk.limit(), "at the depth on screen");
        source.answerOldest();
        assertEquals("page REFRESHED", events.pages().get(events.pages().size() - 1));
    }

    @Test
    void aRefreshLeftToALoadMoreThatIsCancelledIsAskedAgain() {
        opened();
        history.generation++;
        window.refresh();
        window.loadOlder();
        source.answerOldest();

        source.drop(source.pending.get(0));

        assertEquals(1, source.pending.size(), "the refresh is asked again");
        assertEquals(FIRST_PAGE, source.pending.get(0).walk.limit());
    }

    /**
     * The load more lands, but behind a deeper revealed page, so it is not drawn either -
     * and the reveal was asked before the commit the refresh was for.
     */
    @Test
    void aRefreshLeftToALoadMoreThatLandsStaleIsAskedAgain() {
        opened();
        window.reveal(commit(5_000), "c5000", () -> { });
        Pending reveal = source.pending.get(0);
        history.generation++;
        window.refresh();
        window.loadOlder();
        Pending refresh = source.pending.get(1);
        Pending older = source.pending.get(2);
        source.answer(refresh);
        source.answer(reveal);
        assertEquals(8_000, window.limit());

        source.answer(older);

        assertEquals(1, source.pending.size(), "the refresh is asked again");
        assertEquals(8_000, source.pending.get(0).walk.limit(), "at the depth the reveal drew");
    }

    // ------------------------------------------------------------------
    // A refresh failing for the engine a reset replaced
    // ------------------------------------------------------------------

    /**
     * Only its success was checked against the reset: its failure still reached the view,
     * which showed "the history could not be read" over a repository the new engine reads
     * fine.
     */
    @Test
    void aRefreshFailingForTheEngineAResetReplacedIsNotReported() {
        opened();
        window.refresh();
        Pending stale = source.pending.get(0);

        window.reset();
        source.fail(stale, "the repository is closed");

        assertEquals(List.of(), events.log, "the reset's own refresh follows, and decides");
    }

    // ------------------------------------------------------------------
    // What the signature covers
    // ------------------------------------------------------------------

    /**
     * Setting {@code main}'s upstream to {@code origin/main}, on the same commit, folds the
     * two chips into one - but the signature left upstreams out, so the refresh after it
     * drew "the same" and the two chips stayed apart.
     */
    @Test
    void settingAnUpstreamRedrawsThePage() {
        history.remote = "origin";
        history.upstreams = Map.of();
        opened();
        CommitInfo before = window.rows().get(0).getCommit();

        history.upstreams = Map.of("main", "origin/main");
        assertNotEquals(RefRail.chipsOf(before, "main"), RefRail.chipsOf(history.page(1).rows().get(0).getCommit(), "main"),
                "precondition: the chips the new page draws differ");
        window.refresh();
        source.answerOldest();

        assertEquals(List.of("refreshed 1000", "page REFRESHED"), events.log);
    }

    // ------------------------------------------------------------------
    // The module stays testable as plain values
    // ------------------------------------------------------------------

    /** Nothing from JavaFX in the window, so all of the above runs without a toolkit. */
    @Test
    void theHistoryWindowImportsNothingFromJavaFx() throws Exception {
        Path source = Path.of("src/main/java/com/jfx/git/graph/HistoryWindow.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        assertFalse(text.contains("javafx."), "HistoryWindow reaches into JavaFX");
    }
}
