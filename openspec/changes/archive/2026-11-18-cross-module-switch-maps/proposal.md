# Proposal

## Why

Issue mictaege/jitter-plugin#50: when a class switches over an enum declared in **another
module**, javac's synthetic switch-map class (`Outer$1` with `int[] $SwitchMap$<Enum>`) keeps
`GETSTATIC` references to enum constants that the active flavour removed in the producer module.
The stripped flavour artifact therefore leaks the other flavours' constant names (customer/site
names) into its constant pool, and neither `jitterVerify` nor `verifyCriticalTerms` reports it —
the reference verifier deliberately tolerates switch-map `<clinit>` references, because javac
wraps every entry in `catch (NoSuchFieldError)`. Within a single module the sweeper cleans the
map correctly; only the cross-module case leaks, because the consumer's strip run never sees the
producer's removals.

## What Changes

- The strip run learns the removals of flavour **dependencies**: `JitterStripTask` gains the
  dependency removal manifests as a task input (runtime classpath entries plus the sibling
  stripped trees of same-build project dependencies, exactly the mechanism `JitterVerifyTask`
  already uses; the own source set's manifest is excluded so the task never depends on its own
  output). The manifests become a `@Classpath`/`@InputFiles` input, so producer-strip ordering
  is inferred automatically and the cacheable strip task stays correct across flavour switches.
- `BytecodeStripper.strip` takes the cross-module removed members and carries them on the
  `StripContext`; `SyntheticSweeper.cleanSwitchMaps` drops switch-map entries whose enum constant
  is in the own removals **or** in the cross-module removals. Unparseable entry shapes stay a
  hard build error (fail loudly — a leftover entry is a silent leak, the very defect being fixed).
  Cross-module-driven entry removals are logged (`jitter: Remove switch map entry ...`) but are
  NOT added to the consumer's own removal manifest — they are not removals of this module.
- Kotlin's equivalent construct is covered too: kotlinc emits `Outer$WhenMappings` classes with
  `int[] $EnumSwitchMapping$N` whose `<clinit>` `GETSTATIC`s every enum constant inside
  `NoSuchFieldError` handlers (verified with kotlinc 2.2.21). A second shape walker removes
  entries from `$EnumSwitchMapping$` maps — today these are not cleaned even same-module, and
  the verifier does not tolerate them, so Kotlin builds currently fail loudly on this construct;
  after this change they are cleaned like javac switch maps.
- No behavior change for the 150% model (no active flavour), for external published flavour
  dependencies (deliverables carry no manifests by spec, so nothing is known about their
  removals — the `NoSuchFieldError` tolerance remains there), or for the manifest format.

## Capabilities

### New Capabilities

- none — this is a defect fix against existing behavior, not a new capability.

### Modified Capabilities

- `stripping`: the stripping SHALL drop switch-map / when-map entries of enum constants that a
  flavour dependency removed (cross-module), exactly as it already does for same-module
  removals, for both the javac (`$SwitchMap$…`) and kotlinc (`$EnumSwitchMapping$…`) shapes.
- `gradle-tasks`: `jitterStrip<SrcSet>` gains the dependency removal manifests as a task input
  (up-to-date/cache semantics preserved; active flavour already an input).

`verification` is deliberately NOT modified: the switch-map tolerance in `jitterVerify` stays as
is (javac's `NoSuchFieldError` intent; still needed for external producers whose deliverables
carry no manifest) — improving the stripping is the fix, not loosening the check.

## Impact

- `JitterStripTask.groovy` / `JitterPlugin.groovy` — new `dependencyClasspath`-style input wired
  from each source set's runtime classpath plus sibling stripped-tree manifests (reuse the
  `siblingManifests()` mapping), own manifest excluded.
- `BytecodeStripper.java` — new `strip(...)` overload parameter for cross-module removed members;
  `StripContext.java` — new field carrying them.
- `SyntheticSweeper.java` — `cleanSwitchMaps` matches own ∪ cross-module removals; new Kotlin
  `$EnumSwitchMapping$` shape walker; shape mismatch remains a `StripResult` error.
- `RemovalManifest.java` — reuse `read`/`readFile` from the strip task (no format change).
- `ReferenceVerifier.java` — no change: the `$SwitchMap$` `<clinit>` tolerance stays (still
  needed for external producers without manifests).
- Tests: unit (`SyntheticSweeperTest`, `BytecodeStripperTest`) for both shapes and the
  cross-module member set; functional (`CrossModuleVerifyFunctionalTest`) with the issue's
  two-module repro asserting the stripped `Hosts$1` constant pool no longer names
  `B_SECRET_ROBOT`; Kotlin when-map fixtures compiled in-test via the embeddable compiler.
- Full `./gradlew test`, then end-to-end validation in eval.jitter per flavour.
