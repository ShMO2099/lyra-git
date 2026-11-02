package com.jfx.git.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How an engine recognises a {@link Refusal} where words are all it has: git's stderr on
 * the CLI engine, and the text of an exception JGit throws without a type to tell it by.
 *
 * <p>Read only by the engine parts that hold those words, at the point they build the
 * failed result - never by a caller afterwards, which asks {@link GitResult#getRefusal()}.
 * git always speaks English to this application ({@link NativeGitRunner} forces its
 * message locale), so the phrases are git's own, and JGit's where it has one.
 *
 * <p>The field phrases used to be three classifiers in the UI - the clone form's, the
 * add-submodule form's and the add-worktree form's - each reading whichever engine's text
 * it was handed. They are the same lists, moved to where the text is in hand.
 */
public final class RefusalWords {

    private RefusalWords() {
    }

    // ------------------------------------------------------------------
    // Local changes in the way
    // ------------------------------------------------------------------

    /**
     * Local changes in the way, as git words them - the paths it lists, tab-indented,
     * under each "would be overwritten" heading, untracked when a heading says so - or
     * {@code null} when {@code text} is no such refusal.
     *
     * @see LocalChangesRefusal for the phrases, which name both engines' wordings
     */
    static Refusal.LocalChanges localChanges(String text) {
        if (!LocalChangesRefusal.matches(text)) return null;
        List<String> paths = new ArrayList<>();
        boolean listing = false;
        for (String line : text.split("\\R")) {
            if (lower(line).contains("would be overwritten")) {
                listing = true;
            } else if (listing && line.startsWith("\t")) {
                String path = line.strip();
                if (!path.isEmpty() && !paths.contains(path)) paths.add(path);
            } else {
                listing = false;
            }
        }
        return new Refusal.LocalChanges(paths, lower(text).contains("untracked working tree file"));
    }

    /**
     * git's {@code stderr} as a failed result, carrying {@link Refusal.LocalChanges} when
     * that is what it says: for the commands that can be refused for local changes -
     * checkouts, merges, pulls, picks, reverts, rebases, resets, stash applies.
     */
    static GitResult failure(String stderr) {
        return GitResult.refused(stderr, localChanges(stderr));
    }

    // ------------------------------------------------------------------
    // Branches and working copies
    // ------------------------------------------------------------------

    /** Whether git refused to delete a branch with commits nothing else has: "is not fully merged". */
    static boolean notFullyMerged(String text) {
        return lower(text).contains("not fully merged");
    }

    /**
     * Whether a working copy - a submodule's, or a linked worktree's - was kept because it
     * has changes that are not committed: git says "contains local modifications" or
     * "contains modified or untracked files", JGit's deinit "has uncommitted changes".
     */
    static boolean localModifications(String text) {
        String said = lower(text);
        return said.contains("local modifications") || said.contains("uncommitted changes")
                || said.contains("modified or untracked");
    }

    /**
     * {@code message} as a failed result, carrying {@link Refusal.LocalModifications} for
     * {@code path} when {@link #localModifications} says so.
     */
    static GitResult workingCopyFailure(String message, String path) {
        return GitResult.refused(message,
                localModifications(message) ? new Refusal.LocalModifications(path) : null);
    }

    // ------------------------------------------------------------------
    // Bad input, by form field
    // ------------------------------------------------------------------

    /**
     * Which field of the clone form a failed clone's {@code text} is about: the branch
     * that does not exist, the folder that is taken, or the URL that is no repository;
     * {@code null} for none of them, such as a failed sign-in.
     */
    public static Refusal.Field cloneField(String text) {
        String said = lower(text);
        if (said.contains("remote branch") && said.contains("not found")) {
            return Refusal.Field.BRANCH;
        }
        if (said.contains("already exists and is not an empty") || said.contains("could not create")) {
            return Refusal.Field.TARGET;
        }
        if (namesAMissingRepository(said)) {
            return Refusal.Field.URL;
        }
        return null;
    }

    /**
     * Which field of the add-submodule form a failed add's {@code text} is about: the
     * folder ({@link Refusal.Field#PATH}) or the URL; {@code null} for neither.
     */
    public static Refusal.Field submoduleField(String text) {
        String said = lower(text);
        if (said.contains("already exists") || said.contains("is not a valid git repo")
                || said.contains("outside repository") || said.contains("beyond a symbolic link")) {
            return Refusal.Field.PATH;
        }
        if (namesAMissingRepository(said) || said.contains("not allowed")) {
            return Refusal.Field.URL;
        }
        return null;
    }

    /**
     * Which field of the add-worktree form a failed add's {@code text} is about: the
     * branch or the folder ({@link Refusal.Field#PATH}); {@code null} for neither.
     */
    public static Refusal.Field worktreeField(String text) {
        String said = lower(text);
        if (said.contains("a branch named") || said.contains("already checked out")
                || said.contains("already used by worktree") || said.contains("invalid reference")
                || said.contains("not a valid branch name") || said.contains("branch name is required")) {
            return Refusal.Field.BRANCH;
        }
        if (said.contains("already exists") || said.contains("could not create")
                || said.contains("is a missing but") || said.contains("already registered")) {
            return Refusal.Field.PATH;
        }
        return null;
    }

    /** {@code message} as a failed result, carrying {@link Refusal.BadInput} when {@code field} names one. */
    static GitResult inputFailure(String message, Refusal.Field field) {
        return GitResult.refused(message, field == null ? null : new Refusal.BadInput(field));
    }

    /** A URL or path that names no repository git or JGit could reach. */
    private static boolean namesAMissingRepository(String said) {
        return said.contains("not found") || said.contains("does not appear to be a git repository")
                || said.contains("does not exist") || said.contains("could not resolve host")
                || said.contains("unknown host") || said.contains("invalid remote")
                || said.contains("not supported") || said.contains("remote helper");
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
