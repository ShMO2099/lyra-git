package com.jfx.git.graph;

import com.jfx.git.core.GitService;
import com.jfx.git.ui.FxHarness;
import com.jfx.git.ui.OperationRunner;
import com.jfx.git.ui.StatusBar;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Every way of building a graph cell builds one. The shorter constructors - no metrics,
 * no comparison - pass a null metrics on, and the longest read that argument instead of
 * the field it had defaulted, so they threw before the cell existed.
 */
class GitGraphCellConstructorsTest {

    @BeforeAll
    static void toolkit() {
        FxHarness.start();
    }

    @Test
    void aCellWithoutMetricsSizesItselfFromTheDensity() {
        GitService backend = new GitService();
        GitGraphCell bare = FxHarness.call(() ->
                new GitGraphCell(OperationRunner.of(backend, new StatusBar(), () -> { })));
        GitGraphCell comparing = FxHarness.call(() ->
                new GitGraphCell(OperationRunner.of(backend, new StatusBar(), () -> { }), (a, b) -> { }));
        assertNotNull(bare);
        assertNotNull(comparing);
    }
}
