plugins {
    `java-library`
    groovy
}

dependencies {
    implementation("org.apache.groovy:groovy:5.0.0-alpha-1") // Explicitly using Groovy 5
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.kafka:spring-kafka")
    api("org.apache.kafka:kafka-streams")
    api("com.jayway.jsonpath:json-path")
    api("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.spockframework:spock-core:2.4-groovy-5.0")
    testImplementation("org.spockframework:spock-spring:2.4-groovy-5.0")
    testImplementation("org.apache.kafka:kafka-streams-test-utils")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
