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

### Requirement: Kotlin dejittering resolves repeated @Fork and @Alter per active flavour

For Kotlin sources, the dejittering SHALL consider **all** entries of a repeatable jitter
annotation (repeated entries and the entries of an explicitly written container) and SHALL apply
the entry whose `ifActive` contains the active flavour, exactly as the Java source dejittering and
the bytecode stripping do. When no entry matches, no fork/alter SHALL be applied, but the jitter
annotations SHALL still be removed from the surviving declaration.

#### Scenario: The active flavour is the second of repeated @Fork entries
- **WHEN** a Kotlin gateway method carries `@Fork(ifActive = "A", ...)` followed by
  `@Fork(ifActive = "B", ...)` and the source set is dejittered for flavour `B`
- **THEN** the gateway body is replaced with the body of the `B` template, the `B` template is
  removed, and no `@Fork` annotation remains on the gateway

#### Scenario: The active flavour is the first of repeated @Fork entries
- **WHEN** a Kotlin gateway method carries `@Fork(ifActive = "A", ...)` followed by
  `@Fork(ifActive = "B", ...)` and the source set is dejittered for flavour `A`
- **THEN** the gateway body is replaced with the body of the `A` template, the `A` template is
  removed, and no `@Fork` annotation remains on the gateway

#### Scenario: No repeated entry matches the active flavour
- **WHEN** a Kotlin gateway method carries only `@Fork(ifActive = "A", ...)` and
  `@Fork(ifActive = "B", ...)` and the source set is dejittered for flavour `C`
- **THEN** the gateway keeps its placeholder body, both templates survive, and the `@Fork`
  annotations are removed

#### Scenario: Repeated @Alter on a Kotlin class
- **WHEN** a top level Kotlin class carries `@Alter(ifActive = "A", with = "ATemplate")` followed
  by `@Alter(ifActive = "B", with = "BTemplate")` and the source set is dejittered for flavour `B`
- **THEN** the class file is replaced by the `BTemplate` variant, like the Java dejittering and
  the stripped model do

#### Scenario: Explicit container annotation in Kotlin source
- **WHEN** a Kotlin declaration carries the container form `@Gateway([@Fork(...) , @Fork(...)])`
  (or `@Alternatives([...])` for `@Alter`) and the source set is dejittered for the flavour of one
  of the nested entries
- **THEN** the matching nested entry is applied and the whole container annotation is removed

#### Scenario: Source variant agrees with the stripped model
- **WHEN** a Kotlin source set with repeated `@Fork`/`@Alter` is dejittered and cross-checked
  against the removal manifests of the stripped model
- **THEN** gateway bodies, kept templates and removed classes of the source variant match the
  stripped classes for every flavour, so the cross-check never has to report a difference

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

## Design decisions (from the former docs/bytecode_stripping.md)

8. Dejittering is source surgery, not decompilation — a source delivery must read like handwritten
   code; the spike with a decompiler produced compilable but unreviewable output.
9. Kotlin: same stripper, isolated parser — the stripper couples to the class file format (every
   JVM language works); a Kotlin runtime dependency would clash with Kotlin-based builds.
