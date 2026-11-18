# Stripping Specification

## Purpose

The bytecode stripping core: the 150% model compiles untouched, then flavour-specific code is
removed from the *compiled classes* per source set. Source files and line number tables of
surviving code are never touched (debugger resolves to original sources).

## Requirements

### Requirement: Strip bytecode, not sources

The system SHALL compile the complete 150% model untouched and remove flavour-specific parts from
the class files afterwards (ASM tree API), never rewriting sources during the build. The 150%
model SHALL be self-consistent — it must compile in every flavour; flavour usage that only worked
under per-flavour source transformation SHALL fail the compile deliberately.

#### Scenario: Strip per source set
- **WHEN** a source set compiles
- **THEN** `jitterStrip<SrcSet>` runs after its compilation and writes the stripped model to
  `build/jitter-stripped/<srcSet>`, leaving the compiled classes untouched

### Requirement: The flavour model is the default deliverable

Compilation SHALL resolve against the full 150% model (apiElements keeps the full classes), while
everything that ships or runs SHALL be stripped: the flavour model is the default artifact
(`build/libs`, plain names; `runtimeElements` resolves the stripped jar, e.g. `jitterJar` replacing
the plain jar), and the 150% model lives in `build/full-libs` under a `full` classifier. Removal
manifests SHALL stay out of jars/wars/publications (build data, not application data).

#### Scenario: Two artifact lanes per source set
- **WHEN** a project with jitter builds
- **THEN** `build/libs` contains the flavour model under plain archive names and `build/full-libs`
  the 150% model with identical names

### Requirement: The active flavour is a task input everywhere

The active flavour SHALL be a task input of every flavour-specific task (strip, verify, resource,
jar), so flavours switch without a `clean`; outputs SHALL be up-to-date checked and cacheable.
Tests SHALL run against the stripped model (test tasks depend on verification). All plugin log
lines SHALL start with the `jitter: ` prefix (severity via Gradle log level).

#### Scenario: Flavour switch without clean
- **WHEN** the build runs `flavourESA` then `flavourNASA` without clean
- **THEN** both produce correct models via up-to-date/cache handling, and log lines are prefixed
  `jitter: `

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

## Design decisions (from the former docs/bytecode_stripping.md)

1. Strip bytecode, not sources — the class file format is the stable contract across languages
   and syntax levels; the former source transformation doubled compilation, drifted line numbers
   and broke hot code replacement. Consequence: a verification pass is required (see verification
   spec).
2. Flavour model is the default output — consumers stay flavour-agnostic; the 150% model is a
   secondary `full`-classifier artifact.
10. Wiring principles — active flavour always a task input; tests prove the flavour variant; build
    data stays out of deliverables; producer/consumer outputs declared as task dependencies.
