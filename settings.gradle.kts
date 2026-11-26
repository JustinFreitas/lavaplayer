plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "lavaplayer"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":common",
    ":main",
    ":extensions",
    ":extensions:youtube-rotator",
    ":extensions:format-xm",
    ":extensions:spring-boot-starter",
    ":natives",
    ":natives-publish",
    ":testbot"
)

// https://github.com/gradle/gradle/issues/19254
project(":extensions").name = "extensions-project"

