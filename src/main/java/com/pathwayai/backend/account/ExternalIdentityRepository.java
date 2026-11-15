package com.pathwayai.backend.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Links between a Pathway AI account and a sign-in provider's own stable subject id (never the email). */
@Repository
class ExternalIdentityRepository {

    static final String GOOGLE = "GOOGLE";

    private final JdbcClient jdbc;

    ExternalIdentityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<UUID> accountOf(String provider, String subject) {
        return jdbc.sql("select account_id from external_identity where provider = :p and subject = :s")
                .param("p", provider).param("s", subject).query(UUID.class).optional();
    }

    /** @throws DuplicateKeyException when the subject belongs to someone already, or the account already has this provider */
    void link(UUID accountId, String provider, String subject, String email) throws DuplicateKeyException {
        jdbc.sql("insert into external_identity (account_id, provider, subject, email) values (:a, :p, :s, :e)")
                .param("a", accountId).param("p", provider).param("s", subject).param("e", email).update();
    }

    List<String> providersOf(UUID accountId) {
        return jdbc.sql("select provider from external_identity where account_id = :a order by provider")
                .param("a", accountId).query(String.class).list();
    }
}
