package com.jfx.git.model;

/** How a pull should integrate the fetched commits. */
public enum PullMode {
    /** Fast-forward when possible, otherwise merge. */
    MERGE,
    /** Refuse anything that is not a fast-forward. */
    FF_ONLY,
    /** Rebase local commits on top of upstream. */
    REBASE
}
