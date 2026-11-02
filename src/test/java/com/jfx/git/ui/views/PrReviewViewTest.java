package com.jfx.git.ui.views;

import com.jfx.git.model.ReviewComment;
import com.jfx.git.ui.FxHarness;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The diff-plus-comments viewer all three providers' "Review…" buttons open. Covers
 * only this view's own wiring against a fake {@link PrReviewView.Backend} - what
 * each real provider's client sends/parses is covered separately by {@code
 * GitHubClientTest}/{@code GitLabClientTest}/{@code BitbucketClientTest}'s own review-
 * comment cases, and the compose form itself by {@code ReviewCommentComposeDialogTest}.
 */
class PrReviewViewTest {

    private static final String ONE_FILE_DIFF = """
            diff --git a/src/Main.java b/src/Main.java
            index 1111111..2222222 100644
            --- a/src/Main.java
            +++ b/src/Main.java
            @@ -1,3 +1,3 @@
             unchanged line
            -old line
            +new line
             another unchanged line
            """;

    /** Records every call, answers synchronously - fine for a unit test already on the FX thread. */
    private static final class FakeBackend implements PrReviewView.Backend {
        List<ReviewComment> comments = new ArrayList<>();
        int fetchCount = 0;
        List<Object[]> posted = new ArrayList<>();
        boolean supportsSuggestions = true;
        boolean failNextPost = false;

        @Override
        public void fetchComments(Consumer<PrReviewView.Outcome<List<ReviewComment>>> onDone) {
            fetchCount++;
            onDone.accept(PrReviewView.Outcome.ok(List.copyOf(comments)));
        }

        @Override
        public void postComment(String path, int line, ReviewComment.Side side, String body,
                                 Consumer<PrReviewView.Outcome<Void>> onDone) {
            posted.add(new Object[]{path, line, side, body});
            onDone.accept(failNextPost ? PrReviewView.Outcome.failed("nope") : PrReviewView.Outcome.ok(null));
        }

        @Override
        public boolean supportsSuggestions() {
            return supportsSuggestions;
        }
    }

    private PrReviewView view(List<PrReviewView.ReviewFile> files, FakeBackend backend) {
        return FxHarness.call(() -> {
            PrReviewView d = new PrReviewView("test PR", files, backend, new com.jfx.git.ui.StatusBar());
            d.content().applyCss();
            d.content().layout();
            return d;
        });
    }

