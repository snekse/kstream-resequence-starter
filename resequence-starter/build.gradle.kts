plugins {
    `java-library`
}

dependencies {
    api(project(":resequence-core"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
}
