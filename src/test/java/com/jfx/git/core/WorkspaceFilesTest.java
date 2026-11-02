package com.jfx.git.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceFilesTest {

    @TempDir
    Path tempDir;

    private WorkspaceFiles files;

    @BeforeEach
    void setUp() {
        files = new WorkspaceFiles(tempDir.toFile());
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    @Test
    void createsAFileAtTheRoot() {
        assertTrue(files.createFile("readme.md").isOk());
        assertTrue(Files.exists(tempDir.resolve("readme.md")));
    }

    /** "foo/bar.txt" is a request to create the directory as well. */
    @Test
    void createsMissingParentDirectories() {
        assertTrue(files.createFile("src/main/java/App.java").isOk());
        assertTrue(Files.isRegularFile(tempDir.resolve("src/main/java/App.java")));
        assertTrue(Files.isDirectory(tempDir.resolve("src/main/java")));
    }

    @Test
    void refusesToOverwriteAnExistingFile() throws Exception {
        Files.writeString(tempDir.resolve("keep.txt"), "precious");

        var result = files.createFile("keep.txt");
        assertFalse(result.isOk());
        assertEquals("precious", Files.readString(tempDir.resolve("keep.txt")),
                "a create must never truncate what is already there");
    }

    @Test
    void refusesPathsThatEscapeTheRepository() {
        var result = files.createFile("../escaped.txt");
        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("outside the repository"));
        assertFalse(Files.exists(tempDir.getParent().resolve("escaped.txt")));
    }

    // ------------------------------------------------------------------
    // Links out of the work tree
    // ------------------------------------------------------------------

    /**
     * A directory inside the work tree that really is somewhere else.
     *
     * <p>A junction on Windows, a symlink elsewhere. Junctions are the interesting case:
     * the JDK does not report one as a link at all, so anything that asks
     * {@code Files.isSymbolicLink} sees an ordinary directory and walks straight through
     * it. Only resolving the path shows where it goes.
     *
     * @return whether this machine allowed the link to be made; creating symlinks on
     *         Windows needs a privilege a test run may not have
     */
    private boolean linkInto(Path linkInsideRepo, Path realTarget) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    linkInsideRepo.toString(), realTarget.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0 && Files.exists(linkInsideRepo);
        }
        try {
            Files.createSymbolicLink(linkInsideRepo, realTarget);
            return true;
        } catch (UnsupportedOperationException | java.io.IOException notPermitted) {
            return false;
        }
    }

    /**
     * The reproduced escape: {@code repo/link/victim.txt} passes a plain
     * {@code startsWith(root)} as text, and deleted a file outside the repository.
     */
    @Test
    void refusesToDeleteThroughALinkOutOfTheWorkTree() throws Exception {
        Path outside = Files.createDirectory(tempDir.getParent().resolve("outside-" + hashCode()));
        Path victim = outside.resolve("victim.txt");
        Files.writeString(victim, "not ours to delete");
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    linkInto(tempDir.resolve("link"), outside),
                    "this machine does not allow creating directory links");

            var result = files.delete("link/victim.txt");

            assertFalse(result.isOk(), "a path that leaves the work tree must be refused");
            assertTrue(Files.exists(victim), "the file outside the repository is still there");
        } finally {
            Files.deleteIfExists(victim);
            Files.deleteIfExists(outside);
        }
    }

    /** The same rule for writing: a create must not land outside either. */
    @Test
    void refusesToCreateThroughALinkOutOfTheWorkTree() throws Exception {
        Path outside = Files.createDirectory(tempDir.getParent().resolve("outside-w-" + hashCode()));
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    linkInto(tempDir.resolve("link"), outside),
                    "this machine does not allow creating directory links");

            var result = files.createFile("link/planted.txt");

            assertFalse(result.isOk());
            assertFalse(Files.exists(outside.resolve("planted.txt")),
                    "nothing should have been written outside the repository");
        } finally {
            Files.deleteIfExists(outside.resolve("planted.txt"));
            Files.deleteIfExists(outside);
        }
    }

    /** Browsing must not descend through one either, or the tree shows another repository. */
    @Test
    void doesNotListThroughALinkOutOfTheWorkTree() throws Exception {
        Path outside = Files.createDirectory(tempDir.getParent().resolve("outside-l-" + hashCode()));
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "elsewhere");
        try {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    linkInto(tempDir.resolve("link"), outside),
                    "this machine does not allow creating directory links");

            List<WorkspaceFiles.Entry> children = files.children("link");

            assertTrue(children.isEmpty(),
                    () -> "the browser followed a link out of the repository: " + children);
        } finally {
            Files.deleteIfExists(secret);
            Files.deleteIfExists(outside);
        }
    }

    /** {@code .git} is git's own storage, and the file browser has no business in it. */
    @Test
    void refusesToWriteInsideTheGitDirectory() throws Exception {
        Files.createDirectories(tempDir.resolve(".git"));

        var result = files.createFile(".git/hooks/pre-commit");

        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains(".git"), result.getMessage());
        assertFalse(Files.exists(tempDir.resolve(".git/hooks/pre-commit")));
    }

    @Test
    void refusesTheRepositoryRootItself() {
        assertFalse(files.createFile(".").isOk());
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    @Test
    void deletesAFile() throws Exception {
        Files.writeString(tempDir.resolve("gone.txt"), "x");
        assertTrue(files.delete("gone.txt").isOk());
        assertFalse(Files.exists(tempDir.resolve("gone.txt")));
    }

    @Test
    void deletesADirectoryAndItsContents() throws Exception {
        Files.createDirectories(tempDir.resolve("build/classes"));
        Files.writeString(tempDir.resolve("build/classes/A.class"), "x");

        assertTrue(files.delete("build").isOk());
        assertFalse(Files.exists(tempDir.resolve("build")));
    }

    @Test
    void deletingSomethingAbsentIsAnError() {
        var result = files.delete("never-existed.txt");
        assertFalse(result.isOk());
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    @Test
    void listsDirectoriesBeforeFiles() throws Exception {
        Files.writeString(tempDir.resolve("zebra.txt"), "x");
        Files.writeString(tempDir.resolve("apple.txt"), "x");
        Files.createDirectories(tempDir.resolve("src"));

        List<WorkspaceFiles.Entry> entries = files.children("");
        assertEquals("src", entries.get(0).name());
        assertTrue(entries.get(0).directory());
        assertEquals("apple.txt", entries.get(1).name());
        assertEquals("zebra.txt", entries.get(2).name());
    }

    @Test
    void hidesTheGitDirectory() throws Exception {
        Files.createDirectories(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve("visible.txt"), "x");

        List<WorkspaceFiles.Entry> entries = files.children("");
        assertEquals(1, entries.size());
        assertEquals("visible.txt", entries.get(0).name());
    }

    @Test
    void childPathsAreRepositoryRelative() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main"));
        Files.writeString(tempDir.resolve("src/main/App.java"), "x");

        var entries = files.children("src/main");
        assertEquals("src/main/App.java", entries.get(0).path());
        assertEquals("java", entries.get(0).extension());
        assertEquals("src/main", entries.get(0).parent());
    }

    // ------------------------------------------------------------------
    // .gitignore
    // ------------------------------------------------------------------

    @Test
    void ignoringAFileAnchorsItToTheRoot() {
        assertEquals("/build/output.log",
                WorkspaceFiles.ruleFor("build/output.log", WorkspaceFiles.IgnoreScope.FILE));
    }

    @Test
    void ignoringAnExtensionMatchesAnywhere() {
        assertEquals("*.log",
                WorkspaceFiles.ruleFor("build/output.log", WorkspaceFiles.IgnoreScope.EXTENSION));
    }

    @Test
    void ignoringAFolderTrailsASlash() {
        assertEquals("/build/",
                WorkspaceFiles.ruleFor("build/output.log", WorkspaceFiles.IgnoreScope.FOLDER));
    }

    @Test
    void thereIsNoExtensionRuleForAFileWithoutOne() {
        assertNull(WorkspaceFiles.ruleFor("Makefile", WorkspaceFiles.IgnoreScope.EXTENSION));
        assertNull(WorkspaceFiles.ruleFor(".gitignore", WorkspaceFiles.IgnoreScope.EXTENSION),
                "a leading dot is not an extension");
    }

    /** Ignoring the folder of a root-level file would ignore the whole repository. */
    @Test
    void thereIsNoFolderRuleForATopLevelFile() {
        assertNull(WorkspaceFiles.ruleFor("README.md", WorkspaceFiles.IgnoreScope.FOLDER));
    }

    @Test
    void writesTheRuleToTheRootGitignore() throws Exception {
        assertTrue(files.ignore("build/out.log", WorkspaceFiles.IgnoreScope.EXTENSION).isOk());

        String content = Files.readString(tempDir.resolve(".gitignore"));
        assertTrue(content.contains("*.log"), "was: " + content);
    }

    @Test
    void appendsRatherThanReplacingExistingRules() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), "target/\n");
        assertTrue(files.ignore("notes.tmp", WorkspaceFiles.IgnoreScope.FILE).isOk());

        List<String> lines = Files.readAllLines(tempDir.resolve(".gitignore"));
        assertTrue(lines.contains("target/"), "existing rules must survive");
        assertTrue(lines.contains("/notes.tmp"));
    }

    @Test
    void theSameRuleIsNeverAddedTwice() throws Exception {
        files.ignore("a.log", WorkspaceFiles.IgnoreScope.EXTENSION);
        files.ignore("b.log", WorkspaceFiles.IgnoreScope.EXTENSION);

        long occurrences = Files.readAllLines(tempDir.resolve(".gitignore")).stream()
                .filter(line -> line.trim().equals("*.log"))
                .count();
        assertEquals(1, occurrences, "an ignore menu must not turn .gitignore into a log");
    }

    @Test
    void aMissingExtensionIsReportedRatherThanWritten() {
        var result = files.ignore("Makefile", WorkspaceFiles.IgnoreScope.EXTENSION);
        assertFalse(result.isOk());
        assertFalse(Files.exists(tempDir.resolve(".gitignore")),
                "nothing to write means no file should appear");
    }
}
