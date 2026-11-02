package com.jfx.git.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * The one place that decides whether a repository-relative path is safe to touch.
 *
 * <p>Comparing a normalised path against the work-tree root is not enough. {@code
 * repo/link/victim.txt} passes {@code startsWith(root)} as text while {@code link} is a
 * symlink - or, on Windows, a junction - pointing at an unrelated directory, so a delete
 * from the file browser reaches outside the repository. Junctions are worse than
 * symlinks here: the JDK does not report them as links at all, and only resolving the
 * path shows where it really goes.
 *
 * <p>So every check below is made against <em>real</em> paths: the work-tree root and the
 * deepest existing ancestor of the target are both resolved through whatever links they
 * contain, and the two are compared afterwards.
 *
 * <p>Three rules, because the operations differ in what they follow:
 *
 * <ul>
 *   <li>{@link #resolveForWrite} - ancestors are followed, and an existing target is too,
 *       because writing through a link writes to its target.
 *   <li>{@link #resolveForDelete} - ancestors are followed, the final component is not: a
 *       link inside the repository is the user's to delete, but its target is not.
 *   <li>{@link #resolveDirectory} - the whole path is followed, because listing a
 *       directory that really lives elsewhere would show a tree outside the repository.
 * </ul>
 */
public final class WorktreePaths {

    private WorktreePaths() {
    }

    private static final String GIT_DIR = ".git";

    /**
     * The work-tree root with every link resolved.
     *
     * @throws IllegalArgumentException if there is no work tree, or it cannot be read
     */
    public static Path root(File workTree) {
        if (workTree == null) {
            throw new IllegalArgumentException("No repository is open");
        }
        Path root = workTree.toPath().toAbsolutePath().normalize();
        try {
            return root.toRealPath();
        } catch (IOException e) {
            // A work tree that cannot be resolved cannot be compared against either, and
            // guessing would defeat the whole check.
            throw new IllegalArgumentException("Cannot read the repository directory: " + root);
        }
    }

    /**
     * Where {@code relativePath} may be written, with links followed.
     *
     * @throws IllegalArgumentException if the path leaves the work tree, names git's own
     *                                  storage, or reaches outside through a link
     */
    public static Path resolveForWrite(File workTree, String relativePath) {
        Path root = root(workTree);
        Path target = insideRoot(root, relativePath);
        Path real = realWithin(root, target, relativePath);

        // The parent is clean; the target itself can still be a link out of the tree, and
        // writing to it would write to whatever it points at.
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Path realTarget;
            try {
                realTarget = target.toRealPath();
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read " + relativePath);
            }
            if (!realTarget.startsWith(root)) {
                throw outsideThroughLink(relativePath);
            }
            return realTarget;
        }
        return real;
    }

    /**
     * Where {@code relativePath} may be deleted, without following the final component.
     *
     * @throws IllegalArgumentException if the path leaves the work tree, names git's own
     *                                  storage, or reaches outside through a link
     */
    public static Path resolveForDelete(File workTree, String relativePath) {
        Path root = root(workTree);
        return realWithin(root, insideRoot(root, relativePath), relativePath);
    }

    /**
     * The real directory {@code relativeDir} names, or {@code null} when it is not a
     * readable directory inside the work tree.
     *
     * <p>Returns {@code null} rather than throwing: a browser listing a directory that has
     * just been deleted, or expanding a junction pointing elsewhere, wants an empty node,
     * not an error dialog.
     */
    public static Path resolveDirectory(File workTree, String relativeDir) {
        Path root;
        try {
            root = root(workTree);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (relativeDir == null || relativeDir.isBlank()) {
            return root;
        }
        Path target;
        try {
            target = insideRoot(root, relativeDir);
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            // Follows the final component on purpose: this is the one case where the
            // question is what the path leads to, not what it is.
            Path real = target.toRealPath();
            return real.startsWith(root) && Files.isDirectory(real) ? real : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Whether {@code path} is a link rather than the thing it names.
     *
     * <p>Covers Windows junctions as well as symlinks: the JDK reports a junction as an
     * ordinary directory, but its real path is somewhere its parent's real path does not
     * contain.
     */
    public static boolean isLink(Path path) {
        if (path == null) return false;
        if (Files.isSymbolicLink(path)) return true;
        Path parent = path.getParent();
        if (parent == null) return false;
        try {
            Path real = path.toRealPath();
            Path realParent = parent.toRealPath();
            return real.getParent() == null || !real.getParent().equals(realParent);
        } catch (IOException e) {
            // A path that cannot be resolved is not evidence of a link.
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Textual containment, plus the rules that do not need the filesystem at all. */
    private static Path insideRoot(Path root, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("A path is required");
        }
        String cleaned = relativePath.replace('\\', '/');
        Path target = root.resolve(cleaned).normalize();

        if (target.equals(root)) {
            throw new IllegalArgumentException("That is the repository root");
        }
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside the repository: " + relativePath);
        }
        for (Path segment : root.relativize(target)) {
            if (segment.toString().toLowerCase(Locale.ROOT).equals(GIT_DIR)) {
                throw new IllegalArgumentException(
                        "Paths inside .git are git's own storage and cannot be edited here: "
                                + relativePath);
            }
        }
        return target;
    }

    /**
     * {@code target} with its existing ancestors resolved, checked to still be under
     * {@code root}. The final component is left as written.
     */
    private static Path realWithin(Path root, Path target, String relativePath) {
        Path parent = target.getParent();
        // insideRoot guarantees target is strictly below root, so it has a parent.
        Path realParent = resolveExistingAncestors(parent);
        if (realParent == null) {
            throw new IllegalArgumentException("Cannot read the folder holding " + relativePath);
        }
        if (!realParent.startsWith(root)) {
            throw outsideThroughLink(relativePath);
        }
        return realParent.resolve(target.getFileName());
    }

    /**
     * {@code dir} with the deepest ancestor that exists resolved through its links, and
     * the segments that do not exist yet appended unchanged.
     *
     * <p>Missing segments need no resolution: a path component that does not exist cannot
     * be a link to anywhere.
     */
    private static Path resolveExistingAncestors(Path dir) {
        Deque<Path> missing = new ArrayDeque<>();
        Path existing = dir;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name != null) missing.push(name);
            existing = existing.getParent();
        }
        if (existing == null) return null;

        Path real;
        try {
            real = existing.toRealPath();
        } catch (IOException e) {
            return null;
        }
        for (Path segment : missing) {
            real = real.resolve(segment);
        }
        return real;
    }

    private static IllegalArgumentException outsideThroughLink(String relativePath) {
        return new IllegalArgumentException(
                relativePath + " leads outside the repository through a link");
    }
}
