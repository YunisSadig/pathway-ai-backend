package com.pathwayai.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A PostgreSQL container of its own, for the one test class that runs every startup importer against a fresh database
 * and therefore commits real rows. Sharing {@link TestcontainersConfiguration}'s container would leave those rows
 * behind and break every other integration test that expects an empty database. The container is stopped when its
 * Spring context closes (and by Ryuk when the JVM exits).
 */
@TestConfiguration(proxyBeanMethods = false)
public class IsolatedPostgresConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer isolatedPostgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
    }
}
