package com.jfx.git.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Settings that outlive a session.
 *
 * <p>Stored by {@link Settings}, in one plain-text file under the user's application
 * data directory. Nothing secret goes in here - the file is readable by anything running
 * as the user, which is why credentials are held in memory only.
 */
public final class AppPreferences {

    /** What the viewer's "Edit this file" button should do. */
    public enum EditorMode {
        /** Type in the diff viewer itself and save from there. */
        BUILT_IN("Built-in editor"),
        /** Hand the file to whatever the operating system associates with it. */
        SYSTEM_DEFAULT("System default application"),
        /** Run a command of the user's choosing. */
        CUSTOM("Custom command…");

        private final String label;

        EditorMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final String KEY_EDITOR_MODE = "editor.mode";
    private static final String KEY_EDITOR_COMMAND = "editor.command";
    private static final String KEY_DIFF_TOOL_MODE = "tools.diff.mode";
    private static final String KEY_DIFF_TOOL_COMMAND = "tools.diff.command";
    private static final String KEY_MERGE_TOOL_MODE = "tools.merge.mode";
    private static final String KEY_MERGE_TOOL_COMMAND = "tools.merge.command";
    private static final String KEY_TERMINAL_COMMAND = "tools.terminal.command";
    private static final String KEY_UPDATE_CHECK = "update.check";
    private static final String KEY_UPDATE_FEED = "update.feed";
    private static final String KEY_UPDATE_LAST = "update.lastCheck";
    private static final String KEY_DIFF_SPLIT = "diff.split";
    private static final String KEY_DIFF_WHOLE_FILE = "diff.wholeFile";
    private static final String KEY_DIFF_SCROLL_MAP_WIDTH = "diff.scrollMapWidth";
    private static final String KEY_EDITOR_SCROLL_BEYOND_LAST_LINE = "editor.scrollBeyondLastLine";
    private static final String KEY_EDITOR_MINIMAP = "editor.minimap";
    private static final String KEY_DIFF_COLOURBLIND = "diff.colourblind";
    private static final String KEY_CONFIRM_DISCARD = "confirm.discard";
    private static final String KEY_EDITOR_DRAFTS = "editor.drafts";
    private static final String KEY_GRAPH_DENSITY = "graph.density";
    private static final String KEY_GRAPH_TINT = "graph.tint";
    private static final String KEY_GRAPH_NODE = "graph.node";
    private static final String KEY_GRAPH_ORDER = "graph.order";
    /** Prefixes one key per optional column, e.g. {@code graph.column.author}. */
    private static final String KEY_GRAPH_COLUMN = "graph.column.";
    private static final String KEY_GRAPH_COLUMN_ORDER = "graph.columnOrder";
    /** Lanes the user dragged the graph to; 0 means it follows the window. */
    private static final String KEY_GRAPH_LANES = "graph.lanes";
    private static final String KEY_GRAPH_RAIL = "graph.rail";
    private static final String KEY_LAYOUT_SIDEBAR = "layout.sidebar";
    private static final String KEY_LAYOUT_DETAIL = "layout.detail";
    private static final String KEY_LAYOUT_COMMIT_MESSAGE = "layout.commitMessage";
    /** Despite the name, holds any ref's {@code fullName} - local/remote branch or tag, not branches only. */
    private static final String KEY_GRAPH_HIDDEN_BRANCHES = "graph.hiddenBranches";
    private static final String KEY_GRAPH_SOLO_BRANCH = "graph.soloBranch";
    /** Stashes have no ref of their own to key by, so this holds stash commit hashes instead. */
    private static final String KEY_GRAPH_HIDDEN_STASHES = "graph.hiddenStashes";
    private static final String KEY_GRAPH_INITIAL_COMMITS = "graph.initialCommits";
    private static final String KEY_GRAPH_SHOW_ALL = "graph.showAll";
    private static final String KEY_DEFAULT_BRANCH_NAME = "init.defaultBranch";
    private static final String KEY_AUTO_FETCH_MINUTES = "autoFetch.minutes";
    private static final String KEY_PULL_MODE = "pull.mode";
    private static final String KEY_SUBMODULE_AUTO_UPDATE = "submodule.autoUpdate";
    private static final String KEY_AUTOSTASH_CHECKOUT_PULL = "autostash.onCheckoutPull";
    /** Absent until the user chooses; absent means "follow the system", which false does not. */
    private static final String KEY_REDUCE_MOTION = "ui.reduceMotion";

    /** The token replaced with the file path in a custom command. */
    public static final String FILE_TOKEN = "$FILE";

    private AppPreferences() {
    }

    // ------------------------------------------------------------------
    // Editor
    // ------------------------------------------------------------------

    public static EditorMode editorMode() {
        String stored = Settings.get(KEY_EDITOR_MODE, EditorMode.BUILT_IN.name());
        try {
            return EditorMode.valueOf(stored);
        } catch (IllegalArgumentException e) {
            // A value written by a newer version is not worth failing over.
            return EditorMode.BUILT_IN;
        }
    }

