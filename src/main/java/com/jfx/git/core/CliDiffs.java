package com.jfx.git.core;

import com.jfx.git.core.HistoryOps.FileChange;
import com.jfx.git.model.CommitInfo;

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
 * Diffs, blobs and working-tree content on the native engine.
 *
 * <p>Package-private: reached only through {@link NativeGitBackend}, which is the
 * engine's public face.
 */
final class CliDiffs {

    private static final Logger LOGGER = Logger.getLogger(NativeGitBackend.class.getName());

    private final CliRepo repo;

    CliDiffs(CliRepo repo) {
        this.repo = repo;
    }

    /**
     * The diff one commit made to one file.
     *
     * <p>{@code -m --first-parent} is what makes a merge commit show anything. Plain
     * {@code git show} emits a <em>combined</em> diff for a merge, and a combined diff
     * narrowed to one path is almost always empty - so selecting a file of a merge commit
     * rendered a blank pane. Diffing against the first parent is what JGit does and what
     * the file list of that same merge was built from.
     */
    public byte[] getDiffBytesForFileInCommit(String commitHash, String filePath, boolean ignoreWhitespace) {
        return getDiffBytesForFileInCommit(commitHash, filePath, null, ignoreWhitespace);
    }

    /** With both paths in the pathspec, {@code -M} can pair a rename's two sides. */
    public byte[] getDiffBytesForFileInCommit(String commitHash, String filePath, String oldPath,
                                              boolean ignoreWhitespace) {
        repo.ensureOpen();
        // --format= : the diff alone. Without it the commit's header and message came
        // first, in whatever format.pretty the user likes, and the diff parser read them as
        // part of the diff - a message line "Binary files a and b differ" or one starting
        // with "@@" turned into a binary marker or a hunk header that was never there.
        GitCommand show = GitCommand.of("show", "--format=", "--no-color", "--no-ext-diff",
                "-U3", "-p", "-m", "--first-parent", "-M")
                .optsIf(ignoreWhitespace, "-w")
                .revs(commitHash)
                .paths(filePath);
        if (oldPath != null && !oldPath.equals(filePath)) show.paths(oldPath);
        var res = repo.runner.run(show.args());
        // A failed diff still has to reach the viewer: returning empty stdout and
        // dropping stderr left the user with a blank pane and no way to tell why.
        return res.isOk() ? res.stdoutBytes() : diffErrorBytes(res);
    }

    /** A failed diff travels to the viewer as bytes, the way every diff path does. */
    private static byte[] diffErrorBytes(NativeGitRunner.Result res) {
        String message = res.stderr() == null || res.stderr().isBlank()
                ? "git exited with " + res.exitCode() : res.stderr();
        return ("Error getting diff: " + message).getBytes(StandardCharsets.UTF_8);
    }

    public byte[] getDiffBytesForFileInCommit(CommitInfo commit, String filePath, boolean ignoreWhitespace) {
        return getDiffBytesForFileInCommit(commit.getHash(), filePath, ignoreWhitespace);
    }

    /**
     * Options for a diff whose hunks may be staged, unstaged or reverted.
     *
     * <p>{@code --no-ext-diff}: with {@code diff.external} set (difftastic, say) git hands
     * the whole diff to that tool, and what comes back has no hunks to act on.
     * {@code --no-textconv}: a textconv filter shows a readable rendering of the file,
     * and a hunk of that rendering cannot be applied to the file's real bytes.
     * {@code -U3}: the hunk patcher's context is what git's default gives, and a user's
     * {@code diff.context=0} produced hunks it placed a line early.
     */
    private static final List<String> APPLICABLE_DIFF_OPTIONS =
            List.of("--no-color", "--no-ext-diff", "--no-textconv", "-U3", "-p");

    /**
     * The unstaged diff of {@code filePath}; for an untracked file, its whole content as
     * an addition.
     *
     * <p>{@code git diff} says nothing about a path the index has never seen, so an
     * untracked file showed an empty pane and could not be line-staged - where the JGit
     * engine shows it as new. {@code diff --no-index} against the null device is how git
     * itself spells "this file, all added".
     */
    public byte[] getUnstagedDiffBytes(String filePath, boolean ignoreWhitespace) {
        repo.ensureOpen();
        var res = repo.runner.run(GitCommand.of("diff").opts(APPLICABLE_DIFF_OPTIONS)
                .optsIf(ignoreWhitespace, "-w").paths(filePath).args());
        if (!res.isOk()) return diffErrorBytes(res);
        if (res.stdoutBytes().length == 0 && isUntrackedFile(filePath)) {
            return untrackedDiffBytes(filePath, ignoreWhitespace);
        }
        return res.stdoutBytes();
    }

