// Root project: shared conventions only, it is not an application and builds no jar.
plugins {
    alias(libs.plugins.spring.boot) apply false
}

val springBootBom = libs.spring.boot.bom

subprojects {
    apply(plugin = "java")

    group = "com.keyloop.scheduler"
    version = "0.1.0-SNAPSHOT"

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }

    // Log4j2 replaces Logback everywhere (common-logging brings spring-boot-starter-log4j2)
    configurations.configureEach {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }

    dependencies {
        // Spring Boot BOM as a Gradle platform: one place for every Spring dependency version
        "implementation"(platform(springBootBom))
        "testImplementation"(platform(springBootBom))
        // Lombok: compile-time only, version from the BOM
        "compileOnly"(platform(springBootBom))
        "annotationProcessor"(platform(springBootBom))
        "compileOnly"("org.projectlombok:lombok")
        "annotationProcessor"("org.projectlombok:lombok")
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // Apps: only the executable boot jar, named after the module (used by the Dockerfiles).
    // Library modules (common-logging) keep their plain jar.
    plugins.withId("org.springframework.boot") {
        tasks.named<Jar>("jar") { enabled = false }
        tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
            archiveFileName = "${project.name}.jar"
        }
        // Log files go to <repo root>/logs whichever module is run (log4j2-spring.xml)
        tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
            environment("LOG_PATH", rootProject.layout.projectDirectory.dir("logs").asFile.path)
        }
    }
}
