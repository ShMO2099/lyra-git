package com.jfx.git.core;

import com.jfx.git.model.CommitSearch;

import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffConfig;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.StopWalkException;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.AndRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The JGit engine's reading of a {@link CommitSearch}: one {@link RevFilter} per
 * criterion, AND-ed, in the order that rejects most cheaply.
 *
 * <h2>Why these are hand-written rather than JGit's own</h2>
 * JGit ships {@code MessageRevFilter} and {@code AuthorRevFilter}, but both take a
 * regular expression and re-encode it against the raw commit buffer, while the query's
 * contract is a literal, case-insensitive substring - the {@code -F -i} the CLI engine
 * passes. Escaping user text into JGit's regex dialect to get it back out again is the
 * kind of round trip that works until someone searches for a backslash. Matching the
 * parsed strings directly is both simpler and exactly the contract.
 *
 * <p>The content criterion has no JGit counterpart at all: pickaxe is a diff feature, so
 * {@link ContentChange} diffs each commit against its first parent the way {@code git log
 * -G -M} does, which is also why it runs last.
 */
final class JGitCommitSearch {

    private JGitCommitSearch() {
    }

    /**
     * Blobs bigger than this are not diffed for a content search. Git would, but git
     * streams; here the whole blob is held in memory, and one generated file should not
     * be able to exhaust the heap in a search the user can simply narrow by path.
     */
    private static final int MAX_BLOB_BYTES = 16 * 1024 * 1024;

    /** The path criterion as a tree filter, or {@code null} when the query has none. */
    static TreeFilter pathFilter(CommitSearch query) {
        return query.path() == null ? null : PathFilterGroup.createFromStrings(query.path());
    }

    /**
     * Every non-path criterion as one filter. The path is applied separately, as the
     * walk's tree filter, because that is what lets JGit skip commits by tree comparison
     * without parsing their messages.
     *
     * @param paths the path criterion, which also restricts what the content diff looks at
     */
    static RevFilter revFilter(CommitSearch query, TreeFilter paths, DiffConfig diffConfig) {
        List<RevFilter> filters = new ArrayList<>();
        // First, so it is asked about every commit the walk considers - including the long
        // runs a selective filter rejects inside one call to next(), which is exactly
        // where a search spends its time.
        filters.add(new Cancellation());
        if (query.hashPrefix() != null) filters.add(new HashPrefix(query.hashPrefix()));
        if (query.author() != null) filters.add(new Ident(query.author(), false));
        if (query.committer() != null) filters.add(new Ident(query.committer(), true));
        if (!query.messageTerms().isEmpty()) filters.add(new MessageTerms(query.messageTerms()));
        if (query.content() != null) filters.add(new ContentChange(query.content(), paths, diffConfig));
        return filters.size() == 1 ? filters.get(0) : AndRevFilter.create(filters.toArray(new RevFilter[0]));
    }

    /** Ends the walk once the task running it has been cancelled. */
    private static final class Cancellation extends RevFilter {
        @Override
        public boolean include(RevWalk walker, RevCommit c) {
            if (GitTaskRunner.isCancelled()) throw StopWalkException.INSTANCE;
            return true;
        }

        @Override public RevFilter clone() { return this; }
        @Override public boolean requiresCommitBody() { return false; }
    }

    private static final class HashPrefix extends RevFilter {
        private final String prefix;

        HashPrefix(String prefix) {
            this.prefix = prefix.toLowerCase(Locale.ROOT);
        }

        @Override
        public boolean include(RevWalk walker, RevCommit c) {
            return c.getName().startsWith(prefix);
        }

        @Override public RevFilter clone() { return this; }
        @Override public boolean requiresCommitBody() { return false; }
    }

    /** Author or committer, matched against {@code "Name <email>"} as git's header grep is. */
    private static final class Ident extends RevFilter {
        private final String needle;
        private final boolean committer;

        Ident(String needle, boolean committer) {
            this.needle = needle.toLowerCase(Locale.ROOT);
            this.committer = committer;
        }

        @Override
        public boolean include(RevWalk walker, RevCommit c) {
            PersonIdent who = committer ? c.getCommitterIdent() : c.getAuthorIdent();
            if (who == null) return false;
            return CommitSearch.identity(who.getName(), who.getEmailAddress())
                    .toLowerCase(Locale.ROOT).contains(needle);
        }

        @Override public RevFilter clone() { return this; }
    }

    /** Every word somewhere in the full message, as {@code --grep ... --all-match} reads it. */
    private static final class MessageTerms extends RevFilter {
        private final List<String> terms;

