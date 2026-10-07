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
  - Enums: constant init blocks removed from `<clinit>`, surviving ordinals
    renumbered, `$values()` array rebuilt (old-style `$VALUES` in `<clinit>`
    supported as well)
  - Lambdas: `lambda$<removedMethod>$*` synthetic methods removed
  - Inline field initializers of removed fields removed from constructors
    (backward stack-effect scan; bails out on branchy initializers)
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
- Resource handling (`ProcessResourcesListener`, critical terms tasks) untouched.
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
