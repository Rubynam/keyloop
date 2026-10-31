// Shared by server-api and scheduler-batch: Log4j2 config + method logging aspect
plugins {
    `java-library`
}

dependencies {
    // Exposed to the apps: Log4j2 becomes their logging backend behind SLF4J
    api("org.springframework.boot:spring-boot-starter-log4j2")
    implementation("org.springframework.boot:spring-boot-starter-aspectj")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
}
