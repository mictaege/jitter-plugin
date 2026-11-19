# Resource Gates Specification

## Purpose

Flavour-specific resources via three mechanisms: the name-suffix convention, whole-file gates, and
component gates (`jitter:onlyIf` / `jitter:set`) inside XML `annotation`/`appinfo` sections. Gates
are resolved while resources are copied — never afterwards.

## Requirements

### Requirement: Name-suffix convention for whole-file variants

A resource named `Xyz_<Flavour>` SHALL be renamed to `Xyz` in that flavour (folders included); the
inverted form `Xyz_!<Flavour>` SHALL express the inverse (present in every flavour except the
named one). Only one variant per base name SHALL exist per flavour resolution.

#### Scenario: Per-flavour resource variant
- **WHEN** resources contain `config_ESA.properties` and `config_NASA.properties` and the active
  flavour is NASA
- **THEN** the flavour model contains `config.properties` with the NASA content

### Requirement: Whole-file gate via appinfo annotation

A jitter annotation inside an `appinfo` section SHALL gate the whole XML file (the file is present
only in the listed flavours). Files without the gate namespace SHALL pass through unchanged
(opt-in per file).

#### Scenario: Gated whole file
- **WHEN** an XML resource carries a jitter appinfo gate listing only ESA
- **THEN** the file is present only in the ESA flavour

### Requirement: Component gates `jitter:onlyIf` / `jitter:set`

Inside a gated component's `annotation`/`appinfo`, `jitter:onlyIf flavours="..."` SHALL keep the
component only in the listed flavours (removed otherwise; without an active flavour — the full
model — everything is kept), and `jitter:set ifActive="..."` SHALL set the gate's other attributes
(e.g. `minOccurs`, `use`, `maxOccurs`, pattern values) on the component while a listed flavour is
active, keeping the component's own values otherwise; several `set` gates MAY annotate the same
component. The gate vocabulary SHALL follow the schema at `https://mictaege.github.io/jitter`
(see the `gate-schema` spec in the jitter repo). Gates SHALL be resolved at copy time, and the
resolved document SHALL carry no gate residue (gate elements stripped; emptied
`appinfo`/`annotation` husks removed).

#### Scenario: Cardinality differs per flavour
- **WHEN** a schema element carries `<jitter:set ifActive="Bmx" minOccurs="1"/>`
- **THEN** the Bmx flavour's resolved document has `minOccurs="1"`, all other flavours keep the
  element's own value

## Design decisions (from the former docs/bytecode_stripping.md)

7. Resource flavouring — rename / gate the file / gate the components: each mechanism covers what
   the others cannot (whole-file variants vs absence vs component facets like patterns and
   cardinalities, which are not part of the class model). Gates sit in the W3C `appinfo` hook so
   the unified document stays valid for every XML tool; resolution at copy time keeps processed
   resources, runtime artifacts and the dejittered variant consistent with the unified source.
