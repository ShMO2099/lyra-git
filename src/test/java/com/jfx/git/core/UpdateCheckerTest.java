package com.jfx.git.core;

import com.jfx.git.core.UpdateChecker.Result;
import com.jfx.git.core.UpdateChecker.Status;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Version comparison, and the update check against a real local HTTP server standing in
 * for the release feed.
 */
class UpdateCheckerTest {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = "{}";

    @BeforeEach
    void setUp() throws Exception {
        Settings.useFile(tempDir.resolve("settings.properties"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        try {
            server.stop(0);
        } catch (RuntimeException alreadyStopped) {
            // One test stops it itself, to see what an unreachable feed looks like.
        }
        Settings.useFile(null);
    }

    private String feed() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/latest";
    }

    // ------------------------------------------------------------------
    // Versions
    // ------------------------------------------------------------------

    @Test
    void numbersAreComparedPartByPartNotAsText() {
        assertTrue(AppVersion.isNewer("1.10", "1.9"), "ten is more than nine");
        assertTrue(AppVersion.isNewer("2.0", "1.99.99"));
        assertTrue(AppVersion.isNewer("1.2.1", "1.2"));
        assertFalse(AppVersion.isNewer("1.2", "1.2.0"), "a missing part is a zero");
        assertFalse(AppVersion.isNewer("1.2", "1.2"));
        assertFalse(AppVersion.isNewer("1.1", "1.2"));
    }

    @Test
    void aReleaseTagsLeadingVIsNotPartOfTheVersion() {
        assertTrue(AppVersion.isNewer("v1.3", "1.2"));
        assertTrue(AppVersion.isNewer("V2.0.0", "v1.9"));
        assertFalse(AppVersion.isNewer("v1.2", "1.2"));
    }

    /** The order they are made in: snapshots and candidates of 1.2 come before 1.2. */
    @Test
    void aReleaseIsNewerThanItsOwnPreRelease() {
        assertTrue(AppVersion.isNewer("1.0", "1.0-SNAPSHOT"));
        assertTrue(AppVersion.isNewer("1.2", "1.2-rc1"));
        assertFalse(AppVersion.isNewer("1.0-SNAPSHOT", "1.0"));
        assertFalse(AppVersion.isNewer("1.0-SNAPSHOT", "1.0-SNAPSHOT"));
        assertTrue(AppVersion.isNewer("1.1-SNAPSHOT", "1.0"), "a later number wins whatever follows it");
    }

    @Test
    void somethingThatIsNotAVersionIsNeverNewer() {
        assertFalse(AppVersion.isNewer("nightly", "1.0"));
        assertFalse(AppVersion.isNewer("1.1", AppVersion.DEVELOPMENT));
        assertFalse(AppVersion.isNewer(null, "1.0"));
        assertFalse(AppVersion.isNewer("", "1.0"));
        assertFalse(AppVersion.isComparable(AppVersion.DEVELOPMENT));
        assertTrue(AppVersion.isComparable("v1.0"));
    }

    // ------------------------------------------------------------------
    // The check
    // ------------------------------------------------------------------

    @Test
    void aLaterReleaseIsReportedWithWhereToFindIt() {
        body = "{\"tag_name\":\"v1.4.0\",\"html_url\":\"https://example.com/releases/v1.4.0\",\"draft\":false}";

        Result result = UpdateChecker.check(feed(), "1.3.2");

        assertEquals(Status.UPDATE_AVAILABLE, result.status());
        assertEquals("v1.4.0", result.version());
        assertEquals("https://example.com/releases/v1.4.0", result.url());
        assertEquals("Version v1.4.0 is available.", result.message());
    }

    @Test
    void theSameOrAnOlderReleaseIsUpToDate() {
        body = "{\"tag_name\":\"v1.3.2\",\"html_url\":\"https://example.com/r\"}";
        assertEquals(Status.UP_TO_DATE, UpdateChecker.check(feed(), "1.3.2").status());
        assertEquals(Status.UP_TO_DATE, UpdateChecker.check(feed(), "1.4").status());
    }

    /** A development build has no version to compare, and must not be told it is out of date. */
    @Test
    void aDevelopmentBuildIsToldTheLatestReleaseButNotThatItIsBehind() {
        body = "{\"tag_name\":\"v9.9\",\"html_url\":\"https://example.com/r\"}";

        Result result = UpdateChecker.check(feed(), AppVersion.DEVELOPMENT);

        assertEquals(Status.UNKNOWN_VERSION, result.status());
        assertEquals("v9.9", result.version());
    }

    @Test
    void aFeedThatFailsOrSaysNothingUsefulIsAFailureNotAnUpdate() {
        status = 404;
        assertEquals(Status.FAILED, UpdateChecker.check(feed(), "1.0").status());

        status = 200;
        body = "<html>not json</html>";
        assertEquals(Status.FAILED, UpdateChecker.check(feed(), "1.0").status());

        body = "{\"name\":\"a release with no tag\"}";
        assertEquals(Status.FAILED, UpdateChecker.check(feed(), "1.0").status());

        server.stop(0);
        Result unreachable = UpdateChecker.check(feed(), "1.0");
        assertEquals(Status.FAILED, unreachable.status());
        assertFalse(unreachable.message().isBlank());
    }

    @Test
    void withNoFeedNothingIsAskedOfAnyone() {
        Result result = UpdateChecker.check("  ", "1.0");

        assertEquals(Status.NOT_CONFIGURED, result.status());
        assertEquals(0, requests.get());
        assertEquals(0, AppPreferences.lastUpdateCheckMillis(), "and no check is recorded as made");
    }

    // ------------------------------------------------------------------
    // How often
    // ------------------------------------------------------------------

    @Test
    void anUnpromptedCheckIsDueOnceADayAndOnlyWithAFeed() {
        Instant now = Instant.now();
        assertFalse(UpdateChecker.isDue(now), "no feed is set");

        AppPreferences.setUpdateFeedUrl(feed());
        assertTrue(UpdateChecker.isDue(now), "never checked before");

        body = "{\"tag_name\":\"v1.0\"}";
        UpdateChecker.check(feed(), "1.0");
        assertFalse(UpdateChecker.isDue(Instant.now()), "just checked");
        assertTrue(UpdateChecker.isDue(Instant.now().plus(Duration.ofHours(25))), "a day later");

        AppPreferences.setUpdateCheckEnabled(false);
        assertFalse(UpdateChecker.isDue(Instant.now().plus(Duration.ofHours(25))), "switched off");
    }

    /** An offline machine is not asked again at every start: a failed check still counts as one. */
    @Test
    void aFailedCheckStillCountsAsHavingChecked() {
        AppPreferences.setUpdateFeedUrl(feed());
        status = 500;

        UpdateChecker.check(feed(), "1.0");

        assertFalse(UpdateChecker.isDue(Instant.now()));
    }
}
