# Bytecode Stripping Prototype (feature/bytecode)

Status: prototype validated against `eval.jitter` (all flavours build, test, run).
This file carries the context of the prototype session - delete it if it outlives
its usefulness.

## What this branch does

Replaces the Spoon/JAP *source transformation* with post-compile *bytecode
stripping*: the 150% model is compiled untouched, then flavour-specific code is
removed from the class files with ASM.

Motivation: source transformation doubles the build work, keeps hot code
replacement from working, and couples the build to Spoon's Java syntax support.
Bytecode stripping couples only to the class file format (works across Java
11-26 and all javac-based JDK vendors; ECJ is out of scope for now).

## How it works

- Stripping is wired into the `classes` task of each source set:
  `jitterStrip<SrcSet>` runs after the compilation of its own source set, and
  every consumer of the source set output (downstream source sets, jars,
  wars, tests, run tasks) depends on the `classes` task, so it only ever sees
  the stripped classes - the same consistency model the source transformation
  had. The strip task must NOT be ordered after the whole project's
  compilation: the Kotlin Gradle plugin compiles downstream source sets
  against the jar of the main source set, and a `mustRunAfter` on all
  `JavaCompile` tasks creates a circular dependency with that wiring.
- Per source set there is a scoped `jitterVerify<SrcSet>` task that checks
  only the classes of that source set against its own removals. `jar`/`war`
  depend on `jitterVerifyMain` (an artifact task must never wait for the
  compilation of a source set that consumes the artifact). Tests and
  `JavaExec` tasks depend on the aggregate `jitterVerify` task, which checks
  all source sets of the project against all removals.
- `BytecodeStripper` (package `com.github.mictaege.jitter.plugin`, ASM tree API):
  - `@OnlyIf`: removes classes, packages (package-info), fields, methods
  - `@Fork`: replaces the method body with the template method's body, deletes
    the template (original name/modifiers kept)
  - `@Alter`: renames the template class into the target slot
    (`ClassRemapper`, visibility ACC_PUBLIC transferred from the target,
    template deleted - note: unlike Spoon, templates do NOT leak into the output)
  - javac parity for constructors: when all explicit constructors of a
    class are flavour specific, the flavour model gets the implicit
    default constructor javac would have generated for the recompiled
    source model (same access as the class, empty body with `super()`) -
    only for classes whose constructors were actually removed, never for
    Kotlin compiler generated ctor-less helpers (`$WhenMappings`,
    `$DefaultImpls`, `$1`), and not for non-static inner classes (their
    implicit ctor carries the outer instance)
  - Enums: constant init blocks removed from `<clinit>`, surviving ordinals
    renumbered, `$values()` array rebuilt (old-style `$VALUES` in `<clinit>`
    supported as well)
  - Lambdas: `lambda$<removedMethod>$*` synthetic methods removed
  - Inline field initializers of removed fields removed from constructors
    and `<clinit>` (backward stack-effect scan). Labels are treated as
    transparent markers, so multi-line initializer expressions (builder
    chains etc.) are removable; a block is only removed when it is
    self-contained (no branch into it, no exception/local-variable range
    crossing it). Branchy initializers (ternaries etc.) still bail out and
    are reported. Initializers with lambdas and method references are
    covered (2026-10-10, ologis `DataFix` pattern): `invokedynamic` stack
    effects, javac's bound-method-reference `DUP + requireNonNull + POP`
    null-check pattern and straight-line arithmetic/conversions are
    handled, and the initializer's synthetic `lambda$new$N` methods are
    removed with it (transitively - lambdas nest, and their bodies may
    reference removed classes), registered for the
    `$deserializeLambda$` cleanup like removed-method lambdas.
  - Orphaned synthetics removed (fixpoint sweep, reference counting over the
    surviving bytecode): anonymous inner classes, switch map classes and
    enum-constant-body classes (`Outer$N`) and `access$NNN` methods whose only
    purpose was serving removed code
  - Shared switch map classes survive (the surviving forked method body still
    uses them), but their init entries for removed enum constants are removed
    including the `NoSuchFieldError` handler rows. Entries for constants of
    other modules are left alone; the handler tolerates them at runtime.
  - jitter annotations stripped from surviving bytecode (Spoon parity)
  - Writes in place, deletes emptied directories
- `ReferenceVerifier` + `jitterVerify`: fails the build if any surviving class
  still references removed classes/members. This replaces the compile-time
  consistency check that the source transformation got for free.
- Flavour selection is unchanged (`flavourXyz` tasks / `systemProp.jitter.active.flavour`).
- Resource handling (`ProcessResourcesListener`, critical terms tasks): the flavour
  suffix convention is unchanged; in addition resources can now be flavour gated
  via their own content (see "Annotation-gated resources" below).
- The old Spoon processors remain in the tree but are no longer wired.

## Semantics to keep in mind

- Line number tables of surviving code are untouched (better than Spoon, which
  needs blank-line padding).
- Enum ordinals are renumbered exactly like a recompile would.
- `@Alter` keeps the template's `SourceFile` attribute, so the debugger
  resolves swapped classes to the template's source file.

## Known limitations (all produce clear `jitterVerify` build errors)

- local classes (`Outer$1Local`) declared inside `@OnlyIf` methods are not
  auto-removed
- branchy field initializers (ternaries etc.) bail out and are reported
- a non-standard jar task that packages main classes and is consumed by the
  same module's test/testFixtures compilation would reintroduce the task
  graph cycle (standard `jar`/`war` are handled via the scoped verify)

## Validation performed (eval.jitter)

