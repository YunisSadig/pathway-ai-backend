package com.pathwayai.backend.refresh;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the official-source monitoring pipeline ({@code app.refresh.*}). Every default is conservative: slow,
 * identified, bounded.
 */
@ConfigurationProperties(prefix = "app.refresh")
public record RefreshProperties(
        /** Identifies the crawler to site operators. Keep a contact URL in it. */
        @DefaultValue("PathwayAIBot/0.1 (+https://github.com/YunisSadig/pathway-ai-backend; official-source monitoring)")
        String userAgent,
        /** Minimum gap between two requests to the same host. */
        @DefaultValue("3000") long minHostIntervalMs,
        @DefaultValue("20") int timeoutSeconds,
        /** Responses larger than this are rejected, not truncated. */
        @DefaultValue("4000000") int maxBodyBytes,
        /** Retries for transient failures (5xx, timeouts), on top of the first attempt. */
        @DefaultValue("2") int maxRetries,
        @DefaultValue("1000") long backoffMs,
        @DefaultValue("3") int maxRedirects,
        /** A Retry-After longer than this is not waited for in-process; the check is rescheduled instead. */
        @DefaultValue("86400") long maxRetryAfterSeconds,
        /** Programs with a verified deadline inside this window are checked daily. */
        @DefaultValue("60") int deadlineWindowDays,
        @DefaultValue("1") int deadlineIntervalDays,
        @DefaultValue("7") int generalIntervalDays,
        /** Consecutive failures after which a source is shown as FAILING. */
        @DefaultValue("3") int failureThreshold,
        /** Sources checked per run. A scheduled run simply continues with the next batch. */
        @DefaultValue("100") int batchSize,
        /** Extra hosts allowed besides those of registered sources (comma separated in the environment). */
        @DefaultValue List<String> extraApprovedHosts,
        @DefaultValue Scheduler scheduler) {

    public record Scheduler(@DefaultValue("false") boolean enabled, @DefaultValue("0 0 5 * * *") String cron) {
    }
}
