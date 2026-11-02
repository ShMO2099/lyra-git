package com.jfx.git.core;

import com.jfx.git.core.HistoryOps.FileChange;
import com.jfx.git.model.StashEntry;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The native engine's output parsers, fed hand-built git output: the {@code -z} records,
 * separators inside free text and the other shapes that are hard to get a real git to
 * print on demand. No git is run.
 */
class CliParsersTest {

    private static final String US = "\u001f";

    private static String sha(char c) {
        return String.valueOf(c).repeat(40);
    }

    // ------------------------------------------------------------------
    // status --porcelain=v2 --branch -z
    // ------------------------------------------------------------------

    @Test
    void statusRecordsAreReadWhateverThePathsContain() {
        String out = "# branch.oid " + sha('a') + "\0"
                + "# branch.head feature/x\0"
                + "1 M. N... 100644 100644 100644 " + sha('1') + " " + sha('2') + " dir with space/a.txt\0"
                + "1 .M N... 100644 100644 100644 " + sha('1') + " " + sha('1') + " b.txt\0"
                + "1 A. N... 000000 100644 100644 " + sha('0') + " " + sha('3') + " new.txt\0"
                + "2 R. N... 100644 100644 100644 " + sha('4') + " " + sha('4') + " R100 renamed now.txt\0"
                + "was here.txt\0"
                + "u UU N... 100644 100644 100644 100644 " + sha('5') + " " + sha('6') + " " + sha('7') + " both.txt\0"
                + "? untracked dir/file.txt\0";

        CliParsers.StatusListing listing = CliParsers.parseStatus(out.getBytes(StandardCharsets.UTF_8));

        assertEquals("feature/x", listing.branch());
        assertEquals(List.of("dir with space/a.txt", "new.txt", "renamed now.txt"), listing.staged());
        assertEquals(List.of("b.txt", "both.txt", "untracked dir/file.txt"), listing.unstaged());
        assertEquals(Set.of("both.txt"), listing.conflicts());
        assertEquals('M', listing.status().get("dir with space/a.txt"));
        assertEquals('A', listing.status().get("new.txt"));
        assertEquals('R', listing.status().get("renamed now.txt"));
        assertEquals('A', listing.status().get("untracked dir/file.txt"));
        assertFalse(listing.status().containsKey("was here.txt"), "a rename's old path is not a row");
    }

    @Test
    void aDetachedHeadLeavesTheBranchForTheCallerToFillIn() {
        var listing = CliParsers.parseStatus("# branch.head (detached)\0".getBytes(StandardCharsets.UTF_8));
        assertEquals("", listing.branch());
        assertTrue(listing.staged().isEmpty() && listing.unstaged().isEmpty());
    }

    @Test
    void anIndexedNameInTheStatusIsNotSplitAtItsSpaces() {
        String out = "1 M. N... 100644 100644 100644 " + sha('1') + " " + sha('2') + " a b c d e f g h\0";
        assertEquals(List.of("a b c d e f g h"), CliParsers.parseStatus(out.getBytes(StandardCharsets.UTF_8)).staged());
    }

    // ------------------------------------------------------------------
    // stash list and reflog: free text last
    // ------------------------------------------------------------------

    @Test
    void stashIndexesAreGitsAndMessagesMayHoldSeparators() {
        String out = "stash@{0}" + US + sha('a') + US + sha('p') + " " + sha('i') + US + "Ann" + US + "100" + US
                + "On main: one \u001e two " + US + " three\0"
                + "stash@{1}" + US + sha('b') + US + sha('q') + US + "Bob" + US + "not-a-date" + US + "On main: x\0"
                + "garbage without fields\0";

        List<StashEntry> entries = CliParsers.parseStashList(out);

        assertEquals(2, entries.size());
        assertEquals(0, entries.get(0).index());
        assertEquals(sha('a'), entries.get(0).hash());
        assertEquals(sha('p'), entries.get(0).parentHash(), "the first parent is where the stash was taken");
        assertEquals("On main: one \u001e two " + US + " three", entries.get(0).message());
        assertEquals(Instant.ofEpochSecond(100), entries.get(0).when());
        assertEquals(1, entries.get(1).index());
        assertEquals(Instant.EPOCH, entries.get(1).when(), "an unreadable date keeps the entry and its index");
    }

