# Context Map: Resequencer Restructure

**Phase**: 2
**Scout Confidence**: 85/100
**Verdict**: GO

## Prior Phase Summary

Phase 1 (confidence 89/100, GO) created `resequence-core` with zero Spring/Jackson deps, binary serde, builder API (`Resequencer.java`), and migrated core source files from `resequence-starter/`. The `resequence-starter/` module was slimmed to only Spring config files (2 Java classes + 1 META-INF resource + README + build.gradle.kts).

## Dimensions

| Dimension | Score | Notes |
|---|---|---|
| Scope clarity | 18/20 | All files to move, create, modify, and delete are identified. Minor gap: spec does not mention `resequence-starter/README.md` which also exists in the directory to be deleted. |
| Pattern familiarity | 18/20 | `resequence-core/build.gradle.kts` provides the build config pattern. Existing source files are trivially small. `ApplicationContextRunner` test pattern is standard Spring Boot testing. |
| Dependency awareness | 17/20 | `ResequenceProperties` consumed by `sample-app/ResequenceTopologyConfig.java` (Phase 3 fix). `ResequenceAutoConfiguration` referenced only by META-INF imports file (co-moved). |
| Edge case coverage | 16/20 | Key edge case: spec's auto-config code calls `tombstoneSortOrder()` on builder but `Resequencer.Builder` has no such method. Builder only has `stateStoreName()` and `flushInterval()`. |
| Test strategy | 16/20 | Inner loop: `./gradlew :resequence-spring-boot-starter:test`. `ApplicationContextRunner` for auto-config tests. Spock + `spock-spring`. |

## Key Patterns

- `resequence-starter/src/main/java/.../config/ResequenceProperties.java` — Constructor-bound `@ConfigurationProperties` with 3 fields. Package `config` → `spring` after move.
- `resequence-starter/src/main/java/.../config/ResequenceAutoConfiguration.java` — Minimal auto-config with `@EnableConfigurationProperties`. Rewritten to expose builder bean.
- `resequence-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — Single FQCN line. Updated package after move.
- `resequence-core/build.gradle.kts` — Analogous module build config pattern.
- `resequence-core/src/main/java/.../Resequencer.java` — Builder API. Methods: `stateStoreName()`, `flushInterval()`. No `tombstoneSortOrder()`.

## Dependencies

- `ResequenceProperties.java` → consumed by `sample-app/.../ResequenceTopologyConfig.java:5,43,51` (Phase 3 fix)
- `ResequenceAutoConfiguration.java` → consumed by META-INF imports (co-moved)
- `settings.gradle.kts` → `sample-app/build.gradle.kts:8` depends on `:resequence-spring-boot-starter` (updated in Phase 2)

## Conventions

- **Naming**: Module directory matches Gradle project name. Test files: `*Spec.groovy`.
- **Imports**: No wildcard imports. Individual type imports.
- **Testing**: Spock 2.4 + Groovy 5.x. `spock-spring` for Spring integration. `ApplicationContextRunner` for auto-config tests.
- **Build**: Gradle Kotlin DSL. Root applies Spring BOM via `io.spring.dependency-management`. `-parameters` compiler flag needed for Spring Boot constructor binding.

## Risks

- **Spec/builder mismatch on `tombstoneSortOrder`**: Builder has no such method. Auto-config only sets `stateStoreName` and `flushInterval`. Resolved in implementation.
- **Sample-app build will break**: `sample-app` import paths still reference old `config` package. Phase 3 concern.
- **Wildcard generic builder bean**: `Resequencer.Builder<?, ?, ?, ?>` — Phase 3 concern for injection ergonomics.
- **`-parameters` compiler flag**: Required for Spring Boot constructor binding of `ResequenceProperties`. Added to starter module build config.
