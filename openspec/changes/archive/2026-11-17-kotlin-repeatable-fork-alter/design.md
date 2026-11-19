# Design

## Context

See proposal.md for the motivation (issue mictaege/jitter-plugin#49). Three paths resolve the
active flavour entry of a repeatable jitter annotation, and two of them already iterate all
entries: the bytecode stripper (`JitterAnnotations.repeatableAnnotations` reads the single
annotation plus the `Gateway`/`Alternatives` container the compiler generates) and the Java
source dejitterer (`Dejitterer.activeFork`/`activeAlter` iterate `repeatable(annotations, "Fork",
"Gateway")`). Only `KotlinDejitterer` looks at the first entry: `annotationOf` returns the first
`KtAnnotationEntry` with a matching simple name and `activeAnnotation` checks only that entry's
`ifActive`. Cleanup is not affected — `cleanJitterAnnotations` already removes every jitter
annotation entry, and `@AlterConstValue` already loops all its entries.

Constraint: the Kotlin PSI of kotlin-compiler-embeddable only reads structure; all edits are
offset-based text surgery, and the fix must not change that model. The Kotlin compiler stays a
lazily resolved, isolated runtime dependency.

## Goals / Non-Goals

**Goals:**
- Kotlin dejittering picks the active `@Fork`/`@Alter` entry among all repeated entries and among
  the entries of an explicitly written container, with first-active-wins semantics identical to
  the Java dejitterer.
- Zero behavior change for single annotations, Java sources, bytecode stripping, and the
  no-flavour mode.

**Non-Goals:**
- No changes to jitter-api, the annotation contract, the Gradle DSL, or the stripper/verifier.
- No new error cases: multiple entries matching the active flavour stay silently first-wins
  (parity with Java), and a missing template keeps the existing build error.
- No end-to-end change in eval.jitter required; unit tests in `KotlinDejittererTest` are the
  acceptance vehicle.

## Decisions

- **One shared repeatable lookup in `KotlinDejitterer`, mirroring `Dejitterer.repeatable`.**
  A new `repeatableAnnotations(ctx, decl, single, container)` collects all annotation entries named
  `single` and, for an entry named `container`, the nested annotation entries of its array/collection
  argument. `activeAnnotation` then iterates this list and returns the first entry whose `ifActive`
  matches the active flavour; it still returns `null` when no flavour is active. `activeFork` and
  `activeAlter` call it with `("Fork", "Gateway")` / `("Alter", "Alternatives")`, and
  `applyAlterConstValues` uses it with `("AlterConstValue", "AlterConstValues")` so the container
  form works there too.
  *Alternative considered:* fix only `activeFork`/`activeAlter` and leave `@AlterConstValue` as is.
  Rejected: the container form of `@AlterConstValue` would stay silently broken, and a shared
  helper is the same code either way.
- **Container unwrapping in Kotlin PSI.** In Kotlin source, repeated Java `@Repeatable` annotations
  appear as separate `KtAnnotationEntry`s (the issue's repro proves the compiler accepts them);
  the container only appears when written explicitly as `@Gateway([Fork(...), Fork(...)])`. The
  helper descends into the container entry's value argument (a collection literal) and collects
  the nested annotation entries. The exact PSI navigation of that argument is pinned down by a
  unit test with the explicit container form rather than by guessing the node types here.
  *Alternative considered:* ignore the explicit container form as unidiomatic Kotlin. Rejected:
  the Java dejitterer supports it and the spec requires parity; the container is legal Kotlin.
- **Keep the two-pass walk unchanged.** Both the template pre-consumption pass and the
  application pass in `walk()` go through `activeFork`, so fixing the lookup fixes both at once;
  no restructuring of the edit collection is needed.

## Risks / Trade-offs

- [Risk] The container argument's PSI shape (collection literal vs. single value) differs from the
  assumed one → Mitigation: unit tests cover the explicit container form for `@Fork`, `@Alter` and
  `@AlterConstValue`; if a single-element container parses differently, the helper treats a lone
  nested annotation as a one-element list.
- [Risk] Silent wrong output stays possible in principle (the original bug compiled fine) →
  Mitigation: the spec's cross-check scenario requires the source variant to agree with the
  stripped model for every flavour; the regression tests dejitter for the *second* flavour,
  which is the case that used to slip through.
- [Trade-off] First-active-wins keeps parity with Java but hides duplicate matching entries →
  accepted deliberately; making it an error would diverge from the Java dejitterer and the
  stripper.

## Migration Plan

No migration: this is a defect fix in a build plugin. Users on jitter-plugin 2026.4 get the fix
by upgrading the plugin version; no source changes in consumer builds. Rollback is downgrading
the plugin.

## Open Questions

- None blocking: the only unknown is the exact PSI node types of a container's array argument,
  which the implementation resolves with the container-form unit tests.
