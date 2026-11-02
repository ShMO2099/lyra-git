package com.jfx.git.core;

import com.jfx.git.model.StagingState;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Status, staging, discarding and committing on the native engine, with the
 * index plumbing that stages or unstages a single hunk.
 *
 * <p>Package-private: reached only through {@link NativeGitBackend}, which is the
 * engine's public face.
 */
final class CliStaging {

    private static final Logger LOGGER = Logger.getLogger(NativeGitBackend.class.getName());

    private final CliRepo repo;

    CliStaging(CliRepo repo) {
        this.repo = repo;
    }

    /**
     * Whether anything is pending - including a merge, cherry-pick, revert or rebase
     * that is byte-clean but still waiting for its finishing commit.
     *
     * @see GitBackend#isOperationInProgress(File)
     * @throws GitOperationException when {@code git status} fails. This is what the
     *         dirty-tree guards (restore, interactive rebase) ask before discarding or
     *         rewriting anything, and answering "clean" because status timed out or was
     *         refused let them go ahead over uncommitted work. Same contract as
     *         {@link GitService#hasUncommittedChanges()}.
     */
    public boolean hasUncommittedChanges() {
        repo.ensureOpen();
        // The same three things the staging state counts - an operation waiting for its
        // commit, a change on either side of the index, an untracked file - without what
        // only the staging panel needs. This runs on every graph page, and the full read
        // listed every file in every untracked directory (--untracked-files=all), detected
        // renames, and worked out the branch, all for a yes or no. "normal" names an
        // untracked directory once instead of walking it; it is not shown at all when
        // everything in it is ignored, so the answer is the same.
        if (GitBackend.isOperationInProgress(repo.gitDir)) return true;
        // In a repository tuned for speed, the same untracked mode as the staging list:
        // two calls asking in two modes would each find the cache the other one wrote
        // unusable, and rewrite it.
        var res = repo.statusCommand("--porcelain=v2", "-z",
                repo.tunedForSpeed() ? "--untracked-files=all" : "--untracked-files=normal",
                "--no-renames");
        if (!res.isOk()) {
            throw new GitOperationException("Could not read repository status: " + res.stderr());
        }
        // Any record but a "#" header: status.showStash and the like add those to a clean
        // tree's output.
        byte[] out = res.stdoutBytes();
        for (int i = 0; i < out.length; ) {
            if (out[i] != '#' && out[i] != 0) return true;
            int end = CliParsers.findNul(out, i);
            if (end < 0) break;
            i = end + 1;
        }
        return false;
    }

    public List<String> getStagedFiles() {
        return getStagingState().staged();
    }

    public List<String> getConflictingFiles() {
        return new ArrayList<>(getStagingState().conflicting());
    }

    /**
     * The staging panel's model. A failed {@code git status} logs and yields an empty
     * state here, as it does on the JGit engine - the panel keeps drawing. Anything that
     * decides whether it is safe to proceed uses {@link #hasUncommittedChanges()}, which
     * throws instead.
     */
    public StagingState getStagingState() {
        repo.ensureOpen();
        // Porcelain v2 with NUL separator for 100% path safety and microsecond speed.
        // --branch adds a "# branch.head <name>" header to the output this call already
        // makes, which is where the branch below comes from: the staging panel needs it
        // and a second subprocess to ask for it separately would be pure waste.
        var res = repo.statusCommand("--porcelain=v2", "--branch", "-z", "--untracked-files=all");
        if (!res.isOk()) {
            LOGGER.warning("git status failed: " + res.stderr());
            return new StagingState(new ArrayList<>(), new ArrayList<>(), new LinkedHashSet<>());
        }
        CliParsers.StatusListing listing = CliParsers.parseStatus(res.stdoutBytes());

        // Sorted and de-duplicated to match GitService, so switching engines does not
        // reorder or double up the file lists the user is looking at. The branch is the
        // header's - blank for a detached HEAD, as getCurrentBranch() answers - and read
        // separately only when a git without the header left it out.
        return new StagingState(
                listing.unstaged().stream().distinct().sorted().toList(),
                listing.staged().stream().distinct().sorted().toList(),
                listing.conflicts(),
                listing.status(),
                listing.branch() == null ? repo.branches.getCurrentBranch() : listing.branch(),
                GitBackend.isOperationInProgress(repo.gitDir));
    }

