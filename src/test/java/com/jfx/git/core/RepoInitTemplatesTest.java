package com.jfx.git.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Year;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Starter files an optional {@code .gitignore}/LICENSE/README selection writes into a
 * freshly-{@code git init}'d directory. Pure file-writing - no git operations of its
 * own - so these tests work against a plain temp directory, not a real repository.
 */
class RepoInitTemplatesTest {

    @TempDir
    Path tempDir;

    @Test
    void gitignoreTemplateNamesListsNoneFirstThenEveryBundledLanguage() {
        List<String> names = RepoInitTemplates.gitignoreTemplateNames();
        assertEquals(RepoInitTemplates.NONE, names.get(0));
        assertTrue(names.containsAll(List.of("Java", "Node", "Python", "macOS", "Windows")));
    }

    @Test
    void licenseTemplateNamesListsNoneFirstThenEveryBundledLicense() {
        List<String> names = RepoInitTemplates.licenseTemplateNames();
        assertEquals(RepoInitTemplates.NONE, names.get(0));
        assertTrue(names.containsAll(List.of(
                "MIT License", "Apache License 2.0", "GNU General Public License v3.0", "BSD 3-Clause License")));
    }

    @Test
    void nothingSelectedWritesNoFilesAtAll() throws Exception {
        List<String> written = RepoInitTemplates.NOTHING.writeInto(tempDir.toFile(), "my-repo");

        assertTrue(written.isEmpty());
        assertFalse(Files.exists(tempDir.resolve(".gitignore")));
        assertFalse(Files.exists(tempDir.resolve("LICENSE")));
        assertFalse(Files.exists(tempDir.resolve("README.md")));
    }

    @Test
    void aGitignoreTemplateWritesTheRealBundledPatterns() throws Exception {
        var templates = new RepoInitTemplates("Node", RepoInitTemplates.NONE, "", false);

        List<String> written = templates.writeInto(tempDir.toFile(), "my-repo");

        assertEquals(List.of(".gitignore"), written);
        String content = Files.readString(tempDir.resolve(".gitignore"), StandardCharsets.UTF_8);
        assertTrue(content.contains("node_modules/"), "should contain Node's own bundled pattern: " + content);
    }

    @Test
    void mitLicenseFillsInTheCopyrightHolderAndCurrentYear() throws Exception {
        var templates = new RepoInitTemplates(RepoInitTemplates.NONE, "MIT License", "Ada Lovelace", false);

        List<String> written = templates.writeInto(tempDir.toFile(), "my-repo");

        assertEquals(List.of("LICENSE"), written);
        String content = Files.readString(tempDir.resolve("LICENSE"), StandardCharsets.UTF_8);
        assertTrue(content.contains("Copyright (c) " + Year.now().getValue() + " Ada Lovelace"),
                "placeholder should be filled in: " + content);
        assertFalse(content.contains("[year]") || content.contains("[fullname]"),
                "no raw placeholder should survive: " + content);
    }

    @Test
    void aBlankCopyrightHolderFallsBackToAPlaceholderPhraseRatherThanAnEmptyName() throws Exception {
        var templates = new RepoInitTemplates(RepoInitTemplates.NONE, "MIT License", "  ", false);

        templates.writeInto(tempDir.toFile(), "my-repo");

        String content = Files.readString(tempDir.resolve("LICENSE"), StandardCharsets.UTF_8);
        assertTrue(content.contains("Copyright (c) " + Year.now().getValue() + " the copyright holder"),
                "blank input should fall back to a readable placeholder: " + content);
    }

    @Test
    void apacheAndGplNeedNoHolderAndEndAtTheRealTermsBoundary() throws Exception {
        var apache = new RepoInitTemplates(RepoInitTemplates.NONE, "Apache License 2.0", "", false);
        apache.writeInto(tempDir.toFile(), "my-repo");
        String apacheContent = Files.readString(tempDir.resolve("LICENSE"), StandardCharsets.UTF_8);
        assertTrue(apacheContent.contains("Apache License"));
        assertTrue(apacheContent.trim().endsWith("END OF TERMS AND CONDITIONS"),
                "should stop at the license's own terms boundary, not carry the source-header appendix: "
                        + apacheContent);

        Path gplDir = tempDir.resolve("gpl");
        Files.createDirectory(gplDir);
        var gpl = new RepoInitTemplates(RepoInitTemplates.NONE, "GNU General Public License v3.0", "", false);
        gpl.writeInto(gplDir.toFile(), "my-repo");
        String gplContent = Files.readString(gplDir.resolve("LICENSE"), StandardCharsets.UTF_8);
        assertTrue(gplContent.contains("GNU GENERAL PUBLIC LICENSE"));
        assertTrue(gplContent.trim().endsWith("END OF TERMS AND CONDITIONS"),
                "should stop at the license's own terms boundary, not carry the source-header guidance: "
                        + gplContent);
    }

