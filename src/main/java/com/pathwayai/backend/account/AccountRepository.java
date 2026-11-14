package com.pathwayai.backend.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for user_account and account_token. Parameters are always bound, never concatenated. */
@Repository
class AccountRepository {

    private static final String COLUMNS =
            "id, email, display_name, role, catalogue_access, email_verified_at, password_hash, created_at";

    private final JdbcClient jdbc;

    AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static AccountRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Timestamp verified = rs.getTimestamp("email_verified_at");
        return new AccountRow(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                rs.getString("role"), rs.getBoolean("catalogue_access"), verified == null ? null : verified.toInstant(),
                rs.getString("password_hash"), rs.getTimestamp("created_at").toInstant());
    }

    Optional<AccountRow> findByEmail(String emailNormalized) {
        return jdbc.sql("select " + COLUMNS + " from user_account where email_normalized = :e")
                .param("e", emailNormalized).query(AccountRepository::map).optional();
    }

    Optional<AccountRow> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from user_account where id = :id").param("id", id)
                .query(AccountRepository::map).optional();
    }

    /** @throws DuplicateKeyException when the normalised email is already registered (the unique constraint decides, so races are safe) */
    UUID insert(String email, String emailNormalized, String passwordHash, String displayName) throws DuplicateKeyException {
        return jdbc.sql("""
                insert into user_account (email, email_normalized, password_hash, display_name)
                values (:email, :norm, :hash, :name) returning id""")
                .param("email", email).param("norm", emailNormalized).param("hash", passwordHash).param("name", displayName)
                .query(UUID.class).single();
    }

    boolean hasCatalogueAccess(UUID id) {
        return jdbc.sql("select catalogue_access from user_account where id = :id").param("id", id)
                .query(Boolean.class).optional().orElse(false);
    }

    void touchLogin(UUID id) {
        jdbc.sql("update user_account set last_login_at = now() where id = :id").param("id", id).update();
    }

    void markEmailVerified(UUID id) {
        jdbc.sql("update user_account set email_verified_at = coalesce(email_verified_at, now()), updated_at = now() where id = :id")
                .param("id", id).update();
    }

    void updatePassword(UUID id, String hash) {
        jdbc.sql("update user_account set password_hash = :h, password_changed_at = now(), updated_at = now() where id = :id")
                .param("h", hash).param("id", id).update();
    }

    void updateDisplayName(UUID id, String displayName) {
        jdbc.sql("update user_account set display_name = :n, updated_at = now() where id = :id")
                .param("n", displayName).param("id", id).update();
    }

    boolean delete(UUID id) {
        return jdbc.sql("delete from user_account where id = :id").param("id", id).update() > 0;
    }

    void insertToken(UUID accountId, String purpose, String tokenHash, Instant expiresAt) {
        jdbc.sql("insert into account_token (account_id, purpose, token_hash, expires_at) values (:a, :p, :h, :x)")
                .param("a", accountId).param("p", purpose).param("h", tokenHash).param("x", Timestamp.from(expiresAt)).update();
    }

    void deleteTokens(UUID accountId, String purpose) {
        jdbc.sql("delete from account_token where account_id = :a and purpose = :p").param("a", accountId).param("p", purpose).update();
    }

    /** Atomically spends a valid, unused, unexpired token and returns its account. */
    Optional<UUID> consumeToken(String purpose, String tokenHash) {
        return jdbc.sql("""
                update account_token set used_at = now()
                where token_hash = :h and purpose = :p and used_at is null and expires_at > now()
                returning account_id""").param("h", tokenHash).param("p", purpose).query(UUID.class).optional();
    }
}