    public static void setEditorMode(EditorMode mode) {
        Settings.put(KEY_EDITOR_MODE, (mode == null ? EditorMode.BUILT_IN : mode).name());
    }

    /** The command line for {@link EditorMode#CUSTOM}, blank when unset. */
    public static String editorCommand() {
        return Settings.get(KEY_EDITOR_COMMAND, "");
    }

    public static void setEditorCommand(String command) {
        Settings.put(KEY_EDITOR_COMMAND, command == null ? "" : command.trim());
    }

    /**
     * Splits a custom editor command into arguments and substitutes the file path.
     *
     * <p>Quoted segments are kept whole, so {@code "C:\Program Files\app.exe" $FILE}
     * works. A command with no {@link #FILE_TOKEN} gets the path appended, which is
     * what most editors expect anyway.
     */
    public static List<String> buildEditorCommand(String template, File target) {
        List<String> parts = splitCommand(template);
        if (parts.isEmpty()) {
            return List.of();
        }
        String path = target == null ? "" : target.getAbsolutePath();

        boolean substituted = false;
        List<String> command = new ArrayList<>(parts.size() + 1);
        for (String part : parts) {
            if (part.contains(FILE_TOKEN)) {
                substituted = true;
                command.add(part.replace(FILE_TOKEN, path));
            } else {
                command.add(part);
            }
        }
        if (!substituted) {
            command.add(path);
        }
        return command;
    }

    // ------------------------------------------------------------------
    // External diff and merge tools; see ExternalTools
    // ------------------------------------------------------------------

    public static ExternalTools.Mode diffToolMode() {
        return toolMode(KEY_DIFF_TOOL_MODE);
    }

    public static void setDiffToolMode(ExternalTools.Mode mode) {
        Settings.put(KEY_DIFF_TOOL_MODE, (mode == null ? ExternalTools.Mode.NONE : mode).name());
    }

    /** The command line for a custom diff tool, blank when unset. */
    public static String diffToolCommand() {
        return Settings.get(KEY_DIFF_TOOL_COMMAND, "");
    }

    public static void setDiffToolCommand(String command) {
        Settings.put(KEY_DIFF_TOOL_COMMAND, command == null ? "" : command.trim());
    }

    public static ExternalTools.Mode mergeToolMode() {
        return toolMode(KEY_MERGE_TOOL_MODE);
    }

    public static void setMergeToolMode(ExternalTools.Mode mode) {
        Settings.put(KEY_MERGE_TOOL_MODE, (mode == null ? ExternalTools.Mode.NONE : mode).name());
    }

    /** The command line for a custom merge tool, blank when unset. */
    public static String mergeToolCommand() {
        return Settings.get(KEY_MERGE_TOOL_COMMAND, "");
    }

    public static void setMergeToolCommand(String command) {
        Settings.put(KEY_MERGE_TOOL_COMMAND, command == null ? "" : command.trim());
    }

    /**
     * The command that opens a terminal, blank to let {@link TerminalLauncher} pick the
     * best one installed.
     */
    public static String terminalCommand() {
        return Settings.get(KEY_TERMINAL_COMMAND, "");
    }

    public static void setTerminalCommand(String command) {
        Settings.put(KEY_TERMINAL_COMMAND, command == null ? "" : command.trim());
    }

    // ------------------------------------------------------------------
    // Update check; see UpdateChecker
    // ------------------------------------------------------------------

    /**
     * Whether the interface should keep still: no fades or slides when things appear,
     * leave or move; see {@code ui.Motion}.
     *
     * <p>The user's own answer once they have given one in Preferences. Until then it
     * follows the operating system's setting, asked afresh on each call so that a change
     * made while the application runs reaches the very next animation. That question can
     * only be put on the FX thread; from anywhere else, or with no toolkit running, the
     * answer is "no" - motion is the default, and nothing here is worth failing over.
     */
    public static boolean reduceMotion() {
        // Absent is not the same as false: an explicit "off" must beat a system that
        // asks for less motion, so the stored text is read rather than a defaulted boolean.
        String chosen = Settings.get(KEY_REDUCE_MOTION, null);
        if (chosen != null) {
            return Boolean.parseBoolean(chosen);
        }
        return reducedMotionLookup.getAsBoolean();
    }

    /** Records the user's choice, which from now on wins over the system's setting. */
    public static void setReduceMotion(boolean reduce) {
        Settings.putBoolean(KEY_REDUCE_MOTION, reduce);
    }

    /**
     * What {@link #reduceMotion()} asks when the user has not chosen. A seam for tests: the
     * headless toolkit reports no preferences at all, so neither the system asking for
     * less motion nor the toolkit being unavailable could otherwise be exercised.
     */
    private static volatile BooleanSupplier reducedMotionLookup = AppPreferences::platformReducedMotion;

