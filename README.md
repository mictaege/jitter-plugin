# jitter-plugin

[![Apache License 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.mictaege/jitter-plugin.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22io.github.mictaege%22%20AND%20a%3A%22jitter-plugin%22)

>The _jitter-plugin_ is a Gradle plugin to build and distribute different flavours of an application from a single source base.

A simple but complete example how _jitter_ works could be found at [eval.jitter](https://github.com/mictaege/eval.jitter). It's strongly recommended to check out this repository and to have also a closer look on the examples provided there.

## Table of contents

- [Problem](#problem)
- [Getting started](#getting-started)
- [How it works](#how-it-works)
- [_jitter_ Source Code Markup](#jitter-source-code-markup)
  - [_@OnlyIf_](#onlyif)
  - [_@Fork_](#fork)
  - [_@Alter_](#alter)
- [_jitter_ Resource Handling](#jitter-resource-handling)
  - [Making resources flavour specific](#making-resources-flavour-specific)
  - [Inverted flavour declaration](#inverted-flavour-declaration)
  - [Flavour gates in XML resources](#flavour-gates-in-xml-resources)
- [Usage with Gradle](#usage-with-gradle)
  - [Source set roles](#source-set-roles)
  - [The active flavour in forked JVMs](#the-active-flavour-in-forked-jvms)
  - [Task overview](#task-overview)
  - [Output format](#output-format)
  - [Verification of dangling references](#verification-of-dangling-references)
  - [Dejittered source variant](#dejittered-source-variant)
  - [Stale resources](#stale-resources)
- [Verification of critical terms](#verification-of-critical-terms)

> <details>
> <summary><b>Important notes!!! With release 2026.3 the Spoon source transformation is gone, jitter strips bytecode now</b></summary>
> 
> ## The transformation moved from sources to class files
> 
> Until 2026.3 the flavour model was produced by transforming the Java sources with Spoon before compilation. 2026.3 compiles the complete 150% model untouched and removes the flavour-specific code from the compiled classes with bytecode analysis (ASM):
> 
> - works with every language that compiles to JVM bytecode (Java, Kotlin, Groovy) and every javac syntax level - the stripper couples to the class file format, not the source syntax
> - the sources are never touched: line number tables of surviving code stay intact, hot code replacement works, the debugger resolves to the original sources
> - roughly half the build work of the source transformation, because nothing is recompiled after the flavouring
> 
> ## Breaking changes
> 
> 1. The 150% model must be self-consistent. The whole project must compile as one program, in every flavour. Inconsistent flavour usage that the per-flavour source transformation silently tolerated now fails the compile - deliberately: it was invisible inconsistency.
> 2. Two-artifact design: downstream modules compile against the FULL model (classes directories / full jar) and run against the stripped artifacts (`jitterJar` replaces `runtimeElements`, `jitterTestFixturesJar` replaces `testFixturesRuntimeElements`). Consumers see the flavour model at runtime and the full model at compile time.
> 3. `@Fork` and `@Alter` are strict now: a missing or inaccessible template fails the build. The source transformation logged the same problems and silently produced an empty flavour method - one long-masked real bug surfaced this way in our reference consumer.
> 4. New verification: `jitterVerify` fails the build if surviving code references removed classes or members - within the module AND across module boundaries (removal manifests travel inside the stripped artifacts). javac switch map references to removed enum constants are tolerated as javac intends.
> 5. The plain build (no flavour active) works again under Gradle 9.8.
> 6. Spoon configuration is gone: the `compliance` property and all spoon task references are obsolete.
> 
> ## New
> 
> - Source set roles DSL: `packagingSrcSets` (default `main`), `apiSrcSets` (default `testFixtures`), everything else test-like. Custom layouts are supported and validated (role overlap is an error, unknown names and unreachable stripped artifacts warn).
> - Scoped `jitterVerifyTestFixtures` / `jitterVerify<Name>` tasks: artifact tasks fail early on dangling references.
> - Stale resource report: resources whose owner class was removed ship anyway; the verify tasks report them as warnings.
> - Flavour switching without clean; all flavour-specific outputs are up-to-date checked, build-cache safe, and the verification reads removals from the cached manifests.
> - `dejitter<SrcSet>` tasks: the dejittered source variant of the active flavour - the original source minus the flavour specific code as source level surgery (comments, formatting and line numbers survive), with flavour processed resources and a removal cross check against the stripped model. For source deliveries; see below.
> - Kotlin sources dejitter like Java sources: `@OnlyIf` members, constructor parameters and enum entries, `@Fork` bodies, `@Alter` targets. The Kotlin parser resolves lazily from the repositories of the applying build and loads in an isolated classloader, so it never clashes with the Kotlin toolchain of the build.
> 
> ## Migration
> 
> - Remove the `compliance` property and spoon task wiring from the build.
> - Critical term verification: retarget includes from `generated-sources/spoon/**` to `jitter-stripped/**`.
> - Expect clear `jitterVerify` errors where the old pipeline masked inconsistencies - they point at real flavour bugs.
> - Code in forked JVMs that needs the active flavour reads the system property `jitter.active.flavour`; pass it explicitly (see below).
> - [eval.jitter](https://github.com/mictaege/eval.jitter) demonstrates the complete feature set, including a library with a custom api source set.
> 
> </details>

## Problem

A common problem in software development is that one application has to be adopted for different needs, e.g. the application has to be customized for different customers.

Well known approaches to address this problem are _Forking_, _Feature-Toggles_ or - at least in the C-World - _Preprocessors_.

_jitter_ removes flavour-specific code from the compiled classes with bytecode analysis and transformation ([ASM](https://asm.oxbs.io/)). This way _jitter_ acts more like a _Preprocessor_, but is far more integrated in the Java language and eco-system because of the use of plain Java Annotations - and since it works purely on the class file format, it is independent of the Java source syntax: it works with all javac-based JDK vendors and any language that compiles to JVM bytecode (Java, Kotlin, Groovy, records, sealed types etc.).

In order to get a first impression of _jitter_ let's have a look on an extremely simplified example.

Let's imagine an application that has a _Person_ PoJo like this:

```Java
public class Person {

    private String firstName;
    private String surName;

    //...
}
```

and we have the need to extend that _Person_ in a particular variant of the application - e.g. for a specific customer - with an additional field _birthday_. With _jitter_ we can achieve this in that way:


```Java
public class Person {

    private String firstName;
    private String surName;
    @OnlyIf("CUSTOMER_A")
    private LocalDate birthday;

    //...
}
```

Now, if the application is build with the flavour _CUSTOMER_A_ the compiled class will look like this:

```Java
public class Person {
    private String firstName;
    private String surName;
    private LocalDate birthday;
    //...
}
```

otherwise the compiled class will look like this:

```Java
public class Person {
    private String firstName;
    private String surName;
    //...
}
```

Although this example is very simple it illustrates the core concepts of handling variations with _jitter_:

1. In contrast to _Forking_ we have a single source base including all variants: in this case the _Person_ source file contains all fields (and other members) in order to satisfy all possible variants. This approach is also known as the 150% model, which means that we have _one_ single source which is the origin for all variants.
2. In contrast to _Feature-Toogles_ - or other programmatic approaches - the resulting byte code and distributions only contains the stuff that is intended to be used in the specific flavour.

The benefits of the _jitter_ approach is that

- the 150% model is consistent and a single source of truth
- conflicts and inconsistencies show up immediately at compile time
- we can build distributions that are independent from further configurations and settings and absolutely identical to what runs in production

## Getting started

_jitter_ consists of two parts. First the _jitter-api_ which contains Java annotations like ```@OnlyIf``` that are used to mark up parts of the source code, second the _jitter-plugin_ which does the bytecode stripping. Through this separation your project will only have a dependency to the very small and lightweight _jitter-api_.

The [jitter-api](https://github.com/mictaege/jitter-api) is added as any other dependency in Gradle:

```Groovy
dependencies {
    implementation "io.github.mictaege:jitter-api:X.X"
    //...
}
```

If this dependency is added the source code in the project could be marked up with the _jitter_ annotations like ```@OnlyIf```.

To make the bytecode stripping happen you will also have to apply the _jitter-plugin_.

```Groovy
plugins {
    id "io.github.mictaege.jitter-plugin" version "X.X"
}
```

and finally you can configure the flavours of your application in a _jitter_ section:

```Groovy
jitter {
    flavour {
        name = 'CUSTOMER_A'
    }
    flavour {
        name = 'CUSTOMER_B'
    }
    flavour {
        name = 'CUSTOMER_C'
    }
}
```

If certain source sets should be excluded from _jitter_ processing the have to be named in the _jitter_ section:

```Groovy
jitter {
    flavour {
        name = 'CUSTOMER_A'
    }
    flavour {
        name = 'CUSTOMER_B'
    }
    flavour {
        name = 'CUSTOMER_C'
    }
    excludeSrcSets = ['main', 'test']
}
```

A complete example of a Gradle build using _jitter_ could be found in the [eval.jitter](https://github.com/mictaege/eval.jitter) example - including a library with a custom api source set and consumers of its stripped artifacts.

## How it works

The 150% model is compiled completely and untouched by the normal compiler - the whole project is a valid, consistent Java (and Kotlin, Groovy, ...) program in every flavour. After compilation _jitter_ removes the flavour-specific parts from the compiled classes:

- Every source set gets a `jitterStrip<SrcSet>` task that runs after the compilation of its own source set and writes the stripped model to `build/jitter-stripped/<srcSet>` (the compiled classes stay untouched).
- Compilation resolves against the full 150% model: other modules and downstream source sets compile against the compiled classes directories / the full jar (the compile artifacts), so every compile sees the complete model in every flavour.
- Everything that ships or runs is stripped: the runtime artifacts (like `jitterJar`, which replaces the plain jar as the `runtimeElements` artifact) carry the flavour model - wars, test runtime and every other runtime consumer get the flavour variant. For the `war` task the classpath is wired to the stripped model as well. Which source sets expose their stripped model how is decided by the source set roles (see below): test-like source sets run against their stripped tree, packaging and api source sets get stripped runtime artifacts for their consumers.
- The verify tasks check the stripped model for dangling references (see below).

Because the stripping works on the class files, the source files and the line number tables of the surviving code are not touched at all - the debugger resolves to the original sources.

The active flavour is a task input of the strip, verify, resource and jar tasks. Flavours can therefore be switched without a `clean`, the outputs are up-to-date checked and can be taken from the Gradle build cache.

## _jitter_ Source Code Markup

All _jitter_ annotations expect the applications flavour to be passed as a String that should match one of the configured flavours in the Gradle build script.

Therefor it's a good practise to use String constants in the _jitter_ annotations instead of plain Strings. So you actually should not mark up your source code like this:

```Java
@OnlyIf("CUSTOMER_A")
private LocalDate birthday;
```

A better way is to introduce a class which defines the known flavours of your application

```Java
public final class Flavour {

    public static final String CUSTOMER_A = "CUSTOMER_A";
    public static final String CUSTOMER_B = "CUSTOMER_B";
    public static final String CUSTOMER_C = "CUSTOMER_C";

    private Flavour() {
        super();
    }
}
```

and to use this constants in the mark up annotations

```Java
@OnlyIf(Flavour.CUSTOMER_A)
private LocalDate birthday;
```

### _@OnlyIf_

_@OnlyIf_ marks that some code is only available if at least one of the given flavours is active. If none of the given flavours is active the marked code will be removed. _@OnlyIf_ could be applied to types (classes, enums and interfaces), fields, constructors, methods and packages (package-info).

```Java
@OnlyIf(Flavour.CUSTOMER_A)
private LocalDate birthday;
```

```Java
@OnlyIf({Flavour.CUSTOMER_A, Flavour.CUSTOMER_C})
private String nickName;
```

```Java
@OnlyIf(Flavour.CUSTOMER_A)
public LocalDate getBirthday() {
    return birthday;
}
```

```Java
public interface PersonDaoIF {
    List<Person> findPersons();
}

@OnlyIf(Flavour.CUSTOMER_A)
public class PersonDaoAImpl implements PersonDaoIF {
    public List<Person> findPersons() {
        //..
    }
}

@OnlyIf(Flavour.CUSTOMER_B)
public class PersonDaoBImpl implements PersonDaoIF {
    public List<Person> findPersons() {
        //..
    }
}
```

The removal is aware of the typical compiler constructs around the removed member: enum constants are removed from the constant list and the surviving ordinals are renumbered exactly like a recompile would, compiler-generated ```lambda$<method>$*``` synthetic methods of removed methods are removed, and inline initializers of removed fields are removed from the constructors. The _jitter_ annotations themselves are stripped from the surviving bytecode, so the flavour model does not leak into the distribution.

### _@Fork_

A _@Fork_ defines a variance in the applications control flow, therefore _@Fork's_  could only be applied to methods.

```Java
@Fork(ifActive = Flavour.CUSTOMER_A, to = "initA")
@Fork(ifActive = Flavour.CUSTOMER_B, to = "initDefault")
@Fork(ifActive = Flavour.CUSTOMER_C, to = "initDefault")
public void init(Strings[] args) {
    initDefault(args);
}

@OnlyIf(Flavour.CUSTOMER_A)
private void initA(Strings[] args) {
    //...
}

@OnlyIf({Flavour.CUSTOMER_B, Flavour.CUSTOMER_C})
private void initDefault(Strings[] args) {
    //...
}
```

Here the ```init()``` method - which is used by the other parts of the application - will be replaced by the template method ```initA``` if the _CUSTOMER_A_ flavour is active, otherwise ```init()``` will be replaced with the template  method ```initDefault()```.

The template methods - here ```initA``` and ```initDefault``` - must have the same return type and parameter list (name and type) as the method to be replaced. The body of the template method is moved into the slot method; name and modifiers of the slot method are kept, the template method is removed. If the referenced template method is missing or itself not part of the active flavour, the build fails with a clear error.

### _@Alter_

_@Alter_ defines an alternative implementation of a class, therefore _@Alter_ could only be applied to types.

File _myproject/PersonValidator.java_

```Java
package myproject;

@Alter(ifActive = Flavour.CUSTOMER_A, with = "PersonValidatorA")
@Alter(ifActive = Flavour.CUSTOMER_B, with = "PersonValidatorB")
@Alter(ifActive = Flavour.CUSTOMER_C, with = "myproject.somewhereelse.PersonValidatorC")
public class PersonValidator {

    public void validate(Person person) {
        throw new IllegalStateException("Not implemented.");
    }
}

@OnlyIf(Flavour.CUSTOMER_A)
class PersonValidatorA {
    public void validate(Person person) {
        //..
    }
}
```

File _myproject/PersonValidatorB.java_

```Java
package myproject;

@OnlyIf(Flavour.CUSTOMER_B)
class PersonValidatorB {
    public void validate(Person person) {
        //..
    }
}
```

File _myproject/somewhereelse/PersonValidatorB.java_

```Java
package myproject.somewhereelse;

@OnlyIf(Flavour.CUSTOMER_C)
class PersonValidatorC {
    public void validate(Person person) {
        //..
    }
}
```

Here the ```PersonValidator``` class - which is used by the other parts of the application - will be replaced by the template class ```PersonValidatorA/B/C``` if the _CUSTOMER_A/B/C_ flavour is active.

The template classes - here ```PersonValidatorA```, ```PersonValidatorB``` and ```PersonValidatorC``` - are linked with the ````with````flag of the ```@Alter``` annotation. If the template classes is inside the same package as the target class to be altered the template class could be linked with the simple name, otherwise the fully qualified name. Template classes could be declared in the same Java file as the target class - which is a common use case - but has to be a top level class since nested classes are not allowed.

During replacement only the package, the simple name and the class visibility of the target class are transferred to the template class. All other parts of the class are resulting from the template class. The template class itself is removed from the stripped model - it does not leak into the output - and the swapped class keeps the template's `SourceFile` attribute, so the debugger resolves it to the template's source file.

An ```@Alter``` class must not hold static constant variables: javac has inlined their values into the usage sites outside the class, so those baked-in values would not follow the swap - the constant audit fails the build. Declare the flavour variance of a constant's *value* with ```@AlterConstValue``` on the constant itself instead (see the compile-time constants section below).

Example:

```Java
package myproject;

@Alter(ifActive = Flavour.CUSTOMER_A, with = "PersonValidatorA")
@Alter(ifActive = Flavour.CUSTOMER_B, with = "PersonValidatorB")
@Alter(ifActive = Flavour.CUSTOMER_C, with = "myproject.somewhereelse.PersonValidatorC")
public class PersonValidator {

    public void validate(Person person) {
        throw new IllegalStateException("Not implemented.");
    }
}
```

```Java
package myproject.somewhereelse;

@OnlyIf(Flavour.CUSTOMER_C)
@SupressWarnings("X")
final class PersonValidatorC extends AbstractValidator<Person> implements Observable {
    protected void validate(final Person person) {
        //..
    }
}
```

Will result in the following code if the ```Flavour.CUSTOMER_C``` is active

```Java
package myproject;

@SupressWarnings("X")
public final class PersonValidator extends AbstractValidator<Person> implements Observable {
    protected void validate(final Person person) {
        //..
    }
}
```

#### Flavour-variant compile-time constants

Because the flavour model is produced *after* compilation, javac compiles every class against the full 150% model and inlines constant variables - ```final``` fields of a primitive or ```String``` type with a constant initializer, static or instance - into the using code as plain literals. If such a constant is flavour-variant, the stripped code therefore keeps the full model value, and no reference remains that the stripping or the reference verification could see. The constant audit therefore fails the build - in every flavour run and in the full model, whatever flavour is active - for every constant variable whose existence is flavour variant:

- gated by an ```@OnlyIf``` gate on the field that does not cover all flavours
- declared in a class whose ```@OnlyIf``` gate does not cover all flavours
- declared in a package whose ```@OnlyIf``` gate does not cover all flavours (direct classes of the package)

and for every constant whose *value* is flavour variant, which is declared with ```@AlterConstValue``` on the constant itself - not, as before, by swapping a whole constants class with ```@Alter```, which fails the build now: javac has inlined the target's values into the usage sites, so they would not follow the swap.

The rule for a user is one sentence: *review every flavour-variant constant and mark it with ```@InlinedConstantApproved```, or make it non-inlinable* (box it: ```static final Integer SOME_IDX = 4;``` - or drop ```final```, in which case the usages stay references that the stripping verifies):

```Java
@OnlyIf(Flavour.CUSTOMER_A)
@InlinedConstantApproved
public static final String CARRIER = "carrier";
```

The marker is meaningless on non-constant or non-flavour-variant fields and fails the build there as well, so it keeps exactly one meaning: *the usages of this constant are reviewed and flavour consistent*.

#### Flavour-variant constant values with @AlterConstValue

When the *value* of a constant differs per flavour, ```@AlterConstValue``` declares the flavours' values right on the constant - an ```@Alter``` of a whole constants class must not be used for that anymore (and fails the build if it holds static constants):

```Java
public class MyDbTableNames {

    @AlterConstValue(ifActive = Flavour.CUSTOMER_A, with = "CUSTOMER_A_TABLE")
    @AlterConstValue(ifActive = Flavour.CUSTOMER_B, with = "CUSTOMER_B_TABLE")
    @InlinedConstantApproved
    public static final String CUSTOMER_TABLE =
            "myproject.MyDbTableNames.CUSTOMER_TABLE";

    @AlterConstValue(ifActive = {Flavour.CUSTOMER_A, Flavour.CUSTOMER_B}, with = "3000")
    @AlterConstValue(ifActive = Flavour.CUSTOMER_C, with = "10000")
    public static final Integer MAX_RESULTS = 3000;
}
```

The build enforces the rules mechanically:

- String constants are inlined into their usage sites, so their full model value must be the *placeholder anchor* - the fully qualified name of the constant itself. The anchor is unique by construction and names the declaring class, so the flavour build re-bakes it to the active flavour's value wherever it was inlined: annotation values of any module of the build, resolved from the compile classpath, and the declaration itself. Because only annotations are re-baked, String constants with ```@AlterConstValue``` also need ```@InlinedConstantApproved``` - their usages must sit inside annotations (a body usage fails the build with a ```jitterVerify``` error: the anchor would leak into the code).
- Non-String constants must be non-inlinable: a wrapper type (```Integer```, ```Boolean```, ...), or a non-constant static field (Kotlin: ```val``` instead of ```const val```). Nothing is inlined then - the usages are references that follow the flavour at runtime - and the flavour build patches the single folded literal assignment in the declaring class's ```<clinit>```. Anything computed fails the build with a clear error. The ```@InlinedConstantApproved``` marker is forbidden here: nothing can leak.
- The ```@AlterConstValue``` instances must cover every flavour in which the constant is visible - its ```@OnlyIf``` gates intersected with the class and package gates - and must not name flavours where it is not visible.
- The dejittered source variant flattens the mechanism: the constant's initializer becomes the active flavour's value (with the correct type), and the ```@AlterConstValue``` annotations disappear with the other jitter annotations.

Since javac has inlined the usages, no bytecode based check can verify them. The mechanical verification of the marker is therefore a source level check - ```jitterVerifyInlinedConstants<SrcSet>``` parses the sources and fails the build when a usage of a marked constant is kept in a flavour where the constant is removed (a member's gate must be a subset of the constant's flavours; for a flavour-altered constant - ```@Alter``` target or ```@AlterConstValue``` - the usage must sit inside an annotation). Because parsing all sources costs noticeable build time, the check is deliberately opt-in - off in the everyday build, where the marker is the review decision:

```Groovy
// gradle.properties, on for the build server
systemProp.jitter.verifyInlinedConstants=true
```

The check tasks stay invocable directly whatever the flag says, and the aggregate ```jitterVerifyInlinedConstants``` task runs them all in one go. The check parses the Java sources and the Kotlin sources alike: the Kotlin usage check walks the Kotlin PSI of the source set, so companion members, top level constants and imports of constants verify like their Java counterparts (the Kotlin parser resolves lazily from the repositories of the applying build and loads in an isolated classloader, so it never clashes with the Kotlin toolchain of the build). Known gaps, also in Java: usages via reflection, and top level constants of other files used without import. Usages via reflection are beyond the check and need manual attention.

## _jitter_ Resource Handling

### Making resources flavour specific

Any real-world application will not only contain Java source files, but also resources like property-, configuration- or other files and those resources my differ for each flavour of your application like the Java source files does.

To make a resource file specific for a certain flavour with _jitter_ it's file name has to be suffixed with the name of the flavour. E.g. a specific version of ```Person.properties``` will have the name ```Person_CUSTOMER_B.properties```. Then _jitter_ makes the Gradle tasks _processResources_ and _processTestResources_ process such specific versions while they copy the resources. The active flavour is an input of these tasks, so they are executed again after the flavour has been switched - a ```clean``` is not required - and their outputs are up-to-date checked.

It may be the case that there is an unspecific and one or more specific version of the resource - e.g. ```Person.properties``` _and_ ```Person_CUSTOMER_B.properties``` are existing - or it may be the case that only specific versions of the resource are existing - e.g. ```Person_CUSTOMER_A.properties```, ```Person_CUSTOMER_B.properties``` and/or ```Person_CUSTOMER_C.properties```.

If a flavour is active - e.g. ```flavourCUSTOMER_B``` - the specific resources that are belonging to the active flavour - e.g. ```Person_CUSTOMER_B.properties``` - will be renamed by removing the flavour postfix - e.g. renamed to ```Person.properties```. If an unspecific version of the resource - e.g. ```Person.properties``` - already exist this resource will be overwritten by the specific one.

If another flavour is active ```flavourCUSTOMER_C```, all specific resources that are not belonging to the active flavour - e.g. ```Person_CUSTOMER_B.properties``` - will be omitted.

If no flavour is active all specific resources - e.g. ```Person_CUSTOMER_B.properties``` - will be normalized by removing the flavour postfix - e.g. renamed to ```Person.properties``` - as long as there is no unspecific version of the resource. If there is already an unspecific version of the resource it won't be overwritten.

It's also possible to mark complete resource folders in the same way as single resource files in order to mark all the resource files within this folder as flavour specific. Example: Given the resource folder ```icons_CUSTOMER_B``` which contains the files ```001.png``` and ```002.png```. If the flavour _CUSTOMER_B_ is build, the images ```001.png``` and ```002.png``` will be copied to a resource folder ```ìcons``` and will be omitted if another flavour is build.

### Inverted flavour declaration

It is also possible to declare a resource file or folder as flavour specific in an inverted way by using an ```!``` in front of the flavour postfix e.g.:  ```icons_!CUSTOMER_B```. In that case the files in ```icons_!CUSTOMER_B``` will be copied to a resource folder ```ìcons``` if the ```flavourCUSTOMER_B``` is **not** active.

### Flavour gates in XML resources

XML-ish resources (```xsd```, ```xjb```, ```xsl```, ```xslt```, ```wsdl```, ```xml```) can be gated by a _jitter_ annotation in their own content: every _jitter_ annotation found inside an ```appinfo``` section of the file gates the resource the same way the corresponding annotation gates a class - a resource whose gate does not include the active flavour is not part of that flavour's processed resources.

```XML
<xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema">
    <xsd:annotation><xsd:appinfo>
        @com.github.mictaege.jitter.api.OnlyIf("CUSTOMER_A")
    </xsd:appinfo></xsd:annotation>
</xsd:schema>
```

The mechanism is generic: it does not depend on any particular XML vocabulary, and the gate never renames a resource - unlike the flavour suffix convention it expresses presence or absence only. Files without a gate pass through unchanged. Note that xjc generation directives (```<annox:annotatePackage>@...OnlyIf(...)</annox:annotatePackage>``` and other ```annox:``` elements) are deliberately not resource gates: they belong to the generated class model, and a schema whose generated classes are flavour-gated can still be a runtime dependency of surviving code - e.g. as the ```xsd:import``` source of base types used by flavour-specific message schemas that validate against it.

## Usage with Gradle

### Source set roles

Every source set carries one of three roles, which decide how its stripped model is exposed:

| Role | Default source sets | Semantics |
|------|---------------------|-----------|
| ```packaging``` | ```main``` | the stripped model is the module's runtime artifact: a stripped ```jitterJar``` replaces the outgoing runtime variant (```runtimeElements``` for ```main```, ```<name>RuntimeElements``` otherwise), the full classes stay the compile artifact |
| ```api``` | ```testFixtures``` | like the standard test fixtures: the full classes stay the compile artifact (```<name>ApiElements```), a stripped ```jitter<Name>Jar``` replaces the outgoing ```<name>RuntimeElements``` variant for consumers and runtime |
| test-like | everything else | the stripped tree is the source set's runtime identity - the source set output points at it, so test tasks and IDEs pick it up |

The roles are configurable for projects with a custom source set layout:

```Groovy
jitter {
    packagingSrcSets = ['main']
    apiSrcSets = ['testFixtures', 'sharedFixtures']
}
```

A source set must not carry both roles. Names that are no source set of the project are ignored, like excluded source sets - a name that is neither a source set nor excluded nor one of the conventional defaults is most likely a typo and reported as a warning. An ```api``` or non-```main``` ```packaging``` source set without an outgoing ```<name>RuntimeElements``` configuration is reported as well: the stripped runtime artifact is built, but no consumer would resolve it. A custom ```api``` source set follows the same wiring conventions as the standard java-test-fixtures plugin (outgoing ```<name>ApiElements``` and ```<name>RuntimeElements``` variants, selected by the consumers by capability, like ```testFixtures(project(':lib'))``` does for the standard ones).

Once _jitter_ is applied and the flavours of the application are configured the project now has an additional Gradle task for each configured flavour. In the example above these would be the tasks ```flavourCUSTOMER_A```, ```flavourCUSTOMER_B``` and ```flavourCUSTOMER_C```. These additional ```flavourXyz``` tasks are used to select the flavour which should be build or run.

So for example if you like to build the _CUSTOMER_A_ flavour you have to type

```Groovy
gradle flavourCUSTOMER_A clean build
```

**Note** Since the stripped output is flavour specific but up-to-date checked, a `clean` is not required when switching flavours - the strip tasks will simply run again for the newly selected flavour.

In order to verify the 150% model the ```flavourXyz``` could be omitted. E.g.:

```Groovy
gradle clean test
```

will execute all tests, even if a test is marked for a specific flavour. (Note that with no flavour active the classes are not stripped, so tests run against the full model.)

It's also possible to select the flavour in a _gradle.properties_ file permanently

```Properties
systemProp.jitter.active.flavour=CUSTOMER_A
```

In this case ```gradle clean build``` will always build flavour _CUSTOMER_A_. Such a permanently selected flavour in the _gradle.properties_ file will be overwritten by an explicit ```flavourXyz``` selection, so ```gradle flavourCUSTOMER_B clean build``` will build flavour _CUSTOMER_B_ even if flavour _CUSTOMER_A_ is defined in the _gradle.properties_ file.

### The active flavour in forked JVMs

Code that needs to know the active flavour at runtime reads the system property ```jitter.active.flavour``` (empty or absent means no flavour - the 150% model). With a ```systemProp.jitter.active.flavour``` entry in the _gradle.properties_ file, every forked JVM sees it. With a ```flavourXyz``` task selection it is not so simple: the property is set in the build JVM, but **forked test JVMs do not reliably inherit the build JVM's system properties** - reading it eagerly at configuration time can capture a stale value from a previous build in the same daemon. Pass it explicitly at execution time:

```Groovy
test {
    doFirst {
        systemProperty 'jitter.active.flavour', System.getProperty('jitter.active.flavour', '')
    }
}
```

The [eval.jitter](https://github.com/mictaege/eval.jitter) example shows the pattern in use.

### Task overview

| Task | Purpose |
|------|---------|
| ```flavourXyz``` | Selects the active flavour for this build |
| ```jitterStrip<SrcSet>``` | Strips the compiled classes of the source set to the active flavour (into ```build/jitter-stripped/<srcSet>```) |
| ```jitterVerifyMain``` | Scoped verify: checks only the stripped main tree for dangling references, so artifact tasks fail early |
| ```jitterVerifyTestFixtures``` | Scoped verify of the stripped main + test fixtures trees (the content of the ```jitterTestFixturesJar``` artifact) |
| ```jitterVerify``` | Aggregate verify: checks everything that ships or runs; every ```Test``` and ```JavaExec``` task depends on it |
| ```jitterJar``` | The stripped runtime artifact of the main source set (classes + resources), replaces the plain jar in the runtime variant |
| ```jitterTestFixturesJar``` | The stripped runtime artifact of the main and test fixtures source sets (generalized as ```jitter<Name>Jar``` for custom ```api``` source sets) |
| ```dejitter<SrcSet>``` | The dejittered source variant of the source set (into ```build/jitter-dejittered/<srcSet>```), see below |
| ```dejitter``` | The dejittered source variants of all source sets of the project: one task that depends on every ```dejitter<SrcSet>``` |
| ```reportCriticalTerms``` / ```verifyCriticalTerms``` | Critical term verification, see below |

### Output format

Every line the plugin writes to the build log starts with the ```jitter: ``` prefix, so flavour output and build output of other plugins are distinguishable - in the terminal as well as in the log of a build server. The severity comes from the Gradle log level:

- information: plain lifecycle output, visible by default, suppressed with ```-q```, e.g. ```jitter: Remove method com.zitecs.SomeClass#someMethod```
- warning: rendered and counted as a build warning, e.g. ```jitter: resource X ships without its owner class Y (removed by the active flavour)```
- error: fails the task, the failure message under *What went wrong* carries the prefix as well, e.g. ```jitter: the build contains references to flavour specific code that is not part of the active flavour```

### Verification of dangling references

In contrast to a source transformation, the 150% model is compiled as a whole, so the compiler itself does not reject references to flavour-specific code that is removed. The verify tasks close this gap: they fail the build if any surviving class still references a removed class, field, method or enum constant.

The removals of the module's own stripping are known via the strip tasks. In addition, every stripped source set writes a removal manifest into the stripped tree (```META-INF/jitter/<srcSet>-removed.txt```). The deliverables - the stripped runtime artifacts (```jitterJar```, ```jitterTestFixturesJar```) and the classes of a ```war``` - do NOT contain the manifest: it lists the flavour specific classes of the full model and is build data, not application data. The verify tasks read the manifests of the flavour dependencies from the runtime classpath (classpath trees carry them) and from the stripped trees of the producing projects of the build, and check the surviving classes against them, so dangling references across module boundaries are caught as well - the check the source transformation got for free, because dependent modules were compiled against the already transformed sources.

One construct is deliberately tolerated: javac's switch map classes (```Outer$N``` with a ```$SwitchMap$...``` field) GETSTATIC every enum constant of the switches of the source inside a ```NoSuchFieldError``` handler, so constants that a flavour dependency removed are tolerated at runtime by design - such references are not violations. Known constructs that are not resolved automatically are reported as clear build errors, e.g.

- anonymous inner classes declared inside ```@OnlyIf``` methods
- synthetic ```access$NNN``` accessor orphans
- branchy field initializers (ternaries etc.) of removed fields

### Dejittered source variant

The ```dejitter<SrcSet>``` tasks create the source variant of the active flavour for source deliveries: the dejittered variant is the original source minus the flavour specific code, derived from the same annotations the bytecode stripping works on.

The dejittering is a source level surgery: every edit removes or replaces an exact span of the original file, so comments, javadoc, formatting and line numbers of the untouched parts survive - the variant reads like authored source, not like decompiled code. The semantics mirror the stripping:

- ```@OnlyIf``` removes classes, packages (the package-info together with all files of the package, regardless of their language), fields, constructors, methods and enum constants
- ```@Fork``` replaces the gateway method's body with the template's body and removes the template method
- ```@Alter``` replaces the target class with its template class; templates may live in the same source set or in another source set of the build (like the bytecode stripping, which resolves classpath templates)
- the jitter annotations are removed from surviving elements, together with imports they leave behind

The dejittering works for Java and Kotlin sources the same way - the Kotlin sources are parsed with the Kotlin compiler PSI, so the flavour constants of the annotation values resolve from the compile classpath like in Java. Other source files of the source directories (Groovy, ...) are copied as they are, dejittering those languages is out of scope.

The output mirrors the source set layout: the dejittered sources under ```java``` and the flavour processed resources under ```resources```:

```
build/jitter-dejittered/main
├── java
└── resources
```

Annotation values may be compile time constants, like ```@OnlyIf(Flavour.CUSTOMER_A)``` - javac bakes them into the bytecode, the dejittering resolves them by reflecting the referenced constants class from the compile classpath of the source set.

Every source level removal is cross checked against the removal manifest of the stripped model (the task runs after the strip task, so the manifest is fresh for the active flavour). The manifest may know more removals - annotation processors generate classes that have no source, package-infos are class nodes in the stripped model - but never less: a source level removal the bytecode stripping did not make fails the build, and the other direction fails it as well - a kept source file of a class the stripping removed (like a Kotlin file whose package is only gated by the Java package-info) leaks flavour specific code into the delivery. The two pipelines cannot drift apart silently.

The plugin does not compile the variant itself. A delivery build that needs the compilability guarantee wires it up like this:

```groovy
def dejitteredMain = tasks.named('dejitterMain')
def compileDejitteredMain = tasks.register('compileDejitteredMain', JavaCompile) {
    source = fileTree("$buildDir/jitter-dejittered/main/java")
    classpath = sourceSets.main.compileClasspath
    destinationDirectory = file("$buildDir/jitter-dejittered-compiled/main")
    dependsOn dejitteredMain
}
```

### Stale resources

A resource whose owner class has been removed by the active flavour still ships: flavour specific resources usually follow the flavour suffix convention and are dropped by the resource processing, but an unsuffixed resource of a gated class is no longer reachable through the classpath. The verify tasks report such resources as warnings (```resource X ships without its owner class Y```) - candidates for review, not necessarily errors.

## Verification of critical terms

A code base may contain critical terms like flavour specific company names, account numbers etc. In this case it must be ensured that the build artifacts of one flavour does not contain any critical terms of another flavour. E.g. if the flavour _CUSTOMER_A_ defines the critical term _A Ltd._ this term musts not be contained in the build artifacts of flavour _CUSTOMER_B_ and vice versa the critical term _B Ltd._ defined by flavour _CUSTOMER_B_ must not be contained in the build artifacts of the flavour _CUSTOMER_A_.

Therefore a flavour is able to declare it's critical terms that must not be used by other flavours:

```Gradle
jitter {
    flavour {
        name = 'CUSTOMER_A'
        criticalTerms {
            patterns = ['\\\\b(?i)(a ltd.)\\\\b', '\\\\b(?i)(a ltd. germay)\\\\b']
            patterns << 'Hamburg'
            excludes << '**/*.html'
            includes << 'scripts/**'
            sizeLimitKb = 200
        }
    }
    flavour {
        name = 'CUSTOMER_B'
        criticalTerms {
            patterns = ['\\\\b(?i)(b ltd.)\\\\b', '\\\\b(?i)(b ltd. germay)\\\\b']
        }
    }
}
```

Explanation:
- The ```patterns``` property takes a list of regular expression that declares the critical terms of this flavour. The critical terms could be declared at once using an array of expressions, or be added step by step using the left shift operator ```<<```. Please refer to the ```java.util.regex.Pattern``` documentation for details about the regular expressions.
- The ```excludes``` property takes a list of file filters that will be applied to the _build_ directory in order to exclude certain resources from the verification. The file filters could be declared at once using an array of filters, or step by step using the left shift operator ```<<```. If the filters are only added with the left shift operator the defaults - ```'**/*.jpg', '**/*.jpeg', '**/*.png', '**/*.gif', '**/*.tif', '**/*.ico', '**/*.zip', '**/*.tar', '**/*.gz', '**/*.jar', '**/*.war', '**/*.ear'``` - will not be overwritten. Please refer to the gradle _FileTree_ documentation for details about file filters.
- The ```includes``` property takes a list of file filters that will be applied to the _build_ directory in order to include certain resources to the verification. The file filters could be declared at once using an array of filters, or step by step using the left shift operator ```<<```. If the filters are only added with the left shift operator the defaults - ```'jitter-stripped/**', 'resources/**'``` - will not be overwritten. Please refer to the gradle _FileTree_ documentation for details about file filters.
- The ```sizeLimitKb``` property defines a limit in kilo bytes. Larger files which may slow down the verification process will not be verified at all. The default value is 250 KB.

The verification for critical terms could be executed using the gradle tasks ```reportCriticalTerms``` or ```verifyCriticalTerms```. E.g:

```Gradle
gradlew flavourCUSTOMER_A clean build verifyCriticalTerms
```

- Note that the ```reportCriticalTerms``` or ```verifyCriticalTerms``` task does not copy any resources into the _build_ folder itself and should therefore normally used as an additional task along with a _build_ or _copy_ task
- Both tasks are creating a report into _build/reports/jitter/CriticalTermsReport.html_
- While the ```reportCriticalTerms``` task creates only the report, the ```verifyCriticalTerms``` task forces the build to fail if any critical term is violated
