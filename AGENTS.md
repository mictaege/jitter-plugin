# AGENTS.md — jitter-plugin

Project context and conventions live in OpenSpec: `openspec/config.yaml` (project context) and
`openspec/specs/<capability>/spec.md` (capability specs). Use the OpenSpec workflow for behavior
changes: propose via `/openspec-propose`, implement via `/openspec-apply-change`, validate with
`openspec validate`, finish with `/openspec-archive-change`. Family-level planning lives in the
store `jitter` (`~/openspec/jitter`, use `openspec ... --store jitter`).

## Read the relevant spec before changing

- Bytecode stripping (150% model, strip tasks, artifact lanes, log prefix): `openspec/specs/stripping/spec.md`
  before changing the stripper (`BytecodeStripper`, `JitterStripTask`, …).
- Verification (dangling references, manifests, stale resources, `jitterVerifyAll`): `openspec/specs/verification/spec.md`
  before changing the verifier (`JitterVerifyTask`, `ConstantAuditor` wiring, …).
- Inlined constants (`@InlinedConstantApproved` audit, `jitterVerifyInlinedConstants`, `@AlterConstValue`):
  `openspec/specs/inlined-constants/spec.md`.
- Resource flavouring (name-suffix, whole-file gates, `jitter:onlyIf`/`jitter:set` component gates):
  `openspec/specs/resource-gates/spec.md` before changing `FlavouredResources` / resource processing.
- Dejittering (source surgery, cross-check, Kotlin isolated parser): `openspec/specs/dejittering/spec.md`
  before changing `Dejitterer` / `JitterDejitterTask` / `IsolatedKotlinDejitter`.
- Gradle surface (extension, source set roles, task family, active flavour): `openspec/specs/gradle-tasks/spec.md`
  before changing `JitterPlugin` / `JitterExtension` / task wiring.
- The gate XML schema itself: `gate-schema` spec in the `jitter` repo.

## Working conventions

- Run `./gradlew test` before submitting changes; for end-to-end validation build and run
  `eval.jitter` with each flavour — it composite-builds this plugin from the sibling checkout,
  no publishing needed.
- Coordinate version bumps with `jitter-api`, since this plugin's correctness depends directly on
  it. Publishing and releasing is done by the author.
- Change spec and code together when a design decision changes — a decision must not be
  accidentally undone or re-litigated.
