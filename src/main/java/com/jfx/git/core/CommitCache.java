package com.jfx.git.core;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Answers about one commit, kept: a commit's files, its diffs, its blobs, its message.
 *
 * <h2>Why these, and why no invalidation</h2>
 * A full commit hash names one content forever - nothing that happens in the repository
 * can change what a given commit's diff or file looked like. So an answer keyed by one is
 * right for as long as it is kept, and the only policy needed is a size limit. Nothing
 * else in the application was kept at all: selecting a commit again, arrowing back through
 * history or reopening a file ran git again for an answer it had already been given.
 *
 * <p>Only full hashes are keys. {@code HEAD}, a branch name or an abbreviation names
 * whichever commit it points at now, and caching by one would serve stale answers.
 *
 * <p>Failures are never kept: an error, an empty answer, or {@code null} is recomputed on
 * the next ask, so a transient failure cannot become permanent. A commit that genuinely
 * changed nothing is cheap to ask about again.
 *
 * <p>Bounded by an estimate of the bytes held, least recently used out first. Plain
 * references rather than soft ones: on this application's large default heap, soft
 * references are close to never cleared, so they would be an unbounded cache in practice.
 */
final class CommitCache {

    /** What a cached diff starts with when it is a failure dressed as a diff. */
    private static final byte[] ERROR_PREFIX = "Error getting diff: ".getBytes(StandardCharsets.UTF_8);

    private record Entry(Object value, long weight) {}

    private final long budget;
    private long held;
    private long hits;
    private long misses;

    private final LinkedHashMap<Object, Entry> entries = new LinkedHashMap<>(256, 0.75f, true);

    CommitCache(long budgetBytes) {
        this.budget = budgetBytes;
    }

    /** Whether {@code hash} is a full object id - the only kind of key this keeps. */
    static boolean isFullHash(String hash) {
        if (hash == null || (hash.length() != 40 && hash.length() != 64)) return false;
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The cached answer for {@code key}, or {@code compute}'s - kept if it is a real one.
     * Synchronised so a stray call from another thread cannot corrupt the order; engine
     * calls themselves are lane-confined, so there is no contention to speak of.
     */
    @SuppressWarnings("unchecked")
    <T> T get(Object key, Supplier<T> compute) {
        synchronized (this) {
            Entry hit = entries.get(key);
            if (hit != null) {
                hits++;
                return (T) hit.value();
            }
            misses++;
        }
        T value = compute.get();
        long weight = weightOf(value);
        if (weight < 0 || weight > budget / 4) {
            return value;   // a failure, or one answer too big to be worth keeping
        }
        synchronized (this) {
            Entry previous = entries.put(key, new Entry(value, weight));
            if (previous != null) held -= previous.weight();
            held += weight;
            var it = entries.entrySet().iterator();
            while (held > budget && it.hasNext()) {
                held -= it.next().getValue().weight();
                it.remove();
            }
        }
        return value;
    }

    /** Forgets everything - the tab closed, or its repository is gone. */
    synchronized void clear() {
        entries.clear();
        held = 0;
    }

    /** "hits/misses, n entries, bytes" - for the log, and for judging whether it pays. */
    synchronized String describe() {
        return hits + " hits / " + misses + " misses, " + entries.size() + " entries, "
                + (held >> 10) + " KB";
    }

    /**
     * Roughly how much {@code value} holds, or -1 when it is not an answer worth keeping:
     * {@code null}, empty, or a failure.
     */
    private static long weightOf(Object value) {
        if (value == null) return -1;
        if (value instanceof byte[] bytes) {
            if (bytes.length == 0 || startsWith(bytes, ERROR_PREFIX)) return -1;
            return 64L + bytes.length;
        }
        if (value instanceof byte[][] sides) {
            long total = 64;
            for (byte[] side : sides) total += side == null ? 0 : side.length;
            return total;
        }
        if (value instanceof String text) {
            return text.isEmpty() ? -1 : 64L + 2L * text.length();
        }
        if (value instanceof List<?> list) {
            // A changed-files row or a blame line: a path, a few fields, an object header.
            return list.isEmpty() ? -1 : 64L + 160L * list.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty() ? -1 : 64L + 160L * map.size();
        }
        return 256;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) return false;
        }
        return true;
    }
}
