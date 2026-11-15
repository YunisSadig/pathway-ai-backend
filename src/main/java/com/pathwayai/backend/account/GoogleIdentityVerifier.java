package com.pathwayai.backend.account;

/** Turns an authorization code into a VERIFIED Google identity. The real implementation talks to Google; tests supply their own. */
interface GoogleIdentityVerifier {

    /** What Google vouched for in the signed identity token. {@code sub} is the stable id; the email counts only when {@code emailVerified}. */
    record Identity(String subject, String email, boolean emailVerified, String name) {
    }

    /** @throws GoogleAuthException when the exchange fails or the token does not validate (the reason is for logs, never for the visitor) */
    Identity verify(String code, String codeVerifier, String redirectUri, String nonce);

    class GoogleAuthException extends RuntimeException {
        GoogleAuthException(String message) {
            super(message);
        }

        GoogleAuthException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
