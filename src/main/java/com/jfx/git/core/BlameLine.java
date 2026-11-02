package com.jfx.git.core;

import java.time.Instant;

/**
 * One annotated line of a file: the text plus the commit that last touched it.
 *
 * <p>The author date is an instant, not text. It used to be formatted by each engine,
 * and they disagreed - {@code yyyy-MM-dd} from the native one, {@code dd/MM/yyyy} from
 * JGit - so the blame column changed format with the engine. Writing it is the view's
 * job, in the format the user chose.
 *
 * @param hash    abbreviated commit id, empty when the line is uncommitted
 * @param author  author name of that commit
 * @param message short commit message
 * @param when    the commit's author date, to the second; {@code null} when the line is
 *                uncommitted or the commit carries no date
 * @param text    the line content itself
 */
public record BlameLine(String hash, String author, String message, Instant when, String text) {

    /** Placeholder for a line that has no commit yet. */
    public static BlameLine uncommitted(String text) {
        return new BlameLine("", "Uncommitted", "Uncommitted changes", null, text);
    }
}
