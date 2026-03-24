# Implementation Spec: Resequencer Restructure - Phase 2

**Contract**: ./contract.md
**Estimated Effort**: S

## Technical Approach

Phase 2 creates the `resequence-spring-boot-starter` module — the optional bridge that gives Spring Boot users YAML-driven configuration and auto-wired beans. This module depends on `resequence-core` (Phase 1) and `spring-boot-autoconfigure`, adding Spring conveniences without polluting the core library.

The approach: create the `resequence-spring-boot-starter/` directory, `git mv` the Spring config files (`ResequenceProperties`, `ResequenceAutoConfiguration`, META-INF) from the now-thin `resequence-starter/` shell into the new module, update their packages and adapt them to use the new builder API, then delete the empty `resequence-starter/` directory and remove it from `settings.gradle.kts`. This preserves git history for the Spring config files.

This is a small module — ~3 production classes and a test to verify auto-configuration behavior.

## Feedback Strategy

**Inner-loop command**: `./gradlew :resequence-spring-boot-starter:test`

**Playground**: Test suite — verify auto-configuration creates the expected beans with correct defaults and YAML-driven overrides.

**Why this approach**: The starter module is pure Spring wiring. A Spring Boot test with `@SpringBootTest` or `ApplicationContextRunner` verifies bean creation and property binding in seconds.

## File Changes

### New Files

| File Path | Purpose |
|-----------|---------|
| `resequence-spring-boot-starter/build.gradle.kts` | Build config: depends on `resequence-core` + `spring-boot-autoconfigure` |
| `resequence-spring-boot-starter/src/test/groovy/.../spring/ResequenceAutoConfigurationSpec.groovy` | Tests for auto-configuration behavior |

### Moved Files (via `git mv` from `resequence-starter/`)

| Source | Destination | Changes after move |
|--------|-------------|-------------------|
| `resequence-starter/src/main/java/.../config/ResequenceProperties.java` | `resequence-spring-boot-starter/src/main/java/.../spring/ResequenceProperties.java` | Update package declaration (`config` → `spring`), update to use core builder defaults |
| `resequence-starter/src/main/java/.../config/ResequenceAutoConfiguration.java` | `resequence-spring-boot-starter/src/main/java/.../spring/ResequenceAutoConfiguration.java` | Update package, rewrite to expose pre-configured builder bean |
| `resequence-starter/src/main/resources/META-INF/spring/...imports` | `resequence-spring-boot-starter/src/main/resources/META-INF/spring/...imports` | Update FQCN to new package |

### Modified Files

| File Path | Changes |
|-----------|---------|
| `settings.gradle.kts` | Add `resequence-spring-boot-starter`, remove `resequence-starter` |

### Deleted Files

| File Path | Reason |
|-----------|--------|
| `resequence-starter/` (entire directory) | All files have been `git mv`'d to `resequence-core` (Phase 1) or `resequence-spring-boot-starter` (this phase). Directory is now empty. |

## Implementation Details

### 1. Build Configuration

**Overview**: The starter depends on `resequence-core` and `spring-boot-autoconfigure`. It's a `java-library` (not an application).

