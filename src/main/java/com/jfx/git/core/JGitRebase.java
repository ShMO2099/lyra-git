package com.jfx.git.core;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RebaseEligibility;
import com.jfx.git.model.RebaseRange;
import org.eclipse.jgit.api.RebaseResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Rebasing - plain, interactive from a plan, continuing, skipping and aborting - and the
 * range a rebase from a commit would replay, for {@link GitService}.
 */
final class JGitRebase extends JGitPart {

    JGitRebase(JGitRepo ctx) {
        super(ctx);
    }

    /**
     * A non-interactive rebase that did not finish, by what actually happened.
     *
     * <p>Every unsuccessful status used to come back as a conflict, so a rebase that never
     * started - the tree had uncommitted changes, or replaying would have overwritten
     * local edits - put the conflict banner up over a repository with nothing to resolve
     * and nothing in progress, and "Abort" then had nothing to abort.
     *
     * @param what the operation, as git names it in its refusals: "pull with rebase", "rebase"
     */
    GitResult describeUnfinishedRebase(RebaseResult result, String what) {
        String operation = Character.toUpperCase(what.charAt(0)) + what.substring(1);
        return switch (result.getStatus()) {
            // The CLI engine's words, so a stop reads the same on either.
            case STOPPED -> GitResult.conflict(operation + " stopped with conflicts", staging().getConflictingFiles());
            // CONFLICTS is also what JGit answers when checking out the upstream would
            // overwrite local changes - before anything started. Only a rebase that is
            // actually in progress has conflicts to resolve.
            case CONFLICTS -> staging().isMidOperation()
                    ? GitResult.conflict(operation + " stopped with conflicts", staging().getConflictingFiles())
                    : refusedOver(what, result.getConflicts());
            case EDIT -> GitResult.conflict(operation + " stopped at " + describeStoppedAt(result)
                    + " so you can amend it - continue when you are done", List.of());
            case NOTHING_TO_COMMIT -> GitResult.conflict("A commit became empty because its changes"
                    + " are already present. Skip it or abort the rebase.", staging().getConflictingFiles());
            // git's words: "cannot pull with rebase: You have unstaged changes."
            case UNCOMMITTED_CHANGES -> GitResult.refused("Cannot " + what + ": you have uncommitted"
                    + " changes" + JGitIntegration.inParentheses(result.getUncommittedChanges())
                    + ". Commit or stash them first.", localChangesIn(result.getUncommittedChanges()));
            case FAILED -> result.getFailingPaths() == null
                    ? GitResult.failed(operation + " failed, and was rolled back")
                    : refusedOver(what, result.getFailingPaths());
            case ABORTED -> GitResult.failed(operation + " was aborted; nothing was changed");
            case STASH_APPLY_CONFLICTS -> GitResult.conflict(operation + " finished, but the"
                    + " automatically stashed changes did not apply cleanly", staging().getConflictingFiles());
            default -> GitResult.failed(operation + " failed: " + result.getStatus());
        };
    }

    public GitResult rebase(String upstream) {
        if (git() == null) return GitResult.failed("No repository open");
        return ctx.asGit(() -> rebaseWithJGit(upstream), null);
    }

    private GitResult rebaseWithJGit(String upstream) {
        try {
            RebaseResult result = git().rebase().setUpstream(upstream).call();
            if (result.getStatus().isSuccessful()) {
                return GitResult.ok("Rebased onto " + upstream);
            }
            return describeUnfinishedRebase(result, "rebase");
        } catch (Exception e) {
            return failure("Rebase failed: ", e);
        }
    }

    /** Whether an interactive rebase can start from {@code startHash}; see {@link RebaseEligibility}. */
    public RebaseEligibility checkRebaseFrom(String startHash) {
        return RebaseEligibility.from(history().getCommitPosition(startHash));
    }

