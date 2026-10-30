plugins {
    // Lets Gradle download the Java 25 toolchain when it is not installed locally
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "keyloop-scheduler"

include("server-api")   // PR01 — REST API, owns the DB schema (Flyway)
include("scheduler-batch") // PR02 — Spring Batch worker jobs

// Repositories declared once for every module; a module may not add its own
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}
