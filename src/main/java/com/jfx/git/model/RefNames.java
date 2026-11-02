package com.jfx.git.model;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * The rules for reading and making ref names, in one place.
 *
 * <h2>Why this exists</h2>
 * Every view used to read a remote-tracking branch's name for itself, and they did not
 * agree. The graph took the first path segment as the remote, so for a remote whose own
 * name has a slash in it - {@code team/core} - it read {@code team/core/topic} as the
 * branch {@code core/topic} on a remote {@code team}; the sidebar took the longest
 * configured remote and read it as {@code topic} on {@code team/core}; checking it out
 * made a local branch called {@code core/topic}. {@code origin/HEAD} was skipped by a
 * hand-written {@code endsWith("/HEAD")} in seven places and not in four others. Seven
 * prompts validated a new branch's name, each with its own subset of git's rules. Those
 * rules now live here and nowhere else.
 *
 * <p>Pure Java with no engine or JavaFX types: anything can ask, and the rules are tested
 * as a table.
 */
public final class RefNames {

    public static final String HEADS = "refs/heads/";
    public static final String REMOTES = "refs/remotes/";
    public static final String TAGS = "refs/tags/";

    private RefNames() {
    }

    // ------------------------------------------------------------------
    // Full and short names
    // ------------------------------------------------------------------

    /**
     * The name git's porcelain shows: the namespace stripped, so {@code refs/heads/main}
     * is {@code main} and {@code refs/remotes/origin/main} is {@code origin/main}. Any
     * other name comes back as it is.
     *
     * <p>Deliberately not {@code %(refname:short)}: that one disambiguates, so a branch
     * {@code main} beside a tag {@code main} comes back as {@code heads/main}, which then
     * never equals the current branch's name.
     */
    public static String shortName(String fullName) {
        if (fullName == null) return null;
        for (String prefix : new String[]{HEADS, REMOTES, TAGS}) {
            if (fullName.startsWith(prefix)) return fullName.substring(prefix.length());
        }
        return fullName;
    }

    /** {@code refs/heads/<branch>}. */
    public static String localFull(String branch) {
        return HEADS + branch;
    }

    /** {@code refs/remotes/<remote>/<branch>}, from the short {@code <remote>/<branch>}. */
    public static String remoteFull(String remoteBranch) {
        return REMOTES + remoteBranch;
    }

    /** {@code refs/tags/<tag>}. */
    public static String tagFull(String tag) {
        return TAGS + tag;
    }

    // ------------------------------------------------------------------
    // Remote-tracking branches
    // ------------------------------------------------------------------

    /**
     * The remote a remote-tracking branch belongs to: the longest configured remote name
     * it starts with, since a remote's own name may contain a slash. A ref whose remote is
     * no longer configured - or a caller that knows no remotes - falls back to the first
     * path segment.
     *
     * @param remoteBranch the short name, {@code team/core/topic}, or the full one,
     *                     {@code refs/remotes/team/core/topic}
     * @param remotes      the configured remote names; may be null or empty
     */
    public static String remoteOf(String remoteBranch, Collection<String> remotes) {
        if (remoteBranch == null) return null;
        String name = withoutRemotesPrefix(remoteBranch);
        String found = null;
        if (remotes != null) {
            for (String remote : remotes) {
                if (remote == null || remote.isEmpty()) continue;
                if (name.startsWith(remote + "/") && (found == null || remote.length() > found.length())) {
                    found = remote;
                }
            }
        }
        if (found != null) return found;
        int slash = name.indexOf('/');
        return slash < 0 ? name : name.substring(0, slash);
    }

    /**
     * The branch part of a remote-tracking branch: what follows {@link #remoteOf its
     * remote} and the slash after it - {@code topic} for {@code team/core/topic} when
     * {@code team/core} is a remote. Empty for a name that is only a remote.
     */
    public static String branchOf(String remoteBranch, Collection<String> remotes) {
        if (remoteBranch == null) return null;
        String name = withoutRemotesPrefix(remoteBranch);
        String remote = remoteOf(name, remotes);
        return name.length() > remote.length() ? name.substring(remote.length() + 1) : "";
    }

    /**
     * Whether a remote-tracking ref is a remote's symbolic {@code HEAD} - {@code origin/HEAD},
     * the pointer a clone records at the branch the server calls default - rather than a
     * branch. Its branch part is {@code HEAD}; a branch merely ending in {@code /HEAD},
     * such as {@code origin/feature/HEAD}, is a branch.
     *
     * <p>The engines leave these out of every listing, so a view never meets one; this is
     * for code that is handed names from elsewhere.
     */
    public static boolean isSymbolicHead(String remoteBranch, Collection<String> remotes) {
        return "HEAD".equals(branchOf(remoteBranch, remotes));
    }

    /**
     * The remote-tracking branch {@code branch} would be on {@code remote}:
     * {@code <remote>/<branch>}, short.
     */
    public static String remoteBranch(String remote, String branch) {
        return remote + "/" + branch;
    }

    private static String withoutRemotesPrefix(String name) {
        return name.startsWith(REMOTES) ? name.substring(REMOTES.length()) : name;
    }

    // ------------------------------------------------------------------
    // Branch names
    // ------------------------------------------------------------------

    /** Whether this runs on Windows, where a branch's name has to be a file name too. */
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    /** The device names Windows keeps for itself, with or without an extension after them. */
    private static final Set<String> WINDOWS_DEVICES = Set.of("aux", "con", "nul", "prn");

