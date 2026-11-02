package com.jfx.git.core;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Linked working trees - one repository checked out at several commits at once.
 *
 * <p>JGit has no worktree API at all, so this class is split by what each half can
 * honestly do:
 *
 * <ul>
 *   <li><b>Listing is native.</b> The layout under {@code .git/worktrees} is a handful
 *       of plain files, so the list always works - even on a machine with no git
 *       installed, which is the whole point of a JGit-based client.</li>
 *   <li><b>Creating and removing shell out to git.</b> Building a worktree means
 *       writing the administrative files, the {@code .git} pointer file and a full
 *       checkout, and reimplementing that against JGit's internals would be a
 *       reliability problem, not a feature. When git is not on PATH those actions
 *       say so rather than failing obscurely.</li>
 * </ul>
 */
public final class Worktrees {

    private static final Logger LOGGER = Logger.getLogger(Worktrees.class.getName());

    private Worktrees() {
    }

    /**
     * One working tree of a repository.
     *
     * @param name     administrative name; the main tree has none, so it uses "(main)"
     * @param path     the working directory
     * @param branch   the checked-out branch, or the short commit id when detached
     * @param main     whether this is the repository's original working tree
     * @param locked   the user ran {@code git worktree lock} on it
     * @param detached HEAD points at a commit rather than a branch
     */
    public record Worktree(String name,
                           File path,
                           String branch,
                           boolean main,
                           boolean locked,
                           boolean detached) {

        /** True when the directory has gone away and the entry only needs pruning. */
        public boolean missing() {
            return !path.isDirectory();
        }

        public String describe() {
            if (missing()) return "missing - prune it";
            if (locked) return branch + " (locked)";
            if (detached) return "detached at " + branch;
            return branch;
        }
    }

    /**
     * Every working tree of {@code repository}, main one first.
     *
     * <p>Never throws: a repository with no linked worktrees returns just the main
     * entry, and an unreadable administrative file is skipped rather than fatal.
     */
    static List<Worktree> list(Repository repository) {
        List<Worktree> result = new ArrayList<>();
        if (repository == null) {
            return result;
        }

        try {
            result.add(mainWorktree(repository));
        } catch (Exception e) {
            // A bare repository has no main working tree; the linked ones still count.
            LOGGER.log(Level.FINE, "No main worktree", e);
        }

        File admin = new File(repository.getCommonDirectory(), "worktrees");
        File[] entries = admin.listFiles(File::isDirectory);
        if (entries != null) {
            for (File entry : entries) {
                Worktree parsed = parse(entry);
                if (parsed != null) {
                    result.add(parsed);
                }
            }
        }

        // Main first, then alphabetical - the order git itself prints.
        result.sort(Comparator.comparing((Worktree w) -> !w.main())
                .thenComparing(Worktree::name));
        return result;
    }

    /**
     * Reads one {@code .git/worktrees/<name>} directory.
     *
     * <p>{@code gitdir} holds the path of the worktree's own {@code .git} <em>file</em>,
     * so the working directory is its parent.
     */
    private static Worktree parse(File admin) {
        try {
            File gitdirFile = new File(admin, "gitdir");
            if (!gitdirFile.isFile()) {
                return null;
            }
            String pointer = Files.readString(gitdirFile.toPath(), StandardCharsets.UTF_8).trim();
            if (pointer.isEmpty()) {
                return null;
            }
            // git 2.48+ with worktree.useRelativePaths writes this relative to the
            // administrative directory itself. Read as-is, a relative path resolved against
            // the application's own working directory and every such worktree showed as
            // "missing - prune it" - an invitation to delete a perfectly good worktree.
            File dotGit = new File(pointer);
            if (!dotGit.isAbsolute()) {
                dotGit = admin.toPath().resolve(pointer).normalize().toFile();
            }
            File workTree = dotGit.getParentFile();
            if (workTree == null) {
                return null;
            }

            String head = readHead(new File(admin, Constants.HEAD));
            boolean detached = !head.startsWith(Constants.R_HEADS);
            String branch = detached
                    ? (head.length() > 7 ? head.substring(0, 7) : head)
                    : head.substring(Constants.R_HEADS.length());

            return new Worktree(admin.getName(), workTree, branch, false,
                    new File(admin, "locked").exists(), detached);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Unreadable worktree entry " + admin, e);
            return null;
        }
    }

