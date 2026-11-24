# Spec Delta

## ADDED Requirements

### Requirement: References inside removed gate blocks are tolerated

The dangling-reference verification SHALL NOT report references that occur only inside a
`Gate.onlyIf` block removed for the active flavour.

#### Scenario: Flavour-only type used only inside a gated block
- **WHEN** a gated block references a class that is `@OnlyIf`-stripped in the same flavour
- **THEN** verification passes for that flavour
