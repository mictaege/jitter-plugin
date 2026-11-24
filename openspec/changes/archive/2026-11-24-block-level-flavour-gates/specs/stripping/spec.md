# Spec Delta

## ADDED Requirements

### Requirement: Gated blocks are stripped per flavour

For each flavour not in a `Gate.onlyIf(list, block)` call's flavour list, the stripping SHALL
remove the gated call site (including its argument instructions) and the synthetic lambda body
method from the bytecode, in Java and Kotlin classes alike. Surrounding code SHALL remain valid
bytecode; removed lambda bodies SHALL be recorded in the removal manifest so the Kotlin metadata
rewrite and the `$deserializeLambda$` cleanup drop them too.

#### Scenario: Gated block removed in non-listed flavour
- **WHEN** a method contains `Gate.onlyIf(List.of("ESA"), () -> ...)` and the build runs flavour NASA
- **THEN** the stripped class contains no call site and no synthetic lambda method

#### Scenario: Gated block kept in listed flavour
- **WHEN** the same class is stripped for ESA
- **THEN** the block is present and executes normally
