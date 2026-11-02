package com.jfx.git.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The path filter of a search in files reads as {@code git grep -- <pathspec>} reads it -
 * the JGit engine applies it here, the CLI engine hands it to git, and the two must agree.
 */
class GrepQueryTest {

    private static GrepQuery filter(String glob) {
        return GrepQuery.literal("x").withPathGlob(glob);
    }

    @Test
    void blankMatchesEveryFile() {
        assertTrue(filter("").matchesPath("a/b/c.txt"));
        assertTrue(filter(" , ").matchesPath("c.txt"));
        assertTrue(new GrepQuery("x", false, false, false, null).matchesPath("c.txt"));
    }

    @Test
    void aPatternWithoutAWildcardIsAFileOrAFolder() {
        assertTrue(filter("src").matchesPath("src/Main.java"));
        assertTrue(filter("src/").matchesPath("src/a/Main.java"));
        assertTrue(filter("README.md").matchesPath("README.md"));
        assertFalse(filter("src").matchesPath("srcs/Main.java"), "a folder, not a prefix of a name");
        assertFalse(filter("README.md").matchesPath("docs/README.md"), "relative to the top, as git reads it");
    }

    @Test
    void aWildcardCrossesFoldersAsGitsDoes() {
        assertTrue(filter("*.java").matchesPath("Main.java"));
        assertTrue(filter("*.java").matchesPath("src/a/Main.java"));
        assertTrue(filter("src/*.java").matchesPath("src/a/Main.java"));
        assertFalse(filter("*.java").matchesPath("Main.javax"));
        assertTrue(filter("a?c.txt").matchesPath("abc.txt"));
        assertTrue(filter("[ab].txt").matchesPath("b.txt"));
        assertFalse(filter("[!ab].txt").matchesPath("b.txt"));
        assertFalse(filter("*.JAVA").matchesPath("Main.java"), "case-sensitive, as git's pathspecs are");
    }

    @Test
    void severalPatternsAndExcludes() {
        GrepQuery query = filter("*.java, *.kt, !src/generated");
        assertTrue(query.matchesPath("src/Main.java"));
        assertTrue(query.matchesPath("App.kt"));
        assertFalse(query.matchesPath("src/generated/Gen.java"), "an exclude wins");
        assertFalse(query.matchesPath("README.md"), "matches no include");
        assertTrue(filter("!*.min.js").matchesPath("app.js"), "excludes alone leave everything else in");
        assertFalse(filter("!*.min.js").matchesPath("lib/app.min.js"));
        assertEquals(List.of("*.java", "*.kt", "!src/generated"), query.pathPatterns());
    }

    @Test
    void anEmptyQueryIsEmpty() {
        assertTrue(GrepQuery.literal("").isEmpty());
        assertTrue(GrepQuery.literal(null).isEmpty());
        assertFalse(GrepQuery.literal(" ").isEmpty(), "a space is something to look for");
    }
}
