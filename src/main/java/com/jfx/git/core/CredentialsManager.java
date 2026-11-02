package com.jfx.git.core;

import com.jfx.git.core.hosting.ProviderGitCredentials;
import com.jfx.git.core.hosting.ProviderSessions;
import com.jfx.git.ui.Icons;
import com.jfx.git.ui.dialogs.DialogParts;
import com.jfx.git.ui.dialogs.JfxDialog;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;

/**
 * Supplies credentials for authenticated remotes.
 *
 * <p>The project previously had no {@link CredentialsProvider} at all, so any
 * push/pull/fetch against a remote requiring authentication failed outright.
 *
 * <p>Answers are cached for the lifetime of the process only - nothing is written
 * to disk by this class, because the settings file stores values in clear text. For
 * persistence it defers to the user's own git credential helpers: an HTTPS login is
 * first asked of {@code git credential fill} (see {@link GitCredentialHelper}), and a
 * login typed here is handed to {@code git credential approve} once a transfer has
 * proven it, exactly as the git CLI does - so a helper that stores (Git Credential
 * Manager, osxkeychain, libsecret) keeps it where the user chose.
 *
 * <p>The same dialogs answer the git CLI too: {@link #answerAskpass} is what
 * {@link AskpassBridge} calls when git or ssh, run by the CLI engine, needs to ask.
 *
 * <h2>What this does and does not protect</h2>
 * <ul>
 *   <li><b>Never on disk.</b> No secret reaches {@link Settings}, the reflog, a log
 *       record or a subprocess argument list.</li>
 *   <li><b>Never guessed.</b> An unknown SSH host key is refused rather than trusted,
 *       including in the silent background path - see {@link #runSilently}. A
 *       <em>changed</em> host key is refused outright, as OpenSSH refuses it: it is
 *       never turned into a Yes/No question someone can click through.</li>
 *   <li><b>One account per host is not assumed.</b> The login cache is keyed by host
 *       <em>and</em> username, so two accounts on one host do not answer for each
 *       other.</li>
 *   <li><b>Secrets live as {@link String}, and that is a known limit.</b> Strings are
 *       immutable, so a password cannot be zeroed after use and stays readable in the
 *       heap until it is collected - and in a heap dump taken before that. Holding
 *       {@code char[]} in this cache would shorten the window but not close it: the
 *       secret arrives from a JavaFX {@code TextField} as a String, and JGit's
 *       {@code CredentialItem.StringType} takes one back. The threat this leaves open
 *       is an attacker who can already read this process's memory.</li>
 * </ul>
 */
public final class CredentialsManager {

    private static final CredentialsManager INSTANCE = new CredentialsManager();

    /** Cached answers keyed by host, or {@code user@host}; see {@code loginCacheKey}. */
    private final Map<String, String[]> cache = new HashMap<>();

    /** Cached SSH key passphrases, keyed by the key's URI - one entry per key file. */
    private final Map<String, String> secrets = new HashMap<>();

    /**
     * Prompts currently in flight, so two workers asking for the same credential see one
     * dialog instead of two.
     *
     * <p>There is only one git worker now, but a fetch and a passphrase prompt can still
     * overlap with a retry, and two stacked dialogs for the same key read as a hang.
     * The first caller creates the task and shows the dialog; later callers for the same
     * key block on the same answer.
     */
    private final Map<String, FutureTask<String[]>> pendingLogins = new HashMap<>();
    private final Map<String, FutureTask<String>> pendingPassphrases = new HashMap<>();

    /**
     * Set for the duration of a call to {@link #runSilently}, on whichever thread called
     * it - the git worker, in practice.
     *
     * <p>A scheduled background fetch runs on the same single {@code GitTaskRunner}
     * worker as every interactive git action. If it needed a credential this session has
     * never supplied and prompted for one like a user-initiated fetch would, that dialog
     * would sit there until whoever is at the keyboard happens to notice it - and every
     * later git action anyone takes, including a plain commit, would queue silently
     * behind it on the same worker. Silent mode makes an unanswerable prompt fail the
     * fetch instead of blocking anything: a cached answer (from a credential this session
     * already gathered interactively) still works, but nothing new is ever asked for.
     */
    private static final ThreadLocal<Boolean> SILENT = ThreadLocal.withInitial(() -> false);

    private CredentialsManager() {
    }

    public static CredentialsManager getInstance() {
        return INSTANCE;
    }

    /**
     * Runs {@code work} with this provider refusing rather than prompting for anything
     * not already cached, for as long as {@code work} runs on the calling thread.
     *
     * <p>Scoped to the calling thread only: a nested or concurrent call to {@link #get}
     * from a different thread is unaffected, which matters because {@link
     * #singleFlight} can hand a second caller's wait off to a dialog task running on the
     * FX thread rather than the caller's own.
     */
    public static void runSilently(Runnable work) {
        SILENT.set(true);
        try {
            work.run();
        } finally {
            SILENT.remove();
        }
    }

    /**
     * Where a login came from, for the bookkeeping git's own approve/reject protocol
     * needs: a helper's answer is rejected back to it when it fails, and a typed one is
     * approved to the helpers once it has worked.
     */
    private record LoginSource(URIish uri, String username, String password) { }

    /**
     * Logins answered by a connected account (Integrations), by cache key. Never cached and
     * never approved to a helper - the app owns that token, and writing it into Git
     * Credential Manager would leave it there after the user disconnected. A refusal marks
     * it rejected for git (see {@link #reset}).
     */
    private final Map<String, ProviderGitCredentials.GitLogin> fromProvider = new HashMap<>();

    /** Logins answered by a credential helper, by cache key; rejected to it on failure. */
    private final Map<String, LoginSource> fromHelper = new HashMap<>();

    /** Logins the user typed that no transfer has proven yet, by cache key. */
    private final Map<String, LoginSource> unapproved = new HashMap<>();

    /**
     * Cache keys filled by an answer to the git CLI's askpass. The CLI reports a rejected
     * password only through its exit status, so these are dropped when the operation
     * that used them fails - otherwise a typo would be replayed to every later fetch.
     */
    private final java.util.Set<String> answeredForCli = new java.util.HashSet<>();

    /** Null = the user's configured helpers (when git is installed); see {@link #useHelper}. */
    private static volatile GitCredentialHelper helperOverride;
    private static volatile boolean helperDisabled;

    /**
     * Package-visible seam: a test points credential lookups at a helper with its own
     * configuration, or ({@code null}, {@code true}) turns helper lookups off so nothing
     * reaches the machine's real Git Credential Manager.
     */
    static void useHelper(GitCredentialHelper helper, boolean disabled) {
        helperOverride = helper;
        helperDisabled = disabled;
    }

    private static GitCredentialHelper helper() {
        if (helperDisabled) return null;
        GitCredentialHelper override = helperOverride;
        return override != null ? override : GitCredentialHelper.systemDefault();
    }

    /**
     * Forgets every cached login and passphrase, and every SSH key already unlocked.
     *
     * <p>"Forget all credentials" used to clear only the login cache: SSH passphrases and
     * the keys JGit's key cache had already decrypted survived it, so the next fetch
     * still authenticated with a key the user had just asked to be forgotten.
     */
    public void clear() {
        synchronized (cache) {
            cache.clear();
            fromProvider.clear();
            givenToCli.clear();
            fromHelper.clear();
            unapproved.clear();
            answeredForCli.clear();
        }
        synchronized (secrets) {
            secrets.clear();
        }
        SshSessionManager.forgetUnlockedKeys();
    }

