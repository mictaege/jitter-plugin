# Spec Delta

## ADDED Requirements

### Requirement: jitterVerify checks the @kotlin.Metadata of surviving classes against the removal manifest

The metadata of every surviving Kotlin class SHALL be checked against the removal manifest of
the stripped model (and the manifests of flavour dependencies): when the metadata still
references a removed class or a member that was removed from that class, the build SHALL fail
with a `jitter: `-prefixed error. The check SHALL read the annotation payload directly from the
class file (the `d2` string table of `@kotlin.Metadata`), so it needs no Kotlin library on the
verification path.

#### Scenario: Metadata referencing a removed class fails the build
- **WHEN** a surviving class's `@kotlin.Metadata` `d2` still contains the descriptor of a
  class the active flavour removed
- **THEN** `jitterVerify` fails with a `jitter: `-prefixed error naming the class and the
  metadata that still references it

#### Scenario: Metadata listing a member removed from the same class fails the build
- **WHEN** a surviving Kotlin class's metadata still lists a function, constructor, property
  or enum entry that the stripping removed from that class
- **THEN** `jitterVerify` fails with a `jitter: `-prefixed error naming the member

#### Scenario: Consistent metadata passes
- **WHEN** the metadata of every surviving class agrees with the stripped bytecode
- **THEN** `jitterVerify` passes and reports no metadata violations

#### Scenario: Metadata reference that the bytecode still carries is tolerated
- **WHEN** a surviving class's `@kotlin.Metadata` `d2` references a removed class or member,
  but the class's own bytecode still carries the same declaration-level reference (e.g. a
  surviving field or constructor parameter of a removed type, which the code-level reference
  check deliberately tolerates because it only visits instructions)
- **THEN** `jitterVerify` passes: only metadata-only references - references the stripped
  bytecode no longer has - are violations
