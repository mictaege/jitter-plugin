# Spec Delta

## ADDED Requirements

### Requirement: Switch maps of cross-module enums are cleaned during stripping

When the active flavour removes enum constants in a flavour dependency, the synthetic switch-map
classes (`Outer$N` with `int[] $SwitchMap$<Enum>`) of surviving consumers SHALL have the entries
of those removed constants removed from their `<clinit>`, exactly as for constants removed in the
same module.

#### Scenario: Cross-module removed constant disappears from the switch map
- **WHEN** a consumer module switches over an enum of a producer module and the active flavour
  removed one of the used enum constants in the producer (per the producer's removal manifest
  `META-INF/jitter/<srcSet>-removed.txt` in the producer's stripped tree)
- **THEN** the consumer's stripped switch-map class `<clinit>` no longer references the removed
  constant (neither in instructions, exception-table ranges nor constant pool), entries for
  surviving constants remain, the removal is logged with the `jitter: ` prefix, `jitterVerify`
  passes, and the consumer's own removal manifest does NOT list the cross-module removal

#### Scenario: Unparseable entry shape fails the build
- **WHEN** a switch-map entry for a removed constant does not have the expected compiler shape
- **THEN** stripping fails with a `jitter: `-prefixed error naming the enum constant and the
  switch-map class (a leftover entry is a silent leak of other flavours' names — never warn-and-continue)

#### Scenario: External producer without manifest is left untouched
- **WHEN** the switched-over enum comes from an external published artifact (deliverables carry
  no removal manifest)
- **THEN** no entries are removed (nothing is known about its removals) and the runtime
  `NoSuchFieldError` tolerance remains the only mechanism

### Requirement: Kotlin when-maps are cleaned like javac switch maps

Entries for removed enum constants — same-module or cross-module — SHALL be removed from the
`<clinit>` of synthetic Kotlin `Outer$WhenMappings` classes (`int[] $EnumSwitchMapping$N`), so
neither the instructions nor the constant pool of the stripped class reference them anymore.

#### Scenario: Same-module Kotlin when-map entry removed
- **WHEN** a Kotlin class `when`s over an enum of the same module (kotlinc emits an
  `Outer$WhenMappings` class whose `<clinit>` `GETSTATIC`s every used enum constant inside a
  `NoSuchFieldError` handler) and the active flavour removed one of the used constants
- **THEN** the stripped `Outer$WhenMappings` `<clinit>` no longer references the removed constant,
  using the Kotlin entry shape (`ALOAD map; GETSTATIC constant; INVOKEVIRTUAL ordinal; int push;
  IASTORE; GOTO; handler ASTORE`)

#### Scenario: Cross-module Kotlin when-map entry removed
- **WHEN** a Kotlin class `when`s over an enum of a producer module whose removal manifest lists
  one of the used constants as removed
- **THEN** the consumer's stripped `Outer$WhenMappings` `<clinit>` no longer references the removed
  constant (neither in instructions nor in the constant pool)

#### Scenario: Unparseable Kotlin entry shape fails the build
- **WHEN** a `$EnumSwitchMapping$` entry for a removed constant does not have the expected Kotlin
  shape
- **THEN** stripping fails with a `jitter: `-prefixed error naming the enum constant and the
  when-map class
