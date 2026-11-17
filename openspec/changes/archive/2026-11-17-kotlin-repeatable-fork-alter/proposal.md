# Proposal

## Why

Kotlin source dejittering ignores all but the first repeated `@Fork`/`@Alter` on a declaration
(GitHub issue mictaege/jitter-plugin#49): when the first entry's `ifActive` is not the active
flavour, no fork/alter is applied but the jitter annotations are still removed, leaving a gateway
with its placeholder body plus an orphaned template. The result compiles, so neither the
removal-manifest cross-check nor compilation detects it — while bytecode stripping and the Java
`Dejitterer` resolve the active entry correctly. Repeated `@Fork` is documented, first-class usage
(the README's own `@Fork` example repeats it three times), so the Kotlin dejittering must match
the other two paths.

## What Changes

- `KotlinDejitterer` resolves the *active* entry of a repeatable jitter annotation by iterating
  **all** entries of the annotation and its container (`@Gateway`/`@Alternatives` when written
  explicitly in Kotlin source), taking the one whose `ifActive` contains the active flavour —
  mirroring `Dejitterer.repeatable(...)` / `JitterAnnotations.repeatableAnnotations(...)`.
- Applies to `@Fork` (gateway methods) and `@Alter` (top level classes) in Kotlin sources;
  `@AlterConstValue` already iterates its repeated entries, and its container gets the same
  unwrapping for full parity.
- No behavior change for Java sources, bytecode stripping, or single-annotation Kotlin code.
- Regression tests in `KotlinDejittererTest` covering a repeated `@Fork` where the active flavour
  is the *second* entry (the issue's repro), a repeated `@Alter`, and the explicit container form.

## Capabilities

### New Capabilities

- none — this is a defect fix against existing behavior, not a new capability.

### Modified Capabilities

- `dejittering`: the Kotlin dejittering SHALL resolve repeated `@Fork`/`@Alter` (and their
  containers) per active flavour, with parity to the Java dejittering and the stripped model.

## Impact

- `src/main/java/com/github/mictaege/jitter/plugin/KotlinDejitterer.java` — annotation lookup
  becomes repeatable-aware (new `repeatableAnnotations` helper; `activeAnnotation` iterates it;
  container unwrapping shared with `@AlterConstValue` handling).
- `src/test/java/com/github/mictaege/jitter/plugin/KotlinDejittererTest.java` — new regression
  tests; no existing test changes expected.
- No public API, DSL, or dependency changes; no jitter-api change. Validation via
  `./gradlew test`; optional end-to-end check with eval.jitter per flavour.
