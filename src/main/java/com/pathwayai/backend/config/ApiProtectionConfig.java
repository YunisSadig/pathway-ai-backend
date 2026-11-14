package com.pathwayai.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Two protections for {@code /api/v1/*} that exist because the catalogue may not be public yet:
 *
 * <ul>
 *   <li><b>Staging access gate.</b> While {@code app.access.public-launch} is false, every API request must carry the header
 *       {@code X-Staging-Key} equal to {@code app.access.staging-key} (env {@code STAGING_ACCESS_KEY}); otherwise 401. The key is a
 *       server-side secret: only a server (the frontend's server-side proxy, a smoke test) may hold it, never browser JavaScript.
 *       In the {@code prod} profile the application REFUSES TO START unless either the key is set or {@code PUBLIC_LAUNCH=true}
 *       was set deliberately, so a deployment can never become public by forgetting a variable. Preflight (OPTIONS) and
 *       {@code /actuator/health} are not gated.</li>
 *   <li><b>Rate limit.</b> A fixed window per client address ({@code app.rate-limit.per-minute}, default 240; 0 disables) answers 429
 *       with {@code Retry-After}. In memory, bounded, no new dependency; it protects a small free instance, it is not a security boundary.</li>
 * </ul>
 */
@Configuration
public class ApiProtectionConfig {

    @Bean
    @Profile("prod")
    PublicLaunchGuard publicLaunchGuard(@Value("${app.access.staging-key:}") String key,
                                        @Value("${app.access.public-launch:false}") boolean publicLaunch) {
        if (!publicLaunch && key.isBlank()) {
            throw new IllegalStateException("production needs STAGING_ACCESS_KEY (protected staging) or PUBLIC_LAUNCH=true "
                    + "(only after the data reuse rights are established): refusing to start an unprotected API by accident");
        }
        return new PublicLaunchGuard();
    }

    record PublicLaunchGuard() {}

    @Bean
    FilterRegistrationBean<StagingAccessFilter> stagingAccessFilter(@Value("${app.access.staging-key:}") String key,
                                                                    @Value("${app.access.public-launch:false}") boolean publicLaunch) {
        var registration = new FilterRegistrationBean<>(new StagingAccessFilter(publicLaunch ? "" : key));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(@Value("${app.rate-limit.per-minute:240}") int perMinute) {
        var registration = new FilterRegistrationBean<>(new RateLimitFilter(perMinute, System::currentTimeMillis));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    static final class StagingAccessFilter extends OncePerRequestFilter {

        private final byte[] key;

        StagingAccessFilter(String key) {
            this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return key.length == 0 || "OPTIONS".equals(request.getMethod());
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String supplied = request.getHeader("X-Staging-Key");
            boolean ok = supplied != null && MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), key);
            if (!ok) {
                response.setHeader("Cache-Control", "no-store");
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            chain.doFilter(request, response);
        }
    }

    static final class RateLimitFilter extends OncePerRequestFilter {

        private static final int MAX_TRACKED_CLIENTS = 10_000;

        private final int perMinute;
        private final java.util.function.LongSupplier clock;
        private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        private volatile long window = -1;

        RateLimitFilter(int perMinute, java.util.function.LongSupplier clock) {
            this.perMinute = perMinute;
            this.clock = clock;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return perMinute <= 0 || "OPTIONS".equals(request.getMethod());
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            long now = clock.getAsLong();
            long current = now / 60_000;
            synchronized (this) {
                if (current != window || counts.size() > MAX_TRACKED_CLIENTS) {
                    counts.clear();
                    window = current;
                }
            }
            int used = counts.computeIfAbsent(request.getRemoteAddr(), k -> new AtomicInteger()).incrementAndGet();
            if (used > perMinute) {
                long retryAfter = Math.max(1, ((current + 1) * 60_000 - now + 999) / 1000);
                response.setHeader("Retry-After", Long.toString(retryAfter));
                response.setHeader("Cache-Control", "no-store");
                response.sendError(429);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
