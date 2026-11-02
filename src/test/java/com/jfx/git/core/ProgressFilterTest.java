package com.jfx.git.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Git's progress, read out of its stderr: reported as it arrives, and taken out of the
 * text callers parse for errors - which must read exactly as it did without progress.
 */
class ProgressFilterTest {

    private record Run(String kept, List<GitTaskRunner.Progress> reported) {
    }

    private static Run filter(String stderr) {
        List<GitTaskRunner.Progress> reported = new ArrayList<>();
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        NativeGitRunner.ProgressFilter.copy(
                new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8)), kept, reported::add);
        return new Run(kept.toString(StandardCharsets.UTF_8), reported);
    }

    @Test
    void percentagesAreReportedAndNotKept() {
        Run run = filter("Receiving objects:  45% (450/1000)\rReceiving objects: 100% (1000/1000), done.\n");
        assertEquals("", run.kept(), "nothing of the progress is left for an error parser to trip over");
        assertEquals(2, run.reported().size());
        assertEquals("Receiving objects", run.reported().get(0).stage());
        assertEquals(0.45, run.reported().get(0).fraction(), 0.001);
        assertEquals(1.0, run.reported().get(1).fraction(), 0.001);
    }

    @Test
    void remoteStagesWithoutAPercentageAreReportedAsUnknown() {
        Run run = filter("remote: Enumerating objects: 5, done.\nremote: Counting objects:  20% (1/5)\r");
        assertEquals("", run.kept());
        assertEquals("Enumerating objects", run.reported().get(0).stage());
        assertTrue(run.reported().get(0).fraction() < 0);
        assertEquals("Counting objects", run.reported().get(1).stage());
    }

    @Test
    void everythingElseIsKeptLineForLine() {
        Run run = filter("""
                Receiving objects: 100% (3/3), done.
                From ../origin
                 * [new branch]      main       -> origin/main
                fatal: could not read Username for 'https://example.com': terminal prompts disabled
                """);
        assertEquals("""
                From ../origin
                 * [new branch]      main       -> origin/main
                fatal: could not read Username for 'https://example.com': terminal prompts disabled
                """, run.kept());
        assertEquals(1, run.reported().size());
    }

    @Test
    void aLastLineWithoutAnEndIsKept() {
        assertEquals("error: failed to push\n", filter("error: failed to push").kept());
    }
}
