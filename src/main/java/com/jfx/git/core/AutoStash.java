package com.jfx.git.core;

import com.jfx.git.model.StagingState;
import com.jfx.git.model.StashEntry;
import com.jfx.git.model.StashOptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Auto-stash: stashing local changes around an operation they would block, and putting
 * them back afterwards - what a checkout, a pull or a merge refused for local changes
 * offers instead of only the refusal, and what Git Flow's finish does up front.
 *
 * <h2>Why</h2>
 * "Your local changes would be overwritten" is git being careful, and the way through it
 * is always the same three steps - stash, do it again, pop. Every client that leaves the
 * user to do them by hand leaves a stash behind now and then that nobody pops. So the
 * refusal comes back as a question, and the three steps happen together or not at all.
 * For a merge this is git's own {@code merge --autostash} and IntelliJ's "smart merge".
 *
 * <h2>What it does, exactly</h2>
 * <ol>
 *   <li>The operation runs as it always did. Anything but a refusal for local changes
 *       ({@link Refusal.LocalChanges}) is handed straight back.</li>
 *   <li>The caller's {@link Asker} is asked; no means the refusal is handed back, and
 *       nothing else has happened.</li>
 *   <li>The changes are stashed, untracked files with them only when the refusal is about
 *       those, so a stash never takes more than it has to.</li>
 *   <li>The operation runs again, and the stash is popped. An untracked file the stash
 *       took whose name the operation has since brought in as a tracked file is put back
 *       over it: the operation's version is in the commit, and the user's own bytes are
 *       back in the work tree, as a change to it. A pop that conflicts leaves the stash
 *       where it is, and says so: nothing is lost, and nothing is dropped behind the
 *       user's back.</li>
 *   <li>A merge (or a pull) that itself conflicts once the changes are out of the way
 *       stops there, with the changes still in the stash: popping them onto a
 *       half-finished merge would mix the two. The result says so, and so does the
 *       conflict banner ({@link #stashKeptAt}).</li>
 * </ol>
 * Git Flow's finish checks out and merges several times in a row, so it stashes before it
 * starts rather than on a refusal part-way ({@link #stashAround}); {@link GitFlow#finish}
 * does that itself.
 *
 * <h2>Threads</h2>
 * Every git step runs on the repository's lane ({@link GitTaskRunner}); only the question
 * is asked on the FX thread, between the refused attempt and the stash, with the lane free
 * for anything else meanwhile. What the question looks like - a dialog, an "Always do
 * this" preference - is the caller's {@link Asker}: nothing here knows of either.
 */
public final class AutoStash {

    /** What was refused, in the words the question and the results use. */
    public enum Operation {
        CHECKOUT("check out", "checkout", "Stash and check out"),
        PULL("pull", "pull", "Stash and pull"),
        MERGE("merge", "merge", "Stash and merge"),
        /**
         * Git Flow's finish: checkouts and merges in a row, stashed up front rather than
         * on a refusal - see {@link #stashAround}. What it brings in, it merges.
         */
        FINISH("finish", "merge", "Stash and finish");

        private final String verb;
        private final String noun;
        private final String action;

        Operation(String verb, String noun, String action) {
            this.verb = verb;
            this.noun = noun;
            this.action = action;
        }

        /** "check out", "pull", "merge". */
        public String verb() {
            return verb;
        }

        /** "checkout", "pull", "merge": the operation as a thing that happened. */
        public String noun() {
            return noun;
        }

        /** The question's button. */
        public String action() {
            return action;
        }
    }

    /**
     * Whether to auto-stash an operation refused for local changes: asked on the FX thread,
     * once the refused attempt has finished on the lane and before anything is stashed.
     */
    @FunctionalInterface
    public interface Asker {
        /**
         * @param stash takes the answer: {@code true} to stash, run the operation again and
         *              put the changes back, {@code false} to leave the refusal as it is.
         *              Answered once, on the FX thread - straight away or later; a second
         *              answer is ignored.
         */
        void ask(Operation operation, Consumer<Boolean> stash);
    }

    /** Says what a pop that conflicted left behind. */
    static final String POP_CONFLICTED =
            "Re-applying your changes conflicted; your changes are kept in the stash.";

    /**
     * How every stash this makes is described, followed by the operation's verb: what
     * {@link #isAutoStash} recognises one by, in the stash list as in the conflict banner.
     * The words are the user's, which is why they are not "auto-stash".
     */
    private static final String STASH_MESSAGE = "Changes set aside to ";

    private AutoStash() {
    }

    /**
     * Runs {@code work} on the repository lane, and hands {@code done} what came of it on
     * the FX thread - after asking {@code asker}, and on a yes stashing, running {@code work}
     * again and putting the changes back, when local changes were in the way.
     *
     * @param work the checkout, pull or merge; run a second time when the answer is yes, so
     *             it must read what it acts on when it runs, not before
     * @return the first attempt's task
     */
    public static GitTaskRunner.Task run(GitBackend git, Operation operation, Asker asker,
                                         Supplier<GitResult> work, Consumer<GitResult> done) {
        GitTaskRunner.Task[] first = new GitTaskRunner.Task[1];
        run(git, (step, then) -> {
            GitTaskRunner.Task task = GitTaskRunner.run(git, step, then);
            if (first[0] == null) first[0] = task;
        }, operation, asker, work, done);
        return first[0];
    }

    /**
     * Where {@link #run(GitBackend, Lane, Operation, Asker, Supplier, Consumer)} runs its
     * git steps: the repository's lane, with {@code then} called on the FX thread - as
     * {@link GitTaskRunner#run} does, or as an operation runner that watches each step does.
     */
    @FunctionalInterface
    public interface Lane {
        void run(Supplier<GitResult> step, Consumer<GitResult> then);
    }

    /**
     * {@link #run(GitBackend, Operation, Asker, Supplier, Consumer)}, with each git step -
     * the first attempt, and on a yes the stash, the retry and the put-back - run through
     * {@code lane}.
     */
    public static void run(GitBackend git, Lane lane, Operation operation, Asker asker,
                           Supplier<GitResult> work, Consumer<GitResult> done) {
        lane.run(work, first -> {
            if (!(first.getRefusal() instanceof Refusal.LocalChanges)) {
                done.accept(first);
                return;
            }
            AtomicBoolean answered = new AtomicBoolean();
            asker.ask(operation, stash -> {
                if (!answered.compareAndSet(false, true)) return;
                if (Boolean.TRUE.equals(stash)) {
                    lane.run(() -> stashAndRetry(git, operation, work, first), done);
                } else {
                    done.accept(first);
                }
            });
        });
    }

    /** What an operation that conflicted after the stash says about the changes it stashed. */
    static String keptInStash(Operation operation) {
        // A finish that stopped is resolved, committed and then finished again, so "once
        // the merge is finished" would be one step early.
        String when = operation == Operation.FINISH ? "finishing is done" : "the " + operation.noun() + " is finished";
        return "Your own changes are kept in the stash; pop it once " + when + ".";
    }

    /** Whether {@code entry} is a stash this class made. */
    static boolean isAutoStash(StashEntry entry) {
        return entry != null && entry.message() != null && entry.message().contains(STASH_MESSAGE);
    }

    /**
     * The stash this class kept for an operation that stopped on a conflict, or
     * {@code null}: the newest of its stashes taken from {@code head}, the commit a merge
     * stays on until it is committed. Read for the conflict banner.
     */
    public static StashEntry stashKeptAt(List<StashEntry> entries, String head) {
        if (entries == null || head == null) return null;
        for (StashEntry entry : entries) {
            if (isAutoStash(entry) && head.equals(entry.parentHash())) return entry;
        }
        return null;
    }

    /**
     * Whether there is anything for {@link #stashAround} to stash: staged changes, edits to
     * tracked files, or untracked files. On the repository lane.
     */
    static boolean hasChangesToStash(GitBackend git) {
        StagingState state = git.getStagingState();
        List<String> untracked = untrackedFiles(state);
        return !untracked.isEmpty() || hasTrackedChanges(state, untracked);
    }

    /**
     * Stashes, runs {@code work} again and pops - on the calling thread, which must be the
     * repository's lane.
     *
     * @param refusal what the first attempt said, which decides whether untracked files
     *                go into the stash
     */
    static GitResult stashAndRetry(GitBackend git, Operation operation, Supplier<GitResult> work,
                                   GitResult refusal) {
        StagingState state = git.getStagingState();
        List<String> untracked = untrackedFiles(state);
        boolean withUntracked = refusal != null
                && refusal.getRefusal() instanceof Refusal.LocalChanges changes && changes.untracked();
        // With only untracked files to stash and a refusal that does not name them, a
        // stash without them has nothing to save - which both engines answer as a
        // failure - so go straight to the retry below that takes them.
        Stashed stashed = withUntracked || hasTrackedChanges(state, untracked)
                ? stash(git, operation, withUntracked)
                : Stashed.NOTHING;
        if (stashed.failure() != null) {
            return GitResult.failed("Could not stash your changes: " + stashed.failure().getMessage());
        }
        String stash = stashed.stash();
        GitResult retried = work.get();
        // JGit's merger stops at the first path in the way, so its refusal can name an edit
        // and not the untracked file behind it: stashed without that file, the retry is
        // refused over it - after the user said yes. So once: put the changes back, stash
        // them with the untracked files as well, and try again.
        if (!withUntracked && !untracked.isEmpty() && retried.getRefusal() instanceof Refusal.LocalChanges) {
            if (stash != null && !new RepositoryCommands(git).stashPop(stash).isOk()) {
                return GitResult.failed(retried.getMessage() + " Your changes are kept in the stash.");
            }
            withUntracked = true;
            stashed = stash(git, operation, true);
            if (stashed.failure() != null) {
                return GitResult.failed("Could not stash your changes: " + stashed.failure().getMessage());
            }
            stash = stashed.stash();
            retried = work.get();
        }
        return putBack(git, operation, stash, retried, withUntracked, untracked);
    }

    /**
     * Stashes first, untracked files included, then runs {@code work} and puts the changes
     * back - for an operation that checks out and merges several times in a row, where a
     * refusal part-way would leave it half done and a retry would repeat what had worked:
     * Git Flow's finish. Asks nothing; the caller says beforehand that it will happen. On
     * the calling thread, which must be the repository's lane.
     */
    static GitResult stashAround(GitBackend git, Operation operation, Supplier<GitResult> work) {
        StagingState state = git.getStagingState();
        List<String> untracked = untrackedFiles(state);
        if (untracked.isEmpty() && !hasTrackedChanges(state, untracked)) {
            return work.get();   // nothing to stash
        }
        Stashed stashed = stash(git, operation, true);
        if (stashed.failure() != null) {
            return GitResult.failed("Could not stash your changes: " + stashed.failure().getMessage());
        }
        return putBack(git, operation, stashed.stash(), work.get(), true, untracked);
    }

    /**
     * After {@code work} ran with the changes in {@code stash} out of the way: pops them
     * back, or keeps them where popping would mix them into a stopped operation, and words
     * the result. A {@code null} stash means nothing was stashed. A retry that failed keeps
     * its refusal: a pull request that diverged meanwhile is still a question to ask.
     */
    private static GitResult putBack(GitBackend git, Operation operation, String stash, GitResult retried,
                                     boolean withUntracked, List<String> untracked) {
        if (stash == null) {
            return retried;
        }
        if (retried.getStatus() == GitResult.Status.CONFLICT) {
            // Popping onto a half-finished merge would mix the two up; the stash waits.
            return GitResult.conflict(retried.getMessage() + ". " + keptInStash(operation),
                    retried.getConflicts());
        }
        List<String> replaced = retried.isOk() && withUntracked ? makeRoomFor(git, untracked) : List.of();
        GitResult popped = new RepositoryCommands(git).stashPop(stash);
        if (!popped.isOk()) {
            restoreReplaced(git, replaced);
        }
        if (!retried.isOk()) {
            return GitResult.refused(retried.getMessage() + (popped.isOk()
                    ? " Your changes were put back."
                    : " Your changes are kept in the stash."), retried.getRefusal());
        }
        if (!popped.isOk()) {
            return GitResult.conflict(POP_CONFLICTED, popped.getConflicts());
        }
        String message = retried.getMessage() + ", and your changes were re-applied";
        if (!replaced.isEmpty()) {
            message += ": your own " + String.join(", ", replaced) + (replaced.size() == 1 ? " is" : " are")
                    + " back in place of the " + operation.noun() + "'s version, which is in the commit";
        }
        return GitResult.ok(message);
    }

    /** Staged changes, or unstaged changes to tracked files: what a plain stash would take. */
    private static boolean hasTrackedChanges(StagingState state, List<String> untracked) {
        if (state == null) return true;   // unknown: let the stash decide
        if (!state.staged().isEmpty()) return true;
        for (String path : state.unstaged()) {
            if (!untracked.contains(path)) return true;
        }
        return false;
    }

    /**
     * What {@link #stash} did: the commit of the stash it made - {@code null} when it made
     * none - or, when the stash failed, what the stash said.
     */
    private record Stashed(String stash, GitResult failure) {
        static final Stashed NOTHING = new Stashed(null, null);
    }

    /**
     * Stashes for {@code operation}, answering with the new stash's commit - or none when
     * no stash was made. Only a stash this call made counts: were an engine ever to answer
     * "nothing to save" as a success, the newest entry would be one the user made, and the
     * pop afterwards would apply and drop it.
     */
    private static Stashed stash(GitBackend git, Operation operation, boolean withUntracked) {
        List<StashEntry> before = git.getStashEntries();
        String previous = before.isEmpty() ? null : before.get(0).hash();
        GitResult stashed = git.stash(new StashOptions(STASH_MESSAGE + operation.verb(),
                withUntracked, false, List.of()));
        if (!stashed.isOk()) {
            return new Stashed(null, stashed);
        }
        List<StashEntry> after = git.getStashEntries();
        String newest = after.isEmpty() ? null : after.get(0).hash();
        return new Stashed(newest == null || newest.equals(previous) ? null : newest, null);
    }

    /**
     * Clears the way for the stash's untracked files that the operation has since brought
     * in under the same name, so the pop can put the user's own back: git restores a
     * stashed untracked file only where the work tree has nothing.
     *
     * <p>Only a file the operation wrote is removed - a tracked file with no change in the
     * work tree, so its content is in the commit - and only a plain file. Anything else is
     * left in place, and the pop then refuses and keeps the stash, as it would have.
     *
     * @param untracked the untracked files before the stash, which took them all
     * @return the paths removed, to say so or to restore if the pop fails
     */
    private static List<String> makeRoomFor(GitBackend git, List<String> untracked) {
        List<String> removed = new ArrayList<>();
        File workTree = git.getWorkTree();
        if (workTree == null) return removed;
        StagingState now = git.getStagingState();
        for (String path : untracked) {
            Path file = workTree.toPath().resolve(path);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
            if (now != null && (now.unstaged().contains(path) || now.staged().contains(path))) continue;
            try {
                // In the index, or empty: tracked, or with nothing in it to lose.
                if (git.getStagedBytes(path).length == 0 && Files.size(file) > 0) continue;
                Files.delete(file);
                removed.add(path);
            } catch (IOException e) {
                // Still there: the pop will refuse it, and keep the stash.
            }
        }
        return removed;
    }

    /** After a pop that failed: the operation's version back where the pop left nothing. */
    private static void restoreReplaced(GitBackend git, List<String> removed) {
        File workTree = git.getWorkTree();
        if (removed.isEmpty() || workTree == null) return;
        String head = git.getHeadHash();
        for (String path : removed) {
            if (!Files.exists(workTree.toPath().resolve(path), LinkOption.NOFOLLOW_LINKS)) {
                git.restoreFileFromCommit(head, path);
            }
        }
    }

    /** Untracked files: unstaged and never added, which the status reports as {@code A}. */
    private static List<String> untrackedFiles(StagingState state) {
        List<String> untracked = new ArrayList<>();
        if (state == null) return untracked;
        for (String path : state.unstaged()) {
            if (state.status().getOrDefault(path, 'M') == 'A' && !state.staged().contains(path)) {
                untracked.add(path);
            }
        }
        return untracked;
    }
}
