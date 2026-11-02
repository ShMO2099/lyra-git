package com.jfx.git.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A git server over plain HTTP on this machine, behind a Basic-auth gate, that remembers
 * every login it was sent.
 *
 * <p>Real git, not a stub: each request is handed to {@code git http-backend} as CGI, so a
 * clone, fetch and push through either engine run the actual smart-HTTP protocol and fail
 * the way a real server would. What it adds is the gate - only the logins it was told to
 * accept get past the 401 - and the record of what arrived, which is what a test about
 * credentials needs to assert on: who logged in, with what, and in what order.
 *
 * <p>Needs git installed; callers check {@code NativeGitRunner.isGitInstalled()} first.
 */
public final class FakeSmartHttpServer implements AutoCloseable {

    /** One login as it arrived, decoded from the Authorization header. */
    public record Login(String username, String password) {
    }

    private final Path root;
    private final HttpServer server;
    private final Set<Login> accepted = ConcurrentHashMap.newKeySet();
    private final List<Login> received = Collections.synchronizedList(new ArrayList<>());

    /**
     * Serves every repository under {@code root} at {@code http://127.0.0.1:<port>/<name>}.
     */
    public FakeSmartHttpServer(Path root) throws IOException {
        this.root = root;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-smart-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    /** Lets {@code username}/{@code password} in. */
    public FakeSmartHttpServer accept(String username, String password) {
        accepted.add(new Login(username, password));
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** The server's web address, as a provider would be configured with it. */
    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    /** The URL of the repository {@code name} under the root. */
    public String url(String name) {
        return baseUrl() + "/" + name;
    }

    /** Every login sent so far, oldest first, including refused ones. */
    public List<Login> received() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            Login login = basic(exchange.getRequestHeaders().getFirst("Authorization"));
            if (login != null) received.add(login);
            if (login == null || !accepted.contains(login)) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"fake\"");
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            cgi(exchange, login);
        }
    }

    private static Login basic(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        int colon = decoded.indexOf(':');
        return colon < 0 ? null : new Login(decoded.substring(0, colon), decoded.substring(colon + 1));
    }

    /** Runs {@code git http-backend} for one request and relays its CGI answer. */
    private void cgi(HttpExchange exchange, Login login) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        ProcessBuilder pb = new ProcessBuilder("git", "http-backend");
        Map<String, String> env = pb.environment();
        env.put("GIT_PROJECT_ROOT", root.toAbsolutePath().toString());
        env.put("GIT_HTTP_EXPORT_ALL", "1");
        env.put("REQUEST_METHOD", exchange.getRequestMethod());
        env.put("PATH_INFO", exchange.getRequestURI().getPath());
        String query = exchange.getRequestURI().getRawQuery();
        env.put("QUERY_STRING", query == null ? "" : query);
        env.put("REMOTE_USER", login.username());
        env.put("REMOTE_ADDR", "127.0.0.1");
        env.put("CONTENT_LENGTH", Integer.toString(body.length));
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null) env.put("CONTENT_TYPE", contentType);
        String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
        if (encoding != null) env.put("HTTP_CONTENT_ENCODING", encoding);
        String protocol = exchange.getRequestHeaders().getFirst("Git-Protocol");
        if (protocol != null) env.put("GIT_PROTOCOL", protocol);
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();
        Thread feeder = new Thread(() -> {
            try (OutputStream in = process.getOutputStream()) {
                in.write(body);
            } catch (IOException ignored) {
                // The backend stopped reading; its exit status says why.
            }
        }, "fake-smart-http-feed");
        feeder.setDaemon(true);
        feeder.start();
        byte[] output;
        try (InputStream out = process.getInputStream()) {
            output = out.readAllBytes();
        }
        try {
            process.waitFor();
            feeder.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        relay(exchange, output);
    }

    /** Splits CGI output into its headers and body and sends them on. */
    private static void relay(HttpExchange exchange, byte[] output) throws IOException {
        int split = indexOf(output, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        int bodyStart = split + 4;
        if (split < 0) {
            split = indexOf(output, "\n\n".getBytes(StandardCharsets.US_ASCII));
            bodyStart = split + 2;
        }
        if (split < 0) {
            exchange.sendResponseHeaders(500, -1);
            return;
        }
        int status = 200;
        for (String line : new String(output, 0, split, StandardCharsets.ISO_8859_1).split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Status")) {
                status = Integer.parseInt(value.split(" ")[0]);
            } else {
                exchange.getResponseHeaders().add(name, value);
            }
        }
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        bodyBytes.write(output, bodyStart, output.length - bodyStart);
        byte[] responseBody = bodyBytes.toByteArray();
        exchange.sendResponseHeaders(status, responseBody.length == 0 ? -1 : responseBody.length);
        if (responseBody.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(responseBody);
            }
        }
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
