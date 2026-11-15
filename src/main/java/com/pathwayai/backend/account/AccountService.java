package com.pathwayai.backend.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login checks, email verification, password reset and account deletion.
 *
 * <p>Design rules that matter for security:
 * <ul>
 *   <li>A login failure is one answer ("invalid email or password") whether or not the email exists, takes about the same time (a dummy
 *       hash is checked for unknown emails) and is throttled per email and per address, also for unknown emails.</li>
 *   <li>With working email delivery, registration and password reset answer identically for known and unknown addresses (the difference
 *       goes by email). Without delivery there is no private channel, so registration must say "already registered": a documented, rate
 *       limited exception that disappears when SMTP is configured.</li>
 *   <li>One-time tokens are 256-bit random values, only their SHA-256 is stored, they expire, and spending one is a single atomic UPDATE.</li>
 * </ul>
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final String VERIFY = "VERIFY_EMAIL";
    private static final String RESET = "RESET_PASSWORD";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accounts;
    private final AuthThrottle throttle;
    private final PasswordEncoder encoder;
    private final MailGateway mail;
    private final AccountProperties props;
    private final SessionTerminator sessions;
    private final boolean cataloguePublic;
    private final String dummyHash;
    private final ExternalIdentityRepository googleIdentities;

    AccountService(AccountRepository accounts, AuthThrottle throttle, PasswordEncoder encoder, MailGateway mail, AccountProperties props,
                   SessionTerminator sessions, ExternalIdentityRepository googleIdentities,
                   @Value("${app.access.catalogue-public:false}") boolean cataloguePublic) {
        this.googleIdentities = googleIdentities;
        this.accounts = accounts;
        this.throttle = throttle;
        this.encoder = encoder;
        this.mail = mail;
        this.props = props;
        this.sessions = sessions;
        this.cataloguePublic = cataloguePublic;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    /** Re-checks the password of an account before a sensitive step; false for an account that has none. */
    boolean passwordMatches(AccountRow account, String raw) {
        return account.passwordHash() != null && raw != null && encoder.matches(raw, account.passwordHash());
    }

    /**
     * Creates the account of a first-time Google user (no password, the address already proved by Google) and its Google identity in
     * ONE transaction, so a concurrent second attempt either waits for the constraint and then finds the first account, or fails whole.
     */
    @Transactional
    UUID createGoogleAccount(String email, String emailNormalized, String displayName, String googleSubject) {
        UUID id = accounts.insert(email, emailNormalized, null, displayName);
        accounts.markEmailVerified(id);
        googleIdentities.link(id, ExternalIdentityRepository.GOOGLE, googleSubject, email);
        return id;
    }

    Optional<AccountRow> findByEmailNormalized(String emailNormalized) {
        return accounts.findByEmail(emailNormalized);
    }

    public boolean emailDeliveryAvailable() {
        return mail.available();
    }

    public boolean registrationOpen() {
        return props.registrationOpen();
    }

    public boolean cataloguePublic() {
        return cataloguePublic;
    }

    public boolean canViewCatalogue(UUID accountId) {
        return cataloguePublic || accounts.hasCatalogueAccess(accountId);
    }

    /** Result of a registration attempt: the new account, or empty when the answer must stay generic. */
    public record Registered(Optional<AccountRow> account) {
    }

    @Transactional
    Registered register(String rawEmail, String password, String displayName, String ip) {
        if (!props.registrationOpen()) {
            throw new AccountException(403, "REGISTRATION_CLOSED", "Sign-up is closed at the moment.");
        }
        if (throttle.hit("register-ip", ip, 3600) > 8) {
            throw new AccountException(429, "TOO_MANY_ATTEMPTS", "Too many sign-up attempts. Please try again later.");
        }
        String email = EmailAddress.normalize(rawEmail).orElseThrow(() -> new AccountException(400, "VALIDATION",
                "Enter a valid email address.", java.util.List.of(new AccountException.Violation("email", "Enter a valid email address."))));
        PasswordPolicy.violation(password, email).ifPresent(msg -> {
            throw new AccountException(400, "WEAK_PASSWORD", msg, java.util.List.of(new AccountException.Violation("password", msg)));
        });
        String name = cleanName(displayName);
        String hash = encoder.encode(password);
        try {
            UUID id = accounts.insert(rawEmail.trim(), email, hash, name);
            AccountRow created = accounts.findById(id).orElseThrow();
            if (mail.available()) {
                sendVerification(created); // ownership of the address is proved separately; it never gates the account
            }
            return new Registered(Optional.of(created));
        } catch (DuplicateKeyException duplicate) {
            // registration always opens a session now, so a duplicate cannot look identical; it is refused plainly
            throw new AccountException(409, "EMAIL_TAKEN", "An account with this email already exists. Log in instead.");
        }
    }

    /** Checks credentials and returns the account; every failure looks the same. */
    AccountRow authenticate(String rawEmail, String password, String ip) {
        String email = EmailAddress.normalize(rawEmail).orElse("invalid:" + (rawEmail == null ? "" : rawEmail.trim().toLowerCase()));
        if (throttle.locked("login-email", email) || throttle.locked("login-ip", ip)) {
            throw new AccountException(429, "TOO_MANY_ATTEMPTS", "Too many attempts. Please wait a few minutes and try again.");
        }
        Optional<AccountRow> found = accounts.findByEmail(email);
        boolean ok = encoder.matches(password, found.map(a -> a.passwordHash() == null ? dummyHash : a.passwordHash()).orElse(dummyHash))
                && found.isPresent() && found.get().passwordHash() != null; // a Google-only account has no password to match
        if (!ok) {
            throttle.failure("login-email", email, 5, 900, 900);
            throttle.failure("login-ip", ip, 40, 900, 900);
            throw new AccountException(401, "INVALID_CREDENTIALS", "Invalid email or password.");
        }
        AccountRow account = found.get();
        // Email verification (proof of address ownership) is deliberately NOT a login condition: it must not block profiles, saving or browsing.
        throttle.clear("login-email", email);
        accounts.touchLogin(account.id());
        return account;
    }

    public Optional<AccountRow> find(UUID id) {
        return accounts.findById(id);
    }

    @Transactional
    void verifyEmail(String token) {
        UUID id = accounts.consumeToken(VERIFY, hashToken(token))
                .orElseThrow(() -> new AccountException(400, "INVALID_TOKEN", "This link is invalid or has expired. Request a new one."));
        accounts.markEmailVerified(id);
    }

    /** Always answers the same way. */
    void resendVerification(String rawEmail, String ip) {
        if (!mail.available()) {
            throw new AccountException(503, "EMAIL_UNAVAILABLE", "Email is not available yet, so verification links cannot be sent.");
        }
        if (throttle.hit("resend-ip", ip, 3600) > 6) {
            return;
        }
        EmailAddress.normalize(rawEmail).filter(e -> throttle.hit("resend-email", e, 3600) <= 3).flatMap(accounts::findByEmail)
                .filter(a -> !a.emailVerified()).ifPresent(this::sendVerification);
    }

    /** Always answers the same way (the caller shows "if the address is registered, we sent a link"). */
    void forgotPassword(String rawEmail, String ip) {
        if (!mail.available()) {
            throw new AccountException(503, "EMAIL_UNAVAILABLE", "Resetting a password by email is not available yet.");
        }
        if (throttle.hit("forgot-ip", ip, 3600) > 6) {
            return;
        }
        EmailAddress.normalize(rawEmail).filter(e -> throttle.hit("forgot-email", e, 3600) <= 3).flatMap(accounts::findByEmail).filter(a -> a.passwordHash() != null) // no reset link for an account that signs in with Google
                .ifPresent(account -> {
                    String token = newToken();
                    accounts.deleteTokens(account.id(), RESET);
                    accounts.insertToken(account.id(), RESET, hashToken(token), Instant.now().plus(props.resetTtl()));
                    trySend(account.email(), "Reset your Pathway AI password",
                            "Use this link within " + props.resetTtl().toMinutes() + " minutes to choose a new password:\n\n"
                                    + props.siteUrl() + "/reset-password?token=" + token
                                    + "\n\nIf you did not ask for this, ignore this message: your password has not changed.");
                });
    }

    @Transactional
    void resetPassword(String token, String newPassword) {
        UUID id = accounts.consumeToken(RESET, hashToken(token))
                .orElseThrow(() -> new AccountException(400, "INVALID_TOKEN", "This link is invalid or has expired. Request a new one."));
        AccountRow account = accounts.findById(id).orElseThrow();
        PasswordPolicy.violation(newPassword, account.email()).ifPresent(msg -> {
            throw new AccountException(400, "WEAK_PASSWORD", msg, java.util.List.of(new AccountException.Violation("newPassword", msg)));
        });
        accounts.updatePassword(id, encoder.encode(newPassword));
        accounts.markEmailVerified(id); // the link proved control of the mailbox
        accounts.deleteTokens(id, RESET);
        sessions.terminateAll(id);
    }

    @Transactional
    void changePassword(UUID id, String current, String newPassword, String keepSessionId) {
        AccountRow account = accounts.findById(id).orElseThrow(() -> new AccountException(401, "UNAUTHENTICATED", "Please log in again."));
        if (account.passwordHash() == null) {
            throw new AccountException(409, "NO_PASSWORD", "This account signs in with Google and has no password to change.");
        }
        if (!encoder.matches(current, account.passwordHash())) {
            throw new AccountException(403, "WRONG_PASSWORD", "Your current password is not correct.");
        }
        PasswordPolicy.violation(newPassword, account.email()).ifPresent(msg -> {
            throw new AccountException(400, "WEAK_PASSWORD", msg, java.util.List.of(new AccountException.Violation("newPassword", msg)));
        });
        accounts.updatePassword(id, encoder.encode(newPassword));
        sessions.terminateAllExcept(id, keepSessionId);
    }

    @Transactional
    void deleteAccount(UUID id, String password) {
        AccountRow account = accounts.findById(id).orElseThrow(() -> new AccountException(401, "UNAUTHENTICATED", "Please log in again."));
        if (account.passwordHash() == null || !encoder.matches(password, account.passwordHash())) {
            throw new AccountException(403, "WRONG_PASSWORD", "Your password is not correct.");
        }
        accounts.delete(id); // profile, qualifications, destinations, saved items and tokens go with it (ON DELETE CASCADE)
        sessions.terminateAll(id);
    }

    void terminateSessions(UUID id, String keepSessionId) {
        sessions.terminateAllExcept(id, keepSessionId);
    }

    @Transactional
    void updateDisplayName(UUID id, String displayName) {
        accounts.updateDisplayName(id, cleanName(displayName));
    }

    private void sendVerification(AccountRow account) {
        String token = newToken();
        accounts.deleteTokens(account.id(), VERIFY);
        accounts.insertToken(account.id(), VERIFY, hashToken(token), Instant.now().plus(props.verificationTtl()));
        trySend(account.email(), "Confirm your Pathway AI email address",
                "Welcome to Pathway AI. Confirm this email address within " + props.verificationTtl().toHours() + " hours:\n\n"
                        + props.siteUrl() + "/verify-email?token=" + token + "\n\nIf you did not create an account, ignore this message.");
    }

    private void trySend(String to, String subject, String body) {
        try {
            mail.send(to, subject, body);
        } catch (RuntimeException e) {
            log.warn("could not send an account email ({})", e.getClass().getSimpleName()); // no address, no body
        }
    }

    private static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String t = name.strip().replaceAll("[\\p{Cntrl}]", "");
        return t.isEmpty() ? null : t;
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
