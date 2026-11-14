package com.pathwayai.backend.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
class AccountMailConfig {

    private static final Logger log = LoggerFactory.getLogger(AccountMailConfig.class);

    @Bean
    @ConditionalOnProperty("spring.mail.host")
    MailGateway smtpMailGateway(JavaMailSender sender, AccountProperties properties) {
        return new MailGateway() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void send(String to, String subject, String text) {
                var message = new SimpleMailMessage();
                message.setFrom(properties.mailFrom());
                message.setTo(to);
                message.setSubject(subject);
                message.setText(text);
                sender.send(message);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(MailGateway.class)
    MailGateway unavailableMailGateway() {
        log.info("No SMTP server configured (spring.mail.host): email verification and password reset by email are unavailable");
        return new MailGateway() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public void send(String to, String subject, String text) {
                throw new IllegalStateException("email delivery is not configured");
            }
        };
    }
}
