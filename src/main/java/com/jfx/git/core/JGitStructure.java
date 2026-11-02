package com.jfx.git.core;

import com.jfx.git.model.Submodule;
import com.jfx.git.model.SubmoduleState;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Submodules, worktrees, sparse checkout, hooks, maintenance, the repository's own
 * config, and Git LFS (read-only here), for {@link GitService}.
 */
final class JGitStructure extends JGitPart {

    JGitStructure(JGitRepo ctx) {
        super(ctx);
    }

    /** A config key as JGit addresses it: section, subsection or null, and name. */
    private static String[] configKey(String key) {
        if (key == null) return null;
        int first = key.indexOf('.');
        int last = key.lastIndexOf('.');
        if (first <= 0 || last == key.length() - 1) return null;
        return new String[]{key.substring(0, first),
                first == last ? null : key.substring(first + 1, last),
                key.substring(last + 1)};
    }

    public String getLocalConfig(String key) {
        String[] parts = configKey(key);
        if (repository() == null || parts == null) return null;
        try {
            // Read from the file, not from the merged view: the repository's own value.
            var config = repository().getConfig();
            config.load();
            boolean set = parts[1] == null
                    ? config.getNames(parts[0]).stream().anyMatch(parts[2]::equalsIgnoreCase)
                    : config.getNames(parts[0], parts[1]).stream().anyMatch(parts[2]::equalsIgnoreCase);
            if (!set) return null;
            String value = config.getString(parts[0], parts[1], parts[2]);
            return value == null ? "" : value;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read " + key, e);
            return null;
        }
    }

    public GitResult setLocalConfig(String key, String value) {
        if (git() == null) return GitResult.failed("No repository open");
        String[] parts = configKey(key);
        if (parts == null) return GitResult.failed("'" + key + "' is not a configuration key");
        try {
            var config = repository().getConfig();
            if (value == null) {
                config.unset(parts[0], parts[1], parts[2]);
            } else {
                config.setString(parts[0], parts[1], parts[2], value);
            }
            config.save();
            return GitResult.ok(value == null ? "Removed " + key : "Set " + key);
        } catch (Exception e) {
            return GitResult.failed("Could not change " + key + ": " + describe(e));
        }
    }

    public GitResult setLocalIdentity(String name, String email, String signingKey, boolean gpgSign) {
        if (repository() == null) return GitResult.failed("No repository open");
        try {
            var config = repository().getConfig();
            config.setString("user", null, "name", name == null ? "" : name);
            config.setString("user", null, "email", email == null ? "" : email);
            if (signingKey == null || signingKey.isBlank()) {
                config.unset("user", null, "signingkey");
            } else {
                config.setString("user", null, "signingkey", signingKey.trim());
            }
            config.setBoolean("commit", null, "gpgsign", gpgSign);
            config.save();
            return GitResult.ok("Applied identity " + (name == null ? "" : name)
                    + (email == null || email.isBlank() ? "" : " <" + email + ">") + " to this repository");
        } catch (Exception e) {
            return GitResult.failed("Could not write the repository's git config: " + describe(e));
        }
    }

