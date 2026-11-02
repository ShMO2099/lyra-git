package com.jfx.git.core;

import com.jfx.git.model.BisectStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Bisect on the native engine, driven by {@link BisectSession} rather than
 * {@code git bisect} itself.
 *
 * <p>Package-private: reached only through {@link NativeGitBackend}, which is the
 * engine's public face.
 */
final class CliBisect {

    private final CliRepo repo;

    CliBisect(CliRepo repo) {
        this.repo = repo;
    }

    // No restoreTo() here: RepositoryCommands.restoreTo is the dirty-tree guard plus a hard
    // reset, for every engine. An engine's own version is what used to let the reflog
    // Undo discard uncommitted work.

    private final BisectSession bisect = new BisectSession();

    /**
     * The session, re-read from the git directory first - see {@link BisectSession#sync}.
     * Another engine, or this application's previous run, may have moved it on.
     */
    private BisectSession bisect() {
        bisect.sync(repo.gitDir);
        return bisect;
    }

    public GitResult bisectStart() {
        repo.ensureOpen();
        // The branch to return to, or the commit when HEAD is detached - which has no
        // current branch - since a later "git checkout <that>" needs one or the other.
        String branch = repo.branches.getCurrentBranch();
        if (branch.isBlank()) {
            var head = repo.runner.run("rev-parse", "--short", "HEAD");
            branch = head.isOk() ? head.trimmedStdout() : "HEAD";
        }
        bisect().start(branch);
        return GitResult.ok("Bisect started. Mark a known-good and a known-bad commit to begin.");
    }

    public BisectStatus bisectMarkGood(String commitHash) {
        return mark(commitHash, true);
    }

    public BisectStatus bisectMarkBad(String commitHash) {
        return mark(commitHash, false);
    }

    private BisectStatus mark(String commitHash, boolean good) {
        repo.ensureOpen();
        if (!bisect().isActive()) return BisectStatus.inactive();
        String full = resolveFullHash(commitHash);
        if (full == null) {
            return new BisectStatus(true, bisect.currentCandidate(), null, 0, false,
                    "Could not resolve " + commitHash);
        }
        if (good) bisect.markGood(full); else bisect.markBad(full);
        return advance();
    }

    public BisectStatus bisectSkip() {
        repo.ensureOpen();
        if (!bisect().isActive()) return BisectStatus.inactive();
        String current = bisect.currentCandidate();
        if (current == null) {
            return bisectStatus();
        }
        bisect.skip(current);
        return advance();
    }

    private BisectStatus advance() {
        if (!bisect.hasBothEndpoints()) {
            return new BisectStatus(true, null, null, 0, false,
                    bisect.badHash() == null
                            ? "Waiting for a bad commit."
                            : "Waiting for at least one good commit.");
        }
        // git's own merge-base check comes first: `merge-base --all bad good...` is the
        // exact question git bisect asks - the bases of bad with a merge of every good.
        var bases = repo.runner.run(GitCommand.of("merge-base", "--all")
                .revs(bisect.badHash()).revs(bisect.goodHashes()).args());
        List<String> mergeBases = bases.isOk()
                ? bases.trimmedStdout().lines().map(String::strip).filter(l -> !l.isEmpty()).toList()
                : List.of();

        // `bad ^g1 ^g2` - reachable from bad, minus everything reachable from *any* good
        // mark. The `g1..bad g2..bad` form spells the union of two ranges instead, which
        // is bad minus the *intersection* of the goods: with two or more good marks it
        // keeps commits already proven good and hands them back as candidates. With a
        // single good mark the two forms agree, which is why this read as correct.
        // --bisect-all rather than --topo-order: the same set of candidates, but ordered by
        // git's own bisection weighting, best first. That is the algorithm real
        // `git bisect` uses - it counts descendants to find the commit splitting the
        // remaining space most evenly - and asking for it costs one flag, where
        // reimplementing it would not. BisectSession's flat midpoint stays as the fallback
        // for the engines that cannot ask.
        GitCommand range = GitCommand.of("rev-list", "--bisect-all").revs(bisect.badHash());
        for (String goodHash : bisect.goodHashes()) {
            range.revs("^" + goodHash);
        }
        var res = repo.runner.run(range.args());
        if (!res.isOk()) {
            return new BisectStatus(true, bisect.currentCandidate(), null, 0, false,
                    "Could not compute the candidate range: " + res.stderr());
        }
        // Each line is "<sha> (dist=N)", and the annotation can carry ref decorations too -
        // "<sha> (HEAD -> main, dist=0)". The hash is the first token either way.
        List<String> candidates = new ArrayList<>();
        for (String line : res.trimmedStdout().split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int space = trimmed.indexOf(' ');
            candidates.add(space < 0 ? trimmed : trimmed.substring(0, space));
        }
        // A single rev-list cannot repeat a commit, but keep the set distinct so the count
        // reported as "revisions left" is the size of the real candidate set.
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(candidates));

