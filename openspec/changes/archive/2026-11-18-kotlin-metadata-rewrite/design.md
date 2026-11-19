# Design

## Context

See proposal.md for the motivation (issue mictaege/jitter-plugin#49's sibling #48). Verified in
the code: `JitterAnnotations.ALL` does not include `Lkotlin/Metadata;`, so `AnnotationCleaner`
leaves it alone; `@kotlin.Metadata` is Java RUNTIME retention, so ASM sees it in
`visibleAnnotations` — detectable with plain ASM. The strip pipeline (`BytecodeStripper.strip`)
runs its phases over a shared `StripContext` and `StripResult` already records exactly what was
removed: `removedClasses` (internal names, nested classes via the `Outer$…` prefix rule in
`OnlyIfRemover.removeClass`) and `removedMembers` as `owner.name+descriptor` keys (fields,
methods, constructors, lambdas; enum constants are fields). `ReferenceVerifier` only visits code
references, so annotation payload strings are invisible to it — hence the leak. The plugin keeps
all `org.jetbrains.kotlin.*` jars off its runtime classpath on purpose (see the
`kotlin-compiler-embeddable` comment in build.gradle.kts): they resolve lazily via a detached
configuration from the applying build's repositories and load in a `ChildFirstClassLoader`
(`IsolatedKotlinDejitter`, `IsolatedKotlinUsageFinder`).

## Goals / Non-Goals

**Goals:**
- After stripping, a Kotlin class's `@kotlin.Metadata` describes exactly the stripped model; the
  `d2` table no longer contains strings only the removed declarations used.
- Untouched Kotlin classes stay byte-identical; the no-flavour 150% model is untouched.
- `jitterVerify` fails the build when surviving metadata still references removed code.
- No Kotlin library becomes a runtime dependency of the plugin; nothing changes for
  Java/Groovy classes or the removal manifests.

**Non-Goals:**
- Rewriting metadata of classes that had nothing stripped (byte-identical passthrough).
- A full parsed-model comparison on the verify path — the verify check stays ASM-level.
- Changes to dejittering (source deliveries are already clean), to jitter-api, or to the
  manifest format.
- eval.jitter source changes; it is used for end-to-end validation only.

## Decisions

- **Approach A (rewrite the metadata with kotlin-metadata-jvm), not B (drop the annotation).**
  Dropping `@kotlin.Metadata` from stripped classes is one ASM line but destroys the Kotlin view
  (nullability, properties, default arguments) for exactly the classes that carry flavour
  variance — kotlin-reflect/jackson/Spring would degrade on them. Rewriting keeps the view
  consistent with the bytecode. B stays documented as the fallback only if A proves
  unworkable.
- **Same lazy, isolated dependency pattern as the Kotlin compiler embeddable.**
  `kotlin-metadata-jvm` is `org.jetbrains.kotlin.*` and must not sit on the plugin classpath:
  `build.gradle.kts` gets it as `compileOnly` (+ `testImplementation`), `JitterUtil` gets a
  coordinate constant, `JitterPlugin` wires a detached configuration onto a new
  `kotlinMetadataJars` property of `JitterStripTask` (mirroring `kotlinCompilerJars` on the
  dejitter task), and a `KotlinMetadataRewriter` runs behind a child-first classloader wrapper
  mirroring `IsolatedKotlinDejitter` (plugin types cross parent-first, the rewriter and the
  metadata lib child-first). *Alternative:* plain `implementation` dependency — rejected: same
  class-clash rationale as the compiler embeddable.
- **One new phase at the end of the strip pipeline**: `KotlinMetadataRewriter.rewrite(ctx)`
  after `AnnotationCleaner.clean(ctx)`, before `ClassFileWriter.writeBack`. All removal phases
  have run by then, so the metadata sees the final model. Only classes in `ctx.dirty` (or whose
  `d2` mentions a removed class — cheap pre-check) are rewritten; everything else passes through
  byte-identical.
- **Removal mapping onto the metadata model**: for each class carrying `Lkotlin/Metadata`,
  `KotlinClassMetadata.readStrict` → the `Km*` model; drop `KmFunction`/`KmConstructor` whose
  (name, descriptor) matches a `removedMembers` key of that owner (via the
  `JvmExtensionsKt.getSignature` extension — `JvmMethodSignature(name, desc)`), `KmProperty`
  whose field signature matches a removed field of that owner, enum entries via
  `KmClass.kmEnumEntries` (NOT the deprecated `enumEntries` name list — the writer only honors
  `kmEnumEntries` when non-empty; spike-verified), nested classes and `sealedSubclasses` entries
  from `removedClasses`. Writing back rebuilds `d2`, which removes the orphaned strings — the
  issue's core expectation. Kinds k=1..5 each map to their `KotlinClassMetadata` subtype
  (`Class`/`KmClass`, `FileFacade`/`KmPackage`, `SyntheticClass`, `MultiFileClassFacade`,
  `MultiFileClassPart`).
- **Spike results (task 1.1, kotlin-metadata-jvm 2.4.21)**: `readStrict` + mutate + reconstruct
  via `new KotlinClassMetadata.Class(km, originalVersion, originalFlags)` +
  `setAllowedToWrite(true)` + `write()` preserves the original `mv` exactly (written `mv` ==
  read `mv`) and rebuilds `d2` without the removed declarations' strings (verified by
  re-reading the written annotation: model and `d2` are clean). The `kotlin.Metadata` ↔ ASM
  `AnnotationNode` conversion is a flat name/value list (`k`, `mv`, `bv`, `d1`, `d2`, `xs`,
  `pn`, `xi`). Enum entries must be removed from `kmEnumEntries` (mutating only the deprecated
  `enumEntries` list has no effect on write — verified).
