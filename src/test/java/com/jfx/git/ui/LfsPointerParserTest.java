package com.jfx.git.ui;

import com.jfx.git.model.LfsPointer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code parse}'s fixtures are the exact bytes a real {@code git-lfs} 3.7.1 install
 * produced for a tracked file, confirmed byte-for-byte via {@code git cat-file -p}
 * before writing this test - not a hand-guessed approximation of the spec.
 */
class LfsPointerParserTest {

    private static final String REAL_POINTER =
            "version https://git-lfs.github.com/spec/v1\n"
                    + "oid sha256:c37f0c49b82a3d813209c0260617e62684381e151fcc611aa5517f7e2ec55c6e\n"
                    + "size 2000\n";

    @Test
    void parsesARealPointerFile() {
        LfsPointer pointer = LfsPointerParser.parse(REAL_POINTER);

        assertNotNull(pointer);
        assertEquals("sha256:c37f0c49b82a3d813209c0260617e62684381e151fcc611aa5517f7e2ec55c6e",
                pointer.oid());
        assertEquals(2000, pointer.size());
    }

    @Test
    void parsesAPointerFileWithNoTrailingNewline() {
        // Not every producer of pointer-shaped text is guaranteed to end with one -
        // a hunk reconstruction, for instance, might not.
        String noTrailingNewline = REAL_POINTER.substring(0, REAL_POINTER.length() - 1);
        assertNotNull(LfsPointerParser.parse(noTrailingNewline));
    }

    @Test
    void anUnrelatedTextFileIsNotAPointer() {
        assertNull(LfsPointerParser.parse("hello world\nthis is a normal file\n"));
    }

    @Test
    void nullAndEmptyAreNotPointers() {
        assertNull(LfsPointerParser.parse(null));
        assertNull(LfsPointerParser.parse(""));
    }

    @Test
    void aFileThatIsJustTheVersionLineIsNotAPointer() {
        // Real content coincidentally starting with the version line but never
        // declaring oid/size must not be mistaken for one.
        assertNull(LfsPointerParser.parse("version https://git-lfs.github.com/spec/v1\n"));
    }

    @Test
    void missingOidIsNotAPointer() {
        assertNull(LfsPointerParser.parse(
                "version https://git-lfs.github.com/spec/v1\nsize 2000\n"));
    }

    @Test
    void missingSizeIsNotAPointer() {
        assertNull(LfsPointerParser.parse(
                "version https://git-lfs.github.com/spec/v1\n"
                        + "oid sha256:c37f0c49b82a3d813209c0260617e62684381e151fcc611aa5517f7e2ec55c6e\n"));
    }

    @Test
    void aNonHexOidIsRejected() {
        assertNull(LfsPointerParser.parse(
                "version https://git-lfs.github.com/spec/v1\n"
                        + "oid sha256:not-actually-hex\n"
                        + "size 2000\n"));
    }

    @Test
    void aNonNumericSizeIsRejected() {
        assertNull(LfsPointerParser.parse(
                "version https://git-lfs.github.com/spec/v1\n"
                        + "oid sha256:c37f0c49b82a3d813209c0260617e62684381e151fcc611aa5517f7e2ec55c6e\n"
                        + "size not-a-number\n"));
    }

    @Test
    void anUnknownExtensionLineIsPreservedNotRejected() {
        // The spec requires compliant tools to tolerate extension lines they do not
        // themselves understand, rather than treating one as reason to reject the
        // whole pointer.
        LfsPointer pointer = LfsPointerParser.parse(
                "version https://git-lfs.github.com/spec/v1\n"
                        + "oid sha256:c37f0c49b82a3d813209c0260617e62684381e151fcc611aa5517f7e2ec55c6e\n"
                        + "size 2000\n"
                        + "x-some-future-extension somevalue\n");
        assertNotNull(pointer);
        assertEquals(2000, pointer.size());
    }

    @Test
    void contentLargerThanAPlausiblePointerIsRejectedOutright() {
        StringBuilder huge = new StringBuilder(REAL_POINTER);
        huge.append("x ").append("a".repeat(2000)).append('\n');
        assertNull(LfsPointerParser.parse(huge.toString()));
    }

    // ------------------------------------------------------------------
    // Reconstructing a side from real diff hunks
    // ------------------------------------------------------------------

    /** Exactly the hunk `git diff` produced for a real LFS-tracked file's content change. */
    private static final String REAL_POINTER_DIFF =
            "diff --git a/asset.psd b/asset.psd\n"
                    + "index 022056b..dc755ad 100644\n"
                    + "--- a/asset.psd\n"
                    + "+++ b/asset.psd\n"
                    + "@@ -1,3 +1,3 @@\n"
                    + " version https://git-lfs.github.com/spec/v1\n"
                    + "-oid sha256:c37f0c49b82a3d813209c0260617e62684381e151fcc611aa5517f7e2ec55c6e\n"
                    + "-size 2000\n"
                    + "+oid sha256:98175799ea197b92416ed7d9693f27dc8169a575680e6c08effc9f1d38e05d8f\n"
                    + "+size 500\n";

