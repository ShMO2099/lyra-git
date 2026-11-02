package com.jfx.git.graph;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.Locale;

/**
 * Author avatars, drawn from the author's own name and address.
 *
 * <p>Initials on a disc whose hue is hashed from the email, which gets the thing avatars
 * are actually for — telling at a glance that a run of commits is by one person — without
 * an account, a network fetch, or a third party learning which repositories are open.
 *
 * <p>Nothing here produces an image. The initials and the hue are the whole avatar, and
 * both are cheap to derive, so a caller draws them: {@link AuthorBadge} as scene-graph
 * nodes, and the commit graph straight onto its canvas with {@link #paintOn}. Avatars were
 * rasterised and cached once; a bitmap is a fixed grid of pixels and every way the
 * interface can grow — a dense display, the zoom — stretched it into something visibly
 * blocky next to text drawn at full resolution.
 */
public final class Avatars {

    private Avatars() {}

    /**
     * Hues an avatar can take.
     *
     * <p>Chosen to stay apart from the lane palette: an avatar sitting beside a coloured
     * lane must not read as being on it. Text is placed on top by contrast, not by theme,
     * so a hue can be used on either ground.
     */
    private static final Color[] HUES = {
            Color.web("#9fd0ff"),
            Color.web("#a8e6a3"),
            Color.web("#e5b6ff"),
            Color.web("#ffc79a"),
            Color.web("#ffb3ad"),
            Color.web("#f2dd8c"),
            Color.web("#9de3e8"),
            Color.web("#ffb8d8")
    };

    /**
     * Up to two letters standing for the author.
     *
     * <p>First letters of the first and last word of the name, so "Ada Lovelace" gives
     * "AL". A single word gives its first two letters, which separates "Shvan" from
     * "Steve" where a bare "S" would not. Falls back to the email when there is no name.
     */
    static String initials(String name, String email) {
        String source = name == null || name.isBlank() ? localPart(email) : name;
        if (source == null || source.isBlank()) return "?";

        String[] words = WORD_BREAK.split(source.trim());
        if (words.length == 0 || words[0].isEmpty()) return "?";

        if (words.length == 1) {
            String word = words[0];
            return (word.length() == 1 ? word : word.substring(0, 2)).toUpperCase(Locale.ROOT);
        }
        String first = words[0].substring(0, 1);
        String last = words[words.length - 1].substring(0, 1);
        return (first + last).toUpperCase(Locale.ROOT);
    }

    /**
     * The part of an address before the {@code @}.
     *
     * <p>Only that part says who someone is. Splitting the whole address gave
     * {@code ada@example.com} the initials "AC" — the A of "ada" and the C of the
     * top-level domain — so every author at a {@code .com} address whose name began with
     * the same letter got the same two letters.
     */
    private static String localPart(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    /** A stable hue for an identity, so an author keeps the same colour between sessions. */
    static Color hue(String identity) {
        int hash = identity == null ? 0 : identity.hashCode();
        return HUES[Math.floorMod(hash, HUES.length)];
    }


    /**
     * The key an author is known by: their address, or their name when there is none.
     *
     * <p>Lower-cased and trimmed, so the same person committing as {@code Ada@x.com} and
     * {@code ada@x.com} keeps one colour rather than appearing to be two people.
     */
    public static String identityOf(String name, String email) {
        return email == null || email.isBlank()
                ? (name == null ? "" : name)
                : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Draws an author's disc straight onto {@code gc}, centred on {@code (cx, cy)}.
     *
     * <p>For the commit graph, whose nodes are painted rather than laid out. Drawing here
     * rather than blitting a prepared bitmap removes a rasterisation the canvas would only
     * resample anyway: the oval and the letters are emitted at whatever resolution the
     * canvas itself is being drawn at.
     */
    public static void paintOn(GraphicsContext gc, String name, String email,
                               double cx, double cy, double radius) {
        Author author = author(name, email);
        gc.setFill(author.hue());
        gc.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);

        gc.setFill(author.text());
        gc.setFont(fontFor(radius * 0.84));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(javafx.geometry.VPos.CENTER);
        gc.fillText(author.initials(), cx, cy + radius * 0.04);
    }

    /** Splits a name into words; compiled once rather than by {@code String.split} per row. */
    private static final java.util.regex.Pattern WORD_BREAK = java.util.regex.Pattern.compile("[\\s._@-]+");

    /** Everything an avatar draws, derived once per author. */
    record Author(String initials, Color hue, Color text) {
    }

    /**
     * Authors already derived, by name and then address.
     *
     * <h2>Why memoised</h2>
     * The graph paints an avatar on every row of every scroll tick, and each one used to
     * lower-case the address, split the name with a regular expression and look the font
     * up again - for the same few dozen people, over and over. Two nested maps rather than
     * one keyed by a joined string, so a lookup allocates nothing.
     *
     * <p>Bounded by the number of distinct authors on screen over a session, which is
     * small; the cap is only a backstop against a pathological history.
     */
    private static final java.util.Map<String, java.util.Map<String, Author>> AUTHORS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int AUTHOR_CAP = 20_000;
    private static int authorCount;

    static Author author(String name, String email) {
        String byName = name == null ? "" : name;
        String byEmail = email == null ? "" : email;
        var forName = AUTHORS.get(byName);
        if (forName != null) {
            Author known = forName.get(byEmail);
            if (known != null) return known;
        }
        Color hue = hue(identityOf(name, email));
        Author author = new Author(initials(name, email), hue, GraphPalette.textOn(hue));
        if (authorCount >= AUTHOR_CAP) {
            AUTHORS.clear();
            authorCount = 0;
        }
        AUTHORS.computeIfAbsent(byName, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(byEmail, author);
        authorCount++;
        return author;
    }

    /**
     * The initials font at the size last asked for. A graph asks for one size until the
     * density or the zoom changes, so one entry is the whole cache - and comparing a
     * double allocates nothing, where a map keyed by size boxed it on every row.
     */
    private record SizedFont(double size, Font font) {
    }

    private static volatile SizedFont lastFont;

    private static Font fontFor(double size) {
        SizedFont known = lastFont;
        if (known == null || known.size() != size) {
            known = new SizedFont(size, Font.font("System", FontWeight.BOLD, size));
            lastFont = known;
        }
        return known.font();
    }
}
