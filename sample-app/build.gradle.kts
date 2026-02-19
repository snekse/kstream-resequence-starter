plugins {
    id("org.springframework.boot")
    id("groovy")
    id("io.freefair.lombok") version "9.1.0"
}

dependencies {
    implementation(project(":resequence-starter"))
implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.apache.kafka:kafka-streams")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    implementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.apache.kafka:kafka-streams-test-utils")
    testImplementation("org.spockframework:spock-core:2.4-groovy-5.0")
    testImplementation("org.spockframework:spock-spring:2.4-groovy-5.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
