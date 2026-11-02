package com.jfx.git.core;

import com.jfx.git.model.Snapshot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Working-tree snapshots on the native engine; see {@link WorkingTreeSnapshots} for what
 * they are for.
 *
 * <p>Git's own plumbing, pointed at a temporary index through {@code GIT_INDEX_FILE}:
 * the real index is copied (so its stat cache spares {@code add} from hashing every
 * file again), {@code add -A} brings the copy up to the working tree, and
 * {@code write-tree} and {@code commit-tree} turn it into a commit. The real index, the
 * working tree, HEAD and the stash are never written.
 *
 * <p>Package-private: reached only through {@link NativeGitBackend}, which is the
 * engine's public face.
 */
final class CliSnapshots {

    private static final Logger LOGGER = Logger.getLogger(NativeGitBackend.class.getName());

    private final CliRepo repo;

    /** The git directory whose legacy snapshots have been moved across, so it happens once. */
    private volatile File adopted;

    CliSnapshots(CliRepo repo) {
        this.repo = repo;
    }

    /**
     * Moves snapshots taken before the rename from {@code refs/jfxgit/snapshots/} to
     * {@link WorkingTreeSnapshots#REF_PREFIX}, in one transaction: each create is
     * matched by a delete that insists on the same commit, so a ref that changed
     * meanwhile leaves everything where it was.
     */
    private void adoptLegacySnapshots() {
        File gitDir = repo.gitDir;
        if (gitDir == null || gitDir.equals(adopted)) return;
        adopted = gitDir;
        var res = repo.runner.run("for-each-ref", "--format=%(refname) %(objectname)",
                WorkingTreeSnapshots.LEGACY_REF_PREFIX);
        if (!res.isOk() || res.trimmedStdout().isEmpty()) return;
        StringBuilder commands = new StringBuilder();
        for (String line : res.trimmedStdout().split(CliParsers.LINE_BREAK)) {
            int space = line.indexOf(' ');
            if (space < 0 || !line.startsWith(WorkingTreeSnapshots.LEGACY_REF_PREFIX)) continue;
            String legacy = line.substring(0, space);
            String id = legacy.substring(WorkingTreeSnapshots.LEGACY_REF_PREFIX.length());
            if (!WorkingTreeSnapshots.isId(id) || exists(repo.runner, id)) continue;
            String hash = line.substring(space + 1).strip();
            commands.append("create ").append(WorkingTreeSnapshots.refName(id)).append(' ').append(hash).append('\n')
                    .append("delete ").append(legacy).append(' ').append(hash).append('\n');
        }
        if (commands.isEmpty()) return;
        var moved = repo.runner.runWithInput(commands.toString().getBytes(StandardCharsets.UTF_8),
                List.of("update-ref", "--stdin"));
        if (!moved.isOk()) {
            LOGGER.warning("Could not move the old snapshots across: " + moved.stderr().strip());
        }
    }

