package com.jfx.git.model;

/**
 * Backend-agnostic view of a Git reference (branch, tag, or remote-tracking branch).
 *
 * <p>Replaces {@code org.eclipse.jgit.lib.Ref} in the API boundary so no JGit type
 * escapes into the UI layer.
 *
 * @param fullName  e.g. {@code refs/heads/main} or {@code refs/remotes/origin/main}
 * @param shortName e.g. {@code main} or {@code origin/main}
 * @param targetHash full SHA-1 of the commit this ref points at
 */
public record RefInfo(String fullName, String shortName, String targetHash) {

    /** True when this is a local branch ({@code refs/heads/…}). */
    public boolean isLocalBranch() {
        return fullName.startsWith("refs/heads/");
    }

    /** True when this is a remote-tracking branch ({@code refs/remotes/…}). */
    public boolean isRemoteBranch() {
        return fullName.startsWith("refs/remotes/");
    }

    /** True when this is a tag ({@code refs/tags/…}). */
    public boolean isTag() {
        return fullName.startsWith("refs/tags/");
    }

    /**
     * True for the marker the graph puts on the commit a detached HEAD points at.
     *
     * <p>Not a ref the sidebar can act on - it has no {@code refs/} name - but without it a
     * detached HEAD looks exactly like any other unlabelled commit, and the one thing the
     * user most needs to see (that commits made now belong to no branch) is invisible.
     */
    public boolean isDetachedHead() {
        return "HEAD".equals(fullName);
    }

    /** The marker for a detached HEAD at {@code hash}; see {@link #isDetachedHead()}. */
    public static RefInfo detachedHead(String hash) {
        return new RefInfo("HEAD", "HEAD", hash);
    }

    /**
     * The short name git's porcelain would show, taken by stripping the namespace.
     *
     * <p>Deliberately not {@code %(refname:short)}: that one disambiguates, so a branch
     * {@code main} beside a tag {@code main} comes back as {@code heads/main}, which then
     * never equals the current branch's name. JGit strips the prefix, and so does this.
     */
    public static String shortNameOf(String fullName) {
        return RefNames.shortName(fullName);
    }

    /**
     * The order ref chips are drawn in on a commit row.
     *
     * <p>Local branches, then remote-tracking branches, then tags, alphabetically within
     * each. Without a defined order the chips follow whatever order the engine happened to
     * report its refs in, and the same commit shows {@code origin/HEAD origin/main} under
     * one engine and {@code origin/main origin/HEAD} under another.
     */
    public static final java.util.Comparator<RefInfo> DISPLAY_ORDER =
            java.util.Comparator.comparingInt(RefInfo::sortGroup)
                    .thenComparing(RefInfo::shortName);

    private int sortGroup() {
        if (isDetachedHead()) return -1;
        if (isTag()) return 2;
        if (isRemoteBranch()) return 1;
        return 0;
    }

    @Override
    public String toString() { return shortName; }
}
