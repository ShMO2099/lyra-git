package com.jfx.git.core;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Writes a commit the way {@code git format-patch} does: a mail message with the author,
 * date and commit message in its headers and body, and the diff after a {@code ---} line.
 *
 * <p>JGit has no {@code format-patch}. It has a diff formatter, and a bare diff is a
 * patch of a kind - but one that has lost who made the change and why, so applying it
 * can only ever produce uncommitted edits. The mail form is what {@code git am} turns
 * back into the commit, and it is what anyone receiving a patch from a git user expects
 * to be sent.
 *
 * <p>What it deliberately does not reproduce: the diffstat between {@code ---} and the
 * diff, which is a summary for a human reader and is ignored by everything that applies
 * a patch; series numbering in the subject; and git's binary patch encoding, which JGit
 * cannot write - see {@link #binaryPath}.
 */
final class PatchWriter {

    /** RFC 2822, in English whatever the machine's locale: a mail header is not prose. */
    private static final DateTimeFormatter MAIL_DATE =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US);

    private PatchWriter() {
    }

    /**
     * The first file {@code commit} changes that is binary, or {@code null} when every
     * change in it is text.
     *
     * <p>JGit writes "Binary files differ" for such a change. That reads like a patch
     * and applies as nothing: the receiving side gets the commit without the file's new
     * content. Callers refuse rather than write it.
     */
    static String binaryPath(Repository repository, RevCommit commit) throws IOException {
        try (ObjectReader reader = repository.newObjectReader();
             DiffFormatter formatter = new DiffFormatter(new ByteArrayOutputStream())) {
            formatter.setRepository(repository);
            for (DiffEntry entry : formatter.scan(parentTree(reader, commit), tree(reader, commit))) {
                FileHeader header = formatter.toFileHeader(entry);
                if (header.getPatchType() != FileHeader.PatchType.UNIFIED) {
                    return entry.getChangeType() == DiffEntry.ChangeType.DELETE
                            ? entry.getOldPath() : entry.getNewPath();
                }
            }
            return null;
        }
    }

    /** Appends {@code commit} to {@code out} as one message of an mbox. */
    static void write(Repository repository, RevCommit commit, ByteArrayOutputStream out) throws IOException {
        PersonIdent author = commit.getAuthorIdent();
        String message = commit.getFullMessage();
        // The subject is the first paragraph on one line; everything after it is the body.
        int paragraphEnd = message.indexOf("\n\n");
        String body = paragraphEnd < 0 ? "" : message.substring(paragraphEnd + 2).stripTrailing();

        StringBuilder head = new StringBuilder();
        // The fixed date is git's own marker for "this is a patch, not a mailbox entry".
        head.append("From ").append(commit.getName()).append(" Mon Sep 17 00:00:00 2001\n");
        head.append("From: ").append(author.getName()).append(" <").append(author.getEmailAddress()).append(">\n");
        head.append("Date: ").append(MAIL_DATE.format(ZonedDateTime.ofInstant(
                author.getWhenAsInstant(), author.getZoneId()))).append('\n');
        head.append("Subject: [PATCH] ").append(commit.getShortMessage()).append('\n');
        // Declared rather than encoded word by word: names and subjects go out as the
        // UTF-8 they are, which is what git am assumes of a header with no charset.
        head.append("MIME-Version: 1.0\n");
        head.append("Content-Type: text/plain; charset=UTF-8\n");
        head.append("Content-Transfer-Encoding: 8bit\n");
        head.append('\n');
        if (!body.isEmpty()) {
            head.append(body).append('\n');
        }
        head.append("---\n\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));

        try (ObjectReader reader = repository.newObjectReader();
             DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.setRepository(repository);
            List<DiffEntry> entries = formatter.scan(parentTree(reader, commit), tree(reader, commit));
            formatter.format(entries);
            formatter.flush();
        }
        out.write("-- \nLyra\n\n".getBytes(StandardCharsets.UTF_8));
    }

    private static AbstractTreeIterator tree(ObjectReader reader, RevCommit commit) throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, commit.getTree());
        return parser;
    }

    /** The first parent's tree, or an empty one for a commit that has no parent. */
    private static AbstractTreeIterator parentTree(ObjectReader reader, RevCommit commit) throws IOException {
        if (commit.getParentCount() == 0) {
            return new EmptyTreeIterator();
        }
        try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(reader)) {
            RevCommit parent = walk.parseCommit(commit.getParent(0));
            CanonicalTreeParser parser = new CanonicalTreeParser();
            parser.reset(reader, parent.getTree());
            return parser;
        }
    }
}
