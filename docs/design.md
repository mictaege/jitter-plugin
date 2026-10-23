# Design decisions

This document records the design decisions behind the jitter bytecode
stripping and the reasoning that led to them - the *why*, not the *what*:
what the features do and how to use them is documented in the
[README](../README.md); the operational conventions live in the
`AGENTS.md` files. Read this before changing the stripper, the
verification or the dejittering, so a decision is not accidentally undone
or re-litigated. When a decision changes, change this document together
with the code.

The decisions build on each other; they are ordered accordingly.

## 1. Strip bytecode, not sources

**Decision.** The complete "150% model" of all flavours compiles
untouched. The flavour-specific code is removed from the *compiled
classes* afterwards, by bytecode analysis over the class file format.

**Why.** The former source transformation coupled the build to one
compiler front end and one syntax level per language, doubled the
compilation work (compile, flavour, compile again), rewrote the sources
(the line number tables drifted, hot code replacement broke, the
debugger resolved to generated files), and kept every language feature
behind that front end. The class file format is the stable contract:
every language that compiles to JVM bytecode works, on every syntax
level, because the stripper never looks at the sources.

**Consequences.**
- The 150% model must be self-consistent: the whole program must
  compile in every flavour. Flavour usage that the per-flavour source
  transformation silently tolerated now fails the compile - deliberately,
  it was invisible inconsistency.
- Downstream modules compile against the full model, so the flavour
  semantics apply *after* the compile. Everything that javac bakes into
  the class files is out of reach of the stripping (see the constants
  decisions below).
- A verification pass is needed: the source transformation got its
  consistency for free, because dependent modules compiled against the
  already flavour-specific sources.

## 2. The flavour model is the default build output

**Decision.** What a build produces and publishes by default is the
stripped flavour model. The full 150% model is a secondary artifact.

**Why.** The consumer of a build is the delivery. When the stripped
model hid in a side lane, every consumer had to know about the flavouring
to pick the right artifact; conventions like "what to pick lives in
`build/libs`" broke. Making the flavour model the default keeps the
build jobs and other downstream tooling flavour-agnostic.

**Consequences.**
- Every source set carries two artifacts: the full model as the compile
  artifact (`apiElements` keeps the full classes, so dependent projects
  compile against the 150% model) and the stripped model as the runtime
  artifact (`runtimeElements` resolves the stripped jar). Both are
  outgoing variants of the same component, so they publish under
  distinct classifiers: the flavour model keeps the plain coordinates,
  the 150% model carries a `full` classifier.
- The deliverable lanes follow: `build/libs` holds the flavour model
  under the plain archive names, the 150% model lives in
  `build/full-libs`.
- The removal manifests are build data, not application data: they list
  the flavour-specific classes of the full model, so they stay out of the
  jars and wars entirely. The verification reads them from the stripped
  trees instead (decision 3).

## 3. Verification replaces the lost compile-time consistency

**Decision.** `jitterVerify` (per source set, scoped per artifact lane,
and aggregated) fails the build when surviving code references removed
classes or members - within the module and across module boundaries. The
removal manifests of dependent modules are the cross-module carriers:
sibling modules of the same build are read from their stripped trees,
external artifacts contribute their manifests from the classpath.

**Why.** With sources compiled per flavour, inconsistency could not
occur: dependent modules saw the flavour-specific sources. With the full
model compiled as one program, dangling references surface at *runtime*
without this check. The check is deliberately a separate pass after the
stripping, not part of it: it also validates cached strip results and
the models of dependent modules.

**Consequences.**
- The verification is up-to-date checked like any other task. Its inputs
  are the stripped classes, the processed resources, the removal
  manifests of the dependent projects and the active flavour; its output
  is the stale-resource report. A second build without changes skips it,
  a producer's re-strip re-runs the consumer's verification. The build
  cache stays disabled for it: its value is the up-to-date check, not the
  caching.
- javac's switch map classes (`Outer$N` with a `$SwitchMap$...` field)
  GETSTATIC every enum constant of the switches of the source inside a
  `NoSuchFieldError` handler - such references are tolerated by design,
  because javac intends them to fail at runtime, not at build time.
