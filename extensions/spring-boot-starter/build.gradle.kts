plugins {
    `java-library`
}

dependencies {
    api(projects.main)
    implementation("org.springframework.boot:spring-boot-autoconfigure:4.0.5")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:4.0.5")
}