- **Verify check stays ASM-only**: a new check in the verify pass reads each surviving class's
  `@kotlin.Metadata` via ASM (`visibleAnnotations`, `d2` string array) and fails when it
  contains a removed class's descriptor (`L<internalName>;`, or a plain internal name as used
  for sealed subclasses) or — for the class's own metadata — a removed member name of that
  owner. No Kotlin library on the verify path.
  *Alternative:* parse the metadata fully with the isolated lib in the verify task — rejected
  for now: heavier, and the string-level check is precise for class descriptors and scoped for
  members.
- **Verify check tolerates what the bytecode still carries - decided after the ologis e2e
  (task 4.3)**: `ReferenceVerifier` only visits instructions, so declaration-level references
  to removed classes (a surviving field/constructor parameter of a removed type, e.g.
  `ScriptDTO.scriptType: ScriptType` in ologis, where the `scriptengine` package is gated) are
  legitimate and pass the code-level check. The metadata check SHALL mirror that: a `d2`
  reference to a removed class or member is a violation only when the class's own bytecode no
  longer references it either (collected from field/method descriptors, signatures, inner
  classes, super/interfaces and all instruction operands outside annotations). Only
  metadata-only leftovers fail the build — exactly the "metadata keeps declarations that were
  stripped from the bytecode" case of the issue. The strip-side rewrite is unchanged: it
  removes exactly the declarations the manifest lists, nothing more.
- **`mv` (metadata version) handling - decided: preserve the original `mv`.** The rewrite SHALL
  write each class back with the `mv` it was read with, so a consumer Kotlin compiler older than
  the metadata library can still read the stripped class. The spike (task 1.1) only confirms
  that kotlin-metadata-jvm's writer can emit a chosen metadata version; if the API turns out not
  to allow it, the fallback is resolving the metadata library in the consumer's Kotlin version.
- **Fully-stripped file facades - decided: delete them with their class file.** When every top
  level declaration of a Kotlin file (facade kinds k=2/4/5) is removed, the facade's class file
  SHALL be deleted and recorded as a removed class in the removal manifest, exactly like any
  other removed class; the metadata rewrite never resurrects facade content.

## Risks / Trade-offs

- [Risk] `mv` write-back incompatibility with older consumer compilers → Mitigation: spike first
  (task 1.1); preserve original `mv` if the API allows; otherwise pin the library to the
  consumer's Kotlin version. Until resolved, this is the one item that could change the approach.
- [Risk] Property/getter mapping mismatches (a removed property's getter removed too, or a
  same-named function surviving) → Mitigation: unit tests with Kotlin fixtures covering
  properties, const vals, enum entries, overloaded functions; match by name AND descriptor.
- [Risk] kotlin-metadata-jvm is not in the local Gradle cache — the first build of this change
  needs network once → Mitigation: note it in tasks; CI has network.
- [Risk] Performance: parse+write per affected class → Mitigation: only dirty/metadata-touching
  classes are rewritten; stripping is a build-time pass.
- [Trade-off] The verify check is string-level, not a parsed-model comparison — it can miss a
  reference that survives only inside the `d1` protobuf → accepted: `d2` carries all names and
  descriptors; `d1` is positional. The strip-side rewrite is the authoritative fix.

## Migration Plan

No migration: defect fix in a build plugin. Users get it by upgrading the plugin version. Rollback
is downgrading the plugin; the fallback (B) can be shipped behind a flag if A regresses in the
field.

## Open Questions

- None remaining: the two earlier open questions are decided above — preserve the original `mv`
  on write-back (spike-confirmed possible via the `(km, version, flags)` writer constructor plus
  `setAllowedToWrite(true)`), and delete fully-stripped file facades (k=2/4/5) together with
  their class file. Remaining mapping details are pinned by the unit tests in tasks 1.2/2.2.
