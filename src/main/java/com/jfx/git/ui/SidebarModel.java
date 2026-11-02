package com.jfx.git.ui;

import com.jfx.git.core.GitFlow;
import com.jfx.git.core.Worktrees;
import com.jfx.git.graph.GraphVisibility;
import com.jfx.git.model.RefInfo;
import com.jfx.git.model.RefNames;
import com.jfx.git.model.RefSnapshot;
import com.jfx.git.model.StashEntry;
import com.jfx.git.model.Submodule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The sidebar's decisions, apart from drawing it: what each section lists for the filter
 * typed, how many that is, what the "Filtering n / total" line says, which sections are
 * open, which folders of the branch trees are open, and whether a section has to be made
 * again, drawn again in place, or left alone.
 *
 * <h2>Why a model of its own</h2>
 * The sidebar pane both drew every section and decided what was in it, and the decisions
 * were made inside the code that moved panes and tree items about - so checking "does
 * clearing the filter put the sections back as the user had them?" took a whole window.
 * Here the inputs come in through a few calls, the decisions come out as values, and every
 * rule can be checked without one.
 *
 * <h2>What it is told</h2>
 * <ul>
 *   <li>{@link #load} - each refresh's ref snapshot with what the sidebar reads for itself
 *       (tracking counts, gone branches, submodules, worktrees, the Git Flow settings) and
 *       Hide &amp; Solo as stored then.</li>
 *   <li>{@link #answer} - the filter's query, as of the last pause in typing.</li>
 *   <li>{@link #opened} and {@link #folderToggled} - a section or a folder opened or closed,
 *       by the user or by anything else.</li>
 * </ul>
 *
 * <h2>Rows keep their refs</h2>
 * A branch, remote-tracking branch or tag row is one of the snapshot's refs with what its
 * row shows beside it - full name, tip, tracking, gone, upstream, hidden, soloed - so Hide
 * &amp; Solo acts on the ref the row came from, and a cell drawing a row reads it here
 * ({@link #local}, {@link #remote}, {@link #tag}) rather than from a dozen fields.
 *
 * <h2>Rows and facts</h2>
 * Every refresh and every keystroke draws the sidebar again, and on five thousand remote
 * branches making a tree anew is most of a frame. So each {@link Answer} says, per section,
 * whether its <i>rows</i> changed - which names, in which tree - so it has to be made again,
 * or only the <i>facts</i> its cells draw from - the query marked in a name, a count, a
 * hidden ref - so the rows on screen are drawn again in place, or nothing. An answer
 * counts as drawn: the next one is compared with it.
 *
 * <p>Nothing here touches the toolkit or git.
 */
final class SidebarModel {

    /** The sidebar's sections, top to bottom. */
    enum Section { LOCAL, REMOTE, TAGS, STASHES, GIT_FLOW, SUBMODULES, WORKTREES }

    /** What drawing a section takes after an answer; see "Rows and facts" above. */
    enum Change {
        /** Nothing it shows has changed. */
        NONE,
        /** The same rows, saying something new: draw the rows on screen again, in place. */
        REDRAW,
        /** Different rows: make the section's rows anew. */
        REBUILD
    }

    /**
     * One refresh's worth: the ref snapshot, what only the sidebar reads, and Hide &amp; Solo
     * as stored when it was drawn.
     *
     * @param tracking      ahead and behind per local branch, by short name
     * @param gone          local branches whose upstream was deleted on the remote
     * @param flowConfig    the Git Flow settings, or null when the repository has none
     * @param hiddenStashes stashes hidden from the graph, by commit hash
     */
    record Load(RefSnapshot refs, Map<String, int[]> tracking, Set<String> gone,
                List<Submodule> submodules, List<Worktrees.Worktree> worktrees, GitFlow.Config flowConfig,
                GraphVisibility visibility, Set<String> hiddenStashes) {
        Load {
            refs = refs == null ? RefSnapshot.EMPTY : refs;
            tracking = tracking == null ? Map.of() : tracking;
            gone = gone == null ? Set.of() : gone;
            submodules = submodules == null ? List.of() : List.copyOf(submodules);
            worktrees = worktrees == null ? List.of() : List.copyOf(worktrees);
            visibility = visibility == null ? GraphVisibility.ALL : visibility;
            hiddenStashes = hiddenStashes == null ? Set.of() : hiddenStashes;
        }
    }

    /**
     * A local branch as its row shows it.
     *
     * @param name      the short name, {@code feature/login}
     * @param fullName  the ref, {@code refs/heads/feature/login}: what Hide &amp; Solo keys by
     * @param tip       the commit it points at, or null
     * @param upstream  what it tracks, short ({@code origin/feature/login}), or null
     * @param flowKind  the Git Flow kind its name makes it, or null
     * @param hidden    whether the user hid it from the graph
     * @param solo      whether the graph is soloed to it
     */
    record LocalRow(String name, String fullName, String tip, boolean current, int ahead, int behind,
                    boolean gone, String upstream, GitFlow.Kind flowKind, boolean hidden, boolean solo) {

        /** Whether Hide &amp; Solo applies: never to the branch HEAD is on. */
        boolean hideable() {
            return !current;
        }
    }

    /**
     * A remote-tracking branch as its row shows it.
     *
     * @param upstreamOfCurrent whether the current branch tracks it already
     */
    record RemoteRow(String name, String fullName, String tip, boolean upstreamOfCurrent,
                     boolean hidden, boolean solo) {
    }

    /** A tag as its row shows it. */
    record TagRow(String name, String fullName, String tip, boolean hidden, boolean solo) {
    }

    /**
     * What the sidebar shows for one query.
     *
     * <p>The rows per section, filtered; each section's count and what drawing it takes; the
     * status line; and which sections are open, the open-sections policy applied.
     */
    static final class Answer {
        private final String query;
        private final EnumMap<Section, List<?>> rows;
        private final EnumMap<Section, Integer> totals;
        private final EnumMap<Section, Change> changes;
        private final EnumMap<Section, Boolean> open;
        private final int matched;
        private final int total;
        private final List<String> remoteRoots;

        private Answer(String query, EnumMap<Section, List<?>> rows, EnumMap<Section, Integer> totals,
                       EnumMap<Section, Change> changes, EnumMap<Section, Boolean> open,
                       int matched, int total, List<String> remoteRoots) {
            this.query = query;
            this.rows = rows;
            this.totals = totals;
            this.changes = changes;
            this.open = open;
            this.matched = matched;
            this.total = total;
            this.remoteRoots = remoteRoots;
        }

        /** The query, trimmed; empty when not filtering. */
        String query() {
            return query;
        }

        boolean filtering() {
            return !query.isEmpty();
        }

        /** The names a branch tree or the tags or Git Flow list shows, filtered. */
        @SuppressWarnings("unchecked")
        List<String> names(Section section) {
            if (section == Section.STASHES || section == Section.SUBMODULES || section == Section.WORKTREES) {
                throw new IllegalArgumentException(section + " does not list names");
            }
            return (List<String>) rows.get(section);
        }

        @SuppressWarnings("unchecked")
        List<StashEntry> stashes() {
            return (List<StashEntry>) rows.get(Section.STASHES);
        }

        @SuppressWarnings("unchecked")
        List<Submodule> submodules() {
            return (List<Submodule>) rows.get(Section.SUBMODULES);
        }

        @SuppressWarnings("unchecked")
        List<Worktrees.Worktree> worktrees() {
            return (List<Worktrees.Worktree>) rows.get(Section.WORKTREES);
        }

        /** What a section's badge says: everything it has, or - while filtering - what matched. */
        int count(Section section) {
            return filtering() ? rows.get(section).size() : totals.get(section);
        }

        /** What drawing {@code section} takes; see {@link Change}. */
        Change change(Section section) {
            return changes.get(section);
        }

        /** Whether {@code section} is open, the open-sections policy applied. */
        boolean open(Section section) {
            return open.get(section);
        }

        /**
         * "Filtering 53 / 655": what matched and what there is, in every section the filter
         * narrows - not Git Flow, whose rows are local branches counted already, and not the
         * worktrees while there is only the main one and no section for it. Shown only while
         * {@link #filtering()}.
         */
        String status() {
            return "Filtering " + matched + " / " + total;
        }

        /** The remotes the remote tree keeps whole, as one folder each, slashes and all. */
        List<String> remoteRoots() {
            return remoteRoots;
        }

        /**
         * The remote folders the tree shows even with nothing in them: every remote, so one
         * with nothing fetched can be found and fetched - but none while filtering, when an
         * empty folder is a row that matches nothing.
         */
        List<String> remoteFolders() {
            return filtering() ? List.of() : remoteRoots;
        }
    }

    // ------------------------------------------------------------------
    // The load
    // ------------------------------------------------------------------

    private Load load;
    private RefSnapshot refs = RefSnapshot.EMPTY;
    private Map<String, String> upstreams = Map.of();
    private Map<String, LocalRow> locals = Map.of();
    private Map<String, RemoteRow> remotes = Map.of();
    private Map<String, TagRow> tags = Map.of();
    private List<String> localNames = List.of();
    private List<String> remoteBranchNames = List.of();
    private List<String> flow = List.of();

    /** Takes a refresh's refs and reads; the rows are made here, once per load. */
    void load(Load next) {
        load = next == null ? new Load(null, null, null, null, null, null, null, null) : next;
        refs = load.refs();
        upstreams = refs.upstreamShortNames();
        String current = refs.currentBranch();
        GraphVisibility visibility = load.visibility();

        Map<String, LocalRow> localRows = new LinkedHashMap<>();
        for (RefInfo ref : refs.localBranches()) {
            String name = ref.shortName();
            int[] counts = load.tracking().get(name);
            boolean counted = counts != null && counts.length >= 2;
            localRows.putIfAbsent(name, new LocalRow(name, ref.fullName(), ref.targetHash(), name.equals(current),
                    counted ? counts[0] : 0, counted ? counts[1] : 0, load.gone().contains(name),
                    upstreams.get(name), GitFlow.kindOf(load.flowConfig(), name),
                    visibility.hiddenRefs().contains(ref.fullName()), ref.fullName().equals(visibility.soloRef())));
        }
        locals = localRows;

        String currentUpstream = upstreams.get(current);
        Map<String, RemoteRow> remoteRows = new LinkedHashMap<>();
        for (RefInfo ref : refs.remoteBranches()) {
            String name = ref.shortName();
            remoteRows.putIfAbsent(name, new RemoteRow(name, ref.fullName(), ref.targetHash(),
                    name.equals(currentUpstream),
                    visibility.hiddenRefs().contains(ref.fullName()), ref.fullName().equals(visibility.soloRef())));
        }
        remotes = remoteRows;

        Map<String, TagRow> tagRows = new LinkedHashMap<>();
        for (RefInfo ref : refs.tags()) {
            String name = ref.shortName();
            tagRows.putIfAbsent(name, new TagRow(name, ref.fullName(), ref.targetHash(),
                    visibility.hiddenRefs().contains(ref.fullName()), ref.fullName().equals(visibility.soloRef())));
        }
        tags = tagRows;
        localNames = List.copyOf(locals.keySet());
        remoteBranchNames = List.copyOf(remotes.keySet());

        // The features, releases and hotfixes under way: local branches by their prefix.
        flow = load.flowConfig() == null ? List.of() : localNames.stream()
                .filter(b -> GitFlow.kindOf(load.flowConfig(), b) != null).toList();
    }

    /** Whether a load has arrived: until then there is nothing to count, and no counts are shown. */
    boolean loaded() {
        return load != null;
    }

    /** The snapshot the sections were last drawn from. */
    RefSnapshot refs() {
        return refs;
    }

    /** The current branch, blank while HEAD is detached. */
    String currentBranch() {
        return refs.currentBranch();
    }

    /** Every local branch's short name, as the snapshot lists them. */
    List<String> localNames() {
        return localNames;
    }

    /** Every remote-tracking branch's short name, {@code origin/main}. */
    List<String> remoteBranchNames() {
        return remoteBranchNames;
    }

    /** The configured remotes, including any with nothing fetched. */
    List<String> remoteNames() {
        return refs.remoteNames();
    }

    /** Local branch to what it tracks, both short; only branches that track something. */
    Map<String, String> upstreams() {
        return upstreams;
    }

    List<Submodule> submodules() {
        return load == null ? List.of() : load.submodules();
    }

    List<Worktrees.Worktree> worktrees() {
        return load == null ? List.of() : load.worktrees();
    }

    /** The local branch {@code name}'s row; a bare one - nothing known - for a name not loaded. */
    LocalRow local(String name) {
        LocalRow row = locals.get(name);
        return row != null ? row : new LocalRow(name, RefNames.localFull(name), null,
                false, 0, 0, false, null, null, false, false);
    }

    /** The remote-tracking branch {@code name}'s row; a bare one for a name not loaded. */
    RemoteRow remote(String name) {
        RemoteRow row = remotes.get(name);
        return row != null ? row : new RemoteRow(name, RefNames.remoteFull(name), null,
                false, false, false);
    }

    /** The tag {@code name}'s row; a bare one for a name not loaded. */
    TagRow tag(String name) {
        TagRow row = tags.get(name);
        return row != null ? row : new TagRow(name, RefNames.tagFull(name), null, false, false);
    }

    /** Whether the stash with this commit is hidden from the graph. */
    boolean stashHidden(String hash) {
        return load != null && load.hiddenStashes().contains(hash);
    }

    /**
     * Whether a section is on the sidebar at all. Most repositories have no submodules,
     * have only the main worktree and are not set up for Git Flow; an always-present empty
     * section would be noise, so those three are there only when there is something in them.
     */
    boolean present(Section section) {
        return switch (section) {
            case SUBMODULES -> !submodules().isEmpty();
            // The main tree is always there, so the section earns its place with a second.
            case WORKTREES -> worktrees().size() > 1;
            case GIT_FLOW -> load != null && load.flowConfig() != null;
            default -> true;
        };
    }

    /** Whether there is no remote at all, so the remote section offers to add one. */
    boolean offersAddRemote() {
        return refs.remoteNames().isEmpty();
    }

    /** Every ref a section lists, by full name: what its "Show all" shows again. */
    List<String> fullNames(Section section) {
        return switch (section) {
            case LOCAL -> locals.values().stream().map(LocalRow::fullName).toList();
            case REMOTE -> remotes.values().stream().map(RemoteRow::fullName).toList();
            case TAGS -> tags.values().stream().map(TagRow::fullName).toList();
            default -> throw new IllegalArgumentException(section + " lists no refs");
        };
    }

    /**
     * The refs a section's "Hide all" hides, by full name: all of them but the branch HEAD is
     * on, which Hide &amp; Solo leaves alone.
     */
    List<String> hideable(Section section) {
        if (section == Section.LOCAL) {
            return locals.values().stream().filter(LocalRow::hideable).map(LocalRow::fullName).toList();
        }
        return fullNames(section);
    }

    /** Every stash's commit: what the stash section's "Hide all" and "Show all" act on. */
    List<String> stashHashes() {
        return refs.stashes().stream().map(StashEntry::hash).toList();
    }

    // ------------------------------------------------------------------
    // The filter
    // ------------------------------------------------------------------

    /** The query of the last answer; empty before the first. */
    private String answered = "";

    /**
     * Everything the sidebar shows for {@code query}: each section filtered to what
     * contains it, ignoring case.
     *
     * <p>The answer counts as drawn, and the next one's changes are reckoned against it.
     */
    Answer answer(String query) {
        String q = query == null ? "" : query.strip();
        answered = q;
        boolean filtering = !q.isEmpty();

        List<LocalRow> localRows = filtered(locals.values(), LocalRow::name, q);
        List<RemoteRow> remoteRows = filtered(remotes.values(), RemoteRow::name, q);
        List<TagRow> tagRows = filtered(tags.values(), TagRow::name, q);
        List<StashEntry> stashes = filtering
                ? refs.stashes().stream().filter(s -> matches(s.selector(), q) || matches(s.message(), q)).toList()
                : refs.stashes();
        List<Submodule> submodules = filtered(submodules(), Submodule::path, q);
        List<Worktrees.Worktree> worktrees = filtered(worktrees(), Worktrees.Worktree::name, q);
        List<String> flowNames = filtered(flow, name -> name, q);

        EnumMap<Section, List<?>> rows = new EnumMap<>(Section.class);
        rows.put(Section.LOCAL, names(localRows, LocalRow::name));
        rows.put(Section.REMOTE, names(remoteRows, RemoteRow::name));
        rows.put(Section.TAGS, names(tagRows, TagRow::name));
        rows.put(Section.STASHES, stashes);
        rows.put(Section.GIT_FLOW, flowNames);
        rows.put(Section.SUBMODULES, submodules);
        rows.put(Section.WORKTREES, worktrees);

        EnumMap<Section, Integer> totals = new EnumMap<>(Section.class);
        totals.put(Section.LOCAL, locals.size());
        totals.put(Section.REMOTE, remotes.size());
        totals.put(Section.TAGS, tags.size());
        totals.put(Section.STASHES, refs.stashes().size());
        totals.put(Section.GIT_FLOW, flow.size());
        totals.put(Section.SUBMODULES, submodules().size());
        totals.put(Section.WORKTREES, worktrees().size());

        // What each section's cells read besides their row's name, so a change to any of it
        // redraws the rows on screen; see "Rows and facts".
        String current = refs.currentBranch();
        List<String> remoteNames = refs.remoteNames();
        EnumMap<Section, Change> changes = new EnumMap<>(Section.class);
        changes.put(Section.LOCAL, change(Section.LOCAL,
                Arrays.asList(rows.get(Section.LOCAL), filtering),
                Arrays.asList(q, localRows, current, remotes.isEmpty())));
        // The remotes are in the rows' key filtering or not: the tree groups its branches by
        // them (remoteRoots), so a remote added while filtering regroups the rows it owns.
        changes.put(Section.REMOTE, change(Section.REMOTE,
                Arrays.asList(rows.get(Section.REMOTE), filtering, remoteNames),
                Arrays.asList(q, remoteRows, current, remoteNames)));
        changes.put(Section.TAGS, change(Section.TAGS, rows.get(Section.TAGS),
                Arrays.asList(q, tagRows, remoteNames)));
        changes.put(Section.STASHES, change(Section.STASHES, stashes,
                Arrays.asList(q, stashes.stream().map(s -> stashHidden(s.hash())).toList())));
        changes.put(Section.GIT_FLOW, change(Section.GIT_FLOW, flowNames,
                Arrays.asList(current, load == null ? null : load.flowConfig())));
        // Their cells are made for the row they show, and read nothing else.
        changes.put(Section.SUBMODULES, change(Section.SUBMODULES, submodules, null));
        changes.put(Section.WORKTREES, change(Section.WORKTREES, worktrees, null));

        int matched = 0;
        int total = 0;
        for (Section section : List.of(Section.LOCAL, Section.REMOTE, Section.TAGS, Section.STASHES,
                Section.SUBMODULES, Section.WORKTREES)) {
            if (section == Section.WORKTREES && !present(Section.WORKTREES)) continue;
            matched += rows.get(section).size();
            total += totals.get(section);
        }

        EnumMap<Section, Integer> found = new EnumMap<>(Section.class);
        rows.forEach((section, list) -> found.put(section, list.size()));
        openSectionsWithMatches(q, found);

        return new Answer(q, rows, totals, changes, new EnumMap<>(open), matched, total, remoteNames);
    }

    /** Whether the last answer was for a query: what the folders and sections are doing depends on it. */
    boolean filtering() {
        return !answered.isEmpty();
    }

    /** Whether {@code text} contains {@code q}, ignoring case; everything matches no query. */
    static boolean matches(String text, String q) {
        return q == null || q.isEmpty() || matchAt(text, q) >= 0;
    }

    /**
     * Where {@code q} first occurs in {@code text}, ignoring case, as an index into
     * {@code text} itself; -1 when it does not. Not {@code toLowerCase().indexOf()}:
     * lower-casing can change a string's length (a dotted capital I becomes two
     * characters), and an index into the lowered copy then cut the original in the wrong
     * place, or past its end, in the middle of drawing a row.
     */
    static int matchAt(String text, String q) {
        if (text == null || q == null || q.isEmpty()) return -1;
        for (int i = 0; i + q.length() <= text.length(); i++) {
            if (text.regionMatches(true, i, q, 0, q.length())) return i;
        }
        return -1;
    }

    private static <T> List<T> filtered(Collection<T> all, Function<T, String> name, String q) {
        if (q.isEmpty()) return all instanceof List<T> list ? list : List.copyOf(all);
        List<T> kept = new ArrayList<>();
        for (T item : all) {
            if (matches(name.apply(item), q)) kept.add(item);
        }
        return kept;
    }

    private static <T> List<String> names(List<T> rows, Function<T, String> name) {
        List<String> names = new ArrayList<>(rows.size());
        for (T row : rows) names.add(name.apply(row));
        return names;
    }

    // ------------------------------------------------------------------
    // Change detection
    // ------------------------------------------------------------------

    /** What each section was last drawn from: its rows, and the facts its cells read. */
    private final EnumMap<Section, Object> drawnRows = new EnumMap<>(Section.class);
    private final EnumMap<Section, Object> drawnFacts = new EnumMap<>(Section.class);

    /**
     * New rows rebuild a section, new facts only redraw the rows on screen; a section with
     * no facts ({@code null}) is drawn only when its rows change.
     */
    private Change change(Section section, Object rows, Object facts) {
        if (!drawnRows.containsKey(section) || !rows.equals(drawnRows.get(section))) {
            drawnRows.put(section, rows);
            drawnFacts.put(section, facts);
            return Change.REBUILD;
        }
        if (facts != null && !facts.equals(drawnFacts.get(section))) {
            drawnFacts.put(section, facts);
            return Change.REDRAW;
        }
        return Change.NONE;
    }

    // ------------------------------------------------------------------
    // Open sections
    // ------------------------------------------------------------------

    /**
     * Whether each section is open: LOCAL, and Git Flow - what it lists is work in progress -
     * to begin with, and after that as the user and the filter leave them.
     */
    private final EnumMap<Section, Boolean> open = new EnumMap<>(Map.of(
            Section.LOCAL, true, Section.REMOTE, false, Section.TAGS, false, Section.STASHES, false,
            Section.GIT_FLOW, true, Section.SUBMODULES, false, Section.WORKTREES, false));
    /** Each section's open state from before the filter opened and closed them; null when not filtering. */
    private EnumMap<Section, Boolean> openBeforeFilter;
    /** The query the sections were last opened and closed for; null while not filtering. */
    private String sectionsOpenedFor;
    /** How many matches each section had at the last answer for that query. */
    private Map<Section, Integer> matchesOpenedFor = Map.of();

    /** Whether {@code section} is open now. */
    boolean open(Section section) {
        return open.get(section);
    }

    /** {@code section} was opened or closed - by the user, or by anything else. */
    void opened(Section section, boolean isOpen) {
        open.put(section, isOpen);
    }

    /**
     * While filtering, the sections with matches are open and the rest closed; when the
     * filter is cleared each goes back to how the user had it.
     *
     * <p>Once per query, not once per answer: every refresh - auto-fetch, the repository
     * watcher, any action - answers again, and forcing the sections each time snapped one
     * the user had just closed, to look at another's matches, back open. An answer still
     * opens a section whose first match has just arrived - a branch created, a fetch - and
     * closes one whose last has gone: those the user never had the chance to decide about.
     */
    private void openSectionsWithMatches(String q, Map<Section, Integer> found) {
        if (!q.isEmpty()) {
            if (openBeforeFilter == null) {
                openBeforeFilter = new EnumMap<>(open);
            }
            boolean newQuery = !q.equals(sectionsOpenedFor);
            found.forEach((section, count) -> {
                Integer before = newQuery ? null : matchesOpenedFor.get(section);
                if (before == null || (before > 0) != (count > 0)) open.put(section, count > 0);
            });
            sectionsOpenedFor = q;
            matchesOpenedFor = new EnumMap<>(found);
        } else {
            sectionsOpenedFor = null;
            matchesOpenedFor = Map.of();
            if (openBeforeFilter != null) {
                open.putAll(openBeforeFilter);
                openBeforeFilter = null;
            }
        }
    }

    // ------------------------------------------------------------------
    // Folder memory
    // ------------------------------------------------------------------

    /**
     * The folders the user has opened or closed, per tree, by path. A rebuild - every
     * refresh, every keystroke in the filter - used to reset them all, so an opened
     * {@code origin} folder closed again under the pointer.
     */
    private final Map<String, Boolean> localFolders = new HashMap<>();
    private final Map<String, Boolean> remoteFolders = new HashMap<>();

    /**
     * Whether a folder of the LOCAL or REMOTE tree is open when the tree is made: as the
     * user left it; local folders open and remote ones - usually numerous - closed until
     * then; and every folder open while filtering, since a match inside a closed folder is a
     * match nobody can see.
     *
     * @param path the folder's path from the root, {@code origin/agent}; a remote whose own
     *             name has a slash in it is one segment
     */
    boolean folderOpen(Section tree, String path) {
        if (filtering()) return true;
        return tree == Section.REMOTE
                ? remoteFolders.getOrDefault(path, false)
                : localFolders.getOrDefault(path, true);
    }

    /** A folder was opened or closed. Remembered only when not filtering, which opens them all. */
    void folderToggled(Section tree, String path, boolean isOpen) {
        if (filtering()) return;
        (tree == Section.REMOTE ? remoteFolders : localFolders).put(path, isOpen);
    }
}
