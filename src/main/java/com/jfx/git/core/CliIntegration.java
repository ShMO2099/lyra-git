package com.jfx.git.core;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.MergeMode;
import com.jfx.git.model.RebaseEligibility;
import com.jfx.git.model.RebaseRange;
import com.jfx.git.model.ResetMode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Merge, rebase (plain and interactive), cherry-pick, revert, reset, patches and
 * whatever operation is in progress, on the native engine.
 *
 * <p>Package-private: reached only through {@link NativeGitBackend}, which is the
 * engine's public face.
 */
final class CliIntegration {

    private static final Logger LOGGER = Logger.getLogger(NativeGitBackend.class.getName());

    private final CliRepo repo;

    CliIntegration(CliRepo repo) {
        this.repo = repo;
    }

    /**
     * {@code git merge} with the mode's flag, if it has one.
     *
     * <p>A custom message goes through a file ({@code -F <path>}), never {@code -m}: it is
     * free text of any length, with the quoting hazards {@link CliStaging#commit} describes, and
     * unlike {@code commit}, {@code merge -F -} does not read stdin - git takes the dash
     * as a file name. Messages are stripped first, as the JGit engine does, so the two
     * record the same text.
     */
    public GitResult merge(String sourceBranch, MergeMode mode, String message) {
        repo.ensureOpen();
        MergeMode chosen = mode == null ? MergeMode.DEFAULT : mode;
        String custom = message == null || message.isBlank() ? null : message.strip();
        GitCommand merge = GitCommand.of("merge");
        switch (chosen) {
            case DEFAULT -> { }
            case FF_ONLY -> merge.opts("--ff-only");
            case NO_FF -> merge.opts("--no-ff");
            // --ff as well: with merge.ff=false configured, git reads a bare --squash as
            // combined with --no-ff and refuses it ("cannot be used together").
            case SQUASH -> merge.opts("--squash", "--ff");
        }
        if (chosen == MergeMode.SQUASH && repo.runner.run(GitCommand.of("merge-base", "--is-ancestor")
                .revs(sourceBranch, "HEAD").args()).isOk()) {
            // Asked before, not read from the output afterwards: "Already up to date" is
            // translated, and a SQUASH_MSG left by an earlier squash would otherwise be
            // taken for this one's and given a second subject line.
            return GitResult.ok(GitService.alreadySquashedMessage(sourceBranch));
        }
        Path messageFile = null;
        try {
            // A squash makes no commit, and git ignores -m under --squash; its message
            // goes into SQUASH_MSG afterwards instead (SquashMessage.title).
            if (custom != null && chosen != MergeMode.SQUASH) {
                messageFile = Files.createTempFile("lyra-merge-msg", ".txt");
                Files.writeString(messageFile, custom, StandardCharsets.UTF_8);
                merge.opts("-F", messageFile.toAbsolutePath().toString());
            }
            // The branch to merge is whatever the UI handed over - see GitCommand.
            var res = repo.runner.run(merge.revs(sourceBranch).args());
            if (chosen == MergeMode.SQUASH) {
                GitResult squashed = res.isOk()
                        ? GitResult.ok(GitService.squashedMessage(sourceBranch))
                        : conflictOrFailed("Squash of " + sourceBranch + " has conflicts", res);
                // Only when git got as far as writing SQUASH_MSG: a squash refused
                // outright must not retitle one an earlier squash left behind.
                if (squashed.isOk() || squashed.isConflict()) {
                    SquashMessage.title(repo.gitDir, sourceBranch, custom);
                }
                return squashed;
            }
            return res.isOk()
                    ? GitResult.ok("Merged " + sourceBranch)
                    : conflictOrFailed("Merge of " + sourceBranch + " has conflicts", res);
        } catch (IOException e) {
            return GitResult.failed("Could not pass the merge message to git: " + e.getMessage());
        } finally {
            if (messageFile != null) {
                try {
                    Files.deleteIfExists(messageFile);
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Could not delete " + messageFile, e);
                }
            }
        }
    }

