package com.jfx.git.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A search over the whole of history, as opposed to the graph's instant filter, which
 * can only look at the rows already loaded.
 *
 * <h2>Why a query object rather than a string</h2>
 * The graph's filter box used to be the only search there was, and it matched one string
 * against whatever the loaded page happened to hold - the subject and the author's name,
 * never the body, never the email, never which files a commit touched. A search that
 * reaches older history has to go to the engine, and each engine asks the question its
 * own way ({@code git log --grep --author -G -- path} for the CLI, a filtered RevWalk for
 * JGit). One immutable description of <em>what</em> is being looked for keeps the three
 * engines answering the same question, which {@code BackendConformanceTest} pins.
 *
 * <h2>Semantics every engine follows</h2>
 * <ul>
 *   <li>Every criterion given must hold (they are AND-ed, like {@code --all-match}).</li>
 *   <li>All text matching is case-insensitive and literal - no regular expressions, so a
 *       search for {@code a.b} finds {@code a.b} and not {@code axb}.</li>
 *   <li>{@link #message}: each whitespace-separated word must appear somewhere in the
 *       full message, subject or body, in any order - sourcegit's reading, and what
 *       {@code --grep=w1 --grep=w2 --all-match} does.</li>
 *   <li>{@link #author} / {@link #committer}: matched against {@code "Name <email>"}, the
 *       header line git itself greps, so a name, an email or part of either finds it.</li>
 *   <li>{@link #hashPrefix}: the commit's full hash starts with it.</li>
 *   <li>{@link #path}: the commit changed that file, or something under that
 *       directory, relative to its parent.</li>
 *   <li>{@link #content}: the commit's diff against its first parent adds or removes a
 *       line containing the text - {@code git log -G}, with renames detected, binary files
 *       and merge commits skipped, and limited to {@link #path} when one is given.</li>
 * </ul>
 *
 * <p>Every field is {@code null} when unused; blank values are normalised to {@code null}
 * by the constructor, so "not searching by X" has one representation.
 */
public record CommitSearch(String message, String author, String committer,
                           String hashPrefix, String path, String content) {

    /** Searches for nothing; {@link #isEmpty()} is true. */
    public static final CommitSearch EMPTY = new CommitSearch(null, null, null, null, null, null);

    public CommitSearch {
        message = blankToNull(message);
        author = blankToNull(author);
        committer = blankToNull(committer);
        hashPrefix = hashPrefix == null ? null : blankToNull(hashPrefix.toLowerCase(Locale.ROOT));
        path = normalisePath(path);
        // Not trimmed: leading or trailing spaces in a content search are how you ask for
        // " = null" rather than "=null", and pickaxe keeps them too.
        content = content == null || content.isEmpty() ? null : content;
    }

    public static CommitSearch byMessage(String text) {
        return EMPTY.withMessage(text);
    }

    public CommitSearch withMessage(String v) { return new CommitSearch(v, author, committer, hashPrefix, path, content); }
    public CommitSearch withAuthor(String v) { return new CommitSearch(message, v, committer, hashPrefix, path, content); }
    public CommitSearch withCommitter(String v) { return new CommitSearch(message, author, v, hashPrefix, path, content); }
    public CommitSearch withHashPrefix(String v) { return new CommitSearch(message, author, committer, v, path, content); }
    public CommitSearch withPath(String v) { return new CommitSearch(message, author, committer, hashPrefix, v, content); }
    public CommitSearch withContent(String v) { return new CommitSearch(message, author, committer, hashPrefix, path, v); }

    /** Whether nothing at all is being searched for - engines return no commits for it. */
    public boolean isEmpty() {
        return message == null && author == null && committer == null
                && hashPrefix == null && path == null && content == null;
    }

    /** {@link #message} split into the words that must each appear; empty when unused. */
    public List<String> messageTerms() {
        if (message == null) return List.of();
        List<String> terms = new ArrayList<>();
        for (String word : message.trim().split("\\s+")) {
            if (!word.isEmpty()) terms.add(word);
        }
        return terms;
    }

    /**
     * Whether the query can be answered from a {@link CommitInfo} alone, without asking
     * the engine - which is what lets the graph's instant filter apply a prefixed query to
     * the loaded rows. Committer, path and content all need the repository.
     */
    public boolean locallyDecidable() {
        return committer == null && path == null && content == null;
    }

    /**
     * The same answer an engine would give for this commit, for the criteria {@link
     * #locallyDecidable()} covers; criteria it does not cover are ignored.
     *
     * <p>A graph row is a {@linkplain CommitInfo#summary summary} (G15): its subject and
     * the start of its body, not the whole message. The message words are looked for in
     * what it has, and a word that is not there only rules the commit out when the body
     * preview was the whole body. Otherwise the rest of the body is unknown here, and the
     * commit is kept - hiding a row whose unread body may well match would be the filter
     * quietly claiming a miss it cannot know; Enter searches whole messages.
     */
    public boolean matchesLocally(CommitInfo commit) {
        if (commit == null) return false;
        if (hashPrefix != null && !commit.getHash().toLowerCase(Locale.ROOT).startsWith(hashPrefix)) {
            return false;
        }
        if (author != null && !containsIgnoreCase(
                identity(commit.getAuthorName(), commit.getAuthorEmail()), author)) {
            return false;
        }
        List<String> terms = messageTerms();
        if (terms.isEmpty()) return true;
        String known;
        boolean whole;
        if (commit.hasFullMessage()) {
            known = commit.getFullMessage();
            whole = true;
        } else {
            // Words hold no whitespace, so the flattened preview finds exactly what the
            // body would. The cut marker is left out: it is not part of the message.
            String preview = commit.getBodySummary();
            whole = commit.isBodySummaryWhole();
            if (!whole) preview = preview.substring(0, preview.length() - 2);
            known = commit.getSubject() + "\n" + preview;
        }
        for (String term : terms) {
            if (!containsIgnoreCase(known, term)) return !whole;
        }
        return true;
    }

    /** {@code "Name <email>"}, the form git's own {@code --author} greps. */
    public static String identity(String name, String email) {
        return (name == null ? "" : name) + " <" + (email == null ? "" : email) + ">";
    }

    /** Case-insensitive substring test, the one every engine's matching reduces to. */
    public static boolean containsIgnoreCase(String haystack, String needle) {
        if (needle == null) return true;
        if (haystack == null) return false;
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------
    // Parsing the filter box
    // ------------------------------------------------------------------

    /**
     * The prefixes {@link #parse} understands, in the form they are typed. {@code -G} is
     * there because anyone who knows pickaxe types it without thinking.
     */
    private static final List<String> PREFIXES = List.of(
            "message:", "msg:", "author:", "committer:", "hash:", "path:", "content:", "-G:");

    /**
     * Reads what was typed in the filter box.
     *
     * <p>{@code author:ada path:src/ fix login} searches for Ada's commits under
     * {@code src/} whose message mentions both "fix" and "login". A value with spaces is
     * quoted: {@code author:"Ada Lovelace"}. Text without a prefix is message text, so
     * a plain query means what it meant before prefixes existed. A prefix given twice
     * keeps its last value.
     */
    public static CommitSearch parse(String text) {
        if (text == null || text.isBlank()) return EMPTY;
        CommitSearch search = EMPTY;
        List<String> loose = new ArrayList<>();
        for (Token token : tokens(text)) {
            String prefix = token.prefix();
            String value = token.value();
            if (prefix == null) {
                loose.add(value);
                continue;
            }
            search = switch (prefix) {
                case "message:", "msg:" -> search.withMessage(joinMessage(search.message(), value));
                case "author:" -> search.withAuthor(value);
                case "committer:" -> search.withCommitter(value);
                case "hash:" -> search.withHashPrefix(value);
                case "path:" -> search.withPath(value);
                default -> search.withContent(value); // content: and -G:
            };
        }
        if (!loose.isEmpty()) {
            search = search.withMessage(joinMessage(search.message(), String.join(" ", loose)));
        }
        return search;
    }

    /** Whether {@code text} uses any of the prefixes {@link #parse} understands. */
    public static boolean hasPrefix(String text) {
        if (text == null || text.isBlank()) return false;
        for (Token token : tokens(text)) {
            if (token.prefix() != null) return true;
        }
        return false;
    }

    private static String joinMessage(String existing, String more) {
        return existing == null ? more : existing + " " + more;
    }

    private record Token(String prefix, String value) { }

    /**
     * Splits on whitespace outside double quotes, recognising a prefix only at the start
     * of a word - so {@code fix: thing}, a common subject shape, stays plain text.
     */
    private static List<Token> tokens(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= n) break;
            String prefix = null;
            for (String p : PREFIXES) {
                if (text.regionMatches(true, i, p, 0, p.length())) {
                    prefix = p.equals("-G:") ? p : p.toLowerCase(Locale.ROOT);
                    i += p.length();
                    break;
                }
            }
            StringBuilder value = new StringBuilder();
            boolean quoted = false;
            while (i < n) {
                char c = text.charAt(i);
                if (c == '"') {
                    quoted = !quoted;
                } else if (!quoted && Character.isWhitespace(c)) {
                    break;
                } else {
                    value.append(c);
                }
                i++;
            }
            if (prefix != null || !value.isEmpty()) {
                tokens.add(new Token(prefix, value.toString()));
            }
        }
        return tokens;
    }

    /**
     * A repository-relative path in git's form: forward slashes, no leading {@code ./}
     * or {@code /}, no trailing slash. {@code null} for the repository root, which would
     * restrict nothing.
     */
    private static String normalisePath(String path) {
        if (path == null) return null;
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() || p.equals(".") ? null : p;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** A short human description, for the results header. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        if (message != null) parts.add("message “" + message + "”");
        if (author != null) parts.add("author “" + author + "”");
        if (committer != null) parts.add("committer “" + committer + "”");
        if (hashPrefix != null) parts.add("hash " + hashPrefix);
        if (path != null) parts.add("path " + path);
        if (content != null) parts.add("change “" + content + "”");
        return String.join(" · ", parts);
    }
}
