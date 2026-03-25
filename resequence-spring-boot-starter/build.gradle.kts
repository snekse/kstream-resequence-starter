plugins {
    `java-library`
    groovy
}

// Required for Spring Boot constructor-binding of @ConfigurationProperties (parameter names must be retained)
tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

dependencies {
    api(project(":resequence-core"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.spockframework:spock-core:2.4-groovy-5.0")
    testImplementation("org.spockframework:spock-spring:2.4-groovy-5.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
