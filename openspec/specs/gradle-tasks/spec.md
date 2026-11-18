# Gradle Tasks Specification

## Purpose

The plugin's Gradle surface: extension, source set roles, the task family, and how the active
flavour is selected — from the README's "Usage with Gradle" section.

## Requirements

### Requirement: Source set roles and task family

The plugin SHALL provide per source set: `jitterStrip<SrcSet>` (stripped classes to
`build/jitter-stripped/<srcSet>`), `jitterVerify<SrcSet>` (dangling-reference check), flavoured
resource processing, `jitter<Name>Jar` (stripped runtime artifact, e.g. `jitterJar` replacing the
plain jar for main/test-fixtures, generalized for custom `api` source sets), and
`dejitter<SrcSet>` (source variant to `build/jitter-dejittered/<srcSet>`, plus aggregate `dejitter`).
Aggregate/auxiliary tasks SHALL include `jitterVerifyAll` (all verifications + inlined-constant
checks, the CI entry point), `reportCriticalTerms` / `verifyCriticalTerms` (critical-term
verification via `@Tags`).

#### Scenario: Flavour task per configured flavour
- **WHEN** the build configures flavours ESA/NASA/ROSKOSMOS
- **THEN** tasks `flavourESA`, `flavourNASA`, `flavourROSKOSMOS` exist and select the active flavour

### Requirement: Active flavour selection

The active flavour SHALL be selectable via the generated `flavour<F>` tasks or the system property
`systemProp.jitter.active.flavour`, and SHALL be a task input of every flavour-specific task so
flavours switch without `clean`. In forked JVMs (e.g. test/bootRun), the active flavour SHALL be
propagated via the system property.

#### Scenario: Forked JVM sees the active flavour
- **WHEN** a test runs with active flavour NASA
- **THEN** the forked JVM receives `jitter.active.flavour=NASA` and flavour-gated runtime code
  resolves to the NASA model

### Requirement: Output convention

Every line the plugin writes to the build log SHALL start with the `jitter: ` prefix; severity
SHALL come from the Gradle log level (info = plain lifecycle, warning = counted build warning,
error = fails the task with the prefix in the failure message).

#### Scenario: Log lines are distinguishable
- **WHEN** the strip task removes a method
- **THEN** the log shows `jitter: Remove method com.example.SomeClass#someMethod` at lifecycle level

### Requirement: Strip tasks consume the dependency removal manifests as inputs

`jitterStrip<SrcSet>` SHALL read the removal manifests of the flavour dependencies as a
`@Classpath`/`@InputFiles` task input, excluding the own source set's manifest. Because the
manifest files live inside the producers' strip-task output directories, the consumer's strip task
SHALL automatically run after the producers' strip tasks, and the input SHALL keep the strip task
up-to-date checked and cacheable.

#### Scenario: Producer re-strip re-runs consumer strip
- **WHEN** a producer module re-strips (e.g. after a flavour switch changes its removal manifest;
  same-build project dependencies are mapped from the stripped jars on the source set's runtime
  classpath to the producers' stripped trees, the same mechanism `jitterVerify` uses)
- **THEN** the consumer module's `jitterStrip<SrcSet>` re-runs because its manifest input changed,
  and the consumer's stripped switch maps reflect the producer's current removals

#### Scenario: Strip never depends on its own output
- **WHEN** a test-like source set's runtime classpath includes its own stripped tree
- **THEN** the strip task excludes its own manifest from the scanned inputs, so no task cycle is
  created