    /**
     * What creating a branch named {@code name} would refuse on this machine, in a
     * sentence, or {@code null} when it would be made. Not trimmed: a caller that trims
     * what it creates trims before asking.
     *
     * <p>Git's rules first ({@link #branchNameProblem(String, boolean)}), then, on Windows,
     * the ones its file names add: git keeps a branch as a file under {@code .git/refs},
     * and a name Windows cannot store passed the prompt only for git to fail with "cannot
     * lock ref" after the dialog had closed.
     */
    public static String branchNameProblem(String name) {
        return branchNameProblem(name, WINDOWS);
    }

    /**
     * {@link #branchNameProblem(String)} as it is on Windows when {@code windows} is true,
     * whatever this runs on - so both sets of rules are tested anywhere.
     *
     * <p>Git's rules - {@code git check-ref-format --branch} - each with its own reason so
     * a prompt can say which was broken: not empty; not {@code HEAD} or {@code @}; no
     * leading {@code -} (it would read as an option); no space, control character,
     * {@code ~ ^ : ? * [ \}; no {@code ..} or {@code @{}; no {@code /} at either end or
     * two in a row; no part starting with {@code .} or ending in {@code .lock}; no
     * {@code .} at the end.
     *
     * <p>And Windows's, which JGit's checks applied when the prompts asked JGit: no
     * {@code " < > |}; no part ending in {@code .}, which Windows drops; no part that is a
     * device name - {@code aux con nul prn com1}-{@code com9 lpt1}-{@code lpt9}, in any
     * case, alone or with an extension, {@code nul.txt}.
     */
    public static String branchNameProblem(String name, boolean windows) {
        String problem = gitsBranchNameProblem(name);
        return problem != null || !windows ? problem : windowsBranchNameProblem(name);
    }

    private static String gitsBranchNameProblem(String name) {
        if (name == null || name.isEmpty()) return "A branch needs a name.";
        if (name.startsWith("-")) return "A branch name cannot start with \"-\".";
        if (name.equals("HEAD")) return "HEAD cannot be a branch name.";
        if (name.equals("@")) return "\"@\" cannot be a branch name.";
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == ' ') return "A branch name cannot contain spaces.";
            if (c < 0x20 || c == 0x7f) return "A branch name cannot contain control characters.";
            if ("~^:?*[\\".indexOf(c) >= 0) return "A branch name cannot contain \"" + c + "\".";
        }
        if (name.contains("..")) return "A branch name cannot contain \"..\".";
        if (name.contains("@{")) return "A branch name cannot contain \"@{\".";
        if (name.startsWith("/") || name.endsWith("/") || name.contains("//")) {
            return "A branch name cannot start or end with \"/\", or have two in a row.";
        }
        for (String part : name.split("/")) {
            if (part.startsWith(".")) return "No part of a branch name can start with \".\".";
            if (part.endsWith(".lock")) return "No part of a branch name can end with \".lock\".";
        }
        if (name.endsWith(".")) return "A branch name cannot end with \".\".";
        return null;
    }

    /** What Windows would refuse about {@code name} as a path, git's rules being met. */
    private static String windowsBranchNameProblem(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ("\"<>|".indexOf(c) >= 0) return "A branch name cannot contain \"" + c + "\" on Windows.";
        }
        for (String part : name.split("/")) {
            if (part.endsWith(".")) return "No part of a branch name can end with \".\" on Windows.";
            String device = windowsDevice(part);
            if (device != null) {
                return "No part of a branch name can be \"" + part + "\" on Windows: the name "
                        + device.toUpperCase(Locale.ROOT) + " is kept for a device.";
            }
        }
        return null;
    }

    /** The device {@code part} names - {@code aux}, {@code com1}, {@code nul.txt} is {@code nul} - or null. */
    private static String windowsDevice(String part) {
        int dot = part.indexOf('.');
        String base = (dot < 0 ? part : part.substring(0, dot)).toLowerCase(Locale.ROOT);
        if (WINDOWS_DEVICES.contains(base)) return base;
        boolean numbered = base.length() == 4 && (base.startsWith("com") || base.startsWith("lpt"))
                && base.charAt(3) >= '1' && base.charAt(3) <= '9';
        return numbered ? base : null;
    }

    /**
     * {@link #branchNameProblem} for a branch about to be created, which must not be
     * taken either.
     *
     * @param taken the local branch names in use; null when not known, and then only the
     *              name itself is checked
     */
    public static String newBranchNameProblem(String name, Collection<String> taken) {
        String problem = branchNameProblem(name);
        if (problem != null) return problem;
        return taken != null && taken.contains(name) ? "A branch named " + name + " already exists." : null;
    }

    // ------------------------------------------------------------------
    // What HEAD is on
    // ------------------------------------------------------------------

    /**
     * What the user reads for where HEAD is: the current branch's name, or - while HEAD is
     * detached and so there is no current branch - {@code Detached at abc1234}. Blank only
     * when there is neither, as in a repository not yet read.
     *
     * @param currentBranch the current branch, blank when detached
     * @param headHash      HEAD's commit, or null when unborn
     */
    public static String headLabel(String currentBranch, String headHash) {
        if (currentBranch != null && !currentBranch.isBlank()) return currentBranch;
        if (headHash == null || headHash.isBlank()) return "";
        return "Detached at " + (headHash.length() > 7 ? headHash.substring(0, 7) : headHash);
    }
}