    @Test
    void stashSelectorsAreReadExactly() {
        assertEquals(12, CliParsers.stashIndexOf("stash@{12}"));
        assertEquals(-1, CliParsers.stashIndexOf("stash@{yesterday}"));
        assertEquals(-1, CliParsers.stashIndexOf("refs/stash"));
    }

    @Test
    void reflogEntriesKeepTheirPlaceWhenAMessageHoldsSeparators() {
        String out = sha('c') + US + "Ann" + US + "HEAD@{300}" + US + "commit: a \u001e b " + US + " c\0"
                + sha('b') + US + "Ann" + US + "HEAD@{200}" + US + "checkout: moving\0"
                + sha('a') + US + "Ann" + US + "HEAD@{100}" + US + "commit (initial): first\0";

        List<ReflogItem> items = CliParsers.parseReflog(out, 2);

        assertEquals(2, items.size(), "one more was read only to know where the last one came from");
        assertEquals("commit: a \u001e b " + US + " c", items.get(0).comment());
        assertEquals(sha('c'), items.get(0).newHash());
        assertEquals(sha('b'), items.get(0).oldHash());
        assertEquals(sha('a'), items.get(1).oldHash());
        assertEquals(Instant.ofEpochSecond(200), items.get(1).when());
        assertEquals("Ann", items.get(1).author());
    }

    // ------------------------------------------------------------------
    // worktree list --porcelain
    // ------------------------------------------------------------------

    @Test
    void worktreesAreReadRecordByRecord() {
        String out = "worktree C:/repos/main repo\nHEAD " + sha('a') + "\nbranch refs/heads/main\n\n"
                + "worktree C:/repos/detached one\nHEAD " + sha('b') + "\ndetached\nlocked reason here\n\n"
                + "worktree C:/repos/bare\nbare\n";

        List<Worktrees.Worktree> trees = CliParsers.parseWorktrees(out);

        assertEquals(3, trees.size());
        assertEquals("(main)", trees.get(0).name());
        assertTrue(trees.get(0).main());
        assertEquals("main", trees.get(0).branch());
        assertEquals(new File("C:/repos/main repo"), trees.get(0).path());
        assertEquals("detached one", trees.get(1).name());
        assertEquals("bbbbbbb", trees.get(1).branch(), "labelled by its commit");
        assertTrue(trees.get(1).detached());
        assertTrue(trees.get(1).locked());
        assertEquals("detached", trees.get(2).branch(), "no HEAD line, nothing better to say");
    }

    // ------------------------------------------------------------------
    // for-each-ref, check-attr, ls-files, cat-file
    // ------------------------------------------------------------------

    @Test
    void trackingCountsAreKeyedByTheBranchesOwnName() {
        String out = "refs/heads/main\t[ahead 2, behind 1]\n"
                + "refs/heads/feature/x\t[behind 3]\n"
                + "refs/heads/level\t\n"
                + "refs/heads/orphaned\t[gone]\n";

        Map<String, int[]> tracking = CliParsers.parseBranchTracking(out);

        assertEquals(Set.of("main", "feature/x"), tracking.keySet());
        assertArrayEquals(new int[]{2, 1}, tracking.get("main"));
        assertArrayEquals(new int[]{0, 3}, tracking.get("feature/x"));
    }

    @Test
    void attributeValuesAreMatchedPerPath() {
        String out = "a: b.bin\0filter\0lfs\0plain.txt\0filter\0unspecified\0c.psd\0filter\0lfs\0";
        assertEquals(Set.of("a: b.bin", "c.psd"), CliParsers.pathsWithAttribute(out, "lfs"));
    }

