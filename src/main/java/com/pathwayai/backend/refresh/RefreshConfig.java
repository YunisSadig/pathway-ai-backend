package com.pathwayai.backend.refresh;

import com.pathwayai.backend.refresh.fetch.ApprovedHosts;
import com.pathwayai.backend.refresh.fetch.HttpTransport;
import com.pathwayai.backend.refresh.fetch.JdkHttpTransport;
import com.pathwayai.backend.refresh.fetch.PageFetcher;
import com.pathwayai.backend.refresh.fetch.RobotsPolicy;
import com.pathwayai.backend.refresh.fetch.UrlGuard;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wiring of the monitoring pipeline. Transport, resolver and sleeper are replaceable seams (tests supply fakes). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RefreshProperties.class)
@EnableScheduling
public class RefreshConfig {

    @Bean
    @ConditionalOnMissingBean
    HttpTransport httpTransport() {
        return new JdkHttpTransport();
    }

    @Bean
    @ConditionalOnMissingBean
    UrlGuard.HostResolver hostResolver() {
        return UrlGuard.SYSTEM;
    }

    @Bean
    @ConditionalOnMissingBean
    PageFetcher.Sleeper sleeper() {
        return d -> Thread.sleep(d);
    }

    @Bean
    UrlGuard urlGuard(UrlGuard.HostResolver resolver, ApprovedHosts approved) {
        return new UrlGuard(resolver, approved::isApproved);
    }

    @Bean
    RobotsPolicy robotsPolicy(HttpTransport transport, Clock clock, RefreshProperties props) {
        return new RobotsPolicy(transport, clock, props.userAgent(), Duration.ofSeconds(props.timeoutSeconds()),
                props.maxBodyBytes());
    }

    @Bean
    PageFetcher pageFetcher(HttpTransport transport, UrlGuard guard, RobotsPolicy robots, RefreshProperties props,
                            Clock clock, PageFetcher.Sleeper sleeper) {
        return new PageFetcher(transport, guard, robots, props, clock, sleeper);
    }
}