    /**
     * Replaces the question put to the platform in {@link #reduceMotion()}; {@code null}
     * puts the real one back. For tests, which must undo it.
     */
    public static void usePlatformReducedMotion(BooleanSupplier lookup) {
        reducedMotionLookup = lookup == null ? AppPreferences::platformReducedMotion : lookup;
    }

    private static boolean platformReducedMotion() {
        try {
            // Platform.getPreferences() throws anywhere but the FX thread. Checking first
            // keeps the usual off-thread caller - a test, a git worker - from paying for
            // a stack trace to learn what the thread already says.
            return javafx.application.Platform.isFxApplicationThread()
                    && javafx.application.Platform.getPreferences().isReducedMotion();
        } catch (RuntimeException | LinkageError noToolkit) {
            // The toolkit was never started, or this build has no JavaFX graphics module.
            return false;
        }
    }

    /** Whether to look for a newer version once a day. On by default; it does nothing without a feed. */
    public static boolean updateCheckEnabled() {
        return Settings.getBoolean(KEY_UPDATE_CHECK, true);
    }

    public static void setUpdateCheckEnabled(boolean enabled) {
        Settings.putBoolean(KEY_UPDATE_CHECK, enabled);
    }

    /** The release feed to ask, blank when none is set - in which case nothing is ever asked. */
    public static String updateFeedUrl() {
        return Settings.get(KEY_UPDATE_FEED, "");
    }

    public static void setUpdateFeedUrl(String url) {
        Settings.put(KEY_UPDATE_FEED, url == null ? "" : url.trim());
    }

