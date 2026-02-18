plugins {
    id("io.freefair.lombok") version "9.1.0"
}

dependencies {
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