    private static String readHead(File headFile) throws Exception {
        if (!headFile.isFile()) {
            return "";
        }
        String content = Files.readString(headFile.toPath(), StandardCharsets.UTF_8).trim();
        return content.startsWith("ref:") ? content.substring(4).trim() : content;
    }

    /**
     * The repository's own working tree - which is not necessarily the one open.
     *
     * <p>Opened from a linked worktree, {@code repository.getWorkTree()} and
     * {@code getBranch()} describe <em>that</em> worktree, and reading them here labelled
     * the linked tree "(main)" with its own branch, while the real main tree was missing
     * from the list. So the main tree is read from the common directory instead: its
     * {@code HEAD}, and its location - {@code core.worktree} if set, otherwise the
     * directory that holds {@code .git}.
     */
    private static Worktree mainWorktree(Repository repository) throws Exception {
        File common = repository.getCommonDirectory();
        File own = repository.getDirectory();
        boolean openedFromLinked = common != null && own != null
                && !common.getCanonicalFile().equals(own.getCanonicalFile());
        if (!openedFromLinked) {
            File tree = repository.getWorkTree();
            String branch = repository.getBranch();
            if (branch == null) {
                return new Worktree("(main)", tree, "(unborn)", true, false, false);
            }
            var head = repository.resolve(Constants.HEAD);
            boolean detached = head != null && branch.equals(head.getName());
            return new Worktree("(main)", tree,
                    detached ? branch.substring(0, 7) : branch, true, false, detached);
        }

        File tree = mainWorkTreeOf(common);
        if (tree == null) {
            throw new IllegalStateException("The main repository is bare");
        }
        String head = readHead(new File(common, Constants.HEAD));
        if (head.isEmpty()) {
            return new Worktree("(main)", tree, "(unborn)", true, false, false);
        }
        boolean detached = !head.startsWith(Constants.R_HEADS);
        String branch = detached
                ? (head.length() > 7 ? head.substring(0, 7) : head)
                : head.substring(Constants.R_HEADS.length());
        return new Worktree("(main)", tree, branch, true, false, detached);
    }

