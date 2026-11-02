package com.jfx.git.ui;

import com.jfx.git.LyraApp;
import com.jfx.git.core.GitService;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repository view replaces the graph inside its own tab, stacks, goes back the way it
 * came, and is told when it is gone - so what it had running stops.
 */
class RepoViewRobotTest extends ApplicationTest {

    @TempDir
    Path tempDir;

    private GitService service;
    private RepoTab tab;
    private final List<String> closed = new ArrayList<>();

    @Override
    public void start(Stage stage) throws Exception {
        Path repo = tempDir.resolve("repo");
        try (var fixture = com.jfx.git.core.ThreadGuard.buildingFixture()) {
            Git.init().setDirectory(repo.toFile()).call().close();
            service = new GitService();
            service.openRepository(repo.toFile());
        }
        tab = new RepoTab(new LyraApp(), "test", service);
        stage.setScene(new Scene(new TabPane(tab), 1100, 700));
        stage.show();
    }

    @AfterEach
    void tidyUp() {
        RobotWait.drainGitWork();
        if (tab != null) interact(tab::close);
        else if (service != null) service.close();
        interact(() -> new ArrayList<>(Window.getWindows()).forEach(Window::hide));
    }

    private RepoView view(String title) {
        Label content = new Label(title + " content");
        content.setId(title.replace(' ', '-'));
        return RepoView.of(title, content, () -> closed.add(title));
    }

    private boolean graphShowing() {
        return tab.graphList().isVisible() && tab.graphList().getScene() != null
                && isShowingInTree(tab.graphList());
    }

    private static boolean isShowingInTree(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) return false;
        }
        return true;
    }

    private Button backButton() {
        return lookup(".repo-view-header .button").queryAs(Button.class);
    }

    @Test
    void aViewReplacesTheGraphAndBackReturnsToIt() {
        RepoView requests = view("Pull requests");
        interact(() -> tab.showView(requests));

        assertTrue(lookup("#Pull-requests").tryQuery().isPresent());
        assertFalse(graphShowing(), "the graph gives way");
        assertEquals("← Back to graph", backButton().getText());
        assertSame(requests, tab.currentView());

        clickOn(backButton());
        assertNull(tab.currentView());
        assertTrue(graphShowing());
        assertEquals(List.of("Pull requests"), closed, "told it was closed");
    }

    @Test
    void viewsStackAndBackNamesWhereItGoes() {
        interact(() -> tab.showView(view("Pull requests")));
        interact(() -> tab.showView(view("Review #7")));

        assertEquals("← Back to Pull requests", backButton().getText());
        clickOn(backButton());
        assertTrue(lookup("#Pull-requests").tryQuery().isPresent(), "back to the list, not the graph");
        assertEquals(List.of("Review #7"), closed);
    }

    @Test
    void escapeGoesBack() {
        interact(() -> tab.showView(view("Reflog")));
        type(KeyCode.ESCAPE);
        assertNull(tab.currentView());
        assertEquals(List.of("Reflog"), closed);
    }

    @Test
    void closingTheTabClosesEveryView() {
        interact(() -> tab.showView(view("Pull requests")));
        interact(() -> tab.showView(view("Review #7")));
        interact(() -> tab.close());
        assertEquals(List.of("Review #7", "Pull requests"), closed, "newest first");
        tab = null;
        service = null;
    }

    @Test
    void closingAViewBelowTakesTheOnesOpenedFromItToo() {
        RepoView list = view("Pull requests");
        interact(() -> tab.showView(list));
        interact(() -> tab.showView(view("Review #7")));
        interact(() -> tab.closeView(list));
        assertNull(tab.currentView());
        assertEquals(List.of("Review #7", "Pull requests"), closed);
    }
}
