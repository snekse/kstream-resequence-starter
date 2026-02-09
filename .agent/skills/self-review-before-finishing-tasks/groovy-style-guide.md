---
trigger: glob
globs: **/src/**/*.groovy
---

- Avoid FQNs if possible; use imports
- Avoid GStrings when a String is more appropriate e.g. 'Foo' not "Foo"; "$firstName $lastName" is appropriate
- Use groovy's collection comprehension enhancements for less verbose code