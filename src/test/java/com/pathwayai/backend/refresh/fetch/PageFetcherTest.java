package com.pathwayai.backend.refresh.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.refresh.RefreshProperties;
import com.pathwayai.backend.refresh.fetch.PageFetcher.Kind;
import com.pathwayai.backend.refresh.fetch.RobotsPolicyTest.MutableClock;
import com.pathwayai.backend.refresh.support.FakeTransport;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The fetcher against a scripted transport: conditional GET, retries, rate limits, redirects, size caps. */
class PageFetcherTest {

    static final String PAGE = "https://www.uni.example/admissions";
    static final String UA = "PathwayAIBot/0.1 (+https://example.test)";

    FakeTransport transport;
    MutableClock clock;
    List<Duration> sleeps;
    PageFetcher fetcher;

    static RefreshProperties props(long minHostIntervalMs, int maxRetries) {
        return new RefreshProperties(UA, minHostIntervalMs, 5, 1000, maxRetries, 100, 3, 86_400, 60, 1, 7, 3, 100,
                List.of(), new RefreshProperties.Scheduler(false, "0 0 5 * * *"));
    }

    PageFetcher build(RefreshProperties p) {
        var guard = new UrlGuard(h -> List.of(UrlGuardTest.PUBLIC), h -> h.endsWith("uni.example"));
        var robots = new RobotsPolicy(transport, clock, UA, Duration.ofSeconds(5), 100_000);
        return new PageFetcher(transport, guard, robots, p, clock, sleeps::add);
    }

    @BeforeEach
    void setUp() {
        transport = new FakeTransport();
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        sleeps = new ArrayList<>();
        fetcher = build(props(0, 2));
    }

