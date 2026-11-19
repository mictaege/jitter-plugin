# Spec Delta

## ADDED Requirements

### Requirement: Stripped Kotlin classes carry @kotlin.Metadata that matches the stripped bytecode

When the stripping removes declarations from a Kotlin class, the class's `@kotlin.Metadata`
annotation SHALL be rewritten in the same pass so the metadata describes exactly the stripped
model, and the rebuilt `d2` string table SHALL no longer contain strings that nothing references
anymore. All metadata kinds SHALL be handled (classes k=1, file facades k=2, synthetic k=3,
multi-file facades/parts k=4/5).

#### Scenario: Removed enum entries disappear from the metadata
- **WHEN** a Kotlin enum has `@OnlyIf`-gated entries and the source set is stripped for a
  flavour that does not include them
- **THEN** the surviving enum's class file keeps only its surviving entries in the bytecode
  AND its `@kotlin.Metadata` no longer lists the removed entries or their names in `d2`

#### Scenario: Removed constructor stops leaking its parameter types and names
- **WHEN** a Kotlin class has flavour-specific constructors and the source set is stripped for
  another flavour
- **THEN** the removed constructor's JVM signature, parameter names and parameter type
  descriptors SHALL not occur in the surviving class's metadata `d2` anymore

#### Scenario: File facades and synthetic classes are rewritten too
- **WHEN** a Kotlin file's top level declarations or a synthetic/multi-file class are affected
  by the stripping
- **THEN** their metadata kinds (k=2, k=3, k=4/5) SHALL be rewritten with the same rule as
  plain classes

#### Scenario: A fully stripped file facade is deleted with its class file
- **WHEN** every top level declaration of a Kotlin file (facade kinds k=2/4/5) is removed by
  the stripping
- **THEN** the facade's class file SHALL be deleted and the facade SHALL be recorded as a
  removed class in the removal manifest, like any other removed class

### Requirement: Metadata rewriting is scoped and dependency-isolated

A Kotlin class without removals SHALL pass through byte-identical, and with no flavour active
(the 150% model) no metadata SHALL be rewritten. The metadata library SHALL be resolved lazily
at task runtime from the applying build's repositories and loaded in an isolated classloader -
it SHALL never be a runtime dependency of the plugin.

#### Scenario: Untouched Kotlin classes pass through byte-identical
- **WHEN** a Kotlin class has no flavour-gated declarations and the source set is stripped
- **THEN** its class file SHALL be written byte-identical, metadata included

#### Scenario: The 150% model keeps its full metadata
- **WHEN** the stripping runs with no active flavour
- **THEN** no `@kotlin.Metadata` annotation SHALL be modified
