rootProject.name = "lavaplayer"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(
    ":common",
    ":main",
    ":extensions",
    ":extensions:youtube-rotator",
    ":extensions:format-xm",
    ":natives",
    ":natives-publish",
    ":testbot"
)

// https://github.com/gradle/gradle/issues/19254
project(":extensions").name = "extensions-project"
