plugins {
    `java-library`
    groovy
    id("io.freefair.lombok") version "9.1.0"
}

dependencies {
    api("org.apache.kafka:kafka-streams")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test only — Jackson for test value serde, Spock for BDD tests
    testImplementation("tools.jackson.core:jackson-databind")
    testImplementation("org.spockframework:spock-core:2.4-groovy-5.0")
    testImplementation("org.apache.kafka:kafka-streams-test-utils")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
