package com.jfx.git.model;

import java.time.Instant;

/**
 * A copy of the whole working tree, kept by the application under
 * {@code refs/lyra/snapshots/} so work thrown away by a discard or a hard reset can be
 * brought back.
 *
 * <p>A commit whose tree is the working tree as it was - tracked and untracked files,
 * ignored ones left out - and whose parent is the commit HEAD was on. Unlike a stash,
 * taking one changes nothing: not the index, not the files, not the stash list.
 *
 * @param id        the snapshot's name under the snapshot refs: the time it was taken, in
 *                  epoch milliseconds
 * @param hash      the snapshot commit
 * @param time      when it was taken
 * @param reason    why, in the user's terms - "Every 10 minutes", "Before discarding a.txt"
 * @param branch    the branch checked out at the time, or {@code HEAD} when detached
 * @param fileCount how many files differed from that commit
 */
public record Snapshot(String id, String hash, Instant time, String reason, String branch,
                       int fileCount) {

    public String shortHash() {
        return hash != null && hash.length() >= 7 ? hash.substring(0, 7) : String.valueOf(hash);
    }
}
