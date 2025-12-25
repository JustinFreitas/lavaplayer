import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    `java-library`
    alias(libs.plugins.maven.publish.base)
}

base {
    archivesName = "lavaplayer-ext-youtube-rotator"
}

dependencies {
    compileOnly(projects.main)
    implementation(libs.slf4j)
}

mavenPublishing {
    configure(JavaLibrary(JavadocJar.Javadoc()))
}

configurations.all {
    resolutionStrategy.dependencySubstitution {
        // https://mvnrepository.com/artifact/commons-codec/commons-codec
        substitute(module("commons-codec:commons-codec")).using(module("commons-codec:commons-codec:1.20.0"))
    }
}
