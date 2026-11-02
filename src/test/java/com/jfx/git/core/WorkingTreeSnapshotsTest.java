package com.jfx.git.core;

import com.jfx.git.model.Snapshot;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The message every engine writes, and which snapshots pruning lets go. */
class WorkingTreeSnapshotsTest {

    @Test
    void theMessageReadsBackAsWritten() {
        String message = WorkingTreeSnapshots.message("Before discarding a.txt", "feature/x", 3);
        Snapshot read = WorkingTreeSnapshots.parse("1700000000000", "abc1234", message);
        assertEquals("Before discarding a.txt", read.reason());
        assertEquals("feature/x", read.branch());
        assertEquals(3, read.fileCount());
        assertEquals(Instant.ofEpochMilli(1700000000000L), read.time());
        assertEquals("abc1234", read.hash());
    }

    @Test
    void aReasonIsKeptToOneCleanLine() {
        String message = WorkingTreeSnapshots.message("two\nlines\u001e", "main", 1);
        assertEquals("two", WorkingTreeSnapshots.parse("1", "h", message).reason());
        assertEquals("Snapshot", WorkingTreeSnapshots.parse("1", "h",
                WorkingTreeSnapshots.message("  ", "main", 1)).reason());
    }

    @Test
    void onlyDigitsAreAnId() {
        assertTrue(WorkingTreeSnapshots.isId("1700000000000"));
        assertFalse(WorkingTreeSnapshots.isId("../heads/main"));
        assertFalse(WorkingTreeSnapshots.isId(""));
        assertFalse(WorkingTreeSnapshots.isId(null));
        assertNull(WorkingTreeSnapshots.parse("x1", "h", "m"));
    }

    @Test
    void aTakenIdMovesOnByAMillisecond() {
        List<String> asked = new ArrayList<>();
        String id = WorkingTreeSnapshots.newId(candidate -> {
            asked.add(candidate);
            return asked.size() <= 2;
        });
        assertEquals(3, asked.size());
        assertEquals(Long.parseLong(asked.get(0)) + 2, Long.parseLong(id));
    }

    @Test
    void aSnapshotIsDiffedAgainstTheWorkingTreeAsGitWould() {
        byte[] old = "one\ntwo\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] now = "one\r\nthree\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String diff = new String(SnapshotComparison.format("a.txt", old, now), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(diff.startsWith("diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@"), diff);
        assertTrue(diff.contains("\n-two\n") && diff.contains("\n+three\n"), diff);
        assertFalse(diff.contains("-one"), "CRLF on disk against LF in the repository is not a change: " + diff);

        assertTrue(new SnapshotComparison.Sides(old, "one\r\ntwo\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)).same());
        assertFalse(new SnapshotComparison.Sides(old, null).same());
        String gone = new String(SnapshotComparison.format("a.txt", old, null), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(gone.contains("deleted file mode") && gone.contains("+++ /dev/null"), gone);
    }

    private static List<Snapshot> hourly(Instant now, int count) {
        List<Snapshot> newestFirst = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant at = now.minus(Duration.ofHours(i * 6L));
            newestFirst.add(new Snapshot(Long.toString(at.toEpochMilli()), "h" + i, at, "r", "main", 1));
        }
        return newestFirst;
    }

    @Test
    void pruningKeepsTheNewestFiftyOrTheLastWeekWhicheverKeepsMore() {
        Instant now = Instant.parse("2026-10-07T12:00:00Z");
        // Four a day: seven days is 28, so the newest fifty decide.
        List<Snapshot> sparse = hourly(now, 60);
        List<Snapshot> pruned = WorkingTreeSnapshots.toPrune(sparse, now);
        assertEquals(sparse.subList(50, 60), pruned);

        // Every minute for two hours: all inside the week, so nothing goes.
        List<Snapshot> dense = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            Instant at = now.minus(Duration.ofMinutes(i));
            dense.add(new Snapshot(Long.toString(at.toEpochMilli()), "h", at, "r", "main", 1));
        }
        assertEquals(List.of(), WorkingTreeSnapshots.toPrune(dense, now));
        assertEquals(List.of(), WorkingTreeSnapshots.toPrune(hourly(now, 10), now));
    }
}
