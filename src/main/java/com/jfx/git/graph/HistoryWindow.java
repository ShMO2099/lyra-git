package com.jfx.git.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * How much of a repository's history the graph has loaded, and every decision about which
 * page that is.
 *
 * <h2>What it owns</h2>
 * The page on screen - its rows, how far back it was walked, whether more follows, the
 * branch its chips are drawn against, each commit's nearest branch and the page's
 * signature - and every way that page is replaced: a refresh at the same depth, the page
 * kept from the last session, a page of older commits, and paging in until a commit
 * someone asked for is there. It also owns the graph filter's output (the rows the filter
 * keeps, with the "load more" row after them) and points {@link GraphHighlight} at each
 * new page, so no path that replaces the rows can forget to.
 *
 * <h2>What it does not</h2>
 * It reads no repository and draws nothing. Pages come from a {@link Source} that walks
 * history off the FX thread and answers on it, and what the page means for the list, the
 * selection, the viewport and the status bar is the {@link Listener}'s business. That
 * split is what lets the rules below be tested with a scripted source - answering at once,
 * out of order, or not at all - instead of through a whole window.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li>Paging rebuilds from the top: lanes are assigned across the whole walk, so a page
 *       of older commits is a longer walk, not rows appended.</li>
 *   <li>A page that lands shorter than the one drawn is stale and is dropped.</li>
 *   <li>A reveal doubles the walk until the commit is there, history ends or
 *       {@link #REVEAL_LIMIT} is reached; a newer reveal - or a selection made since that
 *       needed no paging - supersedes an older one.</li>
 *   <li>Every {@link Listener#busy} is followed by exactly one {@link Listener#idle}, however
 *       the paging ends - landed, failed, superseded, reset, cancelled by someone else
 *       before it started, or its page thrown by the view drawing it.</li>
 *   <li>A refresh left to a "load more" in flight is asked again if that load draws
 *       nothing.</li>
 * </ul>
 *
 * <p>FX thread only, like the list it feeds; nothing here is synchronised.
 */
public final class HistoryWindow {

    /**
     * The most commits a reveal pages the graph through to reach a commit.
     *
     * <p>Well past any first page, and past where "Show all" would have got to by ordinary
     * scrolling in a sitting. Beyond it the commit's details are shown without the graph,
     * rather than laying out a hundred thousand rows to put one in view.
     */
    public static final int REVEAL_LIMIT = 100_000;

    /**
     * Commits added per "load older" step - fixed, unlike the first page, which
     * Preferences can size differently.
     */
    public static final int PAGE_STEP = com.jfx.git.core.GitService.DEFAULT_GRAPH_LIMIT;

    /**
     * First page in "Show all" mode.
     *
     * <p>"Show all" used to mean a limit of ten million, which asked the engine for the
     * entire history in one walk - and then ordered it through a priority queue and
     * assigned lanes across all of it - before the first row could be drawn. On a
     * hundred-thousand-commit repository that is the difference between "opens" and
     * "appears hung", for a window that shows forty rows.
     *
     * <p>So it now means "do not stop paging", not "walk everything first": a larger first
     * page than the default, and the Load-more row at the bottom keeps walking older
     * history for as long as the user scrolls. What the user sees is unchanged except that
     * it arrives.
     */
    public static final int SHOW_ALL_FIRST_PAGE = 5_000;

    /**
     * The graph's first-page size, honouring Preferences' "Show All Commits in Graph".
     *
     * <p>Read fresh rather than cached in a constant: the user can change either setting
     * from the Settings tab mid-session, and the next refresh - not just the next launch -
     * should draw from it.
     */
    public static int preferredFirstPage() {
        return com.jfx.git.core.AppPreferences.showAllCommitsInGraph()
                ? SHOW_ALL_FIRST_PAGE
                : com.jfx.git.core.AppPreferences.initialGraphCommits();
    }

    // ------------------------------------------------------------------
    // What a page is, and where it comes from
    // ------------------------------------------------------------------

    /**
     * One page of the graph, with what is derived from it worked out where it was loaded.
     *
     * @param branch    the branch HEAD was on when the page was read, blank while detached;
     *                  the chips are drawn against it
     * @param ghosts    the nearest branch for each commit; a walk over the whole page that
     *                  ran on the FX thread after every load
     * @param signature what the page draws, as text, so a reload that changed nothing can
     *                  be recognised and left alone
     */
    public record Loaded(GraphPage page, String branch, GhostBranches ghosts, String signature) {

        /**
         * Built off the FX thread: both derived values are computed here. The branch is
         * the page's own ref snapshot's - read with the page, not asked for again beside it.
         */
        public static Loaded of(GraphPage page) {
            return of(page, page.refs().currentBranch());
        }

        /** {@link #of(GraphPage)}, drawing the chips against {@code branch}: a test's page. */
        public static Loaded of(GraphPage page, String branch) {
            return new Loaded(page, branch, GhostBranches.of(page.rows()), signatureOf(page, branch));
        }

        /**
         * Everything a page draws, as one string: each row's kind, commit, lanes, colour
         * and ref labels - with what each local branch among them tracks - the branch the
         * chips are drawn against, and whether more follows. Two loads with the same
         * signature draw the same graph.
         */
        public static String signatureOf(GraphPage page, String branch) {
            StringBuilder out = new StringBuilder(page.rows().size() * 64);
            out.append(branch).append('|').append(page.hasMore()).append('\n');
            for (GitGraphRow row : page.rows()) {
                out.append(row.getKind()).append(' ').append(row.getTotalLanes());
                var commit = row.getCommit();
                if (commit != null) {
                    out.append(' ').append(commit.getHash()).append(' ').append(commit.getLane())
                            .append(' ').append(commit.getColorIndex())
                            .append(' ').append(commit.getParentHashes());
                    var upstreams = commit.getUpstreams();
                    for (var ref : commit.getRefs()) {
                        out.append(' ').append(ref.fullName()).append('=').append(ref.targetHash());
                        // The chips fold a remote into the local branch that tracks it, so
                        // "Set upstream" between a branch and its namesake on the same
                        // commit redraws them as one: left out, the refresh after it drew
                        // "the same" and the two chips stayed apart. Null upstreams are not
                        // known, and the chips fold by name alone.
                        if (ref.isLocalBranch() && upstreams != null) {
                            out.append('>').append(upstreams.get(ref.shortName()));
                        }
                    }
                }
                if (row.isStash()) {
                    out.append(' ').append(row.getStash().hash()).append(' ').append(row.getStashLane());
                }
                out.append(' ').append(row.getThroughLanes()).append('\n');
            }
            return out.toString();
        }
    }

    /** The page a {@link Walk} ended on, and the limit it was walked to. */
    public record Walked(Loaded loaded, int limit) {
    }

    /**
     * How far to walk history for one request: a single page of a fixed size, or - for a
     * reveal - doubling from a size until a row turns up.
     *
     * <p>The source runs it on its worker with {@link #run}, so a reveal is one request
     * however many pages it reads, and cancelling that request stops it between pages.
     * Each page is walked from the top again, so doubling costs about twice the final page
     * where stepping would cost the square of the distance.
     */
    public static final class Walk {

        private final int limit;
        private final Predicate<GitGraphRow> until;

        private Walk(int limit, Predicate<GitGraphRow> until) {
            this.limit = limit;
            this.until = until;
        }

        /** One page of {@code limit} commits. */
        static Walk page(int limit) {
            return new Walk(limit, null);
        }

        /** Doubling from {@code from}, until a row {@code until} accepts is on the page. */
        static Walk doubling(int from, Predicate<GitGraphRow> until) {
            return new Walk(from, until);
        }

        /** The size of the page this walks, or of the page a reveal doubles from. */
        public int limit() {
            return limit;
        }

        /**
         * Walks it, off the FX thread. The chips are drawn against the current branch of
         * the page walked last, from the ref snapshot it carries.
         *
         * @param pages     reads the page of the newest {@code n} commits
         * @param cancelled whether the request has been cancelled; checked between pages
         */
        public Walked run(IntFunction<GraphPage> pages, BooleanSupplier cancelled) {
            return run(pages, null, cancelled);
        }

        /**
         * {@link #run(IntFunction, BooleanSupplier)}, with the branch read by
         * {@code branch} instead, once, for the page walked last - for pages built without
         * a ref snapshot. Null takes the page's own.
         */
        public Walked run(IntFunction<GraphPage> pages, Supplier<String> branch, BooleanSupplier cancelled) {
            if (branch == null) {
                return walk(pages, cancelled, Loaded::of);
            }
            return walk(pages, cancelled, page -> Loaded.of(page, branch.get()));
        }

        private Walked walk(IntFunction<GraphPage> pages, BooleanSupplier cancelled,
                            java.util.function.Function<GraphPage, Loaded> loaded) {
            if (until == null) {
                return new Walked(loaded.apply(pages.apply(limit)), limit);
            }
            int at = limit;
            while (true) {
                at = (int) Math.min(REVEAL_LIMIT, (long) Math.max(1, at) * 2);
                GraphPage page = pages.apply(at);
                boolean found = page.rows().stream().anyMatch(until);
                if (found || !page.hasMore() || at >= REVEAL_LIMIT || cancelled.getAsBoolean()) {
                    return new Walked(loaded.apply(page), at);
                }
            }
        }
    }

    /** A request a {@link Source} is working on. */
    @FunctionalInterface
    public interface Request {
        /**
         * Asks it to stop. One that has not started never answers; one that has may
         * still answer, and the window ignores it.
         */
        void cancel();
    }

    /** Where pages come from: the repository, walked off the FX thread. */
    public interface Source {

        /**
         * Runs {@code walk} on a worker and answers on the FX thread with the page it ended
         * on, with why it failed, or - when it was cancelled before it started, or its
         * repository closed first - with {@code onDropped}, since it never will. Exactly one
         * of the three; once the window has cancelled the request, whichever comes is
         * ignored.
         *
         * <p>The drop has to be said because the window is not the only one holding the
         * request: the status bar's Cancel reaches the task it is following, which is often
         * this one. Dropped there in silence, a "load more" waited on it for good.
         */
        Request load(Walk walk, Consumer<Walked> onLoaded, Consumer<String> onFailed, Runnable onDropped);

        /**
         * Whether the graph's walk - what Hide/Solo leaves shown - reaches {@code hash},
         * answered on the FX thread like {@link #load}. Asked before paging in for the tip
         * of a ref Hide/Solo excludes, which the graph shows only when a shown branch also
         * reaches it.
         */
        Request reaches(String hash, Consumer<Boolean> onAnswer, Consumer<String> onFailed, Runnable onDropped);

        /** Keeps the first page for the next opening of this repository; see GraphPageCache. */
        default void keepFirstPage(GraphPage page, String branch) {
        }
    }

    /** Why the page on screen was replaced. */
    public enum Change {
        /** The page kept from the last session, drawn while the real one loads. */
        CACHED,
        /** A refresh at the same depth found the page changed. */
        REFRESHED,
        /** A page of older commits, asked for with the "load more" row. */
        OLDER,
        /** Paged in to reach a commit someone asked for. */
        REVEALED
    }

    /** What the window tells the view. Every method runs on the FX thread. */
    public interface Listener {

        /**
         * The page was replaced; the rows, the filter's output and everything else this
         * window answers are already the new page's.
         *
         * @param first whether it is the first page this window has shown
         */
        void pageChanged(Change change, boolean first);

        /**
         * A refresh came back, whether or not it replaced the page shown - so its ref
         * snapshot is the newest even when the page draws what the one on screen does.
         */
        default void refreshed(GraphPage page) {
        }

        /** A refresh failed. */
        default void refreshFailed(String error) {
        }

        /** Paging older history in failed. */
        default void pagingFailed(String error) {
        }

        /** Paging began; say {@code what} until {@link #idle}, which always follows. */
        default void busy(String what) {
        }

        /** The paging {@link #busy} announced is over. */
        default void idle() {
        }
    }

    /**
     * What the graph lists under the current filter.
     *
     * @param rows      the page's rows the filter keeps, laid out among themselves; the
     *                  page's own list, unchanged, while nothing is filtered
     * @param items     {@code rows} with the "load more" row after them while older
     *                  history exists - kept while filtering too, since a search over a
     *                  truncated history that found nothing would otherwise look conclusive
     * @param filtering whether a filter is narrowing the page
     * @param partial   whether it narrows a page with older history still unwalked
     * @param matched   commits the filter kept
     * @param total     commits on the page
     */
    public record Shown(List<GitGraphRow> rows, List<GitGraphRow> items, boolean filtering,
                        boolean partial, long matched, long total) {
    }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private final Source source;
    private final Listener listener;
    private final GraphHighlight highlight;
    private final IntSupplier firstPageSize;

    /** The page: rows, whether more follows, and what was derived from it. */
    private List<GitGraphRow> rows = List.of();
    private boolean hasMore;
    private com.jfx.git.model.RefSnapshot refs = com.jfx.git.model.RefSnapshot.EMPTY;
    private String branch;
    private GhostBranches ghosts = GhostBranches.EMPTY;
    /** The signature of the page on screen; null when the next refresh must replace it. */
    private String signature;
    /**
     * How far back the graph walks: the page on screen's depth, or deeper while a "load
     * more" is loading, since that raises it as it is asked.
     */
    private int limit;
    /**
     * The depth of the page on screen; 0 while nothing trusted is - a kept page, or one the
     * engine a reset replaced. What a reveal's page is compared with: against {@link #limit},
     * one landing while a "load more" loaded was dropped as stale before that load had drawn
     * anything.
     */
    private int drawn;
    private boolean firstPageShown;

    /** What the filter keeps, or null for everything; and its output, worked out once. */
    private Predicate<GitGraphRow> keep;
    private Shown shown;

    /** Bumped by {@link #reset}, so a refresh asked for before it is not applied after it. */
    private int epoch;
    /** The "load more" in flight, or null. */
    private Paging older;
    /**
     * Whether a refresh was left to the "load more" in flight, and is owed should that load
     * not draw its page.
     */
    private boolean refreshOwed;
    /** The reveal in flight, or null; a newer one supersedes it. */
    private Paging revealing;

    public HistoryWindow(Source source, Listener listener, GraphHighlight highlight, IntSupplier firstPageSize) {
        this.source = source;
        this.listener = listener;
        this.highlight = highlight;
        this.firstPageSize = firstPageSize;
        this.limit = firstPageSize.getAsInt();
    }

    // ------------------------------------------------------------------
    // What is loaded
    // ------------------------------------------------------------------

    /** The unfiltered page, newest first; what the fade and the author menu walk. */
    public List<GitGraphRow> rows() {
        return rows;
    }

    /** Whether history continues below the page. */
    public boolean hasMore() {
        return hasMore;
    }

    /**
     * The ref snapshot of the page on screen; {@link com.jfx.git.model.RefSnapshot#EMPTY}
     * before one. A refresh whose page draws the same keeps the page - and this - while
     * {@link Listener#refreshed} still hands on the newer snapshot.
     */
    public com.jfx.git.model.RefSnapshot refs() {
        return refs;
    }

    /**
     * The branch HEAD was on as of the last page load - blank while detached - or null
     * before one.
     *
     * <p>Kept because the two places that need it - the metrics update on every page
     * render, and the same update on every keystroke in the graph filter box - run on the
     * FX thread, and on the CLI engine the backend call behind it is a subprocess.
     */
    public String currentBranch() {
        return branch;
    }

    /**
     * Records the branch read outside a page load, for a view filled without one; the next
     * load overwrites it.
     */
    public void assumeCurrentBranch(String branch) {
        this.branch = branch;
    }

    /** Each commit's nearest branch, for the hover label. */
    public GhostBranches ghosts() {
        return ghosts;
    }

    /** How many commits the graph asks for when it walks history. */
    public int limit() {
        return limit;
    }

    /** Whether a page - kept or loaded - has been shown. */
    public boolean firstPageShown() {
        return firstPageShown;
    }

    // ------------------------------------------------------------------
    // The filter
    // ------------------------------------------------------------------

    /**
     * Narrows the graph to the rows {@code keep} accepts, or shows every row when null,
     * and returns what the graph now lists.
     */
    public Shown filter(Predicate<GitGraphRow> keep) {
        // Every keystroke of a search that dims rather than narrows lands here with no
        // filter; the page has not changed, so neither has what it lists.
        if (keep != null || this.keep != null) {
            this.keep = keep;
            this.shown = null;
        }
        return shown();
    }

    /** What the graph lists: the page under the current filter, then the "load more" row. */
    public Shown shown() {
        if (shown == null) {
            boolean filtering = keep != null;
            // Laid out afresh over the kept rows: drawn with the full page's lanes, each
            // row's edges ran into whatever unrelated commit the filter left beside it.
            List<GitGraphRow> kept = filtering ? FilteredGraph.relayout(rows, keep) : rows;
            List<GitGraphRow> items = new ArrayList<>(kept.size() + 1);
            items.addAll(kept);
            if (hasMore) {
                items.add(GitGraphRow.loadMore());
            }
            shown = new Shown(kept, java.util.Collections.unmodifiableList(items), filtering,
                    filtering && hasMore, commits(kept), commits(rows));
        }
        return shown;
    }

    private static long commits(List<GitGraphRow> rows) {
        return rows.stream().filter(row -> row.getCommit() != null).count();
    }

    // ------------------------------------------------------------------
    // Replacing the page
    // ------------------------------------------------------------------

    /**
     * Reloads the page at the depth it has now.
     *
     * <p>What comes back replaces the page only if it draws something different: a reload
     * that changed nothing would otherwise re-run the selection routing - rebuilding the
     * detail panel for the commit already shown - and reset every visible row.
     */
    public void refresh() {
        int asked = limit;
        int askedIn = epoch;
        source.load(Walk.page(asked),
                walked -> refreshed(asked, askedIn, walked.loaded()),
                error -> {
                    // Like its page: the engine that failed it is gone, and the reset's own
                    // refresh says how the new one reads the repository. Reported, a
                    // repository the new engine reads fine said its history could not be read.
                    if (askedIn == epoch) listener.refreshFailed(error);
                },
                // No busy span waits on a refresh, and asking again here would spin for
                // as long as a closed repository drops every request: the next change asks.
                () -> { });
    }

    private void refreshed(int asked, int askedIn, Loaded loaded) {
        // Asked of the engine a reset has since replaced; the reset's own refresh follows.
        if (askedIn != epoch) return;
        if (asked < limit) {
            if (older != null && older.walksTo >= limit) {
                // "Load more" raised the limit after this was asked, and its own load -
                // asked later, so of the same history - brings the longer page. Applied,
                // this one would throw away the rows it adds; asked again, the longer
                // page would be walked twice. Owed, though, in case that page is never
                // drawn: the commit this refresh was for would otherwise never reach the
                // graph, though the sidebar has already been handed its refs.
                refreshOwed = true;
                listener.refreshed(loaded.page());
                return;
            }
            // A reveal landed while this was queued, and this is the shorter page:
            // applied, it would throw away the rows it loaded - among them the commit a
            // sidebar click had only just selected. The reveal's page may predate what this
            // refresh is for, so it is asked again, at the limit the graph has now.
            refresh();
            return;
        }
        listener.refreshed(loaded.page());
        if (firstPageShown && loaded.signature().equals(signature)) {
            // The page on screen is this depth's, and current.
            drawn = asked;
            return;
        }
        install(loaded, Change.REFRESHED, asked);
        if (asked == firstPageSize.getAsInt()) {
            // The first page, as the next opening of this repository will want it.
            source.keepFirstPage(loaded.page(), loaded.branch());
        }
    }

    /**
     * Draws the page this repository showed when it was last open, until the real one
     * lands - which leaves it alone when its signature matches, and replaces it when the
     * repository changed in between.
     *
     * <p>Only while nothing has been drawn: a kept page arriving after the live one would
     * put old rows back.
     *
     * @return whether it was drawn
     */
    public boolean showCached(Loaded cached) {
        if (firstPageShown) return false;
        // At no depth: a page from the last session is shown, not trusted, and no page
        // walked now is too short to replace it.
        install(cached, Change.CACHED, 0);
        return true;
    }

    /**
     * Walks another page of history.
     *
     * <p>The graph is rebuilt from the start rather than appended to, because lanes are
     * assigned across the whole walk. The rows already on screen come back identical -
     * same commits, same order, same lanes - because {@link CommitOrdering#page} orders
     * history a fixed chunk at a time and a bigger page only adds chunks after the ones
     * already shown.
     *
     * <p>Ignored while one is already loading: a list rebuild re-selects the "load more"
     * row, which must not page again.
     */
    public void loadOlder() {
        if (older != null) return;
        // Reached in "Show All" mode too, and deliberately: that setting means the paging
        // never stops, not that the first page walks the entire history.
        int before = limit;
        limit += PAGE_STEP;
        int asked = limit;
        Paging paging = new Paging();
        paging.walksTo = asked;
        older = paging;
        paging.busy("Loading older commits");
        paging.load(Walk.page(asked),
                walked -> {
                    paging.end();
                    try {
                        // A reveal landed a longer page while this loaded; this one is stale.
                        if (asked >= limit) {
                            // Asked after any refresh left to it, so of history as new.
                            refreshOwed = false;
                            install(walked.loaded(), Change.OLDER, asked);
                        }
                    } finally {
                        // Only now: the list rebuilt for the new page can re-select the
                        // "load more" row, and that must not page again. But however the
                        // drawing went: left set by a list that threw, every later "load
                        // more" was ignored for the rest of the session.
                        older = null;
                    }
                    askOwedRefresh();
                },
                error -> {
                    olderNotDrawn(before);
                    paging.end();
                    listener.pagingFailed(error);
                    askOwedRefresh();
                },
                // Cancelled before it started - by the status bar's Cancel, aimed at it as
                // the task it follows. Nothing to report: the user asked for it.
                () -> {
                    olderNotDrawn(before);
                    paging.end();
                    askOwedRefresh();
                });
    }

    /**
     * A "load more" that ended without drawing its page: paging is unlocked, and the depth
     * it raised as it was asked is given back - unless something drawn since went deeper -
     * so the next refresh, and the next "load more", walk from what is on screen.
     */
    private void olderNotDrawn(int before) {
        older = null;
        limit = Math.max(before, drawn);
    }

    /** Asks the refresh left to a "load more" that did not draw its page. */
    private void askOwedRefresh() {
        if (!refreshOwed) return;
        refreshOwed = false;
        refresh();
    }

    /**
     * Pages older history in until a row {@code isIt} accepts is on the page, then runs
     * {@code then} - which also runs, at once, when paging could not bring it: history is
     * all loaded, the cap is reached, or the row is loaded already and only the view hides
     * it. {@code then} does not run when a newer reveal, a {@link #cancelReveal} or a reset
     * supersedes this one, or when the paging fails or is cancelled.
     *
     * @param what names the row, for the status bar while it pages
     */
    public void reveal(Predicate<GitGraphRow> isIt, String what, Runnable then) {
        Paging paging = supersedeReveal();
        pageIn(paging, isIt, what, then);
    }

    /**
     * {@link #reveal} for the tip of a branch or tag Hide/Solo excludes.
     *
     * <p>Such a tip is not a start of the walk, so it is on the graph only when a branch
     * that is shown also reaches it - an old tag on {@code main} under Solo, a hidden
     * branch merged since. The source is asked that first, and history paged in only when
     * the answer is yes: paging for a tip only the hidden ref reaches read history up to
     * {@link #REVEAL_LIMIT} and never arrived.
     */
    public void revealExcludedTip(String hash, Predicate<GitGraphRow> isIt, String what, Runnable then) {
        Paging paging = supersedeReveal();
        if (!canPageIn(isIt)) {
            finishReveal(paging, then);
            return;
        }
        paging.busy("Looking for " + what);
        paging.reaches(hash,
                reached -> {
                    if (reached) {
                        pageIn(paging, isIt, what, then);
                    } else {
                        finishReveal(paging, then);
                    }
                },
                error -> failReveal(paging, error),
                () -> endReveal(paging));
    }

    /**
     * Abandons the reveal in flight, if one is: its busy span ends, its page is not drawn
     * and what it would have selected is not.
     *
     * <p>For a selection made since that needed no paging, which is newer too. It used to
     * select its row and stop there, and the reveal still paging for the click before it
     * landed afterwards and took the selection back.
     */
    public void cancelReveal() {
        if (revealing == null) return;
        revealing.cancel();
        revealing = null;
    }

    private Paging supersedeReveal() {
        cancelReveal();
        revealing = new Paging();
        return revealing;
    }

    private void pageIn(Paging paging, Predicate<GitGraphRow> isIt, String what, Runnable then) {
        if (!canPageIn(isIt)) {
            finishReveal(paging, then);
            return;
        }
        paging.busy("Loading history to reach " + what);
        paging.load(Walk.doubling(limit, isIt),
                walked -> {
                    try {
                        // Shorter than what a "load more" or a refresh has drawn since: the
                        // row, if it was on this page, is on the page shown too. Drawn, not
                        // asked for: a "load more" still loading has drawn nothing yet, and
                        // its page landing later would select nothing.
                        if (walked.limit() >= drawn) {
                            install(walked.loaded(), Change.REVEALED, walked.limit());
                        }
                    } finally {
                        // However the drawing went: a list that threw used to skip this,
                        // and the status bar stayed busy for good.
                        endReveal(paging);
                    }
                    then.run();
                },
                error -> failReveal(paging, error),
                () -> endReveal(paging));
    }

    /**
     * Whether older history could bring a row {@code isIt} accepts onto the page.
     *
     * <p>Not when the row is loaded and only narrowed away by the graph's filter - it is on
     * {@link #rows}, the unfiltered page: paging would re-walk history up to
     * {@link #REVEAL_LIMIT} to find nothing, take seconds doing it, and leave the graph a
     * hundred thousand rows long for the rest of the session.
     */
    public boolean canPageIn(Predicate<GitGraphRow> isIt) {
        return hasMore && limit < REVEAL_LIMIT && rows.stream().noneMatch(isIt);
    }

    private void finishReveal(Paging paging, Runnable then) {
        endReveal(paging);
        then.run();
    }

    private void failReveal(Paging paging, String error) {
        endReveal(paging);
        listener.pagingFailed(error);
    }

    /**
     * Ends a reveal and its busy span, selecting nothing: on its own, for one cancelled
     * before its request started - by the status bar's Cancel, which reaches the task it
     * follows.
     */
    private void endReveal(Paging paging) {
        if (revealing == paging) revealing = null;
        paging.end();
    }

    /**
     * Drops back to the first page of history, e.g. after the engine changed: paging in
     * flight is cancelled, a refresh asked before this is ignored when it lands, and the
     * next refresh replaces the page whatever it draws - the page shown was read by the
     * engine that is gone, and is not trusted to agree with the new one.
     */
    public void reset() {
        epoch++;
        if (older != null) {
            older.cancel();
            older = null;
        }
        cancelReveal();
        // The reset's own refresh follows, and is of the new engine.
        refreshOwed = false;
        limit = firstPageSize.getAsInt();
        drawn = 0;
        signature = null;
    }

    /**
     * Puts {@code rows} on the page directly, as a test's graph: nothing else about the
     * page - its depth, whether more follows, its signature - changes, and no listener is
     * told; the caller fills the list.
     */
    public void showRows(List<GitGraphRow> rows) {
        this.rows = rows == null ? List.of() : List.copyOf(rows);
        this.ghosts = GhostBranches.of(this.rows);
        this.shown = null;
        highlight.attach(this.rows);
    }

    /** Makes the graph walk only {@code limit} commits, for tests of paging history in. */
    public void setLimitForTest(int limit) {
        this.limit = limit;
    }

    /**
     * The only writer of the page, because it is not the only thing that has to change
     * when the page does.
     *
     * <h2>Why one writer</h2>
     * The hover fade walks the loaded page, so {@link GraphHighlight} has to be pointed at
     * whatever the graph is currently showing. Three separate places replaced the rows -
     * the initial show, the refresh, and paging in older commits - and the third forgot to
     * re-point it. The symptom was silent and confusing: hovering a branch faded nothing at
     * all, because the walk was still looking at a page that no longer existed. One writer
     * means the next place that loads rows cannot forget.
     *
     * @param depth how far back the page was walked; 0 for a page not to be trusted
     */
    private void install(Loaded loaded, Change change, int depth) {
        boolean first = !firstPageShown;
        firstPageShown = true;
        drawn = depth;
        limit = Math.max(limit, depth);
        rows = List.copyOf(loaded.page().rows());
        hasMore = loaded.page().hasMore();
        refs = loaded.page().refs();
        branch = loaded.branch();
        ghosts = loaded.ghosts();
        signature = loaded.signature();
        shown = null;
        highlight.attach(rows);
        listener.pageChanged(change, first);
    }

    /**
     * One paging operation - a "load more" or a reveal - and the busy span it shows.
     *
     * <p>The span belongs to the operation, not to the request answering it: it ends when
     * the operation ends, however that happens. Ending it in the request's callback is
     * what left the status bar busy for good - a superseded reveal's callback returned
     * early without ending it, and a request cancelled before it started never called back
     * at all.
     */
    private final class Paging {
        private Request request;
        private boolean spanOpen;
        /** Ended or abandoned: whatever answers now is ignored. */
        private boolean over;
        /** Bumped per request, so a source that answers at once cannot be confused with a later one. */
        private int asked;
        /** For a "load more", the depth its page is walked to. */
        private int walksTo;

        void busy(String what) {
            endSpan();
            spanOpen = true;
            listener.busy(what);
        }

        private void endSpan() {
            if (spanOpen) {
                spanOpen = false;
                listener.idle();
            }
        }

        void load(Walk walk, Consumer<Walked> onLoaded, Consumer<String> onFailed, Runnable onDropped) {
            int mine = ++asked;
            hold(mine, source.load(walk, answer(mine, onLoaded), answer(mine, onFailed), answer(mine, onDropped)));
        }

        void reaches(String hash, Consumer<Boolean> onAnswer, Consumer<String> onFailed, Runnable onDropped) {
            int mine = ++asked;
            hold(mine, source.reaches(hash, answer(mine, onAnswer), answer(mine, onFailed), answer(mine, onDropped)));
        }

        /** {@code to}, for as long as this operation is waiting on request {@code mine}. */
        private <T> Consumer<T> answer(int mine, Consumer<T> to) {
            return value -> {
                if (!over && mine == asked) to.accept(value);
            };
        }

        /** {@link #answer(int, Consumer)}, for a request's drop. */
        private Runnable answer(int mine, Runnable to) {
            return () -> {
                if (!over && mine == asked) to.run();
            };
        }

        /**
         * Holds request {@code mine} to cancel - unless it has already answered, as a
         * source that answers at once does before this is reached, and moved on.
         */
        private void hold(int mine, Request request) {
            if (!over && mine == asked) this.request = request;
        }

        void end() {
            over = true;
            request = null;
            endSpan();
        }

        void cancel() {
            Request pending = request;
            end();
            if (pending != null) pending.cancel();
        }
    }
}
