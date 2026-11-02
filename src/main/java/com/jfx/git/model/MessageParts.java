package com.jfx.git.model;

/**
 * A commit message as git's convention shapes it: a summary line, one blank line, and a
 * description.
 *
 * <p>Apart from the editors that show it, so that what joins and splits a message needs no
 * toolkit: the commit composer is checked without one, and it joins every message it sends
 * and splits every one it puts back.
 */
public record MessageParts(String summary, String description) {

    /** Git's subject/body convention: two parts, one blank line between them. */
    public static String join(String summary, String description) {
        String subject = summary == null ? "" : summary.strip();
        String body = description == null ? "" : description.strip();
        if (subject.isEmpty()) return body;
        return body.isEmpty() ? subject : subject + "\n\n" + body;
    }

    /** The inverse of {@link #join}: an existing message back into its summary and description. */
    public static MessageParts split(String message) {
        if (message == null) return new MessageParts("", "");
        String text = message.strip();
        int blank = text.indexOf("\n\n");
        if (blank >= 0) {
            return new MessageParts(text.substring(0, blank), text.substring(blank + 2).strip());
        }
        int newline = text.indexOf('\n');
        if (newline >= 0) {
            // A single-newline message is tolerated: the first line stays the summary.
            return new MessageParts(text.substring(0, newline), text.substring(newline + 1).strip());
        }
        return new MessageParts(text, "");
    }
}
