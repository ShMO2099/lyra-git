package com.jfx.git.ui;

import com.jfx.git.core.AutoStash;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;
import com.jfx.git.core.Refusal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operation runner's rules, through its seams and without a toolkit: what the status
 * bar is told and in which order, the verdict a failure gets however it is shown, what is
 * refreshed and recorded, and how a step that never runs ends its operation.
 *
 * <p>Everything the runner does is written to one {@link #events} list - the status bar's
 * side as "busy", "claim", "idle", "report", "settle", "cancelled"; the tab's as "refresh"
 * and "record"; the caller's hooks by name - so a test reads as the sequence a user's
 * click produces.
 */
class OperationRunnerTest {

    private final List<String> events = new ArrayList<>();
    private final FakeLane lane = new FakeLane();
    private final Deque<Consumer<Boolean>> questions = new ArrayDeque<>();
    private final OperationRunner runner = new OperationRunner(null, null, new Feedback(), lane, new Screen(),
            (operation, stash) -> {
                events.add("asked " + operation.verb());
                questions.add(stash);
            });

    /** The status bar's side: each operation is its label, so idle says whose it closes. */
    private final class Feedback implements OperationRunner.Feedback {
        @Override
        public Object busy(String label) {
            events.add("busy " + label);
            return label;
        }

        @Override
        public void claim(Object operation, GitTaskRunner.Task task) {
            events.add("claim " + operation);
        }

        @Override
        public void idle(Object operation) {
            events.add("idle " + operation);
        }

        @Override
        public void report(GitResult result) {
            events.add("report " + result.getStatus() + " " + result.describe());
        }

        @Override
        public void reportAfter(String context, GitResult result) {
            events.add("report after " + context + ": " + result.describe());
        }

        @Override
        public void show(GitResult result) {
            events.add("show " + result.getStatus() + " " + result.describe());
        }

        @Override
        public void settle(Object operation, GitResult result) {
            events.add("settle " + operation + " " + result.getStatus());
        }

        @Override
        public void cancelled(Object operation) {
            events.add("cancelled " + operation);
        }
    }

    private final class Screen implements OperationRunner.Screen {
        @Override
        public void refresh(OperationRunner.Refresh scope) {
            events.add("refresh " + scope);
        }

        @Override
        public void recordOwnChange(OperationRunner.Refresh scope) {
            events.add("record " + scope);
        }
    }

    /** A lane the test runs by hand: steps wait until the test finishes or drops them. */
    private final class FakeLane implements OperationRunner.Lane {

        private final Deque<Step> waiting = new ArrayDeque<>();

        private record Step(Supplier<?> work, Consumer<Object> done, Consumer<String> failed, Runnable dropped) {
        }

        @Override
        public void run(Supplier<GitResult> work, Consumer<GitTaskRunner.Task> claim,
                        Consumer<GitResult> done, Runnable dropped) {
            events.add("submit");
            claim.accept(new GitTaskRunner.Task());
            waiting.add(new Step(work, value -> done.accept((GitResult) value), null, dropped));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> void read(Supplier<T> work, Consumer<GitTaskRunner.Task> claim,
                             Consumer<T> done, Consumer<String> failed, Runnable dropped) {
            events.add("submit");
            claim.accept(new GitTaskRunner.Task());
            waiting.add(new Step(work, value -> done.accept((T) value), failed, dropped));
        }

        /** Runs the oldest waiting step's work and hands back what it made. */
        void runNext() {
            Step step = waiting.removeFirst();
            Object value;
            try {
                value = step.work().get();
            } catch (RuntimeException e) {
                step.failed().accept(e.getMessage());
                return;
            }
            step.done().accept(value);
        }

        /** The oldest waiting step is cancelled before it starts. */
        void dropNext() {
            waiting.removeFirst().dropped().run();
        }

        boolean idle() {
            return waiting.isEmpty();
        }
    }

    private static Supplier<GitResult> giving(GitResult result) {
        return () -> result;
    }

    // ------------------------------------------------------------------
    // One step
    // ------------------------------------------------------------------

    @Test
    void anOperationIsBusyUntilItsVerdictThenRecordsAndRefreshes() {
        runner.run("Fetching", OperationRunner.Refresh.EVERYTHING, giving(GitResult.ok("Fetched")));
        assertEquals(List.of("busy Fetching", "submit", "claim Fetching"), events,
                "busy before the work is submitted, and the task claimed before it can start");

        lane.runNext();
        assertEquals(List.of("busy Fetching", "submit", "claim Fetching",
                "idle Fetching", "report OK Fetched", "record EVERYTHING", "refresh EVERYTHING"), events,
                "idle, then the report that judges it, then the record, then the refresh");
    }

    @Test
    void eachRefreshIsAskedForAndRecordedAsGiven() {
        for (OperationRunner.Refresh scope : OperationRunner.Refresh.values()) {
            events.clear();
            runner.run("Work", scope, giving(GitResult.ok()));
            lane.runNext();
            assertEquals(List.of("record " + scope, "refresh " + scope),
                    events.subList(events.size() - 2, events.size()));
        }
    }

    @Test
    void aFailureIsReportedAndStillRefreshes() {
        runner.run("Pushing", OperationRunner.Refresh.EVERYTHING, giving(GitResult.failed("rejected")));
        lane.runNext();
        assertEquals(List.of("idle Pushing", "report FAILED rejected", "record EVERYTHING", "refresh EVERYTHING"),
                events.subList(3, events.size()));
    }

    @Test
    void aReadStepThatFailsEndsTheOperationAsFailed() {
        runner.operation("Looking", OperationRunner.Refresh.NONE).start(steps ->
                steps.read(() -> {
                    throw new IllegalStateException("unreadable");
                }, value -> events.add("never")));
        lane.runNext();
        assertEquals(List.of("idle Looking", "report FAILED unreadable", "record NONE", "refresh NONE"),
                events.subList(3, events.size()));
    }

    // ------------------------------------------------------------------
    // Who shows the result
    // ------------------------------------------------------------------

    @Test
    void aFailureTheCallerShowsIsJudgedFailedBeforeTheCallerShowsIt() {
        runner.operation("Adding the worktree", OperationRunner.Refresh.EVERYTHING)
                .showFailure(result -> events.add("caller shows " + result.describe()))
                .run(giving(GitResult.refused("bad path", new Refusal.BadInput(Refusal.Field.PATH))));
        lane.runNext();
        assertEquals(List.of("idle Adding the worktree", "settle Adding the worktree FAILED",
                        "caller shows bad path", "record EVERYTHING", "refresh EVERYTHING"),
                events.subList(3, events.size()),
                "the log has its verdict before a dialog can wait, and the bar shows nothing of its own");
    }

    @Test
    void aFailureTheCallerCouldNotShowGoesToTheStatusBarWithItsVerdictAlreadyGiven() {
        runner.operation("Adding the submodule", OperationRunner.Refresh.EVERYTHING)
                .showFailure(result -> false)
                .run(giving(GitResult.failed("clone failed")));
        lane.runNext();
        assertEquals(List.of("idle Adding the submodule", "settle Adding the submodule FAILED",
                        "show FAILED clone failed", "record EVERYTHING", "refresh EVERYTHING"),
                events.subList(3, events.size()),
                "shown, not reported: the log has its one verdict already");
    }

    @Test
    void aFailureAfterWorkThatLandedIsReportedWithWhatWasDone() {
        runner.operation("Pushing", OperationRunner.Refresh.EVERYTHING)
                .reportFailureAfter("Committed locally, but the push failed")
                .run(giving(GitResult.failed("rejected")));
        lane.runNext();
        assertEquals(List.of("idle Pushing", "report after Committed locally, but the push failed: rejected",
                "record EVERYTHING", "refresh EVERYTHING"), events.subList(3, events.size()));
    }

    @Test
    void aSuccessIsNotTakenOverByTheFailureHandler() {
        runner.operation("Removing", OperationRunner.Refresh.EVERYTHING)
                .showFailure(result -> events.add("caller shows"))
                .run(giving(GitResult.ok("Removed")));
        lane.runNext();
        assertTrue(events.contains("report OK Removed"));
        assertFalse(events.contains("caller shows"));
    }

    @Test
    void aConflictIsReportedEvenWhenTheCallerShowsFailures() {
        runner.operation("Merging", OperationRunner.Refresh.EVERYTHING)
                .showFailure(result -> events.add("caller shows"))
                .run(giving(GitResult.conflict("Conflicts", List.of("a.txt"))));
        lane.runNext();
        assertTrue(events.stream().anyMatch(e -> e.startsWith("report CONFLICT Conflicts")), events.toString());
        assertFalse(events.contains("caller shows"), "a conflict is not a failure to take over");
    }

    @Test
    void aSuccessTheCallerShowsIsSettledAfterIt() {
        runner.operation("Deleting x", OperationRunner.Refresh.EVERYTHING)
                .showSuccess(result -> events.add("undo offered"))
                .run(giving(GitResult.ok("Deleted")));
        lane.runNext();
        assertEquals(List.of("idle Deleting x", "undo offered", "settle Deleting x OK",
                        "record EVERYTHING", "refresh EVERYTHING"),
                events.subList(3, events.size()),
                "what the caller said is the verdict; if it said nothing, the settle gives OK");
    }

    @Test
    void aFailureIsReportedPlainlyWhenOnlySuccessIsTheCallers() {
        runner.operation("Deleting x", OperationRunner.Refresh.EVERYTHING)
                .showSuccess(result -> events.add("undo offered"))
                .run(giving(GitResult.failed("no such branch")));
        lane.runNext();
        assertTrue(events.contains("report FAILED no such branch"));
        assertFalse(events.contains("undo offered"));
    }

    // ------------------------------------------------------------------
    // Hooks
    // ------------------------------------------------------------------

    @Test
    void aSuccessHookRunsAfterTheReportAndBeforeTheRefresh() {
        runner.operation("Renaming origin", OperationRunner.Refresh.EVERYTHING)
                .onSuccess(result -> events.add("hide and solo follow the rename"))
                .afterwards(result -> events.add("afterwards"))
                .run(giving(GitResult.ok("Renamed")));
        lane.runNext();
        assertEquals(List.of("idle Renaming origin", "report OK Renamed", "hide and solo follow the rename",
                        "record EVERYTHING", "refresh EVERYTHING", "afterwards"),
                events.subList(3, events.size()));
    }

    @Test
    void aSuccessHookDoesNotRunOnAFailureButAfterwardsDoes() {
        runner.operation("Renaming origin", OperationRunner.Refresh.EVERYTHING)
                .onSuccess(result -> events.add("success hook"))
                .afterwards(result -> events.add("afterwards " + result.getStatus()))
                .run(giving(GitResult.failed("exists")));
        lane.runNext();
        assertFalse(events.contains("success hook"));
        assertEquals("afterwards FAILED", events.getLast());
    }

    @Test
    void aResultHookRunsBeforeTheRefreshHoweverItWent() {
        runner.operation("Undo: checkout", OperationRunner.Refresh.EVERYTHING)
                .onResult(result -> events.add("result " + result.getStatus()))
                .onSuccess(result -> events.add("success"))
                .run(giving(GitResult.conflict("Re-applying conflicted", List.of())));
        lane.runNext();
        assertEquals(List.of("result CONFLICT", "record EVERYTHING", "refresh EVERYTHING"),
                events.subList(events.size() - 3, events.size()));
    }

    @Test
    void aRefreshWithNothingRunOnlyRedraws() {
        runner.refresh(OperationRunner.Refresh.EVERYTHING);
        assertEquals(List.of("refresh EVERYTHING"), events,
                "a Hide or Solo choice redraws; nothing ran, so there is nothing to record");
    }

    @Test
    void aResultThatChangedNothingIsNotRefreshedFor() {
        runner.operation("Checking out pull request #7", OperationRunner.Refresh.EVERYTHING)
                .refreshWhen(result -> result.getStatus() != GitResult.Status.FAILED)
                .afterwards(result -> events.add("afterwards"))
                .run(giving(GitResult.failed("could not fetch")));
        lane.runNext();
        assertEquals(List.of("idle Checking out pull request #7", "report FAILED could not fetch", "afterwards"),
                events.subList(3, events.size()), "nothing redrawn, nothing recorded");

        events.clear();
        runner.operation("Checking out pull request #7", OperationRunner.Refresh.EVERYTHING)
                .refreshWhen(result -> result.getStatus() != GitResult.Status.FAILED)
                .run(giving(GitResult.conflict("Re-applying your changes conflicted", List.of())));
        lane.runNext();
        assertEquals(List.of("record EVERYTHING", "refresh EVERYTHING"), events.subList(events.size() - 2, events.size()),
                "a conflict moved the branch all the same");
    }

    @Test
    void hooksAddUpInTheOrderTheyWereGiven() {
        runner.operation("Work", OperationRunner.Refresh.NONE)
                .afterwards(result -> events.add("first"))
                .afterwards(result -> events.add("second"))
                .run(giving(GitResult.ok()));
        lane.runNext();
        assertEquals(List.of("first", "second"), events.subList(events.size() - 2, events.size()));
    }

    @Test
    void aHookThatThrowsStillLeavesTheRefreshAndIsRethrown() {
        runner.operation("Work", OperationRunner.Refresh.EVERYTHING)
                .onSuccess(result -> {
                    throw new IllegalStateException("hook broke");
                })
                .afterwards(result -> events.add("afterwards"))
                .run(giving(GitResult.ok()));
        assertThrows(IllegalStateException.class, lane::runNext);
        assertTrue(events.containsAll(List.of("idle Work", "record EVERYTHING", "refresh EVERYTHING", "afterwards")),
                events.toString());
    }

    /**
     * A hook that throws skips no other hook. The toolbar's unlock is a result hook: one
     * thrown ahead of it - by a showSuccess, or a hook given before it - left the toolbar
     * saying an operation was still running for the rest of the session.
     */
    @Test
    void aHookThatThrowsSkipsNoOtherHook() {
        runner.operation("Work", OperationRunner.Refresh.EVERYTHING)
                .showSuccess(result -> {
                    throw new IllegalStateException("toast broke");
                })
                .onResult(result -> {
                    throw new IllegalStateException("hook broke");
                })
                .onResult(result -> events.add("unlocked"))
                .afterwards(result -> {
                    throw new IllegalStateException("afterwards broke");
                })
                .afterwards(result -> events.add("afterwards"))
                .run(giving(GitResult.ok()));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, lane::runNext);

        assertEquals("toast broke", thrown.getMessage(), "the first one is the one rethrown");
        assertEquals(List.of("idle Work", "unlocked", "record EVERYTHING", "refresh EVERYTHING", "afterwards"),
                events.subList(3, events.size()));
    }

    // ------------------------------------------------------------------
    // Cancelled before it started
    // ------------------------------------------------------------------

    @Test
    void aStepCancelledBeforeItStartsEndsTheOperation() {
        runner.operation("Staging all", OperationRunner.Refresh.STAGING)
                .onSuccess(result -> events.add("success hook"))
                .afterwards(result -> events.add("afterwards " + result.getStatus()))
                .run(giving(GitResult.ok()));
        lane.dropNext();
        assertEquals(List.of("busy Staging all", "submit", "claim Staging all",
                "idle Staging all", "cancelled Staging all", "record STAGING", "refresh STAGING",
                "afterwards FAILED"), events,
                "the busy indicator comes down, and what waits on the operation is told it did not happen");
    }

    @Test
    void aReadCancelledBeforeItStartsEndsItsBusy() {
        runner.read("Reading the layout", () -> "layout", value -> events.add("got " + value));
        lane.dropNext();
        assertEquals(List.of("busy Reading the layout", "submit", "claim Reading the layout",
                "idle Reading the layout", "cancelled Reading the layout"), events);
    }

    // ------------------------------------------------------------------
    // Several steps, one operation
    // ------------------------------------------------------------------

    /**
     * The cherry-pick with a follow-up rebase: a read, a decision, another read, a run -
     * one busy, one idle, every step claimed.
     */
    @Test
    void severalStepsAreOneOperation() {
        runner.operation("Cherry-picking 2 commits", OperationRunner.Refresh.EVERYTHING).start(steps ->
                steps.read(() -> "before", before ->
                        steps.read(() -> List.of("c1", "c2"), commits ->
                                steps.run(giving(GitResult.ok("Rebased")), steps::finish))));
        lane.runNext();
        lane.runNext();
        lane.runNext();
        assertTrue(lane.idle());
        assertEquals(List.of("busy Cherry-picking 2 commits",
                "submit", "claim Cherry-picking 2 commits",
                "submit", "claim Cherry-picking 2 commits",
                "submit", "claim Cherry-picking 2 commits",
                "idle Cherry-picking 2 commits", "report OK Rebased",
                "record EVERYTHING", "refresh EVERYTHING"), events);
    }

    @Test
    void aCancelPartWayEndsTheOperationOnce() {
        runner.operation("Two steps", OperationRunner.Refresh.EVERYTHING).start(steps ->
                steps.run(giving(GitResult.ok()), first ->
                        steps.run(giving(GitResult.ok()), steps::finish)));
        lane.runNext();
        lane.dropNext();
        assertEquals(1, events.stream().filter(e -> e.startsWith("idle")).count());
        assertTrue(events.contains("cancelled Two steps"));
    }

    @Test
    void aDecisionThatThrowsEndsTheOperationAsFailed() {
        runner.operation("Deciding", OperationRunner.Refresh.NONE).start(steps ->
                steps.run(giving(GitResult.ok()), result -> {
                    throw new IllegalStateException("decision broke");
                }));
        assertThrows(IllegalStateException.class, lane::runNext);
        assertTrue(events.contains("idle Deciding"));
        assertTrue(events.contains("report FAILED IllegalStateException: decision broke"), events.toString());
    }

    @Test
    void anOperationEndsOnceAndStepsAfterTheEndAreIgnored() {
        OperationRunner.Steps[] kept = new OperationRunner.Steps[1];
        runner.operation("Once", OperationRunner.Refresh.NONE).start(steps -> {
            kept[0] = steps;
            steps.finish(GitResult.ok("Nothing to do"));
        });
        kept[0].finish(GitResult.failed("again"));
        kept[0].run(giving(GitResult.ok()), result -> events.add("late step ran"));
        assertEquals(List.of("busy Once", "idle Once", "report OK Nothing to do", "record NONE", "refresh NONE"),
                events);
        assertTrue(lane.idle(), "a step after the end is never submitted");
    }

    // ------------------------------------------------------------------
    // Auto-stash
    // ------------------------------------------------------------------

    private static final GitResult REFUSED = GitResult.refused("local changes",
            new Refusal.LocalChanges(List.of("a.txt"), false));

    @Test
    void autoStashAsksWhileBusyAndEndsOnceAfterTheRetry() {
        GitResult[] attempts = {REFUSED, GitResult.ok("Checked out other")};
        int[] attempt = {0};
        runner.operation("Checking out other", OperationRunner.Refresh.EVERYTHING)
                .withAutoStash(AutoStash.Operation.CHECKOUT, () -> attempts[attempt[0]++]);
        lane.runNext();
        assertEquals("asked check out", events.getLast(), "asked between the refused attempt and the stash");
        assertFalse(events.stream().anyMatch(e -> e.startsWith("idle")), "still busy while the question is open");

        questions.removeFirst().accept(true);
        assertEquals(List.of("submit", "claim Checking out other"), events.subList(events.size() - 2, events.size()),
                "the stash and retry is a step of the same operation, claimed like the first");
    }

    @Test
    void autoStashDeclinedReportsTheRefusal() {
        runner.operation("Pulling", OperationRunner.Refresh.EVERYTHING)
                .withAutoStash(AutoStash.Operation.PULL, giving(REFUSED));
        lane.runNext();
        questions.removeFirst().accept(false);
        assertEquals(List.of("idle Pulling", "report FAILED local changes", "record EVERYTHING", "refresh EVERYTHING"),
                events.subList(events.size() - 4, events.size()));
    }

    /**
     * An operation waiting on its question while another starts and finishes: each idle
     * closes its own operation, whichever finishes first.
     */
    @Test
    void operationsThatFinishOutOfOrderEachCloseTheirOwn() {
        runner.operation("Checking out other", OperationRunner.Refresh.EVERYTHING)
                .withAutoStash(AutoStash.Operation.CHECKOUT, giving(REFUSED));
        lane.runNext();
        runner.run("Staging a.txt", OperationRunner.Refresh.STAGING, giving(GitResult.ok("Staged")));
        lane.runNext();
        questions.removeFirst().accept(false);
        List<String> closes = events.stream().filter(e -> e.startsWith("idle")).toList();
        assertEquals(List.of("idle Staging a.txt", "idle Checking out other"), closes);
    }

    // ------------------------------------------------------------------
    // Reads the user waits on
    // ------------------------------------------------------------------

    @Test
    void aReadIsBusyThenSettledBeforeItsValueIsUsed() {
        runner.read("Checking what the remote has", () -> "lease", value -> events.add("got " + value));
        lane.runNext();
        assertEquals(List.of("busy Checking what the remote has", "submit", "claim Checking what the remote has",
                "idle Checking what the remote has", "settle Checking what the remote has OK", "got lease"), events,
                "nothing is refreshed or recorded: a read changes nothing");
    }

    /**
     * A read whose answer means the user cannot go on - no lease to force with, a range
     * that cannot be rebased - fails, judged as a failure. It was settled OK before its
     * value was looked at, so the log said the force push's check had worked beside the
     * error saying it had not.
     */
    @Test
    void aReadWhoseAnswerIsAProblemIsJudgedFailed() {
        runner.read("Checking what the remote has", () -> "no lease",
                value -> value.equals("no lease") ? "Nothing to lease against" : null,
                value -> events.add("got " + value));
        lane.runNext();
        assertEquals(List.of("idle Checking what the remote has", "report FAILED Nothing to lease against"),
                events.subList(3, events.size()), "reported as the read's failure; the value is not used");

        events.clear();
        runner.read("Checking what the remote has", () -> "lease",
                value -> value.equals("no lease") ? "Nothing to lease against" : null,
                value -> events.add("got " + value));
        lane.runNext();
        assertEquals(List.of("idle Checking what the remote has", "settle Checking what the remote has OK",
                "got lease"), events.subList(3, events.size()), "no problem: settled, then used");
    }

    @Test
    void aReadThatFailsIsReported() {
        runner.read("Reading", () -> {
            throw new IllegalStateException("no such ref");
        }, value -> events.add("got"));
        lane.runNext();
        assertEquals(List.of("idle Reading", "report FAILED no such ref"), events.subList(3, events.size()));
    }

    // ------------------------------------------------------------------
    // No toolkit
    // ------------------------------------------------------------------

    /** Nothing from JavaFX in the runner, so all of the above runs without a toolkit. */
    @Test
    void theRunnerImportsNothingFromJavaFx() throws Exception {
        String text = Files.readString(Path.of("src/main/java/com/jfx/git/ui/OperationRunner.java"),
                StandardCharsets.UTF_8);
        assertFalse(text.contains("javafx."), "OperationRunner reaches into JavaFX");
    }
}
