package com.jfx.git.core;

import com.jfx.git.model.ConflictPrediction;
import com.jfx.git.model.RefInfo;
import com.jfx.git.model.RefNames;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * Which remote branch the current branch is most likely to be merged into, and whether
 * that merge would conflict - the question behind the status bar's early warning.
 *
 * <h2>Choosing the base</h2>
 * The user names the candidates with patterns, one per line: {@code origin/main} for a
 * single branch, {@code origin/**} for every branch on a remote, {@code !origin/wip/**}
 * to leave some out. {@code *} stays within one path segment, {@code **} crosses them.
 * Of the matching remote branches - at most {@link #MAX_CANDIDATES}, never the current
 * branch's own copy or its upstream - the one whose merge base with HEAD is nearest wins:
 * that is the branch this one was most recently started from or brought up to date with.
 * A tie goes to whatever {@code origin/HEAD} points at, the remote's default branch.
 */
public final class ConflictBase {

    /** What a repository with no patterns of its own checks against. */
    public static final String DEFAULT_PATTERNS = "origin/**";

    /** The most matching branches whose merge base is worked out. */
    public static final int MAX_CANDIDATES = 30;

    /** Commits counted back to a merge base before "a long way" is as good as any further. */
    static final int DISTANCE_LIMIT = 500;

    private ConflictBase() {
    }

    /** One line of the patterns. */
    record Rule(boolean exclude, Pattern glob) {
    }

    /** The patterns as rules; blank lines and {@code #} comments are skipped. */
    static List<Rule> parse(String patterns) {
        List<Rule> rules = new ArrayList<>();
        if (patterns == null) return rules;
        for (String raw : patterns.split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            boolean exclude = line.startsWith("!");
            if (exclude) line = line.substring(1).strip();
            if (line.isEmpty()) continue;
            rules.add(new Rule(exclude, globToRegex(line)));
        }
        return rules;
    }

    /** {@code **} any characters, {@code *} any but {@code /}, {@code ?} one character. */
    static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    /** Whether {@code branch} is one of the bases {@code patterns} names. */
    public static boolean matches(String patterns, String branch) {
        return matches(parse(patterns), branch);
    }

    static boolean matches(List<Rule> rules, String branch) {
        boolean included = false;
        for (Rule rule : rules) {
            if (!rule.glob().matcher(branch).matches()) continue;
            if (rule.exclude()) return false;
            included = true;
        }
        return included;
    }

    /**
     * The remote branches worth comparing against, {@code preferred} first, then by name,
     * at most {@link #MAX_CANDIDATES}.
     *
     * @param current  the checked-out branch; its copy on any remote is left out
     * @param upstream the branch it tracks, also left out - being behind it is what pull
     *                 is for, not a conflict with another line of work
     */
    static List<String> candidates(Collection<String> remoteBranches, String patterns,
                                   String current, String upstream, String preferred) {
        return candidates(remoteBranches, List.of(), patterns, current, upstream, preferred);
    }

    /**
     * {@link #candidates(Collection, String, String, String, String)}, reading each remote
     * branch's branch part by the configured remotes - {@code team/core/main} is
     * {@code main} on {@code team/core} - as every view does ({@link RefNames#branchOf}).
     */
    static List<String> candidates(Collection<String> remoteBranches, Collection<String> remoteNames,
                                   String patterns, String current, String upstream, String preferred) {
        List<Rule> rules = parse(patterns);
        List<String> found = new ArrayList<>();
        for (String branch : remoteBranches) {
            // The engines list no origin/HEAD; names handed in from elsewhere meet the rule.
            if (branch == null || RefNames.isSymbolicHead(branch, remoteNames)) continue;
            if (branch.equals(upstream)) continue;
            if (current != null && !current.isEmpty() && RefNames.branchOf(branch, remoteNames).equals(current)) {
                continue;
            }
            if (matches(rules, branch) && !found.contains(branch)) found.add(branch);
        }
        found.sort((a, b) -> {
            boolean aFirst = a.equals(preferred);
            boolean bFirst = b.equals(preferred);
            if (aFirst != bFirst) return aFirst ? -1 : 1;
            return a.compareTo(b);
        });
        return found.size() > MAX_CANDIDATES ? List.copyOf(found.subList(0, MAX_CANDIDATES)) : found;
    }

    /**
     * The candidate nearest HEAD by {@code distance} - commits from HEAD back to the merge
     * base, negative when there is none. Ties go to {@code preferred}, then to the first
     * in order.
     *
     * @return null when no candidate shares history with HEAD
     */
    static String choose(List<String> candidates, ToIntFunction<String> distance, String preferred) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int d = distance.applyAsInt(candidate);
            if (d < 0) continue;
            boolean better = d < bestDistance
                    || (d == bestDistance && candidate.equals(preferred) && !Objects.equals(best, preferred));
            if (better) {
                best = candidate;
                bestDistance = d;
            }
        }
        return best;
    }

    /** The base chosen, and what merging it would do. */
    public record Check(String base, ConflictPrediction prediction) {
    }

    /**
     * Chooses the base and predicts the merge. Runs on the repository lane.
     *
     * @return null when there is nothing to check: a detached HEAD, no matching remote
     *         branch, or none that shares history with this one
     */
    public static Check check(GitBackend backend, String patterns) {
        if (backend.isHeadDetached()) return null;
        String current = backend.getCurrentBranch();
        List<String> names = new ArrayList<>();
        for (RefInfo ref : backend.getRemoteBranches()) names.add(ref.shortName());
        // The branch origin/HEAD points at, read from the symbolic ref itself.
        String preferred = DefaultBranch.remoteHead(backend);
        String upstream = current == null || current.isEmpty() ? null : backend.getUpstream(current);
        List<String> candidates = candidates(names, backend.getRemoteNames(), patterns, current, upstream,
                preferred);
        if (candidates.isEmpty()) return null;

        // Several candidates usually share a merge base; each is counted once.
        Map<String, Integer> byBase = new HashMap<>();
        String base = choose(candidates, candidate -> {
            if (GitTaskRunner.isCancelled()) return -1;
            String mergeBase = backend.mergeBase("HEAD", candidate);
            if (mergeBase == null) return -1;
            return byBase.computeIfAbsent(mergeBase,
                    b -> backend.getCommitsInRange(b, "HEAD", DISTANCE_LIMIT).size());
        }, preferred);
        if (base == null || GitTaskRunner.isCancelled()) return null;
        return new Check(base, backend.predictMergeConflicts("HEAD", base));
    }
}
