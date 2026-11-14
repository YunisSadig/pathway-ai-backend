package com.pathwayai.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * In production the API must reach a remote database with a verified TLS connection: the URL has to carry {@code sslmode=verify-full}
 * exactly once. {@code sslmode=require} would encrypt without authenticating the server. A database on this machine is exempt.
 */
@Configuration
@Profile("prod")
public class ProductionDatabaseGuard {

    @Bean
    DatabaseTlsCheck databaseTlsCheck(@Value("${spring.datasource.url}") String url) {
        check(url);
        return new DatabaseTlsCheck();
    }

    record DatabaseTlsCheck() {}

    static void check(String url) {
        String rest = url.startsWith("jdbc:postgresql://") ? url.substring("jdbc:postgresql://".length()) : url;
        String host = rest.split("[/?]", 2)[0].split(":", 2)[0].toLowerCase();
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")) {
            return;
        }
        int first = url.indexOf("sslmode=");
        boolean once = first >= 0 && url.indexOf("sslmode=", first + 1) < 0;
        boolean verifyFull = once && (url.contains("?sslmode=verify-full") || url.contains("&sslmode=verify-full"));
        if (!verifyFull || url.matches(".*sslmode=verify-full[^&]+.*")) {
            throw new IllegalStateException("the production database URL must contain sslmode=verify-full exactly once "
                    + "(a remote database is never reached without verifying its certificate and host name)");
        }
        if (url.contains("sslfactory=") && !url.contains("sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory")) {
            throw new IllegalStateException("the production database URL must not select another sslfactory");
        }
    }
}
