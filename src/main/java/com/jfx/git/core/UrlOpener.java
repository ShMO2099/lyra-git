package com.jfx.git.core;

import java.awt.Desktop;
import java.net.URI;

/**
 * Hands a URL to the system's default browser.
 *
 * <p>Desktop-first, a raw platform command as fallback - see {@link FileOpener} for
 * why: {@link Desktop#browse} can be unsupported on a headless or trimmed JRE, and the
 * failure needs to be recoverable rather than taking the caller down with it. The
 * platform commands are shared with {@link FileOpener}: {@code rundll32
 * url.dll,FileProtocolHandler}, {@code open}, and {@code xdg-open} all accept a URL
 * exactly as readily as a file path.
 */
public final class UrlOpener {

    private UrlOpener() {
    }

    /** Call this off the FX thread: launching a browser can take a moment. */
    public static GitResult open(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            return GitResult.failed("Not a valid URL: " + url);
        }
        String refusal = refusalFor(uri);
        if (refusal != null) return GitResult.failed(refusal);

        String viaDesktop = openWithDesktop(uri);
        if (viaDesktop == null) {
            return GitResult.ok("Opened " + url);
        }

        String viaCommand = openWithCommand(url);
        if (viaCommand == null) {
            return GitResult.ok("Opened " + url);
        }

        return GitResult.failed("Could not open " + url + ": " + viaDesktop + " (and " + viaCommand + ")");
    }

    /**
     * Why {@code uri} must not be opened, or {@code null} for a web page.
     *
     * <p>The URLs opened here come from provider API responses - an issue's or pull
     * request's {@code html_url} - and the system handler opens whatever it is given.
     * A {@code file:} URL, a {@code smb:} share or a custom protocol handler from a
     * compromised or malicious server would run on this machine, not in a browser. Only
     * http and https are web pages, so only they are opened.
     */
    static String refusalFor(URI uri) {
        String scheme = uri.getScheme();
        if (scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                && uri.getHost() != null) {
            return null;
        }
        return "Not opened: only web addresses (http or https) are opened, and this is "
                + (scheme == null ? "not one" : "a " + scheme + ": link") + ".";
    }

    /** @return null on success, otherwise why it could not be used */
    private static String openWithDesktop(URI uri) {
        try {
            if (!Desktop.isDesktopSupported()) {
                return "no desktop integration on this platform";
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                return "this platform cannot open a browser";
            }
            desktop.browse(uri);
            return null;
        } catch (Throwable t) {
            // Throwable: a headless or trimmed JRE surfaces this as an Error, and an
            // unopenable URL must not take the application down with it.
            return describe(t);
        }
    }

    /** @return null on success, otherwise why it failed */
    private static String openWithCommand(String url) {
        try {
            new ProcessBuilder(FileOpener.command(System.getProperty("os.name", ""), url)).start();
            return null;
        } catch (Exception e) {
            return describe(e);
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