    private PrReviewView.ReviewFile oneFile() {
        return new PrReviewView.ReviewFile("src/Main.java", ONE_FILE_DIFF.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void listsOneEntryPerFileAndAutoSelectsTheFirst() {
        var backend = new FakeBackend();
        PrReviewView view = view(List.of(oneFile()), backend);

        FxHarness.run(() -> {
            assertEquals(List.of("src/Main.java"), view.filesForTest());
            // Auto-selected: the diff should already be rendered with no extra click.
            assertTrue(view.rowTextForTest().stream().anyMatch(t -> t.contains("new line")));
        });
    }

    @Test
    void selectingAFileRendersItsRealDiffRows() {
        var backend = new FakeBackend();
        PrReviewView view = view(List.of(oneFile()), backend);

        FxHarness.run(() -> {
            view.selectFileForTest("src/Main.java");
            List<String> rows = view.rowTextForTest();
            assertTrue(rows.stream().anyMatch(t -> t.contains("unchanged line")));
            assertTrue(rows.stream().anyMatch(t -> t.contains("old line")));
            assertTrue(rows.stream().anyMatch(t -> t.contains("new line")));
        });
    }

    @Test
    void fetchesCommentsOnceOnOpen() {
        var backend = new FakeBackend();
        view(List.of(oneFile()), backend);

        assertEquals(1, backend.fetchCount);
    }

    @Test
    void anExistingCommentShowsAMarkerOnlyOnItsOwnRowAndSide() {
        var backend = new FakeBackend();
        // Row 3 is "+new line": old=null, new=2 -> side NEW, line 2.
        backend.comments.add(new ReviewComment("src/Main.java", 2, ReviewComment.Side.NEW, "octocat", "fix this"));
        PrReviewView view = view(List.of(oneFile()), backend);

        FxHarness.run(() -> {
            // Row 2 is "-old line": old=2, new=null -> side OLD, line 2 - must NOT match
            // the NEW-side comment even though the line number is the same.
            assertFalse(view.hasCommentMarkerForTest(2), "the old-side line at 2 should not show the new-side comment");
            assertTrue(view.hasCommentMarkerForTest(3), "the new-side line at 2 should show its own comment");
        });
    }

    @Test
    void addingACommentPostsThroughTheBackendAndReloadsComments() {
        var backend = new FakeBackend();
        PrReviewView view = view(List.of(oneFile()), backend);
        int fetchesBeforeAdding = backend.fetchCount;

        FxHarness.run(() -> view.addCommentForTest(3, "please rename this"));

        assertEquals(1, backend.posted.size());
        Object[] call = backend.posted.get(0);
        assertEquals("src/Main.java", call[0]);
        assertEquals(2, call[1]);
        assertEquals(ReviewComment.Side.NEW, call[2]);
        assertEquals("please rename this", call[3]);
        assertEquals(fetchesBeforeAdding + 1, backend.fetchCount, "a successful post should reload comments");
    }

    @Test
    void aFailedPostDoesNotReloadComments() {
        var backend = new FakeBackend();
        backend.failNextPost = true;
        PrReviewView view = view(List.of(oneFile()), backend);
        int fetchesBeforeAdding = backend.fetchCount;

        FxHarness.run(() -> view.addCommentForTest(3, "please rename this"));

        assertEquals(1, backend.posted.size(), "the post should still have been attempted");
        assertEquals(fetchesBeforeAdding, backend.fetchCount, "a failed post should not reload the list");
    }

    @Test
    void aPullRequestTouchingNoFilesShowsAnEmptyFileList() {
        var backend = new FakeBackend();
        PrReviewView view = view(List.of(), backend);

        FxHarness.run(() -> assertTrue(view.filesForTest().isEmpty()));
    }

    // ------------------------------------------------------------------
    // Whole file toggle
    // ------------------------------------------------------------------

    @Test
    void wholeFileTogglePullsInTheSuppliedContent() {
        var backend = new FakeBackend();
        PrReviewView view = view(List.of(oneFile()), backend);

        FxHarness.run(() -> {
            // ONE_FILE_DIFF's own hunk already covers every line of a 3-line file; this
            // supplier stands in for a real, longer head-side file so there is a genuine
            // tail past the hunk for whole-file mode to reveal.
            view.setFileContentSupplier(new com.jfx.git.core.GitService(), path ->
                    "unchanged line\nnew line\nanother unchanged line\nEXTRA TAIL LINE");
            view.setWholeFileForTest(true);
        });

        // The file is read on the repository lane, so it lands a moment later.
        waitUntil(() -> view.rowTextForTest().contains("EXTRA TAIL LINE"));
    }

    @Test
    void wholeFileToggleIsDisabledWithoutASupplier() {
        var backend = new FakeBackend();
        PrReviewView view = view(List.of(oneFile()), backend);

        FxHarness.run(() -> assertTrue(view.isWholeFileDisabledForTest(),
                "no content supplier means nothing to reveal from"));
    }

    @Test
    void wholeFileToggleDoesNotBreakExistingCommentLookup() {
        var backend = new FakeBackend();
        // Row 3 is "+new line": old=null, new=2 -> side NEW, line 2 (same fixture as
        // anExistingCommentShowsAMarkerOnlyOnItsOwnRowAndSide above).
        backend.comments.add(new ReviewComment("src/Main.java", 2, ReviewComment.Side.NEW, "octocat", "fix this"));
        PrReviewView view = view(List.of(oneFile()), backend);

        FxHarness.run(() -> {
            view.setFileContentSupplier(new com.jfx.git.core.GitService(), path -> "unchanged line\nnew line\nanother unchanged line");
            view.setWholeFileForTest(true);
        });

        // An existing hunk-row comment must still resolve once whole-file mode is on.
        waitUntil(() -> view.hasCommentMarkerForTest(3));
    }

    @Test
    void theWholeFileIsReadOffTheFxThread() {
        var backend = new FakeBackend();
        PrReviewView view = view(List.of(oneFile()), backend);
        java.util.concurrent.atomic.AtomicBoolean onFx = new java.util.concurrent.atomic.AtomicBoolean(true);

        FxHarness.run(() -> {
            view.setFileContentSupplier(new com.jfx.git.core.GitService(), path -> {
                onFx.set(javafx.application.Platform.isFxApplicationThread());
                return "unchanged line\nnew line\nanother unchanged line\nTAIL";
            });
            view.setWholeFileForTest(true);
        });

        waitUntil(() -> view.rowTextForTest().contains("TAIL"));
        assertFalse(onFx.get(), "git work belongs on a lane, not the FX thread");
    }

    /** Polls an FX-thread condition: whole-file content arrives from the repository lane. */
    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        try {
            org.testfx.util.WaitForAsyncUtils.waitFor(5, java.util.concurrent.TimeUnit.SECONDS,
                    () -> FxHarness.call(condition::getAsBoolean));
        } catch (java.util.concurrent.TimeoutException e) {
            fail("timed out waiting for the whole file to render");
        }
    }
}
