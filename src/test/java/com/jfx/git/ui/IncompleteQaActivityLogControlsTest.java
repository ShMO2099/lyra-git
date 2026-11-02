package com.jfx.git.ui;

import com.jfx.git.core.ActivityLog;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.input.Clipboard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the activity log controls that are separate from row rendering and filtering. */
class IncompleteQaActivityLogControlsTest {
    private ActivityLogTab tab;

    @BeforeEach
    void isolateLog() {
        ActivityLog.clear();
    }

    @AfterEach
    void closeTab() {
        FxHarness.run(() -> {
            if (tab != null) tab.dispose();
        });
        ActivityLog.clear();
        FxHarness.closeShownStages();
    }

    @Test
    void problemsOnlyCopyAndClearOperateOnTheVisibleLog() {
        ActivityLog.note(ActivityLog.Scope.REPOSITORY, "qa-repo", "Fetch completed", ActivityLog.Outcome.OK);
        ActivityLog.note(ActivityLog.Scope.REPOSITORY, "qa-repo", "Push rejected: non-fast-forward",
                ActivityLog.Outcome.FAILED);

        FxHarness.run(() -> {
            tab = new ActivityLogTab("qa-repo");
            FxHarness.show(new TabPane(tab), 900, 550);
        });
        FxHarness.run(() -> {
            CheckBox problems = (CheckBox) FxHarness.lookupAll(tab.getContent(), "check-box").stream()
                    .filter(CheckBox.class::isInstance).findFirst().orElseThrow();
            problems.setSelected(true);
            button("Copy").fire();
        });

        String copied = FxHarness.call(() -> Clipboard.getSystemClipboard().getString());
        assertNotNull(copied);
        assertTrue(copied.contains("Push rejected: non-fast-forward"), copied);
        assertFalse(copied.contains("Fetch completed"), copied);

        FxHarness.run(() -> {
            button("Clear").fire();
            // The listener schedules the repaint; the following FX turn observes it.
        });
        FxHarness.run(() -> {
            List<String> rows = FxHarness.lookupAll(tab.getContent(), "activity-log-list").stream()
                    .filter(ListView.class::isInstance)
                    .map(ListView.class::cast)
                    .flatMap(list -> list.getItems().stream())
                    .map(String::valueOf).toList();
            assertTrue(rows.isEmpty(), () -> "Clear should remove the recorded entries: " + rows);
        });
    }

    private Button button(String label) {
        return FxHarness.lookupAll(tab.getContent(), "button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> label.equals(button.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("no " + label + " button"));
    }
}
