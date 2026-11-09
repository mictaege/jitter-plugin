# Dejittering Specification

## Purpose

`dejitter<SrcSet>` produces the flavour's *source variant* for source deliveries by source-level
surgery on the original sources (parse, remove, print) — not decompilation — cross-checked against
the removal manifests of the stripped model.

## Requirements

### Requirement: Source surgery preserves the original file

Dejittering SHALL remove/replace exact spans of the original sources, so comments, javadoc,
formatting and line numbers of untouched parts survive; the variant SHALL read like authored
source. Untouched files SHALL pass through byte-identical. Java sources SHALL be handled via
javaparser; a decompiler-based variant SHALL NOT be used.

#### Scenario: Dejittered source is reviewable
- **WHEN** `dejitter<SrcSet>` runs on a source set containing `@OnlyIf` code
- **THEN** `build/jitter-dejittered/<srcSet>` mirrors the source set layout (sources and resources
  separately) with the flavour-specific spans removed and all comments/formatting intact

### Requirement: Cross-check against the stripped model

The dejittered model SHALL be cross-checked against the removal manifests of the stripped model;
a disagreement SHALL be a build failure, never a silent difference between the source delivery and
the binary flavour model.

#### Scenario: Disagreement fails the build
- **WHEN** the dejitterer removes something the stripper kept (or vice versa)
- **THEN** the build fails with a `jitter: `-prefixed error

### Requirement: Kotlin via an isolated, lazily resolved parser

Kotlin sources SHALL carry the same annotations and be stripped by the same bytecode pass (no
Kotlin-specific stripping). The Kotlin parser for dejittering SHALL be resolved lazily at task
runtime from the applying build's repositories and loaded in an isolated classloader; it SHALL
never be a runtime dependency of the plugin. A Kotlin-only package SHALL need a Java
`package-info.java` for its flavour gate (`package-info.kt` is a possible later feature).

#### Scenario: Kotlin dejittering without a Kotlin runtime dep
- **WHEN** a Kotlin source set is dejittered
- **THEN** the Kotlin parser loads isolated at task runtime; the plugin's own dependencies do not
  include a Kotlin compiler

## Design decisions (from the former docs/bytecode_stripping.md)

8. Dejittering is source surgery, not decompilation — a source delivery must read like handwritten
   code; the spike with a decompiler produced compilable but unreviewable output.
9. Kotlin: same stripper, isolated parser — the stripper couples to the class file format (every
   JVM language works); a Kotlin runtime dependency would clash with Kotlin-based builds.
