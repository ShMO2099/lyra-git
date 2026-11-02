package com.jfx.git.core;

import com.jfx.git.model.CommitInfo;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Diffs, file lists and line counts for commits and the working tree, and the bytes of
 * files at a commit, in the index, in a conflict or on disk, for {@link GitService}.
 */
final class JGitDiffs extends JGitPart {

    JGitDiffs(JGitRepo ctx) {
        super(ctx);
    }

    /** Content of {@code filePath} as of {@code commitHash}; empty if it did not exist then. */
    public String getFileContentAtCommit(String commitHash, String filePath) {
        return TextEncoding.GUESS.decode(getFileBytesAtCommit(commitHash, filePath));
    }

    /** Convenience overload that accepts a {@link CommitInfo}. */
    public String getFileContentAtCommit(CommitInfo commit, String filePath) {
        return getFileContentAtCommit(commit.getHash(), filePath);
    }

    /**
     * Raw bytes of {@code filePath} as of the commit identified by {@code commitHash}.
     *
     * <p>Git stores content without an encoding, so the caller decides how to decode -
     * see {@link TextEncoding}.
     */
    public byte[] getFileBytesAtCommit(String commitHash, String filePath) {
        if (repository() == null || commitHash == null) return new byte[0];
        try (RevWalk walk = new RevWalk(repository())) {
            RevCommit parsed = ctx.commitOrNull(walk, commitHash);
            if (parsed == null) {
                LOGGER.log(Level.FINE, "No commit {0} to read {1} from", new Object[]{commitHash, filePath});
                return new byte[0];
            }
            try (org.eclipse.jgit.treewalk.TreeWalk tree =
                         org.eclipse.jgit.treewalk.TreeWalk.forPath(repository(), filePath, parsed.getTree())) {
                if (tree == null) return new byte[0];
                return ctx.readBlob(tree.getObjectId(0));
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read " + filePath + " at " + commitHash, e);
            return new byte[0];
        }
    }

    /** Convenience overload that accepts a {@link CommitInfo}. */
    public byte[] getFileBytesAtCommit(CommitInfo commit, String filePath) {
        return getFileBytesAtCommit(commit.getHash(), filePath);
    }

    /**
     * What a commit did to each file it touched, with line counts.
     *
     * <h2>Why this is not left to the interface default</h2>
     * {@code HistoryOps.getChangesInCommit} degrades to "every path, marked M, with no
     * counts" for an engine that cannot do better. This engine can, and until now was
     * never asked to - so the file list of every commit showed a modification letter
     * beside a file that commit had created, and no {@code +/-} at all. The degraded path
     * is meant for an engine that has not implemented this; it should never have been the
     * one the default engine took.
     *
     * <p>Rename detection is on here, unlike the working-tree counts: this runs when a
     * commit is selected rather than on every status refresh, and a commit is exactly
     * where a rename is worth naming as one instead of as a delete beside an add.
     */
    public List<HistoryOps.FileChange> getChangesInCommit(String commitHash) {
        if (git() == null || commitHash == null) return List.of();
        List<HistoryOps.FileChange> changes = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository());
             ObjectReader reader = repository().newObjectReader();
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repository());
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            RevCommit parsed = ctx.commitOrNull(walk, commitHash);
            if (parsed == null) return List.of();
            RevCommit parent = parsed.getParentCount() > 0
                    ? walk.parseCommit(parsed.getParent(0).getId()) : null;

