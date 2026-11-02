package com.jfx.git.ui;

import com.jfx.git.graph.GitGraphRow;
import com.jfx.git.graph.GraphOrder;
import com.jfx.git.graph.GraphPage;
import com.jfx.git.graph.GraphPageBuilder;
import com.jfx.git.model.CommitInfo;
import com.jfx.git.model.RefInfo;
import com.jfx.git.model.RefSnapshot;
import com.jfx.git.model.StashEntry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The first page of each repository's graph, kept on disk between sessions.
 *
 * <h2>Why</h2>
 * Opening a repository drew nothing until the open and the first page walk had both
 * finished on the repository lane - for a thousand-commit page on JGit, a third of a
 * second or more after the open itself, and longer with other tabs' work queued ahead.
 * The page last shown is a very good guess at the page about to be loaded, so it is drawn
 * at once and the real load then confirms or replaces it. A page whose signature matches
 * is left alone, so in the usual case nothing on screen moves at all; a repository that
 * changed while the application was closed shows its old page only until the real one
 * lands.
 *
 * <p>What is stored is what the page was built from - each commit's summary, parents and
 * refs, the stashes, what HEAD reaches, and the ref snapshot the page carries - and the
 * page is built again with {@link GraphPageBuilder}, so the lanes are laid out by the same
 * code as a live load and the kept page names the same current branch and remotes.
 * Nothing here touches git: reading and writing happen on a thread of their own, never on
 * the repository lane and never on the FX thread. Any problem with the file - missing,
 * from another version, damaged - simply means no cached page.
 */
final class GraphPageCache {

    private static final Logger LOGGER = Logger.getLogger(GraphPageCache.class.getName());

    /**
     * Bumped whenever what is written changes; an older file is ignored and removed. 2: the
     * ref snapshot.
     */
    private static final int FORMAT = 2;

