package com.jfx.git.core;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pre-commit checks: what counts as a secret, what counts as large, and what is left
 * alone.
 *
 * <p>The token-shaped strings here are built from parts, so this file does not itself
 * look like a leaked secret to a scanner run over the repository.
 */
class CommitChecksTest {

    @TempDir
    Path tempDir;

    private static final String GITHUB = "gh" + "p_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8";
    private static final String AWS = "AK" + "IA" + "ABCDEFGHIJKLMNOP";
    private static final String GITLAB = "gl" + "pat-" + "abcdefghij0123456789";
    private static final String SLACK = "xo" + "xb-" + "1234567890-abcdefghij";
    private static final String GOOGLE = "AI" + "za" + "SyA1234567890abcdefghijklmnopqrstuv";
    private static final String FINE_GRAINED = "github" + "_pat_" + "11ABCDEFG0123456789_abcdefghijklmnop";

    private static final CommitChecks.Options ALL =
            new CommitChecks.Options(true, true, 50L * 1024 * 1024);

    /** A staged set in memory: path to size, path to diff, and LFS's patterns. */
    private static final class FakeIndex implements CommitChecks.Source {
        final Map<String, Long> sizes = new LinkedHashMap<>();
        final Map<String, String> diffs = new LinkedHashMap<>();
        List<String> lfs = List.of();
        int diffsRead;

        FakeIndex add(String path, long size, String... addedLines) {
            sizes.put(path, size);
            StringBuilder diff = new StringBuilder("diff --git a/" + path + " b/" + path + "\n"
                    + "--- a/" + path + "\n+++ b/" + path + "\n@@ -1,0 +1," + addedLines.length + " @@\n");
            for (String line : addedLines) diff.append('+').append(line).append('\n');
            diffs.put(path, diff.toString());
            return this;
        }

        FakeIndex diff(String path, long size, String diff) {
            sizes.put(path, size);
            diffs.put(path, diff);
            return this;
        }

        @Override public List<String> stagedFiles() { return List.copyOf(sizes.keySet()); }
        @Override public long size(String path) { return sizes.getOrDefault(path, -1L); }
        @Override public byte[] stagedDiff(String path) {
            diffsRead++;
            return diffs.getOrDefault(path, "").getBytes(StandardCharsets.UTF_8);
        }
        @Override public List<String> lfsPatterns() { return lfs; }
    }

