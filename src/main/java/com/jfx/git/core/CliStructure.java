package com.jfx.git.core;

import com.jfx.git.core.RepoStructureOps.Hook;
import com.jfx.git.model.Submodule;
import com.jfx.git.model.SubmoduleState;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Sparse checkout, submodules, worktrees, maintenance and hooks on the native engine.
 *
 * <p>Package-private: reached only through {@link NativeGitBackend}, which is the
 * engine's public face.
 */
final class CliStructure {

    private static final Logger LOGGER = Logger.getLogger(NativeGitBackend.class.getName());

    private final CliRepo repo;

    CliStructure(CliRepo repo) {
        this.repo = repo;
    }

    /**
     * {@code git sparse-checkout list} itself is the enabled/disabled signal - verified
     * directly against a real repository rather than assumed: it exits non-zero with
     * "fatal: this worktree is not sparse" when sparse checkout has never been turned
     * on (or was turned off with {@code disable}), and exits 0 with the cone-mode
     * patterns, one per line, once it has.
     */
    public boolean isSparseCheckoutEnabled() {
        repo.ensureOpen();
        return repo.runner.run("sparse-checkout", "list").isOk();
    }

    /**
     * The directories kept, as the dialog shows them.
     *
     * <p>In a non-cone repository {@code list} prints gitignore-style patterns -
     * {@code /*}, {@code !/*}{@code /}, {@code /docs/} - and none of them is a folder name,
     * so every checkbox came up unticked. The folder patterns are translated back to
     * folder names and the two "top-level files only" lines that go with them are dropped;
     * any other pattern is returned as written, so it is not lost.
     */
    public List<String> getSparseCheckoutPaths() {
        repo.ensureOpen();
        var res = repo.runner.run("sparse-checkout", "list");
        if (!res.isOk()) return List.of();
        List<String> lines = res.trimmedStdout().lines()
                .filter(line -> !line.isBlank())
                .toList();
        if (!isNonConeSparse()) return lines;
        List<String> folders = new ArrayList<>();
        for (String line : lines) {
            String pattern = line.strip();
            if (pattern.equals("/*") || pattern.equals("!/*/")) continue;
            if (pattern.startsWith("/") && pattern.endsWith("/") && pattern.length() > 2
                    && !pattern.substring(1, pattern.length() - 1).matches(".*[*?\\[!].*")) {
                folders.add(pattern.substring(1, pattern.length() - 1));
            } else {
                folders.add(pattern);
            }
        }
        return folders;
    }

    /**
     * Whether sparse checkout is on in the older non-cone mode, whose patterns are
     * gitignore syntax rather than directory names.
     */
    private boolean isNonConeSparse() {
        // One read of the configuration; git lists section and key names in lower case.
        Map<String, String> config = repo.configValues();
        return "true".equalsIgnoreCase(config.get("core.sparsecheckout"))
                && "false".equalsIgnoreCase(config.get("core.sparsecheckoutcone"));
    }

    /**
     * Narrows the working tree to {@code paths}, keeping the repository in the mode it is
     * already in.
     *
     * <p>This always ran {@code init --cone} first, which on a repository set up with
     * non-cone patterns switched modes under the user's hand-written patterns: git warned
     * "unrecognized pattern" and "disabling cone pattern matching" for each and the result
     * depended on which it met first. A non-cone repository now gets the same folders
     * written as the non-cone patterns that mean them - {@code /*}, {@code !/*}{@code /} and
     * {@code /<dir>/} - and an entry that is already a pattern passes through as written.
     * The list goes over stdin, so a name beginning with a dash cannot be read as a flag.
     */
    public GitResult setSparseCheckoutPaths(List<String> paths) {
        repo.ensureOpen();
        if (paths == null || paths.isEmpty()) {
            return GitResult.failed(
                    "At least one path is required - use disableSparseCheckout() to restore the full tree.");
        }
        List<String> patterns = new ArrayList<>();
        if (isNonConeSparse()) {
            patterns.add("/*");
            patterns.add("!/*/");
            for (String path : paths) {
                String p = path.strip();
                boolean alreadyPattern = p.startsWith("/") || p.startsWith("!")
                        || p.matches(".*[*?\\[].*");
                patterns.add(alreadyPattern ? p : "/" + p + (p.endsWith("/") ? "" : "/"));
            }
        } else {
            // --cone is what makes plain directory names (rather than full gitignore-style
            // patterns) valid for `set` below; running init again on an already-sparse,
            // already-cone-mode repository is harmless.
            var init = repo.runner.run("sparse-checkout", "init", "--cone");
            if (!init.isOk()) return GitResult.failed(init.stderr());
            paths.forEach(p -> patterns.add(p.strip()));
        }

        // git leaves a path it thinks is modified in place "despite sparse patterns", and
        // it judges that from the index's stat data without refreshing it. An index JGit
        // wrote last (the hybrid engines, or a JGit-engine session) carries stat data git
        // does not trust, so every file looked modified and nothing was narrowed.
        repo.runner.run("update-index", "-q", "--refresh");

        byte[] input = (String.join("\n", patterns) + "\n").getBytes(StandardCharsets.UTF_8);
        var res = repo.runner.runWithInput(input, List.of("sparse-checkout", "set", "--stdin"));
        return res.isOk()
                ? GitResult.ok("Sparse checkout set to " + String.join(", ", paths))
                : GitResult.failed(res.stderr());
    }

