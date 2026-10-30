plugins {
    id("org.springframework.boot")
}

dependencies {
    // JDBC-backed JobRepository (metadata tables in the "batch" schema)
    implementation("org.springframework.boot:spring-boot-starter-batch-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Only to serve actuator over HTTP on 8081 — no business controllers here
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc)
    runtimeOnly("org.postgresql:postgresql")
}
