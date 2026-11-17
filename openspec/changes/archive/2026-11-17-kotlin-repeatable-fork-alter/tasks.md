# Tasks

## 1. Regression tests (red first)

- [x] 1.1 Add `KotlinDejittererTest` cases for repeated `@Fork` on a Kotlin gateway where the active flavour is the **second** entry (the issue #49 repro) and where it is the first entry, asserting the gateway body, the consumed template and the removed `@Fork` annotations; verified with `./gradlew test --tests KotlinDejittererTest` that the second-entry case failed before the fix (placeholder body + orphaned template).
- [x] 1.2 Add `KotlinDejittererTest` cases for repeated `@Alter` on a top level Kotlin class dejittered for the second flavour, and for the explicit container forms `@Gateway([Fork(...), Fork(...)])` / `@Alternatives([...])` / `@AlterConstValues([...])`; verified with `./gradlew test --tests KotlinDejittererTest` that all 5 new cases failed before the fix.

## 2. Repeatable-aware lookup in KotlinDejitterer

- [x] 2.1 Add a `repeatableAnnotations(decl, single, container)` helper to `KotlinDejitterer` that collects all entries named `single` plus the nested entries of an explicit container's collection-literal argument (annotation entries or call expressions), and make `activeAnnotation` iterate it, returning the first entry whose `ifActive` matches the active flavour (`null` when no flavour is active); wired `activeFork`/`activeAlter` to `("Fork", "Gateway")` / `("Alter", "Alternatives")`; verified the task 1.1 fork cases pass with `./gradlew test --tests KotlinDejittererTest` (15/15 green).
- [x] 2.2 Route `applyAlterConstValues` through the same helper with `("AlterConstValue", "AlterConstValues")` so the container form resolves too; verified the container cases of task 1.2 pass with `./gradlew test --tests KotlinDejittererTest` (15/15 green).

## 3. Full verification

- [x] 3.1 Run the whole suite `./gradlew test` and confirm no regressions in `DejittererTest`, `KotlinDejittererTest` and the stripper/verifier tests; verified: 257 tests, 0 failures, 0 errors, BUILD SUCCESSFUL.
- [x] 3.2 End-to-end validation: ran `./gradlew dejitterModel -Djitter.active.flavour=<F>` in eval.jitter (composite-builds this plugin) for ESA, NASA and ROSKOSMOS - each dejittered `KotlinGadgets.kt` correctly, annotations cleaned. Adding a repeated `@Fork` to an eval.jitter Kotlin source was not done: eval.jitter is outside this change's allowed edit roots, and the repeated-`@Fork` path is covered by the `KotlinDejittererTest` regression cases.

## Workflow follow-up

- Archive the change (`openspec archive`) after review, syncing the delta into `openspec/specs/dejittering/spec.md`.
