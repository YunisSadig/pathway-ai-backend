package com.pathwayai.backend.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Brute-force and abuse limits stored in the database, so they survive restarts and apply to addresses that have no account (which keeps
 * a login answer identical for registered and unregistered emails). Keys are SHA-256 hashes of "kind:value": the table never holds an
 * email address or an IP address.
 */
@Component
class AuthThrottle {

    private final JdbcClient jdbc;

    AuthThrottle(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static String key(String kind, String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((kind + ":" + value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Counts one event in a fixed window and returns the count inside the current window. */
    int hit(String kind, String value, long windowSeconds) {
        if (ThreadLocalRandom.current().nextInt(100) == 0) {
            cleanup();
        }
        return jdbc.sql("""
                insert into auth_throttle (key_hash, hits, window_started_at) values (:k, 1, now())
                on conflict (key_hash) do update set
                  hits = case when auth_throttle.window_started_at < now() - make_interval(secs => :w) then 1 else auth_throttle.hits + 1 end,
                  window_started_at = case when auth_throttle.window_started_at < now() - make_interval(secs => :w)
                                           then now() else auth_throttle.window_started_at end
                returning hits""").param("k", key(kind, value)).param("w", (double) windowSeconds).query(Integer.class).single();
    }

    /** True while the key is locked out. */
    boolean locked(String kind, String value) {
        return jdbc.sql("select coalesce(locked_until > now(), false) from auth_throttle where key_hash = :k")
                .param("k", key(kind, value)).query(Boolean.class).optional().orElse(false);
    }

    /** Records a failed attempt; at {@code max} failures inside the window the key is locked for {@code lockSeconds}. */
    void failure(String kind, String value, int max, long windowSeconds, long lockSeconds) {
        int hits = hit(kind, value, windowSeconds);
        if (hits >= max) {
            jdbc.sql("update auth_throttle set locked_until = now() + make_interval(secs => :l) where key_hash = :k")
                    .param("l", (double) lockSeconds).param("k", key(kind, value)).update();
        }
    }

    void clear(String kind, String value) {
        jdbc.sql("delete from auth_throttle where key_hash = :k").param("k", key(kind, value)).update();
    }

    private void cleanup() {
        jdbc.sql("delete from auth_throttle where window_started_at < now() - interval '1 day' and (locked_until is null or locked_until < now())")
                .update();
    }
}