    public String snapshotWorkingTree(String reason) {
        repo.ensureOpen();
        adoptLegacySnapshots();
        NativeGitRunner git = repo.runner;

        // What add -A is about to take: too many untracked files and nothing is taken,
        // and a changed file too large to copy keeps its staged version.
        var status = git.run("status", "--porcelain=v1", "-z", "--untracked-files=all", "--no-renames");
        if (!status.isOk()) throw new GitOperationException("Could not take a snapshot: " + status.stderr());
        int untracked = 0;
        List<String> tooLarge = new ArrayList<>();
        for (String entry : status.stdout().split("\0")) {
            if (entry.length() < 4) continue;
            char x = entry.charAt(0);
            char y = entry.charAt(1);
            String path = entry.substring(3);
            boolean isUntracked = x == '?' && y == '?';
            if (isUntracked && ++untracked > WorkingTreeSnapshots.MAX_UNTRACKED_FILES) {
                LOGGER.info("No snapshot: more than " + WorkingTreeSnapshots.MAX_UNTRACKED_FILES
                        + " untracked files");
                return null;
            }
            if (isUntracked || (y != ' ' && y != 'D')) {
                File file = new File(repo.workTree, path);
                if (file.isFile() && file.length() > WorkingTreeSnapshots.MAX_FILE_BYTES) tooLarge.add(path);
            }
        }

        var headRes = git.run("rev-parse", "--verify", "--quiet", "HEAD^{commit}");
        String head = headRes.isOk() ? headRes.trimmedStdout() : null;

        File index;
        try {
            index = Files.createTempFile("lyra-snapshot-", ".index").toFile();
        } catch (IOException e) {
            throw new GitOperationException("Could not take a snapshot: " + e.getMessage(), e);
        }
        try {
            Map<String, String> env = Map.of("GIT_INDEX_FILE", index.getAbsolutePath());
            File realIndex = new File(repo.gitDir, "index");
            boolean copied = false;
            if (realIndex.isFile()) {
                Files.copy(realIndex.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING);
                copied = true;
            } else {
                seedFromHead(git, env, index, head);
            }
            var added = addAll(git, env, tooLarge);
            if (!added.isOk() && copied) {
                // A split index names its shared half relative to where it lives, so a copy
                // elsewhere cannot be read. Start over from HEAD: slower, the same answer.
                seedFromHead(git, env, index, head);
                added = addAll(git, env, tooLarge);
            }
            if (!added.isOk()) throw new GitOperationException("Could not take a snapshot: " + added.stderr());

            var written = git.runWithEnvironment(env, List.of("write-tree"));
            if (!written.isOk()) throw new GitOperationException("Could not take a snapshot: " + written.stderr());
            String tree = written.trimmedStdout();

            String headTree = null;
            if (head != null) {
                var res = git.run("rev-parse", "--verify", "--quiet", head + "^{tree}");
                headTree = res.isOk() ? res.trimmedStdout() : null;
            }
            List<String> changed = changedFiles(git, headTree, tree);
            if (changed.isEmpty()) return null;
            String[] newest = newestSnapshot(git);
            if (newest != null && tree.equals(newest[1])) return null;

            var symbolic = git.run("symbolic-ref", "--quiet", "--short", "HEAD");
            String branch = symbolic.isOk() ? symbolic.trimmedStdout() : "HEAD";

            List<String> commitArgs = new ArrayList<>(List.of("commit-tree", "--no-gpg-sign", tree));
            if (head != null) {
                commitArgs.add("-p");
                commitArgs.add(head);
            }
            commitArgs.add("-F");
            commitArgs.add("-");
            Map<String, String> identity = new HashMap<>();
            identity.put("GIT_AUTHOR_NAME", WorkingTreeSnapshots.AUTHOR_NAME);
            identity.put("GIT_AUTHOR_EMAIL", WorkingTreeSnapshots.AUTHOR_EMAIL);
            identity.put("GIT_COMMITTER_NAME", WorkingTreeSnapshots.AUTHOR_NAME);
            identity.put("GIT_COMMITTER_EMAIL", WorkingTreeSnapshots.AUTHOR_EMAIL);
            byte[] message = WorkingTreeSnapshots.message(reason, branch, changed.size())
                    .getBytes(StandardCharsets.UTF_8);
            var committed = git.runWithInputAndEnvironment(message, identity, commitArgs);
            if (!committed.isOk()) throw new GitOperationException("Could not take a snapshot: " + committed.stderr());
            String commit = committed.trimmedStdout();

            // "create" refuses a ref that already exists, so two snapshots in the same
            // millisecond cannot overwrite each other.
            for (int attempt = 0; attempt < 3; attempt++) {
                String id = WorkingTreeSnapshots.newId(candidate -> exists(git, candidate));
                var created = git.runWithInput(
                        ("create " + WorkingTreeSnapshots.refName(id) + " " + commit + "\n")
                                .getBytes(StandardCharsets.UTF_8),
                        List.of("update-ref", "--stdin"));
                if (created.isOk()) return id;
                if (attempt == 2) {
                    throw new GitOperationException("Could not record the snapshot: " + created.stderr());
                }
            }
            return null;
        } catch (IOException e) {
            throw new GitOperationException("Could not take a snapshot: " + e.getMessage(), e);
        } finally {
            index.delete();
            new File(index.getPath() + ".lock").delete();
        }
    }

    /** An index holding HEAD's tree, or none at all for an unborn branch. */
    private static void seedFromHead(NativeGitRunner git, Map<String, String> env, File index,
                                     String head) throws IOException {
        // An empty file is not an empty index - git refuses it as too small - so it goes,
        // and git creates the index itself.
        Files.deleteIfExists(index.toPath());
        if (head == null) return;
        var read = git.runWithEnvironment(env, List.of("read-tree", head));
        if (!read.isOk()) throw new GitOperationException("Could not take a snapshot: " + read.stderr());
    }

    /** {@code add -A}, leaving out the files too large to copy. */
    private static NativeGitRunner.Result addAll(NativeGitRunner git, Map<String, String> env,
                                                 List<String> tooLarge) {
        if (tooLarge.isEmpty()) {
            return git.runWithEnvironment(env, List.of("add", "-A"));
        }
        Map<String, String> magic = new HashMap<>(env);
        magic.putAll(NativeGitRunner.PATHSPEC_MAGIC);
        StringBuilder pathspecs = new StringBuilder(":/\0");
        for (String path : tooLarge) {
            pathspecs.append(":(exclude,literal)").append(path).append('\0');
        }
        return git.runWithInputAndEnvironment(pathspecs.toString().getBytes(StandardCharsets.UTF_8), magic,
                List.of("add", "-A", "--pathspec-from-file=-", "--pathspec-file-nul"));
    }

    /** The files that differ between {@code headTree} (or nothing) and {@code tree}. */
    private static List<String> changedFiles(NativeGitRunner git, String headTree, String tree) {
        var res = headTree == null
                ? git.run("ls-tree", "-r", "--name-only", "-z", tree)
                : git.run("diff-tree", "-r", "--name-only", "--no-renames", "-z", headTree, tree);
        if (!res.isOk()) throw new GitOperationException("Could not take a snapshot: " + res.stderr());
        List<String> files = new ArrayList<>();
        for (String path : res.stdout().split("\0")) {
            if (!path.isEmpty()) files.add(path);
        }
        return files;
    }