    /**
     * The commits from {@code startHash} up to HEAD, newest first.
     *
     * <p>{@code startHash} itself is included, and its parent becomes the base the
     * replay sits on - unless the commit is on another line of history, when the range
     * is this branch's commits rebased onto it.
     */
    public RebaseRange getRebaseRange(String startHash) {
        if (git() == null) return new RebaseRange(List.of(), null, "No repository open");

        RebaseEligibility eligibility = checkRebaseFrom(startHash);
        if (eligibility == RebaseEligibility.MERGE_COMMIT
                || eligibility == RebaseEligibility.ROOT_COMMIT
                || eligibility == RebaseEligibility.UNAVAILABLE) {
            return new RebaseRange(List.of(), null, switch (eligibility) {
                case MERGE_COMMIT -> "That is a merge commit, and an interactive rebase"
                        + " replays a linear history.";
                case ROOT_COMMIT -> "That is the repository's first commit, which an"
                        + " interactive rebase cannot replay onto anything.";
                default -> "Commit " + shorten(startHash) + " could not be read.";
            });
        }

        try (RevWalk walk = new RevWalk(repository());
             var patchIds = new org.eclipse.jgit.diff.PatchIdDiffFormatter()) {
            ObjectId chosenId = repository().resolve(startHash);
            ObjectId headId = repository().resolve(Constants.HEAD);
            if (chosenId == null || headId == null) {
                return new RebaseRange(List.of(), null, "Commit " + shorten(startHash) + " could not be read.");
            }
            RevCommit chosen = walk.parseCommit(chosenId);
            RevCommit head   = walk.parseCommit(headId);

            RevCommit baseCommit = eligibility == RebaseEligibility.ONTO
                    ? chosen : chosen.getParent(0);
            String baseHash = baseCommit.getName();

            // Moving the branch onto another line: a commit already applied there - the
            // same change under a different hash, cherry-picked or merged upstream - has
            // nothing left to replay. git's own rebase drops these ("skipped previously
            // applied commit"); an explicit plan bypasses that filter, and replaying one
            // produced an empty duplicate on JGit and a stop on the CLI.
            //
            // One formatter and one walk for every patch id, both sides: a fresh pair per
            // commit cost a new object reader and diff setup for each upstream commit.
            patchIds.setRepository(repository());
            java.util.Set<ObjectId> alreadyApplied = eligibility == RebaseEligibility.ONTO
                    ? patchIdsBetween(head, baseCommit, walk, patchIds)
                    : java.util.Set.of();

            List<CommitInfo> commits = new ArrayList<>();
            for (RevCommit rc : git().log().addRange(baseCommit, head).call()) {
                if (!alreadyApplied.isEmpty() && rc.getParentCount() == 1) {
                    ObjectId patchId = patchId(rc, walk, patchIds);
                    if (patchId != null && alreadyApplied.contains(patchId)) continue;
                }
                commits.add(JGitHistory.revCommitToCommitInfo(rc, List.of()));
            }
            if (commits.isEmpty()) {
                return new RebaseRange(List.of(), null, eligibility == RebaseEligibility.ONTO
                        ? "This branch has no commits that are not already on " + shorten(startHash) + "."
                        : "There is nothing to replay from that commit.");
            }
            return new RebaseRange(commits, baseHash, null);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not list commits from " + startHash, e);
            return new RebaseRange(List.of(), null, "Could not read the commits: " + describe(e));
        }
    }

