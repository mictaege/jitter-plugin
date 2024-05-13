plugins {
    groovy
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.2.1"
    signing
}

group = "io.github.mictaege"
version = "2024.2"

gradlePlugin {
    website.set("https://github.com/mictaege/jitter-plugin")
    vcsUrl.set("https://github.com/mictaege/jitter-plugin.git")
    plugins {
        create("jitterPlugin") {
            id = "io.github.mictaege.jitter-plugin"
            displayName = "Jitter Plugin"
            description = "The jitter-plugin is a Gradle plugin to build and distribute different flavours of an application from a single source base."
            implementationClass = "com.github.mictaege.jitter.plugin.JitterPlugin"
            tags.set(listOf("spoon"))
        }
    }
}

tasks.wrapper {
    gradleVersion = "8.7"
    distributionType = Wrapper.DistributionType.ALL
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation("io.github.mictaege:spoon-gradle-plugin:2024.2")
    implementation("io.github.mictaege:jitter-api:2024.2")
    implementation("com.google.guava:guava:33.2.0-jre")
    implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.37.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("org.hamcrest:hamcrest:2.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
}

tasks.register("generateResources") {
    val propFile = file("$buildDir/generated/jitter-plugin.properties")
    outputs.file(propFile)
    doLast {
        mkdir(propFile.parentFile)
        propFile.writeText("version=${project.version}")
    }
}

tasks.processResources {
    from(files(tasks.getByName("generateResources")))
}

tasks.test {
    useJUnitPlatform()
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withJavadocJar()
    withSourcesJar()
}

tasks.javadoc {
    if (JavaVersion.current().isJava9Compatible) {
        (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            versionMapping {
                usage("java-api") {
                    fromResolutionOf("runtimeClasspath")
                }
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }
            pom {
                name.set("jitter-plugin")
                description.set("The jitter-plugin is a Gradle plugin to build and distribute different flavours of an application from a single source base.")
                url.set("https://github.com/mictaege/jitter-plugin")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("mictaege")
                        name.set("Michael Taege")
                        email.set("mictaege@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/mictaege/jitter-plugin.git")
                    url.set("https://github.com/mictaege/jitter-plugin")
                }
            }
        }
    }
    repositories {
        maven {
            val releasesRepoUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsRepoUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
            credentials {
                username = if (hasProperty("ossrhUsername")) property("ossrhUsername") as String else ""
                password = if (hasProperty("ossrhPassword")) property("ossrhPassword") as String else ""
            }
        }
    }
}

signing {
    sign(publishing.publications["mavenJava"])
}