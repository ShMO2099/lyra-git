package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitBackend;
import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.graph.LaneAssigner;
import com.jfx.git.model.CommitInfo;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Narrowing the graph to one author from the Author column's heading.
 *
 * <p>Driven through the real dropdown rather than by calling the tab, because the point
 * of the feature is that the question is asked where the answer is displayed.
 */
class AuthorFilterRobotTest extends ApplicationTest {

    private RepoTab tab;

    private static GitBackend stubBackend() {
        return (GitBackend) Proxy.newProxyInstance(
                GitBackend.class.getClassLoader(),
                new Class<?>[]{GitBackend.class},
                (proxy, method, args) -> {
                    if ("getCurrentBranch".equals(method.getName())) return "main";
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == double.class) return 0.0;
                    if (type == List.class) return List.of();
                    return null;
                });
    }

    private static CommitInfo commit(String hash, String subject, String author,
                                     String... parents) {
        return new CommitInfo(hash, subject, author,
                author.toLowerCase(java.util.Locale.ROOT).replace(' ', '.') + "@example.com",
                Instant.now(), List.of(parents), List.of());
    }

    /** Two authors, interleaved, so filtering has something to remove. */
    private static List<GitGraphRow> rows() {
        List<CommitInfo> commits = new ArrayList<>(List.of(
                commit("aaaaaaaa1", "ada one", "Ada Lovelace", "aaaaaaaa2"),
                commit("aaaaaaaa2", "alan one", "Alan Turing", "aaaaaaaa3"),
                commit("aaaaaaaa3", "ada two", "Ada Lovelace", "aaaaaaaa4"),
                commit("aaaaaaaa4", "alan two", "Alan Turing", "aaaaaaaa5"),
                commit("aaaaaaaa5", "ada three", "Ada Lovelace")));
        LaneAssigner.assign(commits);
        List<GitGraphRow> rows = new ArrayList<>();
        for (CommitInfo c : commits) {
            rows.add(new GitGraphRow(c));
        }
        return rows;
    }

    @Override
    public void init() {
        com.jfx.git.core.AppPreferences.setGraphDensity(com.jfx.git.graph.GraphDensity.COMPACT);
        // The dropdown hangs off the Author heading, which only exists under the dot node.
        com.jfx.git.core.AppPreferences.setGraphNodeStyle(com.jfx.git.graph.GraphNodeStyle.DOT);
        com.jfx.git.graph.GraphColumn.resetToDefault();
    }

    @Override
    public void start(Stage stage) {
        tab = new RepoTab(new LyraApp(), "test", stubBackend());
        TabPane pane = new TabPane(tab);
        stage.setScene(new Scene(pane, 1200, 700));
        stage.show();
        tab.showRows(rows());
    }

    /** The Author heading, which carries the dropdown. */
    private Label authorHeading() {
        return (Label) lookup(".graph-header-filter").query();
    }

    /** Commit subjects currently drawn in the graph. */
    private List<String> visibleSubjects() {
        WaitForAsyncUtils.waitForFxEvents();
        List<String> subjects = new ArrayList<>();
        for (GitGraphRow row : tab.graphList().getItems()) {
            if (row.getCommit() != null) {
                subjects.add(row.getCommit().getSubject());
            }
        }
        return subjects;
    }

    private void chooseAuthor(String label) {
        clickOn(authorHeading());
        WaitForAsyncUtils.waitForFxEvents();
        Node item = from(lookup(".context-menu")).lookup(label).query();
        clickOn(item);
        WaitForAsyncUtils.waitForFxEvents();
    }

    // ------------------------------------------------------------------

    @Test
    void everyCommitIsShownBeforeAnyoneIsChosen() {
        assertEquals(5, visibleSubjects().size());
        assertNull(tab.authorFilter());
    }

    @Test
    void theHeadingOffersEveryAuthorOnThePage() {
        clickOn(authorHeading());
        WaitForAsyncUtils.waitForFxEvents();

        assertNotNull(from(lookup(".context-menu")).lookup("Ada Lovelace").tryQuery().orElse(null));
        assertNotNull(from(lookup(".context-menu")).lookup("Alan Turing").tryQuery().orElse(null));
        assertNotNull(from(lookup(".context-menu")).lookup("All authors").tryQuery().orElse(null));
    }

    @Test
    void choosingAnAuthorNarrowsTheGraphToTheirCommits() {
        chooseAuthor("Ada Lovelace");

        assertEquals(List.of("ada one", "ada two", "ada three"), visibleSubjects());
        assertEquals("Ada Lovelace", tab.authorFilter());
    }

    /**
     * A column quietly hiding most of the history has to say so where the user is
     * looking, not only inside a menu they would have to reopen to check.
     */
    @Test
    void theHeadingSaysWhoIsBeingShown() {
        chooseAuthor("Alan Turing");
        assertTrue(authorHeading().getText().startsWith("Alan Turing"),
                "the heading read: " + authorHeading().getText());
    }

    @Test
    void allAuthorsWidensTheGraphAgain() {
        chooseAuthor("Ada Lovelace");
        assertEquals(3, visibleSubjects().size());

        chooseAuthor("All authors");

        assertEquals(5, visibleSubjects().size());
        assertNull(tab.authorFilter());
        assertTrue(authorHeading().getText().startsWith("Author"));
    }

    @Test
    void switchingAuthorReplacesTheFilterRatherThanAddingToIt() {
        chooseAuthor("Ada Lovelace");
        chooseAuthor("Alan Turing");

        assertEquals(List.of("alan one", "alan two"), visibleSubjects());
    }

    /**
     * The two filters are separate questions and both have to hold: choosing an author
     * and then typing should narrow that author's commits, not start again over everyone.
     */
    @Test
    void theSearchBoxNarrowsWithinTheChosenAuthor() {
        chooseAuthor("Ada Lovelace");

        Node search = lookup(".search-field").query();
        clickOn(search);
        write("two");
        WaitForAsyncUtils.waitForFxEvents();

        assertEquals(List.of("ada two"), visibleSubjects(),
                "\"alan two\" matches the text but not the author");
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(() -> tab.close());
    }
}
