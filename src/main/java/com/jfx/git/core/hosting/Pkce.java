package com.jfx.git.core.hosting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The random values a loopback sign-in depends on: the PKCE verifier and challenge
 * (RFC 7636) and the {@code state} parameter.
 *
 * <p>A loopback redirect travels through the browser and arrives at a port any local
 * process could also listen on or post to. PKCE makes an intercepted authorization code
 * useless without the verifier, which never leaves this process; {@code state} makes a
 * redirect this process did not ask for - a forged one, or a stale tab from an earlier
 * attempt - recognisable and refused.
 */
public final class Pkce {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private Pkce() {
    }

    /**
     * A fresh code verifier: 32 random bytes, base64url without padding - 43 characters,
     * the minimum length RFC 7636 allows and the form it recommends.
     */
    public static String verifier() {
        return random32();
    }

    /** The S256 challenge for {@code verifier}: base64url(SHA-256(ASCII(verifier))). */
    public static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return URL.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime is required to provide SHA-256.
            throw new IllegalStateException(e);
        }
    }

    /** A fresh, unguessable {@code state} value. */
    public static String state() {
        return random32();
    }

    /**
     * Whether {@code actual} equals {@code expected}, taking the same time whatever the
     * first differing character.
     *
     * <p>{@code String.equals} returns at the first mismatch, so how long a refusal takes
     * says how much of a guess was right. The window is small on a loopback port, but the
     * fix costs nothing.
     */
    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String random32() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL.encodeToString(bytes);
    }
}
