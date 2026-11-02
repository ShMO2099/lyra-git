package com.jfx.git.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * What the commit box remembers per repository: the messages recently committed there,
 * and the one being written now.
 *
 * <h2>Why</h2>
 * The composer kept its text across panel rebuilds but not across the tab: closing a
 * repository, or the application, with half a message typed threw it away. And the
 * message just committed - the one wanted again after an amend went wrong, or for the
 * same change on another branch - was only to be had by copying it out of the graph.
 *
 * <h2>Where</h2>
 * One JSON file, {@value #FILE_NAME}, beside {@link Settings#file()}, so it follows the
 * {@code -Dlyra.settings} override and the tests write nowhere near a real profile.
 * Not in {@code settings.properties} itself: that file escapes every newline, and a
 * draft is rewritten at every pause in typing.
 *
 * <h2>How it is written</h2>
 * Memory first, disk after a quiet moment, on a thread of its own - the same coalescing
 * {@link Settings} uses, since a draft changes with every key. A save rewrites only the
 * repositories this process changed, over the file as it is on disk then, so a second
 * window does not erase the first one's history.
 */
public final class CommitMessages {

    private static final Logger LOGGER = Logger.getLogger(CommitMessages.class.getName());

    static final String FILE_NAME = "commit-messages.json";

    /** How many committed messages a repository keeps. */
    public static final int HISTORY_LIMIT = 20;

    /**
     * How many repositories the file keeps, most recently used first. Temporary clones
     * and deleted checkouts would otherwise accumulate for ever.
     */
    static final int MAX_REPOSITORIES = 100;

    /** A draft's settling time before it is written: long enough to cover a burst of typing. */
    private static final long QUIET_MILLIS = 800;

    private static final ScheduledExecutorService WRITER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "commit-messages-writer");
                t.setDaemon(true);
                return t;
            });

    /** One repository's entry. Mutable, and only touched under the class lock. */
    private static final class Entry {
        final List<String> history = new ArrayList<>();
        String draft = "";
        long touched;
    }

    private static Path fileOverride;
    private static Map<String, Entry> entries;
    private static final Set<String> changed = new HashSet<>();
    private static ScheduledFuture<?> pending;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(CommitMessages::flush, "commit-messages-flush"));
    }

    private CommitMessages() {
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** The messages committed in {@code repository}, newest first. */
    public static synchronized List<String> history(File repository) {
        Entry entry = load().get(keyFor(repository));
        return entry == null ? List.of() : List.copyOf(entry.history);
    }

    /** The message left unsent in {@code repository}, or {@code ""}. */
    public static synchronized String draft(File repository) {
        Entry entry = load().get(keyFor(repository));
        return entry == null ? "" : entry.draft;
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Records {@code message} as committed in {@code repository}: first in its history,
     * an earlier copy of the same text removed, the oldest past {@link #HISTORY_LIMIT}
     * dropped. The draft is cleared, since it has just been sent.
     */
    public static synchronized void remember(File repository, String message) {
        if (repository == null || message == null || message.isBlank()) return;
        String text = message.strip();
        Entry entry = entry(repository);
        entry.history.remove(text);
        entry.history.add(0, text);
        while (entry.history.size() > HISTORY_LIMIT) {
            entry.history.remove(entry.history.size() - 1);
        }
        entry.draft = "";
        changed(repository);
    }

    /** Keeps {@code text} as {@code repository}'s unsent message; blank forgets it. */
    public static synchronized void saveDraft(File repository, String text) {
        if (repository == null) return;
        String draft = text == null ? "" : text;
        Entry existing = load().get(keyFor(repository));
        if (existing == null && draft.isBlank()) return;
        Entry entry = existing == null ? entry(repository) : existing;
        if (entry.draft.equals(draft)) return;
        entry.draft = draft;
        changed(repository);
    }

    /** Forgets {@code repository}'s unsent message. */
    public static void clearDraft(File repository) {
        saveDraft(repository, "");
    }

    /** Writes anything pending now. For shutdown, and for tests that read the file. */
    public static synchronized void flush() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        if (changed.isEmpty()) return;
        save();
    }

    // ------------------------------------------------------------------
    // Location
    // ------------------------------------------------------------------

    /** Where the messages are kept: beside the settings file, unless a test says otherwise. */
    public static synchronized Path file() {
        if (fileOverride != null) return fileOverride;
        return Settings.file().resolveSibling(FILE_NAME);
    }

    /**
     * Points the store at another file and forgets what was loaded; {@code null} goes
     * back to the default. Anything pending belongs to the old file and is dropped.
     */
    public static synchronized void useFile(Path path) {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        changed.clear();
        entries = null;
        fileOverride = path;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static Entry entry(File repository) {
        return load().computeIfAbsent(keyFor(repository), k -> new Entry());
    }

    private static void changed(File repository) {
        String key = keyFor(repository);
        Entry entry = load().get(key);
        if (entry != null) entry.touched = System.currentTimeMillis();
        changed.add(key);
        if (pending != null) pending.cancel(false);
        pending = WRITER.schedule(CommitMessages::flush, QUIET_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** One key per repository however it was reached: a junction or a different case is the same one. */
    private static String keyFor(File repository) {
        if (repository == null) return "";
        try {
            return repository.getCanonicalPath();
        } catch (IOException e) {
            return repository.getAbsolutePath();
        }
    }

    private static Map<String, Entry> load() {
        if (entries == null) {
            entries = read(file());
        }
        return entries;
    }

    /** The file's contents now; empty when it is missing or unreadable. */
    private static Map<String, Entry> read(Path path) {
        Map<String, Entry> read = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) return read;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return read;
            JsonElement repositories = root.getAsJsonObject().get("repositories");
            if (repositories == null || !repositories.isJsonObject()) return read;
            for (var item : repositories.getAsJsonObject().entrySet()) {
                if (!item.getValue().isJsonObject()) continue;
                JsonObject stored = item.getValue().getAsJsonObject();
                Entry entry = new Entry();
                if (stored.get("history") instanceof JsonArray history) {
                    for (JsonElement message : history) {
                        if (message.isJsonPrimitive() && entry.history.size() < HISTORY_LIMIT) {
                            entry.history.add(message.getAsString());
                        }
                    }
                }
                if (stored.get("draft") != null && stored.get("draft").isJsonPrimitive()) {
                    entry.draft = stored.get("draft").getAsString();
                }
                if (stored.get("touched") != null && stored.get("touched").isJsonPrimitive()) {
                    entry.touched = stored.get("touched").getAsLong();
                }
                read.put(item.getKey(), entry);
            }
        } catch (IOException | RuntimeException e) {
            // A file nobody can parse is not worth refusing the composer over; the next
            // save replaces it.
            LOGGER.log(Level.WARNING, "Could not read " + path + "; starting without it", e);
        }
        return read;
    }

    private static void save() {
        Path path = file();
        Map<String, Entry> merged = read(path);
        for (String key : changed) {
            Entry ours = entries == null ? null : entries.get(key);
            if (ours == null) {
                merged.remove(key);
            } else {
                merged.put(key, ours);
            }
        }
        changed.clear();
        // The most recently used, up to the cap.
        List<Map.Entry<String, Entry>> ordered = new ArrayList<>(merged.entrySet());
        ordered.sort(Comparator.comparingLong((Map.Entry<String, Entry> e) -> e.getValue().touched).reversed());

        JsonObject repositories = new JsonObject();
        Map<String, Entry> kept = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> item : ordered) {
            Entry entry = item.getValue();
            if (entry.history.isEmpty() && entry.draft.isBlank()) continue;
            if (kept.size() == MAX_REPOSITORIES) break;
            kept.put(item.getKey(), entry);
            JsonObject stored = new JsonObject();
            JsonArray history = new JsonArray();
            entry.history.forEach(history::add);
            stored.add("history", history);
            stored.addProperty("draft", entry.draft);
            stored.addProperty("touched", entry.touched);
            repositories.add(item.getKey(), stored);
        }
        // What another window wrote is visible here from now on, too.
        entries = kept;

        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("repositories", repositories);
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(root),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not write " + path, e);
        }
    }
}