    private static boolean exists(NativeGitRunner git, String id) {
        return git.run("show-ref", "--verify", "--quiet", WorkingTreeSnapshots.refName(id)).isOk();
    }

    /** The newest snapshot as {@code {id, tree}}, or null when there is none. */
    private static String[] newestSnapshot(NativeGitRunner git) {
        var res = git.run("for-each-ref", "--format=%(refname) %(tree)", WorkingTreeSnapshots.REF_PREFIX);
        if (!res.isOk()) return null;
        String[] newest = null;
        long newestId = -1;
        for (String line : res.trimmedStdout().split(CliParsers.LINE_BREAK)) {
            int space = line.indexOf(' ');
            if (space < 0 || !line.startsWith(WorkingTreeSnapshots.REF_PREFIX)) continue;
            String id = line.substring(WorkingTreeSnapshots.REF_PREFIX.length(), space);
            if (!WorkingTreeSnapshots.isId(id)) continue;
            long value = Long.parseLong(id);
            if (value > newestId) {
                newestId = value;
                newest = new String[]{id, line.substring(space + 1).strip()};
            }
        }
        return newest;
    }

    public List<Snapshot> listSnapshots() {
        repo.ensureOpen();
        adoptLegacySnapshots();
        var res = repo.runner.run("for-each-ref",
                "--format=%(refname)%00%(objectname)%00%(contents)%1e", WorkingTreeSnapshots.REF_PREFIX);
        if (!res.isOk()) return List.of();
        List<Snapshot> snapshots = new ArrayList<>();
        for (String record : res.stdout().split("\u001e")) {
            String[] fields = record.stripLeading().split("\0", 3);
            if (fields.length < 3 || !fields[0].startsWith(WorkingTreeSnapshots.REF_PREFIX)) continue;
            String id = fields[0].substring(WorkingTreeSnapshots.REF_PREFIX.length());
            Snapshot snapshot = WorkingTreeSnapshots.parse(id, fields[1], fields[2]);
            if (snapshot != null) snapshots.add(snapshot);
        }
        snapshots.sort((a, b) -> Long.compare(Long.parseLong(b.id()), Long.parseLong(a.id())));
        return snapshots;
    }

    public GitResult deleteSnapshot(String id) {
        repo.ensureOpen();
        adoptLegacySnapshots();
        if (!WorkingTreeSnapshots.isId(id) || !exists(repo.runner, id)) {
            return GitResult.failed("There is no snapshot " + id);
        }
        var res = repo.runner.run("update-ref", "-d", WorkingTreeSnapshots.refName(id));
        return res.isOk()
                ? GitResult.ok("Deleted the snapshot")
                : GitResult.failed("Could not delete the snapshot: " + res.stderr());
    }

    /**
     * {@code git restore --worktree --overlay}: the snapshot's files written through the
     * same smudge and line-ending rules a checkout uses, the index untouched, and - in
     * overlay mode - nothing deleted that the snapshot does not have.
     */
    public GitResult restoreFromSnapshot(String id, List<String> paths) {
        repo.ensureOpen();
        adoptLegacySnapshots();
        NativeGitRunner git = repo.runner;
        if (!WorkingTreeSnapshots.isId(id)) return GitResult.failed("There is no snapshot " + id);
        var resolved = git.run("rev-parse", "--verify", "--quiet",
                WorkingTreeSnapshots.refName(id) + "^{commit}");
        if (!resolved.isOk()) return GitResult.failed("There is no snapshot " + id);
        String commit = resolved.trimmedStdout();
        boolean all = paths == null || paths.isEmpty();
        if (!all) {
            Set<String> wanted = new LinkedHashSet<>(paths);
            var listed = git.run(GitCommand.of("ls-tree", "-z", "--name-only", "--full-tree")
                    .revs(commit).paths(List.copyOf(wanted)).args());
            if (!listed.isOk()) return GitResult.failed("Could not restore from the snapshot: " + listed.stderr());
            for (String found : listed.stdout().split("\0")) wanted.remove(found);
            if (!wanted.isEmpty()) return GitResult.failed(wanted.iterator().next() + " is not in this snapshot");
        }
        List<String> args = new ArrayList<>(List.of("restore", "--source=" + commit, "--worktree", "--overlay"));
        NativeGitRunner.Result res;
        if (all) {
            args.add("--");
            args.add(".");
            res = git.run(args);
        } else {
            args.add("--pathspec-from-file=-");
            args.add("--pathspec-file-nul");
            StringBuilder input = new StringBuilder();
            for (String path : paths) input.append(path).append('\0');
            res = git.runWithInput(input.toString().getBytes(StandardCharsets.UTF_8), args);
        }
        return res.isOk()
                ? GitResult.ok(JGitSnapshots.restoredMessage(paths))
                : GitResult.failed("Could not restore from the snapshot: " + res.stderr());
    }
}