    @Test
    void fetchesAPageAndReportsItsValidators() {
        transport.on(PAGE, FakeTransport.html("<main>hello</main>", Map.of("etag", "\"v1\"", "last-modified", "Mon, 01 Jan 2026 00:00:00 GMT")));
        var r = fetcher.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.OK);
        assertThat(r.body()).contains("hello");
        assertThat(r.etag()).isEqualTo("\"v1\"");
        assertThat(r.lastModified()).contains("2026");
        // identified, and asks for HTML rather than everything
        assertThat(transport.requests.get(transport.requests.size() - 1).headers()).containsEntry("User-Agent", UA);
    }

    @Test
    void sendsAConditionalRequestAndTreats304AsNotModified() {
        transport.on(PAGE, FakeTransport.status(304));
        var r = fetcher.fetch(PAGE, "\"v1\"", "Mon, 01 Jan 2026 00:00:00 GMT");
        assertThat(r.kind()).isEqualTo(Kind.NOT_MODIFIED);
        var sent = transport.requests.get(transport.requests.size() - 1).headers();
        assertThat(sent).containsEntry("If-None-Match", "\"v1\"").containsEntry("If-Modified-Since", "Mon, 01 Jan 2026 00:00:00 GMT");
    }

    @Test
    void retriesATimeoutWithBackoffThenSucceeds() {
        transport.on(PAGE, new IOException("timed out"), new IOException("timed out"), FakeTransport.html("<main>ok</main>"));
        var r = fetcher.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.OK);
        assertThat(transport.countFor(PAGE)).isEqualTo(3);
        assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200)); // exponential back-off
    }

    @Test
    void givesUpAfterBoundedRetriesAndReportsAFailureNotAVerdict() {
        transport.on(PAGE, FakeTransport.status(503));
        var r = fetcher.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.FAILED);
        assertThat(transport.countFor(PAGE)).isEqualTo(3); // first attempt + 2 retries, then stop
        assertThat(r.error()).contains("3 attempts");
    }

    @Test
    void aMissingPageIsAFailureNotProofThatTheProgrammeWasDiscontinued() {
        transport.on(PAGE, FakeTransport.status(404));
        var r = fetcher.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.FAILED);
        assertThat(r.error()).isEqualTo("HTTP 404");
        assertThat(transport.countFor(PAGE)).isEqualTo(1); // a 404 is not retried
    }

    @Test
    void http429HonoursRetryAfterWithoutHammering() {
        transport.on(PAGE, FakeTransport.status(429, "Retry-After", "7200"));
        var r = fetcher.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.RATE_LIMITED);
        assertThat(r.retryAfter()).isEqualTo(Duration.ofHours(2));
        assertThat(transport.countFor(PAGE)).isEqualTo(1);
    }

    @Test
    void aVeryLongRetryAfterIsCapped() {
        transport.on(PAGE, FakeTransport.status(429, "Retry-After", "99999999"));
        assertThat(fetcher.fetch(PAGE, null, null).retryAfter()).isEqualTo(Duration.ofSeconds(86_400));
    }

    @Test
    void retryAfterAsAnHttpDateIsUnderstood() {
        transport.on(PAGE, FakeTransport.status(429, "Retry-After", "Thu, 01 Jan 2026 01:00:00 GMT"));
        assertThat(fetcher.fetch(PAGE, null, null).retryAfter()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void accessDeniedIsReportedAsBlockedAndNeverBypassed() {
        transport.on(PAGE, FakeTransport.status(403));
        var r = fetcher.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.BLOCKED);
        assertThat(r.error()).contains("not bypassed");
        assertThat(transport.countFor(PAGE)).isEqualTo(1);
    }

    @Test
    void aBotProtectionChallengePageIsBlockedNotParsedAsContent() {
        transport.on(PAGE, FakeTransport.html("<html><head><title>Just a moment...</title></head><body>Checking your browser</body></html>"));
        var r = fetcher.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.BLOCKED);
        assertThat(r.error()).contains("challenge");
    }

    @Test
    void robotsTxtDisallowStopsTheRequestBeforeItIsMade() {
        transport.on("https://www.uni.example/robots.txt", FakeTransport.text("text/plain", "User-agent: *\nDisallow: /admissions\n"));
        var r = fetcher.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.BLOCKED);
        assertThat(transport.countFor(PAGE)).isZero();
    }

    @Test
    void followsARedirectWithinApprovedHostsButNotToAnOtherSite() {
        transport.on(PAGE, FakeTransport.redirect("https://studies.uni.example/admissions"));
        transport.on("https://studies.uni.example/admissions", FakeTransport.html("<main>moved</main>"));
        var ok = fetcher.fetch(PAGE, null, null);
        assertThat(ok.kind()).isEqualTo(Kind.OK);
        assertThat(ok.finalUrl()).isEqualTo("https://studies.uni.example/admissions");

        transport.reset(PAGE, FakeTransport.redirect("https://evil.example/steal"));
        var blocked = fetcher.fetch(PAGE, null, null);
        assertThat(blocked.kind()).isEqualTo(Kind.BLOCKED);
        assertThat(blocked.error()).contains("redirect").contains("not an approved");
        assertThat(transport.countFor("https://evil.example/steal")).isZero();
    }

    @Test
    void aRedirectToAnInternalAddressIsBlocked() {
        var guard = new UrlGuard(h -> h.startsWith("internal")
                ? List.of(address(10, 0, 0, 7)) : List.of(UrlGuardTest.PUBLIC), h -> true);
        var f = new PageFetcher(transport, guard, new RobotsPolicy(transport, clock, UA, Duration.ofSeconds(5), 1000),
                props(0, 0), clock, sleeps::add);
        transport.on(PAGE, FakeTransport.redirect("https://internal.uni.example/admin"));
        var r = f.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.BLOCKED);
        assertThat(r.error()).contains("private or internal");
    }

    @Test
    void anEndlessRedirectLoopStops() {
        transport.on(PAGE, FakeTransport.redirect(PAGE));
        var r = fetcher.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.FAILED);
        assertThat(r.error()).isEqualTo("too many redirects");
    }

    @Test
    void anOversizedResponseIsRejectedNotTruncated() {
        transport.on(PAGE, FakeTransport.html("x".repeat(5000)));
        var r = fetcher.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.FAILED);
        assertThat(r.error()).contains("larger than");
    }

    @Test
    void aNonTextContentTypeIsRefused() {
        transport.on(PAGE, FakeTransport.text("application/zip", "PK"));
        var r = fetcher.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.FAILED);
        assertThat(r.error()).contains("unsupported content type");
    }

    @Test
    void requestsToTheSameHostAreSpacedOut() {
        fetcher = build(props(2000, 0));
        transport.on(PAGE, FakeTransport.html("<main>a</main>"));
        transport.on("https://www.uni.example/other", FakeTransport.html("<main>b</main>"));
        fetcher.fetch(PAGE, null, null);
        sleeps.clear();
        fetcher.fetch("https://www.uni.example/other", null, null);
        assertThat(sleeps).isNotEmpty().allSatisfy(d -> assertThat(d).isLessThanOrEqualTo(Duration.ofMillis(2000)));
    }

    @Test
    void aPublishedCrawlDelayIsHonouredEvenWhenTheConfiguredSpacingIsShorter() {
        // seen live: opintopolku.fi publishes "crawl-delay: 30"
        transport.on("https://www.uni.example/robots.txt", FakeTransport.text("text/plain", "User-agent: *\nDisallow: /private/\ncrawl-delay: 30\n"));
        fetcher = build(props(1000, 0));
        transport.on(PAGE, FakeTransport.html("<main>a</main>"));
        transport.on("https://www.uni.example/other", FakeTransport.html("<main>b</main>"));
        fetcher.fetch(PAGE, null, null);
        sleeps.clear();
        fetcher.fetch("https://www.uni.example/other", null, null);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(30));
    }

    @Test
    void aCrawlDelayLongerThanWeWillWaitIsNeverSilentlyShortenedTheHostIsNotFetched() {
        transport.on("https://www.uni.example/robots.txt", FakeTransport.text("text/plain", "User-agent: *\nCrawl-delay: 86400\n"));
        fetcher = build(props(1000, 0));
        transport.on(PAGE, FakeTransport.html("<main>a</main>"));
        var r = fetcher.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.FAILED);
        assertThat(r.error()).contains("Crawl-delay of 86400 s").contains("never sped up");
        assertThat(transport.countFor(PAGE)).as("no request was made").isZero();
    }

    @Test
    void aCrawlDelayOfFiveMinutesOrLessIsWaitedForExactly() {
        transport.on("https://www.uni.example/robots.txt", FakeTransport.text("text/plain", "User-agent: *\nCrawl-delay: 300\n"));
        fetcher = build(props(1000, 0));
        transport.on(PAGE, FakeTransport.html("<main>a</main>"));
        transport.on("https://www.uni.example/other", FakeTransport.html("<main>b</main>"));
        fetcher.fetch(PAGE, null, null);
        sleeps.clear();
        fetcher.fetch("https://www.uni.example/other", null, null);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(300));
    }

    @Test
    void anUnreachableRobotsTxtIsATemporaryFailureNotAPermanentBlock() {
        // seen against live sites: a transient robots.txt 5xx must be retried later, never mark the source unsupported
        transport.on("https://www.uni.example/robots.txt", FakeTransport.status(503));
        var r = fetcher.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.FAILED);
        assertThat(r.error()).contains("robots.txt unavailable");
        assertThat(transport.countFor(PAGE)).isZero();
    }

    @Test
    void jsonApiResponsesAreAccepted() {
        // the EU HEI registry answers with application/vnd.api+json
        transport.on(PAGE, FakeTransport.text("application/vnd.api+json", "{\"data\":[]}"));
        var r = fetcher.fetch(PAGE, null, null);
        assertThat(r.kind()).isEqualTo(Kind.OK);
        assertThat(r.contentType()).isEqualTo("application/vnd.api+json");
    }

    @Test
    void anUnsafeStartingUrlIsBlockedBeforeAnyRequest() {
        var r = fetcher.fetch("http://www.uni.example/plain", null, null);
        assertThat(r.kind()).isEqualTo(Kind.BLOCKED);
        assertThat(transport.requests).isEmpty();
    }

    static InetAddress address(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
