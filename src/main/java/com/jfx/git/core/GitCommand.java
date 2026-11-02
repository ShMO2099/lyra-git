package com.jfx.git.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * A git command line whose revisions and paths cannot be read as options.
 *
 * <p>Revisions and paths reach the native engine from the UI - a branch from the sidebar,
 * a commit from the graph, a path from the staging list - and git parses any argument that
 * begins with a dash as an option. So the builder keeps three kinds of argument apart and
 * lays them out the one safe way:
 * <pre>
 *   git &lt;subcommand&gt; &lt;options...&gt; --end-of-options &lt;revisions...&gt; -- &lt;paths...&gt;
 * </pre>
 * {@code --end-of-options} goes in only when there are revisions, {@code --} only when
 * there are paths or {@link #endOfRevisions()} asks for it. The second matters on its own:
 * without a {@code --}, a name that is not a revision is quietly taken as a path - which is
 * how {@code reset --mixed notes} unstaged a file called {@code notes} and reported a
 * reset, and how {@code checkout gone-branch} restored a folder of that name.
 *
 * <h2>Where not to use it</h2>
 * <ul>
 *   <li>{@code blame}: it reads {@code --end-of-options} as the start of its revision
 *       arguments and then takes the path after {@code --} for one as well ("bad
 *       revision"), still in git 2.56;</li>
 *   <li>{@code rev-parse}: it learned {@code --end-of-options} only after
 *       {@link NativeGitRunner#MINIMUM_VERSION};</li>
 *   <li>a command whose revisions arrive on standard input, or as an option's value
 *       ({@code --source=}, {@code --contains}), where there is nothing to separate.</li>
 * </ul>
 * Every command it is used for parses its options with git's parse-options or revision
 * machinery, which both accept {@code --end-of-options} from git 2.24 on.
 */
final class GitCommand {

    private final List<String> options = new ArrayList<>();
    private final List<String> revisions = new ArrayList<>();
    private final List<String> paths = new ArrayList<>();
    private boolean endOfRevisions;

    private GitCommand() {
    }

    /** {@code git <subcommandAndOptions...>}: the subcommand, then any options. */
    static GitCommand of(String... subcommandAndOptions) {
        return new GitCommand().opts(subcommandAndOptions);
    }

    /** More options, in order. */
    GitCommand opts(String... more) {
        options.addAll(Arrays.asList(more));
        return this;
    }

    /** More options, in order. */
    GitCommand opts(Collection<String> more) {
        options.addAll(more);
        return this;
    }

    /** Options added only when {@code condition} holds. */
    GitCommand optsIf(boolean condition, String... more) {
        return condition ? opts(more) : this;
    }

    /** Revisions - commits, branches, ranges - in order. */
    GitCommand revs(String... more) {
        revisions.addAll(Arrays.asList(more));
        return this;
    }

    /** Revisions, in order. */
    GitCommand revs(Collection<String> more) {
        revisions.addAll(more);
        return this;
    }

    /** Paths, in order, after a {@code --}. */
    GitCommand paths(String... more) {
        paths.addAll(Arrays.asList(more));
        return this;
    }

    /** Paths, in order, after a {@code --}. */
    GitCommand paths(Collection<String> more) {
        paths.addAll(more);
        return this;
    }

    /**
     * A {@code --} after the revisions even when no path follows, so that each of them has
     * to be a revision: git otherwise falls back to reading one that does not resolve as a
     * path.
     */
    GitCommand endOfRevisions() {
        endOfRevisions = true;
        return this;
    }

    /** The arguments to hand to {@link NativeGitRunner}, without the leading {@code git}. */
    List<String> args() {
        List<String> args = new ArrayList<>(options);
        if (!revisions.isEmpty()) {
            args.add(CliRepo.END_OF_OPTIONS);
            args.addAll(revisions);
        }
        if (!paths.isEmpty() || endOfRevisions) {
            args.add("--");
            args.addAll(paths);
        }
        return args;
    }

    @Override
    public String toString() {
        return "git " + String.join(" ", args());
    }
}