- `flavourESA clean build`, `flavourNASA clean build`, `flavourROSKOSMOS clean build`,
  plain `clean build` (150% model) - all successful, zero dangling references
- `flavourNASA clean run` - the stripped NASA variant starts and runs
- Parity vs Spoon output: identical class inventory (except Spoon leaks template
  classes, this branch does not), identical `Dao` content, no annotations,
  exact line numbers; ESA build ~14s vs ~29s with Spoon
- Plugin test suite: 39 tests green (incl. executed stripped enums, lambda,
  field-initializer, orphaned-synthetic and switch-map scenarios)

## Test against a large code base (ologis, 2026-10)

**Rule: never commit anything in the ologis repository.** All wiring for the
bytecode-stripping test stays as uncommitted working-tree state.

ologis: ~15.5k Java files, 20 modules, mixed Kotlin/Java, Gradle 9.8.0,
jitter annotations in 2,785 files (6.9k `@OnlyIf`, 1.5k `@Fork`, 18 `@Alter`),
3 flavours. The plugin was substituted into the consumer build via
`includeBuild` in the consumer's `buildSrc/settings.gradle.kts` (works; the
included plugin builds under Gradle 9.8 with deprecation warnings only).

### Bugs found and fixed (validations on real ologis code)

1. **Task graph cycle** (Kotlin projects): the Kotlin Gradle plugin compiles
   test/testFixtures against the main jar. `jar -> jitterVerify -> jitterStrip*
   -> compileTest* -> jar` cycled. Fixed with the `classes`-task wiring and
   scoped `jitterVerify<SrcSet>` tasks described above.
2. **Orphaned synthetic companions**: `@Fork` templates with enum switches
   (mainstream in ologis) leave switch map classes behind; the reference
   counting fixpoint sweep now removes them.
3. **Shared switch map classes**: javac generates one `Outer$N` switch map
   class per enum, shared by all switches over it - including removed
   templates. The entries of removed constants are now cleaned (validated on
   `PaymentType$1`/`CustomerPaymentType$1`: zero removed-constant references,
   exception table rows reduced accordingly).

Validated end to end on `shared/shared-dto` (131 annotated files, 375 class
files, flavour Dba): compile -> strip -> scoped verify -> jar, all green.

### compileAll inventory (2026-10-07, flavour Dba, prototype wired in)

`./gradlew compileAll --continue`:

- **Stripped and verified green**: `shared-base`, `shared-dto`,
  `acceptancetest:base/stories/process`, `ologis-itest-bdd`,
  `ologis-mpk-cc-itest-bdd` (all `jitterStripMain` + `jitterVerifyMain`).
