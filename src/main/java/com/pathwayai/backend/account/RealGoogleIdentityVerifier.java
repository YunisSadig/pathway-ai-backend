package com.pathwayai.backend.account;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exchanges the authorization code at Google's token endpoint (server to server, with the client secret and the PKCE verifier) and
 * validates the returned identity token: signature against Google's published keys, issuer, audience (our client id), expiry and the
 * nonce this very login started with. Nothing the browser says about who the user is is ever trusted.
 */
@Component
class RealGoogleIdentityVerifier implements GoogleIdentityVerifier {

    static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    static final String JWKS = "https://www.googleapis.com/oauth2/v3/certs";

    private final GoogleAuthSettings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile JwtDecoder decoder;

    RealGoogleIdentityVerifier(GoogleAuthSettings settings) {
        this.settings = settings;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Identity verify(String code, String codeVerifier, String redirectUri, String nonce) {
        String form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(redirectUri)
                + "&client_id=" + enc(settings.clientId()) + "&client_secret=" + enc(settings.clientSecret())
                + "&code_verifier=" + enc(codeVerifier);
        String idToken;
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new GoogleAuthException("token endpoint answered " + response.statusCode()); // never log the body: it can echo the code
            }
            Map<String, Object> body = JsonMapper.builder().build().readValue(response.body(), Map.class);
            idToken = body.get("id_token") instanceof String t ? t : null;
        } catch (IOException e) {
            throw new GoogleAuthException("token endpoint unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleAuthException("interrupted", e);
        }
        if (idToken == null) {
            throw new GoogleAuthException("no identity token in the answer");
        }
        return fromToken(jwks().decode(idToken), settings.clientId(), nonce);
    }

    /** Claims of an already signature-checked token, after issuer, audience, expiry and nonce validation. Package-visible for tests. */
    static Identity fromToken(Jwt jwt, String clientId, String nonce) {
        OAuth2TokenValidatorResult result = validator(clientId, nonce).validate(jwt);
        if (result.hasErrors()) {
            throw new GoogleAuthException("identity token rejected: " + result.getErrors().iterator().next().getDescription());
        }
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new GoogleAuthException("identity token has no subject");
        }
        Object verified = jwt.getClaims().get("email_verified");
        boolean emailVerified = Boolean.TRUE.equals(verified) || "true".equals(verified);
        return new Identity(subject, jwt.getClaimAsString("email"), emailVerified, jwt.getClaimAsString("name"));
    }

    static OAuth2TokenValidator<Jwt> validator(String clientId, String nonce) {
        OAuth2TokenValidator<Jwt> issuer = token -> {
            String iss = token.getClaimAsString("iss"); // the raw claim: Google sometimes sends "accounts.google.com", which is not a URL
            return "https://accounts.google.com".equals(iss) || "accounts.google.com".equals(iss) ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "wrong issuer", null));
        };
        OAuth2TokenValidator<Jwt> audience = token -> token.getAudience() != null && token.getAudience().contains(clientId)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "wrong audience", null));
        OAuth2TokenValidator<Jwt> nonceCheck = token -> nonce != null && nonce.equals(token.getClaimAsString("nonce"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "nonce mismatch", null));
        return new DelegatingOAuth2TokenValidator<>(List.of(new JwtTimestampValidator(Duration.ofSeconds(30)), issuer, audience, nonceCheck));
    }

    private JwtDecoder jwks() {
        JwtDecoder d = decoder;
        if (d == null) {
            d = NimbusJwtDecoder.withJwkSetUri(JWKS).build();
            decoder = d;
        }
        return d;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
