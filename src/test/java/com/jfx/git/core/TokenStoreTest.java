package com.jfx.git.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Round-trips a token through whatever this machine keeps secrets in.
 *
 * <p>Deliberately against the real platform store rather than a fake: the whole point of
 * {@link TokenStore} is that it hands the secret to the operating system, and a test
 * double would prove only that the code calls something. On a machine with no keychain
 * this app can reach - a bare Linux CI container with no {@code secret-tool} - the tests
 * skip, and {@link #anUnavailableStoreIsHonestAboutIt} covers that path instead.
 *
 * <p>The account name is unique per run so a crashed test cannot leave an entry that a
 * later run reads as its own.
 */
class TokenStoreTest {

    @TempDir
    Path tempDir;

    private String account;

    @BeforeEach
    void useATempSettingsFileAndAUniqueAccount() {
        // The Windows implementation keeps its DPAPI ciphertext in Settings, so this
        // test must not write into whatever file the rest of the suite is using.
        Settings.useFile(tempDir.resolve("settings.properties"));
        account = "lyra-test-" + java.util.UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        if (account != null && TokenStore.isAvailable()) {
            TokenStore.remove(account);
        }
        Settings.useFile(null);
    }

    @Test
    void aStoredTokenComesBackExactly() {
        assumeTrue(TokenStore.isAvailable(),
                "No token store on this machine: " + TokenStore.describeLocation());
        // Punctuation a shell would mangle if this were ever built by string
        // concatenation rather than passed as data.
        String token = "ghp_A1b2$C3'd4\"e5 f6|g7`h8";

        assertTrue(TokenStore.store(account, token), "storing should succeed");

        assertEquals(Optional.of(token), TokenStore.load(account));
    }

    @Test
    void storingTwiceReplacesRatherThanDuplicates() {
        assumeTrue(TokenStore.isAvailable(),
                "No token store on this machine: " + TokenStore.describeLocation());

        assertTrue(TokenStore.store(account, "first"));
        assertTrue(TokenStore.store(account, "second"));

        assertEquals(Optional.of("second"), TokenStore.load(account));
    }

    @Test
    void removingLeavesNothingToLoad() {
        assumeTrue(TokenStore.isAvailable(),
                "No token store on this machine: " + TokenStore.describeLocation());
        assertTrue(TokenStore.store(account, "a-token"));

        TokenStore.remove(account);

        assertEquals(Optional.empty(), TokenStore.load(account));
    }

    @Test
    void anAccountThatWasNeverStoredLoadsNothing() {
        assertEquals(Optional.empty(), TokenStore.load(account));
    }

    /** Removing something that was never stored is the ordinary case on first launch. */
    @Test
    void removingSomethingThatIsNotThereIsNotAnError() {
        assertDoesNotThrow(() -> TokenStore.remove(account));
    }

    @Test
    void aBlankTokenIsNeverStored() {
        assertFalse(TokenStore.store(account, null));
        assertFalse(TokenStore.store(account, ""));
        assertFalse(TokenStore.store(account, "   "));
    }

    /**
     * The plaintext must never reach the settings file - it is the file the class exists
     * to stay out of, and on Windows it is the file the ciphertext lives in.
     */
    @Test
    void thePlaintextNeverReachesTheSettingsFile() throws Exception {
        assumeTrue(TokenStore.isAvailable(),
                "No token store on this machine: " + TokenStore.describeLocation());
        String token = "ghp_thisMustNotBeReadableOnDisk";

        assertTrue(TokenStore.store(account, token));
        Settings.flush();

        Path file = Settings.file();
        if (Files.exists(file)) {
            assertFalse(Files.readString(file).contains(token),
                    "the token was written to the settings file in clear text");
        }
    }

    /** The direct DPAPI call round-trips, including text outside ASCII. */
    @Test
    void dpapiCalledDirectlyRoundTrips() {
        assumeTrue(WindowsDpapi.isAvailable(), "DPAPI is only callable directly on Windows");
        String token = "glpat-\u00fcn\u00efc\u00f8d\u00e9-\u20ac-x";

        String cipher = WindowsDpapi.protect(token);

        assertNotNull(cipher);
        assertTrue(cipher.matches("[0-9a-f]+"), "ConvertFrom-SecureString's lowercase hex");
        assertEquals(token, WindowsDpapi.unprotect(cipher));
        assertNull(WindowsDpapi.unprotect("not hex at all"));
        assertNull(WindowsDpapi.unprotect("00ff"), "hex that is not a DPAPI blob");
    }

    /**
     * A token remembered by a version that encrypted through PowerShell still loads,
     * and one stored now can still be read by PowerShell - the fallback path, and any
     * older version the user goes back to.
     */
    @Test
    void theStoredFormatIsStillPowerShells() {
        assumeTrue(WindowsDpapi.isAvailable(), "DPAPI is only callable directly on Windows");
        String old = TokenStore.encryptWithPowerShell("ghp_rememberedByAnOlderVersion");
        assumeTrue(old != null && !old.isBlank(), "PowerShell's SecureString cmdlets are not usable here");

        Settings.put("integration.token." + account, old);
        assertEquals(Optional.of("ghp_rememberedByAnOlderVersion"), TokenStore.load(account));
        assertEquals("ghp_rememberedByAnOlderVersion", WindowsDpapi.unprotect(old));

        assertTrue(TokenStore.store(account, "ghp_storedByThisVersion"));
        String written = Settings.get("integration.token." + account, null);
        assertEquals("ghp_storedByThisVersion", TokenStore.decryptWithPowerShell(written));
    }

    @Test
    void anUnavailableStoreIsHonestAboutIt() {
        // Whichever way this machine answers, the two have to agree: a UI that offers
        // "Remember" while nothing can be stored is worse than not offering it.
        if (TokenStore.isAvailable()) {
            assertFalse(TokenStore.describeLocation().contains("nowhere"));
        } else {
            assertTrue(TokenStore.describeLocation().contains("nowhere"),
                    TokenStore.describeLocation());
            assertFalse(TokenStore.store(account, "anything"));
            assertEquals(Optional.empty(), TokenStore.load(account));
        }
    }
}
