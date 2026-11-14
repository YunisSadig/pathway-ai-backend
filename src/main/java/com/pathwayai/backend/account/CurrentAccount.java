package com.pathwayai.backend.account;

import java.util.UUID;
import org.springframework.security.core.Authentication;

/** The only way a controller learns which account is calling: from the authenticated session, never from a request parameter. */
final class CurrentAccount {

    private CurrentAccount() {
    }

    static UUID id(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new AccountException(401, "UNAUTHENTICATED", "Please log in.");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new AccountException(401, "UNAUTHENTICATED", "Please log in.");
        }
    }
}
