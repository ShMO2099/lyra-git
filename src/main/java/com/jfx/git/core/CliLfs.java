package com.jfx.git.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Git LFS on the native engine.
 *
 * <p>Package-private: reached only through {@link NativeGitBackend}, which is the
 * engine's public face.
 */
final class CliLfs {

    private static final Logger LOGGER = Logger.getLogger(NativeGitBackend.class.getName());

    private final CliRepo repo;

    CliLfs(CliRepo repo) {
        this.repo = repo;
    }

    public java.util.Set<String> lfsTrackedPaths(java.util.Collection<String> paths) {
        repo.ensureOpen();
        if (paths == null || paths.isEmpty()) {
            return java.util.Set.of();
        }

        // The paths on stdin, NUL-terminated. On the command line, a staging list of a
        // few hundred files passed Windows' 32K limit, the runner refused to start git,
        // and the failure read as "none of these is in LFS" - so LFS files were offered
        // the text editor and line staging as if they were ordinary text.
        StringBuilder input = new StringBuilder();
        for (String path : paths) input.append(path).append('\0');
        var res = repo.runner.runWithInput(input.toString().getBytes(StandardCharsets.UTF_8),
                List.of("check-attr", "-z", "--stdin", "filter"));
        if (!res.isOk()) {
            LOGGER.warning("git check-attr failed: " + res.stderr());
            return java.util.Set.of();
        }

        return CliParsers.pathsWithAttribute(res.stdout(), "lfs");
    }

    public boolean canMaterializeLfsContent() {
        return NativeGitRunner.isGitLfsInstalled() && isLfsFilterRegistered();
    }

    /**
     * Whether this repository's effective config (system, global or local) has the LFS
     * smudge filter registered - what {@code git lfs install} does. The binary being on
     * PATH is necessary but not sufficient: a machine can have {@code git-lfs} present
     * yet never have run {@code install}, which leaves every LFS-tracked file a pointer
     * on checkout exactly as if it were absent.
     */
    private boolean isLfsFilterRegistered() {
        repo.ensureOpen();
        return repo.runner.run("config", "--get", "filter.lfs.smudge").isOk();
    }

    public List<String> lfsTrackedPatterns() {
        repo.ensureOpen();
        byte[] attributes = repo.diffs.getWorkingTreeBytes(".gitattributes");
        return attributes == null || attributes.length == 0
                ? List.of()
                : List.copyOf(LfsOps.patternsIn(new String(attributes, java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Why an LFS command cannot be run at all, or {@code null} when it can. */
    private GitResult lfsUnavailable() {
        repo.ensureOpen();
        return NativeGitRunner.isGitLfsInstalled() ? null : GitResult.failed(LfsOps.NOT_INSTALLED);
    }

    /** Runs {@code git lfs ...}, reporting {@code success} or git-lfs's own words on failure. */
    private GitResult lfs(String success, boolean network, String... args) {
        GitResult unavailable = lfsUnavailable();
        if (unavailable != null) return unavailable;
        List<String> command = new ArrayList<>();
        command.add("lfs");
        command.addAll(List.of(args));
        var res = network ? repo.runNetwork(command) : repo.runner.run(command);
        if (res.isOk()) return GitResult.ok(success);
        // git-lfs writes its errors to either stream depending on the subcommand.
        String said = res.stderr().isBlank() ? res.trimmedStdout() : res.stderr().strip();
        return GitResult.failed(said.isBlank() ? "git lfs " + args[0] + " failed" : said);
    }

    public GitResult lfsInstall() {
        return lfs("Git LFS is set up for this repository", false, "install", "--local");
    }

    public GitResult lfsTrack(String pattern) {
        String wanted = pattern == null ? "" : pattern.trim();
        if (wanted.isEmpty()) return GitResult.failed("Name the files for LFS to track, for example *.psd");
        GitResult bad = CliRepo.rejectIfOptionLike(wanted, "A pattern");
        if (bad != null) return bad;
        return lfs("Tracking " + wanted + " with LFS", false, "track", wanted);
    }

    public GitResult lfsUntrack(String pattern) {
        String wanted = pattern == null ? "" : pattern.trim();
        if (wanted.isEmpty()) return GitResult.failed("Name the pattern LFS should stop tracking");
        GitResult bad = CliRepo.rejectIfOptionLike(wanted, "A pattern");
        if (bad != null) return bad;
        return lfs("No longer tracking " + wanted + " with LFS", false, "untrack", wanted);
    }

    public GitResult lfsFetch() {
        return lfs("Fetched LFS content", true, "fetch");
    }

    public GitResult lfsPull() {
        return lfs("Pulled LFS content", true, "pull");
    }

    public GitResult lfsPrune() {
        return lfs("Pruned local LFS content", false, "prune");
    }

    /** One lock as {@code git lfs locks --json} writes it. Records, as the provider clients' are. */
    private record LockJson(String id, String path, Owner owner) {
        private record Owner(String name) {
        }
    }

    public List<com.jfx.git.model.LfsLock> lfsLocks() {
        repo.ensureOpen();
        if (!NativeGitRunner.isGitLfsInstalled()) return List.of();
        var res = repo.runNetwork(List.of("lfs", "locks", "--json"));
        if (!res.isOk()) {
            LOGGER.log(Level.FINE, "git lfs locks failed: " + res.stderr());
            return List.of();
        }
        try {
            LockJson[] parsed = new com.google.gson.Gson().fromJson(res.stdout(), LockJson[].class);
            List<com.jfx.git.model.LfsLock> locks = new ArrayList<>();
            if (parsed != null) {
                for (LockJson lock : parsed) {
                    if (lock == null || lock.path() == null) continue;
                    locks.add(new com.jfx.git.model.LfsLock(lock.id() == null ? "" : lock.id(), lock.path(),
                            lock.owner() == null || lock.owner().name() == null ? "" : lock.owner().name()));
                }
            }
            return locks;
        } catch (RuntimeException notJson) {
            LOGGER.log(Level.FINE, "Could not read git lfs locks output", notJson);
            return List.of();
        }
    }

    public GitResult lfsLock(String path) {
        if (path == null || path.isBlank()) return GitResult.failed("No file to lock");
        GitResult bad = CliRepo.rejectIfOptionLike(path, "A path");
        if (bad != null) return bad;
        return lfs("Locked " + path, true, "lock", path);
    }

    public GitResult lfsUnlock(String path, boolean force) {
        if (path == null || path.isBlank()) return GitResult.failed("No file to unlock");
        GitResult bad = CliRepo.rejectIfOptionLike(path, "A path");
        if (bad != null) return bad;
        return force
                ? lfs("Unlocked " + path, true, "unlock", "--force", path)
                : lfs("Unlocked " + path, true, "unlock", path);
    }
}
