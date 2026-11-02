package com.jfx.git.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Working-tree file operations that are not git operations.
 *
 * <p>Creating, deleting and ignoring files are things a user does <em>to</em> a
 * repository rather than <em>with</em> git, so they live here rather than on
 * {@link GitBackend}: none of them differ between engines, and adding five identical
 * implementations to the backend interface would suggest they might.
 *
 * <p>Every path crossing this class is repository-relative and validated by
 * {@link #resolve}, which refuses anything that would land outside the work tree.
 */
public final class WorkspaceFiles {

    private static final Logger LOGGER = Logger.getLogger(WorkspaceFiles.class.getName());

    /** Directories never worth showing in a repository browser. */
    private static final List<String> ALWAYS_HIDDEN = List.of(".git");

    private final File workTree;

    public WorkspaceFiles(File workTree) {
        this.workTree = workTree;
    }

    public File workTree() {
        return workTree;
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    /**
     * One entry in the repository browser.
     *
     * @param link whether the entry is a symlink or junction rather than the thing it
     *             names; the browser will not descend through one that leads out of the
     *             work tree, so it is worth saying which entries those are
     */
    public record Entry(String path, String name, boolean directory, boolean link) {

        /** An ordinary entry, which is what most callers are constructing. */
        public Entry(String path, String name, boolean directory) {
            this(path, name, directory, false);
        }

        /** File extension without the dot, lower-cased, or empty when there is none. */
        public String extension() {
            int dot = name.lastIndexOf('.');
            return dot <= 0 || dot == name.length() - 1
                    ? ""
                    : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        }

        /** Parent directory path, or empty for a top-level entry. */
        public String parent() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? "" : path.substring(0, slash);
        }
    }

    /**
     * Immediate children of {@code relativeDir}, directories first then files, each
     * alphabetically.
     *
     * <p>Listed one level at a time rather than walked in full: a repository browser only
     * ever shows the expanded nodes, and walking everything up front makes opening a large
     * repository wait on directories nobody looked at.
     */
    public List<Entry> children(String relativeDir) {
        // Resolved rather than concatenated: expanding a junction that points at another
        // directory would otherwise browse a tree that is not in this repository at all.
        Path dir = WorktreePaths.resolveDirectory(workTree, relativeDir);
        if (dir == null) return List.of();

        File[] found = dir.toFile().listFiles();
        if (found == null) return List.of();

        List<Entry> entries = new ArrayList<>(found.length);
        for (File file : found) {
            String name = file.getName();
            if (ALWAYS_HIDDEN.contains(name)) continue;
            String path = relativeDir == null || relativeDir.isBlank()
                    ? name
                    : relativeDir + "/" + name;
            entries.add(new Entry(path, name, file.isDirectory(),
                    WorktreePaths.isLink(file.toPath())));
        }

        entries.sort(Comparator
                .comparing(Entry::directory).reversed()
                .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return entries;
    }

    // ------------------------------------------------------------------
    // Create and delete
    // ------------------------------------------------------------------

    /**
     * Creates an empty file, making any missing parent directories.
     *
     * <p>Typing {@code foo/bar.txt} into the new-file box is meant to create the
     * directory too; refusing because {@code foo} does not exist yet would make the user
     * do by hand what they clearly asked for.
     *
     * <p>Refuses to overwrite: a "create" that silently truncated an existing file would
     * be a destructive operation wearing a harmless name.
     */
    public GitResult createFile(String relativePath) {
        Path target;
        try {
            target = resolve(relativePath);
        } catch (IllegalArgumentException e) {
            return GitResult.failed(e.getMessage());
        }

        if (Files.exists(target)) {
            return GitResult.failed(relativePath + " already exists");
        }
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.createFile(target);
            return GitResult.ok("Created " + relativePath);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not create " + relativePath, e);
            return GitResult.failed("Could not create " + relativePath + ": " + e.getMessage());
        }
    }

    /** Creates a directory, and any parents it needs. */
    public GitResult createDirectory(String relativePath) {
        try {
            Files.createDirectories(resolve(relativePath));
            return GitResult.ok("Created " + relativePath + "/");
        } catch (IllegalArgumentException e) {
            return GitResult.failed(e.getMessage());
        } catch (IOException e) {
            return GitResult.failed("Could not create " + relativePath + ": " + e.getMessage());
        }
    }

    /**
     * Deletes a file, or a directory and everything under it.
     *
     * <p>The caller is responsible for confirming; this does not ask.
     */
    public GitResult delete(String relativePath) {
        Path target;
        try {
            // Deliberately not resolveForWrite: deleting a link in the repository removes
            // the link, and a user who asked to delete it did not ask to delete its target.
            target = WorktreePaths.resolveForDelete(workTree, relativePath);
        } catch (IllegalArgumentException e) {
            return GitResult.failed(e.getMessage());
        }

        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return GitResult.failed(relativePath + " does not exist");
        }
        try {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                deleteRecursively(target);
            } else {
                Files.delete(target);
            }
            return GitResult.ok("Deleted " + relativePath);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not delete " + relativePath, e);
            return GitResult.failed("Could not delete " + relativePath + ": " + e.getMessage());
        }
    }

    /**
     * Removes {@code root} and everything under it, without ever walking through a link.
     *
     * <p>{@code Files.walk} follows no links by default, but a junction is not a link as
     * far as the JDK is concerned, so the walk is written by hand: each directory is only
     * descended into once {@link WorktreePaths#isLink} says it really is that directory.
     * A link is unlinked, never emptied.
     */
    private static void deleteRecursively(Path root) throws IOException {
        List<Path> ordered = new ArrayList<>();
        Deque<Path> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Path dir = pending.pop();
            ordered.add(dir);
            try (var stream = Files.newDirectoryStream(dir)) {
                for (Path child : stream) {
                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                            && !WorktreePaths.isLink(child)) {
                        pending.push(child);
                    } else {
                        ordered.add(child);
                    }
                }
            }
        }
        // Deepest first, so a directory is only removed once it is empty.
        ordered.sort(Comparator.reverseOrder());
        for (Path path : ordered) {
            Files.deleteIfExists(path);
        }
    }

    // ------------------------------------------------------------------
    // .gitignore
    // ------------------------------------------------------------------

    /** What an "ignore" gesture should add. */
    public enum IgnoreScope {
        /** Just this path. */
        FILE,
        /** Everything with the same extension, anywhere. */
        EXTENSION,
        /** The containing directory and everything under it. */
        FOLDER
    }

    /**
     * Appends a rule to the repository's root {@code .gitignore}.
     *
     * <p>Existing rules are read first so the same line is never added twice — an ignore
     * menu that appends a duplicate every time it is used turns the file into a log.
     *
     * @return the rule that was added, or a failure explaining why none was
     */
    public GitResult ignore(String relativePath, IgnoreScope scope) {
        String rule = ruleFor(relativePath, scope);
        if (rule == null) {
            return GitResult.failed(scope == IgnoreScope.EXTENSION
                    ? relativePath + " has no extension to ignore"
                    : "Nothing to ignore for " + relativePath);
        }

        Path gitignore;
        try {
            // Through the resolver like every other write: a .gitignore that is a link out
            // of the repository would otherwise have rules appended to a file elsewhere.
            gitignore = WorktreePaths.resolveForWrite(workTree, ".gitignore");
        } catch (IllegalArgumentException e) {
            return GitResult.failed(e.getMessage());
        }
        try {
            List<String> lines = Files.exists(gitignore)
                    ? new ArrayList<>(Files.readAllLines(gitignore, StandardCharsets.UTF_8))
                    : new ArrayList<>();

            for (String line : lines) {
                if (line.trim().equals(rule)) {
                    return GitResult.ok(rule + " is already ignored");
                }
            }

            // Keep the file tidy: a trailing newline before appending, and one after.
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                lines.add("");
            }
            lines.add(rule);

            Files.writeString(gitignore, String.join(System.lineSeparator(), lines)
                    + System.lineSeparator(), StandardCharsets.UTF_8);
            return GitResult.ok("Ignored " + rule);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not update .gitignore", e);
            return GitResult.failed("Could not update .gitignore: " + e.getMessage());
        }
    }

    /**
     * The rule an ignore gesture produces, or {@code null} when there is none.
     *
     * <p>Paths are anchored with a leading slash so {@code /build} means the one at the
     * repository root rather than every directory of that name at any depth.
     */
    public static String ruleFor(String relativePath, IgnoreScope scope) {
        if (relativePath == null || relativePath.isBlank()) return null;
        String path = relativePath.replace('\\', '/');

        return switch (scope) {
            case FILE -> "/" + path;
            case EXTENSION -> {
                String name = path.substring(path.lastIndexOf('/') + 1);
                int dot = name.lastIndexOf('.');
                yield dot <= 0 || dot == name.length() - 1 ? null : "*" + name.substring(dot);
            }
            case FOLDER -> {
                int slash = path.lastIndexOf('/');
                // A top-level file has no folder above it but the repository root, and
                // ignoring that would ignore everything.
                yield slash < 0 ? null : "/" + path.substring(0, slash) + "/";
            }
        };
    }

    // ------------------------------------------------------------------
    // Path safety
    // ------------------------------------------------------------------

    /**
     * Turns a repository-relative path into an absolute one inside the work tree.
     *
     * <p>Delegates to {@link WorktreePaths}, which is where the rules live: comparing the
     * normalised text against the root - as this method used to - happily accepts
     * {@code link/victim.txt} whenever {@code link} points somewhere else entirely.
     *
     * @throws IllegalArgumentException if the result would escape the work tree, which is
     *                                  what {@code ../../etc/passwd} typed into the
     *                                  new-file box would otherwise do
     */
    Path resolve(String relativePath) {
        return WorktreePaths.resolveForWrite(workTree, relativePath);
    }
}
