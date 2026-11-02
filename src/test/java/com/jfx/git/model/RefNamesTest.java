package com.jfx.git.model;

import com.jfx.git.core.NativeGitRunner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The naming rules: which remote a remote-tracking branch is on, what its branch part is,
 * whether it is a remote's symbolic {@code HEAD}, and what git accepts as a branch name.
 */
class RefNamesTest {

    // ------------------------------------------------------------------
    // Remote of a remote-tracking branch
    // ------------------------------------------------------------------

    /** The longest configured remote the name starts with; the first segment only when none does. */
    @ParameterizedTest(name = "{0} with remotes [{1}] is {3} on {2}")
    @CsvSource(delimiter = '|', value = {
            "origin/main               | origin               | origin           | main",
            "origin/feature/x          | origin               | origin           | feature/x",
            "team/core/topic           | team/core            | team/core        | topic",
            "team/core/topic           | team;team/core       | team/core        | topic",
            "team/core/topic           | team                 | team             | core/topic",
            "team/core/topic           | ''                   | team             | core/topic",
            "origin/mirror/main        | origin;origin/mirror | origin/mirror    | main",
            "origin/mirrored/main      | origin;origin/mirror | origin           | mirrored/main",
            "gone/feature              | origin               | gone             | feature",
            "refs/remotes/team/core/a  | team/core            | team/core        | a",
            "origin                    | origin               | origin           | ''",
    })
    void aRemoteBranchIsOnTheLongestConfiguredRemote(String name, String remotes, String remote, String branch) {
        List<String> configured = remotes.isEmpty() ? List.of() : List.of(remotes.split(";"));

        assertEquals(remote, RefNames.remoteOf(name, configured));
        assertEquals(branch, RefNames.branchOf(name, configured));
    }

    @Test
    void noRemotesKnownReadsTheFirstSegment() {
        assertEquals("origin", RefNames.remoteOf("origin/a/b", null));
        assertEquals("a/b", RefNames.branchOf("origin/a/b", null));
        assertNull(RefNames.remoteOf(null, List.of("origin")));
    }

    /** Only a remote's own HEAD is symbolic; a branch that merely ends in /HEAD is a branch. */
    @Test
    void aRemotesSymbolicHeadIsTheOneWhoseBranchPartIsHead() {
        assertTrue(RefNames.isSymbolicHead("origin/HEAD", List.of("origin")));
        assertTrue(RefNames.isSymbolicHead("refs/remotes/origin/HEAD", List.of()));
        assertTrue(RefNames.isSymbolicHead("team/core/HEAD", List.of("team/core")));
        assertFalse(RefNames.isSymbolicHead("origin/feature/HEAD", List.of("origin")));
        assertFalse(RefNames.isSymbolicHead("origin/main", List.of("origin")));
        assertFalse(RefNames.isSymbolicHead("team/core/HEAD", List.of("team")), "core/HEAD on team");
    }

    // ------------------------------------------------------------------
    // Full and short names
    // ------------------------------------------------------------------

    @Test
    void fullAndShortNamesGoBothWays() {
        assertEquals("main", RefNames.shortName("refs/heads/main"));
        assertEquals("origin/main", RefNames.shortName("refs/remotes/origin/main"));
        assertEquals("v1.0", RefNames.shortName("refs/tags/v1.0"));
        assertEquals("HEAD", RefNames.shortName("HEAD"));
        assertNull(RefNames.shortName(null));
        assertEquals("refs/heads/feature/x", RefNames.localFull("feature/x"));
        assertEquals("refs/remotes/origin/main", RefNames.remoteFull("origin/main"));
        assertEquals("refs/tags/v1.0", RefNames.tagFull("v1.0"));
        assertEquals("team/core/topic", RefNames.remoteBranch("team/core", "topic"));
        assertEquals(RefNames.shortName("refs/heads/main"), RefInfo.shortNameOf("refs/heads/main"));
    }

    // ------------------------------------------------------------------
    // Branch names: git check-ref-format --branch
    // ------------------------------------------------------------------

