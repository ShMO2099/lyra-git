package com.jfx.git.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The words a refusal for local changes comes in are recognised, and nothing else is taken
 * for one. What each engine really refuses with, typed, is {@code RefusalConformanceTest}'s.
 */
class LocalChangesRefusalTest {

    @Test
    void gitsAndTheApplicationsOwnWordingsAreRecognised() {
        assertTrue(LocalChangesRefusal.matches(
                "error: Your local changes to the following files would be overwritten by checkout:\n"
                        + "\ta.txt\nPlease commit your changes or stash them before you switch branches."));
        assertTrue(LocalChangesRefusal.matches(
                "error: The following untracked working tree files would be overwritten by checkout:\n\tb.txt"));
        assertTrue(LocalChangesRefusal.matches(
                "error: cannot pull with rebase: You have unstaged changes.\nerror: Please commit or stash them."));
        assertTrue(LocalChangesRefusal.matches("Checkout failed: Checkout conflict with files: \na.txt"));
        assertTrue(LocalChangesRefusal.matches("Cannot pull with rebase: you have uncommitted"
                + " changes (a.txt). Commit or stash them first."));
    }

    @Test
    void otherFailuresAreNot() {
        assertFalse(LocalChangesRefusal.matches("fatal: invalid reference: nope"));
        assertFalse(LocalChangesRefusal.matches(""));
        assertFalse(LocalChangesRefusal.matches(null));
    }
}
