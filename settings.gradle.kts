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

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            plugins()
            common()
            others()
            test()
        }
    }
}

fun VersionCatalogBuilder.plugins() {
    // https://github.com/vanniktech/gradle-maven-publish-plugin
    val mavenPublishPlugin = version("maven-publish-plugin", "0.34.0")

    plugin("maven-publish", "com.vanniktech.maven.publish").versionRef(mavenPublishPlugin)
    plugin("maven-publish-base", "com.vanniktech.maven.publish.base").versionRef(mavenPublishPlugin)
}

fun VersionCatalogBuilder.common() {
    // https://mvnrepository.com/artifact/org.slf4j/slf4j-api
    library("slf4j", "org.slf4j", "slf4j-api").version("2.0.17")
    // https://mvnrepository.com/artifact/commons-io/commons-io
    library("commons-io", "commons-io", "commons-io").version("2.20.0")
    // https://mvnrepository.com/artifact/org.jetbrains/annotations
    library("intellij-annotations", "org.jetbrains", "annotations").version("26.0.2-1")

    // https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-core
    version("jackson", "2.20.0")
    library("jackson-core", "com.fasterxml.jackson.core", "jackson-core").versionRef("jackson")
    library("jackson-databind", "com.fasterxml.jackson.core", "jackson-databind").versionRef("jackson")

    // https://mvnrepository.com/artifact/org.apache.httpcomponents/httpclient
    library("httpclient", "org.apache.httpcomponents", "httpclient").version("4.5.14")

    // https://mvnrepository.com/artifact/org.jsoup/jsoup
    library("jsoup", "org.jsoup", "jsoup").version("1.21.2")
    // https://mvnrepository.com/artifact/net.iharder/base64
    library("base64", "net.iharder", "base64").version("2.3.9")
    // https://mvnrepository.com/artifact/org.json/json
    library("json", "org.json", "json").version("20250517")
}

fun VersionCatalogBuilder.others() {
    // https://mvnrepository.com/artifact/com.github.walkyst/ibxm-fork
    library("ibxm-fork", "com.github.walkyst", "ibxm-fork").version("a75")
    // https://mvnrepository.com/artifact/org.mozilla/rhino-engine
    library("rhino-engine", "org.mozilla", "rhino-engine").version("1.8.0")
}

fun VersionCatalogBuilder.test() {
    // https://mvnrepository.com/artifact/org.apache.groovy/groovy
    library("groovy", "org.apache.groovy", "groovy").version("4.0.28")
    // https://mvnrepository.com/artifact/org.spockframework/spock-core
    library("spock-core", "org.spockframework", "spock-core").version("2.4-M6-groovy-4.0")
    // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
    library("logback-classic", "ch.qos.logback", "logback-classic").version("1.5.18")
}
