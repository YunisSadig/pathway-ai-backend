package com.pathwayai.backend.refresh.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathwayai.backend.refresh.support.FakeTransport;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RobotsPolicyTest {

    static final String ROBOTS = "https://www.uni.example/robots.txt";

    RobotsPolicy policy(FakeTransport t, Clock clock) {
        return new RobotsPolicy(t, clock, "PathwayAIBot/0.1 (+https://example.test)", Duration.ofSeconds(5), 100_000);
    }

    RobotsPolicy policy(FakeTransport t) {
        return policy(t, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    boolean allowed(RobotsPolicy p, String path) {
        return p.check(URI.create("https://www.uni.example" + path)).allowed();
    }

    @Test
    void crawlDelayIsReadFromTheGroupThatAppliesToUs() {
        var generic = policy(new FakeTransport().on(ROBOTS, FakeTransport.text("text/plain", "User-agent: *\nCrawl-delay: 30\n")));
        generic.check(URI.create("https://www.uni.example/a"));
        assertThat(generic.crawlDelay("www.uni.example")).isEqualTo(Duration.ofSeconds(30));

        var own = policy(new FakeTransport().on(ROBOTS, FakeTransport.text("text/plain",
                "User-agent: *\nCrawl-delay: 30\n\nUser-agent: pathwayaibot\nCrawl-delay: 5\nDisallow: /x\n")));
        own.check(URI.create("https://www.uni.example/a"));
        assertThat(own.crawlDelay("www.uni.example")).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void noCrawlDelayMeansZeroAndAnUnparsableOneIsIgnored() {
        var none = policy(new FakeTransport().on(ROBOTS, FakeTransport.text("text/plain", "User-agent: *\nDisallow: /x\n")));
        none.check(URI.create("https://www.uni.example/a"));
        assertThat(none.crawlDelay("www.uni.example")).isZero();
        var junk = policy(new FakeTransport().on(ROBOTS, FakeTransport.text("text/plain", "User-agent: *\nCrawl-delay: soon\n")));
        junk.check(URI.create("https://www.uni.example/a"));
        assertThat(junk.crawlDelay("www.uni.example")).isZero();
        assertThat(junk.crawlDelay("never-loaded.example")).isZero();
    }

    @Test
    void aMissingRobotsFileAllowsEverything() {
        assertThat(allowed(policy(new FakeTransport().on(ROBOTS, FakeTransport.status(404))), "/anything")).isTrue();
    }

    @Test
    void disallowedPathsAreBlockedAndOthersAllowed() {
        var t = new FakeTransport().on(ROBOTS, FakeTransport.text("text/plain", "User-agent: *\nDisallow: /private/\nDisallow: /*.pdf$\n"));
        var p = policy(t);
        assertThat(allowed(p, "/admissions")).isTrue();
        assertThat(allowed(p, "/private/page")).isFalse();
        assertThat(allowed(p, "/files/fees.pdf")).isFalse();
        assertThat(allowed(p, "/files/fees.pdf.html")).isTrue();
        assertThat(p.check(URI.create("https://www.uni.example/private/x")).reason()).contains("robots.txt disallows");
    }

    @Test
    void theLongestMatchingRuleWinsAndAllowBeatsDisallowOnATie() {
        var t = new FakeTransport().on(ROBOTS, FakeTransport.text("text/plain",
                "User-agent: *\nDisallow: /study/\nAllow: /study/open/\nDisallow: /tie\nAllow: /tie\n"));
        var p = policy(t);
        assertThat(allowed(p, "/study/closed")).isFalse();
        assertThat(allowed(p, "/study/open/page")).isTrue();
        assertThat(allowed(p, "/tie")).isTrue();
    }

    @Test
    void aGroupNamingOurProductTokenOverridesTheWildcardGroup() {
        var t = new FakeTransport().on(ROBOTS, FakeTransport.text("text/plain",
                "User-agent: *\nDisallow: /\n\nUser-agent: PathwayAIBot\nAllow: /\nDisallow: /admin/\n"));
        var p = policy(t);
        assertThat(allowed(p, "/programmes")).isTrue();
        assertThat(allowed(p, "/admin/x")).isFalse();
    }

    @Test
    void disallowAllForEveryone_blocksUs() {
        var t = new FakeTransport().on(ROBOTS, FakeTransport.text("text/plain", "User-agent: *\nDisallow: /\n"));
        assertThat(allowed(policy(t), "/")).isFalse();
    }

    @Test
    void anEmptyDisallowValueAllowsEverything() {
        var t = new FakeTransport().on(ROBOTS, FakeTransport.text("text/plain", "User-agent: *\nDisallow:\n"));
        assertThat(allowed(policy(t), "/anything")).isTrue();
    }

    @Test
    void anUnreachableRobotsFileMeansAssumeDisallowRatherThanCrawlBlind() {
        assertThat(allowed(policy(new FakeTransport().on(ROBOTS, FakeTransport.status(500))), "/")).isFalse();
        assertThat(allowed(policy(new FakeTransport().on(ROBOTS, new IOException("timeout"))), "/")).isFalse();
        assertThat(allowed(policy(new FakeTransport().on(ROBOTS, FakeTransport.status(429))), "/")).isFalse();
    }

    @Test
    void robotsIsFetchedOncePerHostAndRefetchedAfterADay() {
        var t = new FakeTransport().on(ROBOTS, FakeTransport.text("text/plain", "User-agent: *\nDisallow: /x\n"));
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var p = policy(t, clock);
        allowed(p, "/a");
        allowed(p, "/b");
        assertThat(t.countFor(ROBOTS)).isEqualTo(1);
        clock.advance(Duration.ofHours(25));
        allowed(p, "/c");
        assertThat(t.countFor(ROBOTS)).isEqualTo(2);
    }

    /** A clock a test can move. */
    static class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
