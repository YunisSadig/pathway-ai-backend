package com.pathwayai.backend.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Who may call what.
 *
 * <ul>
 *   <li>Account endpoints: sign-up, login, verification and reset are open; everything else needs a session.</li>
 *   <li>Catalogue endpoints (programs, universities, discovery status): while the Studyinfo reuse conditions are unresolved they need a
 *       signed-in account whose {@code catalogue_access} flag an administrator has set; {@code app.access.catalogue-public=true} (public
 *       launch) opens them. Signing up is NOT authorization to read the catalogue.</li>
 *   <li>Sessions are server-side (Spring Session JDBC); the browser holds only an opaque HttpOnly SameSite=Lax cookie that the website's
 *       own proxy forwards, so no token is ever readable by JavaScript or stored in localStorage.</li>
 *   <li>CSRF: the browser never calls this API directly (the cookie belongs to the website's origin, SameSite=Lax), every call also needs
 *       the server-held staging key, and state-changing requests that carry an {@code Origin} must come from the website
 *       ({@link OriginCheckFilter}). Spring's token-based CSRF would add nothing on top and is switched off on purpose.</li>
 * </ul>
 */
// Only for a real web application: the command-line jobs (bootstrap, sync, verify) run with web-application-type=none, where there is
// no HttpSecurity and no session repository.
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
class AccountSecurityConfig {

    /**
     * The session cookie, defined explicitly instead of relying on server.servlet.session.cookie.* (which Boot applies only to an embedded
     * server): an opaque id, HttpOnly (no script can read it), SameSite=Lax, Secure in production, and never in localStorage.
     */
    @Bean
    org.springframework.session.web.http.CookieSerializer cookieSerializer(
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        var serializer = new org.springframework.session.web.http.DefaultCookieSerializer();
        serializer.setCookieName("PATHWAY_SESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(secure);
        return serializer;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AccountService accounts, AccountProperties props,
                                            @Value("${app.cors.allowed-origins}") List<String> corsOrigins) throws Exception {
        Set<String> origins = new HashSet<>(corsOrigins);
        origins.add(props.siteUrl());
        AuthorizationManager<RequestAuthorizationContext> catalogue = (authentication, context) -> {
            if (accounts.cataloguePublic()) {
                return new AuthorizationDecision(true);
            }
            Authentication a = authentication.get();
            if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) {
                return new AuthorizationDecision(false);
            }
            try {
                return new AuthorizationDecision(accounts.canViewCatalogue(UUID.fromString(a.getName())));
            } catch (RuntimeException e) {
                return new AuthorizationDecision(false);
            }
        };

        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                // no "remember the page you wanted": it would create a database session for every anonymous 401
                .requestCache(rc -> rc.disable())
                .formLogin(AbstractHttpConfigurer::disable)
                // Spring's default "Cache-Control: no-cache, no-store" on every response would defeat the API's own caching policy
                // (ApiCacheConfig sets it per route), so only that one header writer is switched off.
                .headers(h -> h.cacheControl(c -> c.disable()).frameOptions(f -> f.deny()))
                .sessionManagement(s -> s.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(f -> f.changeSessionId()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> json(res, 401, "UNAUTHENTICATED", "Please log in."))
                        .accessDeniedHandler((req, res, ex) -> json(res, 403, "CATALOGUE_PROTECTED",
                                "This part of Pathway AI is in a protected beta and is not open to your account yet.")))
                .logout(l -> l
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/account/logout"))
                        .deleteCookies("PATHWAY_SESSION")
                        .logoutSuccessHandler((req, res, auth) -> {
                            res.setStatus(204);
                            res.setHeader("Cache-Control", "no-store");
                        }))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/account/config").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/account/register", "/api/v1/account/login",
                                "/api/v1/account/verify-email", "/api/v1/account/resend-verification",
                                "/api/v1/account/forgot-password", "/api/v1/account/reset-password").permitAll()
                        .requestMatchers("/api/v1/account/**").authenticated()
                        .requestMatchers("/api/v1/programs/**", "/api/v1/universities/**", "/api/v1/discovery/**").access(catalogue)
                        .anyRequest().permitAll())
                .addFilterBefore(new AbsoluteSessionLifetimeFilter(props.absoluteSessionAge()), SecurityContextHolderFilter.class)
                .addFilterBefore(new OriginCheckFilter(origins), SecurityContextHolderFilter.class);
        return http.build();
    }

    private static void json(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setHeader("Cache-Control", "no-store");
        res.setContentType("application/json");
        res.getWriter().write("{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    /** Ends a session this long after login, however active it was, so a stolen cookie cannot live forever. */
    static final class AbsoluteSessionLifetimeFilter extends OncePerRequestFilter {
        private final Duration maxAge;

        AbsoluteSessionLifetimeFilter(Duration maxAge) {
            this.maxAge = maxAge;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            HttpSession session = request.getSession(false);
            if (session != null && System.currentTimeMillis() - session.getCreationTime() > maxAge.toMillis()) {
                session.invalidate();
            }
            chain.doFilter(request, response);
        }
    }

    /** A browser always names its origin on a cross-site write: if it is not the website, the request is refused. */
    static final class OriginCheckFilter extends OncePerRequestFilter {
        private static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
        private final Set<String> allowed;

        OriginCheckFilter(Set<String> allowedOrigins) {
            this.allowed = new HashSet<>();
            allowedOrigins.forEach(o -> this.allowed.add(o.replaceAll("/+$", "")));
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !WRITES.contains(request.getMethod()) || !request.getRequestURI().startsWith("/api/v1/account/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String origin = request.getHeader("Origin");
            if (origin != null && !allowed.contains(origin.replaceAll("/+$", ""))) {
                json(response, 403, "BAD_ORIGIN", "This request was not sent from the Pathway AI website.");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
