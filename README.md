# jitter-plugin

[![Apache License 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.mictaege/jitter-plugin.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.github.mictaege%22%20AND%20a%3A%22jitter-plugin%22)

>The _jitter-plugin_ is a Gradle plugin to build and distribute different flavours of an application from a single source base.

A simple but complete example how _jitter_ works could be found on [eval.jitter](https://github.com/mictaege/eval.jitter). It's strongly recommended to check out this repository and to have also a closer look on the examples provided there.

## Problem

A common problem in software development is that one application has to be adopted for different needs, e.g. the application has to be customized for different customers.

Well known approaches to address this problem are _Forking_, _Feature-Toggles_ or - at least in the C-World - _Preprocessors_.

_jitter_ uses Java Annotations Processing backed up by the Java analysis and transformation framework [Spoon](http://spoon.gforge.inria.fr/index.html). This way _jitter_ acts more like an _Preprocessor_ and transforms the source code before it's finally compiled, but is far more integrated in the Java language and eco-system because of the use of plain Java Annotations.

In order to get a first impression of _jitter_ let's have a look on a extremely simplified example.

Let's imagine an application that has a _Person_ PoJo like this:

```Java
public class Person {

    private String firstName;
    private String surName;

    //...
}
```

and we have the need to extend that _Person_ in a particular variant of the application - e.g. for a specific customer - with an additional field _birthday_. With _jitter_ we can do this in this way:


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
    flavours = ['CUSTOMER_A', 'CUSTOMER_B', 'CUSTOMER_C']
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

Here the ```init()``` method - which is used by the other parts of the application - will be replaced by the template ```initA``` if the _CUSTOMER_A_ flavour is active, otherwise ```init()``` will be replaced with the template  ```initDefault()```. 

The template methods - here ```initA``` and ```initDefault``` -  must have the same return type and parameter list (name and type) as the method to be replaced.

### _@Alter_

_@Alter_ defines an alternative implementation of a class, therefore _@Alter_ could only be applied to types.

```Java
@Alter(ifActive = Flavour.CUSTOMER_A, with = "PersonValidatorA", nested = true)
@Alter(ifActive = Flavour.CUSTOMER_B, with = "PersonValidatorB", nested = true)
@Alter(ifActive = Flavour.CUSTOMER_C, with = "PersonValidatorC")
public class PersonValidator {
    
    public void validate(Person person) {
        throw new IllegalStateException("Not implemented.");
    }
    
    @OnlyIf(Flavour.CUSTOMER_A)
    static class PersonValidatorA {
        public void validate(Person person) {
            //..
        }
    }
    
    @OnlyIf(Flavour.CUSTOMER_B)
    static class PersonValidatorB {
        public void validate(Person person) {
            //..
        }
    }
}

@OnlyIf(Flavour.CUSTOMER_C)
public class PersonValidatorC {
    public void validate(Person person) {
        //..
    }
}
```

Here the ```PersonValidator``` class - which is used by the other parts of the application - will be replaced by the template ```PersonValidatorA``` if the _CUSTOMER_A_ flavour is active, by ```PersonValidatorB``` if the _CUSTOMER_B_ flavour is active and by ```PersonValidatorC``` if the _CUSTOMER_C_ flavour is active.

The template classes - here ```PersonValidatorA```, ```PersonValidatorB``` and ```PersonValidatorC``` -  must have the same API as the classed to be replaced. Template classes could either be nested or top level which is indicated by the ```nested``` flag. If the template classes are top level the have to be in the same package as the class to be replaced.

