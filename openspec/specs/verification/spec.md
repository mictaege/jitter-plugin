# Verification Specification

## Purpose

`jitterVerify` closes the consistency gap that the former per-flavour source compilation got for
free: with the 150% model compiled as one program, dangling references to removed code would only
fail at runtime. The verify tasks fail the build on such references — own module and
cross-module.

## Requirements

### Requirement: Dangling-reference check per source set, aggregated

`jitterVerify` SHALL run per source set (scoped per artifact lane) and `jitterVerifyAll` SHALL run
every module's verification plus every inlined-constants check as the single CI entry point. The
check SHALL fail the build when a surviving class references a removed class, field, method or
enum constant. Removal manifests (`META-INF/jitter/<srcSet>-removed.txt` in the stripped trees,
carried on the runtime classpath) SHALL be the cross-module carriers: sibling modules' manifests
are read from their stripped trees, external artifacts' from the classpath. Manifests SHALL NOT be
packaged into deliverables.

#### Scenario: Dangling reference fails the build
- **WHEN** surviving code references a class removed by the active flavour
- **THEN** the build fails with a `jitter: `-prefixed error naming the reference

### Requirement: Up-to-date checked, not cache-relied-upon

Verification SHALL be up-to-date checked like any task (inputs: stripped classes, processed
resources, dependency manifests, active flavour; output: the stale-resource report). A second build
without changes SHALL skip it; the build cache SHALL stay disabled for it.

#### Scenario: Re-strip re-runs consumer verification
- **WHEN** a producer module re-strips
- **THEN** the consumer's verification re-runs because its manifest input changed

### Requirement: Tolerated and rejected constructs are explicit

javac's switch-map classes (`Outer$N` with `$SwitchMap$...` GETSTATIC inside a `NoSuchFieldError`
handler) SHALL be tolerated by design. Known unresolvable constructs SHALL surface as clear build
errors — never silently tolerated: anonymous inner classes inside `@OnlyIf` methods, synthetic
`access$NNN` orphans, branchy field initializers of removed fields. Improving a limitation SHALL
mean handling it correctly, never loosening the check.

#### Scenario: Switch-map references tolerated
- **WHEN** a switch over an enum whose constant a flavour dependency removed is compiled
- **THEN** verification passes (the NoSuchFieldError path is javac's intent)

### Requirement: Stale-resource report

The verify pass SHALL produce a stale-resource report (e.g. a resource shipping without its owner
class because the active flavour removed the class) as a `jitter: `-prefixed build warning.

#### Scenario: Orphaned resource warns
- **WHEN** resource X ships while its owner class Y was removed by the active flavour
- **THEN** the build logs a warning `jitter: resource X ships without its owner class Y ...`

## Design decisions (from the former docs/bytecode_stripping.md)

3. Verification replaces the lost compile-time consistency — a separate pass after stripping,
   also validating cached strip results and dependent modules' models.
4. Fail loudly, never guess — `@Fork`/`@Alter` are strict (missing/inaccessible template fails);
   known unsupported constructs are build errors, because silent degradation is the worst outcome of
   a flavouring build.
