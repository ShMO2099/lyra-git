package com.jfx.git.core;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Following a provider's "next page" to the end of a list.
 *
 * <h2>Why</h2>
 * Every list call asked for one page of a hundred and stopped, so an account with more
 * than a hundred repositories, a pull request with more than a hundred review comments,
 * or a commit with more than a hundred check runs was silently cut short. The last one
 * is the worst: a failed check on page two left the combined build status green.
 *
 * <h2>How</h2>
 * GitHub and GitLab both send an RFC 8288 {@code Link} header with {@code rel="next"};
 * Bitbucket puts the next page's URL in the body's {@code next} field. Both are absolute
 * URLs, and each is followed only when it points at the same server the request went
 * to - the next request carries the access token, and a response naming some other host
 * must not be able to collect it. {@link #MAX_PAGES} bounds a runaway listing.
 */
public final class ApiPaging {

    /** Pages followed before a list is returned as it stands: 2,000 items at 100 a page. */
    public static final int MAX_PAGES = 20;

    private static final Pattern LINK = Pattern.compile("<([^>]*)>\\s*((?:;\\s*[^;,]*)*)");
    private static final Pattern REL = Pattern.compile("rel\\s*=\\s*\"?([^\";]*)\"?", Pattern.CASE_INSENSITIVE);

    private ApiPaging() {
    }

    /** The {@code rel="next"} URL of a response, if it is on {@code base}'s server. */
    public static URI nextLink(HttpResponse<?> response, URI base) {
        for (String header : response.headers().allValues("Link")) {
            URI next = nextFromLinkHeader(header, base);
            if (next != null) return next;
        }
        return null;
    }

    /** The {@code rel="next"} target of one {@code Link} header value, or null. */
    static URI nextFromLinkHeader(String header, URI base) {
        if (header == null) return null;
        Matcher link = LINK.matcher(header);
        while (link.find()) {
            Matcher rel = REL.matcher(link.group(2));
            while (rel.find()) {
                for (String value : rel.group(1).trim().split("\\s+")) {
                    if (value.equalsIgnoreCase("next")) {
                        return sameServer(link.group(1), base);
                    }
                }
            }
        }
        return null;
    }

    /** {@code next} as a URI when it is on the same scheme, host and port as {@code base}. */
    public static URI sameServer(String next, URI base) {
        if (next == null || next.isBlank() || base == null) return null;
        URI uri;
        try {
            uri = base.resolve(next.trim());
        } catch (Exception e) {
            return null;
        }
        if (uri.getHost() == null || base.getHost() == null) return null;
        boolean same = uri.getScheme() != null
                && uri.getScheme().equalsIgnoreCase(base.getScheme())
                && uri.getHost().toLowerCase(Locale.ROOT).equals(base.getHost().toLowerCase(Locale.ROOT))
                && port(uri) == port(base);
        return same ? uri : null;
    }

    private static int port(URI uri) {
        if (uri.getPort() > 0) return uri.getPort();
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }

    /** Whether a listing that stopped at the cap should say so. */
    public static boolean truncated(int pages, Object next) {
        return pages >= MAX_PAGES && next != null;
    }

    /** Joins pages; a helper so each client's loop stays short. */
    public static <T> void addAll(List<T> into, List<T> page) {
        if (page != null) into.addAll(page);
    }
}
