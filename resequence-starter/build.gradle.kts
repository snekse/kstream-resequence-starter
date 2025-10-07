plugins {
    `java-library`
    groovy
}

dependencies {
    api("org.springframework.boot:spring-boot-starter")
    api("org.springframework.kafka:spring-kafka")
    api("org.apache.kafka:kafka-streams")
    api("com.jayway.jsonpath:json-path")
    api("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.spockframework:spock-core:2.3-groovy-4.0")
    testImplementation("org.spockframework:spock-spring:2.3-groovy-4.0")
    testImplementation("org.apache.kafka:kafka-streams-test-utils")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
