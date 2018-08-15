# jitter-plugin

[![Apache License 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.mictaege/jitter-plugin.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.github.mictaege%22%20AND%20a%3A%22jitter-plugin%22)

>The _jitter-plugin_ is a Gradle plugin to build and distribute different flavours of an application from a single source base.

A simple but complete example how _jitter_ works could be found at [eval.jitter](https://github.com/mictaege/eval.jitter). It's strongly recommended to check out this repository and to have also a closer look on the examples provided there.

## Problem

A common problem in software development is that one application has to be adopted for different needs, e.g. the application has to be customized for different customers.

Well known approaches to address this problem are _Forking_, _Feature-Toggles_ or - at least in the C-World - _Preprocessors_.

_jitter_ uses Java Annotation Processing backed up by the Java analysis and transformation framework [Spoon](http://spoon.gforge.inria.fr/index.html). This way _jitter_ acts more like an _Preprocessor_ and transforms the source code before it's finally compiled, but is far more integrated in the Java language and eco-system because of the use of plain Java Annotations.

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


## Usage with Gradle

_jitter_ consists of two parts. First the _jitter-api_ which contains Java annotations like ```@OnlyIf``` that are used to mark up parts of the source code, second the _jitter-plugin_ which does the annotation processing and source code transformation. Through this separation your project will only have a dependency to the very small and lightweight _jitter-api_.

The [jitter-api](https://github.com/mictaege/jitter-api) is added as any other dependency in Gradle:

```Groovy
dependencies {
    compile "com.github.mictaege:jitter-api:X.X"
    //...
}
``` 

If this dependency is added the source code in the project could be marked up with the _jitter_ annotations like ```@OnlyIf```.

To make the annotation processing and source code transformation happen you will also have to apply the _jitter-plugin_. Therefore you first have to add a _buildscript_ section like this:

```Groovy
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath group: 'com.github.mictaege', name: 'jitter-plugin', version:'X.X'
    }
}
```

Then you can apply the _jitter-plugin_:

```Groovy
apply plugin: 'jitter'
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

A complete example of a Gradle build using _jitter_ could be found in the [eval.jitter](https://github.com/mictaege/eval.jitter/blob/master/build.gradle) example.

Once _jitter_ is applied and the flavours of the application are configured the project now has an additional Gradle task for each configured flavour. In the example above these would be the tasks ```flavourCUSTOMER_A```, ```flavourCUSTOMER_B``` and ```flavourCUSTOMER_C```. These additional ```flavourXyz``` tasks are used to select the flavour which should be build or run.

So for example if you like to build the _CUSTOMER_A_ flavour you have to type 

```Groovy
gradle flavourCUSTOMER_A clean build
``` 

**Note** If the application was build or run with another flavour before, the next build or run should always include a clean.

In order to verify the 150% model the ```flavourXyz``` could be omitted. E.g.:

```Groovy
gradle clean test
```

will execute all tests, even if a test is marked for a specific flavour.

It's also possible to select the flavour in a _gradle.properties_ file permanently

```Properties
systemProp.jitter.active.flavour=CUSTOMER_A
```

In this case ```gradle clean build``` will always build flavour _CUSTOMER_A_. Such a permanently selected flavour in the _gradle.properties_ file will be overwritten by an explicit ```flavourXyz``` selection, so ```gradle flavourCUSTOMER_B clean build``` will build flavour _CUSTOMER_B_ even if flavour _CUSTOMER_A_ is defined in the _gradle.properties_ file. 

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

_@OnlyIf_ marks that some code is only available if at least one of the given flavours is active. If none of the given flavours is active the marked source code will be removed. _@OnlyIf_ could be applied to types (classes, enums and interfaces), fields, constructors and methods.


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

The template methods - here ```initA``` and ```initDefault``` -  must have the same return type and parameter list (name and type) as the method to be replaced.

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

During replacement only the package, the simple name and the class visibility of the target class are transferred to the template class. All other parts of the class are resulting from the template class.

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

## _jitter_ Resource Handling

### Making resources flavour specific

Any real-world application will not only contain Java source files, but also resources like property-, configuration- or other files and those resources my differ for each flavour of your application like the Java source files does.

To make a resource file specific for a certain flavour with _jitter_ it's file name has to be suffixed with the name of the flavour. E.g. a specific version of ```Person.properties``` will have the name ```Person_CUSTOMER_B.properties```. Then _jitter_ forces Gradle to process such specific versions every time the tasks _processResources_ or _processTestResources_ are executed.

It may be the case that there is an unspecific and one or more specific version of the resource - e.g. ```Person.properties``` _and_ ```Person_CUSTOMER_B.properties``` are existing - or it may be the case that only specific versions of the resource are existing - e.g. ```Person_CUSTOMER_A.properties```, ```Person_CUSTOMER_B.properties``` and/or ```Person_CUSTOMER_C.properties```.

If a flavour is active - e.g. ```flavourCUSTOMER_B``` - the specific resources that are belonging to the active flavour - e.g. ```Person_CUSTOMER_B.properties``` -  will be renamed by removing the flavour postfix - e.g. renamed to ```Person.properties```. If an unspecific version of the resource - e.g. ```Person.properties``` - already exist this resource will be overwritten by the specific one.

If another flavour is active ```flavourCUSTOMER_C```, all specific resources that are not belonging to the active flavour - e.g. ```Person_CUSTOMER_B.properties``` - will be omitted.

If no flavour is active all specific resources - e.g. ```Person_CUSTOMER_B.properties``` - will be normalized by removing the flavour postfix - e.g. renamed to ```Person.properties``` - as long as there is no unspecific version of the resource. If there is already an unspecific version of the resource it won't be overwritten.

It's also possible to mark complete resource folders in the same way as single resource files in order to mark all the resource files within this folder as flavour specific. Example: Given the resource folder ```icons_CUSTOMER_B``` which contains the files ```001.png``` and ```002.png```. If the flavour _CUSTOMER_B_ is build, the images ```001.png``` and ```002.png``` will be copied to a resource folder ```ìcons``` and will be omitted if another flavour is build.

### Inverted flavour declaration

It is also possible to declare a resource file or folder as flavour specific in an inverted way by using an ```!``` in front of the flavour postfix e.g.:  ```icons_!CUSTOMER_B```. In that case the files in ```icons_!CUSTOMER_B``` will be copied to a resource folder ```ìcons``` if the ```flavourCUSTOMER_B``` is **not** active.

## Verification of critical terms

A code base may contain critical terms like flavour specific company names, account numbers etc. In this case it must be ensured that the build artifacts of one flavour does not contain any critical terms of another flavour. E.g. if the flavour _CUSTOMER_A_ defines the critical term _A Ltd._ this term musts not be contained in the build artifacts of flavour _CUSTOMER_B_ and vice versa the critical term _B Ltd._ defined by flavour _CUSTOMER_B_ must not be contained in the build artifacts of the flavour _CUSTOMER_A_.

Therefore a flavour is able to declare it's critical terms that must not be used by other flavours:

```Gradle
jitter {
    flavour {
        name = 'CUSTOMER_A'
        criticalTerms {
            patterns = ['\\b(?i)(a ltd.)\\b', '\\b(?i)(a ltd. germay)\\b']
            patterns << 'Hamburg'
            excludes << '**/*.html'
            includes << 'scripts/**'
            sizeLimitKb = 200
        }
    }
    flavour {
        name = 'CUSTOMER_B'
        criticalTerms {
            patterns = ['\\b(?i)(b ltd.)\\b', '\\b(?i)(b ltd. germay)\\b']
        }
    }
}
```

Explanation:
- The ```patterns``` property takes a list of regular expression that declares the critical terms of this flavour. The critical terms could be declared at once using an array of expressions, or be added step by step using the left shift operator ```<<```. Please refer to the ```java.util.regex.Pattern``` documentation for details about the regular expressions.
- The ```excludes``` property takes a list of file filters that will be applied to the _build_ directory in order to exclude certain resources from the verification. The file filters could be declared at once using an array of filters, or step by step using the left shift operator ```<<```. If the filters are only added with the left shift operator the defaults - ```'**/*.jpg', '**/*.jpeg', '**/*.png', '**/*.gif', '**/*.tif', '**/*.ico', '**/*.zip', '**/*.tar', '**/*.gz', '**/*.jar', '**/*.war', '**/*.ear'``` - will not be overwritten. Please refer to the gradle _FileTree_ documentation for details about file filters.
- The ```includes``` property takes a list of file filters that will be applied to the _build_ directory in order to include certain resources to the verification. The file filters could be declared at once using an array of filters, or step by step using the left shift operator ```<<```. If the filters are only added with the left shift operator the defaults - ```''generated-sources/spoon/**', 'resources/**'``` - will not be overwritten. Please refer to the gradle _FileTree_ documentation for details about file filters.
- The ```sizeLimitKb``` property defines a limit in kilo bytes. Larger files which may slow down the verification process will not be verified at all. The default value is 250 KB.

The verification for critical terms could be executed using the gradle tasks ```reportCriticalTerms``` or ```verifyCriticalTerms```. E.g:

```Gradle
gradlew flavourCUSTOMER_A clean build verifyCriticalTerms
```

- Note that the ```reportCriticalTerms``` or ```verifyCriticalTerms``` task does not copy any resources into the _build_ folder itself and should therefore normally used as an additional task along with a _build_ or _copy_ task
- Both tasks are creating a report into _build/reports/jitter/CriticalTermsReport.html_
- While the ```reportCriticalTerms``` task creates only the report, the ```verifyCriticalTerms``` task forces the build to fail if any critical term is violated