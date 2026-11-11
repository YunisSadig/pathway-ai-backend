package com.pathwayai.backend.refresh.support;

import com.pathwayai.backend.refresh.extract.ExtractedFact;
import com.pathwayai.backend.refresh.extract.ExtractionAdapter;
import com.pathwayai.backend.refresh.extract.PageCanonicalizer.CanonicalPage;
import com.pathwayai.backend.refresh.fetch.HttpTransport;
import com.pathwayai.backend.refresh.fetch.PageFetcher;
import com.pathwayai.backend.refresh.fetch.UrlGuard;
import java.net.InetAddress;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Swaps the crawler's network seams for fakes: a scripted transport, a resolver that maps every host to a public
 * address (except hosts named {@code internal.*}, which resolve to a private one), and a sleeper that never sleeps.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RefreshTestConfig {

    @Bean
    @Primary
    FakeTransport fakeTransport() {
        return new FakeTransport();
    }

    @Bean
    @Primary
    UrlGuard.HostResolver testResolver() {
        return host -> host.startsWith("internal.")
                ? List.of(InetAddress.getByAddress(new byte[]{10, 0, 0, 5}))
                : List.of(InetAddress.getByAddress(new byte[]{(byte) 93, (byte) 184, (byte) 216, 34}));
    }

    @Bean
    @Primary
    PageFetcher.Sleeper noSleep() {
        return d -> { };
    }

    /** An adapter that always fails, to prove a processing error rolls back and never publishes anything. */
    @Bean
    ExtractionAdapter explodingAdapter() {
        return new ExtractionAdapter() {
            @Override public String name() { return "boom"; }
            @Override public String version() { return "1"; }
            @Override public List<ExtractedFact> extract(CanonicalPage page) {
                throw new IllegalStateException("adapter bug");
            }
        };
    }
}
