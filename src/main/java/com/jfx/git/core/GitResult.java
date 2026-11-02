package com.jfx.git.core;

import java.util.List;

/**
 * Outcome of a git mutation.
 *
 * <p>Replaces the old {@code boolean} returns, which could not distinguish
 * "worked", "hit a conflict" and "failed" - conflicts were reported to the user
 * as success.
 *
 * <p>A failure may also carry a {@link Refusal}: git declining for a reason the app can act
 * on - local changes in the way, a branch not fully merged, and the rest. It sits beside
 * the status rather than being a status of its own, so every caller that only asks
 * whether it worked stays as it was (docs/adr/0001-refusals-beside-status.md).
 */
public final class GitResult {

    public enum Status {
        /** The operation completed. */
        OK,
        /** The operation stopped because of conflicts; {@link #getConflicts()} lists the paths. */
        CONFLICT,
        /** The operation could not be performed. */
        FAILED
    }

    private final Status status;
    private final String message;
    private final List<String> conflicts;
    private final Refusal refusal;

    private GitResult(Status status, String message, List<String> conflicts, Refusal refusal) {
        this.status = status;
        this.message = message;
        this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        this.refusal = refusal;
    }

    public static GitResult ok() {
        return new GitResult(Status.OK, "", List.of(), null);
    }

    public static GitResult ok(String message) {
        return new GitResult(Status.OK, message, List.of(), null);
    }

    public static GitResult conflict(String message, List<String> conflicts) {
        return new GitResult(Status.CONFLICT, message, conflicts, null);
    }

    public static GitResult failed(String message) {
        return new GitResult(Status.FAILED, message, List.of(), null);
    }

    /**
     * A failure that is a refusal: {@code message} for the person, {@code refusal} for the
     * caller deciding what to offer. Set by the engine part that read git's words or caught
     * JGit's exception - never worked out afterwards from the message.
     *
     * @param refusal what kind of refusal; {@code null} makes this a plain {@link #failed}
     */
    public static GitResult refused(String message, Refusal refusal) {
        return new GitResult(Status.FAILED, message, List.of(), refusal);
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    /** Conflicting paths, empty unless {@link #getStatus()} is {@link Status#CONFLICT}. */
    public List<String> getConflicts() {
        return conflicts;
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    public boolean isConflict() {
        return status == Status.CONFLICT;
    }

    /**
     * Why git declined, when the failure is a {@link Refusal} the app can act on;
     * {@code null} for success, a conflict, and every other failure. Read it with a type
     * pattern: {@code if (result.getRefusal() instanceof Refusal.LocalChanges changes)}.
     */
    public Refusal getRefusal() {
        return refusal;
    }

    /** Human-readable summary suitable for a status bar or dialog. */
    public String describe() {
        if (isOk()) {
            return message == null || message.isEmpty() ? "Done" : message;
        }
        if (isConflict()) {
            return message + (conflicts.isEmpty() ? "" : " (" + conflicts.size() + " conflicting file(s))");
        }
        return message;
    }
}
