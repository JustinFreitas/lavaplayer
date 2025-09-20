@file:Suppress("UnstableApiUsage")

import org.ajoberstar.grgit.Grgit

plugins {
    // https://mvnrepository.com/artifact/org.ajoberstar.grgit/grgit-gradle
    id("org.ajoberstar.grgit") version "5.2.2"
    // https://mvnrepository.com/artifact/de.undercouch/gradle-download-task
    id("de.undercouch.download") version "5.6.0"
    alias(libs.plugins.maven.publish.base) apply false
}

val (gitVersion, release) = versionFromGit()
logger.lifecycle("Version: $gitVersion (release: $release)")

allprojects {
    group = "com.github.justinfreitas"
    version = "v2.2.4_2"

    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

subprojects {
    if (project.name == "natives" || project.name == "extensions-project") {
        return@subprojects
    }

    apply<JavaPlugin>()
    apply<MavenPublishPlugin>()

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

fun versionFromGit(): Pair<String, Boolean> {
    Grgit.open(mapOf("currentDir" to project.rootDir)).use { git ->
        val headTag = git.tag
            .list()
            .find { it.commit.id == git.head().id }

        val clean = git.status().isClean || System.getenv("CI") != null
        if (!clean) {
            logger.lifecycle("Git state is dirty, version is a snapshot.")
        }

        return if (headTag != null && clean) headTag.name to true else "${git.head().id}-SNAPSHOT" to false
    }
}
