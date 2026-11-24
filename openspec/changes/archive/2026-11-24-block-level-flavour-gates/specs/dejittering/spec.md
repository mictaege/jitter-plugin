# Spec Delta

## ADDED Requirements

### Requirement: Dejittering resolves gate blocks per flavour

In the dejittered source variant of the active flavour, a `Gate.onlyIf(...)` statement whose
flavour list does not include the active flavour SHALL be removed as a whole; one that includes
it SHALL be unwrapped to its lambda body, in Java and Kotlin sources alike. With no active
flavour the gates stay unchanged.

#### Scenario: Active gate unwrapped
- **WHEN** sources with `Gate.onlyIf(List.of(Flavour.ESA), () -> ...)` are dejittered for ESA
- **THEN** the dejittered source contains the block body without the `Gate.onlyIf` wrapper

#### Scenario: Inactive gate removed
- **WHEN** the same sources are dejittered for NASA
- **THEN** the gated statement is gone from the dejittered source
