# Kafka Streams Resequence Starter

This project provides a Spring Boot Starter library to implement the **Resequence Enterprise Integration Pattern** using Kafka Streams.

## Goal
The simpler goal is to resequence messages based on a sequence number or timestamp without the overhead of full Spring Integration, specifically tailored for Kafka Streams environments.

See [Resequencer Pattern](https://www.enterpriseintegrationpatterns.com/patterns/messaging/Resequencer.html).

## Modules
- **resequence-starter**: The library module containing the resequencing logic and auto-configuration.
- **sample-app**: A reference implementation showing how to use the starter in a Spring Boot application.

## Technologies
- Spring Boot 4.0.1
- Java 21
- Kafka Streams
- Spock Framework & Groovy 5 (Testing)

## Roadmap
- [x] Initial structure and dependency upgrade
- [ ] Core Resequencer implementation
- [ ] Plain Apache Kafka support (planned)
