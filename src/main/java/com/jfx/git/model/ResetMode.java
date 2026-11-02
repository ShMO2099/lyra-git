package com.jfx.git.model;

/**
 * Backend-agnostic reset mode.
 *
 * <p>Replaces {@code org.eclipse.jgit.api.ResetCommand.ResetType} so callers and the
 * UI layer have no JGit dependency just to express which kind of reset they want.
 */
public enum ResetMode {
    /** Move HEAD only; leave the index and working tree untouched. */
    SOFT,
    /** Move HEAD and reset the index; leave the working tree untouched (default). */
    MIXED,
    /** Move HEAD, reset the index, and reset the working tree. Discards local changes. */
    HARD;
}
