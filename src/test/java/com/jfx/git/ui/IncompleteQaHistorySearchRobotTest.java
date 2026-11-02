package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises author and hash-prefix searches through the graph's actual search field. */
class IncompleteQaHistorySearchRobotTest extends ApplicationTest {

    @TempDir
    Path repository;

    private GitService service;
    private RepoTab tab;
    private String authoredCommit;
    private String originalHead;

    @Override
    public void start(Stage stage) throws Exception {
        try (Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("user", null, "name", "Synthetic QA User");
            config.setString("user", null, "email", "qa-user@example.test");
            config.setString("core", null, "autocrlf", "false");
            config.setBoolean("commit", null, "gpgsign", false);
            config.save();

            for (int i = 0; i < 4; i++) {
                Files.writeString(repository.resolve("revision.txt"), "revision " + i + "\n", StandardCharsets.UTF_8);
                git.add().addFilepattern("revision.txt").call();
                var commit = git.commit().setMessage("revision " + i)
                        .setAuthor(i == 2
                                ? new PersonIdent("Feature Author", "feature@example.test")
                                : new PersonIdent("Synthetic QA User", "qa-user@example.test"))
                        .call();
                if (i == 2) authoredCommit = commit.getName();
            }
            originalHead = git.getRepository().resolve("HEAD").name();
        }

        service = new GitService();
        assertTrue(service.openRepository(repository.toFile()));
        tab = new RepoTab(new LyraApp(), "history-search", service);
        stage.setScene(new Scene(new TabPane(tab), 1200, 700));
        stage.show();
        tab.refresh();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (service != null) service.close();
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    private void search(String query) {
        interact(() -> {
            if (tab.historySearchPanel().isVisible()) tab.historySearchPanel().close();
        });
        TextField field = lookup(".search-field").queryAs(TextField.class);
        clickOn(field);
        interact(() -> field.setText(query));
        type(KeyCode.ENTER);
        RobotWait.untilOnFx("history search did not finish: " + query,
                () -> tab.historySearchPanel().isVisible()
                        && !tab.historySearchPanel().summaryText().isBlank()
                        && !tab.historySearchPanel().isSearching()
                        && !tab.historySearchPanel().summaryText().startsWith("Searching"));
    }

    @Test
    void authorAndHashPrefixQueriesFindTheSameCommitWithoutChangingTheRepository() {
        search("author:\"Feature Author\"");
        assertEquals(1, tab.historySearchPanel().resultsList().getItems().size());
        assertEquals(authoredCommit, tab.historySearchPanel().resultsList().getItems().get(0).getHash());

        search("hash:" + authoredCommit.substring(0, 8));
        assertEquals(1, tab.historySearchPanel().resultsList().getItems().size());
        assertEquals(authoredCommit, tab.historySearchPanel().resultsList().getItems().get(0).getHash());
        assertEquals(originalHead, service.getHeadHash(), "searching does not move HEAD");
        assertTrue(service.getStagingState().staged().isEmpty());
        assertTrue(service.getStagingState().unstaged().isEmpty());
    }
}