- The root task `jitterVerifyAll` runs every module's verification and
  every inlined-constants check (decision 5) in one go, as the single
  entry point for a build server.

## 4. Fail loudly, never guess

**Decision.** Where the stripping cannot decide, it fails the build with
a clear error instead of producing a best guess. `@Fork` and `@Alter` are
strict: a missing or inaccessible template fails. Known unsupported
constructs are reported as build errors, not silently tolerated.

**Why.** The source transformation logged the same problems and
silently produced empty flavour methods. Silent degradation is the worst
outcome of a flavouring build: the delivered variant differs from the
intended one with nothing pointing at it. A build failure costs minutes;
a wrong delivery costs trust in every flavour.

**Consequences.**
- The known limitations are documented in the README and surface as
  `jitterVerify` errors, e.g. anonymous inner classes declared inside
  `@OnlyIf` methods, synthetic `access$NNN` orphans and branchy field
  initializers.
- Improving a limitation means making the stripper handle the construct
  correctly - never loosening the check.

## 5. Compile-time constants: audit, review marker, optional mechanical check

**Decision.** javac inlines constant variables - `final` fields of a
primitive or `String` type with a constant initializer - into their
usage sites, so the stripping cannot apply flavour logic there, and the
baked-in values are indistinguishable from handwritten literals. The
constant audit therefore fails the build for every constant variable
whose *existence* is flavour variant (gated on the field, its class or
its package) unless it is marked `@InlinedConstantApproved`. The marker
is the developer's review assertion that every usage sits in code gated
with a subset of the constant's flavours. The opt-in
`jitterVerifyInlinedConstants` check verifies that assertion
mechanically, per source set; `private` constants are included, they
are inlined within their own class like any other constant.

**Why.** There is no way to un-inline a constant after the compile, and
no way to tell an inlined usage from a handwritten literal afterwards -
the problem must be solved at the declaration side. Whether a gated
constant is harmless depends on its usage sites, which only the
developer can review; the plugin can only verify the review. The check
is opt-in because it costs a full usage analysis of the sources; the
build server activates it, the everyday build trusts the review marker.

**Consequences.**
- Alternatives exist for the developer: boxing the type or dropping
  `final` makes the usages ordinary references, which the stripping and
  the verification handle like any other member access.
- A gate that covers all flavours is not flavour variant and needs no
  marker.
- The audit runs on the declaration side, in every flavour run and in
  the full model alike.

## 6. `@AlterConstValue`: flavour-variant values of a constant

**Decision.** For constants whose *value* differs per flavour (while the
constant itself exists in all flavours), `@AlterConstValue` sets the
value per flavour at the declaration site. It works with wrapper types
and `String` only. A class carrying `@Alter` must not declare constant
variables at all.

**Why.** The `@Alter` template mechanism transfers implementations, but
not constant identities: javac inlines the *usages*, so the value must
already be correct in the flavour's own declaration - which is what
`@AlterConstValue` writes. Primitives are excluded for the same physics
as in decision 5: a primitive constant is inlined and cannot be altered
by a post-compile pass; the wrapper types keep the usages as
references. An `@Alter` class with constants would look correct in the
active flavour and leak the full model's values into the other
flavours, so it is rejected outright.

**Consequences.**
- `@AlterConstValue` must cover every visible flavour of the constant;
  the audit verifies the coverage.
- The dejittering applies the active flavour's value in the source
  variant, so a source delivery carries the same constant values as the
  compiled flavour model.

## 7. Resource flavouring: rename, gate the file, gate the components

**Decision.** Resources become flavour-specific through three
mechanisms: the name-suffix convention (`Xyz_<Flavour>`, including
folders and the inverted `!` form) renames a per-flavour variant into
place; a jitter annotation inside an `appinfo` section gates the whole
XML file; and the `jitter:onlyIf` / `jitter:set` gate elements inside a
component's `annotation`/`appinfo` gate single components of an XML
document. The component gates are resolved while the resources are
copied, never afterwards - the copy task's output is already snapshotted
by the time it has run. The gate vocabulary has its own schema, shipped
with the plugin and served at its namespace URL
(`https://mictaege.github.io/jitter`), so IDEs fetch it right from the
`xmlns` declaration.