```kotlin
// resequence-spring-boot-starter/build.gradle.kts
plugins {
    `java-library`
    groovy
}

dependencies {
    api(project(":resequence-core"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.spockframework:spock-core:2.4-groovy-5.0")
    testImplementation("org.spockframework:spock-spring:2.4-groovy-5.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

**Key decisions**:
- `resequence-core` is `api` scope — consumers of the starter transitively get the core types
- `spring-boot-autoconfigure` is `implementation` — its types aren't exposed in the starter's public API
- No Jackson dependency (the core serde doesn't need it)
- No `spring-kafka` dependency — the starter provides config binding and bean setup, not Kafka infrastructure

**Implementation steps**:
1. Create `resequence-spring-boot-starter/` directory structure (src/main/java, src/main/resources, src/test/groovy)
2. Create `build.gradle.kts` per above
3. `git mv` the Spring config files from `resequence-starter/` to `resequence-spring-boot-starter/` (see Moved Files table above)
4. Update `settings.gradle.kts`: replace `resequence-starter` with `resequence-spring-boot-starter` → `include("resequence-core", "resequence-spring-boot-starter", "sample-app")`
5. Delete the now-empty `resequence-starter/` directory (all files have been moved)
6. Verify `./gradlew :resequence-spring-boot-starter:compileJava` passes

### 2. ResequenceProperties

**Pattern to follow**: The `git mv`'d `ResequenceProperties.java` — update in place after the move

**Overview**: Spring Boot `@ConfigurationProperties` class that binds `resequence.*` YAML properties to typed fields with sensible defaults. Same defaults as the core builder.

```java
@ConfigurationProperties(prefix = "resequence")
public class ResequenceProperties {
    private String stateStoreName = "resequence-buffer";
    private Duration flushInterval = Duration.ofSeconds(2);
    private TombstoneSortOrder tombstoneSortOrder = TombstoneSortOrder.LAST;
    // constructor, getters
}
```

**Key decisions**:
- Defaults match the core builder defaults exactly — the starter is just a different way to configure the same thing
- Uses constructor binding (immutable) to match Spring Boot 4.x best practices
- Package: `com.snekse.kafka.streams.resequence.spring` — separate from the core package to make the Spring dependency boundary visible
- The file is `git mv`'d (not recreated) to preserve git history

**Implementation steps**:
1. After `git mv`, update the package declaration from `config` to `spring`
2. Update imports if any core types changed packages
3. Ensure defaults match the core builder: `stateStoreName = "resequence-buffer"`, `flushInterval = 2s`, `tombstoneSortOrder = LAST`
4. Verify YAML binding works with `resequence.state-store-name`, `resequence.flush-interval`, `resequence.tombstone-sort-order`

### 3. ResequenceAutoConfiguration

**Overview**: Minimal auto-configuration that enables property binding. Optionally exposes a pre-configured builder bean that applications can further customize and build.

```java
@AutoConfiguration
@EnableConfigurationProperties(ResequenceProperties.class)
public class ResequenceAutoConfiguration {

    // Expose a pre-configured builder that applications can inject,
    // add their comparator/serdes to, and build.
    // The comparator and serdes are application-specific and cannot be auto-configured.
    @Bean
    @ConditionalOnMissingBean
    public Resequencer.Builder<?, ?> resequencerBuilder(ResequenceProperties properties) {
        return Resequencer.builder()
            .stateStoreName(properties.getStateStoreName())
            .flushInterval(properties.getFlushInterval())
            .tombstoneSortOrder(properties.getTombstoneSortOrder());
    }
}
```

**Key decisions**:
- The auto-configuration exposes a *partially configured builder*, not a fully built definition. Why? Because the comparator and serdes are application-specific — the starter can't know what `V` is or how to compare records. The application injects the builder, adds its domain-specific pieces, and calls `build()`.
- `@ConditionalOnMissingBean` lets applications override the builder entirely if they want full control
- The auto-configuration does NOT create topology beans, processor beans, or Kafka infrastructure — that's the application's responsibility (demonstrated by the sample app)
- The package is `com.snekse.kafka.streams.resequence.spring` to clearly separate Spring concerns from the core

**Implementation steps**:
1. After `git mv`, update the package declaration and rewrite the class to expose a pre-configured builder bean (see code snippet above)
2. Update `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` with the new FQCN
3. Verify the auto-configuration is discovered by Spring Boot

**Feedback loop**:
- **Playground**: Create `ResequenceAutoConfigurationSpec.groovy` using `ApplicationContextRunner`
- **Experiment**: Test with: default properties (verify defaults), custom YAML properties (verify override), missing bean override (verify `@ConditionalOnMissingBean`)
- **Check command**: `./gradlew :resequence-spring-boot-starter:test`

## Testing Requirements

### Unit Tests

| Test File | Coverage |
|-----------|----------|
| `ResequenceAutoConfigurationSpec.groovy` | Auto-config bean creation, property binding, conditional bean override |

**Key test cases**:
- Auto-configuration creates a builder bean with default properties
- Custom YAML properties (`resequence.flush-interval=5s`) are reflected in the builder
- `@ConditionalOnMissingBean` allows applications to override the builder
- Properties use correct defaults matching the core builder

## Validation Commands

```bash
# Compile
./gradlew :resequence-spring-boot-starter:compileJava

# Tests
./gradlew :resequence-spring-boot-starter:test

# Verify dependencies — should show resequence-core + spring-boot-autoconfigure
./gradlew :resequence-spring-boot-starter:dependencies --configuration runtimeClasspath

# Note: ./gradlew build will still FAIL because sample-app hasn't been updated yet.
# Validate only core + starter modules:
./gradlew :resequence-core:build :resequence-spring-boot-starter:build
```

---

_This spec is ready for implementation. Follow the patterns and validate at each step._
