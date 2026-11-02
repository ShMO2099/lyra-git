package com.jfx.git.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Subject, body and the summary a graph row carries (G15).
 *
 * <p>The rules are git's own ({@code %s} and {@code %b}), because the CLI engine's graph
 * page gets its subjects from git and the other engines cut theirs here: the two have to
 * agree, or one commit reads two ways depending on the engine.
 */
class CommitInfoTest {

    private static CommitInfo full(String message) {
        return new CommitInfo("0123456789abcdef0123456789abcdef01234567", message,
                "A", "a@example.com", Instant.EPOCH, List.of(), List.of());
    }

    @Test
    void theSubjectIsTheFirstParagraphOnOneLine() {
        assertEquals("Subject", CommitInfo.subjectOf("Subject\n\nBody\n"));
        assertEquals("wrapped subject", CommitInfo.subjectOf("wrapped  \nsubject\n\nbody"));
        assertEquals("after blanks", CommitInfo.subjectOf("\n  \nafter blanks\n"));
        assertEquals("", CommitInfo.subjectOf(""));
        assertEquals("", CommitInfo.subjectOf(null));
    }

    @Test
    void theBodyStartsAfterTheSubjectParagraph() {
        assertEquals("Body one\nBody two\n", CommitInfo.bodyOf("Subject\n\n\nBody one\nBody two\n"));
        assertEquals("", CommitInfo.bodyOf("Subject only\n"));
        assertEquals("body", CommitInfo.bodyOf("wrapped\nsubject\n \nbody"));
    }

    @Test
    void aWholeMessageAnswersEverything() {
        CommitInfo commit = full("Subject\n\nBody one\nBody two\n");
        assertTrue(commit.hasFullMessage());
        assertEquals("Subject\n\nBody one\nBody two\n", commit.getFullMessage());
        assertEquals("Subject", commit.getSubject());
        assertEquals("Body one Body two", commit.getBodySummary());
    }

    @Test
    void aSummaryRefusesToPassOffAMissingBodyAsAnEmptyOne() {
        CommitInfo summary = CommitInfo.summaryOf("0123456789abcdef0123456789abcdef01234567",
                "Subject\n\nBody\n", "A", "a@example.com", Instant.EPOCH, Instant.EPOCH,
                List.of(), List.of());

        assertFalse(summary.hasFullMessage());
        assertThrows(IllegalStateException.class, summary::getFullMessage);
        assertEquals("Subject", summary.getSubject());
        assertEquals("Body", summary.getBodySummary());
    }

    @Test
    void theBodyPreviewIsBoundedAndMarkedWhenCut() {
        String body = "word ".repeat(100);
        String preview = CommitInfo.bodyPreviewOf("Subject\n\n" + body);
        assertTrue(preview.length() <= CommitInfo.BODY_PREVIEW_COLUMNS, preview);
        assertTrue(preview.endsWith(".."), preview);
        assertTrue(body.startsWith(preview.substring(0, preview.length() - 2)), preview);

        assertEquals("short body", CommitInfo.bodyPreviewOf("Subject\n\nshort\nbody\n"));
    }

    @Test
    void theNativePreviewsPaddingIsTrimmed() {
        CommitInfo summary = CommitInfo.summary("0123456789abcdef0123456789abcdef01234567",
                "Subject", "Body\nline two" + " ".repeat(60), "A", "a@example.com",
                Instant.EPOCH, Instant.EPOCH, List.of(), List.of());
        assertEquals("Body line two", summary.getBodySummary());
        assertEquals("", CommitInfo.summary("0123456789abcdef0123456789abcdef01234567",
                "Subject", " ".repeat(80), "A", "a@example.com",
                Instant.EPOCH, Instant.EPOCH, List.of(), List.of()).getBodySummary());
    }
}
