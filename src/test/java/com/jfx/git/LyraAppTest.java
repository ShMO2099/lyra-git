package com.jfx.git;

import com.jfx.git.core.Refusal;
import com.jfx.git.core.RefusalWords;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LyraApp#repositoryFromArgs(List)} - the pure part of the commercial
 * clients' {@code <app> /path/to/repo} equivalent, factored out so it can be tested
 * without driving the real JavaFX {@code Application} lifecycle (nothing else in
 * this codebase's test suite does that for {@code LyraApp} itself; every existing
 * robot test builds a {@code RepoTab} directly instead).
 */
class LyraAppTest {

    @TempDir
    Path tempDir;

    @Test
    void aRepositoryPathArgumentIsRecognised() throws Exception {
        Git.init().setDirectory(tempDir.toFile()).call().close();

        File found = LyraApp.repositoryFromArgs(List.of(tempDir.toString()));

        assertEquals(tempDir.toFile(), found);
    }

    @Test
    void aNonRepositoryDirectoryArgumentIsIgnored() {
        File found = LyraApp.repositoryFromArgs(List.of(tempDir.toString()));

        assertNull(found, "no .git anywhere up the tree, so this is not a repository");
    }

    @Test
    void aPathInsideARepositoryResolvesToTheRepositoryRootNotTheNestedPath() throws Exception {
        Git.init().setDirectory(tempDir.toFile()).call().close();
        File nested = new File(tempDir.toFile(), "src/main");
        assertEquals(true, nested.mkdirs());

        File found = LyraApp.repositoryFromArgs(List.of(nested.getAbsolutePath()));

        assertEquals(tempDir.toFile(), found,
                "opening a repo from a path nested inside it should open the real root, the way `git status` "
                        + "run from a subdirectory reports on the whole repository, not just that subdirectory");
    }

    @Test
    void aNonExistentPathIsIgnored() {
        File found = LyraApp.repositoryFromArgs(List.of(tempDir.resolve("does-not-exist").toString()));

        assertNull(found);
    }

    @Test
    void aFlagLikeArgumentIsSkippedRatherThanTreatedAsAPath() throws Exception {
        Git.init().setDirectory(tempDir.toFile()).call().close();

        File found = LyraApp.repositoryFromArgs(List.of("--debug", tempDir.toString()));

        assertEquals(tempDir.toFile(), found, "the flag is skipped, the real path argument after it is still found");
    }

    @Test
    void noArgumentsMeansNoCommandLineRepository() {
        assertNull(LyraApp.repositoryFromArgs(List.of()));
    }

    @Test
    void blankArgumentsAreIgnoredRatherThanResolvedAsTheCurrentDirectory() {
        assertNull(LyraApp.repositoryFromArgs(List.of("", "   ")),
                "new File(\"\") resolves to the cwd, which is not what a blank argument should mean");
    }

    // ------------------------------------------------------------------
    // What an opened folder is: the question behind the "initialise it?" offer
    // ------------------------------------------------------------------

    @Test
    void aPlainFolderHasNoRepositoryRoot() {
        assertNull(LyraApp.repositoryRootOf(tempDir.toFile()));
        assertFalse(LyraApp.looksBare(tempDir.toFile()));
    }

    @Test
    void aFolderInsideARepositoryResolvesToItsRoot() throws Exception {
        Git.init().setDirectory(tempDir.toFile()).call().close();
        File nested = new File(tempDir.toFile(), "src/main");
        assertTrue(nested.mkdirs());

        assertEquals(tempDir.toFile(), LyraApp.repositoryRootOf(nested),
                "a subfolder opens the repository it is in; it is never offered an init of its own");
    }

    @Test
    void aBareRepositoryIsRecognised() throws Exception {
        File bare = tempDir.toFile();
        assertTrue(new File(bare, "HEAD").createNewFile());
        assertTrue(new File(bare, "objects").mkdir());
        assertTrue(new File(bare, "refs").mkdir());

        assertNull(LyraApp.repositoryRootOf(bare), "no .git entry: the folder is the git directory");
        assertTrue(LyraApp.looksBare(bare), "so it must be told apart from a folder with nothing in it");
    }

    @Test
    void aFolderMissingAnyBareMarkerIsNot() throws Exception {
        File dir = tempDir.toFile();
        assertTrue(new File(dir, "HEAD").createNewFile());
        assertFalse(LyraApp.looksBare(dir), "HEAD alone");
        assertTrue(new File(dir, "objects").mkdir());
        assertFalse(LyraApp.looksBare(dir), "HEAD and objects/ but no refs/");
        assertTrue(new File(dir, "refs").createNewFile());
        assertFalse(LyraApp.looksBare(dir), "refs must be a directory, not a file");
    }

    /** What the clone dialogs call the repository: "Cloning demo", "Could not clone demo". */
    @Test
    void aCloneIsNamedAfterTheLastPartOfItsUrl() {
        assertEquals("demo", LyraApp.repositoryName("https://github.com/shop/demo.git"));
        assertEquals("demo", LyraApp.repositoryName("https://github.com/shop/demo/"));
        assertEquals("demo", LyraApp.repositoryName("git@github.com:demo.git"));
        assertEquals("demo", LyraApp.repositoryName("C:\\repos\\demo"));
        assertEquals("file:///", LyraApp.repositoryName("file:///"), "nothing to name it by: the URL itself");
    }