    @Test
    void readmeUsesTheGivenRepoNameOrFallsBackToTheDirectoryName() throws Exception {
        var withName = new RepoInitTemplates(RepoInitTemplates.NONE, RepoInitTemplates.NONE, "", true);
        withName.writeInto(tempDir.toFile(), "cool-project");
        assertEquals("# cool-project\n", Files.readString(tempDir.resolve("README.md"), StandardCharsets.UTF_8));

        Path noNameDir = tempDir.resolve("fallback-dir");
        Files.createDirectory(noNameDir);
        var withoutName = new RepoInitTemplates(RepoInitTemplates.NONE, RepoInitTemplates.NONE, "", true);
        withoutName.writeInto(noNameDir.toFile(), " ");
        assertEquals("# fallback-dir\n", Files.readString(noNameDir.resolve("README.md"), StandardCharsets.UTF_8));
    }

    @Test
    void neverOverwritesAFileAlreadyPresentInTheDirectory() throws Exception {
        Files.writeString(tempDir.resolve(".gitignore"), "# my own rules\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("README.md"), "# already had one\n", StandardCharsets.UTF_8);
        var templates = new RepoInitTemplates("Java", "MIT License", "Ada Lovelace", true);

        List<String> written = templates.writeInto(tempDir.toFile(), "my-repo");

        // Only LICENSE was actually missing, so only it should have been written.
        assertEquals(List.of("LICENSE"), written);
        assertEquals("# my own rules\n", Files.readString(tempDir.resolve(".gitignore"), StandardCharsets.UTF_8));
        assertEquals("# already had one\n", Files.readString(tempDir.resolve("README.md"), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // The index files
    // ------------------------------------------------------------------

    /**
     * Every template the index offers has to be a file that is really bundled, with
     * something in it: an entry whose file is missing would be dropped silently, and a
     * name nobody can choose is the bug this is here to catch.
     */
    @Test
    void everyIndexedTemplateIsBundledAndHasContent() throws Exception {
        for (String folder : List.of("gitignore", "licenses")) {
            String listing = new String(RepoInitTemplatesTest.class.getClassLoader()
                    .getResourceAsStream(folder + "/index.txt").readAllBytes(), StandardCharsets.UTF_8);
            long listed = listing.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).count();

            var offered = RepoInitTemplates.index(folder);

            assertEquals(listed, offered.size(), folder + ": every indexed line should name a bundled file");
            for (var entry : offered.entrySet()) {
                try (var in = RepoInitTemplatesTest.class.getClassLoader().getResourceAsStream(entry.getValue())) {
                    assertTrue(in != null && in.readAllBytes().length > 40,
                            entry.getKey() + " should have real content at " + entry.getValue());
                }
            }
        }
    }

    @Test
    void theLanguagesAndLicencesAddedLaterAreOffered() {
        assertTrue(RepoInitTemplates.gitignoreTemplateNames().containsAll(List.of(
                "Go", "Rust", "C", "C++", ".NET (Visual Studio)", "Unity", "Gradle", "Maven",
                "JetBrains IDEs", "VS Code")), RepoInitTemplates.gitignoreTemplateNames().toString());
        assertTrue(RepoInitTemplates.licenseTemplateNames().containsAll(List.of(
                "Mozilla Public License 2.0", "GNU Lesser General Public License v3.0",
                "GNU Affero General Public License v3.0", "The Unlicense",
                "Creative Commons Zero v1.0 Universal")), RepoInitTemplates.licenseTemplateNames().toString());
    }

    @Test
    void aTemplateFromTheIndexIsWrittenLikeTheOriginalFive() throws Exception {
        var templates = new RepoInitTemplates("Rust", "The Unlicense", "", false);

        List<String> written = templates.writeInto(tempDir.toFile(), "my-repo");

        assertEquals(List.of(".gitignore", "LICENSE"), written);
        assertTrue(Files.readString(tempDir.resolve(".gitignore")).contains("target"),
                "Rust's build directory is the one pattern every Rust .gitignore has");
        assertTrue(Files.readString(tempDir.resolve("LICENSE"))
                .contains("This is free and unencumbered software released into the public domain."));
    }

    /** The appendix on how to apply the licence to a program is guidance, not terms, and is left out. */
    @Test
    void theAfferoLicenceEndsAtItsTermsLikeTheGplBesideIt() throws Exception {
        new RepoInitTemplates(RepoInitTemplates.NONE, "GNU Affero General Public License v3.0", "", false)
                .writeInto(tempDir.toFile(), "my-repo");

        String text = Files.readString(tempDir.resolve("LICENSE"));
        assertTrue(text.contains("GNU AFFERO GENERAL PUBLIC LICENSE"));
        assertTrue(text.stripTrailing().endsWith("END OF TERMS AND CONDITIONS"));
        assertFalse(text.contains("<year>"), "no unfilled placeholders from the appendix");
    }
}
