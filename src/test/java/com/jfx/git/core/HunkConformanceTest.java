package com.jfx.git.core;

import com.jfx.git.testsupport.Engines;
import com.jfx.git.ui.DiffParser;
import com.jfx.git.ui.HunkSelection;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Hunk and line staging through the whole chain, on every engine - the findings of the
 * 2026-09-27 comparative review (S1-S5, S7, S11, S12, E1).
 *
 * <p>Kept beside {@link BackendConformanceTest} rather than in it, and run over its
 * {@code engines()}, for the same reason that class exists: each engine reaches the
 * index differently - JGit's {@code DirCacheEditor}, the CLI's {@code update-index} - and
 * the hybrid engines route hunk operations to JGit, so "works on the one I tried" says
 * nothing about the others.
 *
 * <p>Every patch here is built the way the viewer builds it: the engine's own diff
 * bytes, decoded with CRs kept, parsed, selected, and handed over with the charset and
 * preamble. Most of these defects lived in the seams between those steps. Assertions are
 * on the index blob's bytes, because every one of them staged the wrong bytes silently.
 */
@Tag("heavy")
class HunkConformanceTest {

    @TempDir
    Path root;

    private final List<GitBackend> opened = new ArrayList<>();
    private int fixtureCount;

    static {
        // Windows will not delete a memory-mapped pack file, and @TempDir must.
        org.eclipse.jgit.storage.file.WindowCacheConfig config =
                new org.eclipse.jgit.storage.file.WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }

    @AfterEach
    void tearDown() {
        for (GitBackend backend : opened) {
            try {
                backend.close();
            } catch (Exception ignored) {
                // A test that already failed should report its own reason, not this one.
            }
        }
        opened.clear();
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    static Stream<Arguments> engines() {
        return Engines.all();
    }

    // ------------------------------------------------------------------
    // Fixture and chain
    // ------------------------------------------------------------------

    /** A repository with one commit holding {@code path} as exactly {@code content}. */
    private GitBackend fixture(Supplier<GitBackend> factory, String path, byte[] content)
            throws Exception {
        File dir = root.resolve("repo" + (fixtureCount++)).toFile();
        assertTrue(dir.mkdirs());
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();
            Files.write(dir.toPath().resolve(path), content);
            git.add().addFilepattern(path).call();
            git.commit().setMessage("seed").call();
        }
        GitBackend backend = factory.get();
        assertTrue(backend.openRepository(dir));
        opened.add(backend);
        return backend;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void write(GitBackend backend, String path, byte[] content) throws Exception {
        Files.write(new File(backend.getWorkTree(), path).toPath(), content);
    }

    private static byte[] workingTree(GitBackend backend, String path) throws Exception {
        return Files.readAllBytes(new File(backend.getWorkTree(), path).toPath());
    }

    /** One diff as the viewer holds it. */
    private record Shown(List<DiffParser.Hunk> hunks, DiffParser.Summary summary,
                         Charset lineCharset, String text) {}

    private static Shown show(byte[] diffBytes) {
        Charset lineCharset = TextEncoding.GUESS.diffLineCharset(diffBytes);
        String text = TextEncoding.GUESS.decodeDiff(diffBytes, lineCharset, true);
        return new Shown(DiffParser.parse(text), DiffParser.summarize(text), lineCharset, text);
    }

    private static void unstageEveryHunk(GitBackend backend, String path) {
        Shown shown = show(backend.getStagedDiffBytes(path, false));
        assertFalse(shown.hunks().isEmpty(), () -> "no staged hunks for " + path + ":\n" + shown.text());
        for (DiffParser.Hunk hunk : shown.hunks()) {
            HunkSelection.Patch patch = HunkSelection.unstage(hunk);
            GitResult result = backend.applyToIndex(path, patch.toIndexEdit(TextEncoding.GUESS,
                    shown.lineCharset(), shown.summary(), false), "Unstaged hunk");
            assertTrue(result.isOk(), () -> "unstaging failed: " + result.getMessage() + "\n" + shown.text());
        }
    }

    private static GitResult discard(GitBackend backend, String path, int hunkIndex, Set<Integer> lines) {
        Shown shown = show(backend.getUnstagedDiffBytes(path, false));
        DiffParser.Hunk hunk = shown.hunks().get(hunkIndex);
        HunkSelection.Patch patch = lines == null
                ? HunkSelection.discard(hunk) : HunkSelection.discard(hunk, lines);
        return backend.revertHunk(path, patch.toEdit(TextEncoding.GUESS, shown.lineCharset()));
    }

    /** The index entry for {@code path}, read with JGit whatever engine wrote it. */
    private static DirCacheEntry indexEntry(GitBackend backend, String path) throws Exception {
        try (Repository repo = new FileRepositoryBuilder()
                .setWorkTree(backend.getWorkTree()).setMustExist(true).build()) {
            DirCache cache = repo.readDirCache();
            return cache.getEntry(path);
        }
    }

    private static byte[] indexBytes(GitBackend backend, String path) {
        return backend.getStagedBytes(path);
    }

    private static String visible(byte[] bytes) {
        return '"' + new String(bytes, StandardCharsets.ISO_8859_1)
                .replace("\r", "\\r").replace("\n", "\\n") + '"';
    }

    private static void assertIndexHolds(byte[] expected, GitBackend backend, String path) {
        byte[] actual = indexBytes(backend, path);
        assertArrayEquals(expected, actual,
                () -> "index holds " + visible(actual) + " but should hold " + visible(expected));
    }

    // ------------------------------------------------------------------
    // S1. Line endings are content
    // ------------------------------------------------------------------

    /**
     * An LF line added to a CRLF file is staged as LF. Writing it as CRLF matched
     * neither side, and the file showed a phantom change forever.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void anLfLineAddedToACrlfFileIsStagedAsLf(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory, "f.txt", utf8("a\r\nb\r\n"));
        write(backend, "f.txt", utf8("a\r\nnew\nb\r\n"));

        BackendConformanceTest.stageEveryHunkThroughTheUiChain(backend, "f.txt");

        assertIndexHolds(utf8("a\r\nnew\nb\r\n"), backend, "f.txt");
        assertTrue(backend.getUnstagedDiffBytes("f.txt", false).length == 0,
                "the index now matches the working tree - no phantom change");
    }

    /** A CRLF-to-LF change used to report "Nothing selected to stage". */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void anEolOnlyChangeCanBeStaged(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory, "f.txt", utf8("a\r\nb\r\n"));
        write(backend, "f.txt", utf8("a\nb\r\n"));

        BackendConformanceTest.stageEveryHunkThroughTheUiChain(backend, "f.txt");

        assertIndexHolds(utf8("a\nb\r\n"), backend, "f.txt");
    }

