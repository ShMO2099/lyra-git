package com.jfx.git.ui;

import com.jfx.git.core.ThreadGuard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The guard's two mechanisms, exercised where they actually apply: on the FX thread.
 *
 * <p>Kept apart from {@code ThreadGuardTest}, which is a plain unit test with no toolkit.
 * Everything about this guard is conditional on being on the JavaFX Application Thread, so
 * a test that never gets there passes whatever the code does - which is how the first
 * version of the fixture-scope test proved nothing at all.
 */
class ThreadGuardFxTest {

    @BeforeEach
    void clear() {
        FxHarness.start();
        ThreadGuard.clear();
    }

    @AfterEach
    void tidyUp() {
        ThreadGuard.clear();
    }

    @Test
    void gitOnTheFxThreadIsRecorded() {
        FxHarness.run(() -> ThreadGuard.offTheFxThread("git status"));

        assertEquals(1, ThreadGuard.violations().size(),
                "this is the whole point of the guard");
        assertEquals("git status", ThreadGuard.violations().get(0).operation());
    }

    /**
     * The mechanism the naming heuristic is only a backstop for. A robot test's
     * {@code start(Stage)} has to build a repository on the FX thread before there is
     * anything to test; saying so is better than being recognised by class name.
     */
    @Test
    void declaredFixtureWorkIsNotRecorded() {
        FxHarness.run(() -> {
            try (var _ = ThreadGuard.buildingFixture()) {
                ThreadGuard.offTheFxThread("git init");
                ThreadGuard.offTheFxThread("git commit");
            } catch (Exception impossible) {
                throw new AssertionError(impossible);
            }
        });

        assertTrue(ThreadGuard.violations().isEmpty(),
                "fixture setup that declared itself must not be reported");
    }

    /** The scope closes, so the call after it is watched again. */
    @Test
    void theScopeDoesNotLeakPastItsBlock() {
        FxHarness.run(() -> {
            try (var _ = ThreadGuard.buildingFixture()) {
                ThreadGuard.offTheFxThread("git init");
            } catch (Exception impossible) {
                throw new AssertionError(impossible);
            }
            ThreadGuard.offTheFxThread("git status");
        });

        assertEquals(1, ThreadGuard.violations().size(),
                "only the call outside the scope is a violation");
        assertEquals("git status", ThreadGuard.violations().get(0).operation());
    }

    /**
     * Outside strict mode the record is capped. It exists for tests, which clear it per
     * test; in the application it was only ever appended to, for as long as the window
     * stayed open.
     */
    @Test
    void theRecordStopsGrowingOutsideStrictMode() {
        FxHarness.run(() -> {
            for (int i = 0; i < 250; i++) {
                ThreadGuard.offTheFxThread("git status");
            }
        });

        assertEquals(200, ThreadGuard.violations().size());
        assertEquals("git status", ThreadGuard.violations().get(199).operation());
    }

    /** A violation from the FX thread names the caller, which is what makes it fixable. */
    @Test
    void theRecordedViolationNamesWhoAskedForIt() {
        FxHarness.run(() -> ThreadGuard.offTheFxThread("git for-each-ref"));

        String caller = ThreadGuard.violations().get(0).caller();
        assertTrue(caller.startsWith("com.jfx.git.ui.ThreadGuardFxTest"),
                "expected this test as the first frame outside the core, got: " + caller);
    }
}
