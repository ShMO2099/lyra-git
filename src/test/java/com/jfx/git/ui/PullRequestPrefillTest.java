package com.jfx.git.ui;

import com.jfx.git.core.GitService;
import com.jfx.git.ui.dialogs.CreatePullRequestDialog;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What "Create pull request" after a first push fills the form in with. */
class PullRequestPrefillTest {

    @TempDir
    Path root;

    @Test
    void aBranchNameReadsAsWords() {
        assertEquals("Add login page", PullRequestPrefill.titleFromBranch("feature/add-login_page"));
        assertEquals("ABC-123 fix crash", PullRequestPrefill.titleFromBranch("bugfix/ABC-123-fix-crash"));
        assertEquals("Hotfix", PullRequestPrefill.titleFromBranch("hotfix"));
        assertEquals("", PullRequestPrefill.titleFromBranch(""));
    }

    @Test
    void theFirstTemplateTheHostsWouldFindIsUsed() throws Exception {
        File tree = root.toFile();
        assertEquals("", PullRequestPrefill.template(tree), "no template, no description");

        Files.createDirectories(root.resolve(".gitlab/merge_request_templates"));
        Files.writeString(root.resolve(".gitlab/merge_request_templates/Default.md"), "## GitLab\r\n");
        assertEquals("## GitLab\n", PullRequestPrefill.template(tree), "line endings as a form wants them");

        Files.createDirectories(root.resolve(".github"));
        Files.writeString(root.resolve(".github/pull_request_template.md"), "## What\n\n## Why\n");
        assertEquals("## What\n\n## Why\n", PullRequestPrefill.template(tree), ".github comes first");
    }

    /** One commit: its subject is the title; into the default branch; with the template. */
    @Test
    void aOneCommitBranchIsTitledWithItsCommit() throws Exception {
        File dir = root.resolve("repo").toFile();
        try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Test User");
            config.setString("user", null, "email", "test@example.com");
            config.save();
            Files.writeString(dir.toPath().resolve("a.txt"), "a\n");
            git.add().addFilepattern("a.txt").call();
            git.commit().setMessage("first").call();
            git.checkout().setCreateBranch(true).setName("feature/tidy-up").call();
            Files.writeString(dir.toPath().resolve("b.txt"), "b\n");
            git.add().addFilepattern("b.txt").call();
            git.commit().setMessage("Tidy the parser\n\nAt length.").call();
        }
        Files.createDirectories(dir.toPath().resolve(".github"));
        Files.writeString(dir.toPath().resolve(".github/PULL_REQUEST_TEMPLATE.md"), "Describe it\n");
        GitService git = new GitService();
        try {
            assertTrue(git.openRepository(dir));

            CreatePullRequestDialog.Draft draft = PullRequestPrefill.read(git, "feature/tidy-up");

            assertEquals("Tidy the parser", draft.title());
            assertEquals("feature/tidy-up", draft.sourceBranch());
            assertEquals("main", draft.targetBranch(), "no origin/HEAD here, so main");
            assertEquals("Describe it\n", draft.description());
        } finally {
            git.close();
        }
    }
}