    /** No page larger than this is kept: the cache is for the first page, not "Show all". */
    static final int MAX_COMMITS = 2_000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "graph-page-cache");
        t.setDaemon(true);
        return t;
    });

    private GraphPageCache() {
    }

    /** Where {@code repository}'s page is kept, beside the settings file. */
    static Path fileFor(File repository) {
        String key;
        try {
            key = repository.getCanonicalPath();
        } catch (IOException e) {
            key = repository.getAbsolutePath();
        }
        key = key.toLowerCase(java.util.Locale.ROOT);
        Path settings = com.jfx.git.core.Settings.file();
        Path dir = (settings.getParent() != null ? settings.getParent() : Path.of("."))
                .resolve("graph-cache");
        return dir.resolve(digest(key) + ".page");
    }

    /**
     * Reads and rebuilds {@code repository}'s page off the FX thread, runs {@code prepare}
     * on it there too - with the branch its chips were drawn against - and hands the result
     * to {@code onLoaded} on the FX thread; or does nothing at all when there is none.
     */
    static <T> void load(File repository, java.util.function.BiFunction<GraphPage, String, T> prepare,
                         java.util.function.Consumer<T> onLoaded) {
        IO.submit(() -> {
            Path file = fileFor(repository);
            if (!Files.isRegularFile(file)) return;
            try {
                Stored stored = read(file);
                if (stored == null) {
                    // From another version: not read again, and the next save replaces it.
                    Files.deleteIfExists(file);
                    return;
                }
                GraphPage built = GraphPageBuilder.build(stored.input());
                // Built as a complete walk - see read() - with "more follows" put back.
                GraphPage page = new GraphPage(built.rows(), stored.hasMore(), built.refs());
                T prepared = prepare.apply(page, stored.branch());
                javafx.application.Platform.runLater(() -> onLoaded.accept(prepared));
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Ignoring the cached graph page for " + repository, e);
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // Will be overwritten by the next save anyway.
                }
            }
        });
    }

    /** Keeps {@code page} for {@code repository}'s next opening; written off the FX thread. */
    static void save(File repository, GraphPage page, String branch) {
        if (repository == null || page == null) return;
        long commits = page.rows().stream().filter(row -> row.getCommit() != null && !row.isWipNode()).count();
        if (commits == 0 || commits > MAX_COMMITS) return;
        GraphOrder order = GraphOrder.current();
        IO.submit(() -> {
            Path file = fileFor(repository);
            try {
                Files.createDirectories(file.getParent());
                Path temp = file.resolveSibling(file.getFileName() + ".tmp");
                try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                    write(out, page, branch, order);
                }
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not keep the graph page for " + repository, e);
            }
        });
    }

    // ------------------------------------------------------------------
    // Format
    // ------------------------------------------------------------------

    private record Stored(GraphPageBuilder.Input input, String branch, boolean hasMore) {}

    private static void write(DataOutputStream out, GraphPage page, String branch, GraphOrder order)
            throws IOException {
        out.writeInt(FORMAT);
        writeString(out, branch);
        writeString(out, order.name());
        out.writeBoolean(page.hasMore());

        String headHash = null;
        boolean dirty = false;
        List<CommitInfo> commits = new ArrayList<>();
        List<StashEntry> stashes = new ArrayList<>();
        Map<String, String> upstreams = null;
        for (GitGraphRow row : page.rows()) {
            if (row.isWipNode()) {
                dirty = true;
                // The uncommitted row carries no commit, only the pseudo-commit it is laid
                // out as - whose one parent is HEAD.
                CommitInfo pseudo = row.getLayout();
                List<String> parents = pseudo == null ? List.of() : pseudo.getParentHashes();
                headHash = parents.isEmpty() ? null : parents.get(0);
            } else if (row.isStash()) {
                stashes.add(row.getStash());
            } else if (row.getCommit() != null) {
                commits.add(row.getCommit());
                if (upstreams == null) upstreams = row.getCommit().getUpstreams();
            }
        }
        out.writeBoolean(dirty);
        writeString(out, headHash);

        out.writeInt(commits.size());
        for (CommitInfo c : commits) {
            writeString(out, c.getHash());
            writeString(out, c.getSubject());
            writeString(out, c.getBodySummary());
            writeString(out, c.getAuthorName());
            writeString(out, c.getAuthorEmail());
            out.writeLong(c.getAuthorDate() == null ? Long.MIN_VALUE : c.getAuthorDate().toEpochMilli());
            out.writeLong(c.getCommitterDate() == null ? Long.MIN_VALUE : c.getCommitterDate().toEpochMilli());
            out.writeBoolean(c.isOnCurrentBranch());
            out.writeInt(c.getParentHashes().size());
            for (String parent : c.getParentHashes()) writeString(out, parent);
            out.writeInt(c.getRefs().size());
            for (RefInfo ref : c.getRefs()) {
                writeString(out, ref.fullName());
                writeString(out, ref.shortName());
                writeString(out, ref.targetHash());
            }
        }

        writeStashes(out, stashes);

        Map<String, String> ups = upstreams == null ? Map.of() : upstreams;
        out.writeInt(ups.size());
        for (var entry : ups.entrySet()) {
            writeString(out, entry.getKey());
            writeString(out, entry.getValue());
        }

        writeSnapshot(out, page.refs());
    }

    private static void writeSnapshot(DataOutputStream out, RefSnapshot refs) throws IOException {
        writeString(out, refs.currentBranch());
        writeString(out, refs.headHash());
        for (List<RefInfo> list : List.of(refs.localBranches(), refs.remoteBranches(), refs.tags())) {
            out.writeInt(list.size());
            for (RefInfo ref : list) {
                writeString(out, ref.fullName());
                writeString(out, ref.shortName());
                writeString(out, ref.targetHash());
            }
        }
        out.writeInt(refs.upstreams().size());
        for (var entry : refs.upstreams().entrySet()) {
            writeString(out, entry.getKey());
            writeString(out, entry.getValue());
        }
        writeStashes(out, refs.stashes());
        out.writeInt(refs.remoteNames().size());
        for (String remote : refs.remoteNames()) writeString(out, remote);
    }

    private static RefSnapshot readSnapshot(DataInputStream in) throws IOException {
        String current = readString(in);
        String headHash = readString(in);
        List<List<RefInfo>> lists = new ArrayList<>(3);
        for (int list = 0; list < 3; list++) {
            List<RefInfo> refs = new ArrayList<>();
            for (int r = count(in); r > 0; r--) {
                refs.add(new RefInfo(readString(in), readString(in), readString(in)));
            }
            lists.add(refs);
        }
        Map<String, String> upstreams = new java.util.LinkedHashMap<>();
        for (int u = count(in); u > 0; u--) upstreams.put(readString(in), readString(in));
        List<StashEntry> stashes = readStashes(in);
        List<String> remotes = new ArrayList<>();
        for (int r = count(in); r > 0; r--) remotes.add(readString(in));
        return new RefSnapshot(current, headHash, lists.get(0), lists.get(1), lists.get(2), upstreams,
                stashes, remotes);
    }

    private static void writeStashes(DataOutputStream out, List<StashEntry> stashes) throws IOException {
        out.writeInt(stashes.size());
        for (StashEntry s : stashes) {
            out.writeInt(s.index());
            writeString(out, s.hash());
            writeString(out, s.parentHash());
            writeString(out, s.message());
            writeString(out, s.authorName());
            out.writeLong(s.when() == null ? Long.MIN_VALUE : s.when().toEpochMilli());
        }
    }

    private static List<StashEntry> readStashes(DataInputStream in) throws IOException {
        List<StashEntry> stashes = new ArrayList<>();
        for (int s = count(in); s > 0; s--) {
            stashes.add(new StashEntry(in.readInt(), readString(in), readString(in),
                    readString(in), readString(in), instant(in.readLong())));
        }
        return stashes;
    }

    /** A list's length, refusing one no kept page could have. */
    private static int count(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > 1 << 20) throw new IOException("implausible count " + count);
        return count;
    }

    private static Stored read(Path file) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != FORMAT) return null;
            String branch = readString(in);
            GraphOrder order = GraphOrder.valueOf(readString(in));
            boolean hasMore = in.readBoolean();
            boolean dirty = in.readBoolean();
            String headHash = readString(in);

            int count = in.readInt();
            if (count < 0 || count > MAX_COMMITS) return null;
            List<CommitInfo> commits = new ArrayList<>(count);
            Set<String> reachable = new HashSet<>();
            for (int i = 0; i < count; i++) {
                String hash = readString(in);
                String subject = readString(in);
                String body = readString(in);
                String author = readString(in);
                String email = readString(in);
                Instant authored = instant(in.readLong());
                Instant committed = instant(in.readLong());
                boolean onBranch = in.readBoolean();
                List<String> parents = new ArrayList<>();
                for (int p = in.readInt(); p > 0; p--) parents.add(readString(in));
                List<RefInfo> refs = new ArrayList<>();
                for (int r = in.readInt(); r > 0; r--) {
                    refs.add(new RefInfo(readString(in), readString(in), readString(in)));
                }
                commits.add(CommitInfo.summary(hash, subject, body, author, email, authored,
                        committed, parents, refs));
                if (onBranch) reachable.add(hash);
            }

            List<StashEntry> stashes = readStashes(in);

            Map<String, String> upstreams = new HashMap<>();
            for (int u = count(in); u > 0; u--) upstreams.put(readString(in), readString(in));

            RefSnapshot refs = readSnapshot(in);

            if (headHash == null && !commits.isEmpty()) headHash = commits.get(0).getHash();
            // Marked complete whatever the page said: the stored commits are exactly the
            // rows the page drew, already in order. Built as an incomplete walk, the ordering
            // holds back its last stretch, since it cannot see past it - which here would
            // only drop rows that were on screen.
            var input = new GraphPageBuilder.Input(commits, true, headHash, order, count,
                    dirty, stashes, reachable.size() == count ? null : reachable, upstreams, refs);
            return new Stored(input, branch, hasMore);
        }
    }

    private static Instant instant(long millis) {
        return millis == Long.MIN_VALUE ? null : Instant.ofEpochMilli(millis);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) return null;
        if (length > 1 << 20) throw new IOException("implausible string length " + length);
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static String digest(String text) {
        try {
            var sha = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(sha.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
