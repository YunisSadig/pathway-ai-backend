package com.pathwayai.backend.refresh.fetch;

import com.pathwayai.backend.refresh.RefreshProperties;
import com.pathwayai.backend.refresh.registry.SourceMonitorRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The hosts the crawler may contact: the domain of every registered, non-paused source, plus any explicitly
 * configured extras. A host matches a registered domain exactly or as a subdomain (the {@code www.} prefix is
 * ignored), so {@code aalto.fi} and {@code studies.aalto.fi} are covered by a source on {@code www.aalto.fi}, but a
 * redirect to an unrelated site is not. The list is cached for a minute.
 */
@Component
public class ApprovedHosts {

    private static final Duration TTL = Duration.ofMinutes(1);

    private final SourceMonitorRepository monitors;
    private final RefreshProperties props;
    private final Clock clock;
    private volatile Set<String> cached = Set.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    public ApprovedHosts(SourceMonitorRepository monitors, RefreshProperties props, Clock clock) {
        this.monitors = monitors;
        this.props = props;
        this.clock = clock;
    }

    public boolean isApproved(String host) {
        String h = strip(host);
        for (String domain : domains()) {
            if (h.equals(domain) || h.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> domains() {
        Instant now = clock.instant();
        if (loadedAt.plus(TTL).isBefore(now)) {
            Set<String> fresh = new HashSet<>();
            monitors.activeDomains().forEach(d -> fresh.add(strip(d)));
            props.extraApprovedHosts().forEach(d -> fresh.add(strip(d)));
            cached = fresh;
            loadedAt = now;
        }
        return cached;
    }

    /** Forget the cache (after registering new sources). */
    public void refresh() {
        loadedAt = Instant.EPOCH;
    }

    private static String strip(String host) {
        String h = host.toLowerCase(Locale.ROOT).trim();
        return h.startsWith("www.") ? h.substring(4) : h;
    }
}
