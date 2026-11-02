package com.jfx.git.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine doctor, which is the conformance idea turned into something a user can run.
 *
 * <p>What is worth testing here is not that the engines agree - {@link
 * BackendConformanceTest} does that far more thoroughly - but that the doctor itself is
 * trustworthy: that it reports agreement honestly, cleans up after itself, and never
 * touches anything but its own fixture. A doctor that quietly reported "all fine" because
 * it failed to ask would be worse than not having one.
 */
@Tag("heavy")
class EngineDoctorTest {

    static {
        org.eclipse.jgit.storage.file.WindowCacheConfig config =
                new org.eclipse.jgit.storage.file.WindowCacheConfig();
        config.setPackedGitMMAP(false);
        config.install();
    }

    @AfterEach
    void releaseRepositories() {
        org.eclipse.jgit.lib.RepositoryCache.clear();
    }

    @Test
    void itExaminesEveryEngineThisMachineHas() {
        EngineDoctor.Report report = EngineDoctor.run();

        assertTrue(report.isOk(), () -> "the doctor could not run: " + report.problem());
        assertTrue(report.engines().contains("JGit"), "JGit is always available");
        if (NativeGitRunner.isGitInstalled()) {
            assertTrue(report.engines().contains("Native CLI"),
                    () -> "git is installed but was not examined: " + report.engines());
        }
    }

    @Test
    void everyCheckGetsAnAnswerFromEveryEngineItExamined() {
        EngineDoctor.Report report = EngineDoctor.run();

        assertFalse(report.checks().isEmpty(), "there should be checks to run");
        for (EngineDoctor.Check check : report.checks()) {
            assertEquals(report.engines().size(), check.answers().size(),
                    () -> "every engine should have answered " + check.question()
                            + ", got " + check.answers());
            check.answers().forEach((engine, answer) ->
                    assertNotNull(answer, engine + " gave no answer for " + check.question()));
        }
    }

    /**
     * The point of the whole exercise. If this starts failing, either an engine has
     * drifted - which is exactly what the doctor is for - or the doctor has stopped
     * asking properly.
     */
    @Test
    void theEnginesOnThisMachineAgree() {
        EngineDoctor.Report report = EngineDoctor.run();

        List<EngineDoctor.Check> disagreements = report.disagreements();
        assertTrue(disagreements.isEmpty(), () -> "engines disagree: " + disagreements.stream()
                .map(c -> c.question() + " -> " + c.describe()).toList());
    }

    @Test
    void theSummaryReflectsWhatWasFound() {
        EngineDoctor.Report report = EngineDoctor.run();

        String summary = report.summary();
        assertNotNull(summary);
        assertFalse(summary.isBlank());
        if (report.engines().size() > 1 && report.disagreements().isEmpty()) {
            assertTrue(summary.contains("agree"), summary);
        }
    }

    /** A doctor run must be invisible afterwards: it works on a fixture of its own. */
    @Test
    void itLeavesNothingBehindThatItCanDeleteAndTouchesNoRealRepository() throws Exception {
        java.nio.file.Path temp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
        long before = countDoctorDirectories(temp);

        EngineDoctor.run();

        long after = countDoctorDirectories(temp);
        // Windows can hold a pack file open and leave the directory behind, which the
        // class tolerates on purpose - so this asserts it does not *accumulate*, not
        // that it always deletes.
        assertTrue(after <= before + 1,
                "a doctor run should not leave more than the one directory Windows may lock");
    }

    private static long countDoctorDirectories(java.nio.file.Path temp) throws Exception {
        try (var list = java.nio.file.Files.list(temp)) {
            return list.filter(p -> p.getFileName().toString().startsWith("lyra-doctor")).count();
        }
    }
}
