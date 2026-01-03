# Agent Guide

## Project Overview
This project is a Spring Boot Starter library that implements the Resequence Enterprise Integration Pattern (EIP) for Kafka Streams projects, without relying on Spring Integration or Apache Kafka directly.

## Repository Layout
- `resequence-starter`: The core starter library implementing the Resequence EIP.
- `sample-app`: A sample application demonstrating the usage of the starter with Spring Kafka.

## Technology Stack
- **Framework**: Spring Boot 4.x
- **Language**: Java 21+
- **Build Tool**: Gradle (Kotlin DSL)
- **Testing**: Spock 2.4+ (Groovy 5.x)

## Building and Testing
1. **Build the project**:
   ```bash
   ./gradlew clean build
   ```
2. **Run tests**:
   ```bash
   ./gradlew test
   ```

## Development Guidelines
- Follow standard Spring Boot starter patterns.
- Ensure all new features are covered by Spock tests.
- When modifying the starter, ensure the sample app is updated to reflect changes.

## Future Roadmap
- Support for plain Apache Kafka (non-Spring) in a future sample app.
