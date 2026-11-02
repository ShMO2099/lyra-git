package com.jfx.git.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Commit message trailers - {@code Signed-off-by:}, {@code Co-authored-by:} - added the
 * way {@code git interpret-trailers} adds them.
 *
 * <h2>Why not just append a line</h2>
 * Everything that reads trailers - GitHub's co-author avatars, DCO bots, {@code git log
 * --format=%(trailers)} - only looks at the <em>last paragraph</em> of the message, and
 * only when that paragraph is a trailer block. A line tacked on without the blank line
 * before it becomes part of the body and is silently ignored; a second blank line before a
 * second trailer splits the block, and only the last one counts. So this follows git's
 * own rules (trailer.c):
 * <ul>
 *   <li>The first paragraph is the title and is never a trailer block, even if it looks
 *       like one.</li>
 *   <li>The last paragraph is a trailer block when every line is a trailer (or an
 *       indented continuation of one), or when it contains a trailer git itself writes
 *       ({@code Signed-off-by:}, {@code (cherry picked from commit}) and at least a
 *       quarter of its lines are trailers.</li>
 *   <li>A new trailer joins an existing block directly; otherwise it starts a new
 *       paragraph after exactly one blank line.</li>
 *   <li>A trailer already in the block - same token ignoring case, same value ignoring
 *       case, git's {@code same_trailer} - is not added again
 *       ({@code trailer.ifExists = addIfDifferent}).</li>
 * </ul>
 *
 * <p>Pure string work, so the composer can apply it on the FX thread and it can be tested
 * without a repository.
 */
public final class CommitTrailers {

    /** One trailer, as it appears in a block: {@code token: value}. */
    public record Trailer(String token, String value) {
        /** The line it is written as. */
        public String line() {
            return token + ": " + value;
        }
    }

    /**
     * git's {@code find_separator}: a token of letters, digits and dashes, optionally
     * followed by whitespace, then the separator. Nothing else may come before it.
     */
    private static final Pattern TRAILER = Pattern.compile("^([A-Za-z0-9-]+)[ \\t]*:[ \\t]*(.*)$");

    /** The prefixes git's own commands write; one of these lowers the bar for a block. */
    private static final List<String> GIT_GENERATED_PREFIXES =
            List.of("Signed-off-by: ", "(cherry picked from commit ");

    private CommitTrailers() {
    }

    /**
     * {@code message} with {@code token: value} added as a trailer - to the message's
     * trailer block when it ends with one, otherwise as a new paragraph after one blank
     * line. Returns the message unchanged (bar trailing whitespace) when that exact
     * trailer is already there.
     */
    public static String append(String message, String token, String value) {
        String text = normalise(message);
        if (text.isEmpty()) return new Trailer(token, value.strip()).line();
        List<String> lines = List.of(text.split("\n", -1));
        int titleEnd = endOfFirstParagraph(lines);
        if (titleEnd >= lines.size()) {
            // Only a title: the trailer is the second paragraph.
            return text + "\n\n" + new Trailer(token, value.strip()).line();
        }
        int titleLines = 0;
        while (!lines.get(titleLines).isBlank()) titleLines++;
        String title = String.join("\n", lines.subList(0, titleLines));
        String body = String.join("\n", lines.subList(titleEnd, lines.size()));
        return title + "\n\n" + appendToBody(body, token, value);
    }

    /**
     * {@link #append} for the part of a message after its title - what the composer's
     * description field holds. The title has already been written elsewhere, so here
     * even a single paragraph may be the trailer block.
     */
    public static String appendToBody(String body, String token, String value) {
        Trailer wanted = new Trailer(token.strip(), value.strip());
        String text = normalise(body);
        if (text.isEmpty()) return wanted.line();
        List<String> lines = List.of(text.split("\n", -1));
        int start = startOfLastParagraph(lines, 0);
        List<String> last = lines.subList(start, lines.size());
        if (!isTrailerBlock(last)) {
            return text + "\n\n" + wanted.line();
        }
        for (Trailer existing : parseBlock(last)) {
            if (sameTrailer(existing, wanted)) return text;
        }
        return text + "\n" + wanted.line();
    }

    /**
     * The trailers {@code message} ends with, in order, continuation lines folded into
     * their trailer's value; empty when its last paragraph is not a trailer block.
     */
    public static List<Trailer> trailers(String message) {
        String text = normalise(message);
        if (text.isEmpty()) return List.of();
        List<String> lines = List.of(text.split("\n", -1));
        int titleEnd = endOfFirstParagraph(lines);
        if (titleEnd >= lines.size()) return List.of();
        List<String> last = lines.subList(startOfLastParagraph(lines, titleEnd), lines.size());
        return isTrailerBlock(last) ? parseBlock(last) : List.of();
    }

    // ------------------------------------------------------------------

    /** Unix line endings, no trailing whitespace or blank lines. */
    private static String normalise(String message) {
        if (message == null) return "";
        return message.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
    }

    /** Index of the first line after the title paragraph and the blank lines after it. */
    private static int endOfFirstParagraph(List<String> lines) {
        int i = 0;
        while (i < lines.size() && !lines.get(i).isBlank()) i++;
        while (i < lines.size() && lines.get(i).isBlank()) i++;
        return i;
    }

    /** Index of the first line of the last paragraph, never before {@code floor}. */
    private static int startOfLastParagraph(List<String> lines, int floor) {
        int i = lines.size();
        while (i > floor && !lines.get(i - 1).isBlank()) i--;
        return i;
    }

    /**
     * git's test in {@code find_trailer_block_start}, walking the paragraph bottom-up:
     * indented lines are continuations of the trailer above them, and count against the
     * block only if what is above them turns out not to be a trailer.
     */
    private static boolean isTrailerBlock(List<String> paragraph) {
        int trailerLines = 0;
        int nonTrailerLines = 0;
        int possibleContinuations = 0;
        boolean recognisedPrefix = false;
        for (int i = paragraph.size() - 1; i >= 0; i--) {
            String line = paragraph.get(i);
            if (isGitGenerated(line)) {
                recognisedPrefix = true;
                trailerLines++;
                possibleContinuations = 0;
            } else if (TRAILER.matcher(line).matches()) {
                trailerLines++;
                possibleContinuations = 0;
            } else if (line.startsWith(" ") || line.startsWith("\t")) {
                possibleContinuations++;
            } else {
                nonTrailerLines += possibleContinuations + 1;
                possibleContinuations = 0;
            }
        }
        // Indented lines above the first trailer continue nothing.
        nonTrailerLines += possibleContinuations;
        if (trailerLines > 0 && nonTrailerLines == 0) return true;
        return recognisedPrefix && trailerLines * 3 >= nonTrailerLines;
    }

    private static boolean isGitGenerated(String line) {
        for (String prefix : GIT_GENERATED_PREFIXES) {
            if (line.startsWith(prefix)) return true;
        }
        return false;
    }

    private static List<Trailer> parseBlock(List<String> block) {
        List<Trailer> found = new ArrayList<>();
        for (String line : block) {
            Matcher m = TRAILER.matcher(line);
            if (m.matches()) {
                found.add(new Trailer(m.group(1), m.group(2).strip()));
            } else if ((line.startsWith(" ") || line.startsWith("\t")) && !found.isEmpty()) {
                Trailer previous = found.removeLast();
                found.add(new Trailer(previous.token(), (previous.value() + " " + line.strip()).strip()));
            }
        }
        return found;
    }

    /** git's {@code same_trailer}: both token and value compared ignoring case. */
    private static boolean sameTrailer(Trailer a, Trailer b) {
        return a.token().toLowerCase(Locale.ROOT).equals(b.token().toLowerCase(Locale.ROOT))
                && a.value().equalsIgnoreCase(b.value());
    }
}