    /**
     * Which of {@code paths} the {@code .gitattributes} rules route through the LFS
     * filter.
     *
     * <p>One walk for the whole set, filtered by a group of path filters, rather than a
     * fresh {@code TreeWalk} and {@code FileTreeIterator} per path: a directory listing
     * of five hundred files used to mean five hundred working-tree scans to draw one
     * panel.
     */
    public java.util.Set<String> lfsTrackedPaths(java.util.Collection<String> paths) {
        if (repository() == null || paths == null || paths.isEmpty()) {
            return java.util.Set.of();
        }
        java.util.Set<String> wanted = new java.util.LinkedHashSet<>(paths);
        java.util.List<org.eclipse.jgit.treewalk.filter.TreeFilter> filters = new ArrayList<>();
        for (String path : wanted) {
            filters.add(org.eclipse.jgit.treewalk.filter.PathFilter.create(path));
        }

        java.util.Set<String> tracked = new java.util.LinkedHashSet<>();
        try (org.eclipse.jgit.treewalk.TreeWalk walk = new org.eclipse.jgit.treewalk.TreeWalk(repository())) {
            walk.addTree(new FileTreeIterator(repository()));
            walk.setRecursive(true);
            // OrTreeFilter.create rejects a single-element array, and one path needs no
            // disjunction anyway.
            walk.setFilter(filters.size() == 1
                    ? filters.get(0)
                    : org.eclipse.jgit.treewalk.filter.OrTreeFilter.create(filters));
            walk.setAttributesNodeProvider(repository().createAttributesNodeProvider());
            while (walk.next()) {
                String path = walk.getPathString();
                if (wanted.contains(path) && "lfs".equals(walk.getAttributes().getValue("filter"))) {
                    tracked.add(path);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not read .gitattributes for " + paths.size() + " paths", e);
        }
        return tracked;
    }

    // ------------------------------------------------------------------
    // Submodules
    // ------------------------------------------------------------------

    /**
     * Every submodule registered in the index, with its state.
     *
     * <p>Returns an empty list for a repository without submodules, which is the
     * common case and must not be an error.
     */
    public List<Submodule> getSubmodules() {
        if (git() == null) return List.of();
        List<Submodule> result = new ArrayList<>();
        try {
            Map<String, org.eclipse.jgit.submodule.SubmoduleStatus> statuses =
                    git().submoduleStatus().call();
            Map<String, String> urls = statuses.isEmpty() ? Map.of() : submoduleUrls();
            for (Map.Entry<String, org.eclipse.jgit.submodule.SubmoduleStatus> entry
                    : statuses.entrySet()) {
                org.eclipse.jgit.submodule.SubmoduleStatus status = entry.getValue();
                result.add(new Submodule(
                        entry.getKey(),
                        urls.get(entry.getKey()),
                        nameOf(status.getIndexId()),
                        nameOf(status.getHeadId()),
                        translate(status.getType())));
            }
            result.sort(Comparator.comparing(Submodule::path));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read submodules", e);
        }
        return result;
    }

    /** SHA-1 as a plain string, which is what crosses the engine boundary. */
    private static String nameOf(ObjectId id) {
        return id == null ? null : id.getName();
    }

    private static SubmoduleState translate(org.eclipse.jgit.submodule.SubmoduleStatusType type) {
        return switch (type) {
            case UNINITIALIZED -> SubmoduleState.UNINITIALIZED;
            case MISSING -> SubmoduleState.MISSING;
            // The names read backwards: JGit's INITIALIZED is the healthy state where
            // HEAD equals the recorded commit, and REV_CHECKED_OUT means some *other*
            // rev is checked out.
            case INITIALIZED -> SubmoduleState.CURRENT;
            case REV_CHECKED_OUT -> SubmoduleState.OUT_OF_DATE;
        };
    }

    /**
     * Every submodule's URL by path: from {@code .gitmodules}, preferring the initialised
     * one in the config.
     *
     * <p>One walk for all of them. A walk per submodule, each scanning the index from
     * the start for its own path, made listing a repository's submodules quadratic.
     */
    private Map<String, String> submoduleUrls() {
        Map<String, String> urls = new java.util.HashMap<>();
        try (org.eclipse.jgit.submodule.SubmoduleWalk walk =
                     org.eclipse.jgit.submodule.SubmoduleWalk.forIndex(repository())) {
            while (walk.next()) {
                try {
                    String configured = walk.getConfigUrl();
                    urls.put(walk.getPath(), configured != null ? configured : walk.getModulesUrl());
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "No URL for submodule " + walk.getPath(), e);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not read the submodules' URLs", e);
        }
        return urls;
    }

    /**
     * Clones and checks out submodules, the equivalent of
     * {@code git submodule update --init}.
     *
     * @param paths specific submodules, or empty for all of them
     */
    public GitResult updateSubmodules(List<String> paths) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            var init = git().submoduleInit();
            var update = git().submoduleUpdate();
            for (String path : paths) {
                init.addPath(path);
                update.addPath(path);
            }
            init.call();
            Collection<String> updated = ctx.overNetwork(update);
            if (updated.isEmpty()) {
                return GitResult.ok("Submodules already up to date");
            }
            return GitResult.ok("Updated " + String.join(", ", updated));
        } catch (Exception e) {
            return GitResult.failed("Submodule update failed: " + describe(e));
        }
    }

    /**
     * When {@link AppPreferences#autoUpdateSubmodules()} is on, brings submodules in
     * line with whatever a checkout or pull just changed - best-effort, since a failed
     * auto-update should never turn an otherwise-successful checkout or pull into a
     * reported failure; "Init / update" in the sidebar is always there as a manual,
     * visible fallback. Skips the call entirely for a repository with no submodules at
     * all, rather than paying for a guaranteed no-op every time.
     */
    void maybeAutoUpdateSubmodules() {
        if (!AppPreferences.autoUpdateSubmodules() || !hasGitmodulesFile()) return;
        try {
            updateInitializedSubmodules(repository(), 0);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Automatic submodule update failed", e);
        }
    }

    /** Deep enough for any real nesting, and a stop for a submodule that contains itself. */
    private static final int SUBMODULE_UPDATE_DEPTH = 8;

    /**
     * {@code git submodule update --recursive} without {@code --init}: moves every
     * submodule the user has already initialised to the commit its superproject records,
     * and nested ones inside those, and leaves the rest alone.
     *
     * <p>The automatic path used to run {@code init} first, which registers - and so
     * clones - every submodule in {@code .gitmodules}. An optional multi-gigabyte
     * submodule the user had deliberately never initialised was then cloned on the next
     * branch switch. Initialising is a choice, and "Init / update" in the sidebar is where
     * it is made. JGit's {@code SubmoduleUpdateCommand} already skips a submodule with no
     * URL in the repository's config, which is exactly "not initialised".
     */
    private void updateInitializedSubmodules(Repository repo, int depth) throws Exception {
        if (depth >= SUBMODULE_UPDATE_DEPTH) return;
        File workTree = repo.getWorkTree();
        if (workTree == null || !new File(workTree, Constants.DOT_GIT_MODULES).isFile()) return;
        try (Git wrapped = Git.wrap(repo)) {
            ctx.overNetwork(wrapped.submoduleUpdate());
        }
        try (var walk = org.eclipse.jgit.submodule.SubmoduleWalk.forIndex(repo)) {
            while (walk.next()) {
                try (Repository nested = walk.getRepository()) {
                    if (nested != null) {
                        updateInitializedSubmodules(nested, depth + 1);
                    }
                }
            }
        }
    }

    /**
     * Whether the working tree has a {@code .gitmodules} at all.
     *
     * <p>One stat, in front of a full submodule status walk that every checkout and pull
     * would otherwise pay for - and the overwhelming majority of repositories have no
     * submodules, so the walk is a guaranteed no-op for them.
     */
    private boolean hasGitmodulesFile() {
        File workTree = repository() == null ? null : repository().getWorkTree();
        return workTree != null && new File(workTree, Constants.DOT_GIT_MODULES).isFile();
    }

    /**
     * Registers a new submodule and clones it.
     *
     * <p>Writes {@code .gitmodules} and stages it, so the caller still has to commit -
     * exactly what the git CLI does.
     */
    public GitResult addSubmodule(String url, String path) {
        if (git() == null) return GitResult.failed("No repository open");
        if (url == null || url.isBlank()) return GitResult.failed("A URL is required");
        if (path == null || path.isBlank()) return GitResult.failed("A path is required");
        try {
            Repository added = ctx.overNetwork(git().submoduleAdd()
                    .setURI(url.trim())
                    .setPath(path.trim()));
            if (added != null) {
                // submoduleAdd hands back an open repository we are not going to use.
                added.close();
            }
            return GitResult.ok("Added submodule " + path.trim() + " - commit .gitmodules to keep it");
        } catch (Exception e) {
            String said = "Could not add submodule: " + describe(e);
            return RefusalWords.inputFailure(said, RefusalWords.submoduleField(said));
        }
    }

    /**
     * Copies the URLs from {@code .gitmodules} into the repository config, which is
     * how a submodule picks up a remote that moved.
     */
    public GitResult syncSubmodules() {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            Map<String, String> synced = git().submoduleSync().call();
            if (synced.isEmpty()) {
                return GitResult.ok("No submodules to sync");
            }
            return GitResult.ok("Synced " + synced.size() + " submodule URL(s)");
        } catch (Exception e) {
            return GitResult.failed("Submodule sync failed: " + describe(e));
        }
    }

