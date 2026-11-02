package com.jfx.git.core;

import com.jfx.git.model.StashOptions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * The stash on the native engine.
 *
 * <p>Package-private: reached only through {@link NativeGitBackend}, which is the
 * engine's public face.
 */
final class CliStash {

    private static final Logger LOGGER = Logger.getLogger(NativeGitBackend.class.getName());

    private final CliRepo repo;

    CliStash(CliRepo repo) {
        this.repo = repo;
    }

    /**
     * The stash as graph-placeable entries.
     *
     * <p>{@code %P} on a stash lists its parents: the first is the commit it was taken
     * from, which is where the graph should hang it. The others are the index and
     * untracked-files commits git creates as part of the stash, and they are not places
     * in the branch's history.
     */
    public List<com.jfx.git.model.StashEntry> getStashEntries() {
        repo.ensureOpen();
        // -z ends each entry with a NUL, which no reflog message can contain, and the
        // message goes last so a separator inside it stays inside it. The index is git's
        // own, %gd's stash@{N}, not a count of the entries that parsed.
        //
        // That count is what this used to be, over records split on RS and US - which a
        // stash message is free to contain. One such message split into extra records or
        // was skipped, every stash after it got the index of the one before, and
        // stashDrop(hash), which resolves the position from this list, dropped a stash the
        // user had not chosen.
        var res = repo.runner.run("stash", "list", "-z",
                "--format=%gd%x1f%H%x1f%P%x1f%an%x1f%at%x1f%gs");
        if (!res.isOk()) return List.of();

        return CliParsers.parseStashList(res.stdout());
    }

    public GitResult stash(StashOptions options) {
        repo.ensureOpen();
        StashOptions o = options == null ? StashOptions.everything(null) : options;
        String problem = o.problem();
        if (problem != null) return GitResult.failed(problem);

        List<String> args = new ArrayList<>(List.of("stash", "push"));
        if (o.includeUntracked()) args.add("-u");
        if (o.keepIndex()) args.add("--keep-index");
        // -m's value as its own argument: a message beginning with a dash is otherwise
        // read as a flag, and "-m" glued to the text is not a form git accepts for this
        // option.
        // The one message still passed as an argument: `stash push` has no -F, and
        // building the stash by hand would lose -u's handling of untracked files. The
        // runner quotes it correctly on Windows (NativeGitRunner.windowsArgument) and
        // refuses a command line too long to run, rather than letting either garble it.
        if (o.hasMessage()) {
            args.add("-m");
            args.add(o.strippedMessage());
        }
        // The user's paths on stdin, NUL-separated, so no list of selected files is too
        // long for a command line and no name needs quoting.
        byte[] pathspecs = null;
        if (o.isPartial()) {
            args.add("--pathspec-from-file=-");
            args.add("--pathspec-file-nul");
            pathspecs = literalPathspecs(o.paths());
        }

        // Pathspec magic back on for this one command: `stash push -u` clears the
        // untracked files it saved by running `git clean` over the ":/" pathspec, which
        // GIT_LITERAL_PATHSPECS turns into a path nothing matches - the untracked files
        // were stashed and also left behind. With magic on, the user's paths carry
        // :(literal) themselves, so "*.txt" is still one file called that.
        String before = stashTip();
        var res = repo.runner.runWithInputAndEnvironment(pathspecs, NativeGitRunner.PATHSPEC_MAGIC, args);
        if (!res.isOk()) return GitResult.failed(res.stderr());
        // "No local changes to save" is exit 0, and translated. Whether refs/stash moved
        // is the answer in any language - and the JGit engine's is a failure.
        if (java.util.Objects.equals(before, stashTip())) {
            return GitResult.failed(GitService.NOTHING_TO_STASH);
        }
        return GitResult.ok(GitService.stashedMessage(o));
    }

    /** What {@code refs/stash} points at, or null when there is no stash. */
    private String stashTip() {
        var res = repo.runner.run("rev-parse", "-q", "--verify", "refs/stash");
        return res.isOk() ? res.trimmedStdout() : null;
    }

    /**
     * {@code :(literal)}-prefixed, NUL-terminated pathspecs for a command running with
     * {@link NativeGitRunner#PATHSPEC_MAGIC}.
     */
    private static byte[] literalPathspecs(List<String> paths) {
        StringBuilder input = new StringBuilder();
        for (String path : paths) {
            input.append(":(literal)").append(path).append('\0');
        }
        return input.toString().getBytes(StandardCharsets.UTF_8);
    }

    public GitResult stashPop(int index) {
        repo.ensureOpen();
        var res = repo.runner.run("stash", "pop", "stash@{" + index + "}");
        return res.isOk()
                ? GitResult.ok("Popped stash")
                : repo.integration.conflictOrFailed("Stash could not be applied cleanly", res);
    }

    public GitResult stashApply(int index) {
        repo.ensureOpen();
        var res = repo.runner.run("stash", "apply", "stash@{" + index + "}");
        return res.isOk()
                ? GitResult.ok("Applied stash")
                : repo.integration.conflictOrFailed("Stash could not be applied cleanly", res);
    }

    public GitResult stashDrop(int index) {
        repo.ensureOpen();
        var res = repo.runner.run("stash", "drop", "stash@{" + index + "}");
        return res.isOk() ? GitResult.ok("Dropped stash") : GitResult.failed(res.stderr());
    }

    /**
     * {@code git stash branch}, given {@code stash@{n}} rather than a hash: git drops the
     * stash afterwards only when it was named as a stash entry, and would otherwise leave
     * a hash-addressed one in the list after applying it.
     */
    public GitResult stashBranch(String branchName, int index) {
        repo.ensureOpen();
        String name = branchName == null ? "" : branchName.strip();
        if (name.isEmpty()) return GitResult.failed("A branch name is required");
        if (!repo.runner.run("check-ref-format", "--branch", name).isOk()) {
            return GitResult.failed("'" + name + "' is not a valid branch name");
        }
        if (repo.runner.run("rev-parse", "-q", "--verify", "refs/heads/" + name).isOk()) {
            return GitResult.failed("A branch named '" + name + "' already exists");
        }
        var res = repo.runner.run("stash", "branch", name, "stash@{" + index + "}");
        if (res.isOk()) {
            return GitResult.ok("Created " + name + " from the stash and dropped it");
        }
        // Checked out but not applied cleanly: the stash is kept, as git keeps it.
        // Not checked out: git refused the switch, for local changes in the way as a rule.
        return name.equals(repo.branches.getCurrentBranch())
                ? repo.integration.conflictOrFailed("Created " + name + ", but the stash did not apply cleanly"
                        + " and has been kept", res)
                : RefusalWords.failure(res.stderr());
    }
}
