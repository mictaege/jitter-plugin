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

- `jitterStrip<SrcSet>` tasks run after compilation of the whole project
  (`mustRunAfter` all `JavaCompile` tasks) and before `test`/`run`/`jar`
  (aggregate `jitterVerify` task in between). Downstream source sets therefore
  still compile against the full 150% model.
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

- Anonymous inner classes declared inside `@OnlyIf` methods are not auto-removed
- synthetic `access$NNN` orphans are not auto-removed
- `$SwitchMap` references from surviving switch statements are reported, not fixed
- branchy field initializers (ternaries etc.) bail out and are reported

## Validation performed (eval.jitter)

- `flavourESA clean build`, `flavourNASA clean build`, `flavourROSKOSMOS clean build`,
  plain `clean build` (150% model) - all successful, zero dangling references
- `flavourNASA clean run` - the stripped NASA variant starts and runs
- Parity vs Spoon output: identical class inventory (except Spoon leaks template
  classes, this branch does not), identical `Dao` content, no annotations,
  exact line numbers; ESA build ~14s vs ~29s with Spoon
- Plugin test suite: 37 tests green (incl. executed stripped enums, lambda and
  field-initializer scenarios)

## Next steps

- Test against larger code bases (this is the main reason the branch exists)
- If it holds: update README/docs, decide on removing the dead Spoon processors
  and the spoon-gradle-plugin dependency, and coordinate the release with
  jitter-api consumers
