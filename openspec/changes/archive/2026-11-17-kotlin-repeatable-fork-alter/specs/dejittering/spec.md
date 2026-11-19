# Spec Delta

## ADDED Requirements

### Requirement: Kotlin dejittering resolves repeated @Fork and @Alter per active flavour

For Kotlin sources, the dejittering SHALL consider **all** entries of a repeatable jitter
annotation (repeated entries and the entries of an explicitly written container) and SHALL apply
the entry whose `ifActive` contains the active flavour, exactly as the Java source dejittering and
the bytecode stripping do. When no entry matches, no fork/alter SHALL be applied, but the jitter
annotations SHALL still be removed from the surviving declaration.

#### Scenario: The active flavour is the second of repeated @Fork entries
- **WHEN** a Kotlin gateway method carries `@Fork(ifActive = "A", ...)` followed by
  `@Fork(ifActive = "B", ...)` and the source set is dejittered for flavour `B`
- **THEN** the gateway body is replaced with the body of the `B` template, the `B` template is
  removed, and no `@Fork` annotation remains on the gateway

#### Scenario: The active flavour is the first of repeated @Fork entries
- **WHEN** a Kotlin gateway method carries `@Fork(ifActive = "A", ...)` followed by
  `@Fork(ifActive = "B", ...)` and the source set is dejittered for flavour `A`
- **THEN** the gateway body is replaced with the body of the `A` template, the `A` template is
  removed, and no `@Fork` annotation remains on the gateway

#### Scenario: No repeated entry matches the active flavour
- **WHEN** a Kotlin gateway method carries only `@Fork(ifActive = "A", ...)` and
  `@Fork(ifActive = "B", ...)` and the source set is dejittered for flavour `C`
- **THEN** the gateway keeps its placeholder body, both templates survive, and the `@Fork`
  annotations are removed

#### Scenario: Repeated @Alter on a Kotlin class
- **WHEN** a top level Kotlin class carries `@Alter(ifActive = "A", with = "ATemplate")` followed
  by `@Alter(ifActive = "B", with = "BTemplate")` and the source set is dejittered for flavour `B`
- **THEN** the class file is replaced by the `BTemplate` variant, like the Java dejittering and
  the stripped model do

#### Scenario: Explicit container annotation in Kotlin source
- **WHEN** a Kotlin declaration carries the container form `@Gateway([@Fork(...) , @Fork(...)])`
  (or `@Alternatives([...])` for `@Alter`) and the source set is dejittered for the flavour of one
  of the nested entries
- **THEN** the matching nested entry is applied and the whole container annotation is removed

#### Scenario: Source variant agrees with the stripped model
- **WHEN** a Kotlin source set with repeated `@Fork`/`@Alter` is dejittered and cross-checked
  against the removal manifests of the stripped model
- **THEN** gateway bodies, kept templates and removed classes of the source variant match the
  stripped classes for every flavour, so the cross-check never has to report a difference
