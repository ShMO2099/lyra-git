package com.jfx.git.core;

import com.jfx.git.model.Snapshot;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.treewalk.WorkingTreeOptions;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;

/**
 * Working-tree snapshots on the JGit engine; see {@link WorkingTreeSnapshots} for what
 * they are for.
 *
 * <p>The tree is built the way {@code JGitStash} builds a stash's working-tree commit -
 * the index and the files walked side by side, blobs inserted for what differs - but into
 * an in-core {@link DirCache} that is never written, so the real index is only read.
 */
final class JGitSnapshots extends JGitPart {

    JGitSnapshots(JGitRepo ctx) {
        super(ctx);
    }

    public String snapshotWorkingTree(String reason) {
        Repository repo = repository();
        if (repo != null) adoptLegacySnapshots(repo);
        if (repo == null || repo.isBare()) throw new GitOperationException("No repository open");
        try {
            return take(repo, reason);
        } catch (GitOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new GitOperationException("Could not take a snapshot: " + describe(e), e);
        }
    }

    private String take(Repository repo, String reason) throws Exception {
        ObjectId headId = repo.resolve(Constants.HEAD + "^{commit}");
        DirCache index = repo.readDirCache();
        DirCache tree = DirCache.newInCore();
        DirCacheBuilder builder = tree.builder();
        int untracked = 0;
        try (ObjectInserter inserter = repo.newObjectInserter();
             ObjectReader reader = repo.newObjectReader();
             RevWalk revs = new RevWalk(reader);
             TreeWalk walk = new TreeWalk(repo, reader)) {
            walk.setRecursive(true);
            int dcIndex = walk.addTree(new DirCacheIterator(index));
            FileTreeIterator files = new FileTreeIterator(repo);
            int wtIndex = walk.addTree(files);
            files.setDirCacheIterator(walk, dcIndex);
            walk.setFilter(new TrackedOrNotIgnored(dcIndex, wtIndex));

            String last = null;
            while (walk.next()) {
                String path = walk.getPathString();
                // An unresolved conflict has an entry per stage; the file is taken once.
                if (path.equals(last)) continue;
                last = path;
                DirCacheIterator dc = walk.getTree(dcIndex, DirCacheIterator.class);
                WorkingTreeIterator wt = walk.getTree(wtIndex, WorkingTreeIterator.class);
                DirCacheEntry staged = dc == null ? null : dc.getDirCacheEntry();
                boolean merged = staged != null && staged.getStage() == DirCacheEntry.STAGE_0;
                if (merged && staged.isSkipWorkTree()) {
                    builder.add(copyOf(staged));
                    continue;
                }
                // Deleted from the working tree: gone from the snapshot too.
                if (wt == null) continue;
                if (staged == null && ++untracked > WorkingTreeSnapshots.MAX_UNTRACKED_FILES) {
                    LOGGER.info("No snapshot: more than " + WorkingTreeSnapshots.MAX_UNTRACKED_FILES
                            + " untracked files");
                    return null;
                }
                FileMode mode = wt.getEntryFileMode();
                if (mode == FileMode.TREE) continue;
                if (mode == FileMode.GITLINK) {
                    ObjectId nested = wt.getEntryObjectId();
                    if (nested == null || ObjectId.zeroId().equals(nested)) continue;
                    DirCacheEntry entry = new DirCacheEntry(walk.getRawPath());
                    entry.setFileMode(FileMode.GITLINK);
                    entry.setObjectId(nested);
                    builder.add(entry);
                    continue;
                }
                if (merged && !wt.isModified(staged, true, reader)) {
                    builder.add(copyOf(staged));
                    continue;
                }
                if (wt.getEntryLength() > WorkingTreeSnapshots.MAX_FILE_BYTES) {
                    // Too large to copy every few minutes: what is staged stands in for it.
                    if (merged) builder.add(copyOf(staged));
                    continue;
                }
                DirCacheEntry entry = new DirCacheEntry(walk.getRawPath());
                entry.setFileMode(mode);
                try (InputStream in = wt.openEntryStream()) {
                    entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, wt.getEntryContentLength(), in));
                }
                builder.add(entry);
            }
            builder.finish();
            ObjectId treeId = tree.writeTree(inserter);

