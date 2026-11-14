package com.pathwayai.backend.account;

/**
 * Sends account emails (verification, password reset, "you already have an account"). The application only claims email features that
 * work: {@link #available()} is false until an SMTP server is configured ({@code spring.mail.host}), and the API and the website then say
 * so instead of pretending a message was sent.
 */
public interface MailGateway {

    boolean available();

    /** Sends a plain-text message. Never log the body: it contains a one-time link. */
    void send(String to, String subject, String text);
}
