package com.jfx.git.model;

/** Where a submodule's checkout stands relative to what the superproject records. */
public enum SubmoduleState {
    /** Registered in .gitmodules but never initialised - the directory is empty. */
    UNINITIALIZED,
    /** Initialised but the commit the superproject points at is not present. */
    MISSING,
    /** Cloned, but checked out at a different commit than the superproject records. */
    OUT_OF_DATE,
    /** Checked out at exactly the recorded commit. */
    CURRENT;

    public String label() {
        return switch (this) {
            case UNINITIALIZED -> "not initialised";
            case MISSING -> "missing commit";
            case OUT_OF_DATE -> "out of date";
            case CURRENT -> "up to date";
        };
    }

    /** True when "Init / Update" would actually change something. */
    public boolean needsUpdate() {
        return this != CURRENT;
    }
}