**Why.** Each mechanism covers what the others cannot. The suffix
convention expresses whole-file variants but duplicates content. Whole
file gates express absence. Component gates express the rest: schema
facets like patterns (a unified schema carries both variants as an OR -
silently the looser one) or cardinalities like `minOccurs` that differ
per flavour, which no generated-class annotation can express, because
they are not part of the class model. The gates sit in the W3C standard
`appinfo` hook, the extension point designed for exactly this, so the
unified document stays valid for every XML tool - code generators
included, they simply ignore the gates. Resolution at copy time makes
the processed resources, the runtime artifacts and the dejittered
variant carry the flavour-resolved document while the unified source
remains the single source of truth.

**Consequences.**
- The resolved document carries no gate residue: the gate elements are
  stripped like the annotations are stripped from the bytecode, and an
  emptied `appinfo`/`annotation` husk is removed. Files without the gate
  namespace pass through unchanged - the mechanism is opt-in per file.
- Code generation reads the unified source and ignores the gates: only
  *members* are flavour-gated through the generated class annotations,
  never facets - the class model is flavour-gated at the bytecode level
  like any other code, the facets live in the runtime schema only.

## 8. Dejittering is source surgery, not decompilation

**Decision.** The `dejitter<SrcSet>` tasks produce the flavour's source
variant by removing the flavour-specific code from the *original
sources* - parse, remove, print - and cross-check the result against the
removal manifests of the stripped model. A decompiler-based variant was
spiked and rejected.

**Why.** A source delivery must read like handwritten code: comments,
formatting and line numbers survive only in the original sources. Even
the best decompiler plus a formatting pass does not reach that - the
spike produced compilable, structurally correct code that no reviewer
would accept as a source delivery, and Kotlin sources would have become
decompiled Java. Parsing and surgery keeps the sources honest and the
diff reviewable.

**Consequences.**
- The dejittered model mirrors the source set layout (sources and
  resources separately) and cross-checks its removals against the
  stripped model: the two mechanisms must agree, a disagreement is a
  build failure, not a silent difference between the delivery and the
  binary.
- The parser runs only on the sources it must change; untouched files
  pass through byte-identical.

## 9. Kotlin: the same stripper, an isolated parser

**Decision.** Kotlin sources carry the same annotations as Java sources
and are stripped by the same bytecode pass - no Kotlin-specific
stripping exists. The Kotlin parser needed by the dejittering is
resolved lazily at task runtime from the repositories of the applying
build and loaded in an isolated classloader; it is never a runtime
dependency of the plugin. A Kotlin-only package needs a Java
`package-info.java` for its flavour gate (`package-info.kt` is a
possible later feature).

**Why.** The stripper couples to the class file format, so every JVM
language works without a language-specific code path. The parser is a
different story: a Kotlin runtime dependency would clash with the
Kotlin toolchain of Kotlin-based builds (two different Kotlin compilers
on one classpath), which is why it loads isolated and lazily - and why
it never becomes a dependency anyone has to resolve, upgrade or
exclude.

**Consequences.**
- Flavour-gating Kotlin works exactly like Java, with the same
  limitations and the same verification.
- The dejittering of Kotlin needs no second mechanism either, only the
  isolated parser.

## 10. Wiring principles

**Decision.** A few rules apply across all tasks of the plugin:

- The active flavour is a task input of every flavour-specific task.
  Flavours switch without a `clean`; up-to-date checks and the build
  cache handle the switch.
- Tests execute against the stripped model: the test tasks depend on
  the verification, so a test run proves the flavour variant, not the
  150% model.
- Build data stays out of the deliverables: no removal manifests in
  jars, wars or publications; the verification reads them from the
  stripped trees.
- Outputs that other tasks consume are declared as task dependencies,
  because Gradle rejects undeclared producer/consumer relationships -
  and correctly so.

**Why.** These are the invariants that make the two-artifact design
(decision 2) trustworthy: the flavour is always declared, the delivered
model is always the verified model, and no task silently consumes stale
output.

**Consequences.** Changing any of these requires re-checking the
up-to-date wiring, the cache keys and the verification inputs together -
they are one design, not independent settings.
