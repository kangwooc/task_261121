plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "task"
include("member-api", "group-api", "member-service", "group-service")