        MessageTerms(List<String> terms) {
            this.terms = terms.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
        }

        @Override
        public boolean include(RevWalk walker, RevCommit c) {
            String message = c.getFullMessage().toLowerCase(Locale.ROOT);
            for (String term : terms) {
                if (!message.contains(term)) return false;
            }
            return true;
        }

        @Override public RevFilter clone() { return this; }
    }

    /**
     * The commit's own diff adds or removes a line containing the text - {@code git log
     * -G<text> -M -i}, literally.
     *
     * <p>Mirrors git's choices so the two engines return the same commits: a merge has no
     * diff of its own in {@code git log} unless asked for one, so it never matches; a root
     * commit is diffed against the empty tree ({@code log.showRoot}); renames are detected,
     * so a file that only moved has no added or removed lines; binary files are skipped, as
     * git skips them without {@code --text}; and the diff is Myers, git's default.
     */
    private static final class ContentChange extends RevFilter {
        private final String needle;
        private final TreeFilter paths;
        private final DiffConfig diffConfig;

        ContentChange(String needle, TreeFilter paths, DiffConfig diffConfig) {
            this.needle = needle.toLowerCase(Locale.ROOT);
            this.paths = paths;
            this.diffConfig = diffConfig;
        }

        @Override
        public boolean include(RevWalk walker, RevCommit c) throws IOException {
            if (c.getParentCount() > 1) return false;
            ObjectReader reader = walker.getObjectReader();
            List<DiffEntry> entries;
            try (TreeWalk tw = new TreeWalk(reader)) {
                tw.setRecursive(true);
                if (c.getParentCount() == 0) {
                    tw.addTree(new EmptyTreeIterator());
                } else {
                    RevCommit parent = c.getParent(0);
                    walker.parseHeaders(parent);
                    tw.addTree(parent.getTree());
                }
                tw.addTree(c.getTree());
                tw.setFilter(paths == null ? TreeFilter.ANY_DIFF
                        : AndTreeFilter.create(paths.clone(), TreeFilter.ANY_DIFF));
                entries = DiffEntry.scan(tw);
            }
            if (entries.isEmpty()) return false;
            RenameDetector renames = new RenameDetector(reader, diffConfig);
            renames.addAll(entries);
            try {
                entries = renames.compute(reader, NullProgressMonitor.INSTANCE);
            } catch (org.eclipse.jgit.api.errors.CanceledException e) {
                // Only a monitor can cancel it, and this one never does; but if it ever
                // did, ending the walk is the only honest reading.
                throw StopWalkException.INSTANCE;
            }
            for (DiffEntry entry : entries) {
                if (GitTaskRunner.isCancelled()) throw StopWalkException.INSTANCE;
                if (changesALineContaining(reader, entry)) return true;
            }
            return false;
        }

        private boolean changesALineContaining(ObjectReader reader, DiffEntry entry) throws IOException {
            if (entry.getOldMode() == FileMode.GITLINK || entry.getNewMode() == FileMode.GITLINK) {
                return false;
            }
            byte[] before = blob(reader, entry.getOldId().toObjectId());
            byte[] after = blob(reader, entry.getNewId().toObjectId());
            if (before == null || after == null) return false;
            if (RawText.isBinary(before) || RawText.isBinary(after)) return false;
            RawText a = new RawText(before);
            RawText b = new RawText(after);
            for (Edit edit : DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.MYERS)
                    .diff(RawTextComparator.DEFAULT, a, b)) {
                if (anyLineContains(a, edit.getBeginA(), edit.getEndA())
                        || anyLineContains(b, edit.getBeginB(), edit.getEndB())) {
                    return true;
                }
            }
            return false;
        }

        private boolean anyLineContains(RawText text, int from, int to) {
            for (int i = from; i < to; i++) {
                if (text.getString(i).toLowerCase(Locale.ROOT).contains(needle)) return true;
            }
            return false;
        }

        /** The blob's bytes; empty for the absent side of an add or delete; null when too big. */
        private static byte[] blob(ObjectReader reader, ObjectId id) throws IOException {
            if (id == null || ObjectId.zeroId().equals(id)) return new byte[0];
            try {
                return reader.open(id).getCachedBytes(MAX_BLOB_BYTES);
            } catch (LargeObjectException _) {
                return null;
            }
        }

        @Override
        public RevFilter clone() {
            return new ContentChange(needle, paths == null ? null : paths.clone(), diffConfig);
        }
    }
}