    /**
     * Where the main working tree of the repository whose common directory is
     * {@code common} lives, or {@code null} when it is bare.
     */
    private static File mainWorkTreeOf(File common) {
        try {
            var config = new org.eclipse.jgit.storage.file.FileBasedConfig(
                    new File(common, "config"), org.eclipse.jgit.util.FS.DETECTED);
            config.load();
            if (config.getBoolean("core", null, "bare", false)) {
                return null;
            }
            String worktree = config.getString("core", null, "worktree");
            if (worktree != null && !worktree.isBlank()) {
                File configured = new File(worktree);
                return configured.isAbsolute()
                        ? configured
                        : common.toPath().resolve(worktree).normalize().toFile();
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not read the main repository's config", e);
        }
        return common.getParentFile();
    }

    // ------------------------------------------------------------------
    // Mutations, via the git CLI
    // ------------------------------------------------------------------

    /** Whether {@code git} can be run, which is what the mutating actions need. */
    public static boolean cliAvailable() {
        return NativeGitRunner.isGitInstalled();
    }

    /**
     * Creates a worktree at {@code path}.
     *
     * @param branch       branch to check out there; blank means detach at HEAD
     * @param createBranch create {@code branch} rather than checking out an existing one
     */
    static GitResult add(Repository repository, File path, String branch, boolean createBranch) {
        if (repository == null) return GitResult.failed("No repository open");
        if (path == null || path.getPath().isBlank()) return GitResult.failed("A path is required");
        if (path.exists() && path.list() != null && path.list().length > 0) {
            // git would refuse anyway; saying so here is a clearer message.
            return GitResult.refused(path + " already exists and is not empty",
                    new Refusal.BadInput(Refusal.Field.PATH));
        }

        List<String> args = new ArrayList<>(List.of("worktree", "add"));
        if (createBranch) {
            if (branch == null || branch.isBlank()) {
                return GitResult.refused("A branch name is required to create one",
                        new Refusal.BadInput(Refusal.Field.BRANCH));
            }
            args.add("-b");
            args.add(branch.trim());
            args.add(path.getPath());
        } else if (branch == null || branch.isBlank()) {
            args.add("--detach");
            args.add(path.getPath());
        } else {
            args.add(path.getPath());
            args.add(branch.trim());
        }

        GitResult result = run(workDir(repository), args.toArray(new String[0]));
        return result.isOk() ? GitResult.ok("Created worktree at " + path)
                : RefusalWords.inputFailure(result.getMessage(), RefusalWords.worktreeField(result.getMessage()));
    }

    /** Removes a worktree's directory and its administrative entry. */
    static GitResult remove(Repository repository, File path, boolean force) {
        if (repository == null) return GitResult.failed("No repository open");
        List<String> args = new ArrayList<>(List.of("worktree", "remove"));
        if (force) {
            args.add("--force");
        }
        args.add(path.getPath());

        GitResult result = run(workDir(repository), args.toArray(new String[0]));
        return result.isOk() ? GitResult.ok("Removed worktree " + path)
                : RefusalWords.workingCopyFailure(result.getMessage(), path.getPath());
    }

    /** Drops administrative entries whose directories are gone. */
    static GitResult prune(Repository repository) {
        if (repository == null) return GitResult.failed("No repository open");
        GitResult result = run(workDir(repository), "worktree", "prune");
        return result.isOk() ? GitResult.ok("Pruned stale worktree entries") : result;
    }

    /** Locking stops {@code prune} removing a worktree on removable media. */
    static GitResult setLocked(Repository repository, File path, boolean locked) {
        if (repository == null) return GitResult.failed("No repository open");
        GitResult result = run(workDir(repository),
                "worktree", locked ? "lock" : "unlock", path.getPath());
        return result.isOk()
                ? GitResult.ok((locked ? "Locked " : "Unlocked ") + path)
                : result;
    }

    private static File workDir(Repository repository) {
        try {
            return repository.getWorkTree();
        } catch (Exception e) {
            return repository.getCommonDirectory();
        }
    }

    /**
     * Runs git and turns its exit status into a {@link GitResult}.
     *
     * <h2>Why this delegates</h2>
     * This used to spawn its own process, and so was a second git invoker sitting next to
     * {@link NativeGitRunner} without any of what that one exists to provide: no C message
     * locale ({@code LC_MESSAGES=C}, see {@link NativeGitRunner#applyLocale}), which made
     * the "last non-empty line" error message below depend on the user's language; no
     * {@code GIT_TERMINAL_PROMPT=0}, so an authenticated operation
     * could block on a prompt nobody can answer until it was killed; no
     * {@code GIT_OPTIONAL_LOCKS}; no per-command timeouts; and no cancellation. Every one
     * of those is a problem {@code NativeGitRunner} had already solved once.
     *
     * <p>Note what this does <em>not</em> change: worktree mutations still shell out on
     * every engine, including JGit, because JGit has no worktree API to implement them
     * against. That is a real exception to "JGit needs no native git at all", so the engine
     * picker in Preferences says so rather than leaving the user to find out when a
     * worktree action fails on a machine with no git.
     *
     * <p>git writes the useful part of a failure to stderr, and falls back to stdout for
     * the commands that report there, so the last non-empty line of whichever spoke
     * becomes the message - anything else would show the user "exit code 128".
     */
    private static GitResult run(File directory, String... args) {
        // Asked first so a missing git is reported as a missing git. Folded together, as
        // it was, any IO failure at all - a permission problem, a bad working directory -
        // came out as "not found on PATH" and sent the reader looking in the wrong place.
        if (!NativeGitRunner.isGitInstalled()) {
            return GitResult.failed("Worktrees need the git command line, "
                    + "which was not found on PATH");
        }

        NativeGitRunner.Result result = new NativeGitRunner(directory).run(args);
        if (!result.isOk()) {
            String spoke = !result.stderr().isBlank() ? result.stderr() : result.stdout();
            return GitResult.failed(lastLine(spoke,
                    "git " + args[0] + " exited with " + result.exitCode()));
        }
        return GitResult.ok(lastLine(result.stdout(), "Done"));
    }

    static String lastLine(String output, String fallback) {
        if (output == null) return fallback;
        String[] lines = output.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].trim();
            }
        }
        return fallback;
    }
}
