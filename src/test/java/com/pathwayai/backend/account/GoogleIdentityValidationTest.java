package com.pathwayai.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.pathwayai.backend.account.GoogleIdentityVerifier.GoogleAuthException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** What makes a Google identity token acceptable: signature, issuer, audience, expiry, nonce, and an email Google verified. */
class GoogleIdentityValidationTest {

    static final String CLIENT = "client-123";
    static final String NONCE = "nonce-abc";

    static Jwt token(String issuer, String audience, Instant expires, String nonce, Object emailVerified) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-1").issuedAt(expires.minusSeconds(600)).expiresAt(expires)
                .claim("email", "a@example.test").claim("name", "A").claim("email_verified", emailVerified);
        if (issuer != null) {
            b.issuer(issuer);
        }
        if (audience != null) {
            b.audience(List.of(audience));
        }
        if (nonce != null) {
            b.claim("nonce", nonce);
        }
        return b.build();
    }

    static Instant later() {
        return Instant.now().plusSeconds(300);
    }

    @Test
    void aTokenFromGoogleForThisClientAndThisLoginIsAccepted() {
        for (String issuer : List.of("https://accounts.google.com", "accounts.google.com")) {
            var id = RealGoogleIdentityVerifier.fromToken(token(issuer, CLIENT, later(), NONCE, true), CLIENT, NONCE);
            assertThat(id.subject()).isEqualTo("sub-1");
            assertThat(id.emailVerified()).isTrue();
            assertThat(id.email()).isEqualTo("a@example.test");
        }
    }

    @Test
    void aTokenWithTheWrongIssuerAudienceExpiryOrNonceIsRejected() {
        assertThatThrownBy(() -> RealGoogleIdentityVerifier.fromToken(token("https://evil.example", CLIENT, later(), NONCE, true), CLIENT, NONCE))
                .isInstanceOf(GoogleAuthException.class).hasMessageContaining("issuer");
        assertThatThrownBy(() -> RealGoogleIdentityVerifier.fromToken(token("accounts.google.com", "another-client", later(), NONCE, true), CLIENT, NONCE))
                .isInstanceOf(GoogleAuthException.class).hasMessageContaining("audience");
        assertThatThrownBy(() -> RealGoogleIdentityVerifier.fromToken(token("accounts.google.com", CLIENT, Instant.now().minusSeconds(300), NONCE, true), CLIENT, NONCE))
                .isInstanceOf(GoogleAuthException.class);
        assertThatThrownBy(() -> RealGoogleIdentityVerifier.fromToken(token("accounts.google.com", CLIENT, later(), "other-nonce", true), CLIENT, NONCE))
                .isInstanceOf(GoogleAuthException.class).hasMessageContaining("nonce");
        assertThatThrownBy(() -> RealGoogleIdentityVerifier.fromToken(token("accounts.google.com", CLIENT, later(), null, true), CLIENT, NONCE))
                .isInstanceOf(GoogleAuthException.class).hasMessageContaining("nonce");
    }

    @Test
    void anEmailGoogleDidNotVerifyIsNeverTreatedAsVerified() {
        assertThat(RealGoogleIdentityVerifier.fromToken(token("accounts.google.com", CLIENT, later(), NONCE, false), CLIENT, NONCE).emailVerified()).isFalse();
        assertThat(RealGoogleIdentityVerifier.fromToken(token("accounts.google.com", CLIENT, later(), NONCE, "true"), CLIENT, NONCE).emailVerified()).isTrue();
        assertThat(RealGoogleIdentityVerifier.fromToken(token("accounts.google.com", CLIENT, later(), NONCE, null), CLIENT, NONCE).emailVerified()).isFalse();
    }

    @Test
    void aTokenSignedWithAnotherKeyFailsTheSignatureCheck() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair genuine = gen.generateKeyPair();
        KeyPair forger = gen.generateKeyPair();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder().subject("sub-1").issuer("accounts.google.com")
                .audience(CLIENT).expirationTime(Date.from(later())).claim("nonce", NONCE).build());
        jwt.sign(new RSASSASigner(forger.getPrivate()));
        var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) genuine.getPublic()).build();
        assertThatThrownBy(() -> decoder.decode(jwt.serialize())).isInstanceOf(JwtException.class);
        // and the same token signed with the genuine key passes the signature step
        SignedJWT good = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), jwt.getJWTClaimsSet());
        good.sign(new RSASSASigner(genuine.getPrivate()));
        assertThat(RealGoogleIdentityVerifier.fromToken(decoder.decode(good.serialize()), CLIENT, NONCE).subject()).isEqualTo("sub-1");
    }
}
