package com.jfx.git.ui;

import com.jfx.git.core.GitBackend;
import com.jfx.git.model.OperationState;

import java.util.StringJoiner;

/**
 * A stopped operation in words, with the refs it names: "Merging feature/simple into main",
 * "Rebasing feature onto main (step 3 of 7)", "Cherry-picking a1b2c3d onto main",
 * "Reverting a1b2c3d".
 *
 * <p>The in-progress banner used to say "Merging at a1b2c3d" - the incoming commit's hash
 * and nothing about which branch it came from or where it was going. Commercial clients lead their
 * conflict panel with "Merging [feature] into [main]", the two refs as chips, because that
 * is the first thing anyone resolving a conflict needs straight: which side is theirs.
 *
 * <p>Pure: the refs are resolved by whoever builds one (on the repository lane), so the
 * wording is the same for every engine and can be tested without a repository.
 *
 * @param verb        "Merging", "Rebasing", "Cherry-picking", "Reverting", "Applying patches"
 * @param subject     what is brought in, or null when git recorded nothing
 * @param preposition "into" or "onto", or null when there is no target to name
 * @param target      where it lands, or null
 * @param progress    "(step 3 of 7)", or null
 */
public record OperationHeadline(String verb, Ref subject, String preposition, Ref target, String progress) {

    /** How a ref chip is drawn: the graph rail's own chip styles. */
    public enum RefKind { LOCAL, HEAD, REMOTE, TAG, COMMIT }

    /**
     * One ref the headline names.
     *
     * @param name a branch or tag name, or a full commit hash for {@link RefKind#COMMIT}
     */
    public record Ref(String name, RefKind kind) {

        /** What the chip says: the name, or a commit's short hash. */
        public String label() {
            return kind == RefKind.COMMIT ? GitBackend.shortenHash(name) : name;
        }

        /** The chip's style classes beside {@code ref-chip}, as the graph rail uses them. */
        public String styleClass() {
            return switch (kind) {
                case LOCAL -> "ref-chip-local";
                case HEAD -> "ref-chip-head";
                case REMOTE -> "ref-chip-remote";
                case TAG -> "ref-chip-tag";
                case COMMIT -> "ref-chip-commit";
            };
        }

        /** A name as a branch of {@code nameKind}, a hash as a commit; null for null. */
        public static Ref of(String nameOrHash, RefKind nameKind) {
            if (nameOrHash == null || nameOrHash.isBlank()) return null;
            return new Ref(nameOrHash, OperationState.isHash(nameOrHash) ? RefKind.COMMIT : nameKind);
        }
    }

    /**
     * The headline from what the state recorded, without looking anything up: a name is a
     * branch, a hash a commit. The branch HEAD is on is drawn as HEAD.
     *
     * @return null for nothing in progress, and for bisect, which has its own banner
     */
    public static OperationHeadline of(OperationState state) {
        if (state == null) return null;
        boolean onHead = !state.isRebase();
        return of(state, Ref.of(state.incoming(), RefKind.LOCAL),
                Ref.of(state.target(), onHead ? RefKind.HEAD : RefKind.LOCAL));
    }

    /**
     * The headline with the two refs already resolved - a hash named by the branch whose
     * tip it is, a name given its kind.
     *
     * @return null for nothing in progress, and for bisect
     */
    public static OperationHeadline of(OperationState state, Ref incoming, Ref target) {
        if (state == null) return null;
        return switch (state.kind()) {
            case MERGING -> new OperationHeadline("Merging", incoming,
                    target == null ? null : "into", target, null);
            case REBASING, REBASING_APPLY -> new OperationHeadline("Rebasing", incoming,
                    target == null ? null : "onto", target, progress("step", state));
            case CHERRY_PICKING -> new OperationHeadline("Cherry-picking", incoming,
                    target == null ? null : "onto", target, null);
            // Named by the commit alone: a revert lands where HEAD is, which goes
            // without saying.
            case REVERTING -> new OperationHeadline("Reverting", incoming, null, null, null);
            case APPLYING_MAILBOX -> new OperationHeadline("Applying patches", null,
                    target == null ? null : "onto", target, progress("patch", state));
            case NONE, BISECTING -> null;
        };
    }

    private static String progress(String unit, OperationState state) {
        return state.step() > 0 && state.steps() >= state.step()
                ? "(" + unit + " " + state.step() + " of " + state.steps() + ")" : null;
    }

