package com.jfx.git.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Creating repositories with the git CLI: {@code clone} and {@code init}, the twins of
 * {@link GitService}'s, reached through {@link NativeGitBackend}'s statics.
 *
 * <p>Package-private: reached only through {@link NativeGitBackend}, which is the
 * engine's public face.
 */
final class CliClone {

    private static final Logger LOGGER = Logger.getLogger(NativeGitBackend.class.getName());

    private CliClone() {
    }

    /** Creates a new repository, on the branch name Preferences names, with no starter files. */
    public static GitResult initRepository(File dir) {
        return initRepository(dir, RepoInitTemplates.NOTHING);
    }

    /**
     * {@code git init}, on the branch name Preferences names, then whichever starter files
     * {@code templates} asks for, left unstaged - what {@link GitService#initRepository}
     * does, with the user's own git: {@code init.templateDir} and its hooks apply.
     *
     * <p>The branch is set with {@code symbolic-ref} rather than {@code --initial-branch},
     * which needs git 2.28 - newer than {@link NativeGitRunner#MINIMUM_VERSION}. A folder
     * that is already a repository is left on its branch, as the JGit engine leaves it:
     * {@code git init} there only re-initialises, and moving HEAD would strand whatever
     * the current branch has.
     */
    public static GitResult initRepository(File dir, RepoInitTemplates templates) {
        if (dir == null) return GitResult.failed("Init failed: no folder given");
        File target = dir.getAbsoluteFile();
        String branch = AppPreferences.defaultBranchName().strip();
        boolean existing = new File(target, ".git").exists();
        if (!target.isDirectory() && !target.mkdirs()) {
            return GitResult.failed("Init failed: could not create " + target);
        }
        NativeGitRunner git = new NativeGitRunner(target);
        if (!existing && !git.run("check-ref-format", "refs/heads/" + branch).isOk()) {
            return GitResult.failed("Init failed: '" + branch + "' is not a valid branch name");
        }
        var res = git.run("init", "--quiet");
        if (!res.isOk()) return GitResult.failed("Init failed: " + res.stderr());
        if (!existing) {
            var head = git.run("symbolic-ref", "HEAD", "refs/heads/" + branch);
            if (!head.isOk()) {
                return GitResult.failed("Repository created, but its branch could not be named "
                        + branch + ": " + head.stderr());
            }
        }
        try {
            (templates == null ? RepoInitTemplates.NOTHING : templates).writeInto(target, target.getName());
        } catch (IOException e) {
            return GitResult.failed("Repository created, but writing template files failed: " + e.getMessage());
        }
        return GitResult.ok("Initialized empty repository at " + target.getAbsolutePath());
    }

    /** Clones {@code uri} into {@code dir}, in full. Blocking - call from a background thread. */
    public static GitResult cloneRepository(String uri, File dir) {
        return cloneRepository(uri, dir, null, 0);
    }

    /**
     * Clones {@code uri} into {@code dir}. Blocking - call from a background thread.
     *
     * @param branch a branch to check out instead of the remote's default, or null/blank
     * @param depth  a shallow clone's history depth, or {@code 0} for all of it
     */
    public static GitResult cloneRepository(String uri, File dir, String branch, int depth) {
        return cloneRepository(uri, dir, branch, depth, () -> false);
    }

