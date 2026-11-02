package com.jfx.git.core;

import com.jfx.git.model.BisectStatus;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Bisect, on {@link BisectSession}, for {@link GitService}.
 */
final class JGitBisect extends JGitPart {

    JGitBisect(JGitRepo ctx) {
        super(ctx);
    }

    // ------------------------------------------------------------------
    // Bisect
    // ------------------------------------------------------------------

    private final BisectSession bisect = new BisectSession();

    /**
     * Starts a session, remembering where HEAD was so {@link #bisectReset} can go back.
     * Nothing is checked out until both a good and a bad commit are marked.
     */
    public GitResult bisectStart() {
        if (git() == null) return GitResult.failed("No repository open");
        try {
            // getBranch() answers a branch name when attached, or the raw hash when
            // already detached - either is exactly what a later checkout call needs to
            // return to, with no extra case to handle.
            bisect().start(repository().getBranch());
            return GitResult.ok("Bisect started. Mark a known-good and a known-bad commit to begin.");
        } catch (Exception e) {
            return GitResult.failed("Could not start bisect: " + describe(e));
        }
    }

    /**
     * The session, re-read from the git directory first - see {@link BisectSession#sync}.
     * Another engine, or this application's previous run, may have moved it on.
     */
    private BisectSession bisect() {
        if (repository() != null) bisect.sync(repository().getDirectory());
        return bisect;
    }

    public BisectStatus bisectMarkGood(String commitHash) {
        return mark(commitHash, true);
    }

    public BisectStatus bisectMarkBad(String commitHash) {
        return mark(commitHash, false);
    }

    private BisectStatus mark(String commitHash, boolean good) {
        if (git() == null || !bisect().isActive()) return BisectStatus.inactive();
        try {
            ObjectId id = repository().resolve(commitHash);
            if (id == null) {
                return new BisectStatus(true, bisect.currentCandidate(), null, 0, false,
                        "Could not resolve " + commitHash);
            }
            if (good) bisect.markGood(id.getName()); else bisect.markBad(id.getName());
            return advance();
        } catch (Exception e) {
            return new BisectStatus(true, bisect.currentCandidate(), null, 0, false,
                    "Could not mark that commit: " + describe(e));
        }
    }

    public BisectStatus bisectSkip() {
        if (git() == null || !bisect().isActive()) return BisectStatus.inactive();
        String current = bisect.currentCandidate();
        if (current == null) {
            return bisectStatus();
        }
        bisect.skip(current);
        return advance();
    }

    /**
     * Recomputes the candidate range, checks out the next commit to test (or reports the
     * answer), and returns it - by {@link BisectSession#decide}, which follows git's own
     * order: merge bases first, then the range, then the answer.
     */
    private BisectStatus advance() {
        if (!bisect.hasBothEndpoints()) {
            return new BisectStatus(true, null, null, 0, false,
                    bisect.badHash() == null
                            ? "Waiting for a bad commit."
                            : "Waiting for at least one good commit.");
        }
        try (RevWalk walk = new RevWalk(repository())) {
            RevCommit badCommit = walk.parseCommit(ObjectId.fromString(bisect.badHash()));
            List<String> mergeBases = bisectMergeBases(badCommit);

            walk.reset();
            walk.sort(org.eclipse.jgit.revwalk.RevSort.TOPO);
            walk.markStart(walk.parseCommit(badCommit));
            for (String goodHash : bisect.goodHashes()) {
                walk.markUninteresting(walk.parseCommit(ObjectId.fromString(goodHash)));
            }
            List<String> candidates = new ArrayList<>();
            for (RevCommit rc : walk) {
                candidates.add(rc.getName());
            }

            BisectSession.Decision decision = bisect.decide(mergeBases, candidates, false);
            if (!(decision instanceof BisectSession.TestNext next)) {
                bisect.setCurrentCandidate(null);
                BisectSession.BisectStatusText end = bisect.describeEnd(decision, this::subjectOf);
                boolean done = !(decision instanceof BisectSession.MarksReversed);
                return new BisectStatus(true, end.commit(), end.subject(), 0, done,
                        end.message(), end.suspects());
            }
            bisect.setCurrentCandidate(next.commit());
            GitResult checkout = branches().checkoutBranch(next.commit());
            if (!checkout.isOk()) {
                return new BisectStatus(true, next.commit(), null, candidates.size(), false,
                        "Could not check out the next candidate: " + checkout.getMessage());
            }
            int remaining = next.remaining();
            return new BisectStatus(true, next.commit(), subjectOf(next.commit()), remaining, false,
                    next.mergeBase()
                            ? "Bisecting: a merge base must be tested"
                            : "Bisecting: " + remaining + " revision" + (remaining == 1 ? "" : "s")
                                    + " left to test");
        } catch (Exception e) {
            return new BisectStatus(true, bisect.currentCandidate(), null, 0, false,
                    "Could not compute the next candidate: " + describe(e));
        }
    }