    /** "Merging feature/simple into main", as one line. */
    public String text() {
        StringJoiner line = new StringJoiner(" ");
        line.add(verb);
        if (subject != null) line.add(subject.label());
        if (target != null) {
            line.add(preposition);
            line.add(target.label());
        }
        if (progress != null) line.add(progress);
        return line.toString();
    }

    // ------------------------------------------------------------------
    // The sentences around it
    // ------------------------------------------------------------------

    /**
     * What finishes this operation once nothing conflicts: "commit the merge", "continue the
     * rebase". Null with nothing in progress - a conflict from applying a stash has nothing
     * to finish.
     */
    public static String finishing(OperationState state) {
        if (state == null) return null;
        return switch (state.kind()) {
            case MERGING -> "commit the merge";
            case REBASING, REBASING_APPLY -> "continue the rebase";
            case CHERRY_PICKING -> "continue the cherry-pick";
            case REVERTING -> "continue the revert";
            case APPLYING_MAILBOX -> "continue applying the patches";
            case NONE, BISECTING -> null;
        };
    }

    /** "1 conflicting file", "3 conflicting files". */
    public static String conflictCount(int conflicts) {
        return conflicts + (conflicts == 1 ? " conflicting file" : " conflicting files");
    }

    /**
     * What the in-progress banner asks of the user, under the headline: what is
     * conflicting and how to finish, or why it stopped when nothing is.
     */
    public static String advice(OperationState state, int conflicts) {
        // Where a rebase or a patch series stopped is the one thing its headline does not
        // say; a merge, cherry-pick or revert names its commit in the headline already.
        boolean sequence = state.isRebase() || state.kind() == OperationState.Kind.APPLYING_MAILBOX;
        String where = sequence && state.stoppedAt() != null
                ? "Stopped at " + GitBackend.shortenHash(state.stoppedAt()) + " - " : "";
        if (conflicts > 0) {
            String finish = finishing(state);
            return where + conflictCount(conflicts) + " - resolve " + (conflicts == 1 ? "it" : "each one")
                    + " and mark " + (conflicts == 1 ? "it" : "them") + " resolved"
                    + (finish == null ? "." : ", then " + finish + ".");
        }
        if (state.editStop()) {
            return "Stopped so you can amend this commit. Continue when you are done.";
        }
        if (state.kind() == OperationState.Kind.MERGING) {
            return "All conflicts are resolved - commit the merge.";
        }
        return where + "Nothing is conflicting. Continue, or skip this step if it has nothing left to apply.";
    }

    /**
     * The graph's strip while the operation is stopped: "Merge of feature into main
     * stopped: 2 conflicting files", "Rebase of feature onto main stopped at a1b2c3d:
     * amend the commit, then continue".
     */
    public static String graphBanner(OperationState state, OperationHeadline headline, int conflicts) {
        String noun = switch (state.kind()) {
            case MERGING -> "Merge";
            case REBASING, REBASING_APPLY -> "Rebase";
            case CHERRY_PICKING -> "Cherry-pick";
            case REVERTING -> "Revert";
            case APPLYING_MAILBOX -> "Patch series";
            default -> state.label();
        };
        StringJoiner what = new StringJoiner(" ");
        what.add(noun);
        if (headline != null) {
            if (headline.subject() != null) what.add("of").add(headline.subject().label());
            if (headline.target() != null) what.add(headline.preposition()).add(headline.target().label());
        }
        boolean sequence = state.isRebase() || state.kind() == OperationState.Kind.APPLYING_MAILBOX;
        String stopped = sequence && state.stoppedAt() != null
                ? " stopped at " + GitBackend.shortenHash(state.stoppedAt()) : " stopped";
        String why;
        if (conflicts > 0) {
            why = conflictCount(conflicts);
        } else if (state.editStop()) {
            why = "amend the commit, then continue";
        } else {
            String finish = finishing(state);
            why = state.kind() == OperationState.Kind.MERGING
                    ? "all conflicts resolved - " + finish
                    : "nothing conflicting - continue or skip";
        }
        return what + stopped + ": " + why;
    }

    /** "All conflicts resolved - commit the merge", for when the last one is marked resolved. */
    public static String allResolved(OperationState state) {
        String finish = finishing(state);
        return finish == null ? "All conflicts resolved" : "All conflicts resolved - " + finish;
    }
}
