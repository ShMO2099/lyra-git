package com.jfx.git.ui;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything a cancelled or refused operation must leave exactly as it found it, read
 * through JGit directly rather than through the engine under test: the checked-out branch,
 * every branch, remote-tracking, tag and stash ref, the current branch's upstream, the
 * index, the bytes of the top-level files, any operation in progress, and HEAD's reflog.
 *
 * <p>Compare two with {@code assertEquals}: a record's message names every field, so a
 * failure shows which part moved.
 */
record RepoSnapshot(String branch, Map<String, String> refs, Map<String, String> upstream,
                    List<String> index, Map<String, String> files, RepositoryState state,
                    List<String> headReflog) {

    static RepoSnapshot of(Path workTree) throws Exception {
        try (Git git = Git.open(workTree.toFile())) {
            Repository repo = git.getRepository();
            Map<String, String> refs = new TreeMap<>();
            for (Ref ref : repo.getRefDatabase().getRefs()) {
                String name = ref.getName();
                if (name.startsWith(Constants.R_HEADS) || name.startsWith(Constants.R_REMOTES)
                        || name.startsWith(Constants.R_TAGS) || name.equals(Constants.R_STASH)) {
                    refs.put(name, ref.getObjectId() == null ? "" : ref.getObjectId().name());
                }
            }
            List<String> index = new ArrayList<>();
            DirCache cache = repo.readDirCache();
            for (int i = 0; i < cache.getEntryCount(); i++) {
                var entry = cache.getEntry(i);
                index.add(entry.getPathString() + " " + entry.getStage() + " "
                        + entry.getFileMode() + " " + entry.getObjectId().name());
            }
            Map<String, String> files = new TreeMap<>();
            try (var listing = Files.list(workTree)) {
                for (Path file : listing.filter(Files::isRegularFile).toList()) {
                    files.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
                }
            }
            List<String> reflog = new ArrayList<>();
            var reader = repo.getRefDatabase().getReflogReader(Constants.HEAD);
            if (reader != null) {
                for (var entry : reader.getReverseEntries()) {
                    reflog.add(entry.getOldId().name() + " " + entry.getNewId().name() + " " + entry.getComment());
                }
            }
            return new RepoSnapshot(repo.getFullBranch(), refs, upstreamOf(repo, repo.getBranch()), index,
                    files, repo.getRepositoryState(), reflog);
        }
    }

    /** {@code branch.<name>.remote} and {@code .merge}, whichever are set. */
    static Map<String, String> upstreamOf(Repository repo, String branch) {
        Map<String, String> upstream = new LinkedHashMap<>();
        for (String key : List.of("remote", "merge")) {
            String value = repo.getConfig().getString("branch", branch, key);
            if (value != null) upstream.put(key, value);
        }
        return upstream;
    }
}
