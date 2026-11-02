package com.jfx.git.model;

import java.util.List;

/**
 * What a stash takes and what it leaves behind: {@code git stash push} with or without
 * {@code -u}, {@code --keep-index}, {@code -m} and a list of paths.
 *
 * <p>The toolbar button and the plain {@code stash(String)} call are
 * {@link #everything(String)} - every change, untracked files included - which is what the
 * application has always done and what {@code BackendConformanceTest} pins.
 *
 * @param message          the stash's description; blank or null keeps git's
 *                         {@code WIP on <branch>: ...}
 * @param includeUntracked {@code -u}: new files that were never added go into the stash
 *                         and leave the working tree
 * @param keepIndex        {@code --keep-index}: what is staged stays staged, and in the
 *                         working tree, while still being saved in the stash
 * @param paths            repository-relative paths to stash, and nothing else; empty
 *                         for the whole tree. Taken literally - never as a glob
 */
public record StashOptions(String message, boolean includeUntracked, boolean keepIndex,
                           List<String> paths) {

    public StashOptions {
        paths = paths == null ? List.of() : List.copyOf(paths);
    }

    /** Everything, untracked files included: the stash the toolbar button makes. */
    public static StashOptions everything(String message) {
        return new StashOptions(message, true, false, List.of());
    }

    /** Whether the user gave this stash a description of their own. */
    public boolean hasMessage() {
        return message != null && !message.isBlank();
    }

    /** The description as it should be stored, or {@code null} for git's default. */
    public String strippedMessage() {
        return hasMessage() ? message.strip() : null;
    }

    /** Whether only some paths are being stashed. */
    public boolean isPartial() {
        return !paths.isEmpty();
    }

    /**
     * Why these options cannot be carried out, or {@code null} when they can.
     *
     * <p>{@code --keep-index} together with paths is refused on every engine, and not
     * because it is hard to express. git itself gets it wrong: with an untracked file
     * among the paths, {@code stash push --keep-index -u -- <paths>} saves the stash,
     * clears the files, then fails restoring the index ("pathspec did not match") -
     * leaving what was staged for the selected files unstaged and a non-zero exit that
     * says the stash did not happen when it did. An option whose result depends on which
     * of the selected files happen to be new is not one to offer; each of the two works
     * on its own.
     */
    public String problem() {
        if (keepIndex && isPartial()) {
            return "Keeping staged changes only works for a stash of everything. Stash the "
                    + "selected files without it, or stash everything and keep what is staged.";
        }
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                return "A blank path cannot be stashed";
            }
        }
        return null;
    }
}
