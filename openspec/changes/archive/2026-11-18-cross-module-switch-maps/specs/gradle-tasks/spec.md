# Spec Delta

## ADDED Requirements

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
