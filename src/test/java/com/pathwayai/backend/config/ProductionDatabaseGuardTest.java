package com.pathwayai.backend.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ProductionDatabaseGuardTest {

    static final String H = "jdbc:postgresql://ep-x-123.eu-central-1.aws.neon.tech:5432/pathway_ai";

    @Test
    void acceptsVerifyFullAndLocalDatabases() {
        assertThatCode(() -> ProductionDatabaseGuard.check(H + "?sslmode=verify-full")).doesNotThrowAnyException();
        assertThatCode(() -> ProductionDatabaseGuard.check(H + "?user=u&sslmode=verify-full&x=1")).doesNotThrowAnyException();
        assertThatCode(() -> ProductionDatabaseGuard.check("jdbc:postgresql://localhost:5433/pathway_ai")).doesNotThrowAnyException();
    }

    @Test
    void refusesEverythingThatDoesNotVerifyTheServer() {
        for (String bad : new String[] {H, H + "?sslmode=require", H + "?sslmode=verify-ca", H + "?sslmode=prefer", H + "?ssl=true",
                H + "?sslmode=verify-full&sslmode=require", H + "?sslmode=verify-fullx",
                H + "?sslmode=verify-full&sslfactory=org.postgresql.ssl.NonValidatingFactory"}) {
            assertThatThrownBy(() -> ProductionDatabaseGuard.check(bad)).as(bad).isInstanceOf(IllegalStateException.class);
        }
    }
}