    /** When the feed was last asked, in epoch milliseconds; 0 when it never has been. */
    public static long lastUpdateCheckMillis() {
        try {
            return Long.parseLong(Settings.get(KEY_UPDATE_LAST, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void setLastUpdateCheckMillis(long millis) {
        Settings.put(KEY_UPDATE_LAST, Long.toString(millis));
    }

    private static ExternalTools.Mode toolMode(String key) {
        try {
            return ExternalTools.Mode.valueOf(Settings.get(key, ExternalTools.Mode.NONE.name()));
        } catch (IllegalArgumentException e) {
            // A value written by a newer version is not worth failing over.
            return ExternalTools.Mode.NONE;
        }
    }

    /** Whitespace-separated, except inside double quotes. */
    static List<String> splitCommand(String command) {
        List<String> parts = new ArrayList<>();
        if (command == null) {
            return parts;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    // ------------------------------------------------------------------
    // Diff
    // ------------------------------------------------------------------

    /** Whether the diff opens side by side rather than unified. */
    public static boolean splitDiffByDefault() {
        return Settings.getBoolean(KEY_DIFF_SPLIT, false);
    }

    public static void setSplitDiffByDefault(boolean split) {
        Settings.putBoolean(KEY_DIFF_SPLIT, split);
    }

    /** Whether the diff shows the whole file continuously rather than just the hunks. */
    public static boolean wholeFileByDefault() {
        return Settings.getBoolean(KEY_DIFF_WHOLE_FILE, false);
    }

    public static void setWholeFileByDefault(boolean on) {
        Settings.putBoolean(KEY_DIFF_WHOLE_FILE, on);
    }

    private static final int DEFAULT_DIFF_SCROLL_MAP_WIDTH = 16;

    /**
     * Width in pixels of the diff surface's scrollbar ({@code DiffScrollMap}: the thumb
     * and the change marks behind it). The original fixed 10px read as a hairline on
     * most displays, so this is a size to widen rather than one "correct" value.
     */
    public static int diffScrollMapWidth() {
        try {
            int stored = Integer.parseInt(Settings.get(KEY_DIFF_SCROLL_MAP_WIDTH, ""));
            return stored > 0 ? stored : DEFAULT_DIFF_SCROLL_MAP_WIDTH;
        } catch (NumberFormatException e) {
            return DEFAULT_DIFF_SCROLL_MAP_WIDTH;
        }
    }

    public static void setDiffScrollMapWidth(int width) {
        if (width <= 0) {
            Settings.remove(KEY_DIFF_SCROLL_MAP_WIDTH);
        } else {
            Settings.put(KEY_DIFF_SCROLL_MAP_WIDTH, Integer.toString(width));
        }
    }

    /**
     * Whether the diff and file views can scroll past their last line, so it can sit at
     * the top of the viewport. On by default, as in every code editor.
     */
    public static boolean scrollBeyondLastLine() {
        return Settings.getBoolean(KEY_EDITOR_SCROLL_BEYOND_LAST_LINE, true);
    }

    public static void setScrollBeyondLastLine(boolean on) {
        Settings.putBoolean(KEY_EDITOR_SCROLL_BEYOND_LAST_LINE, on);
    }

    /** Whether File view shows a minimap of the whole file beside its scrollbar. On by default. */
    public static boolean editorMinimap() {
        return Settings.getBoolean(KEY_EDITOR_MINIMAP, true);
    }

    public static void setEditorMinimap(boolean on) {
        Settings.putBoolean(KEY_EDITOR_MINIMAP, on);
    }

    /** Blue and amber instead of green and red. */
    public static boolean colourblindPalette() {
        return Settings.getBoolean(KEY_DIFF_COLOURBLIND, false);
    }

    public static void setColourblindPalette(boolean on) {
        Settings.putBoolean(KEY_DIFF_COLOURBLIND, on);
    }

    // ------------------------------------------------------------------
    // Commit graph
    // ------------------------------------------------------------------

    /**
     * How tightly the commit graph draws its rows.
     *
     * <p>Returns the name rather than the enum so this class stays free of the graph
     * package; the graph resolves it. Defaults to Compact.
     */
    public static com.jfx.git.graph.GraphDensity graphDensity() {
        String stored = Settings.get(KEY_GRAPH_DENSITY, com.jfx.git.graph.GraphDensity.COMPACT.name());
        try {
            return com.jfx.git.graph.GraphDensity.valueOf(stored);
        } catch (IllegalArgumentException e) {
            // A value written by a newer version is not worth failing over.
            return com.jfx.git.graph.GraphDensity.COMPACT;
        }
    }

    public static void setGraphDensity(com.jfx.git.graph.GraphDensity density) {
        Settings.put(KEY_GRAPH_DENSITY,
                (density == null ? com.jfx.git.graph.GraphDensity.COMPACT : density).name());
    }

    /**
     * Whether commit rows carry a wash of their branch's colour.
     *
     * <p>On by default: banding a run of commits is the change that does most for reading
     * the graph. Switchable because it is a second colour signal stacked on the lane
     * colour, and some people would rather have neither.
     */
    public static boolean graphBranchTint() {
        return Settings.getBoolean(KEY_GRAPH_TINT, true);
    }

    public static void setGraphBranchTint(boolean on) {
        Settings.putBoolean(KEY_GRAPH_TINT, on);
    }

    /**
     * What a commit is drawn as in the graph.
     *
     * <p>Defaults to the author avatar: it answers "who" and "where" in one place and
     * pays for itself by retiring the author column. Anyone who would rather have the
     * narrower graph can have the dot back.
     */
    /**
     * Whether an optional graph column is switched on.
     *
     * <p>Keyed per column rather than as one packed string, so a column added later
     * defaults to on for an existing user instead of being absent from a stored list and
     * silently disappearing.
     */
    public static boolean graphColumnVisible(com.jfx.git.graph.GraphColumn column) {
        return column != null
                && Settings.getBoolean(KEY_GRAPH_COLUMN + column.name().toLowerCase(java.util.Locale.ROOT), true);
    }

    public static void setGraphColumnVisible(com.jfx.git.graph.GraphColumn column, boolean visible) {
        if (column != null) {
            Settings.putBoolean(
                    KEY_GRAPH_COLUMN + column.name().toLowerCase(java.util.Locale.ROOT), visible);
        }
    }

    /**
     * The user's column order, or the declaration order when none is stored.
     *
     * <p>Stored as one comma-separated name list rather than per-column keys (as
     * visibility is) because an order is a fact about the whole sequence: half a
     * permutation cannot be meaningfully defaulted.
     */
    public static java.util.List<com.jfx.git.graph.GraphColumn> graphColumnOrder() {
        String stored = Settings.get(KEY_GRAPH_COLUMN_ORDER, null);
        if (stored == null || stored.isBlank()) {
            return java.util.List.of(com.jfx.git.graph.GraphColumn.values());
        }
        java.util.List<com.jfx.git.graph.GraphColumn> order = new java.util.ArrayList<>();
        for (String name : stored.split(",")) {
            try {
                com.jfx.git.graph.GraphColumn column = com.jfx.git.graph.GraphColumn.valueOf(name.trim());
                if (!order.contains(column)) {
                    order.add(column);
                }
            } catch (IllegalArgumentException ignored) {
                // A column removed from a newer build: skip it rather than fail.
            }
        }
        // Columns missing from the stored list (added later, or a mangled entry)
        // keep their declaration position after the ones that were named.
        for (com.jfx.git.graph.GraphColumn column : com.jfx.git.graph.GraphColumn.values()) {
            if (!order.contains(column)) {
                order.add(column);
            }
        }
        return order;
    }

    public static void setGraphColumnOrder(java.util.List<com.jfx.git.graph.GraphColumn> order) {
        if (order == null || order.isEmpty()) return;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) names.append(',');
            names.append(order.get(i).name());
        }
        Settings.put(KEY_GRAPH_COLUMN_ORDER, names.toString());
    }

    /**
     * How many lane columns the graph is fixed at, or 0 to follow the window.
     *
     * <p>Remembered because it is a deliberate act — someone dragged the graph to the
     * width they wanted — and having to redo it on every launch would make the handle
     * not worth using.
     */
    public static int graphLaneBudget() {
        try {
            return Math.max(0, Integer.parseInt(Settings.get(KEY_GRAPH_LANES, "0")));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void setGraphLaneBudget(int lanes) {
        if (lanes <= 0) {
            Settings.remove(KEY_GRAPH_LANES);
        } else {
            Settings.put(KEY_GRAPH_LANES, Integer.toString(lanes));
        }
    }

    /**
     * The width the graph's branch column was dragged to, or 0 to let it fit its names.
     *
     * <p>Remembered for the same reason as the lane budget: it is something someone set.
     */
    public static double graphRailWidth() {
        try {
            return Math.max(0, Double.parseDouble(Settings.get(KEY_GRAPH_RAIL, "0")));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void setGraphRailWidth(double width) {
        if (width <= 0) {
            Settings.remove(KEY_GRAPH_RAIL);
        } else {
            Settings.put(KEY_GRAPH_RAIL, Long.toString(Math.round(width)));
        }
    }

    /**
     * The width the sidebar was dragged to, in pixels, or 0 to size it from the window.
     *
     * <p>In pixels rather than as a share of the window: a panel someone made wide enough
     * for their branch names should not narrow because the window was resized.
     */
    public static double layoutSidebarWidth() {
        return storedWidth(KEY_LAYOUT_SIDEBAR);
    }

    public static void setLayoutSidebarWidth(double width) {
        storeWidth(KEY_LAYOUT_SIDEBAR, width);
    }

    /**
     * The height the commit message in the details panel was dragged to, or 0 to fit it
     * to the message (up to a cap). One height for every commit, as commercial clients keep it.
     */
    public static double commitMessageHeight() {
        return storedWidth(KEY_LAYOUT_COMMIT_MESSAGE);
    }

    public static void setCommitMessageHeight(double height) {
        storeWidth(KEY_LAYOUT_COMMIT_MESSAGE, height);
    }

    /** The width the details panel was dragged to, or 0 to size it from the window. */
    public static double layoutDetailWidth() {
        return storedWidth(KEY_LAYOUT_DETAIL);
    }

    public static void setLayoutDetailWidth(double width) {
        storeWidth(KEY_LAYOUT_DETAIL, width);
    }

    private static double storedWidth(String key) {
        try {
            return Math.max(0, Double.parseDouble(Settings.get(key, "0")));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void storeWidth(String key, double width) {
        if (width <= 0) {
            Settings.remove(key);
        } else {
            Settings.put(key, Long.toString(Math.round(width)));
        }
    }

    public static com.jfx.git.graph.GraphNodeStyle graphNodeStyle() {
        String stored = Settings.get(KEY_GRAPH_NODE, com.jfx.git.graph.GraphNodeStyle.AUTHOR.name());
        try {
            return com.jfx.git.graph.GraphNodeStyle.valueOf(stored);
        } catch (IllegalArgumentException e) {
            // A value written by a newer version is not worth failing over.
            return com.jfx.git.graph.GraphNodeStyle.AUTHOR;
        }
    }

    public static void setGraphNodeStyle(com.jfx.git.graph.GraphNodeStyle style) {
        Settings.put(KEY_GRAPH_NODE,
                (style == null ? com.jfx.git.graph.GraphNodeStyle.AUTHOR : style).name());
    }

    /**
     * How commits are ordered, and so how the graph is shaped.
     *
     * <p>Defaults to grouping by branch. Chronological is the livelier picture but it
     * holds many more lanes open at once, and on a repository with hundreds of branches
     * that is only readable on a wide window.
     */
    public static com.jfx.git.graph.GraphOrder graphOrder() {
        String stored = Settings.get(KEY_GRAPH_ORDER, com.jfx.git.graph.GraphOrder.BY_BRANCH.name());
        try {
            return com.jfx.git.graph.GraphOrder.valueOf(stored);
        } catch (IllegalArgumentException e) {
            // A value written by a newer version is not worth failing over.
            return com.jfx.git.graph.GraphOrder.BY_BRANCH;
        }
    }

    public static void setGraphOrder(com.jfx.git.graph.GraphOrder order) {
        Settings.put(KEY_GRAPH_ORDER,
                (order == null ? com.jfx.git.graph.GraphOrder.BY_BRANCH : order).name());
    }

    /**
     * Refs the user has hidden from the commit graph's walk, by {@code fullName}.
     *
     * <p>A comma-separated list, like {@link #graphColumnOrder()}: a ref name cannot
     * contain a comma, and the set is usually small enough that this stays readable in
     * the settings file.
     */
    public static java.util.Set<String> graphHiddenBranches() {
        String stored = Settings.get(KEY_GRAPH_HIDDEN_BRANCHES, "");
        if (stored.isBlank()) {
            return java.util.Set.of();
        }
        java.util.Set<String> refs = new java.util.LinkedHashSet<>();
        for (String name : stored.split(",")) {
            if (!name.isBlank()) refs.add(name.trim());
        }
        return refs;
    }

    public static void setGraphHiddenBranches(java.util.Set<String> refs) {
        if (refs == null || refs.isEmpty()) {
            Settings.remove(KEY_GRAPH_HIDDEN_BRANCHES);
        } else {
            Settings.put(KEY_GRAPH_HIDDEN_BRANCHES, String.join(",", refs));
        }
    }

    /**
     * The ref the graph is soloed to, by {@code fullName}, or {@code null} for none.
     *
     * <p>Single-valued rather than a set: the commercial clients' Solo replaces any previous solo
     * rather than compounding with it, and {@link com.jfx.git.graph.GraphVisibility}
     * relies on there being at most one.
     */
    public static String graphSoloBranch() {
        String stored = Settings.get(KEY_GRAPH_SOLO_BRANCH, "");
        return stored.isBlank() ? null : stored;
    }

    public static void setGraphSoloBranch(String fullRefName) {
        if (fullRefName == null || fullRefName.isBlank()) {
            Settings.remove(KEY_GRAPH_SOLO_BRANCH);
        } else {
            Settings.put(KEY_GRAPH_SOLO_BRANCH, fullRefName);
        }
    }

    /**
     * Stashes the user has hidden from the commit graph, by commit hash.
     *
     * <p>A stash is not a ref - nothing points at it once it is out of the stash stack's
     * own reflog - so it cannot join {@link #graphHiddenBranches()}'s ref-name set, and it
     * has no meaningful "solo" either: solo isolates a line of history reachable from a
     * ref, and nothing is reachable from a stash. Hide is a pure display suppression
     * ({@code com.jfx.git.graph.StashRows} skips a hidden hash before laying out markers),
     * independent of the ref-based hide/solo pair above.
     */
    public static java.util.Set<String> hiddenStashes() {
        String stored = Settings.get(KEY_GRAPH_HIDDEN_STASHES, "");
        if (stored.isBlank()) {
            return java.util.Set.of();
        }
        java.util.Set<String> hashes = new java.util.LinkedHashSet<>();
        for (String hash : stored.split(",")) {
            if (!hash.isBlank()) hashes.add(hash.trim());
        }
        return hashes;
    }

    public static void setHiddenStashes(java.util.Set<String> hashes) {
        if (hashes == null || hashes.isEmpty()) {
            Settings.remove(KEY_GRAPH_HIDDEN_STASHES);
        } else {
            Settings.put(KEY_GRAPH_HIDDEN_STASHES, String.join(",", hashes));
        }
    }

    /**
     * How many commits the graph walks before its first page stops - the commercial
     * clients' "Initial Commits in Graph". Scrolling to the end still walks further via the
     * existing "load more" paging; this only changes where the first page ends.
     */
    public static int initialGraphCommits() {
        try {
            int stored = Integer.parseInt(Settings.get(KEY_GRAPH_INITIAL_COMMITS, ""));
            return stored > 0 ? stored : GitService.DEFAULT_GRAPH_LIMIT;
        } catch (NumberFormatException e) {
            return GitService.DEFAULT_GRAPH_LIMIT;
        }
    }

    public static void setInitialGraphCommits(int commits) {
        if (commits <= 0) {
            Settings.remove(KEY_GRAPH_INITIAL_COMMITS);
        } else {
            Settings.put(KEY_GRAPH_INITIAL_COMMITS, Integer.toString(commits));
        }
    }

    /**
     * The commercial clients' "Show All Commits in Graph": keeps paging until the history runs out,
     * rather than stopping at {@link #initialGraphCommits()}.
     *
     * <p>It walks a larger first page and then goes on loading older commits as the user
     * scrolls; it does not walk the whole history before drawing anything, which is what
     * it used to do and what made a large repository look like it had hung on open.
     */
    public static boolean showAllCommitsInGraph() {
        return Settings.getBoolean(KEY_GRAPH_SHOW_ALL, false);
    }

    public static void setShowAllCommitsInGraph(boolean showAll) {
        Settings.putBoolean(KEY_GRAPH_SHOW_ALL, showAll);
    }

    // ------------------------------------------------------------------
    // General (the commercial clients' category of the same name)
    // ------------------------------------------------------------------

    /** The branch name a new repository's initial commit lands on. The commercial clients' default. */
    public static String defaultBranchName() {
        String stored = Settings.get(KEY_DEFAULT_BRANCH_NAME, "main");
        return stored.isBlank() ? "main" : stored;
    }

    public static void setDefaultBranchName(String name) {
        if (name == null || name.isBlank()) {
            Settings.remove(KEY_DEFAULT_BRANCH_NAME);
        } else {
            Settings.put(KEY_DEFAULT_BRANCH_NAME, name.trim());
        }
    }

    /**
     * How often each open repository fetches in the background, in minutes; 0 turns
     * auto-fetch off. Commercial clients offer 0/1/5/15/60; this is not restricted to that list
     * so a saved value from a future version's extra choice is not silently discarded.
     */
    public static int autoFetchIntervalMinutes() {
        try {
            return Math.max(0, Integer.parseInt(Settings.get(KEY_AUTO_FETCH_MINUTES, "0")));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static void setAutoFetchIntervalMinutes(int minutes) {
        if (minutes <= 0) {
            Settings.remove(KEY_AUTO_FETCH_MINUTES);
        } else {
            Settings.put(KEY_AUTO_FETCH_MINUTES, Integer.toString(minutes));
        }
    }

    /**
     * What the Pull button does when clicked rather than opened.
     *
     * <p>Merge by default, which is git's own default and the one that never refuses.
     * Teams that rebase can say so once instead of reaching into the dropdown every time
     * - and a repository that disagrees with the rest can say so too, through
     * {@link RepositoryPreferences#pullMode(java.io.File)}.
     */
    public static com.jfx.git.model.PullMode defaultPullMode() {
        String stored = Settings.get(KEY_PULL_MODE, com.jfx.git.model.PullMode.MERGE.name());
        try {
            return com.jfx.git.model.PullMode.valueOf(stored);
        } catch (IllegalArgumentException e) {
            // Written by a newer version, or edited by hand into nonsense.
            return com.jfx.git.model.PullMode.MERGE;
        }
    }

    public static void setDefaultPullMode(com.jfx.git.model.PullMode mode) {
        Settings.put(KEY_PULL_MODE, mode == null ? null : mode.name());
    }

    /**
     * Whether a checkout or pull also runs {@code submodule update} afterward, the way
     * commercial clients offer as a preference rather than a default. Off by default: unlike
     * auto-fetch, this runs unasked as a side effect of an action the user already
     * triggered, and a repository with submodules the user does not want kept in lock
     * step should not suddenly start cloning or fetching them in the background.
     */
    public static boolean autoUpdateSubmodules() {
        return Settings.getBoolean(KEY_SUBMODULE_AUTO_UPDATE, false);
    }

    public static void setAutoUpdateSubmodules(boolean on) {
        Settings.putBoolean(KEY_SUBMODULE_AUTO_UPDATE, on);
    }

    // ------------------------------------------------------------------
    // Safety
    // ------------------------------------------------------------------

    /**
     * Whether discarding a file's changes asks first: a whole file, a selection of files,
     * a hunk or some lines, from the staging area.
     *
     * <p>Those discards are snapshotted first and offer Undo, so turning this off costs a
     * question, not the work. "Discard all changes" on the graph's WIP row is not covered
     * and always asks. On by default, as it was before snapshots existed: someone used to
     * the question should not lose it in an upgrade.
     */
    public static boolean confirmDiscard() {
        return Settings.getBoolean(KEY_CONFIRM_DISCARD, true);
    }

    public static void setConfirmDiscard(boolean confirm) {
        Settings.putBoolean(KEY_CONFIRM_DISCARD, confirm);
    }

    /**
     * Whether the built-in editor keeps a recoverable copy of an unsaved buffer on disk.
     *
     * <p>On by default, because a crash-recovery feature nobody has switched on protects
     * nobody. Switchable at all because it writes the <em>contents</em> of source files,
     * in plaintext, outside the repository: for someone working on a repository they
     * consider sensitive that is a policy question rather than a convenience, and "you
     * cannot turn it off" is not an acceptable answer to it.
     *
     * @see EditorDrafts
     */
    public static boolean editorDraftsEnabled() {
        return Settings.getBoolean(KEY_EDITOR_DRAFTS, true);
    }

    public static void setEditorDraftsEnabled(boolean enabled) {
        Settings.putBoolean(KEY_EDITOR_DRAFTS, enabled);
    }

    // ------------------------------------------------------------------
    // Commit (the Settings tab's Commit page)
    // ------------------------------------------------------------------

    private static final String KEY_COMMIT_PUSH_AFTER = "commit.pushAfter";
    private static final String KEY_COMMIT_ISSUE_KEY = "commit.issueKey.enabled";
    private static final String KEY_COMMIT_ISSUE_PATTERN = "commit.issueKey.pattern";
    private static final String KEY_COMMIT_ISSUE_FORMAT = "commit.issueKey.format";
    private static final String KEY_COMMIT_CHECK_SECRETS = "commit.check.secrets";
    private static final String KEY_COMMIT_CHECK_LARGE = "commit.check.largeFiles";
    private static final String KEY_COMMIT_LARGE_MB = "commit.check.largeFileMb";

    /** Where a staged file starts counting as large, unless the user says otherwise. */
    public static final int DEFAULT_LARGE_FILE_MB = 50;

    /**
     * Whether every commit from the composer is followed by a push. Off by default: a
     * push is the step that cannot be taken back quietly, so it should be chosen.
     */
    public static boolean pushAfterCommit() {
        return Settings.getBoolean(KEY_COMMIT_PUSH_AFTER, false);
    }

    public static void setPushAfterCommit(boolean on) {
        Settings.putBoolean(KEY_COMMIT_PUSH_AFTER, on);
    }

    /** Whether an empty summary starts with the issue key the branch is named after. */
    public static boolean issueKeyFromBranch() {
        return Settings.getBoolean(KEY_COMMIT_ISSUE_KEY, true);
    }

    public static void setIssueKeyFromBranch(boolean on) {
        Settings.putBoolean(KEY_COMMIT_ISSUE_KEY, on);
    }

    /** The regular expression that finds the issue key in a branch name. */
    public static String issueKeyPattern() {
        String stored = Settings.get(KEY_COMMIT_ISSUE_PATTERN, "");
        return stored.isBlank() ? BranchIssueKey.DEFAULT_PATTERN : stored;
    }

    /** Blank goes back to the default. */
    public static void setIssueKeyPattern(String pattern) {
        Settings.put(KEY_COMMIT_ISSUE_PATTERN, pattern == null || pattern.isBlank() ? null : pattern);
    }

    /** The prefix's shape, with {@code {key}} where the key goes. */
    public static String issueKeyFormat() {
        String stored = Settings.get(KEY_COMMIT_ISSUE_FORMAT, "");
        return stored.isEmpty() ? BranchIssueKey.DEFAULT_FORMAT : stored;
    }

    /** Empty goes back to the default. Not trimmed: the trailing space is usually the point. */
    public static void setIssueKeyFormat(String format) {
        Settings.put(KEY_COMMIT_ISSUE_FORMAT, format == null || format.isEmpty() ? null : format);
    }

    /** Whether committing first looks for tokens and keys in what is staged. */
    public static boolean checkSecretsBeforeCommit() {
        return Settings.getBoolean(KEY_COMMIT_CHECK_SECRETS, true);
    }

    public static void setCheckSecretsBeforeCommit(boolean on) {
        Settings.putBoolean(KEY_COMMIT_CHECK_SECRETS, on);
    }

    /** Whether committing first looks for staged files over {@link #largeFileThresholdMb()}. */
    public static boolean checkLargeFilesBeforeCommit() {
        return Settings.getBoolean(KEY_COMMIT_CHECK_LARGE, true);
    }

    public static void setCheckLargeFilesBeforeCommit(boolean on) {
        Settings.putBoolean(KEY_COMMIT_CHECK_LARGE, on);
    }

    /** Where a staged file counts as large, in megabytes; at least 1. */
    public static int largeFileThresholdMb() {
        try {
            int stored = Integer.parseInt(Settings.get(KEY_COMMIT_LARGE_MB, ""));
            return stored >= 1 ? stored : DEFAULT_LARGE_FILE_MB;
        } catch (NumberFormatException e) {
            return DEFAULT_LARGE_FILE_MB;
        }
    }

    public static void setLargeFileThresholdMb(int megabytes) {
        if (megabytes < 1 || megabytes == DEFAULT_LARGE_FILE_MB) {
            Settings.remove(KEY_COMMIT_LARGE_MB);
        } else {
            Settings.put(KEY_COMMIT_LARGE_MB, Integer.toString(megabytes));
        }
    }

    private static final String KEY_CONFLICT_WARNING = "conflicts.warn";
    private static final String KEY_SNAPSHOT_MINUTES = "snapshots.minutes";

    /**
     * Whether the status bar warns when the current branch would conflict with its base
     * branch - see {@link ConflictBase}. On by default; a repository can still turn it off
     * for itself through {@link RepositoryPreferences#setConflictWarning}.
     */
    public static boolean warnAboutConflicts() {
        return Settings.getBoolean(KEY_CONFLICT_WARNING, true);
    }

    public static void setWarnAboutConflicts(boolean on) {
        Settings.putBoolean(KEY_CONFLICT_WARNING, on);
    }

    /**
     * How often an open repository's working tree is snapshotted, in minutes; 0 turns the
     * timer off. Snapshots before a discard or a hard reset are taken either way - see
     * {@link WorkingTreeSnapshots}.
     */
    public static int snapshotIntervalMinutes() {
        try {
            return Math.max(0, Integer.parseInt(Settings.get(KEY_SNAPSHOT_MINUTES, "10").trim()));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    public static void setSnapshotIntervalMinutes(int minutes) {
        Settings.put(KEY_SNAPSHOT_MINUTES, Integer.toString(Math.max(0, minutes)));
    }

    // ------------------------------------------------------------------
    // Stash and retry
    // ------------------------------------------------------------------

    /**
     * Whether a checkout, pull or merge that local changes are in the way of stashes them,
     * runs again and re-applies them without asking first. Off by default: the first time,
     * the user is asked, and "Always do this" turns this on. See {@link AutoStash} and
     * {@code ui.AutoStashQuestion}, which asks.
     *
     * <p>Named, and stored, for the checkout and pull it first covered: a merge is the
     * same three steps and the same question, so one answer covers all three, and the
     * key stays as it was so a setting already made carries over.
     */
    public static boolean autoStashOnCheckoutPull() {
        return Settings.getBoolean(KEY_AUTOSTASH_CHECKOUT_PULL, false);
    }

    public static void setAutoStashOnCheckoutPull(boolean always) {
        Settings.putBoolean(KEY_AUTOSTASH_CHECKOUT_PULL, always);
    }
}
