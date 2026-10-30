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

    dependencies {
        // Spring Boot BOM as a Gradle platform: one place for every Spring dependency version
        "implementation"(platform(springBootBom))
        "testImplementation"(platform(springBootBom))
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

    // Only the executable boot jar, named after the module (used by the Dockerfiles)
    tasks.named<Jar>("jar") { enabled = false }
    plugins.withId("org.springframework.boot") {
        tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
            archiveFileName = "${project.name}.jar"
        }
    }
}
