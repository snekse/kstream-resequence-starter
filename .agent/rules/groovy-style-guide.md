---
trigger: glob
globs: **/src/**/*.groovy
---

- Avoid FQNs if possible; use imports
- Use groovy's collection comprehension enhancements for less verbose code