    /**
     * Reports how the network operation that just ran went.
     *
     * <p>On success, logins the user typed are handed to {@code git credential approve},
     * so a storing helper keeps them - the step git performs after every authenticated
     * transfer. On failure nothing is approved, and logins answered for the git CLI are
     * dropped, because the CLI cannot say whether it was the password that failed.
     */
    public void networkOperationFinished(boolean succeeded) {
        List<LoginSource> toApprove;
        synchronized (cache) {
            toApprove = succeeded ? new ArrayList<>(unapproved.values()) : List.of();
            unapproved.clear();
            if (!succeeded) {
                for (String key : answeredForCli) {
                    cache.remove(key);
                }
            }
            answeredForCli.clear();
        }
        GitCredentialHelper helper = helper();
        if (helper == null) return;
        for (LoginSource login : toApprove) {
            try {
                helper.approve(login.uri().getScheme(), login.uri().getHost(), login.uri().getPort(),
                        login.uri().getPath(),
                        new GitCredentialHelper.Credential(login.username(), login.password()));
            } catch (RuntimeException e) {
                java.util.logging.Logger.getLogger(CredentialsManager.class.getName())
                        .log(java.util.logging.Level.FINE, "Could not approve a login to the helper", e);
            }
        }
    }

    /**
     * This manager as JGit's {@link CredentialsProvider}, for the transport commands in
     * this package.
     *
     * <p>An adapter rather than {@code extends}: {@code com.jfx.git.core} is exported, and
     * a public class extending a JGit type put JGit on its API - which module-info keeps
     * off by not requiring JGit transitively. Nothing outside the engines needs this
     * view; the UI only ever asks to {@link #clear()}.
     */
    CredentialsProvider jgit() {
        return jgitView;
    }

    private final CredentialsProvider jgitView = new CredentialsProvider() {
        @Override
        public boolean isInteractive() {
            return true;
        }

        @Override
        public boolean supports(CredentialItem... items) {
            return CredentialsManager.this.supports(items);
        }

        @Override
        public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
            return CredentialsManager.this.get(uri, items);
        }

