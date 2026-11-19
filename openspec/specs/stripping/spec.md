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

### Requirement: Switch maps of cross-module enums are cleaned during stripping

When the active flavour removes enum constants in a flavour dependency, the synthetic switch-map
classes (`Outer$N` with `int[] $SwitchMap$<Enum>`) of surviving consumers SHALL have the entries
of those removed constants removed from their `<clinit>`, exactly as for constants removed in the
same module.

#### Scenario: Cross-module removed constant disappears from the switch map
- **WHEN** a consumer module switches over an enum of a producer module and the active flavour
  removed one of the used enum constants in the producer (per the producer's removal manifest
  `META-INF/jitter/<srcSet>-removed.txt` in the producer's stripped tree)
- **THEN** the consumer's stripped switch-map class `<clinit>` no longer references the removed
  constant (neither in instructions, exception-table ranges nor constant pool), entries for
  surviving constants remain, the removal is logged with the `jitter: ` prefix, `jitterVerify`
  passes, and the consumer's own removal manifest does NOT list the cross-module removal

#### Scenario: Unparseable entry shape fails the build
- **WHEN** a switch-map entry for a removed constant does not have the expected compiler shape
- **THEN** stripping fails with a `jitter: `-prefixed error naming the enum constant and the
  switch-map class (a leftover entry is a silent leak of other flavours' names — never warn-and-continue)

#### Scenario: External producer without manifest is left untouched
- **WHEN** the switched-over enum comes from an external published artifact (deliverables carry
  no removal manifest)
- **THEN** no entries are removed (nothing is known about its removals) and the runtime
  `NoSuchFieldError` tolerance remains the only mechanism

### Requirement: Kotlin when-maps are cleaned like javac switch maps

Entries for removed enum constants — same-module or cross-module — SHALL be removed from the
`<clinit>` of synthetic Kotlin `Outer$WhenMappings` classes (`int[] $EnumSwitchMapping$N`), so
neither the instructions nor the constant pool of the stripped class reference them anymore.

#### Scenario: Same-module Kotlin when-map entry removed
- **WHEN** a Kotlin class `when`s over an enum of the same module (kotlinc emits an
  `Outer$WhenMappings` class whose `<clinit>` `GETSTATIC`s every used enum constant inside a
  `NoSuchFieldError` handler) and the active flavour removed one of the used constants
- **THEN** the stripped `Outer$WhenMappings` `<clinit>` no longer references the removed constant,
  using the Kotlin entry shape (`ALOAD map; GETSTATIC constant; INVOKEVIRTUAL ordinal; int push;
  IASTORE; GOTO; handler ASTORE`)

#### Scenario: Cross-module Kotlin when-map entry removed
- **WHEN** a Kotlin class `when`s over an enum of a producer module whose removal manifest lists
  one of the used constants as removed
- **THEN** the consumer's stripped `Outer$WhenMappings` `<clinit>` no longer references the removed
  constant (neither in instructions nor in the constant pool)

#### Scenario: Unparseable Kotlin entry shape fails the build
- **WHEN** a `$EnumSwitchMapping$` entry for a removed constant does not have the expected Kotlin
  shape
- **THEN** stripping fails with a `jitter: `-prefixed error naming the enum constant and the
  when-map class

## Design decisions (from the former docs/bytecode_stripping.md)

1. Strip bytecode, not sources — the class file format is the stable contract across languages
   and syntax levels; the former source transformation doubled compilation, drifted line numbers
   and broke hot code replacement. Consequence: a verification pass is required (see verification
   spec).
2. Flavour model is the default output — consumers stay flavour-agnostic; the 150% model is a
   secondary `full`-classifier artifact.
10. Wiring principles — active flavour always a task input; tests prove the flavour variant; build
    data stays out of deliverables; producer/consumer outputs declared as task dependencies.
