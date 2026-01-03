plugins {
    java
    id("org.springframework.boot") version "4.0.1" apply false
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val springBootBomVersion = "4.0.1"

allprojects {
    repositories {
        mavenCentral()
        maven {
            name = "Confluent"
            url = uri("https://packages.confluent.io/maven/")
        }
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    dependencyManagement {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootBomVersion")
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