    /** Names git accepts, and names it refuses - each refusal for one rule. */
    static Stream<String[]> branchNames() {
        return Stream.of(
                // name, refused?
                new String[]{"main", "ok"},
                new String[]{"feature/search", "ok"},
                new String[]{"fix-123_a.b", "ok"},
                new String[]{"a/b/c", "ok"},
                new String[]{"release/v1.0", "ok"},
                new String[]{"café", "ok"},
                new String[]{"feature/HEAD", "ok"},
                new String[]{"x@y", "ok"},
                new String[]{"a.lockb", "ok"},
                new String[]{"", "refused"},
                new String[]{"-starts-with-dash", "refused"},
                new String[]{"HEAD", "refused"},
                new String[]{"@", "refused"},
                new String[]{"has space", "refused"},
                new String[]{"tab\there", "refused"},
                new String[]{"bell\u0007", "refused"},
                new String[]{"del\u007f", "refused"},
                new String[]{"tilde~1", "refused"},
                new String[]{"caret^", "refused"},
                new String[]{"colon:", "refused"},
                new String[]{"question?", "refused"},
                new String[]{"star*", "refused"},
                new String[]{"open[bracket", "refused"},
                new String[]{"back\\slash", "refused"},
                new String[]{"a..b", "refused"},
                new String[]{"at@{brace", "refused"},
                new String[]{"/leading", "refused"},
                new String[]{"trailing/", "refused"},
                new String[]{"double//slash", "refused"},
                new String[]{".hidden", "refused"},
                new String[]{"a/.hidden", "refused"},
                new String[]{"x.lock", "refused"},
                new String[]{"a.lock/b", "refused"},
                new String[]{"ends.", "refused"});
    }

    @ParameterizedTest(name = "\"{0}\" is {1}")
    @MethodSource("branchNames")
    void branchNamesFollowGitsRules(String name, String verdict) {
        String problem = RefNames.branchNameProblem(name);
        if (verdict.equals("ok")) {
            assertNull(problem, name);
        } else {
            assertNotNull(problem, name);
            assertTrue(problem.endsWith("."), "a sentence: " + problem);
        }
    }

