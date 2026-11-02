package com.jfx.git.core;

import org.eclipse.jgit.lib.CommitConfig.CleanupMode;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.UserConfig;

import java.util.List;
import java.util.Locale;

/**
 * What a JGit commit needs to know before it may be made, read the way git would read it.
 *
 * <h2>Why this exists</h2>
 * JGit's {@code CommitCommand} answers every one of these questions itself, and answers
 * several of them differently from git:
 * <ul>
 *   <li><b>Identity.</b> It reads JGit's config, which drops {@code includeIf}, so a
 *       work repository gets the personal email; and with no identity at all it invents
 *       {@code <osuser>@<hostname>} where git refuses.</li>
 *   <li><b>Signing.</b> The same blind spot hides a {@code commit.gpgsign = true} that
 *       lives in a conditionally included file.</li>
 *   <li><b>Message cleanup.</b> It stores the message verbatim; git, given a message
 *       with {@code -m} or {@code -F}, strips trailing whitespace and surplus blank
 *       lines unless {@code commit.cleanup} says otherwise.</li>
 *   <li><b>Encoding.</b> It always writes UTF-8 and ignores {@code i18n.commitEncoding}.</li>
 * </ul>
 * So the answers come from {@link EffectiveConfig} - git's own reading - when git is
 * installed, and without it this refuses whenever JGit's reading could be the wrong one.
 */
final class CommitPreflight {

    /**
     * How to make the commit.
     *
     * @param author      who wrote it; applied only to a new commit - an amend keeps the
     *                    original author, as {@code git commit --amend} does
     * @param committer   who is committing it
     * @param cleanup     how the message is tidied, from {@code commit.cleanup}
     * @param commentChar the comment character for {@link CleanupMode#STRIP}, else null
     */
    record Plan(PersonIdent author, PersonIdent committer, CleanupMode cleanup, Character commentChar) {
    }

    /** Either a plan or the reason there is none. */
    record Outcome(Plan plan, String refusal) {
        static Outcome refuse(String why) {
            return new Outcome(null, why);
        }
    }

    private CommitPreflight() {
    }

    static Outcome evaluate(Repository repository) {
        EffectiveConfig effective = EffectiveConfig.fromGit(repository);
        Config jgit = repository.getConfig();

        if (effective == null) {
            // No git to evaluate conditional includes, and JGit ignores them - so any
            // that could apply here could be changing the identity or turning signing on
            // behind JGit's back.
            List<String> conditions = EffectiveConfig.conditionalIncludesThatCouldApply(repository);
            if (!conditions.isEmpty()) {
                return Outcome.refuse("Your git config has conditional includes ([includeIf \""
                        + String.join("\"], [includeIf \"", conditions) + "\"]) that may set who "
                        + "commits here or turn on signing. The JGit engine cannot evaluate them "
                        + "and git is not installed to do it, so committing could record the "
                        + "wrong identity or skip a required signature. Install git, or move "
                        + "those settings into this repository's own config.");
            }
        }

        boolean gpgSign = effective != null
                ? effective.getBoolean("commit.gpgsign", false)
                : jgit.getBoolean("commit", "gpgsign", false);
        if (gpgSign) {
            String format = effective != null ? effective.get("gpg.format")
                    : jgit.getString("gpg", null, "format");
            // Real JGit signing needs a Signer and a resolvable secret key - the
            // key-management work the parity plan explicitly avoids - so this backend
            // declines rather than pretend, and says which engine does sign correctly.
            return Outcome.refuse("commit.gpgsign is on"
                    + (format == null || format.isBlank() ? "" : " (gpg.format=" + format + ")")
                    + ", but the JGit engine cannot sign commits. Switch to the Native Git "
                    + "engine in Settings to commit signed, or turn off \"Sign commits with "
                    + "GPG\" to commit unsigned here.");
        }

        String encoding = effective != null ? effective.get("i18n.commitEncoding")
                : jgit.getString("i18n", null, "commitEncoding");
        if (encoding != null && !isUtf8(encoding)) {
            return Outcome.refuse("i18n.commitEncoding is " + encoding + ", but the JGit engine "
                    + "can only write UTF-8 commit messages. Switch to the Native Git engine in "
                    + "Settings to commit in " + encoding + ".");
        }

        PersonIdent author;
        PersonIdent committer;
        if (effective != null) {
            author = identity(effective, "GIT_AUTHOR", "author");
            committer = identity(effective, "GIT_COMMITTER", "committer");
        } else {
            UserConfig user = jgit.get(UserConfig.KEY);
            boolean invented = user.isAuthorNameImplicit() || user.isAuthorEmailImplicit()
                    || user.isCommitterNameImplicit() || user.isCommitterEmailImplicit();
            author = invented ? null : new PersonIdent(user.getAuthorName(), user.getAuthorEmail());
            committer = invented ? null : new PersonIdent(user.getCommitterName(), user.getCommitterEmail());
        }
        if (author == null || committer == null) {
            return Outcome.refuse("No commit identity is configured. Set user.name and user.email "
                    + "(Profiles, or git config --global user.name \"Your Name\" and "
                    + "git config --global user.email you@example.com) - git refuses to commit "
                    + "without them rather than guess, and so does this.");
        }

        String cleanupSetting = effective != null ? effective.get("commit.cleanup")
                : jgit.getString("commit", null, "cleanup");
        CleanupMode cleanup = cleanupFor(cleanupSetting);
        Character commentChar = null;
        if (cleanup == CleanupMode.STRIP) {
            String configured = effective != null ? effective.get("core.commentChar")
                    : jgit.getString("core", null, "commentChar");
            if (configured == null || configured.isEmpty()) {
                commentChar = '#';
            } else if ("auto".equalsIgnoreCase(configured)) {
                // "auto" picks a character the message does not start a line with, which
                // means stripping removes nothing - whitespace cleanup is the same result.
                cleanup = CleanupMode.WHITESPACE;
            } else {
                commentChar = configured.charAt(0);
            }
        }
        return new Outcome(new Plan(author, committer, cleanup, commentChar), null);
    }

