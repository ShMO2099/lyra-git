package com.jfx.git.core;

import java.util.List;
import java.util.Locale;

/**
 * Where a push of the current branch goes: which remote, which branch on it, and whether
 * this push is the one that publishes the branch.
 *
 * <h2>Why this is worked out rather than assumed</h2>
 * Both engines used to push {@code refs/heads/B:refs/heads/B} to {@code origin} with
 * {@code -u} on every press of Push. That ignored everything the repository says about
 * where a branch belongs, and the {@code -u} then rewrote it: a fork workflow's
 * {@code feature} tracking {@code upstream/main} got a new {@code origin/feature} and its
 * tracking quietly re-pointed there, and a repository whose only remote is called
 * {@code github} could not push at all. This follows the configuration git itself reads,
 * in git's order, so pressing Push does what {@code git push} would.
 *
 * <h2>The rules</h2>
 * <ol>
 *   <li><b>Remote:</b> an explicit one from the caller; else {@code branch.B.pushRemote};
 *       else {@code remote.pushDefault}; else {@code branch.B.remote}; else the only
 *       remote there is; else {@code origin}.</li>
 *   <li><b>Branch on the remote:</b> the upstream's branch ({@code branch.B.merge}) when
 *       {@code push.default} is {@code upstream} (or its old name {@code tracking}) and
 *       the push goes to the upstream's own remote. Otherwise the same name.
 *       <p>Under {@code simple} - git's default - git refuses outright when the upstream
 *       has a different name, because a branch made with {@code git checkout -b fix
 *       origin/main} tracks {@code main}, and pushing it there by default would publish
 *       unreviewed work onto {@code main}. Refusing would leave the Push button useless
 *       for that branch, so the same-name branch is pushed instead and the upstream is
 *       left alone: nothing on the remote is overwritten that the user did not name.</li>
 *   <li><b>Publishing:</b> {@code -u} only when the branch has no upstream yet. A branch
 *       that already tracks something keeps tracking it.</li>
 * </ol>
 *
 * <p>Shared by both engines so the answer cannot drift between them; each supplies its
 * own way of reading configuration.
 *
 * @param remote       the remote name (or URL, when {@code pushRemote} names one) to push to
 * @param remoteBranch the short name of the branch on that remote
 * @param publish      whether the branch has no upstream yet, so this push should record one
 */
public record PushTarget(String remote, String remoteBranch, boolean publish) {

    /** Reads one configuration value; {@code null} when it is not set. */
    @FunctionalInterface
    public interface ConfigLookup {
        /**
         * @param section    e.g. {@code branch}
         * @param subsection e.g. the branch name, or {@code null}
         * @param name       e.g. {@code pushRemote}
         */
        String get(String section, String subsection, String name);
    }

    /** The full ref the push writes on the remote. */
    public String remoteRef() {
        return "refs/heads/" + remoteBranch;
    }

    /** The remote-tracking ref this clone keeps for {@link #remoteRef()}. */
    public String trackingRef() {
        return "refs/remotes/" + remote + "/" + remoteBranch;
    }

    /**
     * Works out where {@code branch} is pushed.
     *
     * @param branch         the checked-out branch's short name
     * @param explicitRemote a remote the caller chose, or {@code null}/blank to follow config
     * @param remotes        the configured remote names, in configuration order
     * @param config         how to read configuration
     * @return where to push, never {@code null}; the remote is {@code origin} when nothing
     *         else can be found, which then fails with git's own "no such remote" message
     */
    public static PushTarget resolve(String branch, String explicitRemote, List<String> remotes,
                                     ConfigLookup config) {
        String upstreamRemote = blankToNull(config.get("branch", branch, "remote"));
        String upstreamMerge = blankToNull(config.get("branch", branch, "merge"));

        String remote = blankToNull(explicitRemote);
        if (remote == null) remote = blankToNull(config.get("branch", branch, "pushRemote"));
        if (remote == null) remote = blankToNull(config.get("remote", null, "pushDefault"));
        // "." is git's way of saying "this repository" - a branch tracking another local
        // branch. There is nowhere to push that to.
        if (remote == null && upstreamRemote != null && !".".equals(upstreamRemote)) {
            remote = upstreamRemote;
        }
        if (remote == null) {
            if (remotes != null && remotes.size() == 1) {
                remote = remotes.get(0);
            } else {
                remote = "origin";
            }
        }

        String mode = blankToNull(config.get("push", null, "default"));
        mode = mode == null ? "simple" : mode.toLowerCase(Locale.ROOT);
        String remoteBranch = branch;
        boolean toUpstreamRemote = remote.equals(upstreamRemote);
        if (toUpstreamRemote && upstreamMerge != null
                && ("upstream".equals(mode) || "tracking".equals(mode))) {
            remoteBranch = shortBranch(upstreamMerge);
        }

        boolean hasUpstream = upstreamRemote != null && upstreamMerge != null;
        return new PushTarget(remote, remoteBranch, !hasUpstream);
    }

    private static String shortBranch(String ref) {
        return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
