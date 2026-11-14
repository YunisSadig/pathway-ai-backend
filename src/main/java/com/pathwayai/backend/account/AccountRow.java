package com.pathwayai.backend.account;

import java.time.Instant;
import java.util.UUID;

/** One user_account row. Internal: never serialised to a client (it carries the password hash). */
record AccountRow(UUID id, String email, String displayName, String role, boolean catalogueAccess, Instant emailVerifiedAt,
                  String passwordHash, Instant createdAt) {

    boolean emailVerified() {
        return emailVerifiedAt != null;
    }
}
