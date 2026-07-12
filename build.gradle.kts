
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.ajoberstar.grgit.Grgit

plugins {
    alias(libs.plugins.grgit)
    alias(libs.plugins.download)
    alias(libs.plugins.versions)
    alias(libs.plugins.maven.publish.base) apply false
}

tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
    rejectVersionIf {
        val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { candidate.version.uppercase().contains(it) }
        val regex = "^[0-9,.v-]+(-r)?$".toRegex()
        val isStable = stableKeyword || regex.matches(candidate.version)
        !isStable
    }
    outputFormatter = "json,plain"
}

val (gitVersion, release) = versionFromGit()
logger.lifecycle("Version: $gitVersion (release: $release)")

allprojects {
    group = "com.github.JustinFreitas.lavaplayer"
    version = gitVersion

    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://jitpack.io")
    }

    configurations.all {
        resolutionStrategy.dependencySubstitution {
            substitute(module("commons-codec:commons-codec")).using(module("commons-codec:commons-codec:1.22.0"))
        }
    }
}

subprojects {
    if (project.name == "natives" || project.name == "extensions-project") {
        return@subprojects
    }

    apply<JavaPlugin>()
    apply<MavenPublishPlugin>()
    apply(plugin = "jacoco")

    tasks.withType<JacocoReport> {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    pluginManager.withPlugin("com.vanniktech.maven.publish.base") {
        configure<MavenPublishBaseExtension> {
            pom {
                description.set("Lavaplayer audio player library")
                url.set("https://github.com/justinfreitas/lavaplayer")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("justinfreitas")
                        name.set("Justin Freitas")
                    }
                    developer {
                        id.set("sedmelluq")
                        name.set("Sedmelluq")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/justinfreitas/lavaplayer.git")
                    developerConnection.set("scm:git:https://github.com/justinfreitas/lavaplayer.git")
                    url.set("https://github.com/justinfreitas/lavaplayer")
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

        return if (headTag != null && clean) headTag.name to true else "${git.head().id.substring(0, 7)}-SNAPSHOT" to false
    }
}
