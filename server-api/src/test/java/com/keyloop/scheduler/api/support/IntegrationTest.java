package com.keyloop.scheduler.api.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Base for tests that need the full app on real Postgres + Redis; Flyway runs on startup (Spec04 TST-02). */
@SpringBootTest
@Import(TestcontainersConfig.class)
public abstract class IntegrationTest {
}