    @Test
    void reconstructsBothSidesOfARealPointerDiff() {
        List<DiffParser.Hunk> hunks = DiffParser.parse(REAL_POINTER_DIFF);

        LfsPointer oldPointer = LfsPointerParser.oldSide(hunks);
        LfsPointer newPointer = LfsPointerParser.newSide(hunks);

        assertNotNull(oldPointer);
        assertEquals(2000, oldPointer.size());
        assertNotNull(newPointer);
        assertEquals(500, newPointer.size());
        assertNotEquals(oldPointer.oid(), newPointer.oid());
    }

    @Test
    void anAddedPointerFileHasOnlyANewSide() {
        String addedDiff = "diff --git a/asset.psd b/asset.psd\n"
                + "new file mode 100644\n"
                + "index 0000000..dc755ad\n"
                + "--- /dev/null\n"
                + "+++ b/asset.psd\n"
                + "@@ -0,0 +1,3 @@\n"
                + "+version https://git-lfs.github.com/spec/v1\n"
                + "+oid sha256:98175799ea197b92416ed7d9693f27dc8169a575680e6c08effc9f1d38e05d8f\n"
                + "+size 500\n";
        List<DiffParser.Hunk> hunks = DiffParser.parse(addedDiff);

        assertNull(LfsPointerParser.oldSide(hunks));
        assertNotNull(LfsPointerParser.newSide(hunks));
    }

    @Test
    void aDeletedPointerFileHasOnlyAnOldSide() {
        String deletedDiff = "diff --git a/asset.psd b/asset.psd\n"
                + "deleted file mode 100644\n"
                + "index 022056b..0000000\n"
                + "--- a/asset.psd\n"
                + "+++ /dev/null\n"
                + "@@ -1,3 +0,0 @@\n"
                + "-version https://git-lfs.github.com/spec/v1\n"
                + "-oid sha256:c37f0c49b82a3d813209c0260617e62684381e151fcc611aa5517f7e2ec55c6e\n"
                + "-size 2000\n";
        List<DiffParser.Hunk> hunks = DiffParser.parse(deletedDiff);

        assertNotNull(LfsPointerParser.oldSide(hunks));
        assertNull(LfsPointerParser.newSide(hunks));
    }

    @Test
    void ordinaryTextDiffHasNeitherSide() {
        String plainDiff = "diff --git a/a.txt b/a.txt\n"
                + "index 1234567..89abcde 100644\n"
                + "--- a/a.txt\n"
                + "+++ b/a.txt\n"
                + "@@ -1,2 +1,2 @@\n"
                + " context\n"
                + "-old\n"
                + "+new\n";
        List<DiffParser.Hunk> hunks = DiffParser.parse(plainDiff);

        assertNull(LfsPointerParser.oldSide(hunks));
        assertNull(LfsPointerParser.newSide(hunks));
    }

    @Test
    void noHunksAtAllMeansNeitherSide() {
        assertNull(LfsPointerParser.oldSide(List.of()));
        assertNull(LfsPointerParser.newSide(List.of()));
    }

    // ------------------------------------------------------------------
    // Messages
    // ------------------------------------------------------------------

    @Test
    void describeFileNamesTheSizeAndHowToFetchIt() {
        LfsPointer pointer = new LfsPointer("sha256:abc", 2048);
        String message = LfsPointerParser.describeFile(pointer);

        assertNotNull(message);
        assertTrue(message.contains("2.0 KB"), message);
        assertTrue(message.contains("git lfs pull"), message);
    }

    @Test
    void describeFileOfNonPointerIsNull() {
        assertNull(LfsPointerParser.describeFile(null));
    }

    @Test
    void describeDiffOfAModifiedPointerNamesBothSizes() {
        String message = LfsPointerParser.describeDiff(
                new LfsPointer("sha256:old", 2000), new LfsPointer("sha256:new", 500));

        assertNotNull(message);
        assertTrue(message.contains("changed"), message);
        assertTrue(message.contains("500 B"), message);
    }

    @Test
    void describeDiffOfAnAddedPointerSaysNewFile() {
        String message = LfsPointerParser.describeDiff(null, new LfsPointer("sha256:new", 500));

        assertNotNull(message);
        assertTrue(message.toLowerCase(java.util.Locale.ROOT).contains("new file"), message);
    }

    @Test
    void describeDiffOfADeletedPointerSaysRemoved() {
        String message = LfsPointerParser.describeDiff(new LfsPointer("sha256:old", 2000), null);

        assertNotNull(message);
        assertTrue(message.toLowerCase(java.util.Locale.ROOT).contains("removed"), message);
    }

    @Test
    void describeDiffOfNeitherSideIsNull() {
        assertNull(LfsPointerParser.describeDiff(null, null));
    }
}
