package com.jfx.git.graph;

import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;
import com.jfx.git.ui.FxHarness;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The width of the graph's branch column.
 *
 * <p>It used to be capped at the density's 64 or 76 pixels whatever the window was, so
 * on a repository with namespaced branches every chip read "featu…". It now takes what
 * its widest name needs, up to a share of the list's width, and can be dragged.
 */
class GraphRailWidthTest {

    @BeforeAll
    static void toolkit() {
        // Chip names are measured with a Text node, which needs the toolkit.
        FxHarness.start();
    }

    private static GraphMetrics metricsWith(String... branches) {
        GraphMetrics metrics = new GraphMetrics(GraphDensity.COMPACT, GraphNodeStyle.DOT);
        metrics.update(rows(branches), "main");
        return metrics;
    }

    /** One commit per branch name, each carrying that branch as a local ref. */
    private static List<GitGraphRow> rows(String... branches) {
        java.util.List<GitGraphRow> rows = new java.util.ArrayList<>();
        int n = 0;
        for (String branch : branches) {
            String hash = String.format("%040d", ++n);
            CommitInfo commit = new CommitInfo(hash, "subject " + n, "Ada", "ada@example.com",
                    java.time.Instant.now(), List.of(),
                    List.of(new RefInfo("refs/heads/" + branch, branch, hash)));
            rows.add(new GitGraphRow(commit));
        }
        return rows;
    }

    @Test
    void aWideWindowGivesALongNameTheRoomToBeRead() {
        GraphMetrics metrics = FxHarness.call(() -> metricsWith("feature/login-redesign"));
        double narrow = metrics.railWidth();

        FxHarness.run(() -> metrics.setAvailableWidth(1100));

        assertTrue(metrics.railWidth() > GraphDensity.COMPACT.railWidth(),
                "with 1100px of list the column should grow past the old fixed cap, was "
                        + metrics.railWidth());
        assertTrue(metrics.railWidth() > narrow, "and past what it was with no width known");
        assertTrue(metrics.railWidth() <= GraphMetrics.MAX_AUTO_RAIL,
                "but never past its own ceiling: " + metrics.railWidth());
    }

    @Test
    void aNarrowWindowKeepsTheColumnAtItsOldSize() {
        GraphMetrics metrics = FxHarness.call(() -> metricsWith("feature/login-redesign"));

        FxHarness.run(() -> metrics.setAvailableWidth(240));

        assertEquals(GraphDensity.COMPACT.railWidth(), metrics.railWidth(), 0.5,
                "a share of 240px is below the density's figure, which stays the floor of the ceiling");
    }

    @Test
    void shortNamesStillGetANarrowColumn() {
        GraphMetrics metrics = FxHarness.call(() -> metricsWith("dev"));

        FxHarness.run(() -> metrics.setAvailableWidth(1400));

        assertTrue(metrics.railWidth() < GraphDensity.COMPACT.railWidth(),
                "a wide window is not a reason to widen a column of short names: " + metrics.railWidth());
    }

    @Test
    void aDraggedWidthSurvivesResizesAndNewPages() {
        GraphMetrics metrics = FxHarness.call(() -> metricsWith("dev"));

        FxHarness.run(() -> {
            metrics.setRailWidth(240);
            metrics.setAvailableWidth(500);
            metrics.update(rows("feature/login-redesign"), "main");
        });

        assertEquals(240, metrics.railWidth(), 0.5, "the column stays where it was dragged to");
        assertEquals(240, metrics.railWidthOverride(), 0.5);
    }

    @Test
    void doubleClickHandsTheWidthBackToTheNames() {
        GraphMetrics metrics = FxHarness.call(() -> metricsWith("dev"));
        FxHarness.run(() -> metrics.setAvailableWidth(1400));
        double automatic = metrics.railWidth();

        FxHarness.run(() -> {
            metrics.setRailWidth(300);
            metrics.clearRailWidth();
        });

        assertEquals(0, metrics.railWidthOverride(), 0.0);
        assertEquals(automatic, metrics.railWidth(), 0.5);
    }

    @Test
    void aDragIsKeptWithinSensibleLimits() {
        GraphMetrics metrics = FxHarness.call(() -> metricsWith("dev"));

        FxHarness.run(() -> metrics.setRailWidth(5000));
        assertEquals(GraphMetrics.MAX_RAIL, metrics.railWidth(), 0.5);

        FxHarness.run(() -> metrics.setRailWidth(3));
        assertTrue(metrics.railWidth() >= 44, "never narrower than a short name needs");
    }
}
