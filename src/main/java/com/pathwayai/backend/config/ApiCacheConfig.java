package com.pathwayai.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * How long a browser, CDN or frontend may reuse an API answer, so that synchronized data reaches users without ever being
 * served stale indefinitely.
 *
 * <ul>
 *   <li>Lists and details: {@code Cache-Control: public, max-age=N, must-revalidate} (default 300 s). After N seconds a cache must
 *       revalidate; the content-derived {@code ETag} makes that a cheap {@code 304} when nothing changed and a fresh {@code 200}
 *       the moment a synchronization changed anything. Worst-case staleness is therefore N seconds.</li>
 *   <li>{@code /api/v1/discovery/*}: {@code no-cache} (always revalidate), because it reports whether the data is current.</li>
 *   <li>Any error response (4xx/5xx): {@code no-store}, so a withheld program that returns later is never remembered as missing.</li>
 * </ul>
 * Only GET/HEAD under {@code /api/v1/} are affected; no response shape changes.
 */
@Configuration
public class ApiCacheConfig {

    @Bean
    FilterRegistrationBean<ApiCacheControlFilter> apiCacheControlFilter(
            @Value("${app.api.cache-max-age-seconds:300}") long maxAgeSeconds) {
        var registration = new FilterRegistrationBean<>(new ApiCacheControlFilter(maxAgeSeconds));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 20); // outside the ETag filter
        return registration;
    }

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> apiEtagFilter() {
        var registration = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        return registration;
    }

    static final class ApiCacheControlFilter extends OncePerRequestFilter {

        private final String cacheable;

        ApiCacheControlFilter(long maxAgeSeconds) {
            this.cacheable = "public, max-age=" + Math.max(0, maxAgeSeconds) + ", must-revalidate";
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()));
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader("Cache-Control", request.getRequestURI().startsWith("/api/v1/discovery/") ? "no-cache" : cacheable);
            chain.doFilter(request, new HttpServletResponseWrapper(response) {
                @Override
                public void setStatus(int status) {
                    noStoreIfError(status);
                    super.setStatus(status);
                }

                @Override
                public void sendError(int status) throws IOException {
                    noStoreIfError(status);
                    super.sendError(status);
                }

                @Override
                public void sendError(int status, String message) throws IOException {
                    noStoreIfError(status);
                    super.sendError(status, message);
                }

                private void noStoreIfError(int status) {
                    if (status >= 400) {
                        setHeader("Cache-Control", "no-store");
                    }
                }
            });
        }
    }
}
