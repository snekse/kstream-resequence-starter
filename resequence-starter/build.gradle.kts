plugins {
    `java-library`
    groovy
    id("io.freefair.lombok") version "9.1.0"
}

dependencies {
    api("org.apache.kafka:kafka-streams")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("tools.jackson.core:jackson-databind")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // OTel API — compileOnly so it is NOT forced onto users' classpaths transitively
    compileOnly("io.opentelemetry:opentelemetry-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-json")
    testImplementation("org.spockframework:spock-core:2.4-groovy-5.0")
    testImplementation("org.spockframework:spock-spring:2.4-groovy-5.0")
    testImplementation("org.apache.kafka:kafka-streams-test-utils")
    testImplementation("org.springframework.kafka:spring-kafka")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}
