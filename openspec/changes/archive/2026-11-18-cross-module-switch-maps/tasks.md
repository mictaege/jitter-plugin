# Tasks

## 1. Red-first unit tests for the sweeper

- [x] 1.1 Add red tests in `SyntheticSweeperTest`: `cleanSwitchMaps` with a cross-module removed
  member set on the `StripContext` removes the javac `$SwitchMap$` entry of a constant that is NOT
  in the own `removedMembers` but in the cross-module set, keeps surviving entries, and leaves
  non-removed constants untouched. Verify with `./gradlew test --tests SyntheticSweeperTest` that
  the new cases fail before the implementation.
- [x] 1.2 Add red tests for the Kotlin shape: compile a Kotlin fixture in-test via the embeddable
  compiler (pattern already used by `KotlinFixtures`) with a `when` over an enum, strip the gated
  constant same-module, and assert the stripped `Outer$WhenMappings` `<clinit>` no longer
  references the removed constant (bytecode + constant pool); plus a cross-module variant using
  the cross-module member set. Verify red with `./gradlew test --tests SyntheticSweeperTest`.
- [x] 1.3 Add red tests that an unparseable entry shape (both javac and Kotlin shapes) produces a
  `StripResult` error naming the constant and the switch-map class, for own-module and
  cross-module removals. Verify red with `./gradlew test --tests SyntheticSweeperTest`.

## 2. Strip-side implementation

- [x] 2.1 Add `crossModuleRemovedMembers` (Set<String> of `owner.name+desc` keys) to `StripContext`
  and a `BytecodeStripper.strip(...)` overload accepting it (older overloads delegate with an
  empty set). Verify: `./gradlew compileJava` + existing `BytecodeStripperTest` still green.
- [x] 2.2 In `SyntheticSweeper.cleanSwitchMaps`, match entries against the union of
  `ctx.result.getRemovedMembers()` and `ctx.crossModuleRemovedMembers`; log each removal with
  `jitter: Remove switch map entry ...`; do NOT add cross-module keys to the result's removed
  members (own manifest unchanged). Verify: task 1.1 tests green via
  `./gradlew test --tests SyntheticSweeperTest`.
- [x] 2.3 Add the Kotlin `$EnumSwitchMapping$` walker to `SyntheticSweeper` (detect
  `Outer$WhenMappings` classes by `$EnumSwitchMapping$` field, walk the Kotlin entry shape
  `ALOAD; GETSTATIC const; INVOKEVIRTUAL ordinal; push; IASTORE; GOTO; handler ASTORE`, remove
  entry + exception-table range for removed constants, error on shape mismatch). Verify: task 1.2
  and 1.3 tests green via `./gradlew test --tests SyntheticSweeperTest`.

## 3. Task wiring: manifests as strip inputs

- [x] 3.1 Add a `dependencyClasspath` FileCollection (`@Internal` - a `@Classpath` input would make
  test-like source sets depend on their own stripped tree, which sits on their runtime classpath)
  to `JitterStripTask` plus a `@InputFiles` manifest-file set (stripped trees/jars on the runtime
  classpath mapped to producers' `build/jitter-stripped/<ss>/META-INF/jitter/<ss>-removed.txt`),
  excluding the own source set's manifest/output; `dependsOnSiblingStripTasks()` declares the
  explicit dependency on the producers' strip tasks (a provider - file inputs alone did not create
  the task edge, Gradle raised "implicit dependency" validation errors). In `run()`, collect
  removed members via `RemovalManifest.readFile` over the manifest files and pass them to the new
  `strip(...)` overload. Wire `stripTask.dependencyClasspath = srcSet.runtimeClasspath` in
  `JitterPlugin.groovy`. Verify: `./gradlew test --tests CrossModuleVerifyFunctionalTest` green
  (incl. red-checked: both new functional tests fail without the wiring).
- [x] 3.2 Add a functional test in `CrossModuleVerifyFunctionalTest` with the issue #50 repro
  (modules `model` + `app`, enum `Robot` with `@OnlyIf` constants, `@Fork`ed `host` with per-flavour
  switches): run `flavourA :app:jitterStripMain :app:jitterVerifyMain`, then assert via ASM/javap
  on `app/build/jitter-stripped/main/repro/Hosts$1.class` that the constant pool and `<clinit>` no
  longer contain `B_SECRET_ROBOT`, and that `jitterVerifyMain` passes. Verify red before 2.2/3.1,
  green after, with `./gradlew test --tests CrossModuleVerifyFunctionalTest`.
- [x] 3.3 Add a functional test that a producer re-strip re-runs the consumer strip (manifest input
  changed) and that no task cycle occurs for test-like source sets (own manifest excluded). Verify
  with `./gradlew test --tests CrossModuleVerifyFunctionalTest`.

## 4. Full verification

- [x] 4.1 Run the whole suite `./gradlew test` — all stripper/verifier/dejitterer/functional tests
  green, no regressions.
- [x] 4.2 End-to-end in eval.jitter (validation only, no source changes there): build each flavour
  (ESA/NASA/ROSKOSMOS), inspect stripped synthetic classes (`Outer$1`, `$WhenMappings` if any) for
  leaked other-flavour names, and confirm `jitterVerify`/`verifyCriticalTerms` pass.

## Workflow follow-up

- Archive the change (`openspec archive`) after review, syncing the deltas into
  `openspec/specs/stripping/spec.md` and `openspec/specs/gradle-tasks/spec.md`.