    public GitResult disableSparseCheckout() {
        repo.ensureOpen();
        var res = repo.runner.run("sparse-checkout", "disable");
        return res.isOk() ? GitResult.ok("Sparse checkout disabled") : GitResult.failed(res.stderr());
    }

    /**
     * Submodules registered in the index, with the commit the superproject records and
     * the one actually checked out.
     *
     * <p>Reads {@code git submodule status}, whose leading character carries the state:
     * {@code -} not initialised, {@code +} checked out at a different commit than the
     * superproject records, {@code U} has merge conflicts, and a space for in sync.
     * Returning an empty list unconditionally, as this used to, made a repository with
     * submodules look like one without.
     *
     * <p>Two passes, because one status line cannot carry both hashes: the plain form
     * prints the <em>checked-out</em> commit (that is what the {@code +} marker means -
     * checked out somewhere else), while {@code --cached} prints the commit the
     * superproject records. Reading the plain form as the recorded hash made the panel
     * show the two columns identical while labelling the submodule "out of date".
     * {@code --cached} is also what keeps this right for a nested submodule, whose
     * gitlink lives in its parent's index rather than the superproject's.
     */
    public List<Submodule> getSubmodules() {
        repo.ensureOpen();
        var res = repo.runner.run("submodule", "status", "--recursive");
        if (!res.isOk()) return List.of();
        var cachedRes = repo.runner.run("submodule", "status", "--recursive", "--cached");
        Map<String, String> recordedByPath = cachedRes.isOk()
                ? parseSubmoduleStatusShas(cachedRes.stdout())
                : Map.of();

        // Each file read once for all of them: this was two `git config --get` per
        // submodule, one against the repository's config and one against .gitmodules.
        Map<String, String> declared = gitmodulesValues();
        Map<String, String> nameByPath = submoduleNamesByPath(declared);
        Map<String, String> config = repo.configValues();

        List<Submodule> result = new ArrayList<>();
        for (String line : res.stdout().split("\\r?\\n")) {
            SubmoduleStatusLine parsed = parseSubmoduleStatusLine(line);
            if (parsed == null) continue;

            char marker = parsed.marker();
            String sha = parsed.sha();
            String path = parsed.path();

            SubmoduleState state = switch (marker) {
                case '-' -> SubmoduleState.UNINITIALIZED;
                // '+' is "checked out elsewhere"; 'U' is a merge conflict inside the
                // submodule. Both leave the checkout at something other than the recorded
                // commit, and the panel offers the same "Init / Update" for either.
                case '+', 'U' -> SubmoduleState.OUT_OF_DATE;
                default -> SubmoduleState.CURRENT;
            };

            String url = submoduleUrl(path, nameByPath.get(path), config, declared);
            boolean uninitialized = state == SubmoduleState.UNINITIALIZED;
            // Uninitialised: there is no checkout, and the plain form already printed the
            // recorded commit, so that is the only hash there is to report.
            String recorded = uninitialized ? sha : recordedByPath.getOrDefault(path, sha);
            String checkedOut = uninitialized ? null : sha;

            result.add(new Submodule(path, url, recorded, checkedOut, state));
        }
        return result;
    }

    /** {@code path -> sha} from one {@code git submodule status} listing. */
    private Map<String, String> parseSubmoduleStatusShas(String output) {
        Map<String, String> shas = new HashMap<>();
        for (String line : output.split("\\r?\\n")) {
            SubmoduleStatusLine parsed = parseSubmoduleStatusLine(line);
            if (parsed != null) shas.put(parsed.path(), parsed.sha());
        }
        return shas;
    }

    /** One {@code git submodule status} line: state marker, commit, path. */
    record SubmoduleStatusLine(char marker, String sha, String path) {
    }