    /**
     * Classifies a failed merge-family command: a CONFLICT result when git left
     * conflicting paths behind, a plain failure otherwise.
     *
     * <p>The CLI reports both the same way - non-zero exit, text on stderr - but the UI
     * keys conflict banners, the resolution panel and the "N conflicting file(s)" wording
     * off {@link GitResult#isConflict()}. Reporting every conflict as a plain failure
     * made all of those behave differently on this engine, and surfaced no file list at
     * all. The porcelain {@code u} records {@link CliStaging#getConflictingFiles()} reads are the
     * same list JGit's {@code MergeResult.getConflicts()} carries.
     *
     * <p>A failure that never started - local changes in the way - carries the refusal
     * that says so ({@link RefusalWords#failure}).
     */
    GitResult conflictOrFailed(String conflictMessage, NativeGitRunner.Result res) {
        List<String> conflicts = repo.staging.getConflictingFiles();
        return conflicts.isEmpty()
                ? RefusalWords.failure(res.stderr())
                : GitResult.conflict(conflictMessage, conflicts);
    }

    public GitResult abortMerge() {
        repo.ensureOpen();
        if (!new File(repo.gitDir, "MERGE_HEAD").exists()) {
            return GitResult.failed("There is no merge to abort");
        }
        var res = repo.runner.run("merge", "--abort");
        return res.isOk() ? GitResult.ok("Merge aborted") : GitResult.failed(res.stderr());
    }

    public com.jfx.git.model.OperationState getOperationState() {
        repo.ensureOpen();
        return com.jfx.git.model.OperationState.detect(repo.gitDir);
    }

    public GitResult continueOperation() {
        repo.ensureOpen();
        var state = getOperationState();
        return switch (state.kind()) {
            case NONE -> GitResult.failed("Nothing is in progress");
            case MERGING -> {
                if (!repo.staging.getConflictingFiles().isEmpty()) {
                    yield GitResult.failed("Resolve and stage every conflicting file before"
                            + " continuing the merge.");
                }
                yield sequence("merge", "--continue", "Finished the merge");
            }
            case CHERRY_PICKING -> sequence("cherry-pick", "--continue", "Cherry-pick continued");
            case REVERTING -> sequence("revert", "--continue", "Revert continued");
            case REBASING, REBASING_APPLY -> rebaseContinue();
            case APPLYING_MAILBOX -> sequence("am", "--continue", "Continued applying patches");
            case BISECTING -> GitResult.failed("A bisect continues by marking commits good or bad.");
        };
    }

    public GitResult skipOperation() {
        repo.ensureOpen();
        var state = getOperationState();
        return switch (state.kind()) {
            case NONE -> GitResult.failed("Nothing is in progress");
            case MERGING -> GitResult.failed("A merge has no step to skip - finish it or abort it.");
            case CHERRY_PICKING -> sequence("cherry-pick", "--skip", "Skipped the commit");
            case REVERTING -> sequence("revert", "--skip", "Skipped the commit");
            case REBASING, REBASING_APPLY -> sequence("rebase", "--skip", "Skipped the commit");
            case APPLYING_MAILBOX -> sequence("am", "--skip", "Skipped the patch");
            case BISECTING -> GitResult.ok(repo.bisection.bisectSkip().message());
        };
    }

    public GitResult abortOperation() {
        repo.ensureOpen();
        var state = getOperationState();
        return switch (state.kind()) {
            case NONE -> GitResult.failed("Nothing is in progress");
            case MERGING -> abortMerge();
            case CHERRY_PICKING -> sequence("cherry-pick", "--abort", "Cherry-pick aborted");
            case REVERTING -> sequence("revert", "--abort", "Revert aborted");
            case REBASING, REBASING_APPLY -> rebaseAbort();
            case APPLYING_MAILBOX -> sequence("am", "--abort", "Stopped applying patches");
            case BISECTING -> repo.bisection.bisectReset();
        };
    }

