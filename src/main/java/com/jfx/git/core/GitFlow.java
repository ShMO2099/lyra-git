package com.jfx.git.core;

import com.jfx.git.model.MergeMode;
import com.jfx.git.model.RefInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The git-flow branching model, done with ordinary git operations.
 *
 * <p>git-flow is a convention: a production branch and a develop branch, with feature,
 * release and hotfix branches cut from one and folded back into one or both. The
 * {@code git flow} command that automates it is a separate install most machines do not
 * have. Nothing it does needs it - every step is a branch, a merge, or a tag - so this
 * class does the same steps through the {@link GitBackend} and reads and writes the same
 * {@code gitflow.*} configuration, which means a repository set up here works with the
 * command-line tool and one set up there works here.
 *
 * <h2>Finishing, and stopping</h2>
 * Finishing a branch is several steps, and any merge among them can conflict. A finish
 * that stops leaves the repository exactly where git left it - on the target branch, in
 * a merge - with a result that says which step stopped. Each step is skipped when it is
 * already done, so once the conflict is resolved and committed, finishing the same
 * branch again carries on from there rather than starting over.
 *
 * <p>Finishing checks out the branches it merges into, so local changes would stop it
 * part-way, or ride along onto develop by accident. A finish auto-stashes them before its
 * first step and puts them back after its last ({@link AutoStash}); {@link #finishPlan}
 * says beforehand whether it will. A finish that stops on a conflict leaves them in the
 * stash, and says to pop it once finishing is done.
 */
public final class GitFlow {

    /** The three kinds of branch git-flow manages. */
    public enum Kind {
        FEATURE("feature"), RELEASE("release"), HOTFIX("hotfix");

        private final String word;

        Kind(String word) {
            this.word = word;
        }

        /** The kind as a word in a sentence: "feature". */
        public String word() {
            return word;
        }
    }

    /**
     * A repository's git-flow settings.
     *
     * @param production       the branch releases are made from, {@code gitflow.branch.master}
     * @param develop          the branch work is integrated on, {@code gitflow.branch.develop}
     * @param versionTagPrefix put before a release's name to make its tag; often {@code v}
     *                         or nothing
     */
    public record Config(String production, String develop, String featurePrefix, String releasePrefix,
                         String hotfixPrefix, String versionTagPrefix) {

        /** git-flow's own defaults, on top of whichever production branch the repository has. */
        public static Config defaults(String production) {
            return new Config(production, "develop", "feature/", "release/", "hotfix/", "");
        }

        public String prefix(Kind kind) {
            return switch (kind) {
                case FEATURE -> featurePrefix;
                case RELEASE -> releasePrefix;
                case HOTFIX -> hotfixPrefix;
            };
        }
    }

    private static final String KEY_PRODUCTION = "gitflow.branch.master";
    private static final String KEY_DEVELOP = "gitflow.branch.develop";
    private static final String KEY_FEATURE = "gitflow.prefix.feature";
    private static final String KEY_RELEASE = "gitflow.prefix.release";
    private static final String KEY_HOTFIX = "gitflow.prefix.hotfix";
    private static final String KEY_VERSION_TAG = "gitflow.prefix.versiontag";

    private final GitBackend backend;

    public GitFlow(GitBackend backend) {
        this.backend = backend;
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------

    /**
     * This repository's git-flow settings, or {@code null} when it has not been set up
     * for git-flow: both branch names have to be configured.
     */
    public Config config() {
        String production = backend.getLocalConfig(KEY_PRODUCTION);
        String develop = backend.getLocalConfig(KEY_DEVELOP);
        if (isBlank(production) || isBlank(develop)) {
            return null;
        }
        return new Config(production.trim(), develop.trim(),
                orDefault(backend.getLocalConfig(KEY_FEATURE), "feature/"),
                orDefault(backend.getLocalConfig(KEY_RELEASE), "release/"),
                orDefault(backend.getLocalConfig(KEY_HOTFIX), "hotfix/"),
                orDefault(backend.getLocalConfig(KEY_VERSION_TAG), ""));
    }

    /**
     * Sets the repository up for git-flow: records the settings, and creates the develop
     * branch from the production branch when it does not exist yet.
     *
     * <p>The production branch has to exist already - it is where releases have been
     * going. Nothing is checked out.
     */
    public GitResult init(Config config) {
        if (config == null || isBlank(config.production()) || isBlank(config.develop())) {
            return GitResult.failed("Git Flow needs a production branch and a develop branch");
        }
        if (config.production().trim().equals(config.develop().trim())) {
            return GitResult.failed("The production branch and the develop branch have to be different branches");
        }
        for (Kind kind : Kind.values()) {
            if (isBlank(config.prefix(kind))) {
                return GitResult.failed("The " + kind.word() + " prefix cannot be empty: it is how a "
                        + kind.word() + " branch is told from any other");
            }
        }
        List<String> branches = localBranches();
        String production = config.production().trim();
        String develop = config.develop().trim();
        if (!branches.contains(production)) {
            return GitResult.failed("There is no branch called " + production + " to make releases from");
        }
        if (!branches.contains(develop)) {
            GitResult created = backend.createBranch(develop, production, false);
            if (!created.isOk()) return created;
        }
        String[][] settings = {
                {KEY_PRODUCTION, production}, {KEY_DEVELOP, develop},
                {KEY_FEATURE, config.featurePrefix().trim()}, {KEY_RELEASE, config.releasePrefix().trim()},
                {KEY_HOTFIX, config.hotfixPrefix().trim()},
        };
        for (String[] setting : settings) {
            GitResult stored = backend.setLocalConfig(setting[0], setting[1]);
            if (!stored.isOk()) return stored;
        }
        // An empty prefix is a real choice, and an unset key reads back as one.
        GitResult stored = backend.setLocalConfig(KEY_VERSION_TAG,
                config.versionTagPrefix() == null ? "" : config.versionTagPrefix().trim());
        if (!stored.isOk()) return stored;
        return GitResult.ok("Git Flow is set up: " + develop + " for work, " + production + " for releases");
    }

    // ------------------------------------------------------------------
    // Branches
    // ------------------------------------------------------------------

    /** Which kind of flow branch {@code branch} is by its prefix, or {@code null} for any other branch. */
    public static Kind kindOf(Config config, String branch) {
        if (config == null || branch == null) return null;
        for (Kind kind : Kind.values()) {
            String prefix = config.prefix(kind);
            if (!isBlank(prefix) && branch.startsWith(prefix) && branch.length() > prefix.length()) {
                return kind;
            }
        }
        return null;
    }

    /** The part of a flow branch's name after its prefix: the feature's name, the release's version. */
    public static String nameOf(Config config, String branch) {
        Kind kind = kindOf(config, branch);
        return kind == null ? branch : branch.substring(config.prefix(kind).length());
    }

    /** The local branches that are features, releases or hotfixes, in the order git lists them. */
    public List<String> flowBranches() {
        Config config = config();
        if (config == null) return List.of();
        List<String> flow = new ArrayList<>();
        for (String branch : localBranches()) {
            if (kindOf(config, branch) != null) flow.add(branch);
        }
        return flow;
    }

    /**
     * Starts a feature, release or hotfix: a new branch, checked out.
     *
     * <p>Features and releases start from develop, where the work for them is. A hotfix
     * starts from the production branch: it repairs what is released, and must not
     * carry along whatever develop has gathered since.
     */
    public GitResult start(Kind kind, String name) {
        Config config = config();
        if (config == null) {
            return GitResult.failed("This repository is not set up for Git Flow yet");
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.chars().anyMatch(Character::isWhitespace)) {
            return GitResult.failed("A " + kind.word() + " needs a name with no spaces in it");
        }
        String branch = config.prefix(kind) + trimmed;
        if (localBranches().contains(branch)) {
            return GitResult.failed("There is already a branch called " + branch);
        }
        String base = kind == Kind.HOTFIX ? config.production() : config.develop();
        GitResult created = backend.createBranch(branch, base, true);
        return created.isOk()
                ? GitResult.ok("Started " + kind.word() + " " + trimmed + " from " + base)
                : created;
    }

    /**
     * What finishing {@code branch} will do, a sentence per step, for showing before it
     * is done. Empty for a branch that is not a flow branch.
     */
    public List<String> finishSteps(String branch) {
        Config config = config();
        Kind kind = kindOf(config, branch);
        if (kind == null) return List.of();
        List<String> steps = new ArrayList<>();
        if (kind == Kind.FEATURE) {
            steps.add("Merge " + branch + " into " + config.develop());
        } else {
            steps.add("Merge " + branch + " into " + config.production());
            steps.add("Tag " + config.production() + " as " + tagFor(config, branch));
            steps.add("Merge " + branch + " into " + config.develop());
        }
        steps.add("Delete " + branch);
        return steps;
    }

    /**
     * What finishing a branch will do, for showing before it is done.
     *
     * @param steps        a sentence per step; empty for a branch that is not a flow branch
     * @param stashesFirst whether there are local changes the finish auto-stashes before it
     *                     starts and puts back once it is done
     */
    public record FinishPlan(List<String> steps, boolean stashesFirst) {
        public FinishPlan {
            steps = List.copyOf(steps);
        }
    }

    /** {@link #finishSteps}, and whether finishing {@code branch} now would auto-stash first. */
    public FinishPlan finishPlan(String branch) {
        List<String> steps = finishSteps(branch);
        return new FinishPlan(steps, !steps.isEmpty() && AutoStash.hasChangesToStash(backend));
    }

    /** The tag a release or hotfix branch is finished with. */
    public static String tagFor(Config config, String branch) {
        return (config.versionTagPrefix() == null ? "" : config.versionTagPrefix()) + nameOf(config, branch);
    }

    /**
     * Finishes a feature, release or hotfix; see {@link #finishSteps} for what that is.
     *
     * <p>Merges are never fast-forwards: the merge commit is what records that a feature
     * or a release happened, which is the point of the model. Local changes are
     * auto-stashed around the whole of it; see the class comment.
     *
     * @param tagMessage the message for a release's or hotfix's tag; blank uses
     *                   "Release 1.2" or "Hotfix 1.2.1"
     * @return a conflict when a merge stopped - resolve, commit, and finish again
     */
    public GitResult finish(String branch, String tagMessage) {
        Config config = config();
        if (config == null) {
            return GitResult.failed("This repository is not set up for Git Flow yet");
        }
        Kind kind = kindOf(config, branch);
        if (kind == null) {
            return GitResult.failed(branch + " is not a feature, release or hotfix branch");
        }
        if (!localBranches().contains(branch)) {
            return GitResult.failed("There is no branch called " + branch);
        }
        // Up front rather than on a refusal: one part-way would leave the finish half
        // done, and running it again would repeat the steps that had worked.
        return AutoStash.stashAround(backend, AutoStash.Operation.FINISH,
                () -> finishAll(config, kind, branch, tagMessage));
    }

    /** {@link #finish}'s steps, for a branch already known to be a flow branch of {@code kind}. */
    private GitResult finishAll(Config config, Kind kind, String branch, String tagMessage) {
        if (kind != Kind.FEATURE) {
            GitResult merged = mergeInto(config.production(), branch);
            if (!merged.isOk()) return merged;

            String tag = tagFor(config, branch);
            boolean tagged = backend.getTags().stream().anyMatch(ref -> ref.shortName().equals(tag));
            if (!tagged) {
                String word = kind.word().substring(0, 1).toUpperCase(Locale.ROOT) + kind.word().substring(1);
                String message = isBlank(tagMessage) ? word + " " + nameOf(config, branch) : tagMessage.trim();
                GitResult created = backend.createTag(tag, message, config.production());
                if (!created.isOk()) return created;
            }
        }

        GitResult merged = mergeInto(config.develop(), branch);
        if (!merged.isOk()) return merged;

        GitResult deleted = backend.deleteBranch(branch, false);
        if (!deleted.isOk()) return deleted;
        return GitResult.ok("Finished " + kind.word() + " " + nameOf(config, branch));
    }

    /** Checks out {@code target} and merges {@code source} into it with a merge commit. */
    private GitResult mergeInto(String target, String source) {
        GitResult checkedOut = backend.checkoutBranch(target);
        if (!checkedOut.isOk()) return checkedOut;
        GitResult merged = backend.merge(source, MergeMode.NO_FF, null);
        if (merged.isConflict()) {
            return GitResult.conflict("Finishing stopped while merging " + source + " into " + target
                    + ". Resolve the conflicts and commit, then finish " + source + " again to carry on.",
                    merged.getConflicts());
        }
        return merged;
    }

    private List<String> localBranches() {
        return backend.getLocalBranches().stream().map(RefInfo::shortName).toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value.trim();
    }
}
