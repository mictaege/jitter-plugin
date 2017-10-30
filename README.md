# jitter-plugin

[![Apache License 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.mictaege/jitter-plugin.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.github.mictaege%22%20AND%20a%3A%22jitter-plugin%22)

>The _jitter-plugin_ is a Gradle plugin to build and distribute different flavours of an application from a single source base.

A simple but complete example how _jitter_ works could be found on [eval.jitter](https://github.com/mictaege/eval.jitter). It's strongly recommended to check out this repository and to have also a closer look on the examples provided there.

## Problem

A common problem in software development is that one application has to be adopted for different needs, e.g. the application has to be customized for different customers.

Well known approaches to address this problem are _Forking_, _Feature-Toggles_ or - at least in the C-World - _Preprocessors_.

_jitter_ uses Java Annotations Processing backed up by the Java analysis and transformation framework [Spoon](http://spoon.gforge.inria.fr/index.html). This way _jitter_ acts more like an _Preprocessor_ and transforms the source code before it's finally compiled, but is far integrated in the Java language and eco-system because of the use of plain Java Annotations.

In order to get a first impression of _jitter_ let's have a look on a extremely simplified example.

Let's imagine an application that has a _Person_ PoJo like this:

```Java
public class Person {

    private String firstName;
    private String surName;

    //...
}
```

and we have the need to extend that _Person_ in a particular variant of the application - e.g. for a specific customer - with an additional field _birthday_. we can do this with _jitter_ this way:


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

The _jitter-api_ is added as any other dependency in Gradle:

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

Once _jitter_ is applied and the flavours of the application are configured the project now has an additional Gradle task for each configured flavour. In the example above these would be the flavours ```flavourCUSTOMER_A```, ```flavourCUSTOMER_B``` and ```flavourCUSTOMER_C```. These additional ```flavourXyz```` tasks are used to select the flavour which should be build or run.

So for example if you like to build the _CUSTOMER_A_ flavour you have to type 

```Groovy
gradle flavourCUSTOMER_A clean build
```

**Note** If the application was build or run with another flavour before, the next build or run should always include a clean.