- **Failed (javac, both the cross-module flavour visibility issue)**:
  - `:backend:ologis-api:compileJava` (~62 errors)
  - `:frontend:ologis-ui-base:compileJava` (incl. "Package
    `com.zitecs.ologis.mauve.s3.v1.connectivity` ist nicht vorhanden")
- Everything downstream of the two failing compiles was skipped - this is the
  first wall, not the complete inventory.

Missing symbols by pattern (all referenced from gated code or ungated
import statements that Spoon removed per flavour):

1. static-imported gated enum constants: `Brand.MPK`, `MEDIHERZ`,
   `PREISAPO`, `MPK_FS`, `MPK_BS`, `ZALANDO`, `WIR_LBN`, ... (e.g.
   `BrandSetToIntAttributeConverter`)
2. static-imported gated settings constants:
   `OmSystemSettings.TRACKING_MHP_*`, `TRACKING_DHL_*` (e.g.
   `ShipmentProviderCfgKey`, `SiteTransferOrderItem`)
3. gated classes of shared-dto: `VKPriceImportErrorReason`,
   `SiteTransferReceiptDiffReason`, `ScriptType`, `AccountIdentification`
4. gated package of shared-base (BMX): `MauveSystemSettings` - ungated
   import in `ServiceDataBuilder`, used only inside `@OnlyIf(BMX)` methods
5. gated UI constants: `BMX_EH_ROBOT_ZERO/ONE`, `MPK_*_ROBOT_*`

All of these are instances of blocker "Multi-project flavour visibility"
above: javac compiles the downstream module's full source against the
already-stripped upstream jar.

### Blockers found (ologis semantics, not plugin bugs)

1. **ologis's 150% model is not self-compilable.** The source transformation
   produced a self-consistent source universe per flavour; the union of all
   flavours is not valid Java, so "compile the 150% model untouched" cannot
   work for this code base. Concrete javac failures against the full model:
   - `BmxCustOrderShippingAttrAdapter` (`@OnlyIf(BMX)`) does not implement
     `setFixedShipmentProvider` (`@OnlyIf({MPK, DBA})`) of the interface it
     implements - consistent per flavour, inconsistent in the 150% model.
   - `AccountFinder` (`@OnlyIf(MPK)`) imports `Account.AccountType`, a nested
     type that does not exist in the 150% model at all (it compiles in some
     flavour's transformed universe or not at all).
   - Static imports of flavour-gated enum constants (`import static Brand.*`)
     resolve eagerly in javac and fail once the constant is removed from the
     upstream jar; Spoon removed the import lines with the gated code.
   Adopting bytecode stripping here requires making the 150% model
   self-compilable, i.e. a deliberate source migration in the consumer.
2. **Multi-project flavour visibility.** Downstream modules' full sources
   reference upstream members that other flavours remove, so downstream
   compilation needs the full upstream model, while downstream tests and
   packaging need the stripped upstream. The design this implies: producers
   expose a full-model artifact for compilation (e.g. classes dirs via
   `apiElements`) and a stripped artifact for runtime (jar built after
   stripping); stripping runs after all consumer compilations. Not yet
   implemented.
3. **Critical terms**: consumers configure `includes =
   ['generated-sources/spoon/main/**', ...]`; with bytecode stripping the
   spoon tree never exists and the source part of the check silently scans
   nothing. Consumers need to retarget the includes (e.g. at the class files)
   or the plugin needs a bytecode-scanning mode.

### Full-model measurement (2026-10-07): making the ologis 150% model self-compilable

To size the source migration for the full-model-compile-artifact design,
stripping was disabled in all ologis modules (via `jitter.excludeSrcSets`,
never committed) so javac compiled every module's full source against the
full upstream model. After the fixes below, `./gradlew compileAll --continue`
passes completely (backend incl. WAR assemble, frontend, acceptancetest,
integration tests - zero errors).

**Source fixes (13 modified files + 1 new file, all uncommitted):**

1. Flavour rot - stale references in code of flavours that are not actively
   built (MPK builds were already broken under the released Spoon plugin):
   stale nested-type imports (`Account.AccountType`,
   `Account.AccountLocationType` - AccountFinder, BasicDebitorScenario),
   stale jaxb package paths (`peem.connectivity.sitefs.*` for classes that
   live in `peem.connectivity` - 6 files in mpk-cc-contracts), dead imports
   (`CancellationReason.PICKING_CHECK`, undeclared `info.kiedrowski.mpt`
   static import in 2 ADG tests), conflicting duplicate hamcrest import
   (AccountDaoITest).
2. Flavour-inconsistent interface/implementer pairs - gated interface
   methods without an implementation in classes that survive other
   flavours: `BmxCustOrderShippingAttrAdapter` (1 method),
   `MfrConnector` (11 methods), `PeemSystemConnector` (1 method),
   `DbaWmsConnectivityTestHelper` (13 methods). Each got an
   `@OnlyIf`-annotated implementation with the flavour set of the
   interface method, so every flavour output stays byte-identical to
   today.
3. Two public top-level classes in one file (`StockLocation` inside
   `MdFlowIdentifier.java` - only compilable when the flavour dropped one
   of them); moved to its own file.

**Structural items (build-level, not line-fixable):**

1. Flavour-variant jaxb generation: `mpk-cc-contracts` generates message
   classes from flavour-variant XSDs (`*_Mpk.xsd`), which the
   flavour-aware resource pipeline drops in the active flavour. The
   sitefs message classes can therefore only exist in one flavour's
   generated set. The full model needs all variants generated (worked
   around with a never-committed copy step in `buildJaxb.gradle`; the
   design for the real solution is open - package-scoped variants or
   flavour-independent schemas).
2. xjc-generated `package-info` classes collide with the flavour-gated
   ones from main when the jar packs all source set outputs (worked around
   with `duplicatesStrategy = EXCLUDE`).

**Cost assessment**: the line-fixable part is small and mechanical (13
files, most of them one import or a handful of annotation-tagged stub
implementations). The two structural items need a design decision. All
fixes live as uncommitted working-tree state in ologis for review.

## Option 1 proven: union schemas + @OnlyIf-annotated jaxb members (2026-10)

The flavour-variant jaxb generation was made 150%-compilable on the
`OrderInsertMessage` family of `ologis-mpk-cc-contracts` (the family with the
largest schema differences). ologis already uses the jaxb2 `annotate` plugin
via XSD `appinfo` (`annox:annotatePackage` -> package-info `@OnlyIf`) for
package-level gating; the spike extends the same mechanism to members:

- **Union schema** (`src/jaxb/schema/OrderInsertMessageUnion.xsd`): base
  members plus `rx_flag` (Bmx-only); `print_ret`/`print_ship` are base-only
  (base is active in Mpk/Dba). Patterns are unioned; requiredness and
  cardinality differences have no Java-shape impact. Generation becomes
  flavour-independent for this family; the flavour-variant resource XSDs
  remain the runtime validation contracts, untouched.
- **Annotated binding** (`OrderInsertBindingUnion.xjb`):
  `annox:annotatePropertyField/Getter/Setter` with
  `@OnlyIf("Bmx")` / `@OnlyIf({"Mpk", "Dba"})` on the flavour-specific
  members. Values are the flavour CONSTANT VALUES ("Mpk"), not the constant
  names ("MPK") - annotations inline constant values.
- **Resulting shapes** (verified with javap on the stripped classes):
  - Dba: whole generated package stripped (existing package gate {Mpk, Bmx})
    - identical to the Spoon-era Dba jar (only sitefs classes)
  - Mpk: `OrderInsertImpl` without `rxFlag`, with `printRet`/`printShip`
  - Bmx: `OrderInsertImpl` with `rxFlag`, without `printRet`/`printShip`

### Findings

1. **Annox syntax** (`org.jvnet.jaxb:jaxb-plugin-annotate` 4.0.2): the
   annotation is a TEXT child of `annox:annotate*`, parsed as a Java
   annotation expression (`@com.github...OnlyIf("Bmx")`); children in the
   annox namespace are rejected by xjc's binding grammar.
2. **Property removal needs all parts tagged**: field, getter and setter,
   otherwise the stripper leaves dangling halves.
3. **Intra-module full-model visibility**: the jaxb source set is upstream
   of main (via `main.compileClasspath += jaxb.output`) and main's full
   source references the generated classes - so the jaxb strip must run
   AFTER the consuming compilations, not after its own compile (the
   default `classes`-task wiring). The spike wires this manually in
   `buildJaxb.gradle` (late strip tasks + jar/test dependencies); the
   plugin needs a supported option for this case. The same argument applies
   to jaxbHelper (flavour-gated interfaces implemented by the generated
   classes).
4. **Kotlin caveat**: `compileTestKotlin` consumes the main jar (KGP), so
   late stripping cannot wait for it without an ordering cycle - Kotlin
   test code sees the stripped generated classes. Matches the documented
   ologis test convention (tests of gated code must be gated).

### Open items

- Roll out to the remaining variant families (`ArticlesMessages`,
  `CloseGoodsInOrderIdMessage`, `OrderRepack` (rename case), the
  `mfr2022` family in ologis-mfr) - mechanical.
- `ObjectFactory`/`ObjectFactoryTest` in Dba: the ungated generated factory
  disappears with its gated package; ungated tests referencing it must
  follow the ologis test rule (gate the test) or the factory needs explicit
  handling.
- Plugin support for late stripping (e.g. a `jitter` extension option)
  instead of the manual `buildJaxb.gradle` wiring.
- Enum values: no flavour-variant XSD contains enumerations today. If one
  appears, prefer the established `jaxb:javaType` mapping to a handwritten
  annotated enum (the `XmlPaymentType` pattern); a generated enum would use
  `annox:annotateEnumConstant`, which the plugin supports and the stripper
  handles (enum constant removal + renumbering + switch-map cleaning).

### Relocation to the original locations (2026-10)

The union schema and the flavour tags moved to the original file locations
of the flavour-variant family (no separate src/jaxb/schema directory
anymore):

- `OrderInsertMessage.xsd` (resource, old location) now contains the union
  of the former base and `_Bmx` schemas; `OrderInsertMessage_Bmx.xsd` is
  deleted. Generation and runtime validation both use this schema - the
  flavour-specific leniency of the removed optional attributes is
  negligible.
- `OrderInsertBinding.xjb` (old location) carries the annox rules:
  `annox:annotatePropertyField/Getter/Setter` with `@OnlyIf` for
  `rx_flag` (Bmx only) and `print_ret`/`print_ship` (Mpk and Dba only).
  Schema appinfo does NOT work for property-level annox customizations on
  inline attributes (only for schema/package level) - the binding file is
  the right place.

Re-verified flavour matrix after relocation (javap on the stripped
`OrderInsertImpl`): Dba - package stripped, nested sitefs survives; Mpk -
no `rxFlag`, `printRet`/`printShip` present; Bmx - `rxFlag` present, no
`printRet`/`printShip`.

Along the way the stripper gained a bugfix: package removal used to delete
nested packages as well (prefix match); a package gate now covers only its
direct classes, as under Spoon.

### Full unification + plugin support (2026-10)

All flavour-variant XSD families of `ologis-mpk-cc-contracts` are unified; the
consumer build carries no stripping knowledge anymore.

**Plugin** (`f355c20`):
- `jitter { lateStripSrcSets = [...] }`: source sets whose classes other
  source sets compile against are stripped after the main compilation and
  before jar/war/test/run (replaces the hand-wired late strip tasks).
- Class removal cascades to nested classes (`Outer$*`).

**Consumer XSDs** (all in the original locations, variants deleted):
- `ArticlesMessages.xsd`: union; `verifyable` tagged `@OnlyIf("Mpk")` in
  `ArticleInsertBinding.xjb`; package gate `@OnlyIf({Mpk, Bmx})` relocated
  from the deleted `_Dba` variant.
- `CloseGoodsInOrderIdMessage.xsd`: union; package gate relocated from the
  deleted `_Dba` variant.
- `OrderRepackMessage.xsd`: union carrying both attribute sets; renamed
  attributes tagged in `OrderRepack.xjb` (`box_number`/`carton_number`
  {Mpk, Dba}, `source_tu`/`dest_tu` {Bmx}).
- Sitefs `_Mpk.xsd` stay as runtime resources; their binding references the
  `_Mpk` filenames and generation reads the schemas from
  `src/main/resources`, which makes generation flavour independent.

**Remaining consumer changes** (generation/packaging, not stripping):
`buildJaxb.gradle` keeps three items - `jaxb extLibs.jitter.api` (annox
resolves @OnlyIf at generation time), xjc schema/binding scan from
`src/main/resources`, and `jar { duplicatesStrategy = EXCLUDE }` (replaces
the model merge the source transformation did implicitly for colliding
package-infos). All mode wiring lives in `buildSrc/jitter.gradle`:
`lateStripSrcSets = ['jaxb', 'jaxbHelper']` with `-PjitterBcStrip`, otherwise
the sets are excluded (full-model mode).

**Verified**: plain `compileAll` green in Dba; strip-mode flavour matrix
(javap + jar inventory) - Dba: generated `peem.connectivity` package
stripped, nested sitefs preserved, handwritten package-infos deduped;
Mpk: `rxFlag`/`source_tu`/`dest_tu` stripped, `verifyable`/
`printRet`/`printShip`/`boxNumber`/`cartonNumber` present; Bmx: the
mirror image. Plugin suite 40 tests green.

## Source-set merge: lateStripSrcSets removed again (2026-10)

The late-strip option became obsolete: the consumer dissolved its
`jaxb`/`jaxbHelper` source sets into `main` (additional srcDirs of main),
so generated classes, helper classes and handwritten code compile as ONE
source set and strip as one unit (`jitterStripMain`, standard
`classes`-task wiring). No consumer code compiles against another source
set's output anymore, which removes the whole reason late stripping
existed (and the Kotlin-jar ordering caveat with it).

Consumer consequences of the merge (ologis, all uncommitted):
- xjc-generated `package-info` classes no longer collide with the
  handwritten ones (single compilation) - the redundant
  `annox:annotatePackage` gates were removed from the schemas; only
  `base.xsd` keeps its gate (no handwritten package-info there).
- `jar { duplicatesStrategy = EXCLUDE }` and the `from sourceSets.*.output`
  packaging lines are gone; the jar packs main output only.
- `jitter.lateStripSrcSets` is removed from the plugin DSL; strip mode now
  simply leaves `main` un-excluded for the stripped contract modules.
- duplicate handwritten `peem/connectivity` package-info of the former
  jaxbHelper set deleted; the itest-bdd module's direct
  `sourceSets.jaxb.output` classpath lines removed (the `testFixtures`
  dependency carries main already).

### Ologis-only constructs found while stripping handwritten main code

Stripping the handwritten main code of the contract modules (never done
before - the earlier matrix only stripped generated classes) surfaced
three real-world gaps; all fixed in the plugin:

1. **`@Fork` on generic overrides**: javac copies method annotations to the
   bridge method of a generic override. The stripper applied the fork twice
   (real method consumes the template, bridge fails to find it). Bridge and
   synthetic methods are now skipped as fork gateways.
2. **Multi-line initializers**: javac emits labels at line boundaries inside
   initializer expressions; the backward scan treated any label as a bail
   out, so every builder-chain initializer of a removed static field left a
   dangling store (first seen in `ConvertCheckingDestinationToStationIdCmd`).
   Labels are transparent now, with the self-containment check above.
3. **Cross-module `@Alter` templates** (ologis `MfrTableNames` ->
   `PeemTableNamesDefault` in `ologis-mpk-peem-api`): the template was
   never resolvable - not in the target's package (Spoon's package-relative
   lookup fails too), and not on the module's compile classpath (no
   dependency at all). The plugin now resolves FQ-named templates from the
   source set's compile classpath (`loadFromClasspath`; the template stays
   where it is, the remapped copy is written into the stripped output -
   matching Spoon's shadow-class intent), and the consumer got a
   `compileOnly` dependency plus FQ `@Alter(with=...)` names. Note: the
   construct fires in Dba only (the `mfr2022` package is DBA-gated, so
   Mpk/Bmx remove the class before `@Alter` runs).

