---
trigger: model_decision
description: When writing Spock unit tests
---

- Follow all Groovy style rules in `.agents/rules/groovy-style-guide.md`
- Naming pattern `*Spec.groovy`
- **`@PendingFeature` workflow**: This annotation marks a test as "expected to fail". When the test fails, Spock treats it as passing. The intended workflow is:
  1. Write the test with `@PendingFeature` annotation
  2. Implement the feature until the test starts passing (which causes `@PendingFeature` to fail)
  3. Remove the `@PendingFeature` annotation
  4. Run tests again - they should now pass
  5. Feature implementation is complete