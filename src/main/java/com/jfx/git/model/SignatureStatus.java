package com.jfx.git.model;

/**
 * Whether a commit is GPG-signed, and how much that has actually been checked.
 *
 * <p>The two engines can answer this to different depths, and the difference is real,
 * not a gap to paper over. Native git calls the user's own {@code gpg} to verify a
 * signature - it can tell a good signature from a bad one, an untrusted key from a
 * revoked one. JGit does none of that on its own: it can only say whether a signature
 * blob is present on the commit object, which is why {@link #SIGNED_UNVERIFIED} exists
 * as its own state rather than being folded into {@link #GOOD} - claiming a signature
 * is good without ever having checked it would be worse than not showing a badge at
 * all.
 *
 * <p>Names and meanings follow {@code git log}'s own {@code %G?} placeholder
 * ({@code N/G/B/U/X/Y/R/E}), the native backend's actual source for this.
 */
public enum SignatureStatus {
    /** No signature on this commit. */
    NONE,
    /** A signature is present, but this engine cannot verify it - JGit's ceiling. */
    SIGNED_UNVERIFIED,
    /** Verified: a good signature from a trusted key. */
    GOOD,
    /** Verified: the signature does not check out. */
    BAD,
    /** Verified: a good signature, but the key is not trusted. */
    GOOD_UNTRUSTED,
    /** Verified: a good signature, but the key (or the signature itself) has expired. */
    GOOD_EXPIRED,
    /** Verified: a good signature from a key that has since been revoked. */
    GOOD_REVOKED_KEY,
    /** Could not be checked at all - typically the signer's public key is not available. */
    CANNOT_VERIFY;

    /** A short phrase for a badge label. */
    public String label() {
        return switch (this) {
            case NONE -> "Unsigned";
            case SIGNED_UNVERIFIED -> "Signed (unverified)";
            case GOOD -> "Signed (verified)";
            case BAD -> "Signed (invalid)";
            case GOOD_UNTRUSTED -> "Signed (untrusted key)";
            case GOOD_EXPIRED -> "Signed (expired)";
            case GOOD_REVOKED_KEY -> "Signed (revoked key)";
            case CANNOT_VERIFY -> "Signed (key unavailable)";
        };
    }

    /** Whether a signature is present at all, verified or not. */
    public boolean isSigned() {
        return this != NONE;
    }

    /** Whether this status represents an outright problem worth calling out, not just an absence. */
    public boolean isProblem() {
        return this == BAD || this == GOOD_REVOKED_KEY;
    }
}
