package com.chronos.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Postgres 16 container for all integration tests.
 *
 * <p>The container is a static singleton started once per JVM and never stopped — Ryuk (the
 * Testcontainers reaper sidecar) removes it when the JVM exits. Tradeoff: tests share one
 * database, so each test class is responsible for cleaning up its own rows; in exchange the
 * suite pays the ~3s container startup once instead of once per class.
 */
public abstract class PostgresTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("chronos")
                    .withUsername("chronos")
                    .withPassword("chronos");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
