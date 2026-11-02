package com.jfx.git.ui;

import com.jfx.git.model.LfsPointer;

import java.util.List;
import java.util.function.Function;

/**
 * Recognises Git LFS pointer files, and turns one (or a diff between two) into a
 * message worth showing instead of the pointer's own raw text.
 *
 * <p>A pointer file is a handful of plain-ASCII lines - {@code version}, {@code oid},
 * {@code size} - so nothing about it looks binary to Git: a change to one diffs as an
 * ordinary handful of text lines, the {@code oid}/{@code size} rewritten, which reads
 * as meaningful content only if you do not know what a pointer file is. This exists so
 * {@link DiffViewer} can catch that before it renders, the same way it already catches
 * an actual binary diff.
 *
 * <p>Kept separate from {@link DiffParser} rather than folded into {@link
 * DiffParser#summarize}: that method reads only the preamble above the first {@code
 * @@}, and a pointer's own oid/size lines live inside the hunk, not the preamble.
 */
public final class LfsPointerParser {

    /** The one line every pointer file starts with - not tunable, it is the spec. */
    private static final String VERSION_LINE = "version https://git-lfs.github.com/spec/v1";

    /**
     * The real spec has no hard cap, but every real pointer file is a few hundred
     * bytes. Rejecting anything larger outright, before even checking its first line,
     * means a large real text file can never be misread as a pointer just because one
     * of its lines happens to match the version string.
     */
    private static final int MAX_POINTER_LENGTH = 1024;

    private LfsPointerParser() {
    }

    /**
     * Parses {@code content} as a pointer file, or answers {@code null} when it plainly
     * is not one - including when it is too large to plausibly be one at all.
     */
    public static LfsPointer parse(String content) {
        if (content == null || content.isEmpty() || content.length() > MAX_POINTER_LENGTH) {
            return null;
        }
        String[] lines = content.split("\n", -1);
        if (lines.length < 3 || !lines[0].equals(VERSION_LINE)) {
            return null;
        }

        String oid = null;
        Long size = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue; // the trailing newline every pointer file ends with
            }
            int space = line.indexOf(' ');
            if (space <= 0 || space == line.length() - 1) {
                return null; // a real pointer file's extension lines are all "key value"
            }
            String key = line.substring(0, space);
            String value = line.substring(space + 1);
            switch (key) {
                case "oid" -> oid = value;
                case "size" -> {
                    try {
                        size = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                default -> {
                    // An extension line this parser does not know about. The spec
                    // requires compliant tools to preserve unknown extensions rather
                    // than reject them, so a pointer with one is still a pointer.
                }
            }
        }
        if (oid == null || size == null || size < 0 || !looksLikeOid(oid)) {
            return null;
        }
        return new LfsPointer(oid, size);
    }

    /** {@code <algorithm>:<hex digest>} - not pinned to sha256 specifically, but shaped like one. */
    private static boolean looksLikeOid(String oid) {
        int colon = oid.indexOf(':');
        if (colon <= 0 || colon == oid.length() - 1) {
            return false;
        }
        String hex = oid.substring(colon + 1);
        for (int i = 0; i < hex.length(); i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** The old side of a diff's hunks, reconstructed and parsed - {@code null} if it is not a pointer. */
    public static LfsPointer oldSide(List<DiffParser.Hunk> hunks) {
        return parse(joinedLines(hunks, DiffParser.Hunk::oldLines));
    }

    /** The new side of a diff's hunks, reconstructed and parsed - {@code null} if it is not a pointer. */
    public static LfsPointer newSide(List<DiffParser.Hunk> hunks) {
        return parse(joinedLines(hunks, DiffParser.Hunk::newLines));
    }

    /**
     * A pointer file is small enough that a change to it is always exactly one hunk
     * covering the whole file in practice; concatenating every hunk's matching side in
     * order still reconstructs that side correctly even if it were not.
     */
    private static String joinedLines(List<DiffParser.Hunk> hunks,
                                      Function<DiffParser.Hunk, List<String>> side) {
        if (hunks == null || hunks.isEmpty()) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (DiffParser.Hunk hunk : hunks) {
            for (String line : side.apply(hunk)) {
                joined.append(line).append('\n');
            }
        }
        return joined.toString();
    }

    /** The notice for viewing a pointer file's whole content, or {@code null} if it is not one. */
    public static String describeFile(LfsPointer pointer) {
        if (pointer == null) {
            return null;
        }
        return "Tracked by Git LFS — " + ImageDiffView.humanSize(pointer.size()) + ", not fetched.\n\n"
                + "Run \"git lfs pull\" to download this file's real content.";
    }

    /**
     * The notice for a diff where either side is a pointer, or {@code null} when
     * neither is - meaning this diff has nothing to do with LFS at all.
     */
    public static String describeDiff(LfsPointer oldPointer, LfsPointer newPointer) {
        String body;
        if (oldPointer != null && newPointer != null) {
            body = "Tracked by Git LFS — binary content changed ("
                    + ImageDiffView.humanSize(oldPointer.size()) + " → "
                    + ImageDiffView.humanSize(newPointer.size()) + "), not fetched.";
        } else if (newPointer != null) {
            body = "New file tracked by Git LFS — " + ImageDiffView.humanSize(newPointer.size())
                    + ", not fetched.";
        } else if (oldPointer != null) {
            body = "Removed a file tracked by Git LFS (was "
                    + ImageDiffView.humanSize(oldPointer.size()) + ").";
        } else {
            return null;
        }
        return body + "\n\nRun \"git lfs pull\" to download LFS content.";
    }
}
