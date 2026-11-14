package com.pathwayai.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The staging gate fails closed and the rate limiter bounds a single client. No server or database needed. */
class ApiProtectionTest {

    private MockHttpServletResponse call(jakarta.servlet.Filter filter, String method, String header, String ip) throws Exception {
        var request = new MockHttpServletRequest(method, "/api/v1/programs");
        request.setRemoteAddr(ip);
        if (header != null) request.addHeader("X-Staging-Key", header);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void gateRejectsMissingAndWrongKeysAcceptsTheRightOneAndLetsPreflightThrough() throws Exception {
        var gate = new ApiProtectionConfig.StagingAccessFilter("s3cret-key");
        assertThat(call(gate, "GET", null, "1.1.1.1").getStatus()).isEqualTo(401);
        assertThat(call(gate, "GET", "wrong", "1.1.1.1").getStatus()).isEqualTo(401);
        assertThat(call(gate, "GET", "s3cret-key ", "1.1.1.1").getStatus()).isEqualTo(401);
        assertThat(call(gate, "GET", "s3cret-key", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(call(gate, "OPTIONS", null, "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(call(gate, "GET", null, "1.1.1.1").getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void anEmptyKeyMeansTheGateIsOffForPublicLaunchOrLocalDevelopment() throws Exception {
        assertThat(call(new ApiProtectionConfig.StagingAccessFilter(""), "GET", null, "1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void productionRefusesToStartWithoutAKeyOrADeliberatePublicLaunch() {
        var config = new ApiProtectionConfig();
        assertThatThrownBy(() -> config.publicLaunchGuard("", false)).hasMessageContaining("STAGING_ACCESS_KEY");
        assertThat(config.publicLaunchGuard("k", false)).isNotNull();
        assertThat(config.publicLaunchGuard("", true)).isNotNull();
    }

    @Test
    void rateLimitAnswers429WithRetryAfterPerClientAndResetsEachMinute() throws Exception {
        var now = new AtomicLong(120_000);
        var limiter = new ApiProtectionConfig.RateLimitFilter(3, now::get);
        for (int i = 0; i < 3; i++) assertThat(call(limiter, "GET", null, "9.9.9.9").getStatus()).isEqualTo(200);
        var limited = call(limiter, "GET", null, "9.9.9.9");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotNull();
        assertThat(call(limiter, "GET", null, "8.8.8.8").getStatus()).as("another client is unaffected").isEqualTo(200);
        now.addAndGet(60_000);
        assertThat(call(limiter, "GET", null, "9.9.9.9").getStatus()).as("a new window").isEqualTo(200);
    }

    @Test
    void zeroDisablesTheRateLimit() throws Exception {
        var limiter = new ApiProtectionConfig.RateLimitFilter(0, () -> 0L);
        for (int i = 0; i < 50; i++) assertThat(call(limiter, "GET", null, "9.9.9.9").getStatus()).isEqualTo(200);
    }
}