    public GitResult stageFile(String path) {
        repo.ensureOpen();
        var res = repo.runner.run(GitCommand.of("add").paths(path).args());
        if (res.isOk()) return GitResult.ok("Staged " + path);

        // `git add` fails for a path that is no longer on disk, which is how a deletion
        // presents; recording the removal is the same gesture for that case. Only that
        // case: add also fails when a clean filter does (git-lfs missing from the app's
        // PATH under filter.lfs.required), when core.safecrlf rejects the file, or on a
        // permissions error - and answering any of those with `rm --cached` staged a
        // deletion of a file that is right there, reported as "Staged", for the next
        // commit to remove from the repository.
        if (existsInWorkTree(path)) {
            return GitResult.failed("Could not stage " + path + ": " + res.stderr());
        }
        var removed = repo.runner.run(GitCommand.of("rm", "--cached", "--ignore-unmatch").paths(path).args());
        if (!removed.isOk()) return GitResult.failed(res.stderr());
        // --ignore-unmatch also exits 0 for a path that is in neither the index nor the
        // working tree, so a clean exit alone would report "Staged" for a no-op. Its own
        // "rm '<path>'" line is what says something was actually removed.
        if (removed.trimmedStdout().isEmpty()) {
            return GitResult.failed(path + " is neither in the working tree nor in the index");
        }
        return GitResult.ok("Staged " + path);
    }

