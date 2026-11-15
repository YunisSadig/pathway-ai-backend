package com.pathwayai.backend.account;

import com.pathwayai.backend.account.GoogleIdentityVerifier.GoogleAuthException;
import com.pathwayai.backend.account.GoogleIdentityVerifier.Identity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Continue with Google": authorization code flow with PKCE (S256), state and nonce, kept in the server-side session of the browser
 * that started it and consumed on first use (a replayed callback finds nothing). Google only proves WHO the person is (the stable
 * subject id, and an email Google has verified); the account and the session are ours. Accounts are never merged by email: a Google
 * login whose address already belongs to a password account is refused with guidance, and linking needs the signed-in owner to
 * re-enter the account password. Every outcome is a redirect to a page of the site carrying only a short error code, never a token.
 */
@RestController
@RequestMapping("/api/v1/account/oauth/google")
class GoogleAuthController {

    private static final Logger log = LoggerFactory.getLogger(GoogleAuthController.class);
    private static final String PENDING = "google.oauth";
    private static final String LINK_INTENT = "google.link";
    private static final long PENDING_SECONDS = 600;
    private static final long LINK_SECONDS = 300;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GoogleAuthSettings settings;
    private final GoogleIdentityVerifier verifier;
    private final ExternalIdentityRepository identities;
    private final AccountService accounts;
    private final AccountProperties props;
    private final SessionStarter sessions;

    GoogleAuthController(GoogleAuthSettings settings, GoogleIdentityVerifier verifier, ExternalIdentityRepository identities,
                         AccountService accounts, AccountProperties props, SessionStarter sessions) {
        this.settings = settings;
        this.verifier = verifier;
        this.identities = identities;
        this.accounts = accounts;
        this.props = props;
        this.sessions = sessions;
    }

    record PasswordBody(String password) {
    }

    /** Step 0 of linking: the signed-in owner proves they know the password; only then may the Google flow attach an identity. */
    @PostMapping("/link")
    ResponseEntity<Map<String, String>> allowLinking(@RequestBody PasswordBody body, Authentication authentication, HttpServletRequest request) {
        UUID id = CurrentAccount.id(authentication);
        AccountRow account = accounts.find(id).orElseThrow(() -> new AccountException(401, "UNAUTHENTICATED", "Please log in again."));
        if (!settings.available()) {
            throw new AccountException(503, "GOOGLE_UNAVAILABLE", "Google sign-in is not available.");
        }
        if (!accounts.passwordMatches(account, body == null ? null : body.password())) {
            throw new AccountException(403, "WRONG_PASSWORD", "Your password is not correct.");
        }
        request.getSession(true).setAttribute(LINK_INTENT, id + ":" + Instant.now().getEpochSecond());
        return ResponseEntity.ok(Map.of("url", "/api/v1/account/oauth/google/start?mode=link"));
    }

    @GetMapping("/start")
    void start(@RequestParam(required = false) String next, @RequestParam(required = false) String mode, Authentication authentication,
               HttpServletRequest request, HttpServletResponse response) {
        boolean link = "link".equals(mode);
        if (!settings.available()) {
            redirect(response, "/login?error=google_unavailable");
            return;
        }
        UUID linkAccount = null;
        if (link) {
            linkAccount = consumeLinkIntent(request.getSession(false), authentication);
            if (linkAccount == null) {
                redirect(response, "/settings?section=security&google=expired");
                return;
            }
        }
        String state = token(), nonce = token(), verifierCode = token();
        Map<String, String> pending = new HashMap<>();
        pending.put("state", state);
        pending.put("nonce", nonce);
        pending.put("verifier", verifierCode);
        pending.put("next", safeNext(next));
        pending.put("created", Long.toString(Instant.now().getEpochSecond()));
        if (linkAccount != null) {
            pending.put("link", linkAccount.toString());
        }
        request.getSession(true).setAttribute(PENDING, pending);
        String url = "https://accounts.google.com/o/oauth2/v2/auth?response_type=code"
                + "&client_id=" + enc(settings.clientId())
                + "&redirect_uri=" + enc(redirectUri())
                + "&scope=" + enc("openid email profile")
                + "&state=" + enc(state) + "&nonce=" + enc(nonce)
                + "&code_challenge=" + enc(challenge(verifierCode)) + "&code_challenge_method=S256"
                + "&prompt=select_account";
        noStore(response);
        response.setStatus(302);
        response.setHeader("Location", url);
    }

