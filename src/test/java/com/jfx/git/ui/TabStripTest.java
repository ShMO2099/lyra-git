package com.jfx.git.ui;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The strip's rules, against a real {@link TabPane}.
 *
 * <p>Every one of these exists because of the trailing ＋, which is a button wearing a
 * tab's clothes. They run on the FX thread through {@link FxHarness} rather than against
 * a mock, because the behaviours being checked — what a list change does to the selection,
 * what {@code select} does when the tab moved — are the toolkit's, not ours.
 */
class TabStripTest {

    /** A strip with {@code count} plain tabs and the ＋ pinned at the end. */
    private record Strip(TabPane pane, Tab plus, TabStrip strip, List<Tab> tabs) {}

    private static Strip strip(int count) {
        return FxHarness.call(() -> {
            TabPane pane = new TabPane();
            java.util.List<Tab> tabs = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                Tab tab = new Tab("tab" + i);
                tabs.add(tab);
                pane.getTabs().add(tab);
            }
            Tab plus = new Tab("＋");
            plus.setClosable(false);
            pane.getTabs().add(plus);
            return new Strip(pane, plus, new TabStrip(pane, plus), List.copyOf(tabs));
        });
    }

    // ------------------------------------------------------------------
    // Pinning the ＋
    // ------------------------------------------------------------------

    @Test
    void draggingATabPastThePlusPutsThePlusBackAtTheEnd() {
        Strip s = strip(2);
        FxHarness.run(() -> {
            s.strip().install(null);
            // What TabDragPolicy.REORDER does when a tab is dropped to the right of ＋.
            s.pane().getTabs().remove(s.tabs().get(0));
            s.pane().getTabs().add(s.tabs().get(0));
        });
        // A second hop onto the FX thread: the correction is queued behind the change
        // notification, so asserting in the same block would read the strip mid-drag.
        FxHarness.run(() ->
                assertSame(s.plus(), s.pane().getTabs().get(s.pane().getTabs().size() - 1),
                        "the ＋ is a button, not a document, and belongs at the end"));
    }

    @Test
    void thePlusIsNeverLostWhileBeingMoved() {
        Strip s = strip(2);
        FxHarness.run(() -> {
            s.strip().install(null);
            s.pane().getTabs().remove(s.tabs().get(0));
            s.pane().getTabs().add(s.tabs().get(0));
        });
        FxHarness.run(() -> assertTrue(s.pane().getTabs().contains(s.plus()),
                "editing a list inside its own change listener drops the edit, and the "
                        + "＋ vanished from the strip entirely"));
    }

    @Test
    void pinningDoesNotChangeWhichTabIsInFront() {
        Strip s = strip(3);
        FxHarness.run(() -> {
            s.strip().install(null);
            s.pane().getSelectionModel().select(s.tabs().get(1));

            s.pane().getTabs().remove(s.tabs().get(0));
            s.pane().getTabs().add(s.tabs().get(0));
        });
        FxHarness.run(() ->
                assertSame(s.tabs().get(1), s.pane().getSelectionModel().getSelectedItem(),
                        "a drag must not also change which repository is being looked at"));
    }

    @Test
    void aStripAlreadyInOrderIsLeftAlone() {
        Strip s = strip(2);
        FxHarness.run(() -> assertFalse(s.strip().pinNewTabButtonLast(),
                "correcting an already-correct strip would loop through the listener"));
    }

    @Test
    void theChangeCallbackRunsAfterTheStripHasSettled() {
        Strip s = strip(2);
        AtomicInteger lastIndexSeen = new AtomicInteger(-1);
        FxHarness.run(() -> {
            s.strip().install(() ->
                    lastIndexSeen.set(s.pane().getTabs().indexOf(s.plus())));

            s.pane().getTabs().remove(s.tabs().get(0));
            s.pane().getTabs().add(s.tabs().get(0));
        });
        FxHarness.run(() ->
                assertEquals(s.pane().getTabs().size() - 1, lastIndexSeen.get(),
                        "the session must never be recorded in the order a drag momentarily left"));
    }

    // ------------------------------------------------------------------
    // Cycling
    // ------------------------------------------------------------------

    @Test
    void cyclingForwardSkipsThePlusAndWraps() {
        Strip s = strip(3);
        FxHarness.run(() -> {
            s.pane().getSelectionModel().select(s.tabs().get(0));

            s.strip().cycle(1);
            assertSame(s.tabs().get(1), s.pane().getSelectionModel().getSelectedItem());

            s.strip().cycle(1);
            assertSame(s.tabs().get(2), s.pane().getSelectionModel().getSelectedItem());

            s.strip().cycle(1);
            assertSame(s.tabs().get(0), s.pane().getSelectionModel().getSelectedItem(),
                    "wrapping past the last tab must skip the ＋, not land on it");
        });
    }

    @Test
    void cyclingBackwardsWrapsTheOtherWay() {
        Strip s = strip(3);
        FxHarness.run(() -> {
            s.pane().getSelectionModel().select(s.tabs().get(0));
            s.strip().cycle(-1);
            assertSame(s.tabs().get(2), s.pane().getSelectionModel().getSelectedItem());
        });
    }

    @Test
    void cyclingFromThePlusStartsAtTheFirstTab() {
        Strip s = strip(2);
        FxHarness.run(() -> {
            s.pane().getSelectionModel().select(s.plus());
            s.strip().cycle(1);
            assertSame(s.tabs().get(1), s.pane().getSelectionModel().getSelectedItem());
        });
    }

    @Test
    void cyclingDoesNothingWithOneTab() {
        Strip s = strip(1);
        FxHarness.run(() -> {
            s.pane().getSelectionModel().select(s.tabs().get(0));
            s.strip().cycle(1);
            assertSame(s.tabs().get(0), s.pane().getSelectionModel().getSelectedItem(),
                    "with nothing to cycle to, the key must be inert rather than select the ＋");
        });
    }

    @Test
    void thePlusIsNeverADocumentTab() {
        Strip s = strip(2);
        FxHarness.run(() -> {
            assertEquals(2, s.strip().documentTabs().size());
            assertFalse(s.strip().documentTabs().contains(s.plus()));
        });
    }

    // ------------------------------------------------------------------
    // Numbering
    // ------------------------------------------------------------------

    @Test
    void numberingIgnoresTabsThatAreNotRepositories() {
        Strip s = strip(2);
        FxHarness.run(() -> {
            // Plain tabs stand in for launchpad pages: neither is a RepoTab, so Ctrl+1
            // has nothing to select and must not throw or pick the ＋.
            s.strip().selectRepoTab(0);
            assertNotSame(s.plus(), s.pane().getSelectionModel().getSelectedItem());
        });
    }

    // ------------------------------------------------------------------
    // Application tabs
    // ------------------------------------------------------------------

    /** Settings or the activity log, as far as the strip is concerned. */
    private static final class AppTab extends ToolTab {
        AppTab() {
            super("Settings");
        }
    }

    /**
     * An application tab is a place to go like any other - Ctrl+Tab reaches it - but not a
     * repository, so it never takes a number from Ctrl+1..9.
     */
    @Test
    void anApplicationTabIsCycledToButNeverNumbered() {
        Strip s = strip(2);
        FxHarness.run(() -> {
            AppTab settings = new AppTab();
            s.pane().getTabs().add(s.pane().getTabs().size() - 1, settings);
            s.pane().getSelectionModel().select(s.tabs().get(1));

            s.strip().cycle(1);
            assertSame(settings, s.pane().getSelectionModel().getSelectedItem(),
                    "the tab after the last document is Settings, not the ＋");
            assertTrue(s.strip().documentTabs().contains(settings));

            s.strip().selectRepoTab(8);
            assertSame(settings, s.pane().getSelectionModel().getSelectedItem(),
                    "with no repository open, Ctrl+9 has nothing to select and leaves Settings in front");
        });
    }
}
