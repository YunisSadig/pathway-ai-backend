package com.pathwayai.backend.account;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Normalisation and a deliberately plain syntax check; the only real proof an address works is the verification email. */
final class EmailAddress {

    private static final Pattern SHAPE = Pattern.compile("^[^\\s@]{1,64}@[^\\s@.]+(\\.[^\\s@.]+)+$");

    private EmailAddress() {
    }

    /** @return the trimmed, lower-cased address, or empty when it is not a plausible address */
    static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String e = raw.trim().toLowerCase(Locale.ROOT);
        if (e.length() > 254 || !SHAPE.matcher(e).matches()) {
            return Optional.empty();
        }
        return Optional.of(e);
    }
}