    /**
     * The best merge bases of the bad commit with every good one - git's
     * {@code merge-base --all bad good...}, built from JGit's pairwise answers.
     */
    private List<String> bisectMergeBases(RevCommit badCommit) throws IOException {
        List<String> bases = new ArrayList<>();
        for (String goodHash : bisect.goodHashes()) {
            try (RevWalk walk = new RevWalk(repository())) {
                walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
                walk.markStart(walk.parseCommit(badCommit));
                walk.markStart(walk.parseCommit(ObjectId.fromString(goodHash)));
                for (RevCommit base : walk) {
                    bases.add(base.getName());
                }
            }
        }
        return BisectSession.bestOf(bases, (a, b) -> {
            try (RevWalk walk = new RevWalk(repository())) {
                return walk.isMergedInto(walk.parseCommit(ObjectId.fromString(a)),
                        walk.parseCommit(ObjectId.fromString(b)));
            } catch (IOException e) {
                return false;
            }
        });
    }

    /** A commit's subject line, or {@code null} if it cannot be read. */
    private String subjectOf(String hash) {
        try (RevWalk walk = new RevWalk(repository())) {
            return walk.parseCommit(ObjectId.fromString(hash)).getShortMessage();
        } catch (Exception e) {
            return null;
        }
    }

    public BisectStatus bisectStatus() {
        if (git() == null || !bisect().isActive()) return BisectStatus.inactive();
        String current = bisect.currentCandidate();
        if (current == null) {
            return advance();
        }
        try (RevWalk walk = new RevWalk(repository())) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(current));
            return new BisectStatus(true, current, commit.getShortMessage(), 0, false,
                    "Testing " + shorten(current));
        } catch (Exception e) {
            return new BisectStatus(true, current, null, 0, false, "Testing " + shorten(current));
        }
    }

    public GitResult bisectReset() {
        if (git() == null || !bisect().isActive()) return GitResult.failed("No bisect session is running");
        String returnTo = bisect.originalPosition();
        bisect.reset();
        // A session the command line started may have packed its refs/bisect marks, which
        // the session's own clean-up (loose files only) cannot reach.
        try {
            for (Ref ref : repository().getRefDatabase().getRefsByPrefix("refs/bisect/")) {
                RefUpdate delete = repository().updateRef(ref.getName());
                delete.setForceUpdate(true);
                delete.delete();
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not remove refs/bisect", e);
        }
        if (returnTo == null || returnTo.isBlank()) {
            return GitResult.ok("Bisect session ended");
        }
        GitResult checkout = branches().checkoutBranch(returnTo);
        return checkout.isOk()
                ? GitResult.ok("Bisect ended; returned to " + returnTo)
                : GitResult.failed("Bisect session ended, but could not return to " + returnTo
                        + ": " + checkout.getMessage());
    }
}
