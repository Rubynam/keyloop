plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":common-logging"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // The API owns the DB schema: migrations run on startup
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    // implementation, not runtimeOnly: the appointment repository reads PSQLException constraint names (Spec07 §7.2)
    implementation("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // Integration tests against real Postgres 18 + Redis 8 (Spec04 TST-01)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("com.redis:testcontainers-redis")
    // Layering rules of CLAUDE.md §1 as tests (Spec05 §7, ARC-01…08)
    testImplementation(libs.archunit.junit5)
}
