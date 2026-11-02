package com.jfx.git.ui;

import com.jfx.git.core.AutoStash;
import com.jfx.git.core.GitBackend;
import com.jfx.git.core.GitResult;
import com.jfx.git.core.GitTaskRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the operations the user starts in one repository tab - a checkout, a commit, a
 * discard - from the click to the redrawn screen. One per tab, handed to every part of it
 * that changes the repository.
 *
 * <p>A caller says what to do, what to call it and what it changes: the work, run on the
 * repository's lane; a label, which the status bar shows while it runs and the activity
 * log records; and the {@link Refresh} it needs afterwards. Everything else is the
 * runner's, the same way every time:
 * <ol>
 *   <li>busy, before the work is submitted, so the log's duration covers the work;</li>
 *   <li>the task claimed for the status bar before it can start - its progress shows from
 *       the first report, and Cancel reaches it;</li>
 *   <li>on the FX thread once it is done: idle, then the verdict and the report - the
 *       verdict always follows the idle of this operation and no other;</li>
 *   <li>the caller's {@link Operation#onResult} and {@link Operation#onSuccess} hooks, then
 *       the repository recorded as the operation left it ({@link Screen#recordOwnChange}),
 *       then the refresh, then the caller's {@link Operation#afterwards}.</li>
 * </ol>
 * A failure is always reported. A caller may show one its own way - a dialog, an error
 * under a form field - and the activity log still records it as failed. A step cancelled
 * before it starts never calls back; the runner hears it and ends the operation as
 * cancelled, so no busy indicator outlives work that will not run.
 *
 * <p>Several steps make one operation when they need to: {@link Operation#start} hands the
 * caller {@link Steps} - lane steps with FX-thread decisions between them, a question,
 * a dialog - and the operation ends when the caller {@link Steps#finish finishes} it.
 * Auto-stash is the commonest such flow and has {@link Operation#withAutoStash}.
 *
 * <p>Nothing here touches JavaFX: the status bar, the lane and the tab are seams
 * ({@link Feedback}, {@link Lane}, {@link Screen}), so the ordering rules above are tested
 * without a toolkit. Call it on the FX thread, as every caller does.
 */
public final class OperationRunner {

    private static final Logger LOGGER = Logger.getLogger(OperationRunner.class.getName());

    /** What an operation redraws once it is over. */
    public enum Refresh {
        /**
         * The whole tab - graph, sidebar, toolbar, banners: for anything that can move
         * HEAD, a ref or a stash.
         */
        EVERYTHING,
        /** The staging panel alone: for a change to the index or the working tree only. */
        STAGING,
        /** Nothing: the caller has already put the screen right, or nothing on it changed. */
        NONE
    }

    /** The status bar's side of an operation, and the activity log behind it. */
    interface Feedback {
        /** Says {@code label} is under way and opens its log entry; returns the operation. */
        Object busy(String label);

        /** Shows {@code task}'s progress under {@code operation}; called before the task can start. */
        void claim(Object operation, GitTaskRunner.Task task);

        /** {@code operation} is over: closes its log entry, which the next report judges. */
        void idle(Object operation);

        /** Shows {@code result} by its status, and gives the operation just closed its verdict. */
        void report(GitResult result);

        /** Shows {@code result} by its status, and leaves the log alone: its verdict is given. */
        void show(GitResult result);

        /**
         * Reports a failure that followed work which did succeed: {@code context} first -
         * "Committed locally, but the push failed" - then git's reason. Gives the verdict.
         */
        void reportAfter(String context, GitResult result);

        /**
         * Gives {@code operation} its verdict from {@code result}, unless something said
         * since its idle already has; shows nothing.
         */
        void settle(Object operation, GitResult result);

        /** {@code operation}'s work was cancelled before it started: says so, quietly. */
        void cancelled(Object operation);
    }

    /** Where an operation's git work runs: the tab's repository lane. */
    interface Lane {
        /**
         * Runs {@code work}; {@code claim} takes its task before it can start, and then
         * exactly one of {@code done} and {@code dropped} is called on the FX thread.
         */
        void run(Supplier<GitResult> work, Consumer<GitTaskRunner.Task> claim,
                 Consumer<GitResult> done, Runnable dropped);

        /** {@link #run} for a read: {@code failed} takes what an exception said. */
        <T> void read(Supplier<T> work, Consumer<GitTaskRunner.Task> claim,
                      Consumer<T> done, Consumer<String> failed, Runnable dropped);
    }

    /** The tab an operation changed: what it redraws, and what it remembers of the repository. */
    public interface Screen {
        /** Redraws {@code scope}; nothing for {@link Refresh#NONE}. */
        void refresh(Refresh scope);

        /**
         * Records the repository as an operation with {@code scope} has just left it, so the
         * check made on returning to the window does not reload for the application's own
         * change. Asked just before the refresh, so it is read ahead of what the refresh
         * reads; {@link Refresh#EVERYTHING} records all of it, {@link Refresh#STAGING} only
         * the working tree and the index, which is all it redraws, and {@link Refresh#NONE}
         * nothing.
         */
        void recordOwnChange(Refresh scope);
    }

    /**
     * One operation as its caller drives it: lane steps, with whatever the caller decides on
     * the FX thread between them, until {@link #finish}. Every step after the operation has
     * ended is ignored.
     */
    public interface Steps {
        /** Runs {@code work} on the lane, then hands its result to {@code then} on the FX thread. */
        void run(Supplier<GitResult> work, Consumer<GitResult> then);

        /**
         * Reads {@code work} on the lane, then hands the value to {@code then} on the FX
         * thread; an exception ends the operation as failed.
         */
        <T> void read(Supplier<T> work, Consumer<T> then);

        /** Ends the operation with {@code result}: reported, hooks run, refreshed. */
        void finish(GitResult result);
    }

    private final GitBackend repository;
    private final StatusBar statusBar;
    private final Feedback feedback;
    private final Lane lane;
    private final Screen screen;
    private final AutoStash.Asker asker;

    /**
     * @param repository the tab's backend: every operation's work runs on its lane
     * @param statusBar  the tab's status bar, where operations are shown and reported
     * @param screen     the tab, which redraws and records after each operation
     */
    public OperationRunner(GitBackend repository, StatusBar statusBar, Screen screen) {
        this(repository, statusBar, statusBar.operations(), laneOf(repository), screen, AutoStashQuestion::ask);
    }

    /** For tests: every seam given, and no status bar for messages that are not operations. */
    OperationRunner(GitBackend repository, StatusBar statusBar, Feedback feedback, Lane lane, Screen screen,
                    AutoStash.Asker asker) {
        this.repository = repository;
        this.statusBar = statusBar;
        this.feedback = Objects.requireNonNull(feedback);
        this.lane = Objects.requireNonNull(lane);
        this.screen = Objects.requireNonNull(screen);
        this.asker = Objects.requireNonNull(asker);
    }

    /**
     * A runner whose only refresh is {@code everything}, for a part built on its own - in a
     * test, or a window apart from any tab - with nothing recorded.
     *
     * <p>{@link Refresh#STAGING} redraws nothing here. That suits a part with no staging
     * panel of its own, but not the panel itself: before there was a runner it redrew
     * itself after a stage, a hunk or a resolve, and built on this it does not, so a test
     * of those redraws needs a tab's runner.
     */
    public static OperationRunner of(GitBackend repository, StatusBar statusBar, Runnable everything) {
        return new OperationRunner(repository, statusBar, new Screen() {
            @Override
            public void refresh(Refresh scope) {
                if (scope == Refresh.EVERYTHING) everything.run();
            }

            @Override
            public void recordOwnChange(Refresh scope) {
            }
        });
    }

    /** The repository the operations change; parts of the tab read it directly too. */
    public GitBackend repository() {
        return repository;
    }

    /** Where the tab says things that are not operations: a copied hash, a refused input. */
    public StatusBar statusBar() {
        return statusBar;
    }

    /**
     * Redraws {@code scope} with nothing run: for a change that is not git work - a ref
     * hidden from the graph, a file created from the file tree, the WIP row going once the
     * tree is clean. Nothing is recorded: no operation ran.
     */
    public void refresh(Refresh scope) {
        screen.refresh(scope);
    }

    /** Runs {@code work} as an operation called {@code label}, then redraws {@code refresh}. */
    public void run(String label, Refresh refresh, Supplier<GitResult> work) {
        operation(label, refresh).run(work);
    }

    /** An operation called {@code label} that redraws {@code refresh}, to add hooks to before it runs. */
    public Operation operation(String label, Refresh refresh) {
        return new Operation(label, refresh);
    }

    /**
     * Reads something the user is waiting for before they can go on - what a dialog will
     * show, what a force push would overwrite - with the status bar busy meanwhile. An
     * exception is reported; nothing is refreshed.
     */
    public <T> void read(String label, Supplier<T> work, Consumer<T> then) {
        read(label, work, value -> null, then);
    }

    /**
     * {@link #read(String, Supplier, Consumer)}, for a read whose answer can mean the user
     * cannot go on - no lease to force with, a range that cannot be rebased.
     * {@code problem} says what is wrong with the value, or null; a problem is the read's
     * failure, reported as one, and {@code then} is not called. The verdict is given
     * before {@code then} runs, since what it opens - a dialog - may wait, and whatever
     * finishes meanwhile must not take this read's verdict.
     */
    public <T> void read(String label, Supplier<T> work, java.util.function.Function<T, String> problem,
                         Consumer<T> then) {
        Object shown = feedback.busy(label);
        lane.read(work, task -> feedback.claim(shown, task),
                value -> {
                    feedback.idle(shown);
                    String wrong = problem.apply(value);
                    if (wrong != null) {
                        feedback.report(GitResult.failed(wrong));
                        return;
                    }
                    feedback.settle(shown, GitResult.ok());
                    then.accept(value);
                },
                error -> {
                    feedback.idle(shown);
                    feedback.report(GitResult.failed(error));
                },
                () -> {
                    feedback.idle(shown);
                    feedback.cancelled(shown);
                });
    }

    /** An operation being described; nothing runs until {@link #run} or {@link #start}. */
    public final class Operation {

        private final String label;
        private final Refresh refresh;
        private final List<Consumer<GitResult>> beforeRefresh = new ArrayList<>();
        private final List<Consumer<GitResult>> afterwards = new ArrayList<>();
        private Predicate<GitResult> showFailure;
        private Consumer<GitResult> showSuccess;
        private String failureContext;
        private Predicate<GitResult> redraws;

        private Operation(String label, Refresh refresh) {
            this.label = Objects.requireNonNull(label);
            this.refresh = Objects.requireNonNull(refresh);
        }

        /** Runs {@code hook} when the operation succeeds: after its report, before the refresh. */
        public Operation onSuccess(Consumer<GitResult> hook) {
            Objects.requireNonNull(hook);
            return onResult(result -> {
                if (result.isOk()) hook.accept(result);
            });
        }

        /**
         * Runs {@code hook} after the report and before the refresh, however the operation
         * went - cancelled included, with a failed result.
         */
        public Operation onResult(Consumer<GitResult> hook) {
            beforeRefresh.add(Objects.requireNonNull(hook));
            return this;
        }

        /**
         * Refreshes - and records - only for a result {@code changed} says left something
         * to redraw: for an operation whose failures are known to change nothing, so the
         * tab is not reloaded for them. By default every result is refreshed for.
         */
        public Operation refreshWhen(Predicate<GitResult> changed) {
            redraws = Objects.requireNonNull(changed);
            return this;
        }

        /** Runs {@code hook} last, after the refresh, however the operation went. */
        public Operation afterwards(Consumer<GitResult> hook) {
            afterwards.add(Objects.requireNonNull(hook));
            return this;
        }

        /**
         * Shows a failure {@code shown}'s way instead of in the status bar - a dialog, an
         * error under a field - answering whether it did: one it did not show (the form it
         * belonged to was closed meanwhile) goes to the status bar after all. Either way the
         * activity log records it as failed, and the refresh still follows.
         */
        public Operation showFailure(Predicate<GitResult> shown) {
            showFailure = Objects.requireNonNull(shown);
            return this;
        }

        /**
         * Reports a failure after {@code context}, which says what had already been done -
         * "Committed locally, but the push failed" - so git's reason alone does not read as
         * though nothing had worked.
         */
        public Operation reportFailureAfter(String context) {
            failureContext = Objects.requireNonNull(context);
            return this;
        }

        /**
         * Shows a success {@code show}'s way instead of plainly - "Deleted x · Undo". What
         * it says becomes the log's verdict; when it says nothing, the verdict is OK.
         */
        public Operation showSuccess(Consumer<GitResult> show) {
            showSuccess = show;
            return this;
        }

        /** Runs {@code work} as the whole operation. */
        public void run(Supplier<GitResult> work) {
            start(steps -> steps.run(work, steps::finish));
        }

        /**
         * Runs {@code work}, offering auto-stash when local changes are in the way: the
         * question is asked between the refused attempt and the stash, with the busy
         * indicator still up and the lane free.
         */
        public void withAutoStash(AutoStash.Operation kind, Supplier<GitResult> work) {
            start(steps -> AutoStash.run(repository, steps::run, kind, asker, work, steps::finish));
        }

        /** Starts the operation and hands its steps to {@code flow}. */
        public void start(Consumer<Steps> flow) {
            new Running(this).begin(flow);
        }
    }

    /** One operation from busy to its last hook. */
    private final class Running implements Steps {

        private final Operation spec;
        private Object shown;
        private boolean ended;

        Running(Operation spec) {
            this.spec = spec;
        }

        void begin(Consumer<Steps> flow) {
            shown = feedback.busy(spec.label);
            proceed(flow, this);
        }

        @Override
        public void run(Supplier<GitResult> work, Consumer<GitResult> then) {
            if (stepAfterEnd()) return;
            lane.run(work, task -> feedback.claim(shown, task), result -> proceed(then, result), this::dropped);
        }

        @Override
        public <T> void read(Supplier<T> work, Consumer<T> then) {
            if (stepAfterEnd()) return;
            lane.read(work, task -> feedback.claim(shown, task), value -> proceed(then, value),
                    error -> finish(GitResult.failed(error)), this::dropped);
        }

        @Override
        public void finish(GitResult result) {
            if (ended) return;
            end(Objects.requireNonNull(result), false);
        }

        private boolean stepAfterEnd() {
            if (ended) LOGGER.log(Level.WARNING, "A step of \"" + spec.label + "\" came after it ended");
            return ended;
        }

        /** Hands {@code value} to the caller's next decision; a decision that throws ends the operation. */
        private <T> void proceed(Consumer<T> next, T value) {
            if (ended) return;
            try {
                next.accept(value);
            } catch (RuntimeException | LinkageError e) {
                if (!ended) end(GitResult.failed(describe(e)), false);
                throw e;
            }
        }

        private void dropped() {
            if (ended) return;
            end(GitResult.failed(spec.label + " was cancelled before it started"), true);
        }

        /**
         * Each part in a guard of its own: a report or a hook that throws must not skip the
         * hooks after it - one of them is what unlocks the toolbar - nor the refresh. The
         * first exception is rethrown once everything has run.
         */
        private void end(GitResult result, boolean cancelled) {
            ended = true;
            feedback.idle(shown);
            RuntimeException first = null;
            try {
                if (cancelled) {
                    feedback.cancelled(shown);
                } else if (result.getStatus() == GitResult.Status.FAILED && spec.showFailure != null) {
                    // Judged first: the caller's dialog may wait, and whatever finishes
                    // meanwhile must not take this operation's verdict.
                    feedback.settle(shown, result);
                    if (!spec.showFailure.test(result)) feedback.show(result);
                } else if (result.getStatus() == GitResult.Status.FAILED && spec.failureContext != null) {
                    feedback.reportAfter(spec.failureContext, result);
                } else if (result.isOk() && spec.showSuccess != null) {
                    spec.showSuccess.accept(result);
                    feedback.settle(shown, result);
                } else {
                    feedback.report(result);
                }
            } catch (RuntimeException e) {
                first = e;
            }
            first = runAll(spec.beforeRefresh, result, first);
            try {
                if (spec.redraws == null || spec.redraws.test(result)) {
                    // Recorded first, queued ahead of the refresh's reads: a change made
                    // elsewhere after those reads would otherwise be recorded as seen and
                    // never drawn. One made between the record and the reads is drawn,
                    // and the next check reloads once for it - the harmless way round.
                    screen.recordOwnChange(spec.refresh);
                    screen.refresh(spec.refresh);
                }
            } catch (RuntimeException e) {
                if (first == null) first = e;
            }
            first = runAll(spec.afterwards, result, first);
            if (first != null) throw first;
        }
    }

    /** Runs every hook with {@code result}; returns {@code first}, or the first a hook threw. */
    private static RuntimeException runAll(List<Consumer<GitResult>> hooks, GitResult result,
                                           RuntimeException first) {
        for (Consumer<GitResult> hook : hooks) {
            try {
                hook.accept(result);
            } catch (RuntimeException e) {
                if (first == null) first = e;
            }
        }
        return first;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    /** The lane of the tab's own repository. */
    private static Lane laneOf(GitBackend repository) {
        return new Lane() {
            @Override
            public void run(Supplier<GitResult> work, Consumer<GitTaskRunner.Task> claim,
                            Consumer<GitResult> done, Runnable dropped) {
                GitTaskRunner.run(repository, work, done, task -> {
                    claim.accept(task);
                    task.onDropped(dropped);
                });
            }

            @Override
            public <T> void read(Supplier<T> work, Consumer<GitTaskRunner.Task> claim,
                                 Consumer<T> done, Consumer<String> failed, Runnable dropped) {
                GitTaskRunner.query(repository, work, done, failed, task -> {
                    claim.accept(task);
                    task.onDropped(dropped);
                });
            }
        };
    }
}
