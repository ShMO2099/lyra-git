package com.jfx.git.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Unsaved editor buffers, kept on disk so a crash does not take them with it.
 *
 * <h2>Why a directory of files rather than a settings key</h2>
 * {@link Settings} is a {@code java.util.Properties} file: it escapes every newline, and
 * every write rewrites the whole thing. A 400 KB buffer saved every couple of seconds
 * through that would thrash it, and a crash mid-write would corrupt the user's
 * <em>preferences</em> rather than lose a draft. So drafts get their own directory beside
 * it - which also means they inherit {@link Settings#file()}'s {@code -Dlyra.settings}
 * override, and the test suite writes nowhere near a real profile.
 *
 * <h2>Why the format is a header and then the bytes</h2>
 * gson is on the classpath, and JSON was still the wrong answer. Escaping a source file
 * into a JSON string roughly doubles it and makes the result unreadable by hand - and the
 * one moment anyone opens this directory is after a crash, wanting their text back with a
 * text editor. A header terminated by a blank line needs no escaping at all for the part
 * that matters, which is the part that is someone's work.
 *
 * <p>The body is always UTF-8, whatever the document's own encoding. A draft holds decoded
 * text; re-encoding it to the document's charset would make a lossy draft of precisely the
 * files whose encoding is the interesting part. The encoding is recorded in the header so
 * recovery can put the selector back.
 *
 * <h2>What this is not</h2>
 * Not a document store. A draft is a crash artefact: it is deleted the moment the buffer
 * reaches disk by any other route, and swept after {@link #MAX_AGE}. Unbounded plaintext
 * source accumulating under a user's profile is a real if mild privacy problem, which is
 * also why {@link AppPreferences#editorDraftsEnabled()} can turn the whole thing off.
 */
public final class EditorDrafts {

    private static final Logger LOGGER = Logger.getLogger(EditorDrafts.class.getName());

    private static final String DIRECTORY_NAME = "drafts";
    private static final String SUFFIX = ".draft";
    private static final String MAGIC = "lyra-draft";

    /** {@link #MAGIC} in drafts saved before the application was renamed; still read. */
    private static final String LEGACY_MAGIC = "jfxgit-draft";

    /**
     * Beyond this, do not draft.
     *
     * <p>Writing twenty megabytes every two seconds is not a safety feature. Nothing this
     * size is being hand-edited either.
     */
    static final int MAX_DRAFT_CHARS = 4 * 1024 * 1024;

    /** A draft nobody has recovered in a month is not going to be. */
    static final Duration MAX_AGE = Duration.ofDays(30);

    /** Ceilings on the directory itself, swept oldest first. */
    static final int MAX_DRAFTS = 200;

    private static volatile Path directoryOverride;

    /**
     * One thread, so writes cannot overlap, and its own thread rather than
     * {@link GitTaskRunner}'s: that lane is serialised git work, and queueing a draft
     * behind a {@code git log} would be wrong in both directions.
     */
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "draft-writer");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * One buffer waiting for the writer, with the baseline and encoding it belongs to.
     *
     * <p>Kept together because "latest wins" has to mean the latest of all three. The
     * text used to be the latest while the encoding came from whichever queued task ran
     * first, so a draft written after an encoding change could record the old one and be
     * restored under it.
     */
    private record Pending(String repository, String path, String text,
                           String baseline, String encoding) {
    }

    /** Latest wins: only the most recent buffer per draft is worth writing. */
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            WRITER.shutdown();
            try {
                // Bounded. A draft is worth a moment on the way out, not a hung exit.
                WRITER.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "draft-writer-shutdown"));
    }

    private EditorDrafts() {
    }

    /**
     * One unsaved buffer.
     *
     * @param baselineDigest a digest of what the file was when editing began, so recovery
     *                       can tell "your draft" from "your draft, and the file moved too"
     */
    public record Draft(String repository, String path, String text,
                        Instant savedAt, String baselineDigest, String encoding) {
    }

    // ------------------------------------------------------------------
    // Where they live
    // ------------------------------------------------------------------

    /** The drafts directory, beside the settings file unless a test says otherwise. */
    public static Path directory() {
        Path override = directoryOverride;
        if (override != null) {
            return override;
        }
        Path settings = Settings.file();
        Path parent = settings.getParent();
        return parent == null ? Path.of(DIRECTORY_NAME) : parent.resolve(DIRECTORY_NAME);
    }

    /** Points the store somewhere else; {@code null} restores the default. */
    public static void useDirectory(Path dir) {
        directoryOverride = dir;
    }

    // ------------------------------------------------------------------
    // Reading and writing
    // ------------------------------------------------------------------

    /**
     * Records {@code text} as the unsaved state of {@code path}, off the calling thread.
     *
     * <p>Returns immediately. Nothing waits on a draft.
     */
    public static void save(File repository, String path, String text,
                            String baseline, String encoding) {
        if (!AppPreferences.editorDraftsEnabled()
                || repository == null || path == null || path.isBlank() || text == null) {
            return;
        }
        if (text.length() > MAX_DRAFT_CHARS) {
            LOGGER.fine("Buffer too large to draft: " + path);
            return;
        }
        String key = keyFor(repository, path);
        PENDING.put(key, new Pending(repository.getAbsolutePath(), path, text,
                baseline == null ? "" : baseline, encoding));

        WRITER.execute(() -> {
            // Latest wins: if a newer buffer arrived while this was queued, write that
            // one and let the stale task find nothing to do.
            Pending latest = PENDING.remove(key);
            if (latest == null) {
                return;
            }
            // Hashed here, on the writer: the baseline can be megabytes, and this used to
            // run on the FX thread at every pause in typing.
            writeDraft(key, latest.repository(), latest.path(), latest.text(),
                    digest(latest.baseline()), latest.encoding());
        });
    }

    /**
     * Waits for queued writes to reach disk.
     *
     * <p>For tests only. Nothing in the application waits on a draft - the point of the
     * writer thread is that the editor never does - but a test that asserts on the
     * directory has to know the queue is empty first.
     */
    static void awaitQuiet() {
        try {
            WRITER.submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("The draft writer did not settle", e);
        }
    }

    /**
     * Occupies the writer until the returned latch is released, so a test can queue work
     * behind it in a known order. For tests only.
     */
    static java.util.concurrent.CountDownLatch holdWriterForTest() {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        WRITER.execute(() -> {
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return release;
    }

    /** The draft for {@code path}, if there is one this process can read. */
    public static Optional<Draft> find(File repository, String path) {
        if (repository == null || path == null || path.isBlank()) {
            return Optional.empty();
        }
        return read(directory().resolve(keyFor(repository, path) + SUFFIX));
    }

    /** Forgets the draft for {@code path}. Safe when there is none. */
    public static void discard(File repository, String path) {
        if (repository == null || path == null || path.isBlank()) {
            return;
        }
        String key = keyFor(repository, path);
        PENDING.remove(key);
        WRITER.execute(() -> {
            try {
                Files.deleteIfExists(directory().resolve(key + SUFFIX));
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Could not remove a draft", e);
            }
        });
    }

    /**
     * Removes every stored draft.
     *
     * <p>For the moment the user turns drafting off: the switch is usually about not
     * wanting this content on disk, and honouring that has to include what is already
     * there.
     */
    public static void discardAll() {
        PENDING.clear();
        // Every file, not every parseable draft: a .tmp left by a crash mid-write holds
        // the same content, and "delete them" in Preferences has to mean all of it.
        for (Path file : draftFiles(true)) {
            deleteFile(file);
        }
        // And once more on the writer, behind any write already under way. Clearing
        // PENDING stops queued writes, but not one that has already taken its text: that
        // one finished after the sweep above and put a draft back on disk.
        WRITER.execute(() -> {
            for (Path file : draftFiles(true)) {
                deleteFile(file);
            }
        });
    }

    /**
     * How many files the drafts directory holds, crash leftovers included - what
     * {@link #discardAll()} would remove.
     */
    public static int storedFileCount() {
        return draftFiles(true).size();
    }

    /** Every draft currently stored, newest first. */
    public static List<Draft> all() {
        return stored().stream().map(Stored::draft).toList();
    }

    /** A parsed draft together with the file it was read from. */
    private record Stored(Path file, Draft draft) {
    }

    /** Every readable draft, newest first, with the file each came from. */
    private static List<Stored> stored() {
        List<Stored> drafts = new ArrayList<>();
        for (Path file : draftFiles(false)) {
            read(file).ifPresent(draft -> drafts.add(new Stored(file, draft)));
        }
        drafts.sort(Comparator.comparing((Stored s) -> s.draft().savedAt()).reversed());
        return drafts;
    }

    /** The files in the drafts directory; with {@code includeTemp}, crash leftovers too. */
    private static List<Path> draftFiles(boolean includeTemp) {
        Path dir = directory();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var entries = Files.list(dir)) {
            return entries.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith(SUFFIX) || (includeTemp && name.endsWith(SUFFIX + ".tmp"));
            }).toList();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not list drafts", e);
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // Sweeping
    // ------------------------------------------------------------------

    /**
     * Removes drafts older than {@code age}, for repositories that are gone, and anything
     * past {@link #MAX_DRAFTS}.
     *
     * <p>Called once at startup. Off the calling thread, like everything else here.
     */
    public static void sweepInBackground() {
        WRITER.execute(() -> {
            int removed = sweep();
            if (removed > 0) {
                LOGGER.fine("Swept " + removed + " stale editor draft(s)");
            }
        });
    }

    /** @return how many drafts were removed */
    static int sweep() {
        int removed = 0;
        Instant cutoff = Instant.now().minus(MAX_AGE);

        // A .tmp is a write that never completed. It was either replaced by a later write
        // or belongs to a crash, and in both cases nothing will ever read it.
        for (Path file : draftFiles(true)) {
            if (file.getFileName().toString().endsWith(".tmp") && deleteFile(file)) {
                removed++;
            }
        }

        // Deleted by the file each draft was read from. Recomputing the key from the
        // header would not always name it again: the header's repository path is trimmed,
        // and for a repository that is gone, canonicalising it cannot recover the casing
        // the key was made from - so the draft was never removed.
        List<Stored> drafts = stored();
        for (Stored stored : drafts) {
            boolean stale = stored.draft().savedAt().isBefore(cutoff)
                    || repositoryIsGone(stored.draft().repository());
            if (stale && deleteFile(stored.file())) {
                removed++;
            }
        }

        // Whatever is left, oldest first, down to the cap.
        List<Stored> remaining = stored();
        for (int i = MAX_DRAFTS; i < remaining.size(); i++) {
            // stored() is newest first, so everything past the cap is the oldest.
            if (deleteFile(remaining.get(i).file())) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * Whether a draft's repository has been deleted, as opposed to being out of reach.
     *
     * <p>A repository on a removable or network drive is simply absent when that drive is
     * not mounted, and sweeping its drafts at startup would destroy exactly the work the
     * feature exists to keep. So "gone" requires the directory above it to be there: the
     * volume is present and the repository is not. An unreachable one is left to the age
     * limit.
     */
    private static boolean repositoryIsGone(String repository) {
        File dir = new File(repository);
        if (dir.isDirectory()) {
            return false;
        }
        File parent = dir.getParentFile();
        return parent != null && parent.isDirectory();
    }

    private static boolean deleteFile(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not remove a draft file", e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // The file format
    // ------------------------------------------------------------------

    private static void writeDraft(String key, String repository, String path, String text,
                                   String baselineDigest, String encoding) {
        Path dir = directory();
        Path target = dir.resolve(key + SUFFIX);
        Path temp = dir.resolve(key + SUFFIX + ".tmp");
        String document = MAGIC + ": 1\n"
                + "repository: " + oneLine(repository) + "\n"
                + "path: " + oneLine(path) + "\n"
                + "saved: " + Instant.now() + "\n"
                + "baseline: " + baselineDigest + "\n"
                + "encoding: " + oneLine(encoding == null ? "" : encoding) + "\n"
                + "\n"
                + text;
        try {
            Files.createDirectories(dir);
            Files.writeString(temp, document, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not write a draft for " + path, e);
        }
    }

    /**
     * Parses one draft file.
     *
     * <p>The header stops at the first blank line and the rest is the buffer, byte for
     * byte. That is what makes the format safe without escaping: a body line reading
     * {@code path: something} cannot be mistaken for a header, because the parser stopped
     * looking before it got there.
     */
    private static Optional<Draft> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int blank = content.indexOf("\n\n");
            if (blank < 0 || !(content.startsWith(MAGIC + ":") || content.startsWith(LEGACY_MAGIC + ":"))) {
                return Optional.empty();
            }
            String header = content.substring(0, blank);
            String body = content.substring(blank + 2);

            String repository = null;
            String path = null;
            String saved = null;
            String baseline = "";
            String encoding = "";
            for (String line : header.split("\n")) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                switch (name) {
                    case "repository" -> repository = value;
                    case "path" -> path = value;
                    case "saved" -> saved = value;
                    case "baseline" -> baseline = value;
                    case "encoding" -> encoding = value;
                    default -> { }
                }
            }
            if (repository == null || path == null || saved == null) {
                return Optional.empty();
            }
            return Optional.of(new Draft(repository, path, body,
                    Instant.parse(saved), baseline, encoding));
        } catch (IOException | RuntimeException unreadable) {
            // A draft that cannot be parsed is not worth failing over; it is swept later.
            LOGGER.log(Level.FINE, "Ignoring an unreadable draft: " + file, unreadable);
            return Optional.empty();
        }
    }

    /** A filename for (repository, path). A repo-relative path cannot be one itself. */
    private static String keyFor(File repository, String path) {
        String canonical;
        try {
            canonical = repository.getCanonicalPath();
        } catch (IOException e) {
            canonical = repository.getAbsolutePath();
        }
        return digest(canonical + "\u0000" + path.replace('\\', '/'));
    }

    private static String digest(String text) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // Required of every JRE.
            throw new IllegalStateException(impossible);
        }
    }

    /** Header values are one line by construction; a stray newline would forge a header. */
    private static String oneLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
