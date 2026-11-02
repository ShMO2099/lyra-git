package com.jfx.git.core;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A git remote URL taken apart: scheme, host, port and repository path.
 *
 * <h2>Why a parser rather than a pattern per provider</h2>
 * Each provider used to find itself in a URL with a regular expression anchored on its
 * public host name. That could not work for a self-hosted server at all, matched the host
 * case-sensitively ({@code GitHub.com} was not GitHub), and read
 * {@code ssh://git@gitlab.com:2222/group/project} as a project called
 * {@code 2222/group/project}. Parsing once, the way git does, gives every provider the
 * same host and path to compare against whichever server it is configured for.
 *
 * <h2>Forms understood</h2>
 * <ul>
 *   <li>{@code scheme://[user@]host[:port]/path} - https, http, ssh, git, git+ssh</li>
 *   <li>git's scp-like form, {@code [user@]host:path}, recognised by git's own rule: a
 *       colon before any slash</li>
 * </ul>
 * Local paths, {@code file://} URLs and Windows drive paths are not remotes on any
 * provider and parse to nothing.
 *
 * @param scheme lower case; {@code ssh} for the scp-like form
 * @param host   lower case - DNS names are case-insensitive
 * @param port   the explicit port, or {@code -1}
 * @param path   the repository path with no leading or trailing slash and no {@code .git}
 */
public record RemoteUrl(String scheme, String host, int port, String path) {

    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*)://(.*)$");
    private static final Pattern SCP_LIKE = Pattern.compile("^(?:[^@/]+@)?([^:/]+):(.*)$");

    public static Optional<RemoteUrl> parse(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        String text = url.trim();
        Matcher scheme = SCHEME.matcher(text);
        if (scheme.matches()) {
            String name = scheme.group(1).toLowerCase(Locale.ROOT);
            if (name.equals("file")) return Optional.empty();
            return fromAuthorityAndPath(name, scheme.group(2));
        }
        Matcher scp = SCP_LIKE.matcher(text);
        if (scp.matches()) {
            String host = scp.group(1);
            // "C:\repo" or "C:/repo" is a Windows path, not host "C".
            if (host.length() == 1 && Character.isLetter(host.charAt(0))) return Optional.empty();
            return build("ssh", host, -1, scp.group(2));
        }
        return Optional.empty();
    }

    private static Optional<RemoteUrl> fromAuthorityAndPath(String scheme, String rest) {
        int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : rest.substring(slash + 1);
        int at = authority.lastIndexOf('@');
        String hostPort = at < 0 ? authority : authority.substring(at + 1);
        String host = hostPort;
        int port = -1;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) return Optional.empty();
            host = hostPort.substring(1, close);
            String after = hostPort.substring(close + 1);
            if (after.startsWith(":") && after.length() > 1) port = parsePort(after.substring(1));
        } else {
            int colon = hostPort.lastIndexOf(':');
            if (colon >= 0) {
                host = hostPort.substring(0, colon);
                String portText = hostPort.substring(colon + 1);
                if (!portText.isEmpty()) {
                    port = parsePort(portText);
                    if (port == -2) return Optional.empty();
                }
            }
        }
        return build(scheme, host, port, path);
    }

    private static int parsePort(String text) {
        try {
            int port = Integer.parseInt(text);
            return port > 0 && port < 65536 ? port : -2;
        } catch (NumberFormatException e) {
            return -2;
        }
    }

    private static Optional<RemoteUrl> build(String scheme, String host, int port, String path) {
        if (host == null || host.isBlank()) return Optional.empty();
        String clean = path;
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        int fragment = clean.indexOf('#');
        if (fragment >= 0) clean = clean.substring(0, fragment);
        clean = trimSlashes(clean);
        if (clean.toLowerCase(Locale.ROOT).endsWith(".git")) {
            clean = trimSlashes(clean.substring(0, clean.length() - 4));
        }
        if (clean.isEmpty()) return Optional.empty();
        return Optional.of(new RemoteUrl(scheme, host.toLowerCase(Locale.ROOT), port, clean));
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') start++;
        while (end > start && value.charAt(end - 1) == '/') end--;
        return value.substring(start, end);
    }

    /** The path's segments, e.g. {@code [group, sub, project]}. */
    public List<String> segments() {
        return Arrays.asList(path.split("/"));
    }

    /** Whether this is a web URL (http or https), where the port identifies the server. */
    public boolean isWeb() {
        return scheme.equals("https") || scheme.equals("http");
    }

    /**
     * Whether this remote lives on the server {@code webBase} names.
     *
     * <p>The host must match, ignoring case. For a web remote an explicit port must match
     * too - {@code https://host:8443} is a different server from {@code https://host}.
     * An ssh remote's port is the ssh daemon's, which has nothing to do with the web
     * server's, so it is not compared.
     */
    public boolean isOn(URI webBase) {
        if (webBase == null || webBase.getHost() == null) return false;
        if (!host.equalsIgnoreCase(webBase.getHost())) return false;
        if (!isWeb()) return true;
        return effectivePort(scheme, port) == effectivePort(
                webBase.getScheme() == null ? "https" : webBase.getScheme().toLowerCase(Locale.ROOT),
                webBase.getPort());
    }

    private static int effectivePort(String scheme, int port) {
        if (port > 0) return port;
        return "http".equals(scheme) ? 80 : 443;
    }

    /**
     * This remote's path with the server's own path prefix removed - a self-hosted
     * install served under {@code https://example.com/gitlab/} puts that prefix in front
     * of every web remote's path, but not in front of an ssh one's.
     */
    public String pathUnder(URI webBase) {
        String prefix = webBase == null || webBase.getPath() == null ? "" : trimSlashes(webBase.getPath());
        if (!prefix.isEmpty() && (path.equals(prefix) || path.startsWith(prefix + "/"))) {
            return trimSlashes(path.substring(prefix.length()));
        }
        return path;
    }
}
