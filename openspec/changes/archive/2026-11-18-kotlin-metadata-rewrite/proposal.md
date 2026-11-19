# Proposal

## Why

Issue mictaege/jitter-plugin#48: bytecode stripping removes flavour-gated members from Kotlin
classes correctly, but the `@kotlin.Metadata` annotation every Kotlin compiler writes onto the
class is left untouched — its `d1` protobuf and `d2` string table still describe the full 150%
model. The stripped binary artifacts therefore leak the names of other flavours' classes,
members and constructor parameter names; `verifyCriticalTerms` scans `jitter-stripped/**` and can
flag those leaked strings with no source-level fix; kotlin-reflect and libraries built on it
(jackson-module-kotlin, Spring Kotlin support, mocking libraries) see removed members; and
kotlinc compiling against a stripped JAR can compile calls to removed members that fail at
runtime with `NoSuchMethodError`/`NoSuchFieldError`. `jitterVerify` cannot catch any of this:
it checks code references, while the leaked names live only as strings inside an annotation.

## What Changes

- The strip pipeline gains a metadata phase: every surviving Kotlin class whose
  `@kotlin.Metadata` is affected is rewritten so the metadata matches the stripped bytecode —
  stripped enum entries, properties, functions, constructors, nested classes and sealed
  subclasses are removed from the metadata model, and writing it back rebuilds the `d2` string
  table so unreferenced strings disappear. All metadata kinds are handled (class k=1, file
  facade k=2, synthetic k=3, multi-file facade/part k=4/5). Classes without removals pass
  through byte-identical, and the no-flavour 150% model is untouched.
- The rewrite uses `org.jetbrains.kotlin:kotlin-metadata-jvm`, resolved lazily at task runtime
  from the applying build's repositories and loaded in an isolated child-first classloader —
  the same pattern as the Kotlin compiler embeddable for dejittering; it is deliberately NOT a
  runtime dependency of the plugin (its `org.jetbrains.kotlin.*` classes would clash with the
  Kotlin toolchain of Kotlin-based builds).
- `jitterVerify` gains a metadata consistency check: the `@kotlin.Metadata` of every surviving
  class is checked against the removal manifest (via plain ASM over the annotation's `d2`
  strings — no Kotlin library needed on the verify path) and the build fails if the metadata
  still references a removed class or a member removed from that class.
- No behavior change for Java/Groovy classes (no `@kotlin.Metadata`), for the 150% model, or for
  the removal manifest contents.

## Capabilities

### New Capabilities

- none — this is a defect fix against existing behavior, not a new capability.

### Modified Capabilities

- `stripping`: stripped Kotlin classes SHALL carry `@kotlin.Metadata` that matches the stripped
  bytecode, with the metadata library resolved lazily and isolated like the Kotlin parser.
- `verification`: `jitterVerify` SHALL fail when the metadata of a surviving class still
  references a removed class or member.

## Impact

- `src/main/java/.../BytecodeStripper.java` — one new phase in the strip pipeline.
- New `KotlinMetadataRewriter` + isolated-classloader wrapper (mirroring
  `IsolatedKotlinDejitter`/`ChildFirstClassLoader`).
- `JitterStripTask`/`JitterPlugin.groovy` — new `kotlinMetadataJars` input wired as a detached
  configuration; `JitterUtil` — new coordinate constant; `build.gradle.kts` — `compileOnly`
  + `testImplementation` for `kotlin-metadata-jvm`.
- `ReferenceVerifier` (or a sibling `MetadataVerifier`) — the ASM-level `d2` check.
- Tests: Kotlin fixtures compiled in-test via the embeddable compiler (already a test dep) or
  precompiled class fixtures; strip-side and verify-side regression tests. Full `./gradlew test`
  plus an eval.jitter end-to-end check per flavour.
