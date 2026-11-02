package com.jfx.git.core;

import java.time.Instant;

/**
 * One line of a branch's reflog: where it pointed, where it moved to, and why.
 *
 * <p>The reflog is the record of every move a branch made, including the ones that
 * rewrote history. It is what turns "I squashed the wrong commit" from a lost afternoon
 * into an undo, so the app surfaces it rather than leaving it to a terminal.
 *
 * @param index    position in the log; 0 is the most recent move, matching {@code HEAD@{0}}
 * @param oldHash  where the branch pointed before this move (full SHA-1, or all-zeros for branch creation)
 * @param newHash  where the branch pointed after this move (full SHA-1)
 * @param comment  Git's own description, e.g. "rebase (finish)" or "commit (amend)"
 */
public record ReflogItem(int index,
                         String oldHash,
                         String newHash,
                         String comment,
                         String author,
                         Instant when) {

    private static final String ZERO = "0000000000000000000000000000000000000000";

    /** {@code HEAD@{2}} - the notation Git itself uses, and what a user would type. */
    public String selector() {
        return "HEAD@{" + index + "}";
    }

    /**
     * The commit hash to restore to in order to get this state back.
     *
     * <p>It is {@link #newHash()}, not {@link #oldHash()}: {@code HEAD@{n}} means the
     * value HEAD <em>had</em> after that move, which is where it ended up. Reading the
     * other end lands one step further back than the row the user pointed at.
     */
    public String restoreTarget() {
        return newHash;
    }

    public String shortOldHash() { return shorten(oldHash); }
    public String shortNewHash() { return shorten(newHash); }

    /**
     * True when this entry created the branch, so there is no earlier state behind it.
     *
     * <p>Git writes a zero id for the first move; restoring to it would mean pointing the
     * branch at nothing.
     */
    public boolean isBranchCreation() {
        return oldHash == null || ZERO.equals(oldHash);
    }

    /**
     * The kind of move, taken from the start of Git's own comment.
     *
     * <p>Comments look like "rebase (finish): returning to refs/heads/main" or
     * "commit: add a thing"; the part before the colon is the operation.
     */
    public String operation() {
        if (comment == null || comment.isBlank()) {
            return "move";
        }
        int colon = comment.indexOf(':');
        return colon > 0 ? comment.substring(0, colon) : comment;
    }

    /** Whether this entry rewrote history rather than adding to it. */
    public boolean isHistoryRewrite() {
        String operation = operation().toLowerCase(java.util.Locale.ROOT);
        return operation.startsWith("rebase")
                || operation.startsWith("reset")
                || operation.contains("amend")
                || operation.startsWith("filter");
    }

    private static String shorten(String hash) {
        if (hash == null || ZERO.equals(hash)) return "-";
        return hash.length() >= 7 ? hash.substring(0, 7) : hash;
    }
}