            List<DiffEntry> diffs = parent == null
                    ? df.scan(new EmptyTreeIterator(),
                            new CanonicalTreeParser(null, reader, parsed.getTree()))
                    : df.scan(parent.getTree(), parsed.getTree());
            changes.addAll(toFileChanges(df, diffs));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read changes in commit " + commitHash, e);
        }
        return changes;
    }

    /**
     * What differs between two commits, per file, as {@link #getChangesInCommit} reports
     * one commit - the same letters, the same counts, the same rename detection.
     */
    public List<HistoryOps.FileChange> getChangesBetweenCommits(String fromHash, String toHash) {
        if (repository() == null || fromHash == null || toHash == null) return List.of();
        try (RevWalk walk = new RevWalk(repository());
             ObjectReader reader = repository().newObjectReader();
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repository());
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            RevCommit from = ctx.commitOrNull(walk, fromHash);
            RevCommit to = ctx.commitOrNull(walk, toHash);
            if (from == null || to == null) return List.of();
            return toFileChanges(df, df.scan(
                    new CanonicalTreeParser(null, reader, from.getTree()),
                    new CanonicalTreeParser(null, reader, to.getTree())));
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not diff " + fromHash + ".." + toHash, e);
            return List.of();
        }
    }

    /** One {@code FileChange} per entry, counted with the formatter that found them. */
    private static List<HistoryOps.FileChange> toFileChanges(DiffFormatter df, List<DiffEntry> diffs)
            throws IOException {
        List<HistoryOps.FileChange> changes = new ArrayList<>();
        for (DiffEntry entry : diffs) {
            String path = entry.getNewPath();
            if (DiffEntry.DEV_NULL.equals(path)) {
                path = entry.getOldPath();
            }
            int added = HistoryOps.FileChange.UNKNOWN_COUNT;
            int deleted = HistoryOps.FileChange.UNKNOWN_COUNT;
            org.eclipse.jgit.patch.FileHeader header = df.toFileHeader(entry);
            // A binary file keeps UNKNOWN_COUNT, so its row shows the letter alone
            // rather than a "+0" that would read as a file that did not change.
            if (header.getPatchType()
                    == org.eclipse.jgit.patch.FileHeader.PatchType.UNIFIED) {
                added = 0;
                deleted = 0;
                for (org.eclipse.jgit.diff.Edit edit : header.toEditList()) {
                    added += edit.getEndB() - edit.getBeginB();
                    deleted += edit.getEndA() - edit.getBeginA();
                }
            }
            DiffEntry.ChangeType type = entry.getChangeType();
            boolean moved = type == DiffEntry.ChangeType.RENAME
                    || type == DiffEntry.ChangeType.COPY;
            changes.add(new HistoryOps.FileChange(
                    path, letterFor(type), added, deleted, moved ? entry.getOldPath() : null));
        }
        return changes;
    }

    /**
     * Narrows a diff to one file - or, for a rename, to both of its paths.
     *
     * <p>Filtered to the new path alone the walk never sees the old one, so the formatter
     * has nothing to pair the addition with and prints the whole file as new. With both
     * paths in, rename detection joins the two halves into one {@code rename from/to} diff.
     */
    private static void narrowTo(DiffFormatter df, String filePath, String oldPath) {
        if (oldPath == null || oldPath.equals(filePath)) {
            df.setPathFilter(PathFilter.create(filePath));
            return;
        }
        df.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilterGroup
                .createFromStrings(filePath, oldPath));
        df.setDetectRenames(true);
    }

    /** JGit's change type as the letter git's {@code --name-status} would print. */
    private static char letterFor(DiffEntry.ChangeType type) {
        return switch (type) {
            case ADD -> 'A';
            case DELETE -> 'D';
            case RENAME -> 'R';
            case COPY -> 'C';
            case MODIFY -> 'M';
        };
    }

    public List<String> getFilesInCommit(String commitHash) {
        if (git() == null || commitHash == null) return List.of();
        List<String> files = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository());
             ObjectReader reader = repository().newObjectReader();
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repository());
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            RevCommit parsed = ctx.commitOrNull(walk, commitHash);
            if (parsed == null) return List.of();
            RevCommit parent = parsed.getParentCount() > 0
                    ? walk.parseCommit(parsed.getParent(0).getId()) : null;

            List<DiffEntry> diffs;
            if (parent == null) {
                diffs = df.scan(new EmptyTreeIterator(), new CanonicalTreeParser(null, reader, parsed.getTree()));
            } else {
                diffs = df.scan(parent.getTree(), parsed.getTree());
            }
            for (DiffEntry entry : diffs) {
                String path = entry.getNewPath();
                if (DiffEntry.DEV_NULL.equals(path)) path = entry.getOldPath();
                files.add(path);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not list files in commit", e);
        }
        return files;
    }

    /** Direct child directories in a commit's tree, independent of its parent diff. */
    public List<String> getTopLevelDirectoriesAtCommit(String commitRef) {
        if (git() == null || commitRef == null) return List.of();
        try (RevWalk walk = new RevWalk(repository());
             org.eclipse.jgit.treewalk.TreeWalk tree = new org.eclipse.jgit.treewalk.TreeWalk(repository())) {
            RevCommit commit = ctx.commitOrNull(walk, commitRef);
            if (commit == null) return List.of();
            tree.addTree(commit.getTree());
            tree.setRecursive(false);
            List<String> directories = new ArrayList<>();
            while (tree.next()) {
                if (tree.isSubtree()) directories.add(tree.getPathString());
            }
            return directories.stream().sorted().toList();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not list top-level directories at " + commitRef, e);
            return List.of();
        }
    }

    /** Convenience overload that accepts a {@link CommitInfo}. */
    public List<String> getFilesInCommit(CommitInfo commit) {
        return getFilesInCommit(commit.getHash());
    }

    public List<String> getFilesBetweenCommits(String fromHash, String toHash) {
        if (repository() == null || fromHash == null || toHash == null) return List.of();
        List<String> files = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository());
             ObjectReader reader = repository().newObjectReader();
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

            df.setRepository(repository());
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            RevCommit from = ctx.commitOrNull(walk, fromHash);
            RevCommit to = ctx.commitOrNull(walk, toHash);
            if (from == null || to == null) return files;

            for (DiffEntry entry : df.scan(
                    new CanonicalTreeParser(null, reader, from.getTree()),
                    new CanonicalTreeParser(null, reader, to.getTree()))) {
                String path = entry.getNewPath();
                if (DiffEntry.DEV_NULL.equals(path)) path = entry.getOldPath();
                files.add(path);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not diff " + fromHash + ".." + toHash, e);
        }
        return files;
    }

    public byte[] getDiffBytesBetweenCommits(String fromHash, String toHash,
                                             String filePath, boolean ignoreWhitespace) {
        return getDiffBytesBetweenCommits(fromHash, toHash, filePath, null, ignoreWhitespace);
    }

    public byte[] getDiffBytesBetweenCommits(String fromHash, String toHash, String filePath,
                                             String oldPath, boolean ignoreWhitespace) {
        if (repository() == null || fromHash == null || toHash == null) return new byte[0];
        try (RevWalk walk = new RevWalk(repository());
             ObjectReader reader = repository().newObjectReader();
             ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter df = new DiffFormatter(out)) {

            df.setRepository(repository());
            narrowTo(df, filePath, oldPath);
            df.setDiffComparator(comparator(ignoreWhitespace));

            RevCommit from = ctx.commitOrNull(walk, fromHash);
            RevCommit to = ctx.commitOrNull(walk, toHash);
            if (from == null || to == null) {
                return noSuchCommitBytes(from == null ? fromHash : toHash);
            }

            df.format(new CanonicalTreeParser(null, reader, from.getTree()),
                    new CanonicalTreeParser(null, reader, to.getTree()));
            return out.toByteArray();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not diff " + filePath + " between commits", e);
            return errorBytes(e);
        }
    }

    /**
     * One file's diff in a commit, against its first parent, as raw bytes for callers
     * that decode with a chosen {@link TextEncoding} rather than assuming UTF-8.
     *
     * @param ignoreWhitespace treat lines differing only in whitespace as unchanged,
     *                         the equivalent of {@code git diff -w}
     */
    public byte[] getDiffBytesForFileInCommit(String commitHash, String filePath, boolean ignoreWhitespace) {
        return getDiffBytesForFileInCommit(commitHash, filePath, null, ignoreWhitespace);
    }

    public byte[] getDiffBytesForFileInCommit(String commitHash, String filePath, String oldPath,
                                              boolean ignoreWhitespace) {
        if (git() == null || commitHash == null) return new byte[0];
        try (RevWalk walk = new RevWalk(repository());
             ObjectReader reader = repository().newObjectReader();
             ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter df = new DiffFormatter(out)) {
            df.setRepository(repository());
            narrowTo(df, filePath, oldPath);
            df.setDiffComparator(comparator(ignoreWhitespace));

            RevCommit parsed = ctx.commitOrNull(walk, commitHash);
            if (parsed == null) return noSuchCommitBytes(commitHash);
            RevCommit parent = parsed.getParentCount() > 0
                    ? walk.parseCommit(parsed.getParent(0).getId()) : null;

            if (parent == null) {
                df.format(new EmptyTreeIterator(), new CanonicalTreeParser(null, reader, parsed.getTree()));
            } else {
                df.format(parent.getTree(), parsed.getTree());
            }
            return out.toByteArray();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not diff " + filePath, e);
            return errorBytes(e);
        }
    }

    /** Convenience overload that accepts a {@link CommitInfo}. */
    public byte[] getDiffBytesForFileInCommit(CommitInfo commit, String filePath, boolean ignoreWhitespace) {
        return getDiffBytesForFileInCommit(commit.getHash(), filePath, ignoreWhitespace);
    }

    /**
     * A failed diff still has to reach the viewer, and every diff path now carries
     * bytes, so the message travels as bytes too.
     */
    private static byte[] errorBytes(Exception e) {
        return ("Error getting diff: " + describe(e)).getBytes(StandardCharsets.UTF_8);
    }

    /** The diff of a commit that is not there - said, rather than a NullPointerException. */
    private static byte[] noSuchCommitBytes(String rev) {
        return ("Error getting diff: there is no commit " + rev + " in this repository")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Comparator controlling whether whitespace-only differences count as changes.
     * {@code WS_IGNORE_ALL} is what {@code git diff -w} uses.
     */
    private static RawTextComparator comparator(boolean ignoreWhitespace) {
        return ignoreWhitespace ? RawTextComparator.WS_IGNORE_ALL : RawTextComparator.DEFAULT;
    }

    /** Working tree vs index, as raw bytes for {@link TextEncoding} to decode. */
    public byte[] getUnstagedDiffBytes(String filePath, boolean ignoreWhitespace) {
        if (git() == null) return new byte[0];
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             DiffFormatter df = new DiffFormatter(out)) {
            df.setRepository(repository());
            df.setPathFilter(PathFilter.create(filePath));
            df.setDiffComparator(comparator(ignoreWhitespace));
            AbstractTreeIterator indexTree = new DirCacheIterator(repository().readDirCache());
            AbstractTreeIterator workingTree = new FileTreeIterator(repository());
            df.format(indexTree, workingTree);
            return out.toByteArray();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not diff unstaged " + filePath, e);
            return errorBytes(e);
        }
    }

    /** Index vs HEAD, as raw bytes for {@link TextEncoding} to decode. */
    public byte[] getStagedDiffBytes(String filePath, boolean ignoreWhitespace) {
        if (git() == null) return new byte[0];
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ObjectReader reader = repository().newObjectReader();
             DiffFormatter df = new DiffFormatter(out)) {
            df.setRepository(repository());
            df.setPathFilter(PathFilter.create(filePath));
            df.setDiffComparator(comparator(ignoreWhitespace));

            ObjectId head = repository().resolve("HEAD^{tree}");
            DirCache index = repository().readDirCache();
            DiffEntry rename = head == null ? null : stagedRenameOnto(filePath, reader, head, index);
            if (rename != null) {
                df.format(rename);
                return out.toByteArray();
            }
            AbstractTreeIterator oldTree = head == null
                    ? new EmptyTreeIterator()
                    : new CanonicalTreeParser(null, reader, head);
            df.format(oldTree, new DirCacheIterator(index));
            return out.toByteArray();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not diff staged " + filePath, e);
            return errorBytes(e);
        }
    }

    /**
     * The staged rename whose new path is {@code filePath}, or {@code null} if it is not
     * one.
     *
     * <p>Filtered to the new path alone, a staged {@code git mv} diffs as a whole new
     * file: every line an addition, the actual edits lost among them, and "Unstage hunk"
     * offering to empty the file rather than undo the edit. Pairing it with the path it
     * came from gives the diff git itself shows for a staged rename - a rename header and
     * only the lines that changed.
     *
     * <p>The full index-versus-HEAD scan this needs is only made for a path that is an
     * addition in the first place, so an ordinary modified file costs nothing extra.
     */
    private DiffEntry stagedRenameOnto(String filePath, ObjectReader reader, ObjectId head,
                                       DirCache index) throws Exception {
        try (DiffFormatter scan = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            scan.setRepository(repository());
            scan.setDetectRenames(false);
            scan.setPathFilter(PathFilter.create(filePath));
            List<DiffEntry> own = scan.scan(new CanonicalTreeParser(null, reader, head),
                    new DirCacheIterator(index));
            if (own.size() != 1 || own.get(0).getChangeType() != DiffEntry.ChangeType.ADD) {
                return null;
            }
            scan.setPathFilter(org.eclipse.jgit.treewalk.filter.TreeFilter.ALL);
            List<DiffEntry> candidates = new ArrayList<>(own);
            for (DiffEntry entry : scan.scan(new CanonicalTreeParser(null, reader, head),
                    new DirCacheIterator(index))) {
                if (entry.getChangeType() == DiffEntry.ChangeType.DELETE) {
                    candidates.add(entry);
                }
            }
            if (candidates.size() == 1) {
                return null;
            }
            org.eclipse.jgit.diff.RenameDetector detector =
                    new org.eclipse.jgit.diff.RenameDetector(repository());
            detector.addAll(candidates);
            for (DiffEntry entry : detector.compute(reader, org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE)) {
                if (entry.getChangeType() == DiffEntry.ChangeType.RENAME
                        && filePath.equals(entry.getNewPath())) {
                    return entry;
                }
            }
            return null;
        }
    }

    /**
     * Line counts for uncommitted work, so the staging list can show scale.
     *
     * <h2>Read from the same trees the diff viewer reads</h2>
     * Index-versus-HEAD for the staged side and working-tree-versus-index for the
     * unstaged one - the same two pairs {@link #getStagedDiffBytes} and
     * {@link #getUnstagedDiffBytes} use, so the numbers beside a file always agree with
     * the diff that opens when you click it.
     *
     * <p>Rename detection is off. It costs a similarity pass over every pair of blobs
     * and this runs on every status refresh, while the answer would change nothing: the
     * list shows paths, and a rename counted as a delete plus an add carries the same
     * total. A binary file is left out of the map entirely rather than counted as zero -
     * git prints a dash there for the same reason.
     */
    public java.util.Map<String, int[]> getWorkingTreeLineCounts(boolean staged,
                                                                 List<String> paths) {
        if (git() == null || repository() == null || paths == null || paths.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, int[]> counts = new java.util.HashMap<>();
        try (ObjectReader reader = repository().newObjectReader();
             DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            df.setRepository(repository());
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(false);
            df.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilterGroup
                    .createFromStrings(paths));

            AbstractTreeIterator oldTree;
            AbstractTreeIterator newTree;
            if (staged) {
                ObjectId head = repository().resolve("HEAD^{tree}");
                oldTree = head == null
                        ? new EmptyTreeIterator()
                        : new CanonicalTreeParser(null, reader, head);
                newTree = new DirCacheIterator(repository().readDirCache());
            } else {
                oldTree = new DirCacheIterator(repository().readDirCache());
                newTree = new FileTreeIterator(repository());
            }

            for (DiffEntry entry : df.scan(oldTree, newTree)) {
                String path = entry.getNewPath();
                if (DiffEntry.DEV_NULL.equals(path)) {
                    path = entry.getOldPath();
                }
                org.eclipse.jgit.patch.FileHeader header = df.toFileHeader(entry);
                if (header.getPatchType()
                        != org.eclipse.jgit.patch.FileHeader.PatchType.UNIFIED) {
                    continue;
                }
                int added = 0;
                int deleted = 0;
                for (org.eclipse.jgit.diff.Edit edit : header.toEditList()) {
                    added += edit.getEndB() - edit.getBeginB();
                    deleted += edit.getEndA() - edit.getBeginA();
                }
                counts.put(path, new int[]{added, deleted});
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not count working-tree lines", e);
        }
        return counts;
    }

    /**
     * Reads one side of a conflicted file from the index.
     *
     * @param stage 1 = common ancestor, 2 = ours, 3 = theirs
     * @return the content, or an empty string if that stage does not exist
     */
    public String getConflictStage(String path, int stage) {
        byte[] bytes = getConflictStageBytes(path, stage);
        return bytes.length == 0 ? "" : TextEncoding.GUESS.decode(bytes);
    }

    public byte[] getConflictStageBytes(String path, int stage) {
        if (repository() == null) return new byte[0];
        try {
            DirCache cache = repository().readDirCache();
            for (int i = 0; i < cache.getEntryCount(); i++) {
                DirCacheEntry entry = cache.getEntry(i);
                if (entry.getPathString().equals(path) && entry.getStage() == stage) {
                    return ctx.readBlob(entry.getObjectId());
                }
            }
            return new byte[0];
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read conflict stage " + stage + " of " + path, e);
            return new byte[0];
        }
    }

    // Block location and splicing now live in HunkPatcher, shared with NativeGitBackend
    // so the two engines cannot drift on where a moved hunk is found.

    /** Current file content in the working tree, used to seed the merge editor. */
    public String getWorkingTreeContent(String path) {
        return TextEncoding.GUESS.decode(getWorkingTreeBytes(path));
    }

    /**
     * The staged and working-tree bytes of {@code path}, for showing a binary side by side.
     *
     * <p>"Before" is what Git would compare against - the index copy, falling back to
     * HEAD - and "after" is what is on disk now. Either can be empty, which is how an
     * addition and a deletion tell themselves apart.
     */
    public byte[][] getWorkingTreeSides(String path) {
        byte[] before = getIndexOrHeadBytes(path);
        return new byte[][]{before, getWorkingTreeBytes(path)};
    }

    /** The bytes of {@code path} at {@code commit} and at its first parent. */
    public byte[][] getCommitSides(CommitInfo commit, String path) {
        if (commit == null) return new byte[][]{new byte[0], new byte[0]};
        String parentHash = commit.getParentHashes().isEmpty() ? null : commit.getParentHashes().get(0);
        byte[] before = parentHash == null ? new byte[0] : getFileBytesAtCommit(parentHash, path);
        return new byte[][]{before, getFileBytesAtCommit(commit.getHash(), path)};
    }

    private byte[] getIndexOrHeadBytes(String path) {
        if (repository() == null) return new byte[0];
        try {
            byte[] staged = staging().getIndexBytes(path);
            if (staged.length > 0) {
                return staged;
            }
            ObjectId head = repository().resolve(Constants.HEAD);
            if (head == null) {
                return new byte[0];
            }
            try (RevWalk walk = new RevWalk(repository())) {
                return getFileBytesAtCommit(walk.parseCommit(head).getName(), path);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read the previous version of " + path, e);
            return new byte[0];
        }
    }

    /**
     * Writes {@code bytes} over the working-tree copy of {@code path}, creating the
     * file (and any missing parent directories) if it does not already exist.
     *
     * <p>The in-application editor is not limited to the current working-tree copy:
     * editing a historical, stashed, or compared revision has to be able to write its
     * result somewhere, and the working tree - possibly recreating a path deleted or
     * renamed since - is the only sensible destination. See {@code DiffViewer.canEdit}.
     */
    public GitResult writeWorkingTreeFile(String path, byte[] bytes) {
        if (repository() == null) return GitResult.failed("No repository open");
        if (path == null || path.isBlank()) return GitResult.failed("No file to save");
        java.nio.file.Path target;
        try {
            // Through the shared policy rather than new File(workTree, path): the editor
            // takes a path from a diff, and a diff can name something reached through a
            // link. See WorktreePaths.
            target = WorktreePaths.resolveForWrite(repository().getWorkTree(), path);
        } catch (IllegalArgumentException e) {
            return GitResult.failed("Could not save " + path + ": " + e.getMessage());
        }
        try {
            java.nio.file.Path parent = target.getParent();
            if (parent != null) java.nio.file.Files.createDirectories(parent);
            java.nio.file.Files.write(target, bytes == null ? new byte[0] : bytes);
            return GitResult.ok("Saved " + path);
        } catch (Exception e) {
            return GitResult.failed("Could not save " + path + ": " + describe(e));
        }
    }

    /** Working-tree bytes, for callers that decode with a chosen {@link TextEncoding}. */
    public byte[] getWorkingTreeBytes(String path) {
        if (repository() == null) return new byte[0];
        try {
            java.nio.file.Path f = WorktreePaths.resolveForWrite(repository().getWorkTree(), path);
            if (!java.nio.file.Files.isRegularFile(f)) return new byte[0];
            return java.nio.file.Files.readAllBytes(f);
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Refused to read " + path + ": " + e.getMessage());
            return new byte[0];
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not read " + path, e);
            return new byte[0];
        }
    }
}
