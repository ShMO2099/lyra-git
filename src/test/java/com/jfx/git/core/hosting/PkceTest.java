package com.jfx.git.core.hosting;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PkceTest {

    /** RFC 7636 section 4.1: 43 to 128 characters of [A-Za-z0-9-._~]; base64url gives 43. */
    @Test
    void aVerifierIs43UnreservedCharacters() {
        for (int i = 0; i < 50; i++) {
            String verifier = Pkce.verifier();
            assertEquals(43, verifier.length());
            assertTrue(verifier.matches("[A-Za-z0-9_-]+"), verifier);
        }
    }

    @Test
    void verifiersAndStatesAreNotRepeated() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            assertTrue(seen.add(Pkce.verifier()));
            assertTrue(seen.add(Pkce.state()));
        }
    }

    /** The known-answer vector from RFC 7636 appendix B. */
    @Test
    void theS256ChallengeMatchesTheRfcExample() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void matchesComparesWholeValuesAndRefusesNull() {
        assertTrue(Pkce.matches("abc", "abc"));
        assertFalse(Pkce.matches("abc", "abd"));
        assertFalse(Pkce.matches("abc", "ab"));
        assertFalse(Pkce.matches("abc", null));
        assertFalse(Pkce.matches(null, "abc"));
    }
}
