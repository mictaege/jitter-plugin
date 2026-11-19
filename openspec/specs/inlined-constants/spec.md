# Inlined Constants Specification

## Purpose

javac inlines constant variables (`final` primitives/String with constant initializer) into their
usage sites, so post-compile stripping cannot flavour them. The plugin handles this at the
declaration side: an audit plus the `@InlinedConstantApproved` review marker, an opt-in mechanical
check, and `@AlterConstValue` for values that differ per flavour.

## Requirements

### Requirement: Constant audit with review marker

The constant audit SHALL fail the build for every constant variable whose *existence* is flavour
variant (gated on the field, its class or its package) unless marked `@InlinedConstantApproved`
(developer's assertion that every usage sits in code gated with a subset of the constant's
flavours). The audit SHALL run on the declaration side, in every flavour run and in the full model
alike. A gate covering all flavours is not flavour-variant and needs no marker. Alternatives for
the developer: box the type or drop `final`.

#### Scenario: Unmarked gated constant fails
- **WHEN** a `final String` constant is `@OnlyIf("NASA")` without `@InlinedConstantApproved`
- **THEN** the build fails the constant audit

### Requirement: Optional mechanical usage check

`jitterVerifyInlinedConstants` SHALL be an opt-in check (per source set) that mechanically verifies
the `@InlinedConstantApproved` assertion via a full usage analysis of the sources; `private`
constants SHALL be included (inlined within their own class). The build server activates it; the
everyday build trusts the marker.

#### Scenario: Mechanical check on CI
- **WHEN** `jitterVerifyInlinedConstants` runs and a marked constant has an ungated usage
- **THEN** the check fails, naming the usage site

### Requirement: `@AlterConstValue` for per-flavour constant values

For constants whose *value* differs per flavour (existing in all flavours), `@AlterConstValue`
SHALL set the value at the declaration site; it SHALL work with wrapper types and `String` only
(primitives are inlined and unalterable post-compile). It SHALL cover every visible flavour of the
constant (audit-verified). A class carrying `@Alter` SHALL NOT declare constant variables at all
(rejected outright). The dejittering SHALL apply the active flavour's value in the source variant,
so source deliveries match the compiled flavour model.

#### Scenario: Wrapper constant altered per flavour
- **WHEN** a `Integer` constant is `@AlterConstValue(ifActive = {"ESA","NASA"}, with = "42")`
- **THEN** ESA/NASA models and dejittered sources carry 42, other flavours keep the declared value

## Design decisions (from the former docs/bytecode_stripping.md)

5. Constants: audit + review marker + optional mechanical check — there is no way to un-inline or
   to distinguish an inlined usage from a handwritten literal afterwards, so the problem is solved
   at the declaration side.
6. `@AlterConstValue` — the `@Alter` template mechanism transfers implementations, not constant
   identities; the value must be correct in the flavour's own declaration.
