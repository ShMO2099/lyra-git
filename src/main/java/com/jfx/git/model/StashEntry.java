package com.jfx.git.model;

import java.time.Instant;

/**
 * One entry in the stash, with enough identity to draw it on the graph.
 *
 * <p>A stash is a commit — it has a hash, a parent and a tree — but it is not reachable
 * from any branch, so a history walk never produces one. Modelling it separately lets the
 * graph place it against the commit it was taken from without pretending it is part of
 * the branch's history.
 *
 * @param index      position in {@code stash@{n}}; 0 is the most recent
 * @param hash       full SHA-1 of the stash commit
 * @param parentHash the commit the stash was taken from, which is where it belongs on the graph
 * @param message    git's description, e.g. {@code "WIP on main: 1a2b3c4 fix the parser"}
 */
public record StashEntry(int index,
                         String hash,
                         String parentHash,
                         String message,
                         String authorName,
                         Instant when) {

    /** {@code stash@{2}} — the notation git itself uses. */
    public String selector() {
        return "stash@{" + index + "}";
    }

    public String shortHash() {
        return hash != null && hash.length() >= 7 ? hash.substring(0, 7) : String.valueOf(hash);
    }

    /**
     * The part of the message worth showing on a graph row.
     *
     * <p>Git's own text repeats the branch and the commit it was taken from, both of
     * which the graph is already showing by placing the node.
     */
    public String shortDescription() {
        if (message == null || message.isBlank()) return selector();
        int colon = message.indexOf(':');
        if (colon < 0 || colon + 1 >= message.length()) return message;

        String rest = message.substring(colon + 1).trim();
        // "WIP on main: 1a2b3c4 subject" — drop the abbreviated hash git prepends.
        int space = rest.indexOf(' ');
        if (space > 0 && rest.substring(0, space).matches("[0-9a-f]{4,40}")) {
            return rest.substring(space + 1).trim();
        }
        return rest;
    }
}
