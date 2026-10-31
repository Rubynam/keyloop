package com.keyloop.scheduler.batch.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base for tests that need the full worker on real Postgres + Redis (Spec04 TST-02).
 * The domain schema is owned by server-api, so its migrations are applied here
 * with Flyway, a test-only dependency: the worker itself never migrates (BAT-03).
 */
@SpringBootTest(properties = {
        "spring.flyway.locations=filesystem:../server-api/src/main/resources/db/migration",
        "spring.flyway.default-schema=public",
        "spring.flyway.schemas=public"
})
@Import(TestcontainersConfig.class)
public abstract class IntegrationTest {
}