    @GetMapping("/callback")
    void callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                  @RequestParam(required = false) String error, Authentication authentication,
                  HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        @SuppressWarnings("unchecked")
        Map<String, String> pending = session == null ? null : (Map<String, String>) session.getAttribute(PENDING);
        if (session != null) {
            session.removeAttribute(PENDING); // single use: a replayed callback finds nothing
        }
        if (pending == null || state == null || !constantTimeEquals(pending.get("state"), state)
                || Instant.now().getEpochSecond() - Long.parseLong(pending.get("created")) > PENDING_SECONDS) {
            redirect(response, "/login?error=google_state");
            return;
        }
        boolean link = pending.containsKey("link");
        String failure = link ? "/settings?section=security&google=" : "/login?error=google_";
        if (error != null || code == null || code.isBlank()) {
            redirect(response, failure + "cancelled");
            return;
        }
        Identity identity;
        try {
            identity = verifier.verify(code, pending.get("verifier"), redirectUri(), pending.get("nonce"));
        } catch (GoogleAuthException e) {
            log.warn("Google sign-in refused: {}", e.getMessage()); // the reason, never the code or a token
            redirect(response, failure + "failed");
            return;
        }
        if (identity.subject() == null || identity.subject().isBlank()) {
            redirect(response, failure + "failed");
            return;
        }
        if (link) {
            finishLink(UUID.fromString(pending.get("link")), identity, authentication, response);
            return;
        }
        signInOrRegister(identity, pending.get("next"), request, response);
    }

    private void finishLink(UUID owner, Identity identity, Authentication authentication, HttpServletResponse response) {
        UUID current = currentId(authentication);
        if (current == null || !current.equals(owner)) {
            redirect(response, "/settings?section=security&google=expired");
            return;
        }
        try {
            identities.link(owner, ExternalIdentityRepository.GOOGLE, identity.subject(), identity.emailVerified() ? identity.email() : null);
        } catch (DuplicateKeyException e) {
            redirect(response, "/settings?section=security&google=taken"); // already tied to another account, or this account has one
            return;
        }
        redirect(response, "/settings?section=security&google=linked");
    }

    private void signInOrRegister(Identity identity, String next, HttpServletRequest request, HttpServletResponse response) {
        Optional<UUID> known = identities.accountOf(ExternalIdentityRepository.GOOGLE, identity.subject());
        if (known.isPresent()) {
            open(known.get(), next, request, response, false);
            return;
        }
        Optional<String> normalized = identity.emailVerified() && identity.email() != null
                ? EmailAddress.normalize(identity.email()) : Optional.empty();
        if (normalized.isEmpty()) {
            redirect(response, "/login?error=google_unverified_email"); // an unverified address proves nothing, so no account is made from it
            return;
        }
        if (accounts.findByEmailNormalized(normalized.get()).isPresent()) {
            redirect(response, "/login?error=google_conflict"); // never merged by email: the owner links Google from Settings
            return;
        }
        try {
            String name = identity.name() == null || identity.name().isBlank() ? null
                    : identity.name().strip().substring(0, Math.min(80, identity.name().strip().length()));
            UUID id = accounts.createGoogleAccount(identity.email().strip(), normalized.get(), name, identity.subject());
            open(id, next, request, response, true);
        } catch (DuplicateKeyException race) {
            // two requests at once: the constraints decided, so look again instead of creating a second account
            Optional<UUID> winner = identities.accountOf(ExternalIdentityRepository.GOOGLE, identity.subject());
            if (winner.isPresent()) {
                open(winner.get(), next, request, response, false);
            } else {
                redirect(response, "/login?error=google_conflict");
            }
        }
    }

    private void open(UUID accountId, String next, HttpServletRequest request, HttpServletResponse response, boolean created) {
        AccountRow account = accounts.find(accountId).orElse(null);
        if (account == null) {
            redirect(response, "/login?error=google_failed");
            return;
        }
        sessions.start(account, request, response);
        redirect(response, created ? "/dashboard?welcome=1" : next);
    }

    private UUID consumeLinkIntent(HttpSession session, Authentication authentication) {
        if (session == null) {
            return null;
        }
        Object raw = session.getAttribute(LINK_INTENT);
        session.removeAttribute(LINK_INTENT);
        UUID current = currentId(authentication);
        if (!(raw instanceof String s) || current == null) {
            return null;
        }
        String[] parts = s.split(":");
        if (parts.length != 2 || !parts[0].equals(current.toString())
                || Instant.now().getEpochSecond() - Long.parseLong(parts[1]) > LINK_SECONDS) {
            return null;
        }
        return current;
    }

    private static UUID currentId(Authentication authentication) {
        try {
            return authentication == null ? null : UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null; // the anonymous placeholder is not an account
        }
    }

    private String redirectUri() {
        return props.siteUrl() + "/api/v1/account/oauth/google/callback";
    }

    private void redirect(HttpServletResponse response, String path) {
        noStore(response);
        response.setStatus(302);
        response.setHeader("Location", props.siteUrl() + path);
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    /** Same-site paths only, as on the login page. */
    static String safeNext(String next) {
        if (next == null || !next.startsWith("/") || next.startsWith("//") || next.contains("\\")
                || next.chars().anyMatch(c -> c < 0x20 || c == 0x7f) || next.length() > 300) {
            return "/dashboard";
        }
        return next;
    }

    private static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifierCode) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifierCode.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