### Verified (2026-10-09)

- ologis plain `compileAll` (full model, no stripping): green in Dba and Mpk.
- Strip-mode flavour matrix (`-PjitterBcStrip`, contracts+mfr, jar shape via
  unzip/javap): Mpk - sitefs present, md/article/custorder/base present,
  mfr connectivity absent; Bmx - md/article/custorder/base/sitefs absent,
  peem connectivity present, mfr absent; Dba - md/article/custorder/base
  present, sitefs and peem connectivity absent, mfr connectivity present
  (24 classes), `MfrTableNames` content-replaced from the classpath
  template.
- Plugin suite: 42 tests green.

## Two-artifact design: stripping is the default (2026-10)

Option b implemented: the flavour-stripped model is what the build outputs,
and the full 150% model lives only where compilation needs it. The interim
mode duality (`-PjitterBcStrip` vs plain full-model builds) is gone.

**Per module:**
- `compileJava` compiles the full 150% sources into the untouched classes
  directories. Same-build downstream modules compile against those classes
  directories (Gradle's `classes` outgoing variant of `apiElements`) - so
  the full model is what every compile sees, in every flavour.
- `jitterStripMain` writes the stripped model to
  `build/jitter-stripped/<srcSet>` instead of mutating the classes in place
  (declared `@InputFiles`/`@OutputDirectory`, so the task is incremental;
  no more stale classes directories, no clean-before-mode-switch).
- `jitterJar` packs stripped classes + processed resources into
  `build/jitter-libs/<name>.jar` and replaces the `runtimeElements` artifact:
  wars, `installOlogis`, test runtime and every other runtime consumer get
  the flavour variant. The standard `jar` (full model) remains the
  compile artifact and carries the test fixtures.
- War projects get their classpath overridden to
  stripped classes + resources + runtimeClasspath, so `WEB-INF/classes`
  and `WEB-INF/lib` both carry the flavour model.
- Test/integrationTest source sets are stripped to their own output
  directories as well, and the source set output points at the stripped
  tree (test tasks pick it up from there). This was the last in-place
  remnant: in-place-stripped test classes lose their annotations, so the
  next flavour switch stripped nothing and the previous flavour's test
  model leaked into the verify - flavour switches without a clean broke
  every build after the first one. With output-dir strips a flavour
  switch simply re-strips the untouched compiled classes (validated:
  Mpk -> Bmx -> Dba -> Mpk without clean, all green).
- Consumers of another module's test output must be pointed at that
  module's full compiled classes for their own compilation
  (ologis: `mpk-cc-itest-bdd` compiles against `compileIntegrationTestJava`
  of `ologis-itest-bdd` instead of its `integrationTest.output`, which is
  the stripped tree now).
- `testFixtures` is stripped as well, with the full jar staying the compile
  artifact (`testFixturesApiElements`) and the stripped
  `jitterTestFixturesJar` replacing the `testFixturesRuntimeElements`
  artifact - test code running against the deployed flavour model must
  see the same @Alter/@Fork/@OnlyIf transformations as the application
  (see "Full pipeline validated").
- `jitterVerifyMain` checks the stripped tree; the aggregate `jitterVerify`
  scans the stripped main tree plus the test classes, so the convention
  (tests of gated code must be gated) is enforced for code that was never
  flavour-built before.

**ologis**: `buildSrc/jitter.gradle` carries no mode wiring anymore - only
flavours and critical terms. The known trap so far: a stripper change does
not invalidate up-to-date `jitterStrip*` outputs (the plugin classes are
not task inputs), so a full clean is needed after plugin changes.

### Stripper gaps found by the first full-strip builds (all fixed, tested)

1. **`$deserializeLambda$`**: javac's synthetic lambda deserializer
   references ALL lambda implementations - also those of removed methods -
   by name and bootstrap handle. The case blocks of removed lambdas are
   now excised and their switch entries fall through to the default
   ("Invalid lambda deserialization") branch. Switch entries must not be
   removed - a table switch derives its keys from the index range.
2. **Lambda ownership**: `lambda$<method>$N` names are per-class counters,
   so overloads of the same method name share the prefix - the old prefix
   sweep removed surviving overloads' lambdas. Removal is now
   reference-based: a lambda of a removed method is one that no surviving
   method references anymore (fixpoint over invokedynamic bootstrap
   handles, `$deserializeLambda$` excluded; nested lambdas covered).
3. **Array initializer stores**: the backward store scan needed
   `AASTORE`/`IALOAD`-family effects and `ANEWARRAY`/`NEWARRAY` as
   count-popping (with them, e.g. `NATURAL_FIELD_ORDER_BMX`-style
   `new String[]{Enum.name(), ...}` initializers of removed fields
   remove cleanly, including the producing `aload_0`/`iconst`).
4. Also added: `ACONST_NULL`, `BIPUSH`, `SIPUSH` effects.

### Verified (2026-10-09)

- ologis `compileAll` green in Dba, Mpk and Bmx - every module stripped
  and verified for the first time (ologis-api, ologis-contracts,
  ologis-contracts-facade, ologis-impl-webui, ...).
- Runtime artifacts flavour-correct in all three flavours (spot checks):
  Dba - `VKPriceImportJob` and its bundle absent from the ologis-api
  stripped jar, sitefs classes absent, mfr connectivity present; Mpk -
  sitefs classes present (10), `VKPriceImportJob` + renamed bundle
  present, mfr absent; Bmx - all Mpk/Dba-only content absent.
- The `ologis-impl-webui` war: `WEB-INF/classes` stripped own classes
  (all Mpk view code absent in Dba), `WEB-INF/lib` the 108 stripped
  project jars. The original `buildLocalDevEnv` failure cause
  (payara loading `VKPriceImportJob` in Dba/Bmx) is gone from the
  deployment artifact.
- Plugin suite: 54 tests green.

### Known follow-ups (superseded by "Full pipeline validated" below)

- No dangling-resource follow-ups: the suffixed bundles follow the base
  + variant pattern (base ships in the other flavours, the active flavour's
  variant replaces it, e.g. `Customer.properties` + `Customer_Mpk.properties`),
  or come with flavour variant sets for all three flavours, or belong to
  flavour-gated classes whose bundle ships with the class
  (`VKPriceImportJob`). Verified during the first deployment tests.
- eval.jitter re-validation pending.
- Publishing: runtimeElements (stripped) vs apiElements/full jar would
  need a classifier/capability scheme for Maven consumers.

### Full pipeline validated (2026-10-10)

`clean buildLocalDevEnv` (war, payara deployment, liquibase, dbunit dumps)
green in **Dba, Mpk and Bmx** - the first full deployment of flavour
variants from bytecode stripping. Three more real-world gaps surfaced on
the way and are fixed:

1. **Per-directory strip broke mixed source sets**: the strip ran per
   classes directory, but a mixed Java/Kotlin source set spreads package
   gates (package-info), classes and cross references over several
   directories - the Kotlin pass saw no gates and copied everything.
   All classes directories of a source set are now stripped as ONE unit
   (`strip(List<Path>, ...)`); the frontend modules (`ologis-user-ui`)
   were the first with real Kotlin+Java packages (`goodsreceiptpck`).
2. **`$deserializeLambda$` rework**: removing the case blocks left stale
   stack map frames / violated maxStack ("StackMapTable format error",
   "Operand stack overflow" at deployment, javap misses both). The case
   of a removed lambda now keeps its structure and only its invokedynamic
   is replaced with the standard "Invalid lambda deserialization"
   failure (+3 maxStack). The name-matching case stays - the lambda name
   is a plain string, not a member reference.
3. **Fork arity**: `additionalQuantities(2 args)` forking to a
   `doNothing()` template shrank the gateway below its own argument
   count ("Arguments can't fit into locals"). Fork now takes the max of
   gateway and template maxStack/maxLocals.
4. **Test fixtures runtime variant** (the former "still open" gap):
   `OutputResourceTestDataGenerator` (@Alter per flavour) ran as the full
   150% model against the stripped deployment - `NoSuchFieldError
   EPOST_EXCHANGE`. Test fixtures are now stripped as well; the stripped
   `jitterTestFixturesJar` replaces the `testFixturesRuntimeElements`
   artifact while `testFixturesApiElements` keeps the full jar for
   downstream itest compilation (EXCLUDE for the colliding
   package-infos, as in the old jar).

Also fixed: the itest cross-module compile dependency in ologis
(`mpk-cc-itest-bdd` compiles against the full `compileIntegrationTestJava`
output of `ologis-itest-bdd` via task references - direct source set
output references hit Gradle's implicit-dependency validation).

Operationally: a failed deploy leaves the payara domain running, which
blocks the next `restore-domain` - stop the domain before re-running
`buildLocalDevEnv` after a failure.

## Lambda field initializers of removed fields (2026-10-10)

Found by the first large acceptancetest run
(`acceptancetest:process:end2EndTest --tests "com.zitecs.ologis.*"`, Bmx):
`:acceptancetest:process:jitterVerify` failed with
`AbstractOrderFlow accesses removed field AbstractOrderFlow#onSiteCfg`.
`onSiteCfg` is `@OnlyIf(MPK)` with a `DataFix` initializer built from
method references and lambdas (`new DataFix<>(dao()::merge,
c -> dao()::findById(...), ...)`). Two stripper gaps, both fixed:

1. The backward stack-effect scan had no `invokedynamic` case, so any
   initializer containing a lambda or method reference bailed out and
   left the `PUTFIELD` as a dangling reference. Now handled, together
   with javac's bound-method-reference null-check pattern
   (`INVOKESTATIC dao(); DUP; requireNonNull; POP; INVOKEDYNAMIC`) and
   straight-line arithmetic/conversion opcodes. Ternaries still bail
   out (branchy).
2. The initializer's synthetic `lambda$new$N` methods would have
   survived as dead code with possibly dangling bodies (the lambdas
   reference `OnSitePharmacyCfg`, an Mpk-only class). They are now
   removed together with the initializer - transitively, since lambdas
   nest - and registered for the `$deserializeLambda$` cleanup like the
   lambdas of removed methods (`removeFieldInitializers` now runs before
   `cleanDeserializeLambda`).

Verified: plugin suite 56 green (new test with the bound-ref + lambda
pattern, executed stripped class); ologis Bmx
`:acceptancetest:process:jitterVerify` green with
`Remove lambda ...lambda$new$6/7` in the strip log; Bmx `compileAll`
green.

Also surfaced by the first large acceptancetest run (Dba), and NOT a
plugin issue: `@Fork(to = "flavouredContextDba")` in the PBH test pointed
at a private template of the parent class instead of a method of the
declaring class - a construct the Spoon processor silently swallowed
(it logged the same error and left the gateway's empty body, so the Dba
scenario never got its Dba context). The stripper's hard error exposed a
real, long-masked ologis bug (fixed in the ologis code). `@Fork`'s model
stays: gateway and templates in the same class.

## Implicit default constructors restored (2026-10-11)

The first full Dba acceptancetest run
(`acceptancetest:process:end2EndTest --tests "com.zitecs.ologis.*"`,
2544 steps, 1041 failed) funneled into a single root cause:
`ImportPaymentData`'s ONLY constructor is `@OnlyIf(BMX)` - in Dba the
class had no constructor at all, while un-gated backend code
(`XmlCustOrderToImportOrderCmd`, `CustOrderResource`) and the un-gated
test builder (`ImportPaymentDataBuilder`) call `new ImportPaymentData()`.
Backend effect: the XML cust order import died, orders stayed CREATED
(114 test classes with state assertions); test-side: 36 classes died at
the builder with `NoSuchMethodError`; everything else was arete cascade
("No entity is matching the given predicate" x610, custOrder-null NPEs,
HTTP status failures).

Why undetected: the reference verification works per module/source set.
The testFixtures -> main reference is only seen by the aggregate verify
task, which does not run when merely consuming the stripped artifacts;
the cc-contracts -> ologis-api reference is cross-module and invisible
to any per-module verify under the full-model-compile design (this is
the check the Spoon pipeline got for free, because downstream modules
compiled against the stripped source model - there it was a Dba COMPILE
error).

The fix restores javac parity instead of touching ologis: under Spoon
the recompiled flavour model ALWAYS had javac's implicit default
constructor when no explicit one remained, so `new ImportPaymentData()`
was legal in every Spoon flavour. `restoreDefaultConstructors` now adds
exactly that ctor (same access as the class, `super()` body) when the
stripping removed all constructors of a class - the restored `()V` is
un-registered from the removed members, so references to it pass the
verification again. Guard rails: only classes whose ctors were actually
removed (Kotlin compiler generated ctor-less helpers like
`$WhenMappings`/`$DefaultImpls` must not get one), no interfaces/enums/
annotations, no named non-static inner classes (implicit ctor carries
the outer instance - reported by the verify instead).

Open follow-ups from the analysis (not yet built): a scoped verify for
`jitterTestFixturesJar` (testFixtures -> main references of other member
kinds), and removal manifests in the stripped jars +
classpath-aware consumer verification for cross-module references.

Verified: plugin suite 58 green (default ctor restored + executed,
surviving ctor untouched); ologis Dba `clean compileAll` green with
exactly ONE restoration - `ImportPaymentData` - across the whole build
(the naive "no ctor remains" condition had produced 202 spurious ones);
stripped Dba `ImportPaymentData` carries the public default ctor, the
BMX fields are gone. Bmx unaffected (ctor survives, nothing restored).

## Annotation-gated resources (2026-10): one mechanism for classes and resources

Closes the last gap of the suffix convention: XML resources can now carry
their flavour gate in their own content, the same way classes do.

**Plugin**: `XmlResourceGate` reads every `appinfo` section of an XML-ish
resource (xsd, xjb, xsl, xslt, wsdl, xml) and collects the values of all
jitter `@OnlyIf` annotation expressions found there (fully qualified or
simple name, multiple annotations merged). The mechanism is generic - the
plugin knows nothing about any XML vocabulary, it only uses the W3C
standard `appinfo` annotation hook. `ProcessResourcesListener` excludes a
gated resource from the processed resources when the active flavour is
not part of the gate; a gated resource is never renamed (the gate
expresses presence or absence, while the suffix convention remains for
per-flavour content variants). Without an active flavour nothing is
gated, mirroring the class model.

**Correction (2026-10-10)**: the coupling of the annox generation gate to
the resource gate was wrong and is reverted. The first Bmx end2End test
run (`HandleDeviationsTest...ConfirmCancellation`) failed with a marshall
exception: `XmlStationCompleteMessageMarshaller` builds its validation
schema at runtime, and `StationCompleteMessage.xsd` (Bmx-present) imports
`base:XmlDateTimeNoDelimiters` from `base.xsd` - which the annox gate
`@OnlyIf({Mpk, Dba})` had removed from the Bmx jar
(`src-resolve: Name 'base:XmlDateTimeNoDelimiters' ...`). A generation
gate belongs to the class model (which classes are generated per flavour)
and says nothing about the resource's runtime role: flavour-specific
message schemas of ANY flavour may import the shared base types.
`XmlResourceGate` therefore ignores `@OnlyIf` expressions inside `annox:*`
elements now; only hand-written plain appinfo gates gate a resource.
Consequence: `base.xsd` ships in every flavour again (as it did under the
old rename mechanism), while the generated base classes stay Mpk/Dba-only.

**ologis migration** (uncommitted): the 8 mfr
`*MFR_Dba.xsd` schemas and the sitefs `*_Mpk.xsd` family dropped their
suffixes and carry `@OnlyIf("Dba")`/`@OnlyIf("Mpk")` in a plain appinfo
comment-style annotation (inert to xjc - no generated package-info, so
no collision with the handwritten ones). The sitefs binding was renamed
alongside and its schemaLocation updated; it carries no gate (xjb has no
appinfo), so it ships in every flavour as dead weight - bindings are
build-time inputs only.

**Verified**: plain `compileAll` green (Dba); strip-mode matrix green in
Dba/Mpk/Bmx with correct resource shapes - Dba: the 8 mfr schemas
present under their unsuffixed names (byte-identical jar content to the
old rename mechanism), sitefs absent, base.xsd present; Mpk: both sitefs
schemas present under their unsuffixed names, mfr schemas absent, only
the ungated `mfr2022/.../base/base.xsd` remains in the mfr jar; Bmx:
everything flavour-correct. After the correction re-verified (Bmx):
`base.xsd` present again in the cc-contracts jar next to
`StationCompleteMessage.xsd`, the hand-written Dba gates of the mfr
schemas still exclude their resources. Plugin suite: 55 tests green.

## Inlined annotation constants of `@Alter`ed constants classes (2026-10)

Surfaced by the first ologis integration tests: `Ologis2PeemStandardJob`
inserted into `OLOGIS2PEEMSTANDARDJOB` (the raw entity name) instead of the
real table `OLOGISJOBH2C1`. Cause: javac inlines compile-time constants into
annotations, so `@Table(name = PeemTableNames.OLOGIS_2_PEEM_STANDARD_JOB)`
bakes the full-model value into the entity's bytecode. The `@Alter` of
`PeemTableNames` itself worked, but the entities kept the inlined stale value
- the source transformation never saw this because Spoon re-baked the values
by rewriting sources before downstream compilation.

**Solution: re-baking.** When a class with `@Alter` is replaced, the stripper
collects a fullValue→templateValue map for String constants of the target
class (now the full model, i.e. pre-strip) and rewrites matching String
values in the annotations of ALL surviving classes (class/field/method/
parameter annotations, annotation defaults, nested arrays and nested
annotations). Ambiguity guard: a full-model value that occurs for more than
one field of the target class (or is already mapped) is skipped, so distinct
constants can never collapse into one value.

**Contract for constants classes**: the full-model constants must be unambiguous
across flavours, or the re-bake is skipped. In ologis the six
`PeemTableNames` constants that were `""` became unique self-named markers
(e.g. `"OLOGIS_2_PEEM_STANDARD_JOB"`). `MfrTableNames` and
`WamasFsTableNames` need no markers: their full-model values are real table
names identical in meaning, and the fields don't exist in the templates, so
re-baking is a safe no-op. Markers must never reach runtime - the stripper
reports the re-baked values (`Re-baked N altered constant value(s) ...`), and
a missed marker shows up as a broken table name in the logs.

Dba note: `com.zitecs.ologis.peem.connectivity` (and its subpackages) are
`@OnlyIf({Mpk, Bmx})`, so in Dba the whole package including the entities is
removed - nothing is re-baked there and the empty stripped output of
`ologis-mpk-peem-api` in Dba is correct.

**Verified (uncommitted)**: plugin suite 54 green (new test
`rebakesInlinedAnnotationConstantsOfAlteredConstantsClasses`: unique marker,
array-valued annotation, ambiguous-skip). ologis: Bmx `clean compileAll` green,
`Re-baked 6 altered constant value(s) in the annotations of 6 class(es)` in
the strip log; stripped `Ologis2PeemStandardJob` carries
`@Table(name = "OLOGISJOBH2C1")` (Bmx) resp. `"MPKJOBH2C1"` (Mpk); full model
keeps the markers; Dba `clean compileAll` green (package removed). The failing
`PeemTableNames`-based integration tests still need a manual re-run against a
deployed environment.

## Next steps

- Decide whether the ologis source migration to a self-compilable 150% model
  is worth the Spoon-independent build (the blocker above is a code base
  convention, not a plugin defect)
- Implement the multi-project artifact design (full model for compilation,
  stripped model for runtime/packaging), then re-run the ologis validation
- Re-validate eval.jitter after the task wiring change
- If it holds: update README/docs, decide on removing the dead Spoon processors
  and the spoon-gradle-plugin dependency, and coordinate the release with
  jitter-api consumers