    /**
     * Whether anything is at {@code path} in the working tree - a dangling symlink
     * counts, since git tracks the link rather than what it points at.
     */
    private boolean existsInWorkTree(String path) {
        try {
            return Files.exists(repo.workTree.toPath().resolve(path), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.InvalidPathException e) {
            // A name this filesystem cannot even express cannot be on disk here.
            return false;
        }
    }

    public GitResult stageAll() {
        repo.ensureOpen();
        var res = repo.runner.run("add", "-A");
        return res.isOk() ? GitResult.ok("Staged all changes") : GitResult.failed(res.stderr());
    }

    /**
     * {@code restore --staged}, or - only on an unborn branch, where there is no HEAD to
     * restore from - {@code rm --cached}, which is what unstaging means before the first
     * commit.
     *
     * <p>The removal used to be the last link of a chain that took any failure as its cue.
     * A HEAD that does not resolve because it is <em>broken</em> - a ref file emptied by a
     * crash, a missing object - fails {@code restore} just the same, and the chain then
     * staged the deletion of the path (for "Unstage all", of every file in the
     * repository) for the next commit to record. The condition is now asked, not
     * inferred. {@code reset HEAD --}, the old middle link, failed whenever restore did:
     * restore is always there at the minimum version.
     */
    public GitResult unstageFile(String path) {
        repo.ensureOpen();
        var res = repo.runner.run(GitCommand.of("restore", "--staged").paths(path).args());
        if (!res.isOk() && isUnbornHead()) {
            res = repo.runner.run(GitCommand.of("rm", "--cached", "-r", "-q", "--ignore-unmatch").paths(path).args());
        }
        return res.isOk() ? GitResult.ok("Unstaged " + path) : GitResult.failed(res.stderr());
    }

    /** As {@link #unstageFile}, for everything. */
    public GitResult unstageAll() {
        repo.ensureOpen();
        var res = repo.runner.run("restore", "--staged", "--", ".");
        if (!res.isOk() && isUnbornHead()) {
            res = repo.runner.run("rm", "--cached", "-r", "-q", "--ignore-unmatch", "--", ".");
        }
        return res.isOk() ? GitResult.ok("Unstaged all changes") : GitResult.failed(res.stderr());
    }

    /**
     * Whether HEAD names a branch that has no commit yet - a new repository, or an orphan
     * branch - as opposed to resolving, or being broken.
     *
     * <p>Both halves are needed. {@code rev-parse --verify HEAD} fails for unborn and
     * broken alike; {@code symbolic-ref HEAD} succeeds for unborn (it only reads HEAD
     * itself) and fails for a branch ref that exists but cannot be read ("No such ref").
     * A branch pointing at a missing object resolves to a hash, so it is not unborn either.
     */
    private boolean isUnbornHead() {
        NativeGitRunner git = repo.runner;
        return !git.run("rev-parse", "--verify", "--quiet", "HEAD").isOk()
                && git.run("symbolic-ref", "--quiet", "HEAD").isOk();
    }

    /**
     * Throws away working-tree changes to {@code path}.
     *
     * <p>Tracked and untracked paths need different commands, and the earlier version ran
     * both unconditionally: for an untracked file {@code restore} failed, {@code clean}
     * deleted it anyway, and the failed {@code restore} was reported — so the file was
     * gone while the UI showed an error and did not refresh. The path's tracked state is
     * settled first, and the result describes what actually happened.
     */
    public GitResult discardAllChanges() {
        repo.ensureOpen();

        var restore = repo.runner.run("restore", "--worktree", "--", ".");
        if (!restore.isOk()) {
            restore = repo.runner.run("checkout", "--", ".");
        }
        if (!restore.isOk()) {
            return GitResult.failed("Could not discard all changes: " + restore.stderr());
        }
        // Untracked files are not touched by restore/checkout; clean removes them.
        var cleaned = repo.runner.run("clean", "-f", "-q", "-d");
        return cleaned.isOk()
                ? GitResult.ok("Discarded all working-tree changes")
                : GitResult.failed("Restored tracked files but could not remove untracked ones: "
                        + cleaned.stderr());
    }

    public GitResult discardChanges(String path) {
        repo.ensureOpen();

        if (!isTracked(path)) {
            var cleaned = repo.runner.run(GitCommand.of("clean", "-f", "-q", "-d").paths(path).args());
            return cleaned.isOk()
                    ? GitResult.ok("Deleted untracked " + path)
                    : GitResult.failed("Could not delete untracked " + path + ": " + cleaned.stderr());
        }

        var res = repo.runner.run(GitCommand.of("restore", "--worktree").paths(path).args());
        if (!res.isOk()) {
            res = repo.runner.run(GitCommand.of("checkout").paths(path).args());
        }
        return res.isOk()
                ? GitResult.ok("Discarded changes to " + path)
                : GitResult.failed(res.stderr());
    }

    /** Whether the index knows about {@code path}. */
    boolean isTracked(String path) {
        var res = repo.runner.run(GitCommand.of("ls-files", "--error-unmatch").paths(path).args());
        return res.isOk() && !res.trimmedStdout().isEmpty();
    }

    public GitResult commit(String message, boolean amend) {
        return commit(message, amend, false);
    }

    public GitResult commit(String message, boolean amend, boolean skipHooks) {
        repo.ensureOpen();
        if (message == null || message.isBlank()) {
            return GitResult.failed("Commit message cannot be empty");
        }
        List<String> args = new java.util.ArrayList<>();
        args.add("commit");
        if (amend) args.add("--amend");
        if (skipHooks) args.add("--no-verify");
        // On stdin, never as `-m <message>`. On Windows the JDK does not escape a quote
        // inside an argument, so `Support 12" display` split into a commit titled
        // "Support 12" plus a pathspec - which committed an unstaged file and left out
        // the staged one - and `x" --author="...` became an option. The reword and squash
        // pre-fills put other people's text here. stdin also has no length limit.
        args.add("-F");
        args.add("-");
        var res = repo.runner.runWithInput(message.getBytes(StandardCharsets.UTF_8), args);
        return res.isOk() ? GitResult.ok("Committed: " + message.lines().findFirst().orElse("")) : GitResult.failed(res.stderr());
    }

    public String getHeadCommitMessage() {
        repo.ensureOpen();
        var res = repo.runner.run("log", "-1", "--format=%B");
        return res.isOk() ? res.trimmedStdout() : "";
    }

    /**
     * {@code git var GIT_COMMITTER_IDENT} - git's own answer, environment and includes
     * and all - without its timestamp. {@code user.useConfigOnly} makes git refuse
     * rather than fall back to {@code <osuser>@<hostname>}, which is what the JGit
     * engine does too.
     */
    public String getCommitIdentity() {
        repo.ensureOpen();
        var res = repo.runner.run("-c", "user.useConfigOnly=true", "var", "GIT_COMMITTER_IDENT");
        String ident = res.trimmedStdout();
        int end = ident.lastIndexOf('>');
        return res.isOk() && end > 0 ? ident.substring(0, end + 1) : null;
    }

    /**
     * {@code commit.template}'s contents. {@code --path} has git expand {@code ~} the way
     * it does for its own use of the setting; a relative result is relative to the work
     * tree, where this runs.
     */
    public String getCommitTemplate() {
        repo.ensureOpen();
        var res = repo.runner.run("config", "--path", "--get", "commit.template");
        if (!res.isOk() || res.trimmedStdout().isEmpty()) return "";
        File file = new File(res.trimmedStdout());
        if (!file.isAbsolute()) file = new File(repo.workTree, res.trimmedStdout());
        try {
            return java.nio.file.Files.readString(file.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException | RuntimeException e) {
            LOGGER.log(java.util.logging.Level.FINE, "Could not read commit.template " + file, e);
            return "";
        }
    }

    public String getHeadHash() {
        repo.ensureOpen();
        var res = repo.runner.run("rev-parse", "HEAD");
        return res.isOk() ? res.trimmedStdout() : null;
    }

    public String getLocalConfig(String key) {
        repo.ensureOpen();
        if (key == null || key.isBlank() || key.startsWith("-")) return null;
        var res = repo.runner.run("config", "--local", "--get", key);
        if (!res.isOk()) return null;
        // One trailing newline is git's; anything else is the value's own.
        String value = res.stdout();
        if (value.endsWith("\r\n")) return value.substring(0, value.length() - 2);
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
    }

    public GitResult setLocalConfig(String key, String value) {
        repo.ensureOpen();
        if (key == null || key.isBlank() || key.startsWith("-") || key.indexOf('.') <= 0) {
            return GitResult.failed("'" + key + "' is not a configuration key");
        }
        if (value == null) {
            var res = repo.runner.run("config", "--local", "--unset-all", key);
            // Exit 5: there was nothing to unset, which is the state asked for.
            return res.isOk() || res.exitCode() == 5
                    ? GitResult.ok("Removed " + key) : GitResult.failed(res.stderr());
        }
        var res = repo.runner.run("config", "--local", "--replace-all", key, value);
        return res.isOk() ? GitResult.ok("Set " + key) : GitResult.failed(res.stderr());
    }

    public GitResult setLocalIdentity(String name, String email, String signingKey, boolean gpgSign) {
        repo.ensureOpen();
        var nameRes = repo.runner.run("config", "user.name", name == null ? "" : name);
        if (!nameRes.isOk()) return GitResult.failed(nameRes.stderr());
        var emailRes = repo.runner.run("config", "user.email", email == null ? "" : email);
        if (!emailRes.isOk()) return GitResult.failed(emailRes.stderr());

        if (signingKey == null || signingKey.isBlank()) {
            // --unset succeeds even if the key was never set; a stderr in that case is
            // not a real failure, so its exit code alone would misreport success as
            // failure - only a signingkey that is actually still set afterwards matters.
            repo.runner.run("config", "--unset", "user.signingkey");
        } else {
            var keyRes = repo.runner.run("config", "user.signingkey", signingKey.trim());
            if (!keyRes.isOk()) return GitResult.failed(keyRes.stderr());
        }

        var signRes = repo.runner.run("config", "commit.gpgsign", Boolean.toString(gpgSign));
        if (!signRes.isOk()) return GitResult.failed(signRes.stderr());

        return GitResult.ok("Applied identity " + (name == null ? "" : name)
                + (email == null || email.isBlank() ? "" : " <" + email + ">") + " to this repository");
    }

    /** Puts one hunk of {@code path} back the way it was, in the working tree. */
    public GitResult revertHunk(String path, HunkEdit edit) {
        repo.ensureOpen();
        Path target;
        try {
            target = WorktreePaths.resolveForWrite(repo.workTree, path);
        } catch (IllegalArgumentException e) {
            return GitResult.failed("Could not revert hunk: " + e.getMessage());
        }
        if (!Files.isRegularFile(target)) {
            return GitResult.failed(path + " is not in the working tree");
        }
        if (indexEntryOf(path).unmerged()) {
            return GitResult.failed(GitService.unmergedRefusal(path));
        }
        try {
            byte[] originalBytes = Files.readAllBytes(target);
            HunkPatcher.BytesResult patched = HunkPatcher.apply(originalBytes, edit);
            if (!patched.applied()) {
                return GitResult.failed(HunkPatcher.explain(patched.refusal(), path, false));
            }
            Files.write(target, patched.content());
            return GitResult.ok("Reverted hunk in " + path + patched.describeOffset());
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not revert a hunk in " + path, e);
            return GitResult.failed("Could not revert hunk: " + e.getMessage());
        }
    }

    /**
     * Rewrites part of the <em>index</em> copy of {@code path}, leaving the working tree
     * alone. This is what stages or unstages a single hunk.
     *
     * <p>Whole-file staging copies the working tree over the index; partial staging
     * cannot, because the index has to end up holding something that exists in neither
     * HEAD nor the working tree. So the index blob is read with {@code cat-file}, patched
     * in memory, written back as a new blob with {@code hash-object -w}, and the index
     * entry repointed at it with {@code update-index --cacheinfo} — the same plumbing
     * {@code git add -p} uses.
     *
     * <p>A file legitimately appears in both the staged and unstaged lists afterwards;
     * that is what partial staging means.
     */
    public GitResult applyToIndex(String path, HunkEdit edit, String action) {
        repo.ensureOpen();
        try {
            CliParsers.IndexEntry entry = indexEntryOf(path);
            if (entry.unmerged()) {
                return GitResult.failed(GitService.unmergedRefusal(path));
            }
            byte[] originalBytes = entry.exists() ? repo.diffs.getConflictStageBytes(path, 0) : new byte[0];
            HunkPatcher.BytesResult patched = HunkPatcher.apply(originalBytes, edit);
            if (!patched.applied()) {
                return GitResult.failed(HunkPatcher.explain(patched.refusal(), path, true));
            }

            byte[] content = patched.content();
            if (content.length == 0 && edit.removesPath()) {
                // Every line of a deletion staged, or of an addition unstaged: the path
                // leaves the index, as `git rm --cached` would, rather than staying as an
                // empty file nobody asked for.
                var removed = repo.runner.run(GitCommand.of("update-index", "--force-remove").paths(path).args());
                if (!removed.isOk()) {
                    return GitResult.failed("Could not update the index: " + removed.stderr());
                }
                return GitResult.ok(action + " in " + path + " - removed from the index");
            }
            var written = repo.runner.runWithInput(content,
                    List.of("hash-object", "-w", "-t", "blob", "--stdin"));
            if (!written.isOk()) {
                return GitResult.failed("Could not write the patched blob: " + written.stderr());
            }
            String blob = written.trimmedStdout();

            // Keep whatever mode the entry already had; a new path takes the mode the
            // diff's preamble gave it. `--cacheinfo` builds a fresh entry, so an
            // intent-to-add flag does not survive it - the content is staged for real.
            String mode = entry.exists() ? entry.mode() : edit.createModeOrRegular();
            var updated = repo.runner.run("update-index", "--cacheinfo", mode + "," + blob + "," + path);
            if (!updated.isOk()) {
                // --add is needed when the path is not in the index yet, but passing it
                // unconditionally would resurrect an entry a concurrent operation removed.
                updated = repo.runner.run("update-index", "--add", "--cacheinfo",
                        mode + "," + blob + "," + path);
            }
            if (!updated.isOk()) {
                return GitResult.failed("Could not update the index: " + updated.stderr());
            }
            return GitResult.ok(action + " in " + path + patched.describeOffset());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not patch the index copy of " + path, e);
            return GitResult.failed("Could not update the index: " + e.getMessage());
        }
    }

    /**
     * The index entry for {@code path}, from {@code ls-files --stage}.
     *
     * <p>Matched on the exact path rather than trusting the pathspec: {@code ls-files} of
     * a directory name lists everything under it.
     */
    private CliParsers.IndexEntry indexEntryOf(String path) {
        var res = repo.runner.run(GitCommand.of("ls-files", "--stage", "-z").paths(path).args());
        if (!res.isOk()) {
            return new CliParsers.IndexEntry(false, "100644", false);
        }
        return CliParsers.parseIndexEntry(res.stdout(), path);
    }
}