    /**
     * Removes a submodule's working copy and its config entry, leaving
     * {@code .gitmodules} alone - the {@code git submodule deinit} behaviour.
     *
     * @param force discard local changes inside the submodule
     */
    public GitResult deinitSubmodule(String path, boolean force) {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            // JGit's own SubmoduleDeinitCommand throws a bare NullPointerException from
            // deep inside SubmoduleWalk - "Cannot invoke SubmoduleWalk.getHead() because
            // w is null" - for a submodule that was added but never committed: it looks
            // the entry up by walking HEAD's tree, and an addition that only exists in
            // the index is not there yet to find. Confirmed by reproducing it with an
            // uncommitted addSubmodule() and watching it succeed once the same addition
            // was committed first. A real git submodule deinit does not have this
            // limitation, but this app goes through JGit; catching it upfront turns an
            // opaque stack trace into the actionable reason.
            if (repository().resolve(Constants.HEAD + ":" + path) == null) {
                return GitResult.failed(path + " has not been committed yet - JGit cannot "
                        + "deinitialise a submodule addition that is still only staged. "
                        + "Commit it first, or discard the addition by hand.");
            }
            var results = git().submoduleDeinit().addPath(path).setForce(force).call();
            for (var result : results) {
                switch (result.getStatus()) {
                    case SUCCESS, FORCED -> {
                        return GitResult.ok("Deinitialised " + path);
                    }
                    case DIRTY -> {
                        return GitResult.refused(path
                                + " has uncommitted changes - deinitialise with force to discard them",
                                new Refusal.LocalModifications(path));
                    }
                    case ALREADY_DEINITIALIZED -> {
                        return GitResult.ok(path + " was already deinitialised");
                    }
                    default -> {
                        return GitResult.failed("Could not deinitialise " + path
                                + ": " + result.getStatus());
                    }
                }
            }
            return GitResult.failed("No submodule at " + path);
        } catch (Exception e) {
            return GitResult.failed("Could not deinitialise " + path + ": " + describe(e));
        }
    }

    /**
     * The submodule's own working directory, for opening it as a repository of its own.
     * Null when it has not been cloned yet.
     */
    public File submoduleWorkTree(String path) {
        if (repository() == null) return null;
        File dir = new File(repository().getWorkTree(), path);
        return new File(dir, Constants.DOT_GIT).exists() ? dir : null;
    }

    // ------------------------------------------------------------------
    // Worktrees
    // ------------------------------------------------------------------

    /** Every working tree of this repository, main one first. */
    public List<Worktrees.Worktree> getWorktrees() {
        return Worktrees.list(repository());
    }

    public GitResult addWorktree(File path, String branch, boolean createBranch) {
        if (repository() == null) return GitResult.failed("No repository open");
        return Worktrees.add(repository(), path, branch, createBranch);
    }

    public GitResult removeWorktree(File path, boolean force) {
        if (repository() == null) return GitResult.failed("No repository open");
        return Worktrees.remove(repository(), path, force);
    }

    public GitResult pruneWorktrees() {
        if (repository() == null) return GitResult.failed("No repository open");
        return Worktrees.prune(repository());
    }

    public GitResult setWorktreeLocked(File path, boolean locked) {
        if (repository() == null) return GitResult.failed("No repository open");
        return Worktrees.setLocked(repository(), path, locked);
    }

    // ------------------------------------------------------------------
    // Maintenance and hooks
    // ------------------------------------------------------------------

    /**
     * Housekeeping, through JGit's own {@code gc}.
     *
     * <p>Not {@code git maintenance}, which JGit has no equivalent of - this is the
     * repacking half, which is the half that makes a neglected repository slow. The
     * result says which engine did the work, because "it took a while and packed
     * differently" is otherwise a mystery when the user switches engines.
     */
    public GitResult runMaintenance() {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            git().gc().setAggressive(false).call();
            return GitResult.ok("Housekeeping finished (JGit gc)");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Housekeeping failed", e);
            return GitResult.failed("Housekeeping failed: " + describe(e));
        }
    }

    /**
     * Whether there are enough loose objects for packing to be worth offering.
     *
     * <p>Counted by listing {@code .git/objects/xx} fan-out directories rather than by
     * walking every object: the question is "roughly how many", and the answer only has
     * to clear a threshold in the thousands. JGit exposes no count of its own.
     */
    public boolean needsMaintenance() {
        if (repository() == null) return false;
        File objects = new File(repository().getDirectory(), "objects");
        File[] fanOut = objects.listFiles(f -> f.isDirectory() && f.getName().length() == 2);
        if (fanOut == null) return false;
        long loose = 0;
        for (File bucket : fanOut) {
            String[] names = bucket.list();
            if (names != null) loose += names.length;
            // git's own default threshold; no point counting past it.
            if (loose >= LOOSE_OBJECT_THRESHOLD) return true;
        }
        return false;
    }

    /** git's {@code gc.auto} default: below this, packing achieves nothing worth saying. */
    private static final int LOOSE_OBJECT_THRESHOLD = 6700;

    /**
     * The hooks git would run here.
     *
     * <p>{@code core.hooksPath} wins when it is set - a shared team hooks directory is
     * exactly the case where the user does not know what is running on their commit.
     */
    public List<RepoStructureOps.Hook> getHooks() {
        if (repository() == null) return List.of();
        return NativeGitBackend.readHooks(ctx.hooksDirectory());
    }

    // ------------------------------------------------------------------
    // Sparse checkout (read-only: JGit cannot change it)
    // ------------------------------------------------------------------

    /**
     * Whether {@code core.sparseCheckout} is on - which JGit does not act on, but this
     * application still has to report. The defaulted {@code false} told the sidebar a
     * sparse repository was a full one on this engine.
     *
     * <p>{@code git sparse-checkout init} writes the setting to {@code config.worktree}
     * when {@code extensions.worktreeConfig} is on, and JGit does not read that file, so
     * it is consulted here directly.
     */
    public boolean isSparseCheckoutEnabled() {
        if (repository() == null) return false;
        return sparseSetting("sparseCheckout");
    }

    /**
     * The directories a cone-mode sparse checkout includes, as {@code git sparse-checkout
     * list} prints them; the raw patterns in non-cone mode, which is also what git prints.
     */
    public List<String> getSparseCheckoutPaths() {
        if (!isSparseCheckoutEnabled()) return List.of();
        File file = new File(repository().getCommonDirectory() != null
                ? repository().getCommonDirectory() : repository().getDirectory(), "info/sparse-checkout");
        // A linked worktree keeps its own patterns beside its own git directory.
        File own = new File(repository().getDirectory(), "info/sparse-checkout");
        if (own.isFile()) file = own;
        if (!file.isFile()) return List.of();
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            return sparseSetting("sparseCheckoutCone") ? coneDirectories(lines) : lines;
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not read the sparse-checkout patterns", e);
            return List.of();
        }
    }

    /**
     * Cone mode writes {@code /dir/} for every directory it includes, parents
     * included, and {@code !/dir/* /} after a parent that is only included for the path
     * through it. The recursive ones - the directories the user asked for - are the
     * {@code /dir/} lines with no such exclusion.
     */
    static List<String> coneDirectories(List<String> patterns) {
        java.util.Set<String> parents = new java.util.HashSet<>();
        for (String pattern : patterns) {
            if (pattern.startsWith("!/") && pattern.endsWith("/*/")) {
                parents.add(pattern.substring(1, pattern.length() - 2));
            }
        }
        List<String> dirs = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern.startsWith("/") && pattern.endsWith("/") && pattern.length() > 2
                    && !parents.contains(pattern)) {
                dirs.add(pattern.substring(1, pattern.length() - 1).replace("\\", ""));
            }
        }
        return dirs;
    }

    /** A {@code core.*} boolean from the repository config or, where git would, {@code config.worktree}. */
    private boolean sparseSetting(String name) {
        var config = repository().getConfig();
        boolean value = config.getBoolean("core", null, name, false);
        if (config.getBoolean("extensions", null, "worktreeConfig", false)) {
            File worktreeConfig = new File(repository().getDirectory(), "config.worktree");
            if (worktreeConfig.isFile()) {
                try {
                    var perWorktree = new org.eclipse.jgit.storage.file.FileBasedConfig(
                            worktreeConfig, repository().getFS());
                    perWorktree.load();
                    String raw = perWorktree.getString("core", null, name);
                    if (raw != null) value = EffectiveConfig.parseBoolean(raw, value);
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Could not read config.worktree", e);
                }
            }
        }
        return value;
    }

    // ------------------------------------------------------------------
    // Git LFS: JGit runs no git-lfs, so only the reading is done here
    // ------------------------------------------------------------------

    public List<String> lfsTrackedPatterns() {
        byte[] attributes = diffs().getWorkingTreeBytes(".gitattributes");
        return attributes == null || attributes.length == 0
                ? List.of()
                : List.copyOf(LfsOps.patternsIn(new String(attributes, StandardCharsets.UTF_8)));
    }

    public GitResult lfsInstall() { return GitResult.failed(LfsOps.NEEDS_NATIVE); }
    public GitResult lfsTrack(String pattern) { return GitResult.failed(LfsOps.NEEDS_NATIVE); }
    public GitResult lfsUntrack(String pattern) { return GitResult.failed(LfsOps.NEEDS_NATIVE); }
    public GitResult lfsFetch() { return GitResult.failed(LfsOps.NEEDS_NATIVE); }
    public GitResult lfsPull() { return GitResult.failed(LfsOps.NEEDS_NATIVE); }
    public GitResult lfsPrune() { return GitResult.failed(LfsOps.NEEDS_NATIVE); }
    public List<com.jfx.git.model.LfsLock> lfsLocks() { return List.of(); }
    public GitResult lfsLock(String path) { return GitResult.failed(LfsOps.NEEDS_NATIVE); }
    public GitResult lfsUnlock(String path, boolean force) { return GitResult.failed(LfsOps.NEEDS_NATIVE); }
}
