package com.jfx.git.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Backend-agnostic view of a single commit.
 *
 * <p>Replaces {@code RevCommit} and {@code PlotCommit<PlotLane>} throughout the UI layer.
 * All fields are plain Java types so any backend implementation (JGit, native git subprocess,
 * libgit2 via JNI) can produce them without leaking library types into the UI.
 *
 * <p>Graph-layout fields ({@link #lane}, {@link #passingLanes}, {@link #edges}) are
 * populated by {@link com.jfx.git.graph.LaneAssigner} after the commit list is fetched.
 * They are null/empty until assigned.
 *
 * <h2>Full message or summary</h2>
 * A commit carries either its whole message or, on a graph page, only a {@linkplain
 * #summary summary}: the subject and a short preview of the body. A page is re-walked on
 * every refresh and every "load more", and carrying every body of every row on it meant
 * reading, parsing and keeping text the rows never draw (G15). Whatever needs the body -
 * the detail panel, a squash - asks the engine for it
 * ({@link com.jfx.git.core.HistoryOps#getCommitMessage}).
 *
 * <p>"Not loaded" is a state of its own, never an empty string: {@link #getFullMessage()}
 * throws on a summary rather than answering {@code ""}, because a squash that read a
 * missing body as an empty one would quietly drop it from the combined message. Check
 * {@link #hasFullMessage()} first.
 */
public final class CommitInfo {

    /**
     * How many columns of body a summary keeps: enough for the dimmed text a graph row
     * shows after its subject, which the row clips anyway. The native engine asks git for
     * exactly this much ({@code %<(N,trunc)%b}), so the two engines' previews agree.
     */
    public static final int BODY_PREVIEW_COLUMNS = 80;

    private final String hash;
    /** The whole message, or {@code null} on a {@linkplain #summary summary}. */
    private final String message;
    /** The subject, set only on a summary; otherwise derived from {@link #message}. */
    private final String subject;
    /** The flattened, bounded body preview, set only on a summary. */
    private final String bodyPreview;
    private final String authorName;
    private final String authorEmail;
    private final Instant authorDate;
    /**
     * When the commit object itself was written.
     *
     * <p>Distinct from {@link #authorDate}: a rebase, an {@code am} or a cherry-pick keeps
     * the author's date and stamps a new committer date. The graph's walk selects commits
     * by committer date - git's own default, and JGit's {@code COMMIT_TIME_DESC} - so the
     * ordering has to use the same clock, or a freshly rebased branch sinks below commits
     * that were selected after it.
     */
    private final Instant committerDate;
    private final List<String> parentHashes;
    private final List<RefInfo> refs;

    // Mutated by LaneAssigner after construction.
    private int lane;
    private List<Integer> childLanes = List.of();     // lanes entering this node from above
    private List<Integer> parentLanes = List.of();    // lanes exiting this node to below
    private List<Integer> passingLanes = List.of();   // lanes whose edges pass through this row
    /**
     * Palette index for each lane drawn on this row.
     *
     * <p>Keyed on lane so the renderer can colour an edge by the branch it belongs to
     * rather than by the column it happens to occupy. Colouring by column looks stable
     * until a lane is freed and reused further down, at which point an unrelated branch
     * inherits its colour.
     */
    private Map<Integer, Integer> laneColors = Map.of();
    /**
     * Palette index of each edge arriving at this node from above, keyed on its lane.
     *
     * <p>Separate from {@link #laneColors} because a lane can carry two edges on one row:
     * a side branch arriving in its upper half, and a new parent edge leaving in its lower
     * half once the lane is free. The arriving edge keeps the colour of the branch it
     * comes from, all the way into the node it joins.
     */
    private Map<Integer, Integer> childLaneColors = Map.of();
    /** Widest lane index on the whole page, so every row reserves the same width. */
    private int totalLanes = 1;
    private boolean onCurrentBranch = true;
    /**
     * Local branch short name to the short name of the remote-tracking branch it tracks,
     * for the graph's ref rail; {@code null} when the engine did not say.
     *
     * <p>Lets {@link com.jfx.git.graph.RefRail} fold {@code origin/main} into {@code main}
     * only when {@code main} actually tracks {@code origin/main} - not merely because some
     * remote happens to have a branch of the same name at the same commit.
     */
    private Map<String, String> upstreams;
    /**
     * The path the file had in this commit, for a file-history row; {@code null}
     * elsewhere. A history that follows renames reaches commits where the file was called
     * something else, and diffing or blaming those under today's name finds nothing.
     */
    private String historyPath;

    public CommitInfo(String hash,
                      String message,
                      String authorName,
                      String authorEmail,
                      Instant authorDate,
                      List<String> parentHashes,
                      List<RefInfo> refs) {
        this(hash, message, authorName, authorEmail, authorDate, authorDate, parentHashes, refs);
    }

    /**
     * The full form, with the committer date given separately.
     *
     * <p>The shorter constructor treats the two dates as equal, which is true for most
     * commits and is what test fixtures want; the engines use this one.
     */
    public CommitInfo(String hash,
                      String message,
                      String authorName,
                      String authorEmail,
                      Instant authorDate,
                      Instant committerDate,
                      List<String> parentHashes,
                      List<RefInfo> refs) {
        this(hash, message == null ? "" : message, null, null, authorName, authorEmail,
                authorDate, committerDate, parentHashes, refs);
    }

    private CommitInfo(String hash, String message, String subject, String bodyPreview,
                       String authorName, String authorEmail, Instant authorDate,
                       Instant committerDate, List<String> parentHashes, List<RefInfo> refs) {
        this.hash        = hash;
        this.message     = message;
        this.subject     = subject;
        this.bodyPreview = bodyPreview;
        this.authorName  = authorName;
        this.authorEmail = authorEmail;
        this.authorDate  = authorDate;
        this.committerDate = committerDate != null ? committerDate : authorDate;
        this.parentHashes = List.copyOf(parentHashes);
        this.refs         = List.copyOf(refs);
    }

    /**
     * A commit that carries only its subject and a bounded body preview - what a graph
     * page holds. {@link #getFullMessage()} refuses to answer for it; the body is fetched
     * on demand with {@link com.jfx.git.core.HistoryOps#getCommitMessage}.
     *
     * @param subject     git's subject ({@code %s}): the first paragraph, on one line
     * @param bodyPreview the start of the body, flattened to one line by this method and
     *                    at most about {@link #BODY_PREVIEW_COLUMNS} long; empty when none
     */
    public static CommitInfo summary(String hash, String subject, String bodyPreview,
                                     String authorName, String authorEmail,
                                     Instant authorDate, Instant committerDate,
                                     List<String> parentHashes, List<RefInfo> refs) {
        return new CommitInfo(hash, null, subject == null ? "" : subject,
                flatten(bodyPreview), authorName, authorEmail, authorDate, committerDate,
                parentHashes, refs);
    }

    /**
     * The {@linkplain #summary summary} of a whole message, cut the way the native
     * engine's graph format has git cut it, so the engines' pages agree.
     */
    public static CommitInfo summaryOf(String hash, String fullMessage,
                                       String authorName, String authorEmail,
                                       Instant authorDate, Instant committerDate,
                                       List<String> parentHashes, List<RefInfo> refs) {
        return summary(hash, subjectOf(fullMessage), bodyPreviewOf(fullMessage),
                authorName, authorEmail, authorDate, committerDate, parentHashes, refs);
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    public String getHash() { return hash; }

    /** Abbreviated 7-character hash — safe for display; never used for JGit lookups. */
    public String getShortHash() { return hash.length() >= 7 ? hash.substring(0, 7) : hash; }

    /**
     * The subject, as git defines it ({@code %s}): the first paragraph of the message,
     * its lines joined by single spaces.
     *
     * <p>Git's rule rather than "the first line", so a commit reads the same wherever it
     * came from: a graph page, whose subject git cut on the native engine, or a whole
     * message read later.
     */
    public String getSubject() {
        String known = subject != null ? subject : derivedSubject;
        if (known == null) {
            known = subjectOf(message);
            derivedSubject = known;
        }
        return known;
    }

    /**
     * {@link #getSubject()} and {@link #getBodySummary()} worked out once from a whole
     * message. Both are asked on every graph row update, and deriving them runs a split
     * and a regex - about a quarter of what a row update allocated, for an answer that
     * never changes. Not final only because they are filled on first use; a race fills
     * them twice with the same value.
     */
    private volatile String derivedSubject;
    private volatile String derivedBodySummary;

    /** Whether this commit carries its whole message, rather than being a {@linkplain #summary summary}. */
    public boolean hasFullMessage() { return message != null; }

    /**
     * The whole message.
     *
     * @throws IllegalStateException on a {@linkplain #summary summary}, which never had
     *         it - answering {@code ""} there would pass a missing body off as an empty one
     */
    public String getFullMessage() {
        if (message == null) {
            throw new IllegalStateException("Only the subject of " + getShortHash()
                    + " was loaded; fetch its message with GitBackend.getCommitMessage");
        }
        return message;
    }

    /**
     * The body, flattened to a single line - cut at about {@link #BODY_PREVIEW_COLUMNS}
     * on a {@linkplain #summary summary}.
     *
     * <p>The graph shows this dimmed on the same line as the subject, which roughly
     * doubles what a row says at no extra height. Blank lines and line breaks collapse to
     * single spaces because the row has exactly one line to give it.
     */
    public String getBodySummary() {
        if (bodyPreview != null) return bodyPreview;
        String known = derivedBodySummary;
        if (known == null) {
            known = flatten(bodyOf(message));
            derivedBodySummary = known;
        }
        return known;
    }

    /**
     * Whether {@link #getBodySummary()} is the whole body, flattened, rather than a cut
     * preview - so that a word missing from it is missing from the message too.
     *
     * <p>A cut preview ends in the {@code ..} marker. A short body that happens to end in
     * {@code ..} itself reads as cut as well, which only ever makes a caller more careful.
     */
    public boolean isBodySummaryWhole() {
        return bodyPreview == null || !bodyPreview.endsWith("..");
    }

    /**
     * Git's subject of {@code message} ({@code %s}): leading blank lines skipped, then
     * every line up to the first blank one, each right-trimmed, joined by a space.
     */
    public static String subjectOf(String message) {
        if (message == null || message.isBlank()) return "";
        String[] lines = message.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) i++;
        StringBuilder subject = new StringBuilder();
        for (; i < lines.length && !lines[i].isBlank(); i++) {
            if (!subject.isEmpty()) subject.append(' ');
            subject.append(lines[i].stripTrailing());
        }
        return subject.toString();
    }

    /**
     * Git's body of {@code message} ({@code %b}): everything after the subject paragraph,
     * from its first non-blank line on, verbatim.
     */
    public static String bodyOf(String message) {
        if (message == null || message.isBlank()) return "";
        String[] lines = message.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) i++;
        while (i < lines.length && !lines[i].isBlank()) i++;
        while (i < lines.length && lines[i].isBlank()) i++;
        return i >= lines.length
                ? ""
                : String.join("\n", java.util.Arrays.asList(lines).subList(i, lines.length));
    }

    /**
     * The body preview a {@linkplain #summary summary} of {@code message} keeps: the body
     * flattened to one line and cut at {@link #BODY_PREVIEW_COLUMNS}, ending in {@code ..}
     * when cut - the marker git's {@code %<(N,trunc)} leaves.
     */
    public static String bodyPreviewOf(String message) {
        String body = flatten(bodyOf(message));
        if (body.length() <= BODY_PREVIEW_COLUMNS) return body;
        int cut = BODY_PREVIEW_COLUMNS - 2;
        // Never between the two halves of a surrogate pair.
        if (Character.isHighSurrogate(body.charAt(cut - 1))) cut--;
        return body.substring(0, cut).stripTrailing() + "..";
    }

    /** Runs of whitespace, line breaks included, as single spaces; trimmed. */
    private static String flatten(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
    public String getAuthorName()  { return authorName; }
    public String getAuthorEmail() { return authorEmail; }
    public Instant getAuthorDate() { return authorDate; }
    /** When the commit was written; equals the author date unless an engine said otherwise. */
    public Instant getCommitterDate() { return committerDate; }
    public List<String> getParentHashes() { return parentHashes; }
    public List<RefInfo> getRefs() { return refs; }

    // ------------------------------------------------------------------
    // Graph layout (set by LaneAssigner)
    // ------------------------------------------------------------------

    /** Column index (0-based) assigned by {@link com.jfx.git.graph.LaneAssigner}. */
    public int getLane() { return lane; }
    public void setLane(int lane) { this.lane = lane; }

    /** Lane indices entering this commit node from above (children). */
    public List<Integer> getChildLanes() { return childLanes; }
    public void setChildLanes(List<Integer> childLanes) { this.childLanes = List.copyOf(childLanes); }

    /** Lane indices exiting this commit node to below (parents). */
    public List<Integer> getParentLanes() { return parentLanes; }
    public void setParentLanes(List<Integer> parentLanes) { this.parentLanes = List.copyOf(parentLanes); }

    /** Lane indices whose edges pass straight through this row without stopping. */
    public List<Integer> getPassingLanes() { return passingLanes; }
    public void setPassingLanes(List<Integer> passingLanes) { this.passingLanes = List.copyOf(passingLanes); }

    /** Palette index for {@code lane} on this row; falls back to the lane index. */
    public int colorOfLane(int lane) {
        Integer color = laneColors.get(lane);
        return color != null ? color : lane;
    }

    /** Palette index for this commit's own dot. */
    public int getColorIndex() { return colorOfLane(lane); }

    public void setLaneColors(Map<Integer, Integer> laneColors) {
        this.laneColors = Map.copyOf(laneColors);
    }

    /**
     * Palette index of the edge arriving from above in {@code lane}: the colour of the
     * branch it comes from. Falls back to {@link #colorOfLane}.
     */
    public int childLaneColorOf(int lane) {
        Integer color = childLaneColors.get(lane);
        return color != null ? color : colorOfLane(lane);
    }

    public void setChildLaneColors(Map<Integer, Integer> childLaneColors) {
        this.childLaneColors = Map.copyOf(childLaneColors);
    }

    /**
     * Number of lane columns the whole page needs.
     *
     * <p>Every row reserves the same width from this, so the message column lines up.
     * Sizing each row to its own widest lane leaves a ragged left edge as the graph
     * narrows and widens.
     */
    public int getTotalLanes() { return totalLanes; }
    public void setTotalLanes(int totalLanes) { this.totalLanes = Math.max(1, totalLanes); }

    public boolean isOnCurrentBranch() { return onCurrentBranch; }
    public void setOnCurrentBranch(boolean onCurrentBranch) { this.onCurrentBranch = onCurrentBranch; }

    /** Local branch to its remote-tracking upstream, both short names; null when unknown. */
    public Map<String, String> getUpstreams() { return upstreams; }
    public void setUpstreams(Map<String, String> upstreams) {
        this.upstreams = upstreams == null ? null : Map.copyOf(upstreams);
    }

    /** The file's path in this commit, for a file-history row; null when not a history row. */
    public String getHistoryPath() { return historyPath; }
    public void setHistoryPath(String historyPath) { this.historyPath = historyPath; }

    // ------------------------------------------------------------------
    // Convenience
    // ------------------------------------------------------------------

    @Override
    public String toString() { return getShortHash() + " " + getSubject(); }

    @Override
    public boolean equals(Object o) {
        return o instanceof CommitInfo c && hash.equals(c.hash);
    }

    @Override
    public int hashCode() { return hash.hashCode(); }
}
