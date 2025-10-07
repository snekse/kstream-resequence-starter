plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":resequence-starter"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
