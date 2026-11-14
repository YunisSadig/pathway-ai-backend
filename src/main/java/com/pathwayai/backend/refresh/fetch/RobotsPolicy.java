package com.pathwayai.backend.refresh.fetch;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * robots.txt handling per RFC 9309: the group for our product token, else the {@code *} group; the longest matching
 * rule wins and Allow beats Disallow on a tie; a missing robots.txt (4xx) allows everything; an unreachable one
 * (5xx or network error) disallows everything until the cached answer expires. Cached per host for 24 hours.
 */
public class RobotsPolicy {

    /** {@code temporary} is true when the answer is only a precaution because robots.txt could not be fetched. */
    public record Decision(boolean allowed, String reason, boolean temporary) {
        static Decision yes() {
            return new Decision(true, null, false);
        }
    }

    private record Rules(List<String> allow, List<String> disallow, boolean disallowAll, Duration crawlDelay,
                         Instant fetchedAt) {
    }

    private static final Duration TTL = Duration.ofHours(24);

    private final HttpTransport transport;
    private final Clock clock;
    private final String productToken;
    private final String userAgent;
    private final Duration timeout;
    private final int maxBytes;
    private final Map<String, Rules> cache = new ConcurrentHashMap<>();

    public RobotsPolicy(HttpTransport transport, Clock clock, String userAgent, Duration timeout, int maxBytes) {
        this.transport = transport;
        this.clock = clock;
        this.userAgent = userAgent;
        this.productToken = userAgent.split("[/ ]")[0].toLowerCase(Locale.ROOT);
        this.timeout = timeout;
        this.maxBytes = maxBytes;
    }

    public Decision check(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        Rules rules = cache.get(host);
        if (rules == null || rules.fetchedAt().plus(TTL).isBefore(clock.instant())) {
            rules = load(host);
            cache.put(host, rules);
        }
        if (rules.disallowAll()) {
            return new Decision(false, "robots.txt unavailable (server error), assuming disallow for now", true);
        }
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        int allowLen = longestMatch(rules.allow(), path);
        int disallowLen = longestMatch(rules.disallow(), path);
        boolean allowed = disallowLen < 0 || allowLen >= disallowLen;
        return allowed ? Decision.yes() : new Decision(false, "robots.txt disallows " + path, false);
    }

    private Rules load(String host) {
        Instant now = clock.instant();
        try {
            var response = transport.get(URI.create("https://" + host + "/robots.txt"),
                    Map.of("User-Agent", userAgent, "Accept", "text/plain"), timeout, maxBytes);
            if (response.status() >= 500 || response.status() == 429) {
                return new Rules(List.of(), List.of(), true, Duration.ZERO, now);
            }
            if (response.status() != 200 || response.tooLarge()) {
                return new Rules(List.of(), List.of(), false, Duration.ZERO, now); // no usable robots.txt: no restrictions
            }
            return parse(new String(response.body(), StandardCharsets.UTF_8), now);
        } catch (IOException e) {
            return new Rules(List.of(), List.of(), true, Duration.ZERO, now);
        }
    }

    Rules parse(String text, Instant now) {
        List<String> ownAllow = new ArrayList<>(), ownDisallow = new ArrayList<>();
        List<String> anyAllow = new ArrayList<>(), anyDisallow = new ArrayList<>();
        boolean sawOwn = false;
        Duration ownDelay = null, anyDelay = null;
        List<String> agents = new ArrayList<>();
        boolean groupHasRules = false;
        for (String raw : text.split("\\R")) {
            String line = raw.contains("#") ? raw.substring(0, raw.indexOf('#')) : raw;
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "user-agent" -> {
                    if (groupHasRules) {
                        agents.clear();
                        groupHasRules = false;
                    }
                    agents.add(value.toLowerCase(Locale.ROOT));
                }
                case "allow", "disallow" -> {
                    groupHasRules = true;
                    boolean own = agents.contains(productToken);
                    boolean any = agents.contains("*");
                    if (own) {
                        sawOwn = true;
                    }
                    if (value.isEmpty()) {
                        continue; // "Disallow:" with no value allows everything
                    }
                    List<String> ownTarget = key.equals("allow") ? ownAllow : ownDisallow;
                    List<String> anyTarget = key.equals("allow") ? anyAllow : anyDisallow;
                    if (own) {
                        ownTarget.add(value);
                    }
                    if (any) {
                        anyTarget.add(value);
                    }
                }
                case "crawl-delay" -> {
                    groupHasRules = true;
                    Duration d = parseDelay(value);
                    if (d != null) {
                        if (agents.contains(productToken)) {
                            ownDelay = d;
                            sawOwn = true;
                        }
                        if (agents.contains("*")) {
                            anyDelay = d;
                        }
                    }
                }
                default -> {
                }
            }
        }
        return sawOwn ? new Rules(ownAllow, ownDisallow, false, ownDelay == null ? Duration.ZERO : ownDelay, now)
                : new Rules(anyAllow, anyDisallow, false, anyDelay == null ? Duration.ZERO : anyDelay, now);
    }

    /** Crawl-delay is not part of RFC 9309 but widely published; it is honoured exactly as published. */
    private static Duration parseDelay(String value) {
        try {
            double seconds = Double.parseDouble(value.trim());
            if (seconds <= 0) {
                return null;
            }
            return Duration.ofMillis((long) (seconds * 1000));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The Crawl-delay a host published (zero if none); valid once {@link #check} has loaded its robots.txt. */
    public Duration crawlDelay(String host) {
        Rules rules = cache.get(host.toLowerCase(Locale.ROOT));
        return rules == null ? Duration.ZERO : rules.crawlDelay();
    }

    /** Length of the longest matching rule (supporting * and trailing $), or -1 if none matches. */
    private static int longestMatch(List<String> rules, String path) {
        int best = -1;
        for (String rule : rules) {
            if (matches(rule, path)) {
                best = Math.max(best, rule.length());
            }
        }
        return best;
    }

    private static boolean matches(String rule, String path) {
        boolean anchored = rule.endsWith("$");
        String body = anchored ? rule.substring(0, rule.length() - 1) : rule;
        StringBuilder regex = new StringBuilder("^");
        for (char c : body.toCharArray()) {
            regex.append(c == '*' ? ".*" : java.util.regex.Pattern.quote(String.valueOf(c)));
        }
        if (anchored) {
            regex.append('$');
        }
        return java.util.regex.Pattern.compile(regex.toString()).matcher(path).find();
    }
}
