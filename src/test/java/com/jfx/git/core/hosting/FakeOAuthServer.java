package com.jfx.git.core.hosting;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A provider's OAuth endpoints, on a local port: each path answers from a script of
 * replies and records every request it gets, so a test can say what the provider said and
 * check what it was sent. The last scripted reply repeats once the script runs out.
 */
public final class FakeOAuthServer implements AutoCloseable {

    /** One request as the server saw it. */
    public record Request(String method, String path, Map<String, String> form, String authorization, String accept) {
    }

    private record Reply(int status, String body) {
    }

    private final HttpServer server;
    private final Map<String, Deque<Reply>> scripts = new ConcurrentHashMap<>();
    final List<Request> requests = new CopyOnWriteArrayList<>();

    public FakeOAuthServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(new Request(exchange.getRequestMethod(), path, form(body),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Accept")));
                Reply reply;
                Deque<Reply> script = scripts.get(path);
                synchronized (this) {
                    if (script == null || script.isEmpty()) {
                        reply = new Reply(404, "{\"message\":\"not found\"}");
                    } else {
                        reply = script.size() > 1 ? script.poll() : script.peek();
                    }
                }
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }

    /** Appends replies to {@code path}'s script. */
    public synchronized FakeOAuthServer on(String path, int status, String body) {
        scripts.computeIfAbsent(path, p -> new ArrayDeque<>()).add(new Reply(status, body));
        return this;
    }

    public URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    public List<Request> requestsTo(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    static Map<String, String> form(String body) {
        Map<String, String> out = new HashMap<>();
        if (body == null || body.isEmpty()) return out;
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
                    eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private boolean closed;

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        server.stop(0);
    }
}