    /**
     * The table agrees with git itself, where git is installed: {@code check-ref-format
     * --branch} refuses exactly what the rules refuse.
     */
    @ParameterizedTest(name = "git agrees about \"{0}\"")
    @MethodSource("branchNames")
    void gitItselfAgrees(String name, String verdict) throws Exception {
        if (!NativeGitRunner.isGitInstalled() || name.isEmpty() || name.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            // An empty or control-character argument cannot be passed through a command
            // line reliably; the table above still covers those.
            return;
        }
        if (name.equals("@")) {
            // --branch reads a lone "@" as HEAD and answers with HEAD's branch, so git never
            // judges it as a name; refused here all the same, since every git command that
            // took it would read it as HEAD.
            return;
        }
        Path dir = Files.createTempDirectory("refnames");
        try {
            Process git = new ProcessBuilder("git", "check-ref-format", "--branch", name)
                    .directory(dir.toFile()).redirectErrorStream(true).start();
            git.getInputStream().readAllBytes();
            assertTrue(git.waitFor(30, TimeUnit.SECONDS));
            boolean accepted = git.exitValue() == 0;
            assertEquals(verdict.equals("ok"), accepted,
                    "git check-ref-format --branch " + name + " says " + (accepted ? "ok" : "refused"));
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void eachRuleSaysWhichItIs() {
        assertEquals("A branch name cannot start with \"-\".", RefNames.branchNameProblem("-x"));
        assertEquals("A branch name cannot contain spaces.", RefNames.branchNameProblem("a b"));
        assertEquals("A branch name cannot contain \"..\".", RefNames.branchNameProblem("a..b"));
        assertEquals("A branch name cannot contain \"~\".", RefNames.branchNameProblem("a~b"));
        assertEquals("No part of a branch name can end with \".lock\".", RefNames.branchNameProblem("a.lock"));
        assertEquals("A branch needs a name.", RefNames.branchNameProblem(""));
    }

    /**
     * A branch is a file under {@code .git/refs/heads}, so on Windows its name has to be a
     * path Windows can store: git checks only its own rules, and a name Windows cannot
     * store passed the prompt and failed afterwards with "cannot lock ref". JGit's
     * {@code SystemReader.checkPath} refused these, back when the prompts asked JGit.
     */
    static Stream<String> namesWindowsCannotStore() {
        return Stream.of("fix|crash", "a<b", "a>b", "say\"hi\"",
                "con", "CON", "aux", "nul", "prn", "Prn", "com1", "COM9", "lpt1", "lpt9",
                "feature/aux", "aux/feature", "nul.txt", "feature/con.tar.gz", "lpt3.x",
                "dots./part");
    }

    @ParameterizedTest(name = "Windows cannot store \"{0}\"")
    @MethodSource("namesWindowsCannotStore")
    void windowsRefusesWhatItCannotStore(String name) {
        assertNull(RefNames.branchNameProblem(name, false), "git itself takes " + name);
        String problem = RefNames.branchNameProblem(name, true);
        assertNotNull(problem, name);
        assertTrue(problem.endsWith("."), "a sentence: " + problem);
        assertTrue(problem.contains("Windows"), "says why: " + problem);
    }

    @Test
    void windowsTakesNamesThatOnlyLookLikeDevices() {
        for (String name : List.of("console", "auxiliary", "nully", "com0", "com10", "lpt", "comX",
                "feature/con-fix", "connect/aux1", "main", "feature/search", "release/v1.0", "café")) {
            assertNull(RefNames.branchNameProblem(name, true), name);
        }
    }

    @Test
    void windowsRulesComeAfterGitsAndSayWhichItIs() {
        assertEquals("A branch name cannot contain spaces.", RefNames.branchNameProblem("a b|c", true),
                "git's own rules first");
        assertEquals("A branch name cannot contain \"|\" on Windows.", RefNames.branchNameProblem("fix|crash", true));
        assertEquals("No part of a branch name can end with \".\" on Windows.",
                RefNames.branchNameProblem("dots./part", true));
        assertEquals("No part of a branch name can be \"aux\" on Windows: the name AUX is kept for a device.",
                RefNames.branchNameProblem("feature/aux", true));
        assertEquals("No part of a branch name can be \"nul.txt\" on Windows: the name NUL is kept for a device.",
                RefNames.branchNameProblem("nul.txt", true));
    }

    /** The prompts ask the rules for the machine they run on: the app runs on Windows. */
    @Test
    void theRulesAreTheMachinesOwn() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        assertEquals(RefNames.branchNameProblem("feature/aux", windows), RefNames.branchNameProblem("feature/aux"));
        assertEquals(RefNames.branchNameProblem("fix|crash", windows),
                RefNames.newBranchNameProblem("fix|crash", List.of()));
    }

    @Test
    void aNewBranchMustNotBeTaken() {
        assertEquals("A branch named main already exists.", RefNames.newBranchNameProblem("main", List.of("main")));
        assertNull(RefNames.newBranchNameProblem("feature", List.of("main")));
        assertNull(RefNames.newBranchNameProblem("main", null), "names not known: only the name is checked");
        assertEquals("A branch name cannot contain spaces.",
                RefNames.newBranchNameProblem("a b", List.of("a b")), "git's rules first");
    }

    // ------------------------------------------------------------------
    // What HEAD is on
    // ------------------------------------------------------------------

    @Test
    void theHeadLabelNamesTheBranchOrTheCommit() {
        assertEquals("main", RefNames.headLabel("main", "0123456789abcdef"));
        assertEquals("Detached at 0123456", RefNames.headLabel("", "0123456789abcdef"));
        assertEquals("Detached at 0123456", RefNames.headLabel(null, "0123456789abcdef"));
        assertEquals("main", RefNames.headLabel("main", null), "an unborn branch");
        assertEquals("", RefNames.headLabel("", null));
    }

    // ------------------------------------------------------------------
    // The snapshot's own reading of them
    // ------------------------------------------------------------------

    @Test
    void theSnapshotReadsItsRefsByTheseRules() {
        RefSnapshot refs = new RefSnapshot("main", "abc", List.of(
                new RefInfo("refs/heads/main", "main", "abc")),
                List.of(new RefInfo("refs/remotes/team/core/main", "team/core/main", "abc")),
                List.of(new RefInfo("refs/tags/v1", "v1", "abc")),
                java.util.Map.of("refs/heads/main", "refs/remotes/team/core/main"),
                List.of(), List.of("team/core"));

        assertEquals("team/core", refs.remoteOf("team/core/main"));
        assertEquals("main", refs.branchOf("team/core/main"));
        assertEquals("refs/heads/main", refs.currentBranchRef());
        assertFalse(refs.detached());
        assertEquals(java.util.Map.of("main", "team/core/main"), refs.upstreamShortNames());
        assertEquals(List.of("main", "team/core/main", "v1"), refs.refs().stream().map(RefInfo::shortName).toList());

        RefSnapshot detached = new RefSnapshot(null, "0123456789", List.of(), List.of(), List.of(), null, null, null);
        assertEquals("", detached.currentBranch(), "never null");
        assertTrue(detached.detached());
        assertNull(detached.currentBranchRef());
        assertEquals("Detached at 0123456", detached.headLabel());
    }

    /** Pure Java: anything can ask, and these tests need no toolkit. */
    @Test
    void theRulesImportNothingFromJavaFxOrTheEngines() throws Exception {
        for (String name : List.of("RefNames", "RefSnapshot")) {
            File source = new File("src/main/java/com/jfx/git/model/" + name + ".java");
            String text = Files.readString(source.toPath(), StandardCharsets.UTF_8);
            assertFalse(text.contains("javafx."), name + " reaches into JavaFX");
            assertFalse(text.contains("org.eclipse.jgit"), name + " reaches into JGit");
            assertFalse(text.contains("com.jfx.git.core"), name + " reaches into the engines");
        }
    }
}
