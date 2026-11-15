package com.pathwayai.backend.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Google sign-in is offered only when both credentials are configured (GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET); otherwise it is simply absent. */
@Component
class GoogleAuthSettings {

    private final String clientId;
    private final String clientSecret;

    GoogleAuthSettings(@Value("${app.google.client-id:}") String clientId, @Value("${app.google.client-secret:}") String clientSecret) {
        this.clientId = clientId == null ? "" : clientId.strip();
        this.clientSecret = clientSecret == null ? "" : clientSecret.strip();
    }

    boolean available() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    String clientId() {
        return clientId;
    }

    String clientSecret() {
        return clientSecret;
    }
}