    @Test
    void anIndexEntryIsTheExactPathNotWhatIsUnderIt() {
        String out = "100755 " + sha('1') + " 0\tdir/x\0" + "100644 " + sha('2') + " 0\tdir\0";
        assertEquals(new CliParsers.IndexEntry(true, "100644", false), CliParsers.parseIndexEntry(out, "dir"));
        assertEquals(new CliParsers.IndexEntry(true, "100755", false), CliParsers.parseIndexEntry(out, "dir/x"));
        assertFalse(CliParsers.parseIndexEntry(out, "di").exists());

        String conflicted = "100644 " + sha('1') + " 1\tc\0" + "100644 " + sha('2') + " 2\tc\0";
        assertTrue(CliParsers.parseIndexEntry(conflicted, "c").unmerged());
    }

    @Test
    void catFileAnswersAreToldApart() {
        byte[] content = "line\nno newline at end".getBytes(StandardCharsets.UTF_8);
        String out = sha('t') + " tree 37\n" + "x".repeat(37) + "\n"
                + "HEAD:gone.txt missing\n"
                + sha('b') + " blob " + content.length + "\n" + new String(content, StandardCharsets.UTF_8) + "\n"
                + sha('e') + " blob 0\n\n"
                + "abc ambiguous\n";

        byte[][] blobs = CliParsers.parseCatFileBatch(out.getBytes(StandardCharsets.UTF_8), 5);

        assertNull(blobs[0], "a tree is not a file's content");
        assertNull(blobs[1]);
        assertArrayEquals(content, blobs[2]);
        assertArrayEquals(new byte[0], blobs[3], "an empty file is empty, not absent");
        assertNull(blobs[4]);
    }

    @Test
    void catFileOutputThatStopsShortIsAnError() {
        byte[] cut = (sha('b') + " blob 100\nshort\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> CliParsers.parseCatFileBatch(cut, 1));
        byte[] fewer = ("HEAD:a missing\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> CliParsers.parseCatFileBatch(fewer, 2));
    }

    // ------------------------------------------------------------------
    // diff -z, config -z
    // ------------------------------------------------------------------

    @Test
    void numstatAndNameStatusCarryRenamesAcrossTheirSplitRecords() {
        String numstat = "3\t1\tplain.txt\0" + "0\t0\t\0old name.txt\0new name.txt\0" + "-\t-\timage.png\0";
        Map<String, int[]> counts = CliParsers.parseNumstat(numstat);
        assertArrayEquals(new int[]{3, 1}, counts.get("plain.txt"));
        assertArrayEquals(new int[]{0, 0}, counts.get("new name.txt"));
        assertFalse(counts.containsKey("image.png"), "binary: no count rather than zero");

        String nameStatus = "M\0plain.txt\0R100\0old name.txt\0new name.txt\0A\0image.png\0";
        List<FileChange> changes = CliParsers.parseNameStatus(nameStatus, counts);
        assertEquals(3, changes.size());
        assertEquals("new name.txt", changes.get(1).path());
        assertEquals("old name.txt", changes.get(1).oldPath());
        assertEquals('R', changes.get(1).status());
        assertEquals(FileChange.UNKNOWN_COUNT, changes.get(2).added());
    }

    @Test
    void configValuesKeepNewlinesAndTheLastValueWins() {
        String out = "core.editor\nvim\0alias.multi\nline one\nline two\0core.bare\0core.editor\ncode\0";
        var values = CliParsers.parseConfigList(new NativeGitRunner.Result(0, out.getBytes(StandardCharsets.UTF_8), ""));
        assertEquals("code", values.get("core.editor"));
        assertEquals("line one\nline two", values.get("alias.multi"));
        assertEquals("", values.get("core.bare"), "a key with no value is there, empty");
        assertTrue(CliParsers.parseConfigList(new NativeGitRunner.Result(1, new byte[0], "x")).isEmpty());
    }
}
