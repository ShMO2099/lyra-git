package com.jfx.git.core;

import javafx.application.Platform;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Notices when expensive git work happens on the thread that draws.
 *
 * <h2>Why a mechanism rather than a rule</h2>
 * "All git operations serialize through a single background thread" is the project's
 * cardinal threading rule, and for three review rounds it was checked by reading call
 * sites. That found the pattern of a call, not the thread it ran on, so it kept passing
 * while the rule quietly stopped holding: the commit button ran a full {@code git status}
 * per click, and the graph's search box spawned a {@code git} subprocess per character
 * typed - both on the FX thread, on the default engine, in the hottest paths in the
 * application. A frozen UI is the symptom, and nothing in the build said a word.
 *
 * <p>So the invariant gets a tripwire. It sits where the cost actually is - the subprocess
 * spawn in {@link NativeGitRunner} - rather than on every method of every backend, because
 * that is the call that takes tens to hundreds of milliseconds and cannot be made cheap.
 *
 * <h2>What it does</h2>
 * By default it <em>reports</em>: one warning per offending call site, naming the command
 * and where it came from, and never more than once for the same site. It does not throw,
 * because a freeze is bad and an exception thrown into a user's half-finished merge is
 * worse.
 *
 * <p>Under {@link #strict(boolean)} - which tests turn on, and the {@code
 * lyra.strictThreading} system property also sets - it throws instead. That is what
 * turns the rule into something a test can hold: drive the paths that must stay off the
 * FX thread, and a regression fails the build rather than waiting to be read.
 *
 * <p>The same tripwire holds the other half of the rule, now that every repository has a
 * lane of its own: an engine called from <em>another</em> repository's lane is reported
 * the same way; see {@link #engineCall}.
 */
public final class ThreadGuard {

    private static final Logger LOGGER = Logger.getLogger(ThreadGuard.class.getName());

    /** Call sites already reported, so a hot path warns once rather than per frame. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * Violations seen since the last {@link #clear()}, for tests to assert on - every one
     * in strict mode, the first {@link #RECORD_LIMIT} otherwise.
     */
    private static final List<Violation> VIOLATIONS = new CopyOnWriteArrayList<>();

    /**
     * How many violations a non-strict run keeps. The list exists for tests, which clear
     * it per test; in the application it was only ever appended to, so a call site on a
     * per-frame path grew it - a copy-on-write list, copied whole on every add - for as
     * long as the window stayed open. The first two hundred say everything the rest
     * would.
     */
    static final int RECORD_LIMIT = 200;

    /**
     * Whether this thread is currently building a test fixture.
     *
     * <p>A robot test's {@code start(Stage)} runs on the FX thread and builds its
     * repository there - {@code git init}, {@code config}, {@code add}, {@code commit}.
     * That is git on the FX thread and the guard is right to see it, but it is a fixture
     * being assembled before anything is under test, not the application freezing itself.
     * Left in the log it buried the four real product violations under ten fixture ones,
     * which is how a guard stops being read.
     *
     * <p>Per-thread rather than global so a scope opened on the FX thread cannot excuse
     * anything happening on a worker at the same moment.
     */
    private static final ThreadLocal<Boolean> BUILDING_FIXTURE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Test scaffolding that does not end in {@code Test}.
     *
     * <p>Named explicitly because the alternative is silence: a harness that runs git on
     * the FX thread while setting a test up would be reported as an application defect,
     * in whichever unrelated test happened to be running. Anything added here should
     * really be reaching for {@link #buildingFixture()} instead.
     */
    private static final Set<String> TEST_SUPPORT = Set.of(
            "com.jfx.git.ui.FxHarness",
            "com.jfx.git.testsupport.TestEnvironment");

    private static volatile boolean strict =
            Boolean.getBoolean("lyra.strictThreading");

    private ThreadGuard() {
    }

    /** Whether a violation should throw rather than be logged. Tests turn this on. */
    public static void strict(boolean enabled) {
        strict = enabled;
    }

    public static boolean isStrict() {
        return strict;
    }

    /**
     * One recorded violation.
     *
     * @param operation what ran, e.g. {@code "git status"}
     * @param caller    the frame that asked for it - the code to go and fix
     * @param where     the thread it should not have run on: {@link #FX_THREAD}, or
     *                  another repository's lane
     */
    public record Violation(String operation, String caller, String where) {

        /** Where most violations happen, and what this guard was first built for. */
        public static final String FX_THREAD = "the JavaFX Application Thread";

        /** Git work on the FX thread. */
        public Violation(String operation, String caller) {
            this(operation, caller, FX_THREAD);
        }

        public String message() {
            if (FX_THREAD.equals(where)) {
                return operation + " ran on the JavaFX Application Thread, from " + caller
                        + ". Git work belongs on a GitTaskRunner lane - the UI is frozen for"
                        + " its whole duration.";
            }
            return operation + " ran on " + where + ", from " + caller
                    + ". A repository's engine is called from its own GitTaskRunner lane"
                    + " only - work submitted for one repository that reaches into another"
                    + " runs alongside that repository's own work. Submit it naming the"
                    + " repository it touches.";
        }

        /**
         * Whether this came from the application rather than from test scaffolding.
         *
         * <p>Drawn from the frame that actually asked. A test that drives the application
         * still produces a product frame here - the first frame outside the git core is
         * the UI class that made the call, with the test further down the stack - which is
         * what makes the rule enforceable from a test at all.
         *
         * <p><b>This is a backstop, not the mechanism.</b> It recognises scaffolding by
         * name, and a name is a guess: a class ending in {@code Test} is one, and so are
         * the harness classes listed below, but a fixture helper called something else
         * would be read as product and fail an unrelated test with a confusing message.
         * Fixture code should say so with {@link ThreadGuard#buildingFixture()}, which is
         * checked before anything reaches here. The heuristic only catches what forgot to.
         */
        public boolean isProductCode() {
            if (!caller.startsWith("com.jfx.git.")) {
                return false;
            }
            String className = className();
            return !className.endsWith("Test") && !TEST_SUPPORT.contains(className);
        }

        /** {@code pkg.Class} out of {@code pkg.Class.method(File.java:12)}. */
        private String className() {
            int parenthesis = caller.indexOf('(');
            String qualified = parenthesis < 0 ? caller : caller.substring(0, parenthesis);
            int lastDot = qualified.lastIndexOf('.');
            return lastDot < 0 ? qualified : qualified.substring(0, lastDot);
        }
    }

    /** What has been seen since {@link #clear()}, oldest first. */
    public static List<Violation> violations() {
        return List.copyOf(VIOLATIONS);
    }

    /** Violations that came from application code - what a build rule should refuse. */
    public static List<Violation> productViolations() {
        return VIOLATIONS.stream().filter(Violation::isProductCode).toList();
    }

    /**
     * Marks this thread as building a test fixture until the returned scope is closed.
     *
     * <p>For a robot test's {@code start(Stage)}, which runs on the FX thread and has to
     * create a repository before there is anything to test. Use it around fixture setup
     * only - never around the code under test, which is the whole point of the guard.
     *
     * <pre>{@code
     * try (var fixture = ThreadGuard.buildingFixture()) {
     *     git.init()...;
     * }
     * }</pre>
     */
    public static AutoCloseable buildingFixture() {
        boolean previous = BUILDING_FIXTURE.get();
        BUILDING_FIXTURE.set(Boolean.TRUE);
        return () -> BUILDING_FIXTURE.set(previous);
    }

    /** Forgets both the recorded violations and which sites have already warned. */
    public static void clear() {
        VIOLATIONS.clear();
        REPORTED.clear();
    }

    /**
     * Forgets the recorded violations, keeping the record of which sites have warned.
     *
     * <p>For a per-test reset. Clearing {@link #REPORTED} too would undo warn-once and
     * make every test log every site again - and the warning is emitted on the FX thread
     * with a stack walk behind it, so a guard doing that would be changing the timing of
     * the very thing it is meant to observe.
     */
    public static void clearViolations() {
        VIOLATIONS.clear();
    }

    /**
     * Records - or, in strict mode, refuses - expensive work on the FX thread.
     *
     * @param operation what is about to happen, for the message: {@code "git status"}
     * @throws IllegalStateException in strict mode, when called on the FX thread
     */
    public static void offTheFxThread(String operation) {
        if (!isOnFxThread() || BUILDING_FIXTURE.get()) {
            return;
        }
        report(operation, callerOutsideCore());
    }

    private static void report(String operation, String caller) {
        report(new Violation(operation, caller));
    }

    private static void report(Violation violation) {
        if (strict || VIOLATIONS.size() < RECORD_LIMIT) {
            VIOLATIONS.add(violation);
        }
        if (strict) {
            throw new IllegalStateException(violation.message());
        }
        if (REPORTED.add(violation.caller() + " :: " + violation.operation())) {
            LOGGER.warning(violation.message());
        }
    }

    /**
     * {@link #offTheFxThread} for a call into a repository's engine, named by the
     * {@link GitBackend} method being called.
     *
     * <p>The subprocess check above only ever saw the CLI engine. A JGit or libgit2 call on
     * the FX thread - the graph, diffs and blobs on every composed engine, all of JGit -
     * spawns nothing, so it went unnoticed however long it held the thread. This is called
     * from the one indirection every UI call passes through, so every engine is covered.
     * The stack is only walked once the cheap thread check has already failed, and only
     * once: the backend method and the caller come out of the same pass, where this used
     * to walk it a second time inside {@link #offTheFxThread}.
     */
    public static void engineCallOffTheFxThread() {
        if (!isOnFxThread() || BUILDING_FIXTURE.get()) {
            return;
        }
        String[] found = engineMethodAndCaller();
        report("GitBackend." + (found[0] == null ? "call" : found[0]),
                found[1] == null ? "an unknown caller" : found[1]);
    }

    /**
     * {@link #engineCallOffTheFxThread}, and a check that {@code repository}'s engine is
     * not being called from another repository's lane.
     *
     * <h2>Why</h2>
     * Every open repository has a {@link GitTaskRunner} lane of its own, and an engine is
     * safe only because every call into it queues on that one lane. Work submitted naming
     * one repository that reaches into another's engine runs alongside the other's own
     * lane - two threads in one engine, which nothing would report until a status came
     * back corrupt. A lane thread knows which repository its task was submitted for, so
     * the mismatch is caught at the call. A thread that is no lane at all - a test
     * driving a backend directly - is left alone.
     */
    public static void engineCall(GitBackend repository) {
        engineCallOffTheFxThread();
        GitBackend lane = GitTaskRunner.laneRepository();
        if (lane == null || lane == repository) {
            return;
        }
        String[] found = engineMethodAndCaller();
        report(new Violation("GitBackend." + (found[0] == null ? "call" : found[0]),
                found[1] == null ? "an unknown caller" : found[1],
                "another repository's lane (" + Thread.currentThread().getName() + ")"));
    }

    /**
     * The {@link GitBackend} method being called, and the first frame outside the git
     * core that led to it, out of one pass over the stack.
     */
    private static String[] engineMethodAndCaller() {
        String delegating = DelegatingGitBackend.class.getName();
        return StackWalker.getInstance().walk(frames -> {
            String method = null;
            String caller = null;
            for (var it = frames.iterator(); it.hasNext() && (method == null || caller == null); ) {
                StackWalker.StackFrame f = it.next();
                if (method == null && f.getClassName().equals(delegating)) {
                    method = f.getMethodName();
                }
                if (caller == null && !f.getClassName().startsWith("com.jfx.git.core.")) {
                    caller = describe(f);
                }
            }
            return new String[]{method, caller};
        });
    }

    /**
     * Whether this is the FX thread, tolerating a toolkit that was never started.
     *
     * <p>{@link Platform#isFxApplicationThread()} is safe to call without a toolkit, but
     * the surrounding code runs in plain unit tests too and a guard is not worth an
     * exception from anywhere.
     */
    private static boolean isOnFxThread() {
        try {
            return Platform.isFxApplicationThread();
        } catch (Throwable noToolkit) {
            return false;
        }
    }

    /**
     * The first frame that is not this class or the git core - the UI code that made the
     * call, which is the thing the reader has to go and fix.
     */
    private static String callerOutsideCore() {
        return StackWalker.getInstance()
                .walk(frames -> frames
                        .filter(f -> !f.getClassName().startsWith("com.jfx.git.core."))
                        .map(ThreadGuard::describe)
                        .findFirst()
                        .orElse("an unknown caller"));
    }

    private static String describe(StackWalker.StackFrame f) {
        return f.getClassName() + "." + f.getMethodName()
                + "(" + f.getFileName() + ":" + f.getLineNumber() + ")";
    }
}