        // Ranked best-first, so take the best rather than the middle - see
        // BisectSession.pickBest, which also skips past anything already found untestable.
        BisectSession.Decision decision = bisect.decide(mergeBases, distinct, true);
        if (!(decision instanceof BisectSession.TestNext next)) {
            bisect.setCurrentCandidate(null);
            BisectSession.BisectStatusText end = bisect.describeEnd(decision, this::subjectOf);
            boolean done = !(decision instanceof BisectSession.MarksReversed);
            return new BisectStatus(true, end.commit(), end.subject(), 0, done,
                    end.message(), end.suspects());
        }
        bisect.setCurrentCandidate(next.commit());
        GitResult checkout = repo.branches.checkoutBranch(next.commit());
        if (!checkout.isOk()) {
            return new BisectStatus(true, next.commit(), null, distinct.size(), false,
                    "Could not check out the next candidate: " + checkout.getMessage());
        }
        int remaining = next.remaining();
        return new BisectStatus(true, next.commit(), subjectOf(next.commit()), remaining, false,
                next.mergeBase()
                        ? "Bisecting: a merge base must be tested"
                        : "Bisecting: " + remaining + " revision" + (remaining == 1 ? "" : "s")
                                + " left to test");
    }

    public BisectStatus bisectStatus() {
        repo.ensureOpen();
        if (!bisect().isActive()) return BisectStatus.inactive();
        String current = bisect.currentCandidate();
        if (current == null) {
            return advance();
        }
        return new BisectStatus(true, current, subjectOf(current), 0, false,
                "Testing " + CliRepo.shorten(current));
    }

    public GitResult bisectReset() {
        repo.ensureOpen();
        if (!bisect().isActive()) return GitResult.failed("No bisect session is running");
        String returnTo = bisect.originalPosition();
        bisect.reset();
        // A session the command line started may have packed refs/bisect marks, which the
        // session's own clean-up (loose files only) cannot reach.
        var marks = repo.runner.run("for-each-ref", "--format=%(refname)", "refs/bisect/");
        if (marks.isOk()) {
            for (String ref : marks.trimmedStdout().split("\\r?\\n")) {
                if (!ref.isBlank()) repo.runner.run("update-ref", "-d", ref.strip());
            }
        }
        if (returnTo == null || returnTo.isBlank()) {
            return GitResult.ok("Bisect session ended");
        }
        GitResult checkout = repo.branches.checkoutBranch(returnTo);
        return checkout.isOk()
                ? GitResult.ok("Bisect ended; returned to " + returnTo)
                : GitResult.failed("Bisect session ended, but could not return to " + returnTo
                        + ": " + checkout.getMessage());
    }

    /** Full SHA-1 for any revision spec, or {@code null} if it does not resolve. */
    private String resolveFullHash(String revision) {
        var res = repo.runner.run("rev-parse", "--verify", "--quiet", revision);
        return res.isOk() ? res.trimmedStdout() : null;
    }

    /** A commit's subject line, or {@code null} if it cannot be read. */
    private String subjectOf(String hash) {
        var res = repo.runner.run(GitCommand.of("log", "-1", "--format=%s").revs(hash).args());
        return res.isOk() ? res.trimmedStdout() : null;
    }
}
