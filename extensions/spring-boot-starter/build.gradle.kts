plugins {
    `java-library`
}

dependencies {
    api(projects.main)
    implementation(libs.spring.boot.autoconfigure)
    annotationProcessor(libs.spring.boot.configuration.processor)
}