    /**
     * Reads {@code <marker><sha> <path>[ (<describe>)]}.
     *
     * <p>The path runs to the end of the line, less the optional describe suffix: it
     * can contain spaces, and splitting on whitespace turned {@code libs/my lib} into
     * {@code libs/my}, a submodule that does not exist. Whether a trailing
     * {@code " (...)"} is git's describe or part of the name is settled by what is on
     * disk, since git prints it only for a checked-out submodule, whose directory exists.
     */
    SubmoduleStatusLine parseSubmoduleStatusLine(String line) {
        if (line == null || line.length() < 3) return null;
        char marker = line.charAt(0);
        String rest = line.substring(1);
        int space = rest.indexOf(' ');
        if (space <= 0) return null;
        String sha = rest.substring(0, space);
        String path = rest.substring(space + 1);
        if (path.isEmpty()) return null;

        int describe = path.lastIndexOf(" (");
        if (describe > 0 && path.endsWith(")")) {
            String withoutDescribe = path.substring(0, describe);
            // The whole text being a directory while the shortened one is not means the
            // parenthesis is part of the name.
            boolean wholeIsPath = new File(repo.workTree, path).isDirectory()
                    && !new File(repo.workTree, withoutDescribe).isDirectory();
            if (!wholeIsPath) path = withoutDescribe;
        }
        return new SubmoduleStatusLine(marker, sha, path);
    }

    /**
     * {@code path -> name} for the submodules {@code .gitmodules} declares.
     *
     * <p>A submodule's config is keyed by its <em>name</em>, which is only by default the
     * same as its path; after a {@code git mv}, or when it was added with
     * {@code --name}, the two differ, and looking the URL up by path found nothing.
     * {@code -z} so a name or path with spaces or dots comes back in one piece.
     */
    private static Map<String, String> submoduleNamesByPath(Map<String, String> gitmodules) {
        Map<String, String> names = new HashMap<>();
        gitmodules.forEach((key, value) -> {
            if (key.startsWith("submodule.") && key.endsWith(".path")) {
                names.put(value, key.substring("submodule.".length(), key.length() - ".path".length()));
            }
        });
        return names;
    }

    /** Everything {@code .gitmodules} says, in the form {@link CliRepo#configValues()} reads. */
    private Map<String, String> gitmodulesValues() {
        if (!new File(repo.workTree, ".gitmodules").isFile()) return Map.of();
        return CliParsers.parseConfigList(repo.runner.run("config", "-z", "--file", ".gitmodules", "--list"));
    }

