package com.jfx.git.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@link SignatureStatus}'s own small helpers - the enum values themselves are exercised end-to-end by {@code NativeGitBackendTest} and {@code GitServiceTest}. */
class SignatureStatusTest {

    @Test
    void onlyNoneIsUnsigned() {
        assertFalse(SignatureStatus.NONE.isSigned());
        for (SignatureStatus status : SignatureStatus.values()) {
            if (status == SignatureStatus.NONE) continue;
            assertTrue(status.isSigned(), status + " should report as signed");
        }
    }

    @Test
    void onlyBadAndRevokedAreProblems() {
        for (SignatureStatus status : SignatureStatus.values()) {
            boolean expected = status == SignatureStatus.BAD || status == SignatureStatus.GOOD_REVOKED_KEY;
            assertEquals(expected, status.isProblem(), status + ".isProblem()");
        }
    }

    @Test
    void everyValueHasANonBlankLabel() {
        for (SignatureStatus status : SignatureStatus.values()) {
            assertFalse(status.label().isBlank(), status + " has a blank label");
        }
    }

    @Test
    void unverifiedIsNotTheSameAsGood() {
        // The whole point of SIGNED_UNVERIFIED existing as its own value: JGit must
        // never be able to claim GOOD without ever having checked anything.
        assertNotEquals(SignatureStatus.GOOD, SignatureStatus.SIGNED_UNVERIFIED);
        assertFalse(SignatureStatus.SIGNED_UNVERIFIED.label().equals(SignatureStatus.GOOD.label()));
    }
}
