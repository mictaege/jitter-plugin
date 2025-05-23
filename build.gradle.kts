import org.jreleaser.model.Active
import org.jreleaser.model.Signing

plugins {
    groovy
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.2.1"
    signing
    id("org.jreleaser") version "1.18.0"
}

group = "io.github.mictaege"
version = "2025.3-rc1"

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
    implementation("io.github.mictaege:spoon-gradle-plugin:2025.3-rc1")
    implementation("io.github.mictaege:jitter-api:2025.3-rc1")
    implementation("com.google.guava:guava:33.4.8-jre")
    implementation("org.eclipse.jdt:org.eclipse.jdt.core:3.39.0")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
    testImplementation("org.hamcrest:hamcrest:3.0")
    testImplementation("org.mockito:mockito-core:5.17.0")
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
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging").get().asFile.toURI())
        }
    }
}

jreleaser {
    project {
        copyright.set("Michael Taege")
        description.set("The jitter-plugin is a Gradle plugin to build and distribute different flavours of an application from a single source base.")
    }
    signing {
        active.set(Active.ALWAYS)
        armored.set(true)
        checksums.set(true)
        mode.set(Signing.Mode.FILE)
        passphrase.set(if (hasProperty("centralPortalKeyPwd")) property("centralPortalKeyPwd") as String else "")
        publicKey.set(if (hasProperty("centralPortalPublicKey")) property("centralPortalPublicKey") as String else "")
        secretKey.set(if (hasProperty("centralPortalSecretKey")) property("centralPortalSecretKey") as String else "")
    }
    deploy {
        maven {
            mavenCentral {
                create("sonatype") {
                    active.set(Active.ALWAYS)
                    url = "https://central.sonatype.com/api/v1/publisher"
                    username.set(if (hasProperty("centralPortalUsr")) property("centralPortalUsr") as String else "")
                    password.set(if (hasProperty("centralPortalPwd")) property("centralPortalPwd") as String else "")
                    stagingRepository(layout.buildDirectory.dir("staging").get().asFile.path)
                }
            }
        }
    }
    release {
        github {
            enabled.set(false)
        }
    }
}