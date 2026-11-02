package com.jfx.git.ui.syntax;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where syntax highlighting runs: one thread of its own, off the FX thread.
 *
 * <p>Highlighting used to run on the FX thread, on every render and after every pause in
 * typing, so a large file - or a grammar's first use, which compiles it - held the window
 * still while it worked. Here the text is rendered plain at once and the colours follow.
 *
 * <p>One thread, not a pool, and not the git lane. One, because TM4E does not promise
 * that a grammar can be used from two threads at once, and everything that lexes goes
 * through here. Not the git lane, because colouring a file must not wait behind a fetch.
 *
 * <p>Callers are responsible for recognising a stale answer: a result is delivered to
 * whatever asked, and the asker checks that what it asked about is still what is on
 * screen.
 */
public final class HighlightLane {

    private static final Logger LOGGER = Logger.getLogger(HighlightLane.class.getName());

    private static final ExecutorService LANE = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "syntax-highlighter");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Runs the work inline instead, delivering at once. Set for the test suite (see the
     * surefire configuration), whose assertions read the colours straight after a render.
     */
    private static volatile boolean synchronous = Boolean.getBoolean("lyra.syntax.synchronous");

    private HighlightLane() {
    }

    /**
     * The newest request per key. Weak, so a surface that is gone does not stay reachable
     * through the lane: the text areas already had one leak of exactly that kind.
     */
    private static final java.util.Map<Object, Long> LATEST =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.concurrent.atomic.AtomicLong TOKENS =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Like {@link #submit(Supplier, Consumer)}, but only the newest request for {@code key}
     * is computed.
     *
     * <p>Every pause in typing asks for the whole document again. Queued one behind the
     * other, a large file's requests were each computed in full only to be thrown away as
     * stale on arrival, and the colours fell further behind the longer the user typed.
     * A request superseded before its turn now costs nothing.
     */
    public static <T> void submit(Object key, Supplier<T> work, Consumer<T> onFx) {
        long token = TOKENS.incrementAndGet();
        LATEST.put(key, token);
        submit(() -> {
            Long newest = LATEST.get(key);
            return newest != null && newest != token ? null : new Holder<>(work.get());
        }, holder -> {
            if (holder != null) {
                onFx.accept(holder.value());
            }
        });
    }

    /**
     * Where streamed work hands each partial result; see {@link #stream}.
     *
     * @param <T> what one part is
     */
    public interface Sink<T> {
        /** Delivers {@code part} to the FX thread, after every part published before it. */
        void publish(T part);

        /**
         * Whether a newer request for the same key has arrived, so that what is still to
         * be computed is wanted by nobody. Work checks this between parts and stops.
         */
        boolean superseded();
    }

    /**
     * Runs {@code work} on the lane, letting it publish its result in parts as it goes,
     * each delivered to {@code onFx} on the FX thread in the order published.
     *
     * <p>For work too long to hand over in one piece: colouring a 50,000-line file whole
     * kept every colour off the screen until the last line was lexed. Published a chunk at
     * a time, the first rows are coloured while the rest are still being lexed.
     *
     * <p>Like {@link #submit(Object, Supplier, Consumer)}, a newer request for {@code key}
     * supersedes this one: a request not yet started never starts, and a running one sees
     * {@link Sink#superseded()} turn true. Parts already published are still delivered,
     * so the receiver checks, as every receiver here does, that what it asked about is
     * still on screen. Synchronous mode runs the work and every delivery inline.
     */
    public static <T> void stream(Object key, Consumer<Sink<T>> work, Consumer<T> onFx) {
        long token = TOKENS.incrementAndGet();
        LATEST.put(key, token);
        Sink<T> sink = new Sink<>() {
            @Override
            public void publish(T part) {
                if (synchronous) {
                    onFx.accept(part);
                } else {
                    Platform.runLater(() -> onFx.accept(part));
                }
            }

            @Override
            public boolean superseded() {
                Long newest = LATEST.get(key);
                return newest != null && newest != token;
            }
        };
        Runnable run = () -> {
            if (sink.superseded()) {
                return;
            }
            try {
                work.accept(sink);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Syntax highlighting failed", e);
            }
        };
        if (synchronous) {
            run.run();
        } else {
            LANE.execute(run);
        }
    }

    /** Tells "superseded, nothing computed" apart from a computed null. */
    private record Holder<T>(T value) {
    }

    /**
     * Computes {@code work} on the lane and hands the result to {@code onFx} on the FX
     * thread. A failure is logged and delivers nothing: colours are a nicety, and the text
     * is already on screen without them.
     */
    public static <T> void submit(Supplier<T> work, Consumer<T> onFx) {
        if (synchronous) {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Syntax highlighting failed", e);
                return;
            }
            onFx.accept(result);
            return;
        }
        LANE.execute(() -> {
            T result;
            try {
                result = work.get();
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Syntax highlighting failed", e);
                return;
            }
            Platform.runLater(() -> onFx.accept(result));
        });
    }

    /** Whether results arrive inline; see {@link #synchronous}. */
    public static boolean isSynchronous() {
        return synchronous;
    }

    /** Switches between inline and background delivery. For tests. */
    public static void setSynchronousForTest(boolean on) {
        synchronous = on;
    }

    /**
     * Waits until everything submitted so far has been computed and its delivery queued
     * on the FX thread. For tests, from a thread other than the FX thread.
     */
    public static void awaitIdleForTest() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        LANE.execute(done::countDown);
        if (!done.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the syntax lane did not go idle");
        }
    }
}