    /**
     * The URL for the submodule at {@code path}: the one {@code submodule init} copied
     * into the repository's config, else the one {@code .gitmodules} declares - so an
     * uninitialised submodule still says where it would come from.
     */
    private static String submoduleUrl(String path, String name, Map<String, String> config,
                                       Map<String, String> gitmodules) {
        if (name == null) {
            // Not in the top-level .gitmodules - a nested submodule. Keyed by path as
            // before, which is right whenever nobody renamed it.
            return nonBlank(config.get("submodule." + path + ".url"));
        }
        String configured = nonBlank(config.get("submodule." + name + ".url"));
        return configured != null ? configured : nonBlank(gitmodules.get("submodule." + name + ".url"));
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public GitResult updateSubmodules(List<String> paths) {
        repo.ensureOpen();
        // The interface contract is "these submodules, or all of them when empty".
        // Without the path arguments, "Init / Update" on one submodule in the sidebar
        // fetches and moves every submodule in the repository.
        GitCommand update = GitCommand.of("submodule", "update", "--init", "--recursive");
        if (paths != null && !paths.isEmpty()) {
            update.endOfRevisions();
            for (String path : paths) {
                if (path != null && !path.isBlank()) update.paths(path);
            }
        }
        // A network command: it clones and fetches. See runNetwork.
        var res = repo.runNetwork(update.args());
        if (!res.isOk()) return GitResult.failed(res.stderr());
        return GitResult.ok(paths == null || paths.isEmpty()
                ? "Updated submodules"
                : "Updated " + paths.size() + " submodule" + (paths.size() == 1 ? "" : "s"));
    }

    /**
     * When {@link AppPreferences#autoUpdateSubmodules()} is on, brings submodules in
     * line with whatever a checkout or pull just changed - best-effort, since a failed
     * auto-update should never turn an otherwise-successful checkout or pull into a
     * reported failure. Skips the call entirely for a repository with no submodules at
     * all, rather than paying for a guaranteed no-op every time. See {@code
     * GitService.maybeAutoUpdateSubmodules} for the JGit twin of this.
     */
    void maybeAutoUpdateSubmodules() {
        if (!AppPreferences.autoUpdateSubmodules()) return;
        // One stat in front of the subprocess below. The overwhelming majority of
        // repositories have no submodules, and for them the update is a guaranteed
        // no-op paid for on every checkout and pull.
        if (!new File(repo.workTree, ".gitmodules").isFile()) return;
        // No --init, unlike the explicit "Init / Update": this runs on every checkout and
        // pull, and initialising everything cloned each optional multi-gigabyte submodule
        // the user had deliberately left alone. Without it, git moves only the submodules
        // already initialised - recursively, with the same rule inside each - which is
        // "keep what I have in step with the branch".
        //
        // It fetches whatever commit a submodule is missing, so it talks to remotes - and
        // nobody asked for it, so nothing may prompt: run plainly, a passphrase or an
        // unknown host key put ssh's question on a terminal nobody could see, and the
        // single git worker waited for it with no timeout (submodule update writes, so it
        // has none). Silently, the way a background fetch runs, it fails instead, and the
        // user's own "Init / Update" can ask.
        var res = repo.runSilentNetwork(List.of("submodule", "update", "--recursive"));
        if (!res.isOk()) {
            LOGGER.fine("Automatic submodule update failed: " + res.stderr());
        }
    }

    public GitResult addSubmodule(String url, String path) {
        repo.ensureOpen();
        // See addRemote for the separator. A clone, so the network path: credentials
        // through the prompt bridge and the profile's ssh key, as a fetch gets them.
        var res = repo.runNetwork(List.of("submodule", "add", "--", url, path));
        return res.isOk() ? GitResult.ok("Added submodule " + path)
                : RefusalWords.inputFailure(res.stderr(), RefusalWords.submoduleField(res.stderr()));
    }

    public GitResult syncSubmodules() {
        repo.ensureOpen();
        var res = repo.runner.run("submodule", "sync", "--recursive");
        return res.isOk() ? GitResult.ok("Synced submodules") : GitResult.failed(res.stderr());
    }

    public GitResult deinitSubmodule(String path, boolean force) {
        repo.ensureOpen();
        // An omitted flag has to be left out of the argument list, not passed as "":
        // git reads the empty string as an argument in its own right.
        var res = repo.runner.run(GitCommand.of("submodule", "deinit").optsIf(force, "-f").paths(path).args());
        return res.isOk() ? GitResult.ok("Deinitialized submodule " + path)
                : RefusalWords.workingCopyFailure(res.stderr(), path);
    }

    /**
     * The submodule's folder, or {@code null} while it is not initialised - no
     * {@code .git} inside it - as the JGit engine answers. The sidebar reads null as
     * "nothing to open", and the bare folder of an uninitialised submodule opened in a
     * new tab as no repository at all.
     */
    public File submoduleWorkTree(String path) {
        File dir = new File(repo.workTree, path);
        return new File(dir, ".git").exists() ? dir : null;
    }

    /** Every working tree, main one first, read from {@code git worktree list --porcelain}. */
    public List<Worktrees.Worktree> getWorktrees() {
        repo.ensureOpen();
        var res = repo.runner.run("worktree", "list", "--porcelain");
        if (!res.isOk()) return List.of();

        return CliParsers.parseWorktrees(res.stdout());
    }

    public GitResult addWorktree(File path, String branch, boolean createBranch) {
        repo.ensureOpen();
        List<String> args = new ArrayList<>(List.of("worktree", "add"));
        if (createBranch) {
            args.add("-b");
            args.add(branch);
        }
        // The option parser does not stop at the path, so a branch after it that began
        // with a dash - "--force", say - was read as a flag.
        args.add(CliRepo.END_OF_OPTIONS);
        args.add(path.getAbsolutePath());
        if (!createBranch && branch != null) args.add(branch);
        var res = repo.runner.run(args);
        return res.isOk() ? GitResult.ok("Added worktree at " + path)
                : RefusalWords.inputFailure(res.stderr(), RefusalWords.worktreeField(res.stderr()));
    }

    public GitResult removeWorktree(File path, boolean force) {
        repo.ensureOpen();
        List<String> args = new ArrayList<>(List.of("worktree", "remove"));
        if (force) args.add("--force");
        args.add(path.getAbsolutePath());
        var res = repo.runner.run(args);
        return res.isOk() ? GitResult.ok("Removed worktree at " + path)
                : RefusalWords.workingCopyFailure(res.stderr(), path.getPath());
    }

    public GitResult pruneWorktrees() {
        repo.ensureOpen();
        var res = repo.runner.run("worktree", "prune");
        return res.isOk() ? GitResult.ok("Pruned worktrees") : GitResult.failed(res.stderr());
    }

    public GitResult setWorktreeLocked(File path, boolean locked) {
        repo.ensureOpen();
        var res = repo.runner.run("worktree", locked ? "lock" : "unlock", path.getAbsolutePath());
        return res.isOk() ? GitResult.ok((locked ? "Locked" : "Unlocked") + " worktree") : GitResult.failed(res.stderr());
    }

    /**
     * {@code git maintenance run --auto}, falling back to {@code gc --auto}.
     *
     * <p>{@code git maintenance} arrived in 2.29 and is the better command - it runs the
     * whole set of tasks git currently thinks worthwhile rather than only repacking. On
     * anything older the subcommand does not exist, and {@code gc --auto} is what it
     * replaced.
     *
     * <p>Which one is decided by the version, not by the first one failing: maintenance
     * fails for reasons that are not "unknown command" too - a {@code gc.log} left by an
     * earlier failed run, a lock held by another git - and running {@code gc} straight
     * after reported the same problem a second time or, worse, hid the first one.
     */
    public GitResult runMaintenance() {
        repo.ensureOpen();
        var res = hasMaintenanceCommand()
                ? repo.runner.run("maintenance", "run", "--auto")
                : repo.runner.run("gc", "--auto");
        return res.isOk() ? GitResult.ok("Housekeeping finished") : GitResult.failed(res.stderr());
    }

    /** Whether the installed git has {@code git maintenance} (2.29 and later). */
    private static boolean hasMaintenanceCommand() {
        int[] version = NativeGitRunner.parseVersion(NativeGitRunner.getGitVersion());
        return version == null || NativeGitRunner.compareVersions(version, new int[]{2, 29}) >= 0;
    }

    /**
     * Whether git's own thresholds say there is work to do.
     *
     * <p>Asked by counting loose objects rather than by running maintenance and seeing
     * whether anything happened: {@code count-objects -v} is a cheap read, and the number
     * it reports is the same one git's {@code gc.auto} threshold looks at. The default
     * threshold is 6700; well under it, packing would achieve nothing worth interrupting
     * the user about.
     */
    public boolean needsMaintenance() {
        repo.ensureOpen();
        var res = repo.runner.run("count-objects", "-v");
        if (!res.isOk()) return false;
        long loose = -1;
        for (String line : res.trimmedStdout().split(CliParsers.LINE_BREAK)) {
            if (line.startsWith("count:")) {
                try {
                    loose = Long.parseLong(line.substring("count:".length()).trim());
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return loose >= looseObjectThreshold();
    }

    /** {@code gc.auto}, or git's own default when it is not configured. */
    private long looseObjectThreshold() {
        String configured = repo.configValue("gc.auto");
        if (configured == null) return 6700;
        try {
            long value = Long.parseLong(configured.trim());
            // gc.auto=0 disables automatic housekeeping entirely; respect that by never
            // suggesting it rather than by treating zero as "always".
            return value <= 0 ? Long.MAX_VALUE : value;
        } catch (NumberFormatException e) {
            return 6700;
        }
    }

    /**
     * The hooks git would run here, read from {@code core.hooksPath} when it is set and
     * from {@code .git/hooks} otherwise.
     *
     * <p>{@code git rev-parse --git-path hooks} answers that question directly, including
     * for a worktree, rather than making this guess at the layout.
     */
    public List<Hook> getHooks() {
        repo.ensureOpen();
        var res = repo.runner.run("rev-parse", "--git-path", "hooks");
        if (!res.isOk()) return List.of();
        File dir = new File(res.trimmedStdout());
        if (!dir.isAbsolute()) dir = new File(repo.workTree, res.trimmedStdout());
        return readHooks(dir);
    }

    /**
     * Lists real hooks in {@code dir}, sorted by name.
     *
     * <p>{@code .sample} files are skipped: git creates one per hook in every new
     * repository and never runs any of them, so listing them would bury the one script
     * that actually is running under a dozen that are not.
     */
    static List<Hook> readHooks(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return List.of();
        List<Hook> hooks = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || name.endsWith(".sample")) continue;
            hooks.add(new Hook(name, file.getAbsolutePath(), file.canExecute()));
        }
        hooks.sort(java.util.Comparator.comparing(Hook::name));
        return hooks;
    }
}
