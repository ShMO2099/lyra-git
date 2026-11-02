package com.jfx.git.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The engine-independent half of bisect: which candidate to try next, given the marks
 * made so far, and whether the search is over.
 *
 * <p>Both {@link GitService} and {@link NativeGitBackend} hold one of these and feed it
 * the commits their own engine can already produce - a topologically-ordered walk of
 * {@code good..bad} (JGit: {@code RevWalk} with the good commits marked uninteresting;
 * native: {@code git rev-list good1..bad ... goodN..bad}, deduplicated) - rather than
 * duplicating the walk itself here. What lives here is only the part neither engine's
 * own history-walking code already does: remembering the marks across calls, and
 * choosing the midpoint of whatever candidate list comes back.
 *
 * <p>Deliberately not the real {@code git bisect}: no {@code .git/BISECT_*} files, no
 * subprocess. The whole point of implementing this ourselves - see {@link
 * GitBackend#bisectStart()} - is that every engine can run the identical algorithm, so
 * JGit needs no bisect API of its own to support this feature.
 *
 * <h2>Where the state lives</h2>
 * Not only in memory. A session moves HEAD to a detached candidate on every step, and one
 * held only in this object's fields was lost by an engine switch or a restart - which
 * stranded the user on a detached commit with no "End bisect" button and no record of
 * where they had started. So once {@link #sync(File)} has tied a session to a git
 * directory, every change is written to {@value #STATE_FILE} there, and every engine
 * re-reads it before acting: the file is the session, and these fields are a copy of it.
 *
 * <p>A bisect started with the command line ({@code .git/BISECT_START} and
 * {@code refs/bisect/*}) is picked up the same way, so the banner offers to finish or end
 * it rather than pretending nothing is happening.
 *
 * <h2>Following git's rules for the answer</h2>
 * {@link #decide} is what {@code git bisect} does after every mark, in git's order: a
 * merge base of the bad commit and the good ones must be tested before the range means
 * anything; a bad merge base means the good and bad marks are the wrong way round; and a
 * range with only skipped commits left has several possible answers, not one.
 */
final class BisectSession {

    private static final Logger LOGGER = Logger.getLogger(BisectSession.class.getName());

    /** The file in the git directory holding a session this application started. */
    static final String STATE_FILE = "lyra-bisect";

    /** {@link #STATE_FILE} before the application was renamed; renamed on the next sync. */
    static final String LEGACY_STATE_FILE = "jfxgit-bisect";

    /** The git directory this session is persisted in, or {@code null} for memory only. */
    private File gitDir;

    /** The ref or hash HEAD was on before {@link #start} - what {@code reset} returns to. */
    private String originalPosition;
    private boolean active;

    private final Set<String> good = new LinkedHashSet<>();
    private String bad;
    private final Set<String> skipped = new LinkedHashSet<>();

    /** Hash currently checked out for testing, or {@code null} before the first candidate exists. */
    private String currentCandidate;

    boolean isActive() {
        return active;
    }

    String originalPosition() {
        return originalPosition;
    }

    void start(String originalPosition) {
        this.originalPosition = originalPosition;
        this.active = true;
        good.clear();
        skipped.clear();
        bad = null;
        currentCandidate = null;
        save();
    }

    void reset() {
        active = false;
        good.clear();
        skipped.clear();
        bad = null;
        currentCandidate = null;
        originalPosition = null;
        clearPersistedState();
    }

    void markGood(String hash) {
        good.add(hash);
        skipped.remove(hash);
        save();
    }

    void markBad(String hash) {
        bad = hash;
        skipped.remove(hash);
        save();
    }

    void skip(String hash) {
        skipped.add(hash);
        save();
    }

    boolean hasBothEndpoints() {
        return bad != null && !good.isEmpty();
    }

    Set<String> goodHashes() {
        return good;
    }

    String badHash() {
        return bad;
    }

    void setCurrentCandidate(String hash) {
        currentCandidate = hash;
        save();
    }

    Set<String> skippedHashes() {
        return skipped;
    }

    /** Whether this session came from a {@code git bisect} the command line started. */
    boolean startedByGit() {
        return gitDir != null && new File(gitDir, "BISECT_START").exists();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Ties this session to {@code dir} and re-reads it from there.
     *
     * <p>Called before every bisect operation, not once: another engine instance - the
     * one a switch replaced, or the one the previous run of the application had - may
     * have written it since. With nothing on disk the session is inactive, whatever these
     * fields last said, because the file is what every engine agrees on.
     */
    void sync(File dir) {
        if (dir == null) return;
        gitDir = dir;
        File state = new File(dir, STATE_FILE);
        File legacy = new File(dir, LEGACY_STATE_FILE);
        if (!state.isFile() && legacy.isFile() && !legacy.renameTo(state)) {
            state = legacy;
        }
        if (state.isFile()) {
            load(state);
        } else if (new File(dir, "BISECT_START").isFile()) {
            importFromGit(dir);
        } else {
            active = false;
            good.clear();
            skipped.clear();
            bad = null;
            currentCandidate = null;
            originalPosition = null;
        }
    }

    private void load(File state) {
        try {
            List<String> lines = Files.readAllLines(state.toPath(), StandardCharsets.UTF_8);
            good.clear();
            skipped.clear();
            bad = null;
            currentCandidate = null;
            originalPosition = null;
            for (String line : lines) {
                int space = line.indexOf(' ');
                if (space < 0) continue;
                String key = line.substring(0, space);
                String value = line.substring(space + 1).strip();
                if (value.isEmpty()) continue;
                switch (key) {
                    case "start" -> originalPosition = value;
                    case "good" -> good.add(value);
                    case "bad" -> bad = value;
                    case "skip" -> skipped.add(value);
                    case "current" -> currentCandidate = value;
                    default -> { }
                }
            }
            active = true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not read the bisect state", e);
        }
    }

    /**
     * Reads a session the command line started: {@code BISECT_START} holds where to
     * return to, {@code BISECT_TERMS} the words used for bad and good (custom terms such
     * as {@code new}/{@code old} rename the refs), and {@code refs/bisect/} the marks,
     * loose or packed.
     */
    private void importFromGit(File dir) {
        try {
            good.clear();
            skipped.clear();
            bad = null;
            currentCandidate = null;
            List<String> start = Files.readAllLines(new File(dir, "BISECT_START").toPath(),
                    StandardCharsets.UTF_8);
            originalPosition = start.isEmpty() ? null : start.get(0).strip();
            String badTerm = "bad";
            String goodTerm = "good";
            File terms = new File(dir, "BISECT_TERMS");
            if (terms.isFile()) {
                List<String> t = Files.readAllLines(terms.toPath(), StandardCharsets.UTF_8);
                if (t.size() >= 2 && !t.get(0).isBlank() && !t.get(1).isBlank()) {
                    badTerm = t.get(0).strip();
                    goodTerm = t.get(1).strip();
                }
            }
            java.util.Map<String, String> refs = new java.util.LinkedHashMap<>();
            File packed = new File(dir, "packed-refs");
            if (packed.isFile()) {
                for (String line : Files.readAllLines(packed.toPath(), StandardCharsets.UTF_8)) {
                    int space = line.indexOf(' ');
                    if (line.startsWith("#") || line.startsWith("^") || space < 0) continue;
                    String name = line.substring(space + 1).strip();
                    if (name.startsWith("refs/bisect/")) {
                        refs.put(name.substring("refs/bisect/".length()), line.substring(0, space));
                    }
                }
            }
            File[] loose = new File(dir, "refs/bisect").listFiles(File::isFile);
            if (loose != null) {
                for (File ref : loose) {
                    String hash = Files.readString(ref.toPath(), StandardCharsets.UTF_8).strip();
                    if (!hash.isEmpty()) refs.put(ref.getName(), hash);
                }
            }
            for (var ref : refs.entrySet()) {
                String name = ref.getKey();
                if (name.equals(badTerm)) bad = ref.getValue();
                else if (name.startsWith(goodTerm + "-")) good.add(ref.getValue());
                else if (name.startsWith("skip-")) skipped.add(ref.getValue());
            }
            active = true;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not read the command line's bisect state", e);
        }
    }

    /**
     * Writes the session to disk, replacing the previous copy in one move so a crash
     * part-way cannot leave half a session behind.
     */
    private void save() {
        if (gitDir == null || !active) return;
        StringBuilder out = new StringBuilder();
        if (originalPosition != null) out.append("start ").append(originalPosition).append('\n');
        if (bad != null) out.append("bad ").append(bad).append('\n');
        for (String g : good) out.append("good ").append(g).append('\n');
        for (String s : skipped) out.append("skip ").append(s).append('\n');
        if (currentCandidate != null) out.append("current ").append(currentCandidate).append('\n');
        try {
            File target = new File(gitDir, STATE_FILE);
            File temp = new File(gitDir, STATE_FILE + ".tmp");
            Files.writeString(temp.toPath(), out, StandardCharsets.UTF_8);
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not save the bisect state", e);
        }
    }

    /**
     * Removes this application's state file and, for a session the command line started,
     * its loose state too - the files {@code git bisect reset} removes. Packed
     * {@code refs/bisect} entries are left to the engine, which can delete refs properly.
     */
    private void clearPersistedState() {
        if (gitDir == null) return;
        new File(gitDir, STATE_FILE).delete();
        new File(gitDir, LEGACY_STATE_FILE).delete();
        for (String name : List.of("BISECT_START", "BISECT_LOG", "BISECT_TERMS",
                "BISECT_EXPECTED_REV", "BISECT_ANCESTORS_OK", "BISECT_NAMES", "BISECT_RUN",
                "BISECT_HEAD", "BISECT_FIRST_PARENT")) {
            new File(gitDir, name).delete();
        }
        File[] loose = new File(gitDir, "refs/bisect").listFiles(File::isFile);
        if (loose != null) {
            for (File ref : loose) ref.delete();
        }
    }

    // ------------------------------------------------------------------
    // What to do next
    // ------------------------------------------------------------------

    /** What {@link #decide} concluded. */
    sealed interface Decision permits TestNext, Found, Ambiguous, MarksReversed { }

    /**
     * Check out and test {@code commit}.
     *
     * @param mergeBase the commit is a merge base being verified, not a range midpoint
     */
    record TestNext(String commit, boolean mergeBase, int remaining) implements Decision { }

    /** {@code commit} is the first bad commit. */
    record Found(String commit) implements Decision { }

    /** Only skipped commits are left; the first bad commit is one of these (bad last). */
    record Ambiguous(List<String> suspects) implements Decision { }

    /**
     * The bad commit is a merge base of the good ones - an ancestor of a commit marked
     * good. git refuses to go on: either the bug was fixed between the two, or the marks
     * were given the wrong way round.
     */
    record MarksReversed(String bad, Set<String> goods) implements Decision { }

    /**
     * git bisect's next step, given the merge bases of the bad commit with the good ones
     * and the candidate range {@code bad ^good...}.
     *
     * <ol>
     *   <li>If the bad commit is itself a merge base, a good commit descends from it:
     *       {@link MarksReversed}. Before this, the empty range that case produces was
     *       turned into "the bad commit is the answer" - an answer to a question the marks
     *       did not ask.</li>
     *   <li>A merge base that is neither good nor skipped is tested first: the range only
     *       holds the first bad commit if the merge base is good, which is an assumption
     *       git checks rather than makes.</li>
     *   <li>Otherwise the next testable candidate, ranked or midpoint.</li>
     *   <li>With none left: the bad commit if no skipped commit is in the range, or every
     *       skipped candidate plus the bad commit if one is - git's "could be any of".</li>
     * </ol>
     *
     * @param mergeBases the best merge bases of the bad commit with the good ones, or
     *                   {@code null} to skip that check
     * @param ranked     the candidates are best-first ({@code rev-list --bisect-all});
     *                   otherwise the flat midpoint is taken
     */
    Decision decide(Collection<String> mergeBases, List<String> candidates, boolean ranked) {
        if (mergeBases != null) {
            if (bad != null && mergeBases.contains(bad)) {
                return new MarksReversed(bad, Set.copyOf(good));
            }
            for (String base : mergeBases) {
                if (!good.contains(base) && !skipped.contains(base)) {
                    return new TestNext(base, true, candidates.size());
                }
            }
        }
        String next = ranked ? pickBest(candidates) : pickNext(candidates);
        if (next != null) {
            return new TestNext(next, false, Math.max(0, testable(candidates).size() - 1));
        }
        List<String> suspects = new ArrayList<>();
        for (String candidate : candidates) {
            if (skipped.contains(candidate) && !candidate.equals(bad)) suspects.add(candidate);
        }
        if (suspects.isEmpty()) {
            return new Found(bad);
        }
        suspects.add(bad);
        return new Ambiguous(suspects);
    }

    /**
     * The best common ancestors among {@code bases}: those not an ancestor of another.
     *
     * <p>git asks {@code merge-base --all bad good1 good2...}, the merge bases of the bad
     * commit with a hypothetical merge of every good one. An engine that can only ask
     * pairwise gets the union of each pair's bases, of which exactly the maximal ones are
     * git's answer. {@code isAncestor.test(a, b)} says whether {@code a} is reachable from
     * {@code b}.
     */
    static List<String> bestOf(Collection<String> bases,
                               java.util.function.BiPredicate<String, String> isAncestor) {
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(bases));
        List<String> best = new ArrayList<>();
        for (String candidate : distinct) {
            boolean dominated = false;
            for (String other : distinct) {
                if (!other.equals(candidate) && isAncestor.test(candidate, other)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) best.add(candidate);
        }
        return best;
    }

    /**
     * Turns a {@link Decision} that ends or blocks the session into what to tell the user,
     * worded as git words it. {@code subject} looks a commit's subject up for display.
     *
     * @return the status, or {@code null} for a {@link TestNext}, which the engine handles
     *         by checking the commit out
     */
    BisectStatusText describeEnd(Decision decision, java.util.function.Function<String, String> subject) {
        return switch (decision) {
            case Found found -> {
                String s = subject.apply(found.commit());
                yield new BisectStatusText(found.commit(), s, List.of(),
                        GitBackend.shortenHash(found.commit()) + " is the first bad commit"
                                + (s == null || s.isBlank() ? "" : ": " + s));
            }
            case Ambiguous ambiguous -> {
                List<String> shortened = ambiguous.suspects().stream()
                        .map(GitBackend::shortenHash).toList();
                yield new BisectStatusText(null, null, ambiguous.suspects(),
                        "There are only skipped commits left to test. The first bad commit"
                                + " could be any of: " + String.join(", ", shortened));
            }
            case MarksReversed reversed -> new BisectStatusText(null, null, List.of(),
                    "The merge base " + GitBackend.shortenHash(reversed.bad()) + " is bad. This"
                            + " means the bug has been fixed between "
                            + GitBackend.shortenHash(reversed.bad()) + " and ["
                            + String.join(", ", reversed.goods().stream()
                                    .map(GitBackend::shortenHash).sorted().toList())
                            + "] - or the good and bad marks are the wrong way round."
                            + " End the bisect and start again.");
            case TestNext _ -> null;
        };
    }

    /** The parts of a finished or blocked status that {@link #describeEnd} words. */
    record BisectStatusText(String commit, String subject, List<String> suspects, String message) { }

    String currentCandidate() {
        return currentCandidate;
    }

    /**
     * Picks the next commit to test from a topologically-ordered {@code good..bad}
     * range (oldest-reachable-from-bad last is not required; any consistent order the
     * caller's own walk produces is fine, since the midpoint of a list is order-
     * dependent only in which specific commit ties land on, not in correctness).
     *
     * <p>{@code skipped} commits are removed first - retesting one already known to be
     * untestable would loop forever - then the middle of what remains is returned.
     * Real {@code git bisect} weights candidates by descendant count for a slightly
     * better expected step count on a branchy history. This flat midpoint is the same
     * simplification other reimplementations of bisect outside git itself make, and is
     * optimal already on the linear history bisect is most useful for. Where git's own
     * weighting is available for free - the CLI engine, which can ask
     * {@code rev-list --bisect-all} - {@link #pickBest} takes it instead.
     *
     * @return the next candidate, or {@code null} when nothing remains - {@code bad} is
     *         then the answer
     */
    String pickNext(List<String> candidatesGoodToBad) {
        List<String> remaining = testable(candidatesGoodToBad);
        return remaining.isEmpty() ? null : remaining.get(remaining.size() / 2);
    }

    /**
     * Picks the next commit from a list already ranked best-first by whoever produced it.
     *
     * <p>For {@code git rev-list --bisect-all}, which orders the range by how good a
     * bisection point each commit is - git's own weighting, counting descendants, rather
     * than the flat midpoint {@link #pickNext} falls back to. On a linear history the two
     * agree; on a branchy one git's answer splits the remaining space more evenly and gets
     * there in fewer steps.
     *
     * <p>Taking the first <em>testable</em> entry rather than simply the first is what
     * makes skipping work: {@code --bisect-all} does not know which commits have been
     * skipped, so the best candidate may be one already found untestable. The next entry
     * down is the next-best bisection point, which is exactly what should be offered.
     *
     * @return the best untested candidate, or {@code null} when none remains - {@code bad}
     *         is then the answer
     */
    String pickBest(List<String> rankedBestFirst) {
        List<String> remaining = testable(rankedBestFirst);
        return remaining.isEmpty() ? null : remaining.get(0);
    }

    /**
     * The candidates still worth testing.
     *
     * <p>Skipped ones are dropped because retesting a commit already found untestable
     * would loop forever, and {@code bad} because it is only in the range at all - a
     * {@code good..bad} range always includes its own endpoint - and is already known.
     */
    private List<String> testable(List<String> candidates) {
        List<String> remaining = new ArrayList<>(candidates);
        remaining.removeAll(skipped);
        remaining.remove(bad);
        return remaining;
    }
}