    /**
     * The committer identity git would use here, or {@code null} when there is none -
     * the same resolution {@link #evaluate} makes, without its refusals about signing or
     * encoding, which are about making a commit rather than about who makes it.
     */
    static PersonIdent committer(Repository repository) {
        EffectiveConfig effective = EffectiveConfig.fromGit(repository);
        if (effective != null) return identity(effective, "GIT_COMMITTER", "committer");
        UserConfig user = repository.getConfig().get(UserConfig.KEY);
        if (user.isCommitterNameImplicit() || user.isCommitterEmailImplicit()) return null;
        return new PersonIdent(user.getCommitterName(), user.getCommitterEmail());
    }

    /**
     * What {@code git commit -m}/{@code -F} does for each {@code commit.cleanup} value.
     *
     * <p>{@code default} means "strip if the message was edited in an editor, whitespace
     * otherwise", and this application never opens an editor. {@code scissors} is
     * whitespace for the same reason: the scissors line only exists in an editor buffer.
     */
    static CleanupMode cleanupFor(String setting) {
        if (setting == null) return CleanupMode.WHITESPACE;
        return switch (setting.trim().toLowerCase(Locale.ROOT)) {
            case "strip" -> CleanupMode.STRIP;
            case "verbatim" -> CleanupMode.VERBATIM;
            default -> CleanupMode.WHITESPACE;
        };
    }

    private static boolean isUtf8(String encoding) {
        String e = encoding.trim().toLowerCase(Locale.ROOT);
        return e.equals("utf-8") || e.equals("utf8");
    }

    /**
     * One side of the identity, in git's order of precedence: the environment
     * ({@code GIT_AUTHOR_NAME}), then the role's own config ({@code author.name}), then
     * {@code user.name}; for the email, {@code EMAIL} as the last resort.
     *
     * @return {@code null} when either half is missing - git's "Please tell me who you are"
     */
    private static PersonIdent identity(EffectiveConfig config, String envPrefix, String role) {
        String name = firstNonBlank(System.getenv(envPrefix + "_NAME"),
                config.get(role + ".name"), config.get("user.name"));
        String email = firstNonBlank(System.getenv(envPrefix + "_EMAIL"),
                config.get(role + ".email"), config.get("user.email"), System.getenv("EMAIL"));
        if (name == null || email == null) return null;
        return new PersonIdent(name, email);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) return candidate.trim();
        }
        return null;
    }
}
