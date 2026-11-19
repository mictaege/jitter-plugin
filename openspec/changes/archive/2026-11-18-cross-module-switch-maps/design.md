# Design

## Context

See proposal.md — Why (issue mictaege/jitter-plugin#50). Current state, verified in code:

- `SyntheticSweeper.cleanSwitchMaps` (`src/main/java/.../SyntheticSweeper.java:206`) removes a
  switch-map entry only when the enum constant's `owner.name+descriptor` key is in
  `ctx.result.getRemovedMembers()` — i.e. removed by THIS strip run. Its javadoc explicitly
  leaves cross-module entries untouched. Same-module cleaning works (covered by
  `SyntheticSweeperTest.shouldCleanSwitchMapEntriesOfRemovedEnumConstants`).
- Cross-module removals exist only as removal manifests
  (`META-INF/jitter/<srcSet>-removed.txt`, format `C <class>` / `M <owner>.<name><descriptor>`,
  `StripResult.memberKey` = `owner + "." + name + desc`). They are read only by
  `JitterVerifyTask` (runtime classpath entries via `RemovalManifest.read` + `siblingManifests()`
  mapping stripped jars back to producers' stripped trees) and `JitterDejitterTask`. Deliverable
  jars/wars exclude `META-INF/jitter/**` (`JitterPlugin.groovy`), so manifests exist only in
  same-build stripped trees — external published flavour dependencies carry no manifest.
- `JitterStripTask` today has only `templateClasspath = srcSet.compileClasspath` for `@Alter`
  templates; `BytecodeStripper.strip(...)` has no parameter for cross-module removals.
- `ReferenceVerifier` tolerates field references inside a `$SwitchMap$` `<clinit>`
  (`ReferenceVerifier.java:81-99`), so `jitterVerify` passes while the names leak.
- Both compilers emit the same leak pattern (verified empirically, JDK 21 / kotlinc 2.2.21):
  - javac: `Outer$1` with `int[] $SwitchMap$<Enum>`; per-entry shape `GETSTATIC map; GETSTATIC const;
    INVOKEVIRTUAL ordinal; int push; IASTORE; try-end label; GOTO; handler label; ASTORE`, with
    `NoSuchFieldError` exception-table ranges.
  - kotlinc: `Outer$WhenMappings` with `int[] $EnumSwitchMapping$N`; per-entry shape `ALOAD map;
    GETSTATIC const; INVOKEVIRTUAL ordinal; int push; IASTORE; GOTO; handler ASTORE` (map is a
    local var, no try-end label). Nothing cleans these today — the sweeper only matches
    `$SwitchMap$` field names, and the verifier does not tolerate them, so Kotlin currently fails
    loudly on this construct instead of leaking.
- Dirty classes are serialized via `ClassWriter(0)` + `node.accept(...)` (`ClassFileWriter`), so
  removed instructions drop out of the rebuilt constant pool too.

## Goals / Non-Goals

**Goals:**
- Stripped consumer switch maps / when-maps no longer reference enum constants removed in a
  same-build flavour dependency (javac and kotlinc shapes); own-module behavior unchanged.
- Strip-task ordering and cache correctness come from the manifest files as task inputs.
- Unparseable entry shapes stay hard build errors (fail loudly — a leftover entry is a silent
  leak, the defect being fixed).

**Non-Goals:**
- External published flavour dependencies: their deliverables carry no manifest by spec
  ("removal manifests SHALL stay out of jars/wars/publications"), so their removals are unknowable
  at consumer strip time; the `NoSuchFieldError` tolerance remains the only mechanism there.
- No change to `ReferenceVerifier` (tolerance kept), to the manifest format, or to the 150% model
  (no active flavour ⇒ no removals ⇒ nothing to clean).
- Cross-module-driven entry removals are NOT added to the consumer's own removal manifest.

## Decisions

1. **Feed the manifests into the strip run (Option A) instead of resolving the stripped enum from
   the classpath (Option B) or extending critical-terms (Option C).** The manifest is the
   established cross-module carrier (verification spec: manifests are "the cross-module carriers"),
   records exactly what was removed, and avoids reading producer bytecode at strip time. Option B
   needs the runtime classpath anyway (compile classpath holds the full 150% enum) and duplicates
   manifest knowledge; Option C only detects configured terms and does not fix the leak.
2. **Reuse the verify task's manifest resolution.** `JitterStripTask` gets a
   `dependencyClasspath`-style input = the source set's runtime classpath; manifests are collected
   with `RemovalManifest.read` per entry (directories: scan `META-INF/jitter/*-removed.txt`;
   jars: none, they are excluded) plus the `siblingManifests()` mapping from stripped jars to
   producers' stripped trees, so same-build project dependencies are covered. Declaring the
   manifest files as `@InputFiles` on the `@CacheableTask` strip task makes Gradle order
   consumer-strip after producer-strip automatically (manifests live inside the producers'
   `@OutputDirectory`) and keeps the cache key correct across flavour switches (active flavour is
   already an `@Input`; manifest content differs per flavour).
3. **Exclude the own manifest.** Test-like source sets get `srcSet.output.classesDirs =
   project.files(stripTask)` (`JitterPlugin.groovy:143-146`), so their runtime classpath contains
   their own stripped tree; the strip task must skip its own output dir / own manifest name or it
   would depend on its own output (task cycle).
4. **Widen the member set, not the shape check.** `cleanSwitchMaps` matches
   `ctx.result.getRemovedMembers() ∪ ctx.crossModuleRemovedMembers` (new `StripContext` field,
   populated via a new `BytecodeStripper.strip(...)` overload). The javac shape walker is unchanged.
5. **Second shape walker for Kotlin `$EnumSwitchMapping$`.** The Kotlin entry shape differs (local
   var map load, no try-end label), so a dedicated walker handles `$WhenMappings` classes; entries
   for removed constants (own ∪ cross-module) are removed the same way. This also converts the
   current Kotlin behavior (verify fails loudly on leftover `$EnumSwitchMapping$` references) into
   proper cleaning.
6. **Shape mismatch = error, for both shapes and both removal sources.** javac's shape is stable
   across JDKs (ECJ emits no `$SwitchMap$` for enum switches); kotlinc's shape is covered by its
   own walker. Consistent with the project's "fail loudly, never guess" decision: a warning would
   silently keep the leaked name.

## Risks / Trade-offs

- [Risk] New `@InputFiles` on the strip task could create a cycle via the own stripped tree →
  Mitigation: decision 3 — exclude own output/manifest; covered by a gradle-tasks spec scenario.
- [Risk] Producer strip not yet run when consumer strip reads the manifest → Mitigation:
  implicit task dependency via the manifest file input (decision 2); functional test covers it.
- [Risk] Cache poisoning across flavours if manifest input were `@Internal` → Mitigation: declared
  as `@Classpath`/`@InputFiles`, and active flavour remains an `@Input`.
- [Risk] Removing an entry could corrupt frames/branches if the shape is mis-detected →
  Mitigation: strict shape validation, hard error otherwise (decision 6); ClassWriter rebuilds the
  constant pool, so no stale strings remain.
- [Trade-off] External cross-build producer/consumer leaks remain (no manifests in deliverables
  by spec) → accepted: changing that would contradict the stripping spec's "manifests stay out
  of deliverables"; out of scope.

## Migration Plan

No migration — defect fix. Existing builds get strictly cleaner stripped artifacts; the only
behavior change for previously-passing builds is that unparseable switch-map entry shapes (which
today pass verify silently) now fail stripping loudly, by design.

## Open Questions

- None blocking. (Kotlin walker is in scope per the change request; whether kotlinc versions other
  than 2.x emit a different `$EnumSwitchMapping$` shape can be answered by the unit fixtures —
  if a shape is unparseable, the build fails loudly, never silently.)