    /** A regular file on disk that the index does not know. */
    private boolean isUntrackedFile(String path) {
        try {
            Path file = WorktreePaths.resolveForWrite(repo.workTree, path);
            return Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !repo.staging.isTracked(path);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private byte[] untrackedDiffBytes(String filePath, boolean ignoreWhitespace) {
        var res = repo.runner.run(GitCommand.of("diff", "--no-index").opts(APPLICABLE_DIFF_OPTIONS)
                .optsIf(ignoreWhitespace, "-w").paths("/dev/null", filePath).args());
        // --no-index implies --exit-code: 1 means "there are differences", which for a
        // file compared with nothing is the expected answer, not a failure.
        return res.exitCode() == 0 || res.exitCode() == 1 ? res.stdoutBytes() : diffErrorBytes(res);
    }

    public byte[] getStagedDiffBytes(String filePath, boolean ignoreWhitespace) {
        repo.ensureOpen();
        var res = repo.runner.run(GitCommand.of("diff", "--cached").opts(APPLICABLE_DIFF_OPTIONS)
                .optsIf(ignoreWhitespace, "-w").paths(filePath).args());
        if (!res.isOk()) return diffErrorBytes(res);

        // Filtered to the new path alone, a staged rename diffs as a whole new file: every
        // line an addition, and "Unstage hunk" offering to empty the file rather than undo
        // the edit. Paired with the path it came from it diffs the way git shows a staged
        // rename - only the lines that changed. Asked only for a path that is an addition,
        // so an ordinary modified file costs nothing extra.
        String oldPath = res.stdout().contains("\nnew file mode ") ? stagedRenameSource(filePath) : null;
        if (oldPath == null) return res.stdoutBytes();
        // The same options as the diff it replaces: its hunks are the ones "Unstage hunk"
        // applies, so an external diff tool, a textconv driver or a diff.context setting
        // must not reach them here either.
        var paired = repo.runner.run(GitCommand.of("diff", "--cached").opts(APPLICABLE_DIFF_OPTIONS)
                .opts("-M").optsIf(ignoreWhitespace, "-w").paths(oldPath, filePath).args());
        return paired.isOk() ? paired.stdoutBytes() : res.stdoutBytes();
    }

    /** The path a staged rename onto {@code newPath} came from, or {@code null}. */
    private String stagedRenameSource(String newPath) {
        var res = repo.runner.run("diff", "--cached", "-M", "--name-status", "-z", "--diff-filter=R");
        if (!res.isOk()) return null;
        // "R096\0old\0new\0" per rename
        String[] fields = res.stdout().split("\0");
        for (int i = 0; i + 2 < fields.length; i += 3) {
            if (fields[i].startsWith("R") && fields[i + 2].equals(newPath)) {
                return fields[i + 1];
            }
        }
        return null;
    }

    public List<String> getFilesInCommit(String commitHash) {
        repo.ensureOpen();
        // Against the first parent, as getChangesInCommit is: given a single merge
        // commit diff-tree prints nothing, so a merge listed no files at all.
        var res = againstFirstParent("--name-only", commitHash);
        if (!res.isOk()) return List.of();
        return CliParsers.nulSeparated(res.stdout());
    }

    /** Direct top-level tree entries that are directories at the named revision. */
    public List<String> getTopLevelDirectoriesAtCommit(String commitRef) {
        repo.ensureOpen();
        if (commitRef == null) return List.of();
        var res = repo.runner.run(GitCommand.of("ls-tree", "-d", "--name-only", "-z")
                .revs(commitRef).args());
        if (!res.isOk()) return List.of();
        return CliParsers.nulSeparated(res.stdout()).stream().sorted().toList();
    }

    public List<String> getFilesInCommit(CommitInfo commit) {
        return getFilesInCommit(commit.getHash());
    }

    /**
     * Status and line counts for everything a commit touched.
     *
     * <p>Two calls rather than one, because git will not emit both formats at once: given
     * {@code --numstat --name-status} it silently honours the last one and drops the other,
     * which is the kind of thing that looks like it worked. The walk is identical and
     * ordered identically both times, so the results are merged on path.
     *
     * <p>{@code -z} throughout: without it git quotes paths containing spaces or non-ASCII
     * and the parser would have to unquote them.
     *
     * <h2>Against the first parent, named as a tree</h2>
     * Given a single merge commit, {@code diff-tree} prints nothing at all - so a merge,
     * and every stash (a stash is a merge of the index into HEAD), showed an empty file
     * list. {@code -m} is no cure: it prints the diff against <em>each</em> parent, and
     * {@code diff-tree} ignores {@code --first-parent}, which is a walk option it has no
     * walk to apply to. {@code --diff-merges=first-parent} would do it, from git 2.31 on.
     * Naming the first parent as the left-hand tree works on every version, and is what
     * the JGit engine and the per-file diff beside the list both use. A root commit has no
     * {@code ^1}, so it falls back to {@code --root} against the empty tree.
     */
    public List<FileChange> getChangesInCommit(String commitHash) {
        repo.ensureOpen();
        var res = againstFirstParent("--name-status", commitHash);
        if (!res.isOk()) return List.of();
        var counted = againstFirstParent("--numstat", commitHash);
        return CliParsers.parseNameStatus(res.stdout(),
                counted.isOk() ? CliParsers.parseNumstat(counted.stdout()) : Map.of());
    }

    /** {@code diff-tree} of a commit against its first parent, or a root commit against nothing. */
    private NativeGitRunner.Result againstFirstParent(String format, String commitHash) {
        var res = repo.runner.run(GitCommand.of("diff-tree", "-r", "-M", format, "-z")
                .revs(commitHash + "^1", commitHash).args());
        if (res.isOk()) return res;
        return repo.runner.run(GitCommand.of("diff-tree", "--no-commit-id", "-r", "-M", "--root", format, "-z")
                .revs(commitHash).args());
    }

    /**
     * What differs between two commits, the way {@link #getChangesInCommit} reports one.
     *
     * <p>{@code diff-tree} with two trees rather than porcelain {@code diff}: the plumbing
     * command reads none of the user's diff configuration, so a {@code diff.relative} or
     * {@code diff.renames=false} set for their own terminal cannot change what this lists.
     */
    public List<FileChange> getChangesBetweenCommits(String fromHash, String toHash) {
        repo.ensureOpen();
        var counted = repo.runner.run(GitCommand.of("diff-tree", "-r", "-M", "--numstat", "-z")
                .revs(fromHash, toHash).args());
        var res = repo.runner.run(GitCommand.of("diff-tree", "-r", "-M", "--name-status", "-z")
                .revs(fromHash, toHash).args());
        if (!res.isOk()) return List.of();
        return CliParsers.parseNameStatus(res.stdout(),
                counted.isOk() ? CliParsers.parseNumstat(counted.stdout()) : Map.of());
    }

    /**
     * Line counts for uncommitted work.
     *
     * <p>Plain {@code diff --numstat} against the index or against HEAD, which is the
     * command the numbers are named after. The paths the caller passed are not sent on to
     * git: it already leaves ignored and untracked files out of a diff, so the walk is
     * bounded without help, and a repository with a thousand changed files would
     * otherwise build a command line long enough for the OS to refuse it.
     */
    public Map<String, int[]> getWorkingTreeLineCounts(boolean staged, List<String> paths) {
        repo.ensureOpen();
        if (paths == null || paths.isEmpty()) {
            return Map.of();
        }
        // Porcelain diff reads the user's diff configuration, so the counts are pinned:
        // --no-renames, as the JGit engine counts (diff.renames decided it before, so a
        // staged rename was one row for some users and two for others); --no-relative for
        // diff.relative, which keys the paths from a subdirectory; --no-textconv, so a
        // textconv driver's rendering is not what gets counted.
        List<String> args = new ArrayList<>(List.of("diff", "--numstat", "-z", "--no-renames",
                "--no-relative", "--no-textconv", "--no-ext-diff"));
        if (staged) args.add("--cached");
        var counted = repo.runner.run(args);
        if (!counted.isOk()) return Map.of();
        return CliParsers.parseNumstat(counted.stdout());
    }

    public List<String> getFilesBetweenCommits(String fromHash, String toHash) {
        repo.ensureOpen();
        // Two dots: what differs between the two end points, not what either branch did.
        // -M so a rename reads as one row here as it does on the JGit engine, which has
        // rename detection on; without it the same compare shows an add and a delete.
        var res = repo.runner.run(GitCommand.of("diff", "--name-only", "-M", "-z", "--no-ext-diff")
                .revs(fromHash + ".." + toHash).args());
        if (!res.isOk()) return List.of();
        return CliParsers.nulSeparated(res.stdout());
    }

    public byte[] getDiffBytesBetweenCommits(String fromHash, String toHash,
                                             String filePath, boolean ignoreWhitespace) {
        return getDiffBytesBetweenCommits(fromHash, toHash, filePath, null, ignoreWhitespace);
    }

    public byte[] getDiffBytesBetweenCommits(String fromHash, String toHash, String filePath,
                                             String oldPath, boolean ignoreWhitespace) {
        repo.ensureOpen();
        GitCommand diff = GitCommand.of("diff", "--no-color", "--no-ext-diff", "-U3", "-p", "-M")
                .optsIf(ignoreWhitespace, "-w")
                .revs(fromHash + ".." + toHash)
                .paths(filePath);
        if (oldPath != null && !oldPath.equals(filePath)) diff.paths(oldPath);
        var res = repo.runner.run(diff.args());
        return res.isOk() ? res.stdoutBytes() : diffErrorBytes(res);
    }

    public String getFileContentAtCommit(String commitHash, String filePath) {
        byte[] b = getFileBytesAtCommit(commitHash, filePath);
        return TextEncoding.GUESS.decode(b);
    }

    public String getFileContentAtCommit(CommitInfo commit, String filePath) {
        return getFileContentAtCommit(commit.getHash(), filePath);
    }

    /**
     * The blob at {@code filePath} in {@code commitHash}; empty when the commit has no
     * file there, as on the JGit engine.
     *
     * @throws GitOperationException when git could not be asked - see {@link #readBlobs}
     */
    public byte[] getFileBytesAtCommit(String commitHash, String filePath) {
        repo.ensureOpen();
        if (commitHash == null || filePath == null) return new byte[0];
        return orEmpty(readBlobs(commitHash + ":" + filePath)[0]);
    }

    /**
     * Reads blobs by revision expression ({@code <commit>:<path>}, {@code :<stage>:<path>})
     * with one {@code git cat-file --batch}, which answers each one as a blob, as
     * "missing", or as some other type - so the three can be told apart.
     *
     * <p>This used to be porcelain {@code show <rev>:<path>}, one process per side, with
     * stdout returned whatever the exit code. A path the commit does not have, a read
     * killed at its time limit and an empty file all came back as zero bytes, and a
     * directory came back as git's listing of it, shown as the file's content.
     * {@code cat-file} is plumbing: the stored bytes, as the JGit engine reads them, with
     * none of porcelain's configuration in the way.
     *
     * @return one entry per spec, {@code null} where there is no blob at that spec
     * @throws GitOperationException when git itself failed - timed out, was cancelled,
     *         could not start - which must not read as "no such file": the hunk staging
     *         that patches an index blob would otherwise patch nothing and stage the result
     */
    private byte[][] readBlobs(String... specs) {
        NativeGitRunner git = repo.runner;
        byte[][] blobs = new byte[specs.length][];
        StringBuilder batch = new StringBuilder();
        List<Integer> batched = new ArrayList<>();
        for (int i = 0; i < specs.length; i++) {
            String spec = specs[i];
            // --batch reads one name per line and drops a trailing CR, so a path that
            // contains a line break, or ends in a CR, cannot be named there. Rare enough
            // that it costs its own two processes.
            if (spec.indexOf('\n') >= 0 || spec.endsWith("\r")) {
                blobs[i] = readBlobAlone(git, spec);
            } else {
                batch.append(spec).append('\n');
                batched.add(i);
            }
        }
        if (batched.isEmpty()) return blobs;

        var res = git.runWithInput(batch.toString().getBytes(StandardCharsets.UTF_8),
                List.of("cat-file", "--batch"));
        if (!res.isOk()) throw readFailed(res);
        byte[][] read;
        try {
            read = CliParsers.parseCatFileBatch(res.stdoutBytes(), batched.size());
        } catch (IllegalArgumentException malformed) {
            throw readFailed(res);
        }
        for (int i = 0; i < batched.size(); i++) {
            blobs[batched.get(i)] = read[i];
        }
        return blobs;
    }

    /** One blob by plain {@code cat-file}, for a name {@code --batch} cannot carry. */
    private static byte[] readBlobAlone(NativeGitRunner git, String spec) {
        var type = git.run(GitCommand.of("cat-file", "-t").revs(spec).args());
        if (!type.isOk() || !type.trimmedStdout().equals("blob")) return null;
        var blob = git.run(GitCommand.of("cat-file", "blob").revs(spec).args());
        if (!blob.isOk()) throw readFailed(blob);
        return blob.stdoutBytes();
    }

    private static GitOperationException readFailed(NativeGitRunner.Result res) {
        String why = res.stderr() == null || res.stderr().isBlank()
                ? "git exited with " + res.exitCode() : res.stderr();
        return new GitOperationException("Could not read file content: " + why);
    }

    private static byte[] orEmpty(byte[] blob) {
        return blob == null ? new byte[0] : blob;
    }

    public byte[] getFileBytesAtCommit(CommitInfo commit, String filePath) {
        return getFileBytesAtCommit(commit.getHash(), filePath);
    }

    public GitResult restoreFileFromCommit(String commitHash, String filePath) {
        repo.ensureOpen();
        // git restore is the native spelling of "write that blob to the work tree",
        // and it recreates deleted files along with their directories.
        //
        // No fallback. There used to be one to `checkout <commit> -- <path>` plus a
        // `reset` of the index entry, taken on any failure: but restore exists in every
        // git this engine runs on, so whatever made it fail - a path not in that commit,
        // a commit that does not resolve - failed the checkout too, or worse, half-worked
        // and left the index rewritten when the reset after it did not.
        var res = repo.runner.run(GitCommand.of("restore", "--worktree", "--source=" + commitHash)
                .paths(filePath).args());
        String shortHash = commitHash.length() >= 7 ? commitHash.substring(0, 7) : commitHash;
        return res.isOk()
                ? GitResult.ok("Restored " + filePath + " from " + shortHash)
                : GitResult.failed("Could not restore " + filePath + ": " + res.stderr());
    }

    /**
     * {@link #restoreFileFromCommit(String, String)}, written into {@code intoPath}: the
     * file as it is called now, when it has been renamed since that commit.
     *
     * <p>{@code git restore} can only write a path back to itself, so a different
     * destination is read with {@code cat-file --filters --path=<intoPath>}: the blob as a
     * checkout of {@code intoPath} would write it - its line endings, smudge and LFS
     * filters - which is what restore writes for a path of its own. The index is not read
     * or touched.
     */
    public GitResult restoreFileFromCommit(String commitHash, String filePath, String intoPath) {
        if (intoPath.equals(filePath)) {
            return restoreFileFromCommit(commitHash, filePath);
        }
        repo.ensureOpen();
        Path target;
        try {
            // The policy writeWorkingTreeFile follows; see WorktreePaths.
            target = WorktreePaths.resolveForWrite(repo.workTree, intoPath);
        } catch (IllegalArgumentException e) {
            return GitResult.failed("Could not restore " + intoPath + ": " + e.getMessage());
        }
        String spec = commitHash + ":" + filePath;
        var res = repo.runner.run(GitCommand.of("cat-file", "--filters", "--path=" + intoPath)
                .revs(spec).args());
        if (!res.isOk()) {
            // git words a missing path several ways - "does not exist in", "exists on disk,
            // but not in" - so it is asked plainly, and answered as the JGit engine does.
            if (readBlobs(spec)[0] == null) {
                return GitResult.failed(filePath + " does not exist in " + commitHash);
            }
            return GitResult.failed("Could not restore " + intoPath + ": " + res.stderr());
        }
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(target, res.stdoutBytes());
        } catch (IOException e) {
            return GitResult.failed("Could not restore " + intoPath + ": " + e.getMessage());
        }
        String shortHash = commitHash.length() >= 7 ? commitHash.substring(0, 7) : commitHash;
        return GitResult.ok("Restored " + intoPath + " from " + shortHash + " (" + filePath + " there)");
    }

    /** Both sides in one {@code cat-file}; see {@link #readBlobs}. */
    public byte[][] getCommitSides(CommitInfo commit, String path) {
        repo.ensureOpen();
        String parentHash = commit.getParentHashes().isEmpty() ? null : commit.getParentHashes().get(0);
        if (parentHash == null) {
            return new byte[][]{new byte[0], getFileBytesAtCommit(commit.getHash(), path)};
        }
        byte[][] sides = readBlobs(parentHash + ":" + path, commit.getHash() + ":" + path);
        return new byte[][]{orEmpty(sides[0]), orEmpty(sides[1])};
    }

    /**
     * The "before" and "after" bytes of {@code path} for a side-by-side view.
     *
     * <p>"Before" is what git compares against - the index copy, falling back to HEAD -
     * not HEAD unconditionally. For a partially staged file those are different blobs,
     * and using HEAD showed a different comparison here than on the JGit engine.
     */
    public byte[][] getWorkingTreeSides(String path) {
        repo.ensureOpen();
        // Whether the index has the path, not whether its blob has bytes: a file staged
        // empty is "before" as an empty file, and taking zero bytes for "not staged"
        // showed HEAD's copy instead, as if the emptying had not been staged.
        byte[][] blobs = readBlobs(":0:" + path, "HEAD:" + path);
        byte[] before = blobs[0] != null ? blobs[0] : orEmpty(blobs[1]);
        return new byte[][]{before, getWorkingTreeBytes(path)};
    }

    public String getConflictStage(String path, int stage) {
        return TextEncoding.GUESS.decode(getConflictStageBytes(path, stage));
    }

    /** Raw bytes of one index stage, so a caller that rewrites the file keeps its encoding. */
    public byte[] getConflictStageBytes(String path, int stage) {
        repo.ensureOpen();
        return orEmpty(readBlobs(":" + stage + ":" + path)[0]);
    }

    /**
     * Writes resolved content back to the working tree and stages it.
     *
     * <p>Encoded the way the conflicted file on disk was read, not as UTF-8: the file is
     * rewritten whole, so a non-UTF-8 one would come back as replacement characters
     * everywhere the user did not touch.
     */
    public GitResult resolveConflict(String path, String content) {
        repo.ensureOpen();
        byte[] onDisk = getWorkingTreeBytes(path);
        GitResult written = writeWorkingTreeFile(path, TextEncoding.encodeForEdit(content, onDisk));
        if (!written.isOk()) return written;
        return repo.staging.stageFile(path);
    }

    public String getWorkingTreeContent(String path) {
        return TextEncoding.GUESS.decode(getWorkingTreeBytes(path));
    }

    public byte[] getWorkingTreeBytes(String path) {
        repo.ensureOpen();
        try {
            Path file = WorktreePaths.resolveForWrite(repo.workTree, path);
            if (!Files.exists(file)) return new byte[0];
            return Files.readAllBytes(file);
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Refused to read " + path + ": " + e.getMessage());
            return new byte[0];
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public GitResult writeWorkingTreeFile(String path, byte[] bytes) {
        repo.ensureOpen();
        Path file;
        try {
            // Same policy as the JGit engine: the two must not disagree about which paths
            // the editor is allowed to write. See WorktreePaths.
            file = WorktreePaths.resolveForWrite(repo.workTree, path);
        } catch (IllegalArgumentException e) {
            return GitResult.failed("Failed to write " + path + ": " + e.getMessage());
        }
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(file, bytes == null ? new byte[0] : bytes);
            return GitResult.ok("Wrote " + path);
        } catch (IOException e) {
            return GitResult.failed("Failed to write " + path + ": " + e.getMessage());
        }
    }

    public byte[] getStagedBytes(String path) {
        return getConflictStageBytes(path, 0);
    }
}
