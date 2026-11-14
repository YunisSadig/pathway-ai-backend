package com.pathwayai.backend.account;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The address abuse limits are keyed on. Behind the website's proxy every request arrives from the proxy's servers, so the proxy sends the
 * visitor's address in {@code X-Client-IP}; it is believed only when the request also carried the valid staging key (see
 * {@code ApiProtectionConfig}), otherwise anyone could spoof it. Without that, the socket address is used.
 */
public final class ClientIp {

    public static final String TRUSTED_ATTRIBUTE = "pathway.trustedClientIp";

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        Object trusted = request.getAttribute(TRUSTED_ATTRIBUTE);
        return trusted instanceof String s && !s.isBlank() ? s : request.getRemoteAddr();
    }
}
