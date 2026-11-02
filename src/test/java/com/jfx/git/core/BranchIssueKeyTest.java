package com.jfx.git.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The issue key a branch is named after, and the summary prefix made from it. */
class BranchIssueKeyTest {

    private static final String PATTERN = BranchIssueKey.DEFAULT_PATTERN;

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "feature/ABC-123-x, ABC-123",
            "ABC-123, ABC-123",
            "bugfix/PROJ2-7, PROJ2-7",
            "ABC-123-and-DEF-456, ABC-123",
            "users/noon/JFX-42_login, JFX-42",
    })
    void findsTheKeyAnywhereInTheName(String branch, String key) {
        assertEquals(key, BranchIssueKey.keyIn(branch, PATTERN));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"main", "feature/login-form", "abc-123", "A-1", "release/2.0", "ABC-", "-123"})
    void aBranchWithoutAKeyHasNone(String branch) {
        assertNull(BranchIssueKey.keyIn(branch, PATTERN));
        assertEquals("", BranchIssueKey.prefixFor(branch, PATTERN, BranchIssueKey.DEFAULT_FORMAT));
    }

    @Test
    void theDefaultPrefixIsTheKeyAndAColon() {
        assertEquals("ABC-123: ", BranchIssueKey.prefixFor("feature/ABC-123-x", PATTERN, BranchIssueKey.DEFAULT_FORMAT));
    }

    @Test
    void theFormatPutsTheKeyWhereItSays() {
        assertEquals("[ABC-123] ", BranchIssueKey.prefixFor("feature/ABC-123-x", PATTERN, "[{key}] "));
    }

    @Test
    void aFormatWithoutTheKeyFallsBackToTheDefault() {
        assertEquals("ABC-123: ", BranchIssueKey.prefixFor("ABC-123", PATTERN, "fix: "));
        assertEquals("ABC-123: ", BranchIssueKey.prefixFor("ABC-123", PATTERN, ""));
    }

    @Test
    void aGroupInThePatternIsTheKey() {
        assertEquals("123", BranchIssueKey.keyIn("feature/gh-123-crash", "gh-(\\d+)"));
        assertEquals("#123 ", BranchIssueKey.prefixFor("feature/gh-123-crash", "gh-(\\d+)", "#{key} "));
    }

    @Test
    void aPatternThatDoesNotCompileFindsNothing() {
        assertFalse(BranchIssueKey.isValidPattern("[A-Z"));
        assertNull(BranchIssueKey.keyIn("ABC-123", "[A-Z"));
        assertTrue(BranchIssueKey.isValidPattern(PATTERN));
        assertFalse(BranchIssueKey.isValidPattern(""));
    }

    @Test
    void noBranchHasNoKey() {
        assertNull(BranchIssueKey.keyIn(null, PATTERN));
        assertNull(BranchIssueKey.keyIn("", PATTERN));
    }
}
