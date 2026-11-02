package com.jfx.git.core;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Asks a release feed whether there is a newer version, and says so. It never downloads
 * or installs anything.
 *
 * <p>The feed is whatever URL Preferences names: a GitHub "latest release" endpoint
 * ({@code https://api.github.com/repos/<owner>/<repo>/releases/latest}) or anything
 * that answers with the same two fields. There is no default, because a build of this
 * application does not know where its releases are published; with no feed set, nothing
 * is asked of anyone.
 *
 * <p>At most once a day unprompted. One request, with a short timeout, on the network
 * lane; a failure is recorded as a check having happened, so a machine that is offline
 * is not asked again at every start.
 */
public final class UpdateChecker {

    /** How long between unprompted checks. */
    static final Duration INTERVAL = Duration.ofHours(24);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private UpdateChecker() {
    }

    /** What a check found. */
    public enum Status {
        /** A later version is published. */
        UPDATE_AVAILABLE,
        /** The feed's latest version is this one, or older. */
        UP_TO_DATE,
        /** No feed is set in Preferences. */
        NOT_CONFIGURED,
        /** This is a development build, whose version cannot be compared with anything. */
        UNKNOWN_VERSION,
        /** The feed could not be read. */
        FAILED
    }

    /**
     * The outcome of one check.
     *
     * @param version the latest published version, when the feed was read
     * @param url     where that release can be found, or blank
     * @param message what to tell the user; never blank
     */
    public record Result(Status status, String version, String url, String message) {
    }

    /** The two fields read from a release feed; GitHub's names. A record, as the provider clients' are. */
    private record Release(@SerializedName("tag_name") String tagName,
                           @SerializedName("html_url") String htmlUrl) {
    }

    /** Whether an unprompted check is due: enabled, a feed set, and a day since the last. */
    public static boolean isDue(Instant now) {
        if (!AppPreferences.updateCheckEnabled() || AppPreferences.updateFeedUrl().isBlank()) {
            return false;
        }
        long last = AppPreferences.lastUpdateCheckMillis();
        return last <= 0 || Duration.between(Instant.ofEpochMilli(last), now).compareTo(INTERVAL) >= 0;
    }

    /** Checks the configured feed for a version later than the one running. */
    public static Result check() {
        return check(AppPreferences.updateFeedUrl(), AppVersion.current());
    }

    /**
     * Checks {@code feedUrl} for a version later than {@code installed}, and records
     * that a check was made.
     */
    static Result check(String feedUrl, String installed) {
        if (feedUrl == null || feedUrl.isBlank()) {
            return new Result(Status.NOT_CONFIGURED, "", "",
                    "No update feed is set. Name one in Settings, under General.");
        }
        AppPreferences.setLastUpdateCheckMillis(System.currentTimeMillis());
        Release latest;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(feedUrl.trim()))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "Lyra")
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return failed("The update feed answered " + response.statusCode() + ".");
            }
            latest = new Gson().fromJson(response.body(), Release.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("The update check was interrupted.");
        } catch (Exception e) {
            String why = e.getMessage();
            return failed("The update feed could not be read"
                    + (why == null || why.isBlank() ? "." : ": " + why));
        }
        if (latest == null || latest.tagName() == null || latest.tagName().isBlank()) {
            return failed("The update feed did not name a version.");
        }

        String version = latest.tagName().trim();
        String url = latest.htmlUrl() == null ? "" : latest.htmlUrl().trim();
        if (!AppVersion.isComparable(installed)) {
            return new Result(Status.UNKNOWN_VERSION, version, url,
                    "The latest release is " + version + ". This is a " + installed
                            + ", so there is nothing to compare it with.");
        }
        if (AppVersion.isNewer(version, installed)) {
            return new Result(Status.UPDATE_AVAILABLE, version, url, "Version " + version + " is available.");
        }
        return new Result(Status.UP_TO_DATE, version, url, "You have the latest version, " + installed + ".");
    }

    private static Result failed(String message) {
        return new Result(Status.FAILED, "", "", message);
    }
}