            ObjectId headTree = headId == null ? null : revs.parseCommit(headId).getTree().getId();
            if (headTree == null ? tree.getEntryCount() == 0 : treeId.equals(headTree)) {
                return null;
            }
            Ref newest = newestSnapshot(repo);
            if (newest != null && treeId.equals(revs.parseCommit(newest.getObjectId()).getTree().getId())) {
                return null;
            }

            int changed = countChanged(repo, reader, headTree, treeId);
            Ref head = repo.exactRef(Constants.HEAD);
            String branch = head != null && head.isSymbolic()
                    ? Repository.shortenRefName(head.getTarget().getName())
                    : Constants.HEAD;

            PersonIdent ident = new PersonIdent(WorkingTreeSnapshots.AUTHOR_NAME,
                    WorkingTreeSnapshots.AUTHOR_EMAIL);
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            if (headId != null) commit.setParentId(headId);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(WorkingTreeSnapshots.message(reason, branch, changed));
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();

            String id = WorkingTreeSnapshots.newId(candidate -> exists(repo, candidate));
            RefUpdate update = repo.updateRef(WorkingTreeSnapshots.refName(id));
            update.setNewObjectId(commitId);
            update.setExpectedOldObjectId(ObjectId.zeroId());
            update.disableRefLog();
            RefUpdate.Result result = update.update(revs);
            if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD
                    && result != RefUpdate.Result.FORCED) {
                throw new GitOperationException("Could not record the snapshot: " + result);
            }
            return id;
        }
    }

    private static DirCacheEntry copyOf(DirCacheEntry staged) {
        DirCacheEntry entry = new DirCacheEntry(staged.getRawPath());
        entry.setFileMode(staged.getFileMode());
        entry.setObjectId(staged.getObjectId());
        return entry;
    }

    /** How many files differ between {@code headTree} (or nothing) and the snapshot's tree. */
    private static int countChanged(Repository repo, ObjectReader reader, ObjectId headTree,
                                    ObjectId treeId) throws Exception {
        try (TreeWalk diff = new TreeWalk(repo, reader)) {
            diff.setRecursive(true);
            if (headTree == null) {
                diff.addTree(new EmptyTreeIterator());
            } else {
                diff.addTree(headTree);
            }
            diff.addTree(treeId);
            diff.setFilter(TreeFilter.ANY_DIFF);
            int count = 0;
            while (diff.next()) count++;
            return count;
        }
    }

    /** The git directory whose legacy snapshots have been moved across, so it happens once. */
    private volatile File adopted;

    /**
     * Moves snapshots taken before the rename from {@code refs/jfxgit/snapshots/} to
     * {@link WorkingTreeSnapshots#REF_PREFIX}. Each is created under the new name before
     * the old one is deleted, so a failure part-way leaves a snapshot in one place or
     * both, never neither.
     */
    private void adoptLegacySnapshots(Repository repo) {
        File gitDir = repo.getDirectory();
        if (gitDir == null || gitDir.equals(adopted)) return;
        adopted = gitDir;
        try {
            for (Ref ref : repo.getRefDatabase().getRefsByPrefix(WorkingTreeSnapshots.LEGACY_REF_PREFIX)) {
                String id = ref.getName().substring(WorkingTreeSnapshots.LEGACY_REF_PREFIX.length());
                if (!WorkingTreeSnapshots.isId(id) || ref.getObjectId() == null || exists(repo, id)) continue;
                RefUpdate create = repo.updateRef(WorkingTreeSnapshots.refName(id));
                create.setNewObjectId(ref.getObjectId());
                create.setExpectedOldObjectId(ObjectId.zeroId());
                create.disableRefLog();
                if (create.update() != RefUpdate.Result.NEW) continue;
                RefUpdate delete = repo.updateRef(ref.getName());
                delete.setExpectedOldObjectId(ref.getObjectId());
                delete.setForceUpdate(true);
                delete.disableRefLog();
                delete.delete();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not move the old snapshots across", e);
        }
    }

    private static boolean exists(Repository repo, String id) {
        try {
            return repo.exactRef(WorkingTreeSnapshots.refName(id)) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static List<Ref> snapshotRefs(Repository repo) throws Exception {
        List<Ref> refs = new ArrayList<>();
        for (Ref ref : repo.getRefDatabase().getRefsByPrefix(WorkingTreeSnapshots.REF_PREFIX)) {
            String id = ref.getName().substring(WorkingTreeSnapshots.REF_PREFIX.length());
            if (WorkingTreeSnapshots.isId(id) && ref.getObjectId() != null) refs.add(ref);
        }
        refs.sort(Comparator.comparingLong(
                (Ref ref) -> Long.parseLong(ref.getName().substring(WorkingTreeSnapshots.REF_PREFIX.length())))
                .reversed());
        return refs;
    }

    private static Ref newestSnapshot(Repository repo) throws Exception {
        List<Ref> refs = snapshotRefs(repo);
        return refs.isEmpty() ? null : refs.get(0);
    }

    public List<Snapshot> listSnapshots() {
        Repository repo = repository();
        if (repo != null) adoptLegacySnapshots(repo);
        if (repo == null) return List.of();
        try (RevWalk walk = new RevWalk(repo)) {
            List<Snapshot> snapshots = new ArrayList<>();
            for (Ref ref : snapshotRefs(repo)) {
                RevCommit commit;
                try {
                    commit = walk.parseCommit(ref.getObjectId());
                } catch (Exception notACommit) {
                    continue;
                }
                String id = ref.getName().substring(WorkingTreeSnapshots.REF_PREFIX.length());
                Snapshot snapshot = WorkingTreeSnapshots.parse(id, commit.getName(), commit.getFullMessage());
                if (snapshot != null) snapshots.add(snapshot);
            }
            return snapshots;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not list snapshots", e);
            return List.of();
        }
    }

    public GitResult deleteSnapshot(String id) {
        Repository repo = repository();
        if (repo != null) adoptLegacySnapshots(repo);
        if (repo == null) return GitResult.failed("No repository open");
        if (!WorkingTreeSnapshots.isId(id)) return GitResult.failed("There is no snapshot " + id);
        try {
            String name = WorkingTreeSnapshots.refName(id);
            if (repo.exactRef(name) == null) return GitResult.failed("There is no snapshot " + id);
            RefUpdate update = repo.updateRef(name);
            update.setForceUpdate(true);
            update.disableRefLog();
            RefUpdate.Result result = update.delete();
            return switch (result) {
                case FORCED, NO_CHANGE, FAST_FORWARD -> GitResult.ok("Deleted the snapshot");
                default -> GitResult.failed("Could not delete the snapshot: " + result);
            };
        } catch (Exception e) {
            return GitResult.failed("Could not delete the snapshot: " + describe(e));
        }
    }

    /**
     * Writes each file as checkout would - through the smudge filter and line-ending
     * conversion its attributes ask for - and only where the working tree's copy differs,
     * so restoring everything does not touch every file's timestamp.
     */
    public GitResult restoreFromSnapshot(String id, List<String> paths) {
        Repository repo = repository();
        if (repo != null) adoptLegacySnapshots(repo);
        if (repo == null) return GitResult.failed("No repository open");
        if (!WorkingTreeSnapshots.isId(id)) return GitResult.failed("There is no snapshot " + id);
        boolean all = paths == null || paths.isEmpty();
        try (RevWalk revs = new RevWalk(repo)) {
            Ref ref = repo.exactRef(WorkingTreeSnapshots.refName(id));
            if (ref == null || ref.getObjectId() == null) return GitResult.failed("There is no snapshot " + id);
            RevCommit snapshot = revs.parseCommit(ref.getObjectId());
            if (!all) {
                for (String path : paths) {
                    try (TreeWalk found = TreeWalk.forPath(repo, path, snapshot.getTree())) {
                        if (found == null) return GitResult.failed(path + " is not in this snapshot");
                    }
                }
            }
            WorkingTreeOptions options = repo.getConfig().get(WorkingTreeOptions.KEY);
            File workTree = repo.getWorkTree();
            try (TreeWalk walk = new TreeWalk(repo)) {
                walk.setOperationType(TreeWalk.OperationType.CHECKOUT_OP);
                walk.addTree(snapshot.getTree());
                walk.addTree(new FileTreeIterator(repo));
                walk.setRecursive(true);
                if (!all) walk.setFilter(PathFilterGroup.createFromStrings(paths));
                while (walk.next()) {
                    FileMode mode = walk.getFileMode(0);
                    if (mode == FileMode.MISSING || mode == FileMode.GITLINK || mode == FileMode.TREE) continue;
                    String path = walk.getPathString();
                    File target = new File(workTree, path);
                    ObjectId blob = walk.getObjectId(0);
                    if (mode == FileMode.SYMLINK) {
                        String link = new String(repo.open(blob).getBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        if (Files.isSymbolicLink(target.toPath())
                                && link.equals(repo.getFS().readSymLink(target))) {
                            continue;
                        }
                        Files.deleteIfExists(target.toPath());
                        mkdirsFor(target);
                        repo.getFS().createSymLink(target, link);
                        continue;
                    }
                    ByteArrayOutputStream content = new ByteArrayOutputStream();
                    DirCacheCheckout.getContent(repo, path,
                            new DirCacheCheckout.CheckoutMetadata(
                                    walk.getEolStreamType(TreeWalk.OperationType.CHECKOUT_OP),
                                    walk.getFilterCommand(Constants.ATTR_FILTER_TYPE_SMUDGE)),
                            repo.open(blob), options, content);
                    byte[] wanted = content.toByteArray();
                    if (target.isFile() && !Files.isSymbolicLink(target.toPath())
                            && Arrays.equals(Files.readAllBytes(target.toPath()), wanted)) {
                        continue;
                    }
                    if (target.isDirectory()) {
                        return GitResult.failed("Could not restore " + path + ": a folder is in the way");
                    }
                    Files.deleteIfExists(target.toPath());
                    mkdirsFor(target);
                    Files.write(target.toPath(), wanted);
                    if (mode == FileMode.EXECUTABLE_FILE && repo.getFS().supportsExecute()) {
                        repo.getFS().setExecute(target, true);
                    }
                }
            }
            return GitResult.ok(restoredMessage(paths));
        } catch (Exception e) {
            return GitResult.failed("Could not restore from the snapshot: " + describe(e));
        }
    }

    private static void mkdirsFor(File target) throws java.io.IOException {
        File parent = target.getParentFile();
        if (parent != null) Files.createDirectories(parent.toPath());
    }

    /** The same words on every engine. */
    static String restoredMessage(List<String> paths) {
        if (paths == null || paths.isEmpty()) return "Restored the snapshot";
        return paths.size() == 1 ? "Restored " + paths.get(0) : "Restored " + paths.size() + " files";
    }

    /**
     * What {@code git add -A} would look at: everything the index tracks, and whatever in
     * the working tree is not ignored. An ignored folder is not entered at all, so a
     * {@code node_modules} costs one check rather than a walk.
     */
    private static final class TrackedOrNotIgnored extends TreeFilter {
        private final int dcIndex;
        private final int wtIndex;

        TrackedOrNotIgnored(int dcIndex, int wtIndex) {
            this.dcIndex = dcIndex;
            this.wtIndex = wtIndex;
        }

        @Override
        public boolean include(TreeWalk walker) throws java.io.IOException {
            if (walker.getTree(dcIndex, DirCacheIterator.class) != null) return true;
            WorkingTreeIterator wt = walker.getTree(wtIndex, WorkingTreeIterator.class);
            return wt == null || !wt.isEntryIgnored();
        }

        @Override
        public boolean shouldBeRecursive() {
            return false;
        }

        @Override
        public TreeFilter clone() {
            return this;
        }
    }
}
