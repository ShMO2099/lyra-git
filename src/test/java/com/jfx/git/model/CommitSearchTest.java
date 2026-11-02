package com.jfx.git.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading the filter box into a history search, and answering it for a loaded row.
 *
 * <p>The engines' side of the contract is pinned in {@code BackendConformanceTest}; this
 * is the part that is pure Java - what a typed query means, and that the graph's instant
 * filter reads a prefixed query the same way the engines will.
 */
class CommitSearchTest {

    @Test
    void plainTextIsMessageText() {
        CommitSearch search = CommitSearch.parse("  fix   login ");
        assertEquals("fix login", search.message());
        assertEquals(List.of("fix", "login"), search.messageTerms());
        assertNull(search.author());
        assertFalse(CommitSearch.hasPrefix("fix login"));
    }

    @Test
    void prefixesFillTheirOwnCriteria() {
        CommitSearch search = CommitSearch.parse(
                "author:ada committer:bot hash:ABC123 path:./src/ content:needle fix");
        assertEquals("ada", search.author());
        assertEquals("bot", search.committer());
        assertEquals("abc123", search.hashPrefix(), "hashes are compared lower-case");
        assertEquals("src", search.path(), "normalised to git's form");
        assertEquals("needle", search.content());
        assertEquals("fix", search.message());
        assertTrue(CommitSearch.hasPrefix("author:ada"));
    }

    @Test
    void quotedValuesKeepTheirSpaces() {
        CommitSearch search = CommitSearch.parse("author:\"Ada Lovelace\" -G:\"x = 1\"");
        assertEquals("Ada Lovelace", search.author());
        assertEquals("x = 1", search.content());
        assertNull(search.message());
    }

    /** "fix: typo" is a common subject shape, and must not be read as a prefix. */
    @Test
    void anUnknownPrefixIsJustText() {
        CommitSearch search = CommitSearch.parse("fix: typo");
        assertEquals("fix: typo", search.message());
        assertFalse(CommitSearch.hasPrefix("fix: typo"));
    }

    @Test
    void blankAndRootValuesMeanNotSearchingByThem() {
        assertTrue(CommitSearch.parse("   ").isEmpty());
        assertTrue(CommitSearch.parse("author:").isEmpty());
        assertTrue(CommitSearch.EMPTY.withPath("/").isEmpty());
        assertTrue(CommitSearch.EMPTY.withPath(".").isEmpty());
    }

    @Test
    void theLoadedRowsAreMatchedAsTheEnginesWouldMatchThem() {
        CommitInfo commit = new CommitInfo("abcdef1234", "Tweak settings\n\nFixes the Frobnicator",
                "Alan Turing", "alan@bletchley.org", Instant.EPOCH, List.of(), List.of());

        assertTrue(CommitSearch.parse("frobnicator tweak").matchesLocally(commit),
                "words anywhere in subject or body");
        assertFalse(CommitSearch.parse("frobnicator missing").matchesLocally(commit));
        assertTrue(CommitSearch.parse("author:bletchley").matchesLocally(commit),
                "the email is part of the author");
        assertTrue(CommitSearch.parse("author:\"turing <alan\"").matchesLocally(commit),
                "matched against \"Name <email>\", as git greps the header");
        assertTrue(CommitSearch.parse("hash:ABCD").matchesLocally(commit));
        assertFalse(CommitSearch.parse("hash:bcd").matchesLocally(commit), "a prefix, not a substring");
    }

    /**
     * G15. A graph row carries only its subject and a body preview. A word the preview
     * lacks rules the row out only when the preview was the whole body; past a cut, the
     * body is unknown and the row stays, rather than vanishing from a filter that could
     * not see its message.
     */
    @Test
    void aGraphRowsSummaryIsMatchedOnlyAsFarAsItKnows() {
        CommitInfo whole = CommitInfo.summaryOf("abcdef1234", "Tweak settings\n\nFixes the Frobnicator",
                "Alan Turing", "alan@bletchley.org", Instant.EPOCH, Instant.EPOCH, List.of(), List.of());
        assertTrue(CommitSearch.parse("frobnicator tweak").matchesLocally(whole));
        assertFalse(CommitSearch.parse("frobnicator missing").matchesLocally(whole),
                "the preview is the whole body, so a missing word is a miss");

        String longBody = "Fixes the Frobnicator. " + "Background that runs on. ".repeat(10)
                + "The cause was the Zanzibar cache.";
        CommitInfo cut = CommitInfo.summaryOf("abcdef1234", "Tweak settings\n\n" + longBody,
                "Alan Turing", "alan@bletchley.org", Instant.EPOCH, Instant.EPOCH, List.of(), List.of());
        assertFalse(cut.isBodySummaryWhole());
        assertTrue(CommitSearch.parse("frobnicator").matchesLocally(cut), "found in the preview");
        assertTrue(CommitSearch.parse("zanzibar").matchesLocally(cut),
                "past the cut the body is unknown, so the row is kept rather than hidden");
        assertFalse(CommitSearch.parse("author:hopper zanzibar").matchesLocally(cut),
                "criteria the row does know still rule it out");
    }

    @Test
    void pathContentAndCommitterNeedTheRepository() {
        assertTrue(CommitSearch.parse("author:ada fix").locallyDecidable());
        assertFalse(CommitSearch.parse("path:src").locallyDecidable());
        assertFalse(CommitSearch.parse("content:x").locallyDecidable());
        assertFalse(CommitSearch.parse("committer:x").locallyDecidable());
    }
}
