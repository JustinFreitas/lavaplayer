plugins {
    `java-library`
}

dependencies {
    api(projects.main)
    implementation("org.springframework.boot:spring-boot-autoconfigure:4.1.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:4.1.0")
}
