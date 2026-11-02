package com.jfx.git.model;

import java.util.List;

/**
 * What a bisect session has to say right now.
 *
 * @param currentCommitHash    the commit to test, or the culprit once {@code done} - null
 *                             when the session finished without a single culprit (see
 *                             {@code suspects})
 * @param currentCommitSubject that commit's subject line, for display
 * @param revisionsRemaining   an estimate of remaining candidates, not a promise - the
 *                             real step count depends on what the next marks turn out
 *                             to be
 * @param done                 the range has narrowed to nothing left to test
 * @param message              what to tell the user right now: waiting for the other
 *                             endpoint, "N revisions left", or the final "commit X is
 *                             the first bad commit" - callers show this rather than
 *                             composing their own from the other fields
 * @param suspects             when only skipped commits are left, every commit the first
 *                             bad one could be - git's "could be any of" - and otherwise
 *                             empty. A skipped commit was never tested, so naming the bad
 *                             end as <em>the</em> answer would be a guess presented as a
 *                             result.
 */
public record BisectStatus(boolean active, String currentCommitHash, String currentCommitSubject,
                           int revisionsRemaining, boolean done, String message,
                           List<String> suspects) {

    public BisectStatus {
        suspects = suspects == null ? List.of() : List.copyOf(suspects);
    }

    public BisectStatus(boolean active, String currentCommitHash, String currentCommitSubject,
                        int revisionsRemaining, boolean done, String message) {
        this(active, currentCommitHash, currentCommitSubject, revisionsRemaining, done, message, List.of());
    }

    public static BisectStatus inactive() {
        return new BisectStatus(false, null, null, 0, false, "No bisect session is running.");
    }

    /** Finished, but with several commits that could each be the first bad one. */
    public boolean ambiguous() {
        return done && suspects.size() > 1;
    }
}