    /**
     * {@code git clone}, as {@link GitService#cloneRepository(String, File, String, int,
     * java.util.function.BooleanSupplier)} does it with JGit.
     *
     * <ul>
     *   <li>Submodules are cloned too ({@code --recurse-submodules}), for the reason the JGit
     *       engine gives: a clone whose submodule folders are empty does not build.</li>
     *   <li>A depth keeps every branch ({@code --no-single-branch}): on its own
     *       {@code --depth} implies a single branch, where the JGit engine fetches them
     *       all. A local path is cloned through the transport ({@code --no-local}) then,
     *       because git ignores a depth for the hard-linking local copy.</li>
     *   <li>Credentials and ssh's questions go through {@link AskpassBridge}, as a fetch's
     *       do, and the outcome is reported to the credential store.</li>
     *   <li>Progress reaches the task's watcher: the runner asks git for it
     *       ({@code --progress}) whenever someone is listening.</li>
     *   <li>There is no time limit - see {@link NativeGitRunner#timeoutFor} - but a
     *       transfer that stalls for a minute is abandoned, as a background fetch's is.</li>
     *   <li>Cancelling kills git, which then cannot clean up after itself as it does after
     *       an ordinary failure, so what it wrote is removed here: the folder, if the clone
     *       created it, or its contents, if it was an empty folder already.</li>
     * </ul>
     * LFS content arrives by the smudge filter {@code git lfs install} registers, as for
     * any {@code git clone}; without it the files are pointers, which
     * {@link CliLfs#canMaterializeLfsContent()} reports once the repository is open.
     *
     * @param cancelled polled while git runs; {@code true} stops the clone
     */
    public static GitResult cloneRepository(String uri, File dir, String branch, int depth,
                                            java.util.function.BooleanSupplier cancelled) {
        if (uri == null || uri.isBlank()) return GitResult.failed("Clone failed: no repository to clone");
        if (dir == null) return GitResult.failed("Clone failed: no folder to clone into");
        File target = dir.getAbsoluteFile();
        boolean existed = target.exists();
        String[] present = target.isDirectory() ? target.list() : null;
        if (existed && (present == null || present.length > 0)) {
            return GitResult.refused("Clone failed: " + target + " already exists and is not an empty folder",
                    new Refusal.BadInput(Refusal.Field.TARGET));
        }
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            return GitResult.refused("Clone failed: could not create " + parent,
                    new Refusal.BadInput(Refusal.Field.TARGET));
        }

        List<String> args = new ArrayList<>(List.of("clone", "--recurse-submodules"));
        if (branch != null && !branch.isBlank()) args.add("--branch=" + branch.strip());
        if (depth > 0) {
            args.add("--depth=" + depth);
            args.add("--no-single-branch");
            args.add("--no-local");
        }
        args.add("--");
        args.add(uri.strip());
        args.add(target.getPath());

        // Run from the parent: the runner never runs without a directory, and the target
        // may not exist yet. The scrubbed environment keeps a repository the parent sits
        // in out of it.
        NativeGitRunner git = new NativeGitRunner(parent);
        Map<String, String> env = new HashMap<>(AskpassBridge.interactiveEnvironment(
                () -> globalSshCommand(git)));
        env.put("GIT_HTTP_LOW_SPEED_LIMIT", "1000");
        env.put("GIT_HTTP_LOW_SPEED_TIME", "60");
        // A clone refused on the connected account removes what it created, so the second
        // attempt starts from the same place; see CliRepo.withConnectedAccount.
        var res = CliRepo.withConnectedAccount(
                withHelper -> CliRepo.networkFinished(git.runWithEnvironment(env, withHelper, cancelled)), args);

        if (cancelled.getAsBoolean() || GitTaskRunner.isCancelled()) {
            removeClone(target, existed);
            return GitResult.failed(GitService.cancelledCloneMessage(target));
        }
        if (!res.isOk()) {
            String why = res.stderr().isBlank() ? "git exited with " + res.exitCode() : res.stderr();
            return RefusalWords.inputFailure("Clone failed: " + why, RefusalWords.cloneField(why));
        }
        return GitResult.ok("Cloned into " + target.getAbsolutePath());
    }

    /**
     * {@code core.sshCommand} from the user's own configuration - global, then system -
     * never a repository's: the clone's parent folder may sit inside one.
     */
    private static String globalSshCommand(NativeGitRunner git) {
        for (String scope : List.of("--global", "--system")) {
            String value = git.run("config", scope, "--get", "core.sshCommand").trimmedStdout();
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    /** Removes what a killed clone wrote: the folder it made, or what it put in one that was empty. */
    private static void removeClone(File target, boolean existed) {
        Path root = target.toPath();
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                if (existed && p.equals(root)) continue;
                try {
                    // git writes its pack and object files read-only; on Windows that
                    // stops a delete.
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Could not remove " + p + " after a cancelled clone", e);
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            LOGGER.log(Level.FINE, "Could not clean up after a cancelled clone at " + target, e);
        }
    }
}
