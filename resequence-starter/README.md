# Resequence Starter

This module contains the core logic for the Resequence EIP implementation on top of Kafka Streams.

## Features
- Drop-in Spring Boot Starter.
- Configurable resequencing window and timeout.
- State store management for buffering messages.

## Usage
Add this dependency to your Spring Boot Kafka Streams application (not yet published to user repo):

```kotlin
implementation(project(":resequence-starter"))
```
