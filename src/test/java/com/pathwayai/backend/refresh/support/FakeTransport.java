package com.pathwayai.backend.refresh.support;

import com.pathwayai.backend.refresh.fetch.HttpTransport;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scripted HTTP transport for crawler tests: no network, fully deterministic. Responses are queued per URL; the last
 * one is repeated once the queue runs dry. A queued {@link IOException} is thrown (a timeout or connection reset).
 * Every request is recorded so a test can assert headers, counts and order.
 */
public class FakeTransport implements HttpTransport {

    public record Request(URI uri, Map<String, String> headers) {
    }

    private final Map<String, Deque<Object>> script = new HashMap<>();
    private final Map<String, Object> last = new HashMap<>();
    public final List<Request> requests = new ArrayList<>();

    public FakeTransport on(String url, Object... responses) {
        Deque<Object> q = script.computeIfAbsent(url, k -> new ArrayDeque<>());
        for (Object r : responses) {
            q.add(r);
        }
        return this;
    }

    /** Replaces whatever was scripted for the URL. */
    public FakeTransport reset(String url, Object... responses) {
        script.remove(url);
        last.remove(url);
        return on(url, responses);
    }

    public void clearRequests() {
        requests.clear();
    }

    public long countFor(String url) {
        return requests.stream().filter(r -> r.uri().toString().equals(url)).count();
    }

    @Override
    public Response get(URI uri, Map<String, String> headers, Duration timeout, int maxBytes) throws IOException {
        requests.add(new Request(uri, Map.copyOf(headers)));
        String key = uri.toString();
        Deque<Object> q = script.get(key);
        Object next;
        if (q != null && !q.isEmpty()) {
            next = q.poll();
            last.put(key, next);
        } else if (last.containsKey(key)) {
            next = last.get(key);
        } else if (key.endsWith("/robots.txt")) {
            next = status(404);
        } else {
            throw new IOException("no scripted response for " + key);
        }
        if (next instanceof IOException e) {
            throw e;
        }
        Response r = (Response) next;
        if (r.body().length > maxBytes) {
            return new Response(r.status(), r.headers(), new byte[0], true);
        }
        return r;
    }

    public static Response status(int code) {
        return new Response(code, Map.of(), new byte[0], false);
    }

    public static Response status(int code, String headerName, String headerValue) {
        return new Response(code, Map.of(headerName.toLowerCase(), headerValue), new byte[0], false);
    }

    public static Response html(String body) {
        return html(body, Map.of());
    }

    public static Response html(String body, Map<String, String> headers) {
        Map<String, String> h = new HashMap<>(headers);
        h.putIfAbsent("content-type", "text/html; charset=utf-8");
        return new Response(200, h, body.getBytes(StandardCharsets.UTF_8), false);
    }

    public static Response text(String contentType, String body) {
        return new Response(200, Map.of("content-type", contentType), body.getBytes(StandardCharsets.UTF_8), false);
    }

    public static Response redirect(String location) {
        return new Response(302, Map.of("location", location), new byte[0], false);
    }

    /** A programme page: main content plus navigation/cookie noise that must not count as a change. */
    public static String page(String main, String noise) {
        return "<html><head><title>Programme</title></head><body>"
                + "<nav>" + noise + "</nav><div class=\"cookie-banner\">" + noise + "</div>"
                + "<main><h1>Programme</h1><p>" + main + "</p></main><footer>" + noise + "</footer></body></html>";
    }

    public static String pageWithJsonLd(String main, String jsonLd) {
        return "<html><head><title>Programme</title><script type=\"application/ld+json\">" + jsonLd
                + "</script></head><body><main><p>" + main + "</p></main></body></html>";
    }
}