    // ------------------------------------------------------------------
    // Secrets
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"github", "aws", "gitlab", "slack", "google", "fine-grained", "private-key"})
    void eachKindOfSecretIsFound(String kind) {
        String line = switch (kind) {
            case "github" -> "token = \"" + GITHUB + "\"";
            case "aws" -> "aws_access_key_id=" + AWS;
            case "gitlab" -> "GITLAB_TOKEN: " + GITLAB;
            case "slack" -> "slack: " + SLACK;
            case "google" -> "key=" + GOOGLE;
            case "fine-grained" -> "export GH_TOKEN=" + FINE_GRAINED;
            default -> "-----BEGIN " + "RSA PRIVATE KEY-----";
        };
        assertNotNull(CommitChecks.secretIn(line), kind + " should be recognised in: " + line);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "String token = System.getenv(\"GITHUB_TOKEN\");",
            "ghp_short",
            "the AKIA prefix is how AWS keys start",
            "-----BEGIN PUBLIC KEY-----",
            "glpat-",
            "url = https://github.com/owner/repo",
    })
    void ordinaryLinesAreNotSecrets(String line) {
        assertNull(CommitChecks.secretIn(line), "not a secret: " + line);
    }

    @Test
    void onlyAddedLinesAreLookedAt() {
        String diff = "diff --git a/config.yml b/config.yml\n--- a/config.yml\n+++ b/config.yml\n"
                + "@@ -1,3 +1,3 @@\n"
                + " name: app\n"
                + "-token: " + GITHUB + "\n"
                + "+token: ${GITHUB_TOKEN}\n"
                + " other: x\n";
        var findings = CommitChecks.run(new FakeIndex().diff("config.yml", 100, diff), ALL);
        assertTrue(findings.isEmpty(), "removing a token is the cure, not the disease: " + findings);
    }

    @Test
    void aSecretIsReportedWithItsLine() {
        String diff = "diff --git a/app.properties b/app.properties\n--- a/app.properties\n+++ b/app.properties\n"
                + "@@ -10,2 +10,3 @@\n"
                + " a=1\n"
                + "+token=" + GITHUB + "\n"
                + " b=2\n";
        var findings = CommitChecks.run(new FakeIndex().diff("app.properties", 100, diff), ALL);
        assertEquals(1, findings.size());
        CommitChecks.Finding finding = findings.get(0);
        assertEquals(CommitChecks.Kind.SECRET, finding.kind());
        assertEquals("app.properties", finding.path());
        assertEquals("a GitHub token", finding.what());
        assertEquals(11, finding.line());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {".env", "config/.env.local", "certs/server.pem", "id_rsa", ".ssh/id_ed25519",
            "src/main/resources/oauth-secrets.properties"})
    void filesNamedLikeSecretsAreFoundWhateverTheyHold(String path) {
        var findings = CommitChecks.run(new FakeIndex().add(path, 10, "nothing to see"), ALL);
        assertEquals(1, findings.size(), path);
        assertEquals(CommitChecks.Kind.SECRET, findings.get(0).kind());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {".env.example", ".env.sample", "environment.ts", "id_rsa.pub", "docs/pem-format.md"})
    void filesThatOnlyLookLikeSecretsAreNot(String path) {
        assertNull(CommitChecks.secretFileName(path), path);
    }

    @Test
    void aFileBeingDeletedIsNotFlagged() {
        FakeIndex index = new FakeIndex();
        index.sizes.put(".env", -1L);
        assertTrue(CommitChecks.run(index, ALL).isEmpty(), "taking .env out of the repository is the fix");
    }

    @Test
    void theSecretCheckCanBeTurnedOff() {
        FakeIndex index = new FakeIndex().add("a.txt", 10, "token=" + GITHUB).add(".env", 10, "X=1");
        assertTrue(CommitChecks.run(index, new CommitChecks.Options(false, true, 50L << 20)).isEmpty());
        assertEquals(0, index.diffsRead, "and then no diff is read at all");
    }

    @Test
    void aFileOverTheScanCapIsNotRead() {
        FakeIndex index = new FakeIndex().add("big.sql", CommitChecks.MAX_SCAN_BYTES + 1, "token=" + GITHUB);
        assertTrue(CommitChecks.run(index, ALL).isEmpty());
        assertEquals(0, index.diffsRead);
    }

    @Test
    void noMoreThanFiveHundredFilesAreRead() {
        FakeIndex index = new FakeIndex();
        for (int i = 0; i < CommitChecks.MAX_SCANNED_FILES + 50; i++) {
            index.add("file" + i + ".txt", 10, "line " + i);
        }
        CommitChecks.run(index, ALL);
        assertEquals(CommitChecks.MAX_SCANNED_FILES, index.diffsRead);
    }

    // ------------------------------------------------------------------
    // Large files
    // ------------------------------------------------------------------

    @Test
    void aFileOverTheThresholdIsLarge() {
        long sixtyMb = 60L * 1024 * 1024;
        var findings = CommitChecks.run(new FakeIndex()
                .add("assets/video.mp4", sixtyMb)
                .add("small.txt", 1024), ALL);
        assertEquals(1, findings.size());
        assertEquals(CommitChecks.Kind.LARGE_FILE, findings.get(0).kind());
        assertEquals("assets/video.mp4", findings.get(0).path());
        assertEquals(sixtyMb, findings.get(0).bytes());
        assertEquals("60 MB", findings.get(0).what());
    }

    @Test
    void theThresholdIsTheSetting() {
        FakeIndex index = new FakeIndex().add("data.bin", 3L * 1024 * 1024);
        assertTrue(CommitChecks.run(index, ALL).isEmpty(), "3 MB is under 50 MB");
        assertEquals(1, CommitChecks.run(index, new CommitChecks.Options(true, true, 2L * 1024 * 1024)).size());
        assertTrue(CommitChecks.run(index, new CommitChecks.Options(true, false, 1)).isEmpty(),
                "and the check can be turned off");
    }

    @Test
    void aFileLfsAlreadyCoversIsNotLarge() {
        FakeIndex index = new FakeIndex()
                .add("assets/video.mp4", 60L << 20)
                .add("build/out.zip", 60L << 20)
                .add("raw/dump.bin", 60L << 20);
        index.lfs = List.of("*.mp4", "build/**");
        var findings = CommitChecks.run(index, ALL);
        assertEquals(List.of("raw/dump.bin"), findings.stream().map(CommitChecks.Finding::path).toList());
    }

    @Test
    void lfsPatternsMatchTheWayGitDoes() {
        assertTrue(CommitChecks.coveredByLfs("deep/in/tree/a.psd", List.of("*.psd")));
        assertTrue(CommitChecks.coveredByLfs("media/a.wav", List.of("media/*.wav")));
        assertTrue(!CommitChecks.coveredByLfs("other/media/a.wav", List.of("/media/*.wav")));
        assertTrue(!CommitChecks.coveredByLfs("a.psd", List.of()));
    }

    // ------------------------------------------------------------------
    // Conflict markers
    // ------------------------------------------------------------------

    private static final CommitChecks.Options MARKERS_ONLY =
            new CommitChecks.Options(false, false, 50L << 20, true);

    /** HEAD's "ours" line kept as context; everything git wrote around it added. */
    private static final String MARKED_DIFF = "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n"
            + "@@ -1,2 +1,6 @@\n"
            + " intro\n"
            + "+<<<<<<< HEAD\n"
            + " main a\n"
            + "+=======\n"
            + "+feature a\n"
            + "+>>>>>>> feature/simple\n";

    @Test
    void aConflictBlockInTheAddedLinesIsFoundWithItsLine() {
        var findings = CommitChecks.run(new FakeIndex().diff("a.txt", 100, MARKED_DIFF), MARKERS_ONLY);
        assertEquals(1, findings.size(), findings.toString());
        CommitChecks.Finding finding = findings.get(0);
        assertEquals(CommitChecks.Kind.CONFLICT_MARKERS, finding.kind());
        assertEquals("a.txt", finding.path());
        assertEquals(2, finding.line(), "the line of the <<<<<<< in the staged file");
    }

    @Test
    void aHeadingUnderlineIsNotAConflict() {
        var findings = CommitChecks.run(new FakeIndex()
                .add("README.md", 100, "Title", "=======", "", "Text >>>>>>> more text"), MARKERS_ONLY);
        assertTrue(findings.isEmpty(), "a lone ======= is Markdown: " + findings);
    }

    @Test
    void markersAlreadyInTheFileAreNotThisCommits() {
        String diff = "diff --git a/fixture.txt b/fixture.txt\n--- a/fixture.txt\n+++ b/fixture.txt\n"
                + "@@ -1,5 +1,6 @@\n"
                + " <<<<<<< ours\n"
                + " one\n"
                + " =======\n"
                + " two\n"
                + " >>>>>>> theirs\n"
                + "+a line after the sample\n";
        assertTrue(CommitChecks.run(new FakeIndex().diff("fixture.txt", 100, diff), MARKERS_ONLY).isEmpty());
    }

    @Test
    void theThreeArgumentOptionsLeaveMarkersAlone() {
        FakeIndex index = new FakeIndex().diff("a.txt", 100, MARKED_DIFF);
        assertTrue(CommitChecks.run(index, new CommitChecks.Options(false, true, 50L << 20)).isEmpty());
        assertEquals(0, index.diffsRead);
        assertEquals(1, CommitChecks.run(index, new CommitChecks.Options(false, true, 50L << 20)
                .withConflictMarkers()).size());
    }

    @Test
    void aSecretAndMarkersInOneFileAreBothFound() {
        String diff = MARKED_DIFF + "+token=" + GITHUB + "\n";
        var kinds = CommitChecks.run(new FakeIndex().diff("a.txt", 100, diff),
                        new CommitChecks.Options(true, true, 50L << 20, true)).stream()
                .map(CommitChecks.Finding::kind).toList();
        assertEquals(List.of(CommitChecks.Kind.SECRET, CommitChecks.Kind.CONFLICT_MARKERS), kinds);
    }

    @Test
    void aFileNamedLikeASecretIsStillReadForMarkers() {
        FakeIndex index = new FakeIndex().diff(".env", 100, MARKED_DIFF.replace("a.txt", ".env"));
        var kinds = CommitChecks.run(index, new CommitChecks.Options(true, false, 50L << 20, true)).stream()
                .map(CommitChecks.Finding::kind).toList();
        assertEquals(List.of(CommitChecks.Kind.SECRET, CommitChecks.Kind.CONFLICT_MARKERS), kinds);
    }

    /**
     * A real merge stopped on a conflict, the marked file staged as git's "resolved": the
     * commit that would finish the merge is looked over for markers with every check off.
     */
    @Test
    void aMergeCommitIsCheckedForMarkersWhateverTheOptionsSay() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        GitService service = new GitService();
        try {
            service.openRepository(tempDir.toFile());
            Files.writeString(tempDir.resolve("a.txt"), "base a\n", StandardCharsets.UTF_8);
            assertTrue(service.stageFile("a.txt").isOk());
            assertTrue(service.commit("base", false).isOk());
            String main = service.getCurrentBranch();
            assertTrue(service.createBranch("feature", null, true).isOk());
            Files.writeString(tempDir.resolve("a.txt"), "feature a\n", StandardCharsets.UTF_8);
            assertTrue(service.stageFile("a.txt").isOk());
            assertTrue(service.commit("feature", false).isOk());
            assertTrue(service.checkoutBranch(main).isOk());
            Files.writeString(tempDir.resolve("a.txt"), "main a\n", StandardCharsets.UTF_8);
            assertTrue(service.stageFile("a.txt").isOk());
            assertTrue(service.commit("main", false).isOk());
            assertTrue(new RepositoryCommands(service).merge("feature", false).isConflict());

            CommitChecks.Options none = new CommitChecks.Options(false, false, 50L << 20);
            // What Stage all used to do: the conflict "resolved" with its markers in it.
            assertTrue(service.stageFile("a.txt").isOk());
            assertTrue(service.getConflictingFiles().isEmpty());

            var findings = CommitChecks.run(service, none);
            assertEquals(1, findings.size(), findings.toString());
            assertEquals(CommitChecks.Kind.CONFLICT_MARKERS, findings.get(0).kind());
            assertEquals("a.txt", findings.get(0).path());
        } finally {
            service.close();
        }
    }

    // ------------------------------------------------------------------
    // Against a real index
    // ------------------------------------------------------------------

    @Test
    void aRealStagedTokenIsFoundAndAnUnstagedOneIsNot() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.setString("core", null, "autocrlf", "false");
            config.save();
        }
        GitService service = new GitService();
        try {
            service.openRepository(tempDir.toFile());
            Files.writeString(tempDir.resolve("README.md"), "hello\n", StandardCharsets.UTF_8);
            assertTrue(service.stageFile("README.md").isOk());
            assertTrue(service.commit("first", false).isOk());

            Files.writeString(tempDir.resolve("settings.py"), "TOKEN = '" + GITHUB + "'\n", StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("notes.txt"), "token " + GITLAB + "\n", StandardCharsets.UTF_8);
            assertTrue(service.stageFile("settings.py").isOk());

            var findings = CommitChecks.run(service, ALL);
            assertEquals(1, findings.size(), "only what is staged: " + findings);
            assertEquals("settings.py", findings.get(0).path());
            assertEquals(1, findings.get(0).line());
        } finally {
            service.close();
        }
    }

    @Test
    void sizesReadAsPeopleSayThem() {
        assertEquals("60 MB", CommitChecks.describeSize(60L << 20));
        assertEquals("1.5 MB", CommitChecks.describeSize(3L << 19));
        assertEquals("2.0 GB", CommitChecks.describeSize(2L << 30));
    }
}
