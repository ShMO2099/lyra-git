package com.jfx.git.core;

/**
 * A pull/merge request's overall CI status at its head commit, the way commercial clients'
 * list icons distinguish passed / pending / failing - normalised across GitHub's check
 * runs, GitLab's commit statuses, and Bitbucket's commit statuses, each of which reports
 * a list of individual checks rather than one combined value.
 */
public enum BuildStatus {
    /** No CI configured for this commit at all - not the same as "checks ran and passed". */
    NONE,
    /** At least one check is still running or queued, and none has failed yet. */
    PENDING,
    /** Every check that ran completed successfully. */
    PASSED,
    /** At least one check failed, errored, or was cancelled. */
    FAILED;

    /**
     * Reduces a set of individual per-check states to one overall status - failing
     * beats pending beats passing, the same precedence the commercial clients' icon implies:
     * a build is not "passing" while something else is still failing or unresolved.
     */
    public static BuildStatus combine(java.util.List<BuildStatus> checks) {
        if (checks.isEmpty()) {
            return NONE;
        }
        if (checks.contains(FAILED)) {
            return FAILED;
        }
        if (checks.contains(PENDING)) {
            return PENDING;
        }
        return PASSED;
    }
}
