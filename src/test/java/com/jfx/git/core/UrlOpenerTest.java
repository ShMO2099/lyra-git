package com.jfx.git.core;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T6: URLs from provider responses are opened only when they are web pages. Nothing is
 * actually opened here - {@link UrlOpener#refusalFor} decides before any launch.
 */
class UrlOpenerTest {

    @Test
    void onlyHttpAndHttpsAreOpened() {
        assertNull(UrlOpener.refusalFor(URI.create("https://github.com/o/r/issues/1")));
        assertNull(UrlOpener.refusalFor(URI.create("http://localhost:8080/x")));
        assertNotNull(UrlOpener.refusalFor(URI.create("file:///C:/Windows/System32/calc.exe")));
        assertNotNull(UrlOpener.refusalFor(URI.create("smb://attacker/share")));
        assertNotNull(UrlOpener.refusalFor(URI.create("ms-settings:privacy")));
        assertNotNull(UrlOpener.refusalFor(URI.create("relative/path")));
    }

    @Test
    void aRefusedUrlIsReportedNotLaunched() {
        GitResult result = UrlOpener.open("file:///etc/passwd");

        assertFalse(result.isOk());
        assertTrue(result.getMessage().contains("file"), result.getMessage());
    }
}
