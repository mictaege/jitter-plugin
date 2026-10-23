# AGENTS.md — jitter-plugin

## Project purpose
`jitter-plugin` is a Gradle plugin to build and distribute different flavours of an application from
a single source base — a code-level alternative to forking, feature-toggles, or preprocessors. The
complete application ("150% model") compiles untouched; flavour-specific code is then removed from
the compiled classes by post-compile bytecode stripping (ASM), based on annotations defined in
`jitter-api`. A verification pass fails the build if the surviving code still references removed
code, and the source-level "dejittering" produces a flavour's source variant for source deliveries.

Part of the jitter family: this plugin is the integration point — it depends on `jitter-api` for its
annotation contract. [`eval.jitter`](https://github.com/mictaege/eval.jitter) is the reference
example/demo consumer of this plugin.

## Technical stack
- Languages: mixed Groovy (`src/main/groovy`, plugin and task wiring) and Java (`src/main/java`,
  stripper, verifier, dejitterer), plus Java unit tests (`src/test/java`) and Gradle TestKit
  functional tests (`src/test/groovy`).
- Build tool: Gradle (Kotlin DSL, `build.gradle.kts`), `java-gradle-plugin` — build via `./gradlew`.
- Plugin packaging: `com.gradle.plugin-publish`, published to the Gradle Plugin Portal.
- Key dependencies: `jitter-api` (the annotation contract), ASM (`asm`, `asm-tree`, `asm-commons`
  — the bytecode stripping), javaparser (source-level dejittering of Java sources), Guava. The
  Kotlin compiler for the Kotlin dejittering is resolved lazily at task runtime and loaded in an
  isolated classloader — deliberately no runtime dependency, it would clash with the Kotlin
  toolchain of Kotlin based builds.
- Resources: `src/main/resources/META-INF/gradle-plugins` registers the plugin id; generated
  properties (written during `processResources`/`processTestResources`) expose the plugin's own
  version and the jitter-api version the tests build against.
- Release/publishing: `org.jreleaser`, publishing to Maven Central under group `io.github.mictaege`.
- License: Apache License 2.0.

## Project semantics / domain notes
- Core domain concept: "flavours" — different build/runtime variants of the same application,
  selected via generated Gradle tasks (e.g. `flavourESA`, `flavourNASA`) or
  `systemProp.jitter.active.flavour`; see `eval.jitter` for a concrete example.
- The pipeline per source set: compile the 150% model untouched → `jitterStrip<SrcSet>` (ASM tree
  API, `@OnlyIf`/`@Fork`/`@Alter`/`@AlterConstValue` from `jitter-api`) → `jitterVerify`
  (dangling references, own and cross-module via the removal manifests; up-to-date checked;
  stale-resource report) → the flavour-model artifacts.
- Artifact lanes: the flavour model is the default deliverable (`build/libs`, plain names), the
  150% model lives in `build/full-libs` under a `full` classifier.
- Flavour-specific resources: the name-suffix convention (`Xyz_<Flavour>`), whole-file gates in
  XML `appinfo` sections and component gates (`jitter:onlyIf`/`jitter:set`, resolved by the plugin
  while the resources are copied).
- javac inlines compile-time constants, so flavour-gated constants need special handling:
  `@InlinedConstantApproved` plus the mechanical `jitterVerifyInlinedConstants` check.
- `dejitter<SrcSet>` produces the flavour's source variant by source-level surgery (Java via
  javaparser, Kotlin via the Kotlin PSI), for source deliveries.
- Kotlin sources support the annotations like Java sources; a Kotlin-only package needs a Java
  `package-info.java` for its flavour gate (`package-info.kt` is a possible later feature).

## Working conventions
- Run `./gradlew test` before submitting changes; for end-to-end validation build and run
  `eval.jitter` with each flavour — it composite-builds this plugin from the sibling checkout,
  no publishing needed.
- Coordinate version bumps with `jitter-api`, since this plugin's correctness depends directly on
  it. Publishing and releasing is done by the author.
