
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.ajoberstar.grgit.Grgit

plugins {
    // https://mvnrepository.com/artifact/org.ajoberstar.grgit/grgit-gradle
    id("org.ajoberstar.grgit") version "5.3.3"
    // https://mvnrepository.com/artifact/de.undercouch/gradle-download-task
    id("de.undercouch.download") version "5.6.0"
    alias(libs.plugins.maven.publish.base) apply false
}

val (gitVersion, release) = versionFromGit()
logger.lifecycle("Version: $gitVersion (release: $release)")

allprojects {
    group = "com.github.justinfreitas"
    version = gitVersion

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

    pluginManager.withPlugin("com.vanniktech.maven.publish.base") {
        configure<MavenPublishBaseExtension> {
            pom {
                description.set("Lavaplayer audio player library")
                url.set("https://github.com/sedmelluq/lavaplayer")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("sedmelluq")
                        name.set("Sedmelluq")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/sedmelluq/lavaplayer.git")
                    developerConnection.set("scm:git:https://github.com/sedmelluq/lavaplayer.git")
                    url.set("https://github.com/sedmelluq/lavaplayer")
                }
            }
        }

        afterEvaluate {
            configure<MavenPublishBaseExtension> {
                val archivesName = project.extensions.getByType<org.gradle.api.plugins.BasePluginExtension>().archivesName.get()
                coordinates(project.group.toString(), archivesName, project.version.toString())
                pom {
                    name.set(archivesName)
                }
            }
        }
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
