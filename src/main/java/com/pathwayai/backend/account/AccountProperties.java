package com.pathwayai.backend.account;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the student account module ({@code app.account.*}).
 *
 * @param registrationOpen   false closes sign-up (existing accounts keep working)
 * @param siteUrl            public origin of the website, used in the links of verification and reset emails
 * @param bcryptCost         bcrypt work factor (10 is the OWASP minimum; a free 0.1 vCPU instance needs about half a second at 10)
 * @param verificationTtl    lifetime of an email verification link
 * @param resetTtl           lifetime of a password reset link
 * @param maxSavedItems      saved programs and saved universities allowed per account (each)
 * @param absoluteSessionAge a session is ended this long after login however active it is
 * @param mailFrom           sender of account emails (used only when SMTP is configured)
 */
@ConfigurationProperties(prefix = "app.account")
public record AccountProperties(
        @DefaultValue("true") boolean registrationOpen,
        @DefaultValue("http://localhost:3000") String siteUrl,
        @DefaultValue("10") int bcryptCost,
        @DefaultValue("24h") Duration verificationTtl,
        @DefaultValue("1h") Duration resetTtl,
        @DefaultValue("200") int maxSavedItems,
        @DefaultValue("30d") Duration absoluteSessionAge,
        @DefaultValue("no-reply@pathway-ai.invalid") String mailFrom) {
}
