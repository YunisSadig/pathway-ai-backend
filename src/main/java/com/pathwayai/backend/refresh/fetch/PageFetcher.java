package com.pathwayai.backend.refresh.fetch;

import com.pathwayai.backend.refresh.RefreshProperties;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Fetches one official page politely and safely. Per request: URL guard (https, approved host, public addresses),
 * robots.txt, a per-host minimum interval, a conditional GET (ETag / Last-Modified), manual redirects that are
 * re-validated at every hop, a size cap, bounded retries with back-off for transient errors, and HTTP 429 /
 * Retry-After handling. It never solves or bypasses a challenge page, CAPTCHA, login or 403: those are reported as
 * {@link Kind#BLOCKED} so the source is marked unsupported instead of being hammered.
 */
public class PageFetcher {

    public enum Kind { OK, NOT_MODIFIED, BLOCKED, RATE_LIMITED, FAILED }

    /** What the caller needs to know. {@code retryAfter} is set only for RATE_LIMITED. */
    public record Result(Kind kind, int httpStatus, String finalUrl, String contentType, String body, String etag,
                         String lastModified, String error, Duration retryAfter, long durationMs) {
        static Result of(Kind kind, int status, String error, long ms) {
            return new Result(kind, status, null, null, null, null, null, error, null, ms);
        }
    }

    /** Sleeping is injected so tests (and the throttle) never really wait. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private static final Set<String> TEXT_TYPES = Set.of("text/html", "application/xhtml+xml", "application/json",
            "application/ld+json", "application/vnd.api+json", "text/plain", "application/xml", "text/xml");
    /** A published Crawl-delay above this is not waited for in-process and not shortened either: the host is not fetched. */
    static final Duration MAX_CRAWL_DELAY = Duration.ofMinutes(5);
    private static final Pattern CHALLENGE = Pattern.compile(
            "(?i)<title[^>]*>\\s*(just a moment|attention required|one moment, please|access denied|please wait|"
                    + "checking your browser|are you a robot|verify you are human)");

    private final HttpTransport transport;
    private final UrlGuard guard;
    private final RobotsPolicy robots;
    private final RefreshProperties props;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<String, Instant> lastRequestByHost = new ConcurrentHashMap<>();

    public PageFetcher(HttpTransport transport, UrlGuard guard, RobotsPolicy robots, RefreshProperties props,
                       Clock clock, Sleeper sleeper) {
        this.transport = transport;
        this.guard = guard;
        this.robots = robots;
        this.props = props;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public Result fetch(String url, String etag, String lastModified) {
        long start = System.nanoTime();
        try {
            String current = url;
            String[] redirect = new String[1];
            for (int hop = 0; hop <= props.maxRedirects(); hop++) {
                URI uri;
                try {
                    uri = guard.validate(current);
                } catch (UrlGuard.UnsafeUrlException e) {
                    return Result.of(Kind.BLOCKED, 0, (hop == 0 ? "" : "redirect: ") + e.getMessage(), ms(start));
                }
                var decision = robots.check(uri);
                if (!decision.allowed()) {
                    // an unreachable robots.txt is temporary: retry later, never mark the source unsupported for it
                    return Result.of(decision.temporary() ? Kind.FAILED : Kind.BLOCKED, 0, decision.reason(), ms(start));
                }
                if (robots.crawlDelay(uri.getHost()).compareTo(MAX_CRAWL_DELAY) > 0) {
                    return Result.of(Kind.FAILED, 0, "robots.txt Crawl-delay of " + robots.crawlDelay(uri.getHost()).toSeconds()
                            + " s exceeds the " + MAX_CRAWL_DELAY.toSeconds() + " s this crawler will wait; not fetched, never sped up", ms(start));
                }
                Result outcome = requestWithRetries(uri, hop == 0 ? etag : null, hop == 0 ? lastModified : null, start, redirect);
                if (outcome != null) {
                    return outcome;
                }
                current = redirect[0]; // requestWithRetries returns null only for a redirect
            }
            return Result.of(Kind.FAILED, 0, "too many redirects", ms(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.of(Kind.FAILED, 0, "interrupted", ms(start));
        }
    }

    private Result requestWithRetries(URI uri, String etag, String lastModified, long start, String[] redirectOut)
            throws InterruptedException {
        IOException lastIo = null;
        int lastStatus = 0;
        for (int attempt = 0; attempt <= props.maxRetries(); attempt++) {
            if (attempt > 0) {
                sleeper.sleep(Duration.ofMillis(props.backoffMs() * (1L << (attempt - 1))));
            }
            throttle(uri.getHost());
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", props.userAgent());
            headers.put("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,text/plain;q=0.5");
            if (etag != null) {
                headers.put("If-None-Match", etag);
            }
            if (lastModified != null) {
                headers.put("If-Modified-Since", lastModified);
            }
            HttpTransport.Response r;
            try {
                r = transport.get(uri, headers, Duration.ofSeconds(props.timeoutSeconds()), props.maxBodyBytes());
            } catch (IOException e) {
                lastIo = e;
                continue; // timeout or network error: transient, retry with back-off
            }
            lastStatus = r.status();
            int s = r.status();
            if (s == 304) {
                return new Result(Kind.NOT_MODIFIED, 304, uri.toString(), null, null, etag, lastModified, null, null, ms(start));
            }
            if (s == 301 || s == 302 || s == 303 || s == 307 || s == 308) {
                String location = r.header("location");
                if (location == null) {
                    return Result.of(Kind.FAILED, s, "redirect without Location", ms(start));
                }
                redirectOut[0] = uri.resolve(location).toString();
                return null;
            }
            if (s == 429 || s == 503 && r.header("retry-after") != null) {
                Duration wait = retryAfter(r.header("retry-after"));
                return new Result(Kind.RATE_LIMITED, s, uri.toString(), null, null, null, null,
                        "HTTP " + s + " (rate limited)", wait, ms(start));
            }
            if (s >= 500) {
                continue; // transient server error: retry
            }
            if (s == 401 || s == 403) {
                return Result.of(Kind.BLOCKED, s, "access denied (HTTP " + s + "); not bypassed", ms(start));
            }
            if (s != 200) {
                return Result.of(Kind.FAILED, s, "HTTP " + s, ms(start)); // 404/410 do not prove a page was discontinued
            }
            if (r.tooLarge()) {
                return Result.of(Kind.FAILED, s, "response larger than " + props.maxBodyBytes() + " bytes", ms(start));
            }
            String type = r.header("content-type") == null ? "" : r.header("content-type").split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!TEXT_TYPES.contains(type)) {
                return Result.of(Kind.FAILED, s, "unsupported content type: " + type, ms(start));
            }
            String body = new String(r.body(), StandardCharsets.UTF_8);
            if (type.contains("html") && CHALLENGE.matcher(body.substring(0, Math.min(body.length(), 4000))).find()) {
                return Result.of(Kind.BLOCKED, s, "bot-protection challenge page; not bypassed", ms(start));
            }
            return new Result(Kind.OK, s, uri.toString(), type, body, r.header("etag"), r.header("last-modified"), null, null, ms(start));
        }
        String why = lastIo != null ? "network error: " + lastIo.getMessage() : "HTTP " + lastStatus;
        return Result.of(Kind.FAILED, lastStatus, why + " (after " + (props.maxRetries() + 1) + " attempts)", ms(start));
    }

    /** At most one request per host per minHostIntervalMs, across all sources on that host. */
    private void throttle(String host) throws InterruptedException {
        Instant now = clock.instant();
        Instant last = lastRequestByHost.get(host);
        if (last != null) {
            Duration interval = Duration.ofMillis(props.minHostIntervalMs());
            Duration published = robots.crawlDelay(host); // the host's own Crawl-delay, when it states one
            if (published.compareTo(interval) > 0) {
                interval = published;
            }
            Duration wait = interval.minus(Duration.between(last, now));
            if (!wait.isNegative() && !wait.isZero()) {
                sleeper.sleep(wait);
            }
        }
        lastRequestByHost.put(host, clock.instant());
    }

    private Duration retryAfter(String value) {
        Duration cap = Duration.ofSeconds(props.maxRetryAfterSeconds());
        Duration wait = Duration.ofHours(1);
        if (value != null) {
            try {
                wait = Duration.ofSeconds(Long.parseLong(value.trim()));
            } catch (NumberFormatException e) {
                try {
                    wait = Duration.between(clock.instant(),
                            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                } catch (RuntimeException ignored) {
                    // keep the default
                }
            }
        }
        if (wait.isNegative()) {
            wait = Duration.ofMinutes(1);
        }
        return wait.compareTo(cap) > 0 ? cap : wait;
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