    /**
     * Runs {@code git <command> <flag>} without an editor, reporting a stop that leaves
     * conflicts as a conflict and anything else as the failure git described.
     */
    private GitResult sequence(String command, String flag, String success) {
        var res = repo.runner.runWithEnvironment(CliRepo.NO_EDITOR, List.of(command, flag));
        if (res.isOk()) {
            return GitResult.ok(success);
        }
        String detail = res.stderr().isBlank() ? res.trimmedStdout() : res.stderr();
        List<String> conflicts = repo.staging.getConflictingFiles();
        if (!conflicts.isEmpty()) {
            return GitResult.conflict("Stopped again with conflicts: " + detail, conflicts);
        }
        // Still in progress but not conflicted: an edit stop, or a step that became empty.
        return getOperationState().inProgress() && !flag.equals("--abort")
                ? GitResult.conflict(detail, List.of())
                : GitResult.failed(detail);
    }

    public GitResult rebase(String upstream) {
        repo.ensureOpen();
        var res = repo.runner.run(GitCommand.of("rebase").revs(upstream).args());
        return res.isOk()
                ? GitResult.ok("Rebased onto " + upstream)
                : conflictOrFailed("Rebase stopped with conflicts", res);
    }

    /**
     * Runs {@code todo} as a real {@code git rebase -i}.
     *
     * <h4>How the plan reaches git without an editor</h4>
     * {@code rebase -i} always writes a todo file and opens {@code $GIT_SEQUENCE_EDITOR}
     * on it; there is no flag to supply one directly. So the plan is written to a temp
     * file and the sequence editor is set to {@code cp <that file>} — git appends the todo
     * path, making the "editor" a copy that replaces the plan wholesale.
     *
     * <p>Messages are handled with {@code exec} lines rather than {@code $GIT_EDITOR}:
     * a reword or a combined squash message becomes
     * {@code exec git commit --amend -F <file>} after the step it belongs to. That keeps
     * every message explicit instead of depending on git opening an editor the right
     * number of times, and {@code GIT_EDITOR=true} makes the prompts git still raises
     * accept their default.
     *
     * <p>A conflict leaves the rebase in progress, as git does; the toolbar's continue and
     * abort actions are what resolve it.
     */
    public GitResult rebaseInteractive(RebaseTodo todo) {
        repo.ensureOpen();
        if (todo == null || todo.size() == 0) {
            return GitResult.failed("There is nothing to rebase");
        }
        if (todo.base() == null) {
            return GitResult.failed("This range starts at the repository's first commit,"
                    + " which an interactive rebase cannot replay onto anything.");
        }
        // See RebaseTodo.normalized(): the same plan has to mean the same thing here as
        // it does on JGit, and a message left on a squash step is where they diverged.
        todo = todo.normalized();

        List<String> problems = todo.problems();
        if (!problems.isEmpty()) {
            return GitResult.failed(String.join(" ", problems));
        }
        try {
            if (repo.staging.hasUncommittedChanges()) {
                return GitResult.failed("Commit or stash your changes before rebasing.");
            }
        } catch (GitOperationException e) {
            // Not knowing is not the same as clean: a rebase over unread changes is
            // exactly what the check exists to prevent.
            return GitResult.failed("Could not check the working tree: " + e.getMessage());
        }

        List<Path> temporaries = new ArrayList<>();
        try {
            Path todoFile = Files.createTempFile("lyra-rebase-todo", ".txt");
            temporaries.add(todoFile);
            Files.writeString(todoFile, buildTodoFile(todo, temporaries), StandardCharsets.UTF_8);

            var res = repo.runner.runWithEnvironment(
                    java.util.Map.of(
                            "GIT_SEQUENCE_EDITOR", "cp " + quoteForShell(todoFile),
                            "GIT_EDITOR", "true"),
                    // --empty=drop: a pick whose change is already on the new base becomes
                    // empty, and git's default for an explicit todo is to stop there -
                    // with nothing to resolve. Dropping it is what the plain rebase does
                    // for "previously applied" commits. Commits that were empty to begin
                    // with are still kept, as rebase -i always keeps them.
                    List.of("rebase", "-i", "--no-autosquash", "--empty=drop", todo.base()));

            // git exits 0 when it pauses at an "edit" step on purpose, so success is also
            // "no rebase left in progress" - otherwise an edit stop was reported as done.
            if (res.isOk() && !isRebaseInProgress()) {
                return GitResult.ok(todo.summary());
            }
            if (res.isOk()) {
                return GitResult.conflict("The rebase stopped so you can amend a commit"
                        + " - continue when you are done", List.of());
            }
            // A stopped rebase is not the same as one that never started; say which.
            String detail = res.stderr().isBlank() ? res.trimmedStdout() : res.stderr();
            return isRebaseInProgress()
                    ? GitResult.conflict("The rebase stopped and needs your help: " + detail, List.of())
                    : GitResult.failed("Rebase failed: " + detail);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not prepare the rebase plan", e);
            return GitResult.failed("Could not prepare the rebase: " + e.getMessage());
        } finally {
            for (Path temp : temporaries) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // A leftover file in the temp directory is not worth reporting.
                }
            }
        }
    }

    /**
     * Renders {@code todo} as a git todo file, writing message files as it goes.
     *
     * <p>Action lines are laid out first, then the {@code exec} amends are inserted
     * backwards so that each insertion cannot shift the position of one still to come.
     */
    String buildTodoFile(RebaseTodo todo, List<Path> temporaries) throws IOException {
        List<String> lines = new ArrayList<>(todo.size());
        for (int i = 0; i < todo.size(); i++) {
            RebaseTodo.Step step = todo.step(i);
            // reword becomes pick plus an explicit amend, so no editor is involved.
            String token = step.action() == RebaseTodo.Action.REWORD
                    ? RebaseTodo.Action.PICK.token()
                    : step.action().token();
            lines.add(token + " " + step.commit().getHash());
        }

        for (int i = todo.size() - 1; i >= 0; i--) {
            RebaseTodo.Step step = todo.step(i);
            if (step.action() == RebaseTodo.Action.DROP) continue;
            if (step.message() == null || step.message().isBlank()) continue;

            // A message on a step that later steps fold into has to be applied after the
            // last of them; applying it earlier would let the squashes overwrite it.
            // A drop line does not end the run - git treats it as a no-op, and a squash
            // after it still folds into the step above - so it is stepped over.
            int lastOfGroup = i;
            for (int next = i + 1; next < todo.size(); next++) {
                RebaseTodo.Action action = todo.step(next).action();
                if (action == RebaseTodo.Action.DROP) continue;
                if (!action.foldsUpward()) break;
                lastOfGroup = next;
            }

            Path messageFile = Files.createTempFile("lyra-rebase-msg", ".txt");
            temporaries.add(messageFile);
            Files.writeString(messageFile, step.message(), StandardCharsets.UTF_8);
            // --no-verify: this amend only replaces a message the user already wrote. A
            // plain `git rebase -i` reword does not run pre-commit, and running it (and
            // commit-msg) here meant a slow or failing hook stopped the rebase part-way
            // for a change it had no business judging.
            lines.add(lastOfGroup + 1,
                    "exec git commit --amend --no-verify -F " + quoteForShell(messageFile));
        }
        return String.join("\n", lines) + "\n";
    }

    /**
     * A path git's shell will accept.
     *
     * <p>git runs the sequence editor and {@code exec} lines through a shell, and on
     * Windows that is the bundled {@code sh} — where a backslash is an escape, not a
     * separator. Forward slashes work on every platform git supports.
     */
    private static String quoteForShell(Path path) {
        return "'" + path.toAbsolutePath().toString().replace('\\', '/').replace("'", "'\\''") + "'";
    }

    /** True while a rebase is stopped part-way through. */
    private boolean isRebaseInProgress() {
        return new File(repo.gitDir, "rebase-merge").isDirectory()
                || new File(repo.gitDir, "rebase-apply").isDirectory();
    }

    public GitResult rebaseAbort() {
        repo.ensureOpen();
        var res = repo.runner.run("rebase", "--abort");
        return res.isOk() ? GitResult.ok("Rebase aborted") : GitResult.failed(res.stderr());
    }

    public GitResult rebaseContinue() {
        repo.ensureOpen();
        return sequence("rebase", "--continue", "Rebase continued");
    }

    /**
     * The commits an interactive rebase from {@code startHash} would replay, newest first.
     *
     * <p>Mirrors {@link GitService#getRebaseRange}: a commit on this branch means "replay
     * from here", so the base is its parent; a commit elsewhere means "put my branch on
     * top of that", so the commit itself is the base.
     */
    public RebaseRange getRebaseRange(String startHash) {
        repo.ensureOpen();
        var eligibility = checkRebaseFrom(startHash);
        if (eligibility != RebaseEligibility.OK
                && eligibility != RebaseEligibility.ONTO) {
            return new RebaseRange(List.of(), null, switch (eligibility) {
                case MERGE_COMMIT -> "That is a merge commit, and an interactive rebase"
                        + " replays a linear history.";
                case ROOT_COMMIT -> "That is the repository's first commit, which an"
                        + " interactive rebase cannot replay onto anything.";
                default -> "Commit " + CliRepo.shorten(startHash) + " could not be read.";
            });
        }

        String baseSpec = eligibility == RebaseEligibility.ONTO
                ? startHash
                : startHash + "^";
        var baseRes = repo.runner.run("rev-parse", baseSpec);
        if (!baseRes.isOk()) {
            return new RebaseRange(List.of(), null,
                    "Could not resolve the commit to replay onto: " + baseRes.stderr());
        }
        String baseHash = baseRes.trimmedStdout();

        // Moving the branch onto another line: `base...HEAD --cherry-pick --right-only`
        // leaves out the commits whose change is already there under another hash, which
        // git's own rebase skips as "previously applied". An explicit plan bypasses that
        // filter, and replaying one stopped the rebase with "the previous cherry-pick is
        // now empty". Replaying a range already on this branch has no upstream side, so
        // the plain range is exact there.
        var res = eligibility == RebaseEligibility.ONTO
                ? repo.runner.run(GitCommand.of("log", "--topo-order", "--cherry-pick", "--right-only",
                        "--no-decorate", CliHistory.COMMIT_FORMAT).revs(baseHash + "...HEAD").args())
                : repo.runner.run(GitCommand.of("log", "--topo-order", "--no-decorate", CliHistory.COMMIT_FORMAT)
                        .revs(baseHash + "..HEAD").args());
        if (!res.isOk()) {
            return new RebaseRange(List.of(), null,
                    "Could not read the commits: " + res.stderr());
        }

        List<CommitInfo> commits = CliParsers.parseCommits(res.stdout());
        if (commits.isEmpty()) {
            return new RebaseRange(List.of(), null,
                    eligibility == RebaseEligibility.ONTO
                            ? "This branch has no commits that are not already on "
                              + CliRepo.shorten(startHash) + "."
                            : "There is nothing to replay from that commit.");
        }
        return new RebaseRange(commits, baseHash, null);
    }

    public RebaseEligibility checkRebaseFrom(String startHash) {
        return RebaseEligibility.from(repo.history.getCommitPosition(startHash));
    }

    /**
     * {@code format-patch} as a patch file, not as an email.
     *
     * <p>Porcelain {@code format-patch} reads a whole set of {@code format.*} settings
     * meant for mailing a series: {@code format.signOff} added a Signed-off-by line to
     * every commit message, {@code format.to}/{@code cc}/{@code headers} put the user's
     * addresses into the file, {@code format.signature} appended a footer,
     * {@code format.numbered} and {@code subjectPrefix} renamed the subjects, and
     * {@code coverLetter}, {@code thread}, {@code attach} and {@code useAutoBase} changed
     * the file's shape. Each is switched off here, and the diff options that change what
     * the hunks look like ({@code diff.noprefix}, {@code diff.relative},
     * {@code diff.context}, an external diff) are pinned, so the file is the same for every
     * user - the commits as the JGit engine writes them, numbered as single patches the
     * way one {@code -1} per commit used to come out.
     *
     * <p>{@code --root} is not decoration. Given exactly one revision, format-patch reads
     * it as "everything since" - for the commit HEAD is on, nothing at all, an empty file
     * reported as saved - and {@code --root} is what makes it the commit itself. (A
     * {@code --max-count} would do that too, but makes format-patch walk the history
     * again despite {@code --no-walk}.)
     */
    private static final List<String> FORMAT_PATCH = List.of("format-patch", "--stdout",
            "--no-walk=unsorted", "--stdin", "--root",
            "--no-signoff", "--no-to", "--no-cc", "--no-add-header", "--no-signature",
            "--no-numbered", "--subject-prefix=PATCH", "--no-cover-letter", "--no-thread",
            "--no-attach", "--no-base", "--no-from", "--no-notes",
            "--no-color", "--no-ext-diff", "--no-relative", "--src-prefix=a/", "--dst-prefix=b/",
            "-U3");

    public GitResult createPatch(List<String> commits, File out) {
        repo.ensureOpen();
        if (commits == null || commits.isEmpty()) return GitResult.failed("No commits to make a patch from");
        if (out == null) return GitResult.failed("No file to save the patch to");
        for (String commit : commits) {
            GitResult bad = CliRepo.rejectIfOptionLike(commit, "A commit");
            if (bad != null) return bad;
        }
        // Oldest first, by git's own ordering of the commits named - and with each
        // commit's parents, which is how a merge is told from an ordinary commit.
        var ordered = repo.runner.run(GitCommand.of("rev-list", "--no-walk", "--reverse", "--parents")
                .revs(commits).args());
        if (!ordered.isOk()) return GitResult.failed(ordered.stderr());

        List<String> hashes = new ArrayList<>();
        for (String line : ordered.trimmedStdout().split("\\r?\\n")) {
            if (line.isBlank()) continue;
            String[] commitAndParents = line.trim().split("\\s+");
            // Asked for a merge, "format-patch -1" does not fail: it passes over it and
            // writes the patch of the commit before, which nobody asked for.
            if (commitAndParents.length > 2) {
                return GitResult.failed(IntegrationOps.mergeHasNoPatch(
                        commitAndParents[0].substring(0, Math.min(8, commitAndParents[0].length()))));
            }
            hashes.add(commitAndParents[0]);
        }

        // All of them in one format-patch. It writes the commits it is given in the
        // reverse of the order given, so they go in newest first to come out oldest first;
        // on stdin, so a long selection does not meet the command-line limit.
        List<String> newestFirst = new ArrayList<>(hashes);
        java.util.Collections.reverse(newestFirst);
        var res = repo.runner.runWithInput(CliRepo.revisionsInput(newestFirst), FORMAT_PATCH);
        if (!res.isOk()) return GitResult.failed(res.stderr());
        if (res.stdoutBytes().length == 0) {
            return GitResult.failed("git wrote no patch for " + (hashes.size() == 1
                    ? CliRepo.shorten(hashes.get(0)) : hashes.size() + " commits"));
        }
        int count = hashes.size();
        try {
            java.nio.file.Files.write(out.toPath(), res.stdoutBytes());
        } catch (java.io.IOException e) {
            return GitResult.failed("Could not save the patch: " + e.getMessage());
        }
        return GitResult.ok(count == 1
                ? "Saved a commit as " + out.getName()
                : "Saved " + count + " commits as " + out.getName());
    }

    /**
     * {@code git apply} for the two modes that leave the result uncommitted, and
     * {@code git am} for the one that makes commits. Both with a three-way fallback
     * where git has one, so a patch made against an older version of a file lands as a
     * conflict to resolve rather than as a flat refusal.
     */
    public GitResult applyPatch(File patch, com.jfx.git.model.PatchApplyMode mode) {
        repo.ensureOpen();
        if (patch == null || !patch.isFile()) return GitResult.failed("There is no patch file to apply");
        String path = patch.getAbsolutePath();
        return switch (mode) {
            // No --3way here: it stages, which is exactly what this mode does not do.
            case WORKTREE -> {
                var res = repo.runner.run(GitCommand.of("apply").paths(path).args());
                yield res.isOk() ? GitResult.ok("Applied " + patch.getName())
                        : GitResult.failed("The patch does not apply: " + res.stderr().strip());
            }
            case INDEX -> {
                var res = repo.runner.run(GitCommand.of("apply", "--index", "--3way").paths(path).args());
                yield res.isOk() ? GitResult.ok("Applied and staged " + patch.getName())
                        : conflictOrFailed("The patch applied with conflicts", res);
            }
            case AM -> {
                var res = repo.runner.runWithEnvironment(CliRepo.NO_EDITOR,
                        GitCommand.of("am", "--3way").paths(path).args());
                if (res.isOk()) {
                    yield GitResult.ok("Applied " + patch.getName() + " as commits");
                }
                List<String> conflicts = repo.staging.getConflictingFiles();
                if (!conflicts.isEmpty()) {
                    yield GitResult.conflict("Applying the patch stopped with conflicts", conflicts);
                }
                // A file that is not a mailbox leaves no operation behind; one that
                // stopped part-way without conflicts does, and has to be finishable.
                String detail = res.stderr().isBlank() ? res.trimmedStdout() : res.stderr().strip();
                yield getOperationState().inProgress()
                        ? GitResult.conflict(detail, List.of())
                        : GitResult.failed("The patch could not be applied as commits: " + detail);
            }
        };
    }

    public GitResult cherryPick(String commitHash) {
        return cherryPick(commitHash, 0);
    }

    public GitResult cherryPick(String commitHash, int mainline) {
        repo.ensureOpen();
        GitResult bad = CliRepo.rejectIfOptionLike(commitHash, "A commit");
        if (bad != null) return bad;
        String parent = mainlineArgument(commitHash, mainline);
        if (parent == null) return GitResult.failed("That commit has no parent " + mainline);
        var res = repo.runner.runWithEnvironment(CliRepo.NO_EDITOR, GitCommand.of("cherry-pick")
                .optsIf(!parent.isEmpty(), "-m", parent).revs(commitHash).args());
        return res.isOk()
                ? GitResult.ok("Cherry-picked " + commitHash)
                : conflictOrFailed("Cherry-pick has conflicts", res);
    }

    public GitResult revert(String commitHash) {
        return revert(commitHash, 0);
    }

    public GitResult revert(String commitHash, int mainline) {
        repo.ensureOpen();
        GitResult bad = CliRepo.rejectIfOptionLike(commitHash, "A commit");
        if (bad != null) return bad;
        String parent = mainlineArgument(commitHash, mainline);
        if (parent == null) return GitResult.failed("That commit has no parent " + mainline);
        var res = repo.runner.runWithEnvironment(CliRepo.NO_EDITOR, GitCommand.of("revert", "--no-edit")
                .optsIf(!parent.isEmpty(), "-m", parent).revs(commitHash).args());
        return res.isOk()
                ? GitResult.ok("Reverted " + commitHash)
                : conflictOrFailed("Revert has conflicts", res);
    }

    /**
     * The {@code -m} value for {@code commitHash}: empty for an ordinary commit (git
     * refuses {@code -m} there), the requested parent - or 1 - for a merge, and
     * {@code null} when the request names a parent the commit does not have.
     */
    private String mainlineArgument(String commitHash, int requested) {
        var res = repo.runner.run(GitCommand.of("rev-list", "--parents", "-n", "1").revs(commitHash).args());
        int parents = res.isOk() ? Math.max(0, res.trimmedStdout().split("\\s+").length - 1) : 1;
        if (parents <= 1) {
            return requested > 1 ? null : "";
        }
        int parent = requested <= 0 ? 1 : requested;
        return parent <= parents ? String.valueOf(parent) : null;
    }

    public GitResult reset(String commitish, ResetMode mode) {
        repo.ensureOpen();
        String flag = switch (mode) {
            case SOFT -> "--soft";
            case MIXED -> "--mixed";
            case HARD -> "--hard";
        };
        // Both separators. Without "--" git takes a name that is not a revision as a path:
        // "reset --mixed <x>" with x a file unstaged it and reported "Reset (MIXED) to x".
        // With it, x has to be a revision; --end-of-options keeps one beginning with a
        // dash from being read as a flag before that is checked.
        var res = repo.runner.run(GitCommand.of("reset", flag).revs(commitish).endOfRevisions().args());
        return res.isOk() ? GitResult.ok("Reset (" + mode + ") to " + commitish) : RefusalWords.failure(res.stderr());
    }

}