    /** Said under the field as it is typed; a missing URL or folder is said on Clone (see ManualQaCloneValidationTest). */
    @Test
    void theCloneFormSaysWhatIsWrongWithEachField() {
        assertEquals("Clone depth must be a whole number.", LyraApp.cloneDepthProblem("1.5"));
        assertEquals("Clone depth cannot be negative.", LyraApp.cloneDepthProblem("-2"));
        assertNull(LyraApp.cloneDepthProblem(""), "blank depth is the full history");
        assertNull(LyraApp.cloneDepthProblem(" 3 "));

        assertNull(LyraApp.cloneBranchProblem(""), "blank is the remote's default branch");
        assertNull(LyraApp.cloneBranchProblem("feature/search"));
        assertTrue(LyraApp.cloneBranchProblem("two words").startsWith("Not a branch name git accepts"));
    }

    /** A folder can take a clone when it is new or empty; one with files in it, or a file, cannot. */
    @Test
    void theCloneFolderMustBeNewOrEmpty() throws Exception {
        File fresh = tempDir.resolve("demo").toFile();
        assertNull(LyraApp.cloneTargetProblem(fresh), "a folder that is not there yet is made");
        assertEquals("Creates the folder demo.", LyraApp.cloneTargetHint(fresh, fresh.getPath()));

        assertTrue(fresh.mkdir());
        assertNull(LyraApp.cloneTargetProblem(fresh), "an empty folder takes the clone");
        assertEquals("Clones into the empty folder demo.", LyraApp.cloneTargetHint(fresh, fresh.getPath()));

        assertTrue(new File(fresh, "README.md").createNewFile());
        assertEquals("demo already exists and has files in it. Choose a new folder, or an empty one.",
                LyraApp.cloneTargetProblem(fresh));
        assertEquals("README.md is a file. Choose a folder.",
                LyraApp.cloneTargetProblem(new File(fresh, "README.md")));
        assertNull(LyraApp.cloneTargetProblem(null), "nothing typed is said on Clone, not as typed");
    }

    /** "demo" typed alone is a folder beside where clones go, and the hint says where that is. */
    @Test
    void aBareFolderNameIsBesideTheDefaultParent() {
        File parent = tempDir.toFile();
        File into = LyraApp.cloneTarget("demo", parent);
        assertEquals(new File(parent, "demo").getAbsoluteFile(), into);
        assertEquals("Creates the folder demo in " + parent.getAbsolutePath() + ".",
                LyraApp.cloneTargetHint(into, "demo"));
        assertNull(LyraApp.cloneTarget("  ", parent));
    }

    /** The folder that follows the URL: its name once there is one, nothing while "https:" is still being typed. */
    @Test
    void theCloneFolderIsNamedFromTheUrl() {
        assertEquals("demo", LyraApp.cloneFolderName("https://github.com/shop/demo.git"));
        assertEquals("demo", LyraApp.cloneFolderName("git@github.com:shop/demo"));
        assertEquals("source", LyraApp.cloneFolderName("file:///synthetic/source.git"));
        assertEquals("", LyraApp.cloneFolderName("https"));
        assertEquals("", LyraApp.cloneFolderName("https://"));
        assertEquals("", LyraApp.cloneFolderName(""));
    }

    /** A failure goes on the field it is about: a taken folder, a missing repository, an unknown branch. */
    @Test
    void aCloneFailureIsPutOnTheFieldItIsAbout() {
        assertEquals(Refusal.Field.TARGET, RefusalWords.cloneField(
                "Clone failed: C:\\repos\\demo already exists and is not an empty folder"));
        assertEquals(Refusal.Field.URL, RefusalWords.cloneField(
                "Clone failed: Cloning into 'demo'...\nfatal: repository 'https://github.com/shop/demo.git/' not found"));
        assertEquals(Refusal.Field.URL, RefusalWords.cloneField(
                "Clone failed: fatal: unable to access 'https://githb.com/x/y.git/': Could not resolve host: githb.com"));
        assertEquals(Refusal.Field.BRANCH, RefusalWords.cloneField(
                "Clone failed: Cloning into 'demo'...\nfatal: Remote branch nope not found in upstream origin"));
        assertNull(RefusalWords.cloneField(
                "Clone failed: fatal: Authentication failed for 'https://github.com/shop/demo.git/'"));

        assertEquals("Remote branch nope not found in upstream origin", LyraApp.failureSentence(
                "Clone failed: Cloning into 'demo'...\nfatal: Remote branch nope not found in upstream origin",
                "Clone failed:"));
        // git closes every transport failure with the same line; the one before it says why.
        assertEquals("'C:/repos/demo' does not appear to be a git repository", LyraApp.failureSentence(
                "Clone failed: Cloning into 'demo'...\nfatal: 'C:/repos/demo' does not appear to be a git repository\n"
                        + "fatal: Could not read from remote repository.\n\nPlease make sure you have the correct"
                        + " access rights\nand the repository exists.", "Clone failed:"));
        assertEquals("Could not read from remote repository.",
                LyraApp.failureSentence("fatal: Could not read from remote repository.", null),
                "and when it is the only line, it is what there is to say");
    }
}