        @Override
        public void reset(URIish uri) {
            CredentialsManager.this.reset(uri);
        }
    };

    boolean supports(CredentialItem... items) {
        for (CredentialItem item : items) {
            if (!(item instanceof CredentialItem.Username)
                    && !(item instanceof CredentialItem.InformationalMessage)
                    && !(item instanceof CredentialItem.YesNoType)
                    // Password and StringType are both CharArrayType/StringType
                    // subclasses; SSH key passphrases arrive as the base types.
                    && !(item instanceof CredentialItem.CharArrayType)
                    && !(item instanceof CredentialItem.StringType)) {
                return false;
            }
        }
        return true;
    }

    boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
        boolean hasUsername = false;
        boolean hasSecret = false;
        boolean hasYesNo = false;
        for (CredentialItem item : items) {
            if (item instanceof CredentialItem.Username) {
                hasUsername = true;
            } else if (item instanceof CredentialItem.CharArrayType
                    || item instanceof CredentialItem.StringType) {
                hasSecret = true;
            } else if (item instanceof CredentialItem.YesNoType) {
                hasYesNo = true;
            }
        }

        // An unknown host key. OpenSSH asks before trusting one and so do we - blindly
        // answering yes would make the known_hosts file worthless. Silent mode fails
        // rather than skips this check: trusting an unseen key without asking is the one
        // thing that must never happen automatically, not even to keep a background
        // fetch quiet.
        if (hasYesNo && !hasUsername && !hasSecret) {
            // A key that differs from the one on record is not a question. It is either a
            // server rebuilt by someone who should have said so, or someone in the middle;
            // OpenSSH refuses it outright, and so does this, rather than handing the user
            // a Yes-default dialog to click through.
            if (isChangedHostKey(items)) {
                if (!SILENT.get()) onFxThread(() -> refuseChangedHostKey(uri, items));
                return false;
            }
            return !SILENT.get() && confirmAll(uri, items);
        }

        // A secret with no username to go with it is an SSH key passphrase, not a
        // login. Prompting for a username there would be nonsense, and reusing the
        // HTTPS password cached for the same host would just fail to unlock the key.
        if (hasSecret && !hasUsername) {
            return unlock(uri, items);
        }

        if (!hasUsername && !hasSecret) {
            return true;
        }

        String key = loginCacheKey(uri);
        // The account connected for this exact server comes first, ahead of anything cached
        // or stored: it is the one the user chose for it. Silent mode may use it - it needs
        // no window. Once refused it is skipped (see reset), and JGit's retry falls through
        // to the cache, the user's helpers and the prompt.
        String[] answer = fromConnectedAccount(uri, key);
        if (answer == null) {
            synchronized (cache) {
                answer = cache.get(key);
            }
        }
        if (answer == null) {
            // What the user already saved for git - GCM, osxkeychain, libsecret - comes
            // before asking them again. In silent mode the helper may still answer from
            // its store; it just may not open a window of its own.
            answer = fromCredentialHelper(uri, key, SILENT.get());
        }
        if (answer == null && SILENT.get()) {
            return false;
        }
        if (answer == null) {
            answer = singleFlight(pendingLogins, key, () -> prompt(uri));
            if (answer == null) {
                return false;
            }
            synchronized (cache) {
                cache.put(key, answer);
                if (isHttp(uri)) {
                    unapproved.put(key, new LoginSource(uri, answer[0], answer[1]));
                }
            }
        }

        for (CredentialItem item : items) {
            // Username first: it is itself a StringType, so the secret branches below
            // would otherwise claim it.
            if (item instanceof CredentialItem.Username) {
                ((CredentialItem.Username) item).setValue(answer[0]);
            } else if (item instanceof CredentialItem.CharArrayType) {
                ((CredentialItem.CharArrayType) item).setValue(answer[1].toCharArray());
            } else if (item instanceof CredentialItem.StringType) {
                // Any string prompt travelling with a username is the secret. Matching on
                // the prompt containing "password" left hosts that ask for a "Token:",
                // "Passcode:" or "OTP:" with an unset item and a true return, so the
                // transport authenticated with nothing and failed obscurely.
                ((CredentialItem.StringType) item).setValue(answer[1]);
            }
        }
        return true;
    }

    /**
     * Cache key for an HTTPS login.
     *
     * <p>Host <em>and</em> username, because one host commonly serves two accounts -
     * enterprise GitHub next to a personal one. Keyed by host alone, the first login won
     * silently for every later URI on that host until {@link #reset(URIish)} cleared it.
     * URIs that carry no username still share one per-host entry, which is the behaviour
     * a single-account user already had.
     *
     * <p>Scheme and port as well: keyed by host alone, a password typed for
     * {@code https://host} was replayed in clear text to {@code http://host:8080}, a
     * different service that happened to share the name.
     */
    static String loginCacheKey(URIish uri) {
        if (uri.getHost() == null) return uri.toString();
        return loginCacheKey(uri.getScheme(), uri.getUser(), uri.getHost(), uri.getPort());
    }

    static String loginCacheKey(String scheme, String user, String host, int port) {
        String origin = (scheme == null ? "" : scheme.toLowerCase(java.util.Locale.ROOT) + "://")
                + (user == null || user.isBlank() ? "" : user + "@")
                + host.toLowerCase(java.util.Locale.ROOT);
        return port > 0 ? origin + ":" + port : origin;
    }

    private static boolean isHttp(URIish uri) {
        String scheme = uri.getScheme();
        return scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"));
    }

    /** When a connected account last answered a git login, from {@link System#nanoTime()}; 0 for never. */
    private volatile long lastAccountAnswer;

    /** How long after a connected account answered a permission error is put down to it. */
    private static final long ACCOUNT_HINT_WINDOW = java.util.concurrent.TimeUnit.MINUTES.toNanos(2);

    private void accountAnswered() {
        lastAccountAnswer = System.nanoTime();
    }

    /**
     * {@code error}, with what to do about it when it is a server refusing an operation the
     * connected account was used for - a 403, or GitHub refusing an OAuth token without
     * the {@code workflow} scope.
     *
     * <p>A refused <em>login</em> already falls back to the user's own credentials; this is
     * the other case: the account logged in fine and lacks the permission. git does not
     * erase a credential on a 403, so without this the user sees "permission denied" with
     * no hint that the application chose which account to push as.
     */
    public String withAccountHint(String error) {
        if (error == null || lastAccountAnswer == 0
                || System.nanoTime() - lastAccountAnswer > ACCOUNT_HINT_WINDOW) {
            return error;
        }
        String lower = error.toLowerCase(java.util.Locale.ROOT);
        boolean forbidden = lower.contains("error: 403") || lower.contains("403 forbidden")
                || lower.contains("permission to ") || lower.contains("not permitted")
                || lower.contains("workflow") && lower.contains("scope");
        if (!forbidden) return error;
        return error.stripTrailing() + "\n\nThe account connected in Integrations was used for this, and"
                + " the server refused it the permission (pushing workflow files to GitHub, for"
                + " one, needs the workflow scope). Connect it again with the access it needs, or"
                + " turn off \"Use for git\" for it in Integrations to use your own git credentials.";
    }

    /** The connected account's login for this server, or null; see {@link ProviderGitCredentials}. */
    private String[] fromConnectedAccount(URIish uri, String key) {
        if (!isHttp(uri) || uri.getHost() == null) return null;
        ProviderGitCredentials.GitLogin login = ProviderGitCredentials
                .lookup(uri.getScheme(), uri.getHost(), uri.getPort(), uri.getUser()).orElse(null);
        if (login == null) return null;
        synchronized (cache) {
            fromProvider.put(key, login);
        }
        accountAnswered();
        return new String[]{login.username(), login.password()};
    }

    /** A login from {@code git credential fill}, cached like a typed one, or null. */
    private String[] fromCredentialHelper(URIish uri, String key, boolean silent) {
        if (!isHttp(uri) || uri.getHost() == null) return null;
        GitCredentialHelper helper = helper();
        if (helper == null) return null;
        GitCredentialHelper.Credential found;
        try {
            found = helper.fill(uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath(),
                    uri.getUser(), silent);
        } catch (RuntimeException e) {
            return null;
        }
        if (found == null || found.username() == null) return null;
        String[] answer = {found.username(), found.password()};
        synchronized (cache) {
            cache.put(key, answer);
            fromHelper.put(key, new LoginSource(uri, found.username(), found.password()));
        }
        return answer;
    }

    /**
     * Whether JGit is asking about a host key that differs from the recorded one, rather
     * than one it has never seen. JGit phrases the two differently; this matches its
     * wording for the changed case.
     */
    static boolean isChangedHostKey(CredentialItem... items) {
        for (CredentialItem item : items) {
            String prompt = item.getPromptText();
            if (prompt == null) continue;
            String text = prompt.toUpperCase(java.util.Locale.ROOT);
            if (text.contains("IDENTIFICATION HAS CHANGED")
                    || text.contains("CONTINUE CONNECTING ALL THE SAME")
                    || text.contains("ALSO STORE THE NEW KEY")) {
                return true;
            }
        }
        return false;
    }

    /**
     * JGit's "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!" in the application's own
     * words. The connection has already been refused; what is left to say is why it
     * matters, the two fingerprints - read out of JGit's text, so the saved and the
     * received key can be compared - and, for a server that really did replace its key,
     * the command that removes the old entry, in a well with Copy. JGit's own text is
     * behind Show Details. Nothing here trusts the new key: one click must not undo what
     * known_hosts is for.
     */
    private static Void refuseChangedHostKey(URIish uri, CredentialItem... items) {
        StringBuilder text = new StringBuilder();
        for (CredentialItem item : items) {
            if (item instanceof CredentialItem.InformationalMessage && item.getPromptText() != null) {
                text.append(item.getPromptText()).append(System.lineSeparator());
            }
        }
        String said = text.toString().strip();
        String host = uri.getHost();
        // known_hosts records a host on another port as [host]:port, and so does ssh-keygen.
        String entry = uri.getPort() > 0 && uri.getPort() != 22 ? "\"[" + host + "]:" + uri.getPort() + "\"" : host;

        var refused = com.jfx.git.ui.dialogs.Dialogs.error("Host key changed")
                .icon(com.jfx.git.ui.Icons.shield())
                .title("The SSH key of " + host + " has changed")
                .subtitle("The connection was refused")
                .message("The key it sent does not match the one in your known_hosts file: either the"
                        + " server replaced its key, or someone is intercepting the connection. If "
                        + host + " announced a new key matching the one received, remove the old"
                        + " entry and connect again.")
                .code("REMOVES THE OLD ENTRY", "ssh-keygen -R " + entry)
                .details(said)
                .size(com.jfx.git.ui.dialogs.JfxDialog.Size.MD);
        List<String> prints = changedKeyFingerprints(said);
        if (!prints.isEmpty()) {
            refused.affected("KEY FINGERPRINTS", prints);
        }
        refused.answer();
        return null;
    }

    /**
     * The saved and the received fingerprint from JGit's changed-key warning, as
     * {@code "Saved     SHA256:..."} and {@code "Received  SHA256:..."}; whichever it
     * names. JGit gives each key's MD5 and SHA256 fingerprints, saved key first, then
     * "the ... key actually received"; SHA256 is the one OpenSSH and the hosts publish.
     */
    static List<String> changedKeyFingerprints(String warning) {
        List<String> rows = new ArrayList<>();
        java.util.regex.Matcher received = RECEIVED.matcher(warning);
        if (!received.find()) {
            // Not JGit's shape: whichever fingerprint it names, without guessing whose.
            String named = preferredFingerprint(warning);
            if (named != null) {
                rows.add(named);
            }
            return rows;
        }
        String saved = preferredFingerprint(warning.substring(0, received.start()));
        String sent = preferredFingerprint(warning.substring(received.start()));
        if (saved != null) {
            rows.add("Saved     " + saved);
        }
        if (sent != null) {
            rows.add("Received  " + sent);
        }
        return rows;
    }

    /** The SHA256 fingerprint in {@code text}, or else the first of any kind, or null. */
    private static String preferredFingerprint(String text) {
        String first = null;
        java.util.regex.Matcher print = FINGERPRINT.matcher(text);
        while (print.find()) {
            if (print.group(1).startsWith("SHA256:")) {
                return print.group(1);
            }
            if (first == null) {
                first = print.group(1);
            }
        }
        return first;
    }

    /** Where JGit's warning turns from the saved key to the one the server sent. */
    private static final java.util.regex.Pattern RECEIVED =
            java.util.regex.Pattern.compile("(?i)actually received|\\bsent by\\b");

    /**
     * Answers an SSH key passphrase prompt.
     *
     * <p>Cached per key file, so a repeated fetch during one session unlocks the key
     * silently. A wrong answer is dropped by {@link #reset(URIish)}, which the SSH
     * transport calls before it retries.
     */
    private boolean unlock(URIish uri, CredentialItem... items) {
        String cacheKey = uri.toString();
        String secret;
        synchronized (secrets) {
            secret = secrets.get(cacheKey);
        }
        if (secret == null && SILENT.get()) {
            return false;
        }
        if (secret == null) {
            secret = singleFlight(pendingPassphrases, cacheKey,
                    () -> promptSecret(describeSecret(uri, items)));
            if (secret == null) {
                return false;
            }
            synchronized (secrets) {
                secrets.put(cacheKey, secret);
            }
        }

        for (CredentialItem item : items) {
            if (item instanceof CredentialItem.CharArrayType) {
                ((CredentialItem.CharArrayType) item).setValue(secret.toCharArray());
            } else if (item instanceof CredentialItem.StringType) {
                ((CredentialItem.StringType) item).setValue(secret);
            }
        }
        return true;
    }

    /**
     * The transport's own wording if it gave any, otherwise something serviceable. A bare
     * "Passphrase" names no key, so the URI being unlocked is added to it for the dialog's
     * subtitle.
     */
    private static String describeSecret(URIish uri, CredentialItem... items) {
        for (CredentialItem item : items) {
            String prompt = item.getPromptText();
            if (prompt != null && !prompt.isBlank()) {
                return prompt.contains("'") || prompt.contains(" for ") ? prompt : prompt.strip() + " for " + uri;
            }
        }
        return "Passphrase for " + uri;
    }

    /**
     * Asks the user to accept an unknown or changed host key.
     *
     * <p>Answering yes automatically would defeat the point of {@code known_hosts}:
     * a changed key is exactly the signal a user needs to see.
     */
    private boolean confirmAll(URIish uri, CredentialItem... items) {
        StringBuilder text = new StringBuilder();
        for (CredentialItem item : items) {
            String prompt = item.getPromptText();
            if (prompt != null && !prompt.isBlank()) {
                text.append(prompt).append(System.lineSeparator());
            }
        }
        Boolean accepted = onFxThread(() -> confirmDialog(uri, text.toString().trim()));
        if (accepted == null || !accepted) {
            return false;
        }
        for (CredentialItem item : items) {
            if (item instanceof CredentialItem.YesNoType) {
                ((CredentialItem.YesNoType) item).setValue(true);
            }
        }
        return true;
    }

    /**
     * OpenSSH's "The authenticity of host ... can't be established" in the application's
     * own words, with the one thing to check - the fingerprint - pulled out into a well
     * with Copy rather than wrapped mid-token through a sentence. Cancel is the default:
     * trusting a key should be a decision, not an Enter.
     */
    private static Boolean confirmDialog(URIish uri, String message) {
        return hostKeyQuestion(uri.getHost(), message);
    }

    /**
     * The unknown-host question, for JGit's transport ({@link #confirmDialog}) and for
     * OpenSSH's own, asked through askpass when the git CLI runs ssh ({@link #confirmText}):
     * one question, asked one way, whichever engine reached the host.
     */
    private static boolean hostKeyQuestion(String host, String message) {
        java.util.regex.Matcher print = FINGERPRINT.matcher(message);
        java.util.regex.Matcher kind = KEY_KIND.matcher(message);
        var ask = com.jfx.git.ui.dialogs.Dialogs.warn("Verify host")
                .icon(com.jfx.git.ui.Icons.shield())
                .title("First connection to " + host)
                .subtitle("Its SSH key is not in your known_hosts file yet")
                .action("Trust and connect")
                .safeDefault()
                .size(com.jfx.git.ui.dialogs.JfxDialog.Size.MD);
        if (print.find()) {
            String heading = (kind.find() ? kind.group(1).toUpperCase(java.util.Locale.ROOT) + " " : "")
                    + "KEY FINGERPRINT";
            ask.message("Compare this fingerprint with the one " + host
                    + " publishes before you trust it.").code(heading, print.group(1));
        } else {
            // Not the shape OpenSSH and JGit use: say it as it was said.
            ask.message(message.isEmpty() ? "Accept the host key for " + host + "?" : message);
        }
        return ask.ask();
    }

    /**
     * A key fingerprint as OpenSSH and JGit print one - {@code SHA256:} and base64, or
     * {@code MD5:} and hex pairs - without the full stop of the sentence it ends.
     */
    private static final java.util.regex.Pattern FINGERPRINT =
            java.util.regex.Pattern.compile("((?:SHA256|MD5):[A-Za-z0-9+/=:]+)");
    private static final java.util.regex.Pattern KEY_KIND =
            java.util.regex.Pattern.compile("(\\S+) key fingerprint");
    /** The host in OpenSSH's "The authenticity of host 'github.com (140.82.121.4)' can't be established." */
    private static final java.util.regex.Pattern SSH_HOST =
            java.util.regex.Pattern.compile("host '([^'(]+?)\\s*(?:\\([^)]*\\))?'");

    /**
     * Asks for the passphrase of the key JGit is unlocking, with JGit's wording read into a
     * title and the key's path ({@link AskpassPrompt}); cached by {@link #unlock}, so the
     * hint can say how long it is kept.
     */
    private String promptSecret(String label) {
        return onFxThread(() -> promptDialog(AskpassPrompt.of(label, "SSH key"), true,
                "Lyra keeps it until it closes, and never writes it to disk.")
                .showAndWait().orElse(null));
    }

    /**
     * Forgets what was cached for a URI.
     *
     * <p>The SSH transport calls this after a rejected passphrase, so without it a
     * single typo would be replayed until the retry limit ran out.
     */
    void reset(URIish uri) {
        synchronized (secrets) {
            secrets.remove(uri.toString());
        }
        if (uri.getHost() != null) {
            LoginSource rejected;
            ProviderGitCredentials.GitLogin refusedAccount;
            synchronized (cache) {
                String key = loginCacheKey(uri);
                refusedAccount = fromProvider.remove(key);
                // The connected account answered this attempt, so the cache and the helper
                // were not asked and have nothing to forget.
                if (refusedAccount == null) {
                    cache.remove(key);
                    unapproved.remove(key);
                }
                rejected = refusedAccount == null ? fromHelper.remove(key) : null;
            }
            if (refusedAccount != null) {
                markRefused(refusedAccount);
                return;
            }
            // A stored credential the server just refused is one the helper should stop
            // offering, or every later attempt fails the same way - git rejects it too.
            GitCredentialHelper helper = helper();
            if (rejected != null && helper != null) {
                try {
                    helper.reject(uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath(),
                            new GitCredentialHelper.Credential(rejected.username(), rejected.password()));
                } catch (RuntimeException ignored) {
                    // Best effort: the login is dropped from this session either way.
                }
            }
        }
    }

    /**
     * Stops offering a connected account git refused, and says so once: the next attempt
     * is answered by the user's own credentials, and without a word the change of account
     * would be invisible.
     */
    static void markRefused(ProviderGitCredentials.GitLogin login) {
        boolean first = !ProviderSessions.isRejectedForGit(login.provider());
        ProviderSessions.markRejectedForGit(login.provider(), login.credential());
        if (first) {
            java.util.logging.Logger.getLogger(CredentialsManager.class.getName()).warning(
                    login.provider().displayName() + " refused the connected account as a git login;"
                            + " your own git credentials are used for it instead this session.");
            IntegrationEvents.gitLoginRefused(login.provider());
        }
    }

    // ------------------------------------------------------------------
    // The git CLI's prompts, forwarded by AskpassBridge
    // ------------------------------------------------------------------

    /** The two dialogs ssh's prompts need; replaceable so a test can answer them. */
    interface AskpassDialogs {
        /** A Yes/No question defaulting to No; null when it could not be shown. */
        Boolean confirm(String title, String message);

        /** A secret; null when cancelled or when it could not be shown. */
        String secret(String title, String prompt);
    }

    private static final AskpassDialogs FX_ASKPASS_DIALOGS = new AskpassDialogs() {
        @Override
        public Boolean confirm(String title, String message) {
            return onFxThread(() -> confirmText(title, message));
        }

        @Override
        public String secret(String title, String prompt) {
            return onFxThread(() -> secretDialog(title, prompt));
        }
    };

    private static volatile AskpassDialogs askpassDialogs = FX_ASKPASS_DIALOGS;

    /**
     * Answers ssh's askpass prompts with {@code dialogs} instead of the real ones, or with
     * the real ones again for null. For tests that drive git and ssh end to end.
     */
    static void useAskpassDialogs(AskpassDialogs dialogs) {
        askpassDialogs = dialogs == null ? FX_ASKPASS_DIALOGS : dialogs;
    }

    /** git's HTTPS prompts: {@code Username for 'https://host': }. */
    private static final java.util.regex.Pattern GIT_USERNAME_PROMPT =
            java.util.regex.Pattern.compile("(?s)^\\s*Username for '([^']+)'\\s*:?\\s*$");
    /** {@code Password for 'https://user@host': }. */
    private static final java.util.regex.Pattern GIT_PASSWORD_PROMPT =
            java.util.regex.Pattern.compile("(?s)^\\s*Password for '([^']+)'\\s*:?\\s*$");

    /**
     * Answers a prompt from git or ssh run by the CLI engine, with the same dialogs and
     * the same session cache the JGit engine uses; {@code null} when the user cancelled.
     *
     * <p>git asks for a username and a password as two separate askpass runs. The login
     * dialog takes both at once, so the password is held under the {@code user@host}
     * key the second prompt will name, and answered from there without a second dialog.
     *
     * <p>Called on the bridge's own thread, never the FX thread; the dialogs are marshalled
     * across and this thread waits, as the JGit path's worker does.
     */
    public String answerAskpass(String kind, String prompt) {
        if (kind != null && kind.startsWith(AskpassMain.CREDENTIAL_KIND)) {
            String operation = kind.substring(AskpassMain.CREDENTIAL_KIND.length());
            boolean silent = operation.endsWith("-silent");
            if (silent) operation = operation.substring(0, operation.length() - "-silent".length());
            return answerCredential(operation, prompt == null ? "" : prompt, silent);
        }
        String text = prompt == null ? "" : prompt.trim();
        if ("confirm".equalsIgnoreCase(kind)) {
            Boolean yes = askpassDialogs.confirm("SSH", text);
            return Boolean.TRUE.equals(yes) ? "" : null;
        }
        if ("none".equalsIgnoreCase(kind)) {
            onFxThread(() -> {
                SshPrompt said = SshPrompt.of(text);
                var notice = com.jfx.git.ui.dialogs.Dialogs.info("SSH")
                        .icon(com.jfx.git.ui.Icons.key())
                        .title(said.headline().isEmpty() ? "A message from ssh" : said.headline())
                        .subtitle("From ssh")
                        .message(said.rest());
                if (said.fingerprint() != null) {
                    notice.code("KEY FINGERPRINT", said.fingerprint());
                }
                notice.answer();
                return null;
            });
            return "";
        }

        var username = GIT_USERNAME_PROMPT.matcher(text);
        if (username.matches()) {
            return answerGitUsername(username.group(1));
        }
        var password = GIT_PASSWORD_PROMPT.matcher(text);
        if (password.matches()) {
            return answerGitPassword(password.group(1), text);
        }
        if (isSshHostKeyQuestion(text)) {
            // Unknown host keys only: OpenSSH never asks about a changed one, it refuses.
            Boolean yes = askpassDialogs.confirm("SSH host key", text);
            return Boolean.TRUE.equals(yes) ? "yes" : "no";
        }
        // A key passphrase, a keyboard-interactive password, a one-time code: all
        // secrets, and none cached - ssh retries a wrong one itself, and a cached wrong
        // answer would be replayed into every retry without the user seeing why.
        return askpassDialogs.secret(title(text), text);
    }

    // ------------------------------------------------------------------
    // git's credential-helper protocol, for the CLI engine
    // ------------------------------------------------------------------

    /**
     * Connected-account logins handed to the git CLI, by origin (scheme, host, port), so
     * git's {@code store} and {@code erase} for them can be told apart from the user's own.
     */
    private final Map<String, ProviderGitCredentials.GitLogin> givenToCli = new HashMap<>();

    /** Counts connected-account logins git refused; see {@link #tokenRejections()}. */
    private final java.util.concurrent.atomic.AtomicInteger tokenRejections =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * How many connected-account logins git has refused so far. Within one git process git
     * does not try another credential after a refusal, so a command that failed while this
     * went up is run once more - when the account is skipped and the user's own
     * credentials answer. See {@code CliRepo.withConnectedAccount}.
     */
    public int tokenRejections() {
        return tokenRejections.get();
    }

    /**
     * Answers git's credential helper - the application is the only one git sees for
     * commands it runs (see {@link AskpassBridge#credentialHelperArgs()}), and asks the
     * user's own helpers itself.
     *
     * <p>{@code get}: the connected account for this exact server, then a login typed this
     * session, then the user's own helpers through {@code git credential fill} (Git
     * Credential Manager, libsecret, ...) - which may show their own window unless silent.
     * Nothing known means an empty answer, and git goes on to its askpass prompt.
     *
     * <p>{@code store} and {@code erase} are passed to the user's helpers, as git would
     * have done itself - except for the connected account's token, which the application
     * owns: never stored in the user's helpers, and a refusal of it marks it rejected for
     * git rather than erasing anything.
     *
     * @param operation {@code get}, {@code store} or {@code erase}
     * @param request   {@code cwd=<directory>} then git's {@code key=value} block
     * @return for {@code get}, the block to print; otherwise, and when nothing is known, null
     */
    String answerCredential(String operation, String request, boolean silent) {
        Map<String, String> fields = GitCredentialHelper.parse(request);
        String protocol = fields.get("protocol");
        String hostAndPort = fields.get("host");
        if (protocol == null || hostAndPort == null) return null;
        String host = hostAndPort;
        int port = -1;
        int colon = hostAndPort.lastIndexOf(':');
        if (colon > 0 && hostAndPort.indexOf(']') < colon) {
            try {
                port = Integer.parseInt(hostAndPort.substring(colon + 1));
                host = hostAndPort.substring(0, colon);
            } catch (NumberFormatException ignored) {
                // Not a port; the whole thing is the host.
            }
        }
        String path = fields.get("path");
        String username = fields.get("username");
        String password = fields.get("password");
        String cwd = fields.get("cwd");
        java.io.File directory = cwd == null || cwd.isBlank() ? null : new java.io.File(cwd);
        String origin = loginCacheKey(protocol, null, host, port);
        GitCredentialHelper helper = helper();

        switch (operation) {
            case "get" -> {
                var account = ProviderGitCredentials.lookup(protocol, host, port, username).orElse(null);
                if (account != null) {
                    synchronized (cache) {
                        givenToCli.put(origin, account);
                    }
                    accountAnswered();
                    return credentialBlock(account.username(), account.password());
                }
                String[] typed;
                synchronized (cache) {
                    typed = cache.get(loginCacheKey(protocol, username, host, port));
                    if (typed == null) typed = cache.get(origin);
                    if (typed != null) answeredForCli.add(origin);
                }
                if (typed != null && (username == null || username.equals(typed[0]))) {
                    return credentialBlock(typed[0], typed[1]);
                }
                if (helper == null || !isHttpScheme(protocol)) return null;
                GitCredentialHelper.Credential found;
                try {
                    found = helper.fill(protocol, host, port, path, username, silent, directory);
                } catch (RuntimeException e) {
                    return null;
                }
                return found == null || found.username() == null ? null
                        : credentialBlock(found.username(), found.password());
            }
            case "store", "erase" -> {
                ProviderGitCredentials.GitLogin account;
                synchronized (cache) {
                    account = givenToCli.get(origin);
                    if (account != null && account.password().equals(password)) {
                        givenToCli.remove(origin);
                    } else {
                        account = null;
                    }
                }
                if (account != null) {
                    if (operation.equals("erase")) {
                        tokenRejections.incrementAndGet();
                        markRefused(account);
                    }
                    return null;
                }
                if (operation.equals("erase")) {
                    synchronized (cache) {
                        cache.remove(origin);
                        if (username != null) cache.remove(loginCacheKey(protocol, username, host, port));
                    }
                }
                if (helper == null || username == null || password == null) return null;
                var credential = new GitCredentialHelper.Credential(username, password);
                try {
                    if (operation.equals("store")) {
                        helper.approve(protocol, host, port, path, credential, directory);
                    } else {
                        helper.reject(protocol, host, port, path, credential, directory);
                    }
                } catch (RuntimeException e) {
                    java.util.logging.Logger.getLogger(CredentialsManager.class.getName())
                            .log(java.util.logging.Level.FINE, "Could not pass " + operation + " to the helpers", e);
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static boolean isHttpScheme(String protocol) {
        return protocol.equalsIgnoreCase("https") || protocol.equalsIgnoreCase("http");
    }

    /** git's answer format; a value with a line break would inject a field, so none is sent. */
    private static String credentialBlock(String username, String password) {
        if (username == null || password == null
                || username.contains("\n") || password.contains("\n")) {
            return null;
        }
        return "username=" + username + "\n" + "password=" + password + "\n";
    }

    /** Whether ssh is asking the "continue connecting (yes/no)" question. */
    static boolean isSshHostKeyQuestion(String prompt) {
        String lower = prompt.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("continue connecting") && lower.contains("yes/no");
    }

    private String answerGitUsername(String url) {
        URIish uri = parseUri(url);
        if (uri == null || uri.getHost() == null) return onFxThread(() -> plainDialog("Username for " + url));
        String key = loginCacheKey(uri);
        String[] answer;
        synchronized (cache) {
            answer = cache.get(key);
        }
        if (answer == null) {
            answer = singleFlight(pendingLogins, key, () -> showDialog(uri));
            if (answer == null) return null;
        }
        String[] login = answer;
        synchronized (cache) {
            cache.put(key, login);
            String withUser = loginCacheKey(uri.getScheme(), login[0], uri.getHost(), uri.getPort());
            cache.put(withUser, login);
            answeredForCli.add(key);
            answeredForCli.add(withUser);
        }
        return login[0];
    }

    private String answerGitPassword(String url, String prompt) {
        URIish uri = parseUri(url);
        if (uri == null || uri.getHost() == null) return onFxThread(() -> secretDialog("Sign in", prompt));
        String key = loginCacheKey(uri);
        String[] answer;
        synchronized (cache) {
            answer = cache.get(key);
        }
        if (answer != null) {
            synchronized (cache) {
                answeredForCli.add(key);
            }
            return answer[1];
        }
        String secret = onFxThread(() -> secretDialog("Sign in", prompt));
        if (secret == null) return null;
        synchronized (cache) {
            cache.put(key, new String[]{uri.getUser(), secret});
            answeredForCli.add(key);
        }
        return secret;
    }

    private static URIish parseUri(String url) {
        try {
            return new URIish(url);
        } catch (Exception e) {
            return null;
        }
    }

    /** The window title for a secret ssh or git asks for, when {@link AskpassPrompt} cannot do better. */
    private static String title(String prompt) {
        return prompt.toLowerCase(java.util.Locale.ROOT).contains("passphrase") ? "SSH key" : "Sign in";
    }

    /**
     * A yes/no question from ssh whose default - Enter, Escape, closing it - is no. The
     * unknown-host question is the one {@link #confirmDialog} asks for JGit; anything
     * else ssh asks ("Allow use of key ...?") is shown as ssh put it, its first line as
     * the title and a fingerprint, if it names one, in a well of its own.
     */
    private static Boolean confirmText(String title, String message) {
        if (isSshHostKeyQuestion(message)) {
            java.util.regex.Matcher host = SSH_HOST.matcher(message);
            return hostKeyQuestion(host.find() ? host.group(1) : "this host", message);
        }
        SshPrompt said = SshPrompt.of(message);
        var ask = com.jfx.git.ui.dialogs.Dialogs.confirm(title == null || title.isBlank() ? "SSH" : title)
                .icon(com.jfx.git.ui.Icons.key())
                .title(said.headline().isEmpty() ? "Allow ssh to go on?" : said.headline())
                .subtitle("Asked by ssh")
                .message(said.rest())
                .action("Allow")
                .safeDefault();
        if (said.fingerprint() != null) {
            ask.code("KEY FINGERPRINT", said.fingerprint());
        }
        return ask.ask();
    }

    /**
     * ssh's prompt laid out for a dialog: its first line as the title, the lines after it
     * under that, and the key fingerprint it names, if any, on its own - taken out of the
     * lines it sat in, which say nothing more once it is gone ("Key fingerprint SHA256:...").
     */
    record SshPrompt(String headline, String rest, String fingerprint) {

        static SshPrompt of(String prompt) {
            String text = prompt == null ? "" : prompt.strip();
            java.util.regex.Matcher print = FINGERPRINT.matcher(text);
            String fingerprint = print.find() ? print.group(1) : null;
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\\R")) {
                String kept = line.strip();
                if (fingerprint != null && kept.contains(fingerprint)) {
                    kept = kept.replace(fingerprint, "")
                            .replaceAll("(?i)\\b(?:key )?fingerprint(?: is)?\\b", "")
                            .replaceAll("[\\s.,:]+$", "")
                            .strip();
                }
                if (!kept.isEmpty()) {
                    lines.add(kept);
                }
            }
            return new SshPrompt(lines.isEmpty() ? "" : lines.get(0),
                    lines.size() < 2 ? "" : String.join("\n", lines.subList(1, lines.size())),
                    fingerprint);
        }
    }

    /** A secret git or ssh asked for through askpass; null when cancelled. On the FX thread. */
    private static String secretDialog(String title, String prompt) {
        return promptDialog(AskpassPrompt.of(prompt, title), true, null).showAndWait().orElse(null);
    }

    /** Something git asked for that is not a secret - a username; null when cancelled. On the FX thread. */
    private static String plainDialog(String prompt) {
        return promptDialog(AskpassPrompt.of(prompt, "Sign in"), false, null).showAndWait().orElse(null);
    }

    // ------------------------------------------------------------------
    // The dialogs
    // ------------------------------------------------------------------

    /**
     * A prompt from git, ssh or JGit, read for a dialog: a header title and subtitle, a label
     * for the field and a verb for the button.
     *
     * <p>The prompt used to be the dialog's header word for word - "Enter passphrase for key
     * '/home/ada/.ssh/id_ed25519':", a sentence ending in a colon standing in for a title,
     * over a field with no label. What it asks for is said here in the dialog's own terms,
     * with the key or the server it is for as the subtitle. A prompt this does not know is
     * kept as it was asked: as the field's label when it is one short line, otherwise in the
     * body above a field labelled Answer.
     *
     * @param window   the window's title
     * @param subtitle markup, the key or server in backticks; null for none
     * @param key      whether it unlocks a key (the key icon) rather than signs in (the lock)
     * @param hint     under the field; null for none
     * @param retry    what was wrong with the last answer, as the field's error; null for none
     * @param raw      the prompt as asked when it was not understood, possibly empty; null when
     *                 it was
     */
    record AskpassPrompt(String window, String title, String subtitle, String label, String verb,
                         boolean key, String hint, String retry, String raw) {

        /** The label a prompt too long to be one gets; the prompt then goes in the body. */
        static final String ANSWER = "Answer";

        /** Whether the prompt was understood; one that was not takes any answer, an empty one included. */
        boolean understood() {
            return raw == null;
        }

        /** The prompt as asked, for the body above the field, when it is not the field's label; or null. */
        String body() {
            return raw == null || raw.isBlank() || !ANSWER.equals(label) ? null : raw;
        }

        /** @param fallbackWindow the window title for a prompt this does not recognise */
        static AskpassPrompt of(String prompt, String fallbackWindow) {
            String text = prompt == null ? "" : prompt.strip();
            java.util.regex.Matcher m;
            if ((m = USERNAME_FOR.matcher(text)).matches()) {
                return signIn(m.group(1), hostOf(m.group(1)), "Username", "Continue", null);
            }
            if ((m = PASSWORD_FOR.matcher(text)).matches()) {
                String host = hostOf(m.group(1));
                return signIn(m.group(1), host, "Password or token", "Sign in", tokenHint(host));
            }
            if ((m = SSH_PASSWORD.matcher(text)).matches() || (m = SSH_PASSWORD_PREFIXED.matcher(text)).matches()) {
                return signIn(m.group(1) + "@" + m.group(2), m.group(2), "Password", "Sign in", null);
            }
            if ((m = SECURITY_KEY_PIN.matcher(text)).matches()) {
                return new AskpassPrompt("Security key", "Unlock your security key", mono(keyPath(m.group(2))),
                        "PIN", "Unlock key", true, null, null, null);
            }
            String keyFile = passphraseKey(text);
            if (keyFile != null) {
                return new AskpassPrompt("SSH key", "Unlock your SSH key",
                        keyFile.isEmpty() ? null : mono(keyPath(keyFile)), "Passphrase", "Unlock key", true, null,
                        RETRY.matcher(text).find() ? "That passphrase did not unlock the key. Try again." : null,
                        null);
            }
            String window = fallbackWindow == null || fallbackWindow.isBlank() ? "Sign in" : fallbackWindow;
            boolean oneLine = !text.isEmpty() && text.length() <= 60 && text.lines().count() == 1;
            String label = oneLine ? text.replaceAll("[\\s:]+$", "") : "";
            return new AskpassPrompt(window, "Answer a prompt from git", null,
                    label.isEmpty() ? ANSWER : label, "Continue", false, null, null, text);
        }

        private static AskpassPrompt signIn(String target, String host, String label, String verb, String hint) {
            return new AskpassPrompt("Sign in", host == null || host.isBlank() ? "Sign in" : "Sign in to " + host,
                    mono(target), label, verb, false, hint, null, null);
        }

        /**
         * The key a passphrase prompt names: the quoted path JGit and OpenSSH put in it, or
         * what follows "passphrase for"; empty when it names none, null when it is not about a
         * passphrase at all.
         */
        private static String passphraseKey(String text) {
            if (!text.toLowerCase(java.util.Locale.ROOT).contains("passphrase")) {
                return null;
            }
            java.util.regex.Matcher quoted = QUOTED.matcher(text);
            if (quoted.find()) {
                return quoted.group(1);
            }
            java.util.regex.Matcher named = PASSPHRASE_FOR.matcher(text);
            return named.find() ? named.group(1) : "";
        }

        /** A key's path as written, not as JGit's {@code file:} URI of it. */
        private static String keyPath(String path) {
            String p = path.strip();
            if (p.regionMatches(true, 0, "file:", 0, 5)) {
                p = p.substring(5).replaceFirst("^//", "");
                if (p.matches("/[A-Za-z]:.*")) {
                    p = p.substring(1);
                }
            }
            return p;
        }

        private static String hostOf(String url) {
            URIish uri = parseUri(url);
            return uri == null ? null : uri.getHost();
        }

        private static String mono(String text) {
            return text == null || text.isBlank() ? null : "`" + text.replace('`', '\'') + "`";
        }
    }

    /** git's {@code Username for 'https://host':}, and the unquoted form this class writes itself. */
    private static final java.util.regex.Pattern USERNAME_FOR =
            java.util.regex.Pattern.compile("(?is)^username for\\s+'?([^'\\s]+)'?\\s*:?$");
    private static final java.util.regex.Pattern PASSWORD_FOR =
            java.util.regex.Pattern.compile("(?is)^password for\\s+'?([^'\\s]+)'?\\s*:?$");
    /** OpenSSH's password authentication: {@code git@host's password:}. */
    private static final java.util.regex.Pattern SSH_PASSWORD =
            java.util.regex.Pattern.compile("(?i)^([^\\s@']+)@([^\\s']+)'s password\\s*:?$");
    /** OpenSSH's keyboard-interactive one: {@code (git@host) Password:}. */
    private static final java.util.regex.Pattern SSH_PASSWORD_PREFIXED =
            java.util.regex.Pattern.compile("(?i)^\\(([^\\s@)]+)@([^\\s)]+)\\)\\s*password\\s*:?$");
    /** OpenSSH unlocking a FIDO key: {@code Enter PIN for ED25519-SK key /home/ada/.ssh/id_ed25519_sk:}. */
    private static final java.util.regex.Pattern SECURITY_KEY_PIN =
            java.util.regex.Pattern.compile("(?is)^enter pin for (\\S+) key (.+?)\\s*:?$");
    private static final java.util.regex.Pattern QUOTED = java.util.regex.Pattern.compile("'([^']+)'");
    private static final java.util.regex.Pattern PASSPHRASE_FOR =
            java.util.regex.Pattern.compile("(?i)passphrase for (?:key )?(\\S.*?)\\s*:?\\s*$");
    /** JGit asking again after a wrong passphrase: "... could not be read. Enter the passphrase again." */
    private static final java.util.regex.Pattern RETRY =
            java.util.regex.Pattern.compile("(?i)could not be (?:read|decrypted)|passphrase again");

    /**
     * The dialog for one prompt, as {@link AskpassPrompt} read it. Built here and shown by
     * the callers, so a test can look at it without waiting on it.
     *
     * @param secret whether the answer is masked, with a toggle that shows it
     * @param hint   under the field in place of the prompt's own; null for the prompt's
     */
    static Dialog<String> promptDialog(AskpassPrompt asked, boolean secret, String hint) {
        Dialog<String> dialog = new Dialog<>();
        JfxDialog.shell(dialog, JfxDialog.Size.SM, asked.window());
        JfxDialog.header(dialog, asked.key() ? Icons.key() : Icons.lock(), JfxDialog.Tone.NEUTRAL,
                asked.title(), asked.subtitle());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        JfxDialog.verb(dialog, ButtonType.OK, asked.verb());

        DialogParts.Secret masked = secret ? new DialogParts.Secret(asked.label().toLowerCase(java.util.Locale.ROOT)) : null;
        TextField plain = secret ? masked.hidden : new TextField();
        plain.setId(secret ? PROMPT_SECRET_ID : PROMPT_TEXT_ID);
        DialogParts.Field field = DialogParts.field(asked.label(), secret ? masked.node : plain)
                .hint(hint != null ? hint : asked.hint());
        if (secret) {
            field.trailing(masked.reveal);
            if (asked.retry() != null) {
                masked.danger(true);
            }
        }
        field.error(asked.retry());
        Label said = null;
        if (asked.body() != null) {
            said = new Label(asked.body());
            said.setWrapText(true);
            said.setMinHeight(Region.USE_PREF_SIZE);
        }
        dialog.getDialogPane().setContent(DialogParts.stack(said, field));

        // A prompt this understood wants an answer; one it did not may take an empty one.
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        Runnable validate = () -> ok.setDisable(asked.understood() && plain.getText().isEmpty());
        plain.textProperty().addListener((obs, old, text) -> {
            validate.run();
            if (asked.retry() != null) {
                // Typed again: the last answer's error no longer describes this one.
                field.error(null);
                if (masked != null) masked.danger(false);
            }
        });
        validate.run();

        Platform.runLater(plain::requestFocus);
        dialog.setResultConverter(button -> button == ButtonType.OK ? plain.getText() : null);
        return dialog;
    }

    /** The field a prompt dialog takes its answer in: the masked one, or the plain one. */
    static final String PROMPT_SECRET_ID = "askpass-secret";
    static final String PROMPT_TEXT_ID = "askpass-text";
    static final String LOGIN_USERNAME_ID = "login-username";
    static final String LOGIN_PASSWORD_ID = "login-password";

    /**
     * Shows the login dialog on the FX thread and blocks the calling worker
     * thread until the user answers.
     */
    private String[] prompt(URIish uri) {
        return onFxThread(() -> showDialog(uri));
    }

    /**
     * Runs {@code dialog} at most once per {@code key} until it completes.
     *
     * <p>The first caller creates the {@link FutureTask}, schedules it on the FX thread
     * and answers it; any caller arriving with the same key blocks on that same answer
     * instead of raising a second dialog. The map entry is dropped once the answer is
     * delivered, so a later failure prompts afresh.
     *
     * <p>Returns null if the toolkit is not running, matching {@link #onFxThread}, which
     * is what keeps these providers exercisable from headless tests.
     */
    private static <T> T singleFlight(Map<String, FutureTask<T>> pending, String key,
                                      java.util.concurrent.Callable<T> dialog) {
        FutureTask<T> task;
        boolean owner;
        synchronized (pending) {
            task = pending.get(key);
            owner = task == null;
            if (owner) {
                task = new FutureTask<>(dialog);
                pending.put(key, task);
            }
        }
        if (owner) {
            try {
                if (Platform.isFxApplicationThread()) {
                    task.run();
                } else {
                    // Throws IllegalStateException when no toolkit is running - the
                    // headless-test case - which the catch below turns into null. If it
                    // throws, the task was never scheduled and never will run, so it is
                    // cancelled here rather than left to strand any concurrent waiter
                    // already blocked on task.get() below.
                    Platform.runLater(task);
                }
                // Blocks this worker until the user answers. That is unchanged behaviour:
                // prompting always blocked a worker. What changed is that a second worker
                // asking for the same credential now waits here too instead of stacking
                // another dialog.
                return task.get();
            } catch (Exception e) {
                task.cancel(false);
                return null;
            } finally {
                synchronized (pending) {
                    if (pending.get(key) == task) {
                        pending.remove(key);
                    }
                }
            }
        }
        try {
            return task.get();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Runs a dialog on the FX thread and blocks the calling worker until it closes.
     *
     * <p>Returns null if the toolkit is not running, which is what makes these
     * providers safe to exercise from tests.
     */
    private static <T> T onFxThread(java.util.concurrent.Callable<T> dialog) {
        FutureTask<T> task = new FutureTask<>(dialog);
        try {
            if (Platform.isFxApplicationThread()) {
                task.run();
            } else {
                Platform.runLater(task);
            }
            return task.get();
        } catch (Exception e) {
            return null;
        }
    }

    /** The HTTPS login, on the FX thread; null when cancelled. */
    private String[] showDialog(URIish uri) {
        return loginDialog(uri).showAndWait().orElse(null);
    }

    /**
     * The HTTPS login: the server in the header, the username and the password or token
     * stacked under it, the username filled in when the URL names one. Built here and shown
     * by {@link #showDialog}, so a test can look at it without waiting on it.
     *
     * <p>It cannot check the login before it closes: the transport that asked is waiting on
     * the answer, and a refused one comes back as that transport's failure.
     */
    static Dialog<String[]> loginDialog(URIish uri) {
        Dialog<String[]> dialog = new Dialog<>();
        String host = uri.getHost();
        JfxDialog.shell(dialog, JfxDialog.Size.SM, "Sign in");
        JfxDialog.header(dialog, Icons.lock(), JfxDialog.Tone.NEUTRAL, "Sign in to " + host,
                "`" + uri.toString().replace('`', '\'') + "`");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        JfxDialog.verb(dialog, ButtonType.OK, "Sign in");

        TextField username = new TextField(uri.getUser() == null ? "" : uri.getUser());
        username.setId(LOGIN_USERNAME_ID);
        DialogParts.Secret password = new DialogParts.Secret("password or token");
        password.hidden.setId(LOGIN_PASSWORD_ID);
        dialog.getDialogPane().setContent(DialogParts.stack(
                DialogParts.field("Username", username),
                DialogParts.field("Password or token", password.node).trailing(password.reveal)
                        .hint(tokenHint(host))));

        Button signIn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        Runnable validate = () -> signIn.setDisable(username.getText() == null || username.getText().isBlank());
        username.textProperty().addListener((obs, old, text) -> validate.run());
        validate.run();

        // The URL named the user: what is left to type is the password.
        Node first = username.getText().isEmpty() ? username : password.hidden;
        Platform.runLater(first::requestFocus);

        dialog.setResultConverter(button -> button == ButtonType.OK
                ? new String[]{username.getText(), password.text()}
                : null);
        return dialog;
    }

    /**
     * What to type where a password is asked for, for the hosts that refuse the account's
     * own password over HTTPS; null for the others. GitHub has refused it since 2021; a host
     * named like a GitHub Enterprise server is taken to do the same.
     */
    static String tokenHint(String host) {
        return host != null && host.toLowerCase(java.util.Locale.ROOT).contains("github")
                ? "GitHub takes a personal access token here, not your account password."
                : null;
    }
}
