plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":common-logging"))
    // JDBC-backed JobRepository (metadata tables in the "batch" schema)
    implementation("org.springframework.boot:spring-boot-starter-batch-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Only to serve actuator over HTTP on 8081 — no business controllers here
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc)
    // Tracing (spine AD-1): Micrometer Observation → OTel SDK → OTLP/HTTP; JDBC spans from datasource-micrometer
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation(libs.datasource.micrometer.spring.boot)
    runtimeOnly("org.postgresql:postgresql")

    // Integration tests against real Postgres 18 + Redis 8 (Spec04 TST-01)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.redis:testcontainers-redis")
    // Layering rules of CLAUDE.md §1 as tests (Spec05 §7, ARC-01…08)
    testImplementation(libs.archunit.junit5)
    // @AutoConfigureTracing (export is off in @SpringBootTest without it) + InMemorySpanExporter;
    // the tracing ITs never call Jaeger
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    // Tests only: apply server-api's migrations to the test database (the app itself never runs Flyway, BAT-03)
    testImplementation("org.springframework.boot:spring-boot-starter-flyway")
    testImplementation("org.flywaydb:flyway-database-postgresql")
}
