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

## Design decisions (from the former docs/bytecode_stripping.md)

1. Strip bytecode, not sources — the class file format is the stable contract across languages
   and syntax levels; the former source transformation doubled compilation, drifted line numbers
   and broke hot code replacement. Consequence: a verification pass is required (see verification
   spec).
2. Flavour model is the default output — consumers stay flavour-agnostic; the 150% model is a
   secondary `full`-classifier artifact.
10. Wiring principles — active flavour always a task input; tests prove the flavour variant; build
    data stays out of deliverables; producer/consumer outputs declared as task dependencies.