    /** And reverting one used to report success over identical bytes. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void anEolOnlyChangeCanBeReverted(String name, Supplier<GitBackend> factory) throws Exception {
        GitBackend backend = fixture(factory, "f.txt", utf8("a\r\nb\r\n"));
        write(backend, "f.txt", utf8("a\nb\r\n"));

        GitResult reverted = discard(backend, "f.txt", 0, null);

        assertTrue(reverted.isOk(), reverted::getMessage);
        assertArrayEquals(utf8("a\r\nb\r\n"), workingTree(backend, "f.txt"));
    }

    // ------------------------------------------------------------------
    // S2. One charset for both sides
    // ------------------------------------------------------------------

    /**
     * An ASCII index guesses as UTF-8; the diff adding a Windows-1252 {@code é} guesses as
     * 1252. The line must be staged as the byte the working tree has, {@code e9}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aLatin1LineStagedIntoAnAsciiFileKeepsItsBytes(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory, "f.txt", utf8("one\ntwo\n"));
        byte[] edited = {'o', 'n', 'e', '\n', 'c', 'a', 'f', (byte) 0xE9, '\n', 't', 'w', 'o', '\n'};
        write(backend, "f.txt", edited);

        BackendConformanceTest.stageEveryHunkThroughTheUiChain(backend, "f.txt");

        assertIndexHolds(edited, backend, "f.txt");
    }

    // ------------------------------------------------------------------
    // S3. Whole-file results
    // ------------------------------------------------------------------

    /** Every line removed stages the empty blob, e69de29 - not a lone newline, 8b13789. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void stagingAnEmptiedFileStagesTheEmptyBlob(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory, "f.txt", utf8("a\nb\n"));
        write(backend, "f.txt", new byte[0]);

        BackendConformanceTest.stageEveryHunkThroughTheUiChain(backend, "f.txt");

        DirCacheEntry entry = indexEntry(backend, "f.txt");
        assertNotNull(entry, "an emptied file is still a file");
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", entry.getObjectId().name());
    }

    /** All of a deletion staged: the path leaves the index, as `git rm --cached` would. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void stagingAllOfADeletionRemovesThePath(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory, "f.txt", utf8("a\nb\n"));
        Files.delete(new File(backend.getWorkTree(), "f.txt").toPath());

        BackendConformanceTest.stageEveryHunkThroughTheUiChain(backend, "f.txt");

        assertNull(indexEntry(backend, "f.txt"), "the deletion is staged, not an empty file");
    }

    /** All of a new file unstaged: the path leaves the index, not a blank line in its place. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void unstagingAllOfANewFileRemovesThePath(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory, "f.txt", utf8("a\n"));
        write(backend, "new.txt", utf8("one\ntwo\n"));
        assertTrue(backend.stageFile("new.txt").isOk());

        unstageEveryHunk(backend, "new.txt");

        assertNull(indexEntry(backend, "new.txt"));
        assertArrayEquals(utf8("one\ntwo\n"), workingTree(backend, "new.txt"),
                "unstaging never touches the working tree");
    }

    /** Reverting the whole of a file's content leaves it empty, not holding a newline. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void revertingAHunkThatFilledAnEmptyFileEmptiesIt(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory, "f.txt", new byte[0]);
        write(backend, "f.txt", utf8("one\ntwo\n"));

        GitResult reverted = discard(backend, "f.txt", 0, null);

        assertTrue(reverted.isOk(), reverted::getMessage);
        assertArrayEquals(new byte[0], workingTree(backend, "f.txt"));
    }

    // ------------------------------------------------------------------
    // S4. Conflicted paths
    // ------------------------------------------------------------------

    /**
     * No hunk of an unmerged path may be applied. Discard hunk on one used to prepend a
     * whole copy of "ours" to the working file.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void hunkOperationsRefuseAConflictedPath(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory, "f.txt", utf8("base\n"));
        File dir = backend.getWorkTree();
        try (Git git = Git.open(dir)) {
            git.checkout().setCreateBranch(true).setName("other").call();
            Files.writeString(dir.toPath().resolve("f.txt"), "theirs\n");
            git.add().addFilepattern("f.txt").call();
            git.commit().setMessage("theirs").call();
            git.checkout().setName("main").call();
            Files.writeString(dir.toPath().resolve("f.txt"), "ours\n");
            git.add().addFilepattern("f.txt").call();
            git.commit().setMessage("ours").call();
            git.merge().include(git.getRepository().resolve("other")).call();
        }
        org.eclipse.jgit.lib.RepositoryCache.clear();
        byte[] conflicted = workingTree(backend, "f.txt");

        GitResult revert = backend.revertHunk("f.txt",
                new HunkEdit(1, List.of("ours"), List.of("base")));
        GitResult stage = backend.applyToIndex("f.txt",
                new HunkEdit(1, List.of(), List.of("x")), "Staged hunk");

        assertFalse(revert.isOk(), "revert must refuse a conflicted path");
        assertFalse(stage.isOk(), "stage must refuse a conflicted path");
        assertTrue(revert.getMessage().contains("conflict"), revert.getMessage());
        assertArrayEquals(conflicted, workingTree(backend, "f.txt"), "the file is untouched");
        assertNotEquals(0, indexEntry(backend, "f.txt").getStage(), "and still unmerged");
    }

    // ------------------------------------------------------------------
    // S5. Intent-to-add
    // ------------------------------------------------------------------

    /**
     * After {@code git add -N}, a staged hunk used to keep the intent-to-add flag, so the
     * content never reached the commit.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void stagingAHunkOfAnIntentToAddFileStagesItForReal(String name, Supplier<GitBackend> factory)
            throws Exception {
        assumeTrue(NativeGitRunner.isGitInstalled(), "git add -N needs the git CLI");
        GitBackend backend = fixture(factory, "f.txt", utf8("a\n"));
        write(backend, "ita.txt", utf8("one\ntwo\n"));
        assertTrue(new NativeGitRunner(backend.getWorkTree()).run("add", "-N", "--", "ita.txt").isOk());
        assertTrue(indexEntry(backend, "ita.txt").isIntentToAdd(), "precondition");

        BackendConformanceTest.stageEveryHunkThroughTheUiChain(backend, "ita.txt");

        DirCacheEntry entry = indexEntry(backend, "ita.txt");
        assertFalse(entry.isIntentToAdd(), "real content is staged, not an intention");
        assertIndexHolds(utf8("one\ntwo\n"), backend, "ita.txt");
        assertTrue(backend.commit("add ita", false).isOk());
        var status = new NativeGitRunner(backend.getWorkTree()).run("status", "--porcelain", "--", "ita.txt");
        assertEquals("", status.trimmedStdout(), "the commit took the file");
    }

    // ------------------------------------------------------------------
    // S7 / S11 / S12
    // ------------------------------------------------------------------

    /** A zero-count range means "after line N"; placing at N-1 put insertions a line early. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aZeroContextInsertionLandsAfterItsLine(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory, "f.txt", utf8("a\nb\nc\n"));
        // What `git diff -U0` says for inserting X after b: @@ -2,0 +3 @@
        DiffParser.Hunk hunk = DiffParser.parse("@@ -2,0 +3 @@\n+X\n").get(0);

        GitResult staged = backend.applyToIndex("f.txt",
                HunkSelection.stage(hunk).toEdit(TextEncoding.GUESS), "Staged hunk");

        assertTrue(staged.isOk(), staged::getMessage);
        assertIndexHolds(utf8("a\nb\nX\nc\n"), backend, "f.txt");
    }

    /**
     * A staged rename diffed by its new path alone looked like a whole new file, and
     * "Unstage hunk" emptied it. Paired with its old path it shows - and unstages - only
     * the edit.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aStagedRenameDiffsAndUnstagesOnlyItsEdit(String name, Supplier<GitBackend> factory)
            throws Exception {
        String body = "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\n";
        GitBackend backend = fixture(factory, "old.txt", utf8(body));
        File dir = backend.getWorkTree();
        Files.delete(dir.toPath().resolve("old.txt"));
        String edited = body.replace("four", "FOUR");
        write(backend, "new.txt", utf8(edited));
        assertTrue(backend.stageFile("old.txt").isOk());
        assertTrue(backend.stageFile("new.txt").isOk());

        Shown shown = show(backend.getStagedDiffBytes("new.txt", false));
        assertEquals(DiffParser.Change.RENAMED, shown.summary().change(), shown.text());
        assertEquals(1, shown.hunks().size());
        assertEquals(1, shown.hunks().get(0).lines().stream()
                .filter(DiffParser.Line::isAdd).count(), "only the edited line is an addition");

        unstageEveryHunk(backend, "new.txt");

        assertIndexHolds(utf8(body), backend, "new.txt");
    }

    /** A new executable staged a hunk at a time keeps the mode its diff showed. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void aNewPathTakesTheModeFromThePreamble(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory, "f.txt", utf8("a\n"));
        write(backend, "run.sh", utf8("#!/bin/sh\n"));
        HunkEdit edit = new HunkEdit(0, List.of(), List.of("#!/bin/sh"))
                .withWholeFile("100755", false);

        GitResult staged = backend.applyToIndex("run.sh", edit, "Staged hunk");

        assertTrue(staged.isOk(), staged::getMessage);
        assertEquals(org.eclipse.jgit.lib.FileMode.EXECUTABLE_FILE,
                indexEntry(backend, "run.sh").getFileMode());
        assertIndexHolds(utf8("#!/bin/sh\n"), backend, "run.sh");
    }

    // ------------------------------------------------------------------
    // Discard lines
    // ------------------------------------------------------------------

    /** Discarding one selected line reverts it and nothing else. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void discardingSelectedLinesKeepsTheRest(String name, Supplier<GitBackend> factory)
            throws Exception {
        GitBackend backend = fixture(factory, "f.txt", utf8("a\nb\nc\n"));
        write(backend, "f.txt", utf8("A\nb\nc\nd\n"));
        Shown shown = show(backend.getUnstagedDiffBytes("f.txt", false));
        List<DiffParser.Line> lines = shown.hunks().get(0).lines();
        int added = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).isAdd() && lines.get(i).text().equals("d")) added = i;
        }
        assertTrue(added >= 0, shown.text());

        GitResult discarded = discard(backend, "f.txt", 0, Set.of(added));

        assertTrue(discarded.isOk(), discarded::getMessage);
        assertArrayEquals(utf8("A\nb\nc\n"), workingTree(backend, "f.txt"),
                "the unselected edit to line 1 survives; only the added line goes");
    }

    // ------------------------------------------------------------------
    // E1. Clean filters on single-file staging
    // ------------------------------------------------------------------

    /**
     * Staging one file runs the repository's clean filter. libgit2 registers no filter
     * drivers, so its in-process add stored LFS files raw and git-crypt files as
     * plaintext; it now stages through the CLI whenever git is there.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    void stagingOneFileRunsTheCleanFilter(String name, Supplier<GitBackend> factory)
            throws Exception {
        assumeTrue(!name.equals("JGit"), "JGit's own filter support is not what this pins");
        GitBackend backend = fixture(factory, "f.txt", utf8("a\n"));
        File dir = backend.getWorkTree();
        NativeGitRunner git = new NativeGitRunner(dir);
        assertTrue(git.run("config", "filter.upper.clean", "tr a-z A-Z").isOk());
        Files.writeString(dir.toPath().resolve(".gitattributes"), "*.up filter=upper\n");
        write(backend, "x.up", utf8("secret\n"));

        assertTrue(backend.stageFile("x.up").isOk());

        assertIndexHolds(utf8("SECRET\n"), backend, "x.up");
    }
}
