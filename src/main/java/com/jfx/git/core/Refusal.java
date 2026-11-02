package com.jfx.git.core;

import java.util.List;

/**
 * Git declining an operation for a reason the app can act on, carried beside a failed
 * {@link GitResult}'s status and message - see {@link GitResult#getRefusal()}.
 *
 * <h2>Why typed</h2>
 * The UI used to recognise each of these by matching words in the message: "would be
 * overwritten" to offer a stash, "not fully merged" to offer a force delete, "already
 * exists" to put an error under the right field of a form. Each match had to know both
 * engines' wording, and a new phrasing in either - or a message that happened to contain
 * the words - silently changed what the user was offered. Now the engine part that holds
 * git's text or JGit's exception sets the refusal, and callers ask what kind it is.
 *
 * <p>The message stays the whole story for a person: a refusal says what kind of "no" it
 * was, never how to word it. A failure that is not one of these has no refusal at all.
 * Why it is not a set of new statuses is in {@code docs/adr/0001-refusals-beside-status.md}.
 */
public sealed interface Refusal {

    /**
     * Local changes in the way: a checkout, merge, pull, cherry-pick, revert, rebase or
     * stash apply that would overwrite them, which a stash, the operation again and a pop
     * of the stash turn into a success.
     *
     * @param paths     the files git or JGit named, when it named any - git's rebasing
     *                  pull, for one, only says that there are changes
     * @param untracked whether files that were never added are among those in the way, so
     *                  a stash has to take untracked files too
     */
    record LocalChanges(List<String> paths, boolean untracked) implements Refusal {
        public LocalChanges {
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }

    /** A branch delete refused because the branch has commits no other branch has. */
    record NotFullyMerged(String branch) implements Refusal {
    }

    /**
     * A checkout of a pull request, or of another fetched ref, refused because the local
     * branch it would update has commits of its own and the fetched one has moved on
     * without them - see {@link DivergedCheckout}.
     *
     * @param branch       the local branch, {@code pr-7}
     * @param localCommits how many commits the local branch has that the fetched one does not
     */
    record Diverged(String branch, int localCommits) implements Refusal {
    }

    /**
     * A submodule deinit or a worktree removal refused because the working copy it would
     * delete has changes that are not committed, which forcing it would lose for good.
     *
     * @param path the submodule's or worktree's path, as the caller gave it
     */
    record LocalModifications(String path) implements Refusal {
    }

    /**
     * An input that names something wrong - a URL that is no repository, a folder that is
     * already taken, a branch that does not exist - and which form field it belongs to,
     * so the form can say so under that field.
     */
    record BadInput(Field field) implements Refusal {
    }

    /** The fields of the clone, add-submodule and add-worktree forms a {@link BadInput} can be about. */
    enum Field {
        /** The repository to clone or add: its URL or path. */
        URL,
        /** The folder a submodule or worktree goes in, inside or beside this repository. */
        PATH,
        /** The branch to check out or create. */
        BRANCH,
        /** The folder a clone goes in. */
        TARGET
    }
}
