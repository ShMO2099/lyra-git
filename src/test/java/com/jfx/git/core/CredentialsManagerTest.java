package com.jfx.git.core;

import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CredentialsManager#runSilently}: the guarantee a scheduled background fetch
 * needs from the single-worker {@link GitTaskRunner} - a credential this session has
 * not already supplied must fail the fetch rather than open a dialog and block the
 * worker behind it.
 *
 * <p>No JavaFX toolkit is started for any of these. That is not a workaround; it is how
 * a regression would actually be caught. Without {@link CredentialsManager#runSilently}
 * short-circuiting first, {@code get} would try to show a real dialog via {@code
 * Platform.runLater} - which throws {@code IllegalStateException} with no toolkit
 * running, caught by {@code onFxThread} and turned into a null answer. That null answer
 * happens to look identical to a correct refusal, so a naive assertion here could pass
 * for the wrong reason. {@link #silentModeStillAnswersFromAnAlreadyCachedLogin} is the
 * test that actually distinguishes the two: it only passes if silent mode reads the
 * cache and returns {@code true} - the no-toolkit fallback can never do that, since it
 * never runs the cache-hit branch of {@code get} at all.
 */
class CredentialsManagerTest {

    /**
     * Nothing here may reach the machine's real credential helper - on a developer's
     * Windows box that is Git Credential Manager, which would go looking for the hosts
     * these tests invent. Tests that exercise the helper give it their own configuration.
     */
    @org.junit.jupiter.api.BeforeEach
    void keepTheRealCredentialHelperOutOfIt() {
        CredentialsManager.useHelper(null, true);
    }

    @AfterEach
    void clearCachedAnswers() {
        CredentialsManager.getInstance().clear();
        CredentialsManager.useHelper(null, false);
    }

    // ------------------------------------------------------------------
    // T3: the login cache key
    // ------------------------------------------------------------------

    /** A password typed for https://host was replayed to http://host:8080. */
    @Test
    void aLoginIsNotReplayedToAnotherSchemeOrPortOnTheSameHost() throws Exception {
        CredentialsManager manager = CredentialsManager.getInstance();
        seedLoginCache(manager, uri("https://shared.invalid/repo.git"), "alice", "secret");

        assertFalse(getUnderSilence(manager, uri("http://shared.invalid:8080/repo.git"),
                new CredentialItem.Username(), new CredentialItem.Password()),
                "a different scheme and port is a different server");
        assertTrue(getUnderSilence(manager, uri("https://SHARED.invalid/other.git"),
                new CredentialItem.Username(), new CredentialItem.Password()),
                "the same server, whatever the case of its name");
    }

    // ------------------------------------------------------------------
    // T2: forgetting
    // ------------------------------------------------------------------

    @Test
    void forgettingCredentialsAlsoForgetsSshPassphrases() throws Exception {
        CredentialsManager manager = CredentialsManager.getInstance();
        Field field = CredentialsManager.class.getDeclaredField("secrets");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> secrets = (Map<String, String>) field.get(manager);
        URIish key = uri("ssh://git@example.invalid/repo.git");
        secrets.put(key.toString(), "passphrase");
        assertTrue(getUnderSilence(manager, key, new CredentialItem.StringType("Passphrase", true)),
                "precondition: the cached passphrase answers");

        manager.clear();

        assertFalse(getUnderSilence(manager, key, new CredentialItem.StringType("Passphrase", true)),
                "Forget all credentials must forget passphrases too");
    }

    // ------------------------------------------------------------------
    // T5: a changed host key
    // ------------------------------------------------------------------

    /** JGit's own wording for a changed key; see SshdText.knownHostsModifiedKey*. */
    @Test
    void aChangedHostKeyIsRecognisedAndRefused() throws Exception {
        CredentialItem[] changed = {
                new CredentialItem.InformationalMessage("WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!"),
                new CredentialItem.YesNoType("Accept this key and continue connecting all the same?")};
        CredentialItem[] unknown = {
                new CredentialItem.InformationalMessage("The authenticity of host 'x' cannot be established."),
                new CredentialItem.YesNoType("Accept and store this key, and continue connecting?")};

        assertTrue(CredentialsManager.isChangedHostKey(changed));
        assertFalse(CredentialsManager.isChangedHostKey(unknown));
        // Silent only so no error dialog can open in a suite that has a toolkit running;
        // the refusal itself does not depend on it.
        assertFalse(getUnderSilence(CredentialsManager.getInstance(),
                uri("ssh://git@example.invalid/r.git"), changed));
        assertFalse(((CredentialItem.YesNoType) changed[1]).getValue());
    }

    /**
     * The changed-key dialog compares the two keys: the saved and the received
     * fingerprint read out of JGit's warning (SshdText.knownHostsModifiedKeyWarning), the
     * SHA256 one of each, without the sentence's full stop.
     */
    @Test
    void aChangedHostKeysFingerprintsAreReadOutOfJGitsWarning() {
        String warning = "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!\n"
                + "The connection might be compromised (man-in-the-middle attack).\n"
                + "It is also possible that the ED25519 key of the host has just been changed.\n"
                + "The expected ED25519 key for host 'github.com' has the fingerprints:\n"
                + "MD5:aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99\n"
                + "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU\n"
                + "The ED25519 key actually received has the fingerprints:\n"
                + "MD5:99:88:77:66:55:44:33:22:11:00:ff:ee:dd:cc:bb:aa\n"
                + "SHA256:nThbg6kXUpJWGl7E1IGOCspRomTxdCARLviKw6E5SY8.";
        assertEquals(java.util.List.of(
                        "Saved     SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU",
                        "Received  SHA256:nThbg6kXUpJWGl7E1IGOCspRomTxdCARLviKw6E5SY8"),
                CredentialsManager.changedKeyFingerprints(warning));
        assertEquals(java.util.List.of(), CredentialsManager.changedKeyFingerprints("No fingerprint here."));
    }

    /** ssh's other yes/no questions: the first line a title, the fingerprint in a well of its own. */
    @Test
    void anSshPromptIsSplitIntoTitleRestAndFingerprint() {
        var said = CredentialsManager.SshPrompt.of(
                "Allow use of key /home/ada/.ssh/id_ed25519?\nKey fingerprint SHA256:abc+/12.\nRequested by ssh");
        assertEquals("Allow use of key /home/ada/.ssh/id_ed25519?", said.headline());
        assertEquals("Requested by ssh", said.rest(), "the fingerprint's own line goes with it");
        assertEquals("SHA256:abc+/12", said.fingerprint());

        var touch = CredentialsManager.SshPrompt.of("Confirm user presence for key ED25519-SK SHA256:xyz");
        assertEquals("Confirm user presence for key ED25519-SK", touch.headline());
        assertEquals("", touch.rest());
        assertEquals("SHA256:xyz", touch.fingerprint());
    }

    // ------------------------------------------------------------------
    // R6: the git CLI's prompts
    // ------------------------------------------------------------------

    /**
     * git asks for a username and then a password as two askpass runs; once the session
     * has a login, both are answered from it without a dialog.
     */
    @Test
    void theCliPromptsAreAnsweredFromTheSessionLogin() throws Exception {
        CredentialsManager manager = CredentialsManager.getInstance();
        seedLoginCache(manager, uri("https://cli.invalid"), "alice", "token123");
        seedLoginCache(manager, uri("https://alice@cli.invalid"), "alice", "token123");

        assertEquals("alice", manager.answerAskpass("", "Username for 'https://cli.invalid': "));
        assertEquals("token123", manager.answerAskpass("", "Password for 'https://alice@cli.invalid': "));
    }

    /** A password the CLI rejected must not be replayed to the next fetch. */
    @Test
    void aFailedCliOperationDropsTheLoginsItWasGiven() throws Exception {
        CredentialsManager manager = CredentialsManager.getInstance();
        seedLoginCache(manager, uri("https://alice@cli.invalid"), "alice", "wrong");
        assertEquals("wrong", manager.answerAskpass("", "Password for 'https://alice@cli.invalid': "));

        manager.networkOperationFinished(false);

        Field field = CredentialsManager.class.getDeclaredField("cache");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String[]> cache = (Map<String, String[]>) field.get(manager);
        assertFalse(cache.containsKey(CredentialsManager.loginCacheKey(uri("https://alice@cli.invalid"))),
                "the login the failed operation used must be gone");
    }

    // ------------------------------------------------------------------
    // The prompts, read for their dialogs
    // ------------------------------------------------------------------

    /** git's and ssh's own words become a title, the server or key as the subtitle, and a label. */
    @Test
    void gitsAndSshsPromptsAreReadIntoATitleASubtitleAndALabel() {
        var user = CredentialsManager.AskpassPrompt.of("Username for 'https://github.com': ", "Sign in");
        assertEquals("Sign in to github.com", user.title());
        assertEquals("`https://github.com`", user.subtitle());
        assertEquals("Username", user.label());
        assertEquals("Continue", user.verb(), "git asks for the password next");
        assertTrue(user.understood());

        var password = CredentialsManager.AskpassPrompt.of("Password for 'https://ada@github.com': ", "Sign in");
        assertEquals("Sign in to github.com", password.title());
        assertEquals("`https://ada@github.com`", password.subtitle());
        assertEquals("Password or token", password.label());
        assertEquals("Sign in", password.verb());
        assertTrue(password.hint().contains("personal access token"), "GitHub refuses the account password");
        assertEquals(null, CredentialsManager.AskpassPrompt.of(
                "Password for 'https://ada@git.example.com': ", "Sign in").hint(), "a host that may not");

        var key = CredentialsManager.AskpassPrompt.of("Enter passphrase for key '/home/ada/.ssh/id_ed25519': ",
                "SSH key");
        assertEquals("Unlock your SSH key", key.title());
        assertEquals("`/home/ada/.ssh/id_ed25519`", key.subtitle());
        assertEquals("Passphrase", key.label());
        assertEquals("Unlock key", key.verb());
        assertTrue(key.key(), "the key icon, not the lock");
        assertEquals(null, key.retry());

        var ssh = CredentialsManager.AskpassPrompt.of("git@example.com's password: ", "Sign in");
        assertEquals("Sign in to example.com", ssh.title());
        assertEquals("`git@example.com`", ssh.subtitle());
        assertEquals("Password", ssh.label());

        var pin = CredentialsManager.AskpassPrompt.of(
                "Enter PIN for ED25519-SK key /home/ada/.ssh/id_ed25519_sk: ", "SSH key");
        assertEquals("Unlock your security key", pin.title());
        assertEquals("`/home/ada/.ssh/id_ed25519_sk`", pin.subtitle());
        assertEquals("PIN", pin.label());
    }

    /** JGit names the key by a {@code file:} URI, and asks again in other words after a wrong one. */
    @Test
    void jgitsPassphrasePromptsNameTheKeyAsAPath() {
        assertEquals("`/home/ada/.ssh/id_ed25519`", CredentialsManager.AskpassPrompt.of(
                "'file:///home/ada/.ssh/id_ed25519' needs a passphrase to be read.", "SSH key").subtitle());
        assertEquals("`C:/Users/ada/.ssh/id_ed25519`", CredentialsManager.AskpassPrompt.of(
                "'file:/C:/Users/ada/.ssh/id_ed25519' needs a passphrase to be read.", "SSH key").subtitle());
        assertEquals("`/home/ada/.ssh/id_ed25519`", CredentialsManager.AskpassPrompt.of(
                "Passphrase for /home/ada/.ssh/id_ed25519", "SSH key").subtitle(), "this class's own fallback");

        var again = CredentialsManager.AskpassPrompt.of(
                "'/home/ada/.ssh/id_ed25519' could not be read. Enter the passphrase again.", "SSH key");
        assertEquals("Unlock your SSH key", again.title());
        assertTrue(again.retry() != null && again.retry().contains("did not unlock"),
                "the wrong answer is said on the field: " + again.retry());
    }

    /** A prompt it does not know keeps its own words: as the label when short, in the body when not. */
    @Test
    void aPromptThatIsNotUnderstoodIsKeptAsItWasAsked() {
        var code = CredentialsManager.AskpassPrompt.of("Verification code: ", "Sign in");
        assertFalse(code.understood());
        assertEquals("Verification code", code.label());
        assertEquals(null, code.body(), "the label says it already");
        assertEquals("Sign in", code.window());

        String asked = "Touch the security key now, then type the six digits it shows\nand press Enter:";
        var touch = CredentialsManager.AskpassPrompt.of(asked, "Sign in");
        assertEquals("Answer", touch.label());
        assertEquals(asked, touch.body(), "the raw prompt, above the field");
    }

    @Test
    void sshsHostKeyQuestionIsRecognised() {
        assertTrue(CredentialsManager.isSshHostKeyQuestion("The authenticity of host 'h' can't be established.\n"
                + "Are you sure you want to continue connecting (yes/no/[fingerprint])? "));
        assertFalse(CredentialsManager.isSshHostKeyQuestion("Enter passphrase for key '/home/u/.ssh/id_ed25519': "));
    }

    // ------------------------------------------------------------------
    // R7: the user's credential helper
    // ------------------------------------------------------------------

    /** A login the user's helper already has is used, and nothing is prompted for. */
    @Test
    void aLoginFromTheCredentialHelperIsUsedBeforeAnyPrompt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(NativeGitRunner.isGitInstalled());
        CredentialsManager.useHelper(GitCredentialHelperTest.helperAnswering(dir, "helper-user", "helper-pass"), false);
        CredentialItem.Username username = new CredentialItem.Username();
        CredentialItem.Password password = new CredentialItem.Password();

        boolean answered = getUnderSilence(CredentialsManager.getInstance(),
                uri("https://helper.invalid/repo.git"), username, password);

        assertTrue(answered);
        assertEquals("helper-user", username.getValue());
        assertEquals("helper-pass", new String(password.getValue()));
    }

    private static URIish uri(String url) throws Exception {
        return new URIish(url);
    }

    private static boolean getUnderSilence(CredentialsManager manager, URIish uri, CredentialItem... items) {
        boolean[] result = new boolean[1];
        Exception[] failure = new Exception[1];
        CredentialsManager.runSilently(() -> {
            try {
                result[0] = manager.get(uri, items);
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
        return result[0];
    }

    @Test
    void silentModeRefusesAnUncachedLoginWithoutPrompting() throws Exception {
        CredentialsManager manager = CredentialsManager.getInstance();
        URIish uri = uri("https://example.invalid/repo.git");

        boolean answered = getUnderSilence(manager, uri,
                new CredentialItem.Username(), new CredentialItem.Password());

        assertFalse(answered, "an uncached login must be refused, not prompted for, in silent mode");
    }

    @Test
    void silentModeStillAnswersFromAnAlreadyCachedLogin() throws Exception {
        CredentialsManager manager = CredentialsManager.getInstance();
        URIish uri = uri("https://cached.invalid/repo.git");
        seedLoginCache(manager, uri, "alice", "token123");

        CredentialItem.Username username = new CredentialItem.Username();
        CredentialItem.Password password = new CredentialItem.Password();
        boolean answered = getUnderSilence(manager, uri, username, password);

        assertTrue(answered, "a credential this session already gathered must still work silently");
        assertEquals("alice", username.getValue());
    }

    /**
     * B15. Not every host calls its secret a password. Matching on the prompt text
     * containing "password" left a "Token:", "Passcode:" or "OTP:" item unset while
     * {@code get} still returned true, so the transport authenticated with nothing and
     * failed with something unrelated to the real cause.
     */
    @Test
    void aSecretPromptThatIsNotCalledPasswordIsStillFilledIn() throws Exception {
        CredentialsManager manager = CredentialsManager.getInstance();
        URIish uri = uri("https://token.invalid/repo.git");
        seedLoginCache(manager, uri, "alice", "token123");

        CredentialItem.Username username = new CredentialItem.Username();
        CredentialItem.StringType token = new CredentialItem.StringType("Token: ", true);
        boolean answered = getUnderSilence(manager, uri, username, token);

        assertTrue(answered);
        assertEquals("alice", username.getValue());
        assertEquals("token123", token.getValue(),
                "any string prompt travelling with a username is the secret");
    }

    /**
     * S2. One host commonly serves two accounts - enterprise GitHub beside a personal
     * one. Keyed by host alone, the first login silently won for every later URI on that
     * host until the cache was reset.
     */
    @Test
    void twoAccountsOnOneHostDoNotShareOneCachedLogin() throws Exception {
        CredentialsManager manager = CredentialsManager.getInstance();
        seedLoginCache(manager, uri("https://alice@shared.invalid/repo.git"), "alice", "alice-token");
        seedLoginCache(manager, uri("https://bob@shared.invalid/repo.git"), "bob", "bob-token");

        CredentialItem.Password forBob = new CredentialItem.Password();
        boolean answered = getUnderSilence(manager, uri("https://bob@shared.invalid/repo.git"),
                new CredentialItem.Username(), forBob);

        assertTrue(answered);
        assertEquals("bob-token", new String(forBob.getValue()),
                "the second account must not be answered with the first one's secret");
    }

    @Test
    void silentModeRefusesAnUnknownHostKeyRatherThanAutoAccepting() throws Exception {
        CredentialsManager manager = CredentialsManager.getInstance();
        URIish uri = uri("ssh://git@example.invalid/repo.git");

        boolean answered = getUnderSilence(manager, uri,
                new CredentialItem.YesNoType("Accept this host key?"));

        assertFalse(answered,
                "trusting an unseen host key automatically must never happen, silent mode or not");
    }

    @Test
    void silentModeRefusesAnUncachedSshPassphrase() throws Exception {
        CredentialsManager manager = CredentialsManager.getInstance();
        URIish uri = uri("ssh://git@example.invalid/repo.git");

        // A secret item with no username alongside it is the SSH-passphrase branch of
        // get(), distinct from the username/password login branch above.
        boolean answered = getUnderSilence(manager, uri,
                new CredentialItem.StringType("Passphrase", true));

        assertFalse(answered, "an uncached SSH key passphrase must be refused in silent mode");
    }

    @Test
    void theSilentFlagDoesNotLeakPastRunSilentlyReturning() {
        CredentialsManager.runSilently(() -> { });

        assertFalse(isSilentOnThisThread(),
                "runSilently must clear its flag once work() returns, or every later call on "
                        + "this thread - including a user-initiated fetch right after a background one - "
                        + "would be silently refused instead of prompting");
    }

    @Test
    void runSilentlyClearsItsFlagEvenWhenWorkThrows() {
        try {
            CredentialsManager.runSilently(() -> {
                throw new RuntimeException("boom");
            });
        } catch (RuntimeException expected) {
            // expected - runSilently does not swallow the caller's exception
        }

        assertFalse(isSilentOnThisThread(),
                "the ThreadLocal must be cleared in a finally, not just on the success path");
    }

    /** Reads the private {@code SILENT} ThreadLocal directly for the calling thread. */
    private static boolean isSilentOnThisThread() {
        try {
            Field field = CredentialsManager.class.getDeclaredField("SILENT");
            field.setAccessible(true);
            ThreadLocal<?> silent = (ThreadLocal<?>) field.get(null);
            return Boolean.TRUE.equals(silent.get());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Seeds the login cache directly. There is deliberately no public setter: the cache
     * is in-memory-only, filled solely by a real interactive answer, and adding a
     * test-only mutator to production code for this one case is worse than reaching
     * past it here.
     */
    private static void seedLoginCache(CredentialsManager manager, URIish uri, String user, String pass)
            throws ReflectiveOperationException {
        seedLoginCache(manager, CredentialsManager.loginCacheKey(uri), user, pass);
    }

    /** Seeds one entry under an explicit cache key; see {@code CredentialsManager.loginCacheKey}. */
    private static void seedLoginCache(CredentialsManager manager, String key, String user, String pass)
            throws ReflectiveOperationException {
        Field field = CredentialsManager.class.getDeclaredField("cache");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String[]> cache = (Map<String, String[]>) field.get(manager);
        cache.put(key, new String[]{user, pass});
    }
}
