package com.pathwayai.backend.account;

import java.util.UUID;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** Ends server-side sessions of an account (password reset, password change, "log out everywhere", account deletion). */
@Component
class SessionTerminator {

    private final org.springframework.beans.factory.ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> repository;

    // optional: absent in command-line runs, where there are no sessions to end
    SessionTerminator(org.springframework.beans.factory.ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> repository) {
        this.repository = repository;
    }

    void terminateAll(UUID accountId) {
        terminateAllExcept(accountId, null);
    }

    void terminateAllExcept(UUID accountId, String keepSessionId) {
        FindByIndexNameSessionRepository<? extends Session> sessions = repository.getIfAvailable();
        if (sessions == null) {
            return;
        }
        for (String id : sessions.findByPrincipalName(accountId.toString()).keySet()) {
            if (!id.equals(keepSessionId)) {
                sessions.deleteById(id);
            }
        }
    }
}