    /**
     * Patch ids of the non-merge commits in {@code exclude..include} - the upstream side
     * of {@code git log --cherry-pick}.
     */
    private java.util.Set<ObjectId> patchIdsBetween(RevCommit exclude, RevCommit include, RevWalk walk,
                                                    org.eclipse.jgit.diff.PatchIdDiffFormatter formatter)
            throws Exception {
        java.util.Set<ObjectId> ids = new java.util.HashSet<>();
        for (RevCommit rc : git().log().addRange(exclude, include).call()) {
            if (rc.getParentCount() != 1) continue;
            ObjectId id = patchId(rc, walk, formatter);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    /**
     * git's patch id of a single-parent commit: the hash of its diff, line numbers ignored.
     *
     * <p>The formatter is shared: reading the id resets its digest, so each call starts
     * from nothing - including after a failure part-way through, which reads it too.
     */
    private static ObjectId patchId(RevCommit commit, RevWalk walk,
                                    org.eclipse.jgit.diff.PatchIdDiffFormatter formatter) {
        try {
            RevCommit parsed = walk.parseCommit(commit);
            RevCommit parent = walk.parseCommit(parsed.getParent(0));
            formatter.format(parent.getTree(), parsed.getTree());
            return formatter.getCalulatedPatchId();
        } catch (Exception e) {
            formatter.getCalulatedPatchId();
            LOGGER.log(Level.FINE, "Could not compute the patch id of " + commit.getName(), e);
            return null;
        }
    }

    /**
     * Runs an interactive rebase from a plan.
     *
     * <p>JGit owns the sequencing - replaying commits, stopping on a conflict, resuming -
     * so this supplies the todo list and translates the outcome. The plan is validated
     * first and refused outright if it cannot run, because a rebase that fails halfway
     * leaves the repository in a state the user then has to understand.
     *
     * <p>The upstream is the parent of the plan's oldest commit: everything from there
     * forward is replayed, and everything before it is untouched. This is also what a
     * genuine "rebase onto" plan uses (a commercial client's drag-a-branch-onto-another, or
     * "Rebase current onto this") - {@code upstream} there is a commit on a different
     * line of history entirely rather than this range's own original parent, and JGit's
     * {@code RebaseCommand} replays onto it correctly either way (confirmed by reading
     * its own source: {@code initFilesAndRewind} checks out {@code upstreamCommit}
     * directly, with no separate merge-base detour, for both interactive and
     * non-interactive rebases).
     */
    public GitResult rebaseInteractive(RebaseTodo todo) {
        if (git() == null) return GitResult.failed("No repository open");
        return ctx.asGit(() -> rebaseInteractiveWithJGit(todo), null);
    }

    private GitResult rebaseInteractiveWithJGit(RebaseTodo todo) {

        // Both engines start from the same normalized plan, so a squash run's message
        // cannot mean one thing here and another on the CLI - see RebaseTodo.normalized().
        todo = todo.normalized();

        List<String> problems = todo.problems();
        if (!problems.isEmpty()) {
            return GitResult.failed(String.join(" ", problems));
        }
        if (todo.isNoop()) {
            return GitResult.ok("Nothing to do - every commit was left as it is");
        }

        // The range is fixed by where the plan started, not by whatever a reorder moved
        // to the front - otherwise moving a commit up would shrink the rebase to it.
        String baseHash = todo.base();
        if (baseHash == null) {
            return GitResult.failed("This range starts at the repository's first commit,"
                    + " which an interactive rebase cannot replay onto anything.");
        }
        ObjectId base;
        try {
            base = repository().resolve(baseHash);
            if (base == null) throw new IllegalStateException("Cannot resolve " + baseHash);
        } catch (Exception e) {
            return GitResult.failed("Could not resolve rebase base: " + describe(e));
        }

        try {
            if (staging().hasUncommittedChanges()) {
                return GitResult.failed("Commit or stash your changes before rebasing.");
            }
        } catch (GitOperationException e) {
            return GitResult.failed("Could not check the working tree: " + describe(e));
        }

        try {
            RebaseResult result = git().rebase()
                    .setUpstream(base)
                    .runInteractively(new TodoHandler(todo, repository().getDirectory()))
                    .call();
            return describeRebase(result, todo);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Interactive rebase failed", e);
            return failure("Rebase failed: ", e);
        }
    }

    /** Turns a rebase outcome into something that says what to do next. */
    private GitResult describeRebase(RebaseResult result, RebaseTodo todo) {
        RebaseResult.Status status = result.getStatus();
        if (status.isSuccessful()) {
            return GitResult.ok("Rebased: " + todo.summary()
                    + " (" + todo.resultingCommitCount() + " commits)");
        }
        return switch (status) {
            case STOPPED, CONFLICTS -> GitResult.conflict(
                    "Rebase stopped at " + describeStoppedAt(result)
                            + " - resolve the conflicts and continue, or abort to undo it all",
                    staging().getConflictingFiles());
            case EDIT -> GitResult.conflict(
                    "Rebase stopped at " + describeStoppedAt(result)
                            + " so you can amend it - continue when you are done",
                    staging().getConflictingFiles());
            case UNCOMMITTED_CHANGES -> GitResult.failed(
                    "Commit or stash your changes before rebasing.");
            case NOTHING_TO_COMMIT -> GitResult.conflict(
                    "A commit became empty because its changes are already present."
                            + " Skip it or abort the rebase.", staging().getConflictingFiles());
            default -> GitResult.failed("Rebase failed: " + status);
        };
    }

    private String describeStoppedAt(RebaseResult result) {
        RevCommit stopped = result.getCurrentCommit();
        if (stopped == null) {
            return "a commit";
        }
        return stopped.getName().substring(0, 7) + " \"" + stopped.getShortMessage() + "\"";
    }

    /**
     * Feeds the plan to JGit.
     *
     * <p>JGit hands over its own todo list and expects it edited in place. Dropping a
     * commit means removing its line rather than marking it, and reordering means
     * rebuilding the list in the plan's order - there is no "move" to call.
     *
     * <h2>Which message goes where</h2>
     * JGit asks for a message at exactly these points, read from its
     * {@code RebaseCommand.processStep} and {@code squashIntoPrevious}:
     * <ul>
     *   <li>once for every {@code reword} line, after the commit is picked;</li>
     *   <li>once at the <em>end</em> of every run of {@code squash}/{@code fixup} lines
     *       that contains at least one {@code squash} - never for a fixup-only run, which
     *       keeps the message of the commit it folds into;</li>
     *   <li>nowhere else - a {@code pick} with a message of its own is simply picked.</li>
     * </ul>
     * A queue in plan order, as this used to be, did not match that: a fixup-only run
     * consumed an entry JGit never asked for, and every later message landed on the wrong
     * commit ({@code [reword a "Ra", squash b, reword c "Rc"]} gave a+b "Rc" and left c
     * unchanged). Nor does the question say which commit it is about. So the reply is
     * looked up by commit instead: when JGit asks, the line it is processing is the last
     * one in {@code rebase-merge/done}, and the replies are keyed by that line's commit.
     * A {@code pick} carrying a message is turned into a {@code reword} so JGit asks for it.
     *
     * <p>The run boundaries ignore {@code drop} lines, because those are removed before
     * JGit sees the list - {@code squash} after a {@code drop} still folds into the step
     * above the drop - and because git itself treats {@code drop} as a no-op there.
     *
     * <h2>Keeping the text as written</h2>
     * The plain {@code InteractiveHandler} makes JGit clean every reply with
     * {@code CleanupMode.STRIP}, which deletes any line starting with {@code #} - "#123
     * root cause", "## Changes". This implements {@code InteractiveHandler2} and asks for
     * {@code WHITESPACE} cleanup, which is what {@code git commit -F} applies on the CLI
     * engine: trailing blanks go, {@code #} lines stay. JGit's own squash template is the
     * one reply still stripped, because its comment lines are template, not message.
     *
     * <h2>Surviving a stop</h2>
     * A rebase that stops (a conflict, an {@code edit}) is resumed by a new
     * {@code RebaseCommand}, which needs a handler again for the steps still to come. The
     * replies are saved in {@code rebase-merge/lyra-replies} when the plan is laid down,
     * and {@link #resume} reads them back.
     */
    static final class TodoHandler
            implements org.eclipse.jgit.api.RebaseCommand.InteractiveHandler2 {

        static final String REPLIES_FILE = "lyra-replies";

        /** {@link #REPLIES_FILE} as a build from before the rename left it mid-rebase. */
        static final String LEGACY_REPLIES_FILE = "jfxgit-replies";

        /** The plan, or {@code null} when resuming a rebase already laid down. */
        private final RebaseTodo todo;
        /** Full hash of the step JGit is asking about → the message to give it; null value = keep JGit's. */
        private final Map<String, String> replies;
        /** Picks that carry a message and have to become rewords for JGit to ask about them. */
        private final java.util.Set<String> picksToReword;
        /** Rewords with no new text, which are plain picks - see {@link #planReplies}. */
        private final java.util.Set<String> rewordsToPick;
        /** {@code .git/rebase-merge}, where {@code done} and the saved replies live. */
        private final File rebaseDir;

        TodoHandler(RebaseTodo todo, File gitDir) {
            this.todo = todo;
            this.rebaseDir = new File(gitDir, "rebase-merge");
            this.replies = new java.util.LinkedHashMap<>();
            this.picksToReword = new java.util.HashSet<>();
            this.rewordsToPick = new java.util.HashSet<>();
            planReplies(todo.steps());
        }

        private TodoHandler(Map<String, String> replies, File rebaseDir) {
            this.todo = null;
            this.rebaseDir = rebaseDir;
            this.replies = replies;
            this.picksToReword = java.util.Set.of();
            this.rewordsToPick = java.util.Set.of();
        }

        /** A handler for the remaining steps of a stopped rebase, from the saved replies. */
        static TodoHandler resume(File rebaseDir) {
            Map<String, String> saved = new java.util.LinkedHashMap<>();
            File file = new File(rebaseDir, REPLIES_FILE);
            if (!file.isFile()) file = new File(rebaseDir, LEGACY_REPLIES_FILE);
            if (file.isFile()) {
                try {
                    for (String line : java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                        int tab = line.indexOf('\t');
                        if (tab <= 0) continue;
                        String value = line.substring(tab + 1);
                        saved.put(line.substring(0, tab), value.equals("-") ? null
                                : new String(java.util.Base64.getDecoder().decode(value), StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Could not read the saved rebase messages", e);
                }
            }
            return new TodoHandler(saved, rebaseDir);
        }

        static void forget(File rebaseDir) {
            new File(rebaseDir, REPLIES_FILE).delete();
            new File(rebaseDir, LEGACY_REPLIES_FILE).delete();
        }

        /** Works out where JGit will ask, and what to answer - see the class comment. */
        private void planReplies(List<RebaseTodo.Step> allSteps) {
            List<RebaseTodo.Step> steps = allSteps.stream()
                    .filter(s -> s.action() != RebaseTodo.Action.DROP)
                    .toList();
            String leaderMessage = null;
            boolean runHasSquash = false;
            for (int i = 0; i < steps.size(); i++) {
                RebaseTodo.Step step = steps.get(i);
                String hash = step.commit().getHash();
                String message = step.message() == null || step.message().isBlank()
                        ? null : step.message();
                if (!step.action().foldsUpward()) {
                    leaderMessage = message;
                    runHasSquash = false;
                    if (step.action() == RebaseTodo.Action.REWORD && message == null) {
                        // A reword with no new text changes nothing - the CLI engine
                        // writes it as a plain pick. Kept as a reword, JGit amends to a
                        // byte-identical commit within the same second and fails with
                        // NO_CHANGE; before, the reply was the subject alone and the
                        // body was silently dropped.
                        rewordsToPick.add(hash);
                    } else if (step.action() == RebaseTodo.Action.REWORD) {
                        replies.put(hash, message);
                    } else if (step.action() == RebaseTodo.Action.PICK && message != null) {
                        replies.put(hash, message);
                        picksToReword.add(hash);
                    }
                    continue;
                }
                runHasSquash |= step.action() == RebaseTodo.Action.SQUASH;
                boolean endsRun = i + 1 >= steps.size() || !steps.get(i + 1).action().foldsUpward();
                if (endsRun && runHasSquash) {
                    // The CLI engine applies the leader's message after the whole run, so
                    // it is the run's final message there; the same here. With none, JGit's
                    // combined template stands, stripped as git strips it.
                    replies.put(hash, leaderMessage);
                }
            }
        }

        public void prepareSteps(List<org.eclipse.jgit.lib.RebaseTodoLine> steps) {
            if (todo == null) {
                return; // resuming: JGit's remaining list is already the plan
            }
            List<org.eclipse.jgit.lib.RebaseTodoLine> rebuilt = new ArrayList<>();
            for (RebaseTodo.Step step : todo.steps()) {
                if (step.action() == RebaseTodo.Action.DROP) {
                    continue; // a dropped commit is a line that is simply not there
                }
                org.eclipse.jgit.lib.RebaseTodoLine line = find(steps, step.commit());
                if (line == null) {
                    continue;
                }
                try {
                    String hash = step.commit().getHash();
                    line.setAction(picksToReword.contains(hash)
                            ? org.eclipse.jgit.lib.RebaseTodoLine.Action.REWORD
                            : rewordsToPick.contains(hash)
                                    ? org.eclipse.jgit.lib.RebaseTodoLine.Action.PICK
                                    : toJGit(step.action()));
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Could not set rebase action for " + step.shortId(), e);
                }
                rebuilt.add(line);
            }
            steps.clear();
            steps.addAll(rebuilt);
            persist();
        }

        /** Saves the replies beside JGit's state, for {@link #resume} after a stop. */
        private void persist() {
            if (replies.isEmpty() || !rebaseDir.isDirectory()) return;
            StringBuilder out = new StringBuilder();
            for (var reply : replies.entrySet()) {
                out.append(reply.getKey()).append('\t')
                        .append(reply.getValue() == null ? "-"
                                : java.util.Base64.getEncoder().encodeToString(
                                        reply.getValue().getBytes(StandardCharsets.UTF_8)))
                        .append('\n');
            }
            try {
                java.nio.file.Files.writeString(new File(rebaseDir, REPLIES_FILE).toPath(),
                        out, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not save the rebase messages", e);
            }
        }

        private static org.eclipse.jgit.lib.RebaseTodoLine find(
                List<org.eclipse.jgit.lib.RebaseTodoLine> steps, CommitInfo commit) {
            for (org.eclipse.jgit.lib.RebaseTodoLine line : steps) {
                // Match by abbreviated hash prefix (7+ chars). prefixCompare compares a
                // partial AbbreviatedObjectId against the full id stored in the line.
                if (line.getCommit() != null
                        && commit.getHash().startsWith(line.getCommit().name())) {
                    return line;
                }
            }
            return null;
        }

        private static org.eclipse.jgit.lib.RebaseTodoLine.Action toJGit(RebaseTodo.Action action) {
            return switch (action) {
                case REWORD -> org.eclipse.jgit.lib.RebaseTodoLine.Action.REWORD;
                case EDIT -> org.eclipse.jgit.lib.RebaseTodoLine.Action.EDIT;
                case SQUASH -> org.eclipse.jgit.lib.RebaseTodoLine.Action.SQUASH;
                case FIXUP -> org.eclipse.jgit.lib.RebaseTodoLine.Action.FIXUP;
                // DROP never reaches here: those lines are removed rather than marked.
                default -> org.eclipse.jgit.lib.RebaseTodoLine.Action.PICK;
            };
        }

        /**
         * The abbreviated hash of the step JGit is processing: the last line of
         * {@code rebase-merge/done}, which {@code popSteps} writes before the step runs.
         */
        private String currentStep() {
            File done = new File(rebaseDir, "done");
            try {
                List<String> lines = java.nio.file.Files.readAllLines(done.toPath(), StandardCharsets.UTF_8);
                for (int i = lines.size() - 1; i >= 0; i--) {
                    String line = lines.get(i).strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split("\\s+");
                    return parts.length > 1 ? parts[1] : null;
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not read which rebase step is running", e);
            }
            return null;
        }

        @Override
        public ModifyResult editCommitMessage(String message,
                org.eclipse.jgit.lib.CommitConfig.CleanupMode mode, char commentChar) {
            String step = currentStep();
            if (step != null) {
                for (var reply : replies.entrySet()) {
                    if (reply.getKey().startsWith(step)) {
                        return reply.getValue() == null
                                ? keepOffered(message)
                                : result(reply.getValue(),
                                        org.eclipse.jgit.lib.CommitConfig.CleanupMode.WHITESPACE);
                    }
                }
            }
            return keepOffered(message);
        }

        /**
         * JGit's own text when the plan has nothing to say: a squash run's combined
         * template is cleaned as git would clean it (its {@code #} lines are the
         * template's), and anything else - a commit's own message on a plain reword - is
         * kept exactly.
         */
        private static ModifyResult keepOffered(String message) {
            boolean template = message != null && message.length() > 1
                    && message.substring(1).startsWith(" This is a combination of");
            return result(message, template
                    ? org.eclipse.jgit.lib.CommitConfig.CleanupMode.DEFAULT
                    : org.eclipse.jgit.lib.CommitConfig.CleanupMode.VERBATIM);
        }

        private static ModifyResult result(String message,
                                           org.eclipse.jgit.lib.CommitConfig.CleanupMode cleanup) {
            return new ModifyResult() {
                @Override public String getMessage() { return message == null ? "" : message; }
                @Override public org.eclipse.jgit.lib.CommitConfig.CleanupMode getCleanupMode() { return cleanup; }
                @Override public boolean shouldAddChangeId() { return false; }
            };
        }
    }

    // restoreTo() is inherited from GitBackend: the dirty-tree guard, then reset(HARD).
    // The copy that used to be here had drifted from it - it reset directly, past the
    // SquashMessage.forget that reset() adds - and the guard is the one thing the
    // interface asks every engine to keep identical.

    /**
     * Aborts an in-progress rebase and restores the original HEAD - JGit's own
     * {@code RebaseCommand} ABORT, which also removes the rebase directory. A hard reset
     * does neither: it wiped the tree and left the repository REBASING on a detached HEAD.
     */
    public GitResult rebaseAbort() {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            git().rebase().setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.ABORT).call();
            return GitResult.ok("Rebase aborted");
        } catch (Exception e) {
            return GitResult.failed("Could not abort rebase: " + describe(e));
        }
    }

    /** Continues a rebase after conflicts were resolved and staged. */
    public GitResult rebaseContinue() {
        return runRebase(org.eclipse.jgit.api.RebaseCommand.Operation.CONTINUE, "Continued");
    }

    /**
     * Resumes a stopped rebase with CONTINUE or SKIP.
     *
     * <p>An interactive rebase needs its handler back: JGit asks it for the remaining
     * steps' messages, and with no handler a later {@code reword} throws. The replies the
     * plan was started with were saved beside JGit's own state - see
     * {@link TodoHandler#persist} - so the rest of the plan still gets the messages the
     * user wrote.
     */
    GitResult runRebase(org.eclipse.jgit.api.RebaseCommand.Operation operation, String verb) {
        if (git() == null) return GitResult.failed("No repository open");
        return ctx.asGit(() -> runRebaseWithJGit(operation, verb), null);
    }

    private GitResult runRebaseWithJGit(org.eclipse.jgit.api.RebaseCommand.Operation operation, String verb) {
        try {
            var command = git().rebase().setOperation(operation);
            File rebaseMerge = new File(repository().getDirectory(), "rebase-merge");
            if (new File(rebaseMerge, "interactive").exists()) {
                command.runInteractively(TodoHandler.resume(rebaseMerge));
            }
            RebaseResult result = command.call();
            RebaseResult.Status status = result.getStatus();
            if (status.isSuccessful()) {
                TodoHandler.forget(rebaseMerge);
                return GitResult.ok(verb + " - the rebase is complete");
            }
            return switch (status) {
                case STOPPED, CONFLICTS -> GitResult.conflict(
                        "Rebase stopped at " + describeStoppedAt(result)
                                + " - resolve the conflicts and continue, skip it, or abort",
                        staging().getConflictingFiles());
                case EDIT -> GitResult.conflict(
                        "Rebase stopped at " + describeStoppedAt(result)
                                + " so you can amend it - continue when you are done",
                        List.of());
                case NOTHING_TO_COMMIT -> GitResult.conflict(
                        "Nothing is left to commit for this step - its changes are already"
                                + " here, or were committed by hand. Skip it to carry on.",
                        List.of());
                default -> GitResult.failed("Rebase could not continue: " + status);
            };
        } catch (org.eclipse.jgit.api.errors.UnmergedPathsException e) {
            return GitResult.conflict("Resolve and stage every conflicting file before continuing.",
                    staging().getConflictingFiles());
        } catch (Exception e) {
            return GitResult.failed("Could not continue the rebase: " + describe(e));
        }
    }
}
