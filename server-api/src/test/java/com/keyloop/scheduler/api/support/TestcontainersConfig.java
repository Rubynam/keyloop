package com.keyloop.scheduler.api.support;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Postgres + Redis with the same images and init script as docker-compose (Spec04 TST-01). */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                // btree_gist (exclusion constraints) + batch schema, as in compose
                .withCopyFileToContainer(
                        MountableFile.forHostPath("../infra/postgres/init/01-extensions.sql"),
                        "/docker-entrypoint-initdb.d/01-extensions.sql");
    }

    @Bean
    @ServiceConnection(name = "redis")
    RedisContainer redis() {
        return new RedisContainer(DockerImageName.parse("redis:8-alpine"));
    }
}
