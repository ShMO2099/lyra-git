package com.jfx.git.core;

import com.jfx.git.model.GrepMatch;
import com.jfx.git.model.GrepQuery;

import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The JGit engine's {@code git grep -n -I}: the files of a tree or of the working tree,
 * read line by line, in git's order.
 *
 * <h2>Matching git rather than approximating it</h2>
 * The CLI engine hands the same query to git, so each choice here is git's own: binary
 * means a NUL in the first 8000 bytes ({@code buffer_is_binary}); only regular files are
 * read, never a symbolic link or a submodule; the working tree is every file on disk that
 * no ignore rule covers, tracked or not, sorted by path - which is how {@code --untracked}
 * lists them.
 *
 * <p>Lines are matched with {@link TextSearch}'s reading of the query, the find bar's, so
 * whole-word means what it means there.
 */
final class JGitGrep {

    /** How far into a file git looks for a NUL before calling it binary. */
    private static final int BINARY_PROBE = 8000;

    /**
     * Files bigger than this are not read. git streams; here the whole file is held in
     * memory, and one generated file should not be able to exhaust the heap - the same
     * cap {@link JGitCommitSearch} puts on a blob.
     */
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

    private JGitGrep() {
    }

    static List<GrepMatch> grep(Repository repository, String revOrNull, GrepQuery query, int limit) {
        if (repository == null || query == null || query.isEmpty() || limit <= 0) return List.of();
        Pattern pattern;
        try {
            pattern = TextSearch.compile(query.text(),
                    new TextSearch.Options(query.matchCase(), query.wholeWord(), query.regex()));
        } catch (PatternSyntaxException e) {
            throw new GitOperationException("Search in files failed: " + e.getDescription());
        }
        List<GrepMatch> found = new ArrayList<>();
        try {
            if (revOrNull == null) {
                grepWorkingTree(repository, query, pattern, limit, found);
            } else {
                grepTree(repository, revOrNull, query, pattern, limit, found);
            }
        } catch (IOException e) {
            throw new GitOperationException("Search in files failed: " + JGitPart.describe(e));
        }
        return found;
    }

    private static void grepTree(Repository repository, String rev, GrepQuery query, Pattern pattern,
                                 int limit, List<GrepMatch> found) throws IOException {
        ObjectId tree = repository.resolve(rev + "^{tree}");
        if (tree == null) {
            throw new GitOperationException("Search in files failed: " + rev + " is not a revision");
        }
        try (ObjectReader reader = repository.newObjectReader(); TreeWalk walk = new TreeWalk(reader)) {
            walk.addTree(tree);
            walk.setRecursive(true);
            while (walk.next() && found.size() < limit) {
                if (GitTaskRunner.isCancelled()) return;
                if (!isRegularFile(walk.getFileMode(0))) continue;
                String path = walk.getPathString();
                if (!query.matchesPath(path)) continue;
                byte[] bytes = readBlob(reader, walk.getObjectId(0));
                if (bytes != null) scan(path, bytes, pattern, limit, found);
            }
        }
    }

    private static void grepWorkingTree(Repository repository, GrepQuery query, Pattern pattern,
                                        int limit, List<GrepMatch> found) throws IOException {
        File workTree = repository.getWorkTree();
        for (String path : workingTreeFiles(repository, query)) {
            if (GitTaskRunner.isCancelled() || found.size() >= limit) return;
            byte[] bytes = readFile(new File(workTree, path));
            if (bytes != null) scan(path, bytes, pattern, limit, found);
        }
    }

    /**
     * The working tree's regular files that no ignore rule covers, tracked or not, sorted
     * by path - the files {@code git grep --untracked} reads. A file git tracks but an
     * ignore rule covers is left out, as git leaves it out, and an ignored folder is not
     * entered: that is where a build's thousands of files are.
     */
    private static List<String> workingTreeFiles(Repository repository, GrepQuery query) throws IOException {
        List<String> files = new ArrayList<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(new FileTreeIterator(repository));
            walk.setRecursive(false);
            while (walk.next()) {
                if (GitTaskRunner.isCancelled()) break;
                WorkingTreeIterator file = walk.getTree(0, WorkingTreeIterator.class);
                if (file == null || file.isEntryIgnored()) continue;
                if (walk.isSubtree()) {
                    walk.enterSubtree();
                    continue;
                }
                if (!isRegularFile(walk.getFileMode(0))) continue;
                String path = walk.getPathString();
                if (query.matchesPath(path)) files.add(path);
            }
        }
        files.sort(null);
        return files;
    }

    private static boolean isRegularFile(FileMode mode) {
        return mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE;
    }

    private static byte[] readBlob(ObjectReader reader, ObjectId id) throws IOException {
        if (id == null || !reader.has(id)) return null;
        ObjectLoader loader = reader.open(id);
        if (loader.getSize() > MAX_FILE_BYTES) return null;
        return loader.getCachedBytes((int) MAX_FILE_BYTES);
    }

    private static byte[] readFile(File file) {
        try {
            if (!file.isFile() || file.length() > MAX_FILE_BYTES) return null;
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            // Deleted or locked since the index was read: git skips it too.
            return null;
        }
    }

    /** Adds the matching lines of one file, unless it looks binary. */
    private static void scan(String path, byte[] bytes, Pattern pattern, int limit, List<GrepMatch> found) {
        int probe = Math.min(bytes.length, BINARY_PROBE);
        for (int i = 0; i < probe; i++) {
            if (bytes[i] == 0) return;
        }
        int lineNumber = 0;
        int start = 0;
        Matcher matcher = pattern.matcher("");
        while (start < bytes.length && found.size() < limit) {
            int end = start;
            while (end < bytes.length && bytes[end] != '\n') end++;
            lineNumber++;
            int textEnd = end > start && bytes[end - 1] == '\r' ? end - 1 : end;
            String line = new String(bytes, start, textEnd - start, StandardCharsets.UTF_8);
            if (matcher.reset(line).find()) {
                found.add(new GrepMatch(path, lineNumber, line));
            }
            start = end + 1;
        }
    }
}
