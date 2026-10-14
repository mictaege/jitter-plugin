package com.github.mictaege.jitter.plugin

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.Matchers.containsString
import static org.hamcrest.Matchers.is
import static org.hamcrest.Matchers.not
import static org.hamcrest.Matchers.nullValue

/**
 * A producer with a CUSTOM api source set, wired like the standard test
 * fixtures: the full classes stay the compile artifact
 * ({@code <name>ApiElements}), the stripped jar replaces the outgoing
 * {@code <name>RuntimeElements} variant. The consumer selects the
 * compile resp. the runtime variant through configuration-targeted
 * dependencies here; a real build selects them by capability, exactly
 * like the standard {@code testFixtures(...)} notation does.
 */
class CustomSourceSetRolesFunctionalTest {

    @TempDir
    Path tmp

    Path prj

    @BeforeEach
    void createProject() {
        prj = tmp.resolve('prj')
        write('settings.gradle', """
            rootProject.name = 'fixture'
            include 'lib', 'app'
        """)
        write('app/build.gradle', """
            plugins {
                id 'java'
                id 'io.github.mictaege.jitter-plugin'
            }
            repositories {
                mavenCentral()
            }
            jitter {
                flavour { name = 'A' }
                flavour { name = 'B' }
            }
        """)
        write('app/src/main/java/app/App.java', """
            package app;

            import lib.Fixture;

            public class App {

                public String use() {
                    return new Fixture().plain();
                }
            }
        """)
    }

    @Test
    void shouldGiveACustomApiSourceSetAStrippedRuntimeArtifactWithAManifest() {
        writeLib('apiSrcSets = [\'sharedFixtures\']', true)

        final BuildResult result = run('flavourA', ':lib:jitterVerifySharedFixtures', ':lib:showRuntimeArtifact')

        assertThat(result.task(':lib:jitterVerifySharedFixtures').outcome, is(TaskOutcome.SUCCESS))
        assertThat(result.getOutput(), containsString('RUNTIME_ARTIFACT'))
        final String artifact = result.getOutput().readLines().find { it.startsWith('RUNTIME_ARTIFACT') }
                .substring('RUNTIME_ARTIFACT: '.length()).trim()
        assertThat(artifact, containsString('jitter-libs'))
        final File jarFile = new File(artifact)
        assertThat(jarFile.exists(), is(true))
        try (def zip = new ZipFile(jarFile)) {
            def entry = zip.getEntry('META-INF/jitter/sharedFixtures-removed.txt')
            assertThat(entry, is(not(nullValue())))
            assertThat(zip.getInputStream(entry).text,
                    containsString('M lib/Fixture.nasaOnly()Ljava/lang/String;'))
        }
    }

    @Test
    void shouldKeepTheFullModelAsTheCompileArtifactOfTheCustomApiSourceSet() {
        writeLib('apiSrcSets = [\'sharedFixtures\']', true)

        // the consumer targets the api variant and references the gated
        // member - it only compiles against the full model
        write('app/build.gradle', """
            plugins {
                id 'java'
                id 'io.github.mictaege.jitter-plugin'
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation project(path: ':lib', configuration: 'sharedFixturesApiElements')
            }
            jitter {
                flavour { name = 'A' }
                flavour { name = 'B' }
            }
        """)
        write('app/src/main/java/app/App.java', """
            package app;

            import lib.Fixture;

            public class App {

                public String use() {
                    return new Fixture().nasaOnly();
                }
            }
        """)

        final BuildResult result = run('flavourA', ':app:compileJava')

        assertThat(result.task(':app:compileJava').outcome, is(TaskOutcome.SUCCESS))
    }

    @Test
    void shouldLetTheConsumerResolveTheStrippedArtifactOfTheCustomApiSourceSet() {
        writeLib('apiSrcSets = [\'sharedFixtures\']', true)

        // the consumer targets the runtime variant: the stripped jar is
        // what it compiles and runs against, and its removal manifest
        // drives the consumer's cross module verification
        write('app/build.gradle', """
            plugins {
                id 'java'
                id 'io.github.mictaege.jitter-plugin'
            }
            repositories {
                mavenCentral() }
            dependencies {
                implementation project(path: ':lib', configuration: 'sharedFixturesRuntimeElements')
            }
            jitter {
                flavour { name = 'A' }
                flavour { name = 'B' }
            }
        """)
        write('app/src/main/java/app/App.java', """
            package app;

            import lib.Fixture;

            public class App {

                public String use() {
                    return new Fixture().plain();
                }
            }
        """)

        final BuildResult result = run('flavourA', ':app:jitterVerifyMain')

        assertThat(result.task(':app:jitterVerifyMain').outcome, is(TaskOutcome.SUCCESS))
    }

    @Test
    void shouldFailTheConsumersCompileAgainstTheStrippedRuntimeVariant() {
        writeLib('apiSrcSets = [\'sharedFixtures\']', true)

        write('app/build.gradle', """
            plugins {
                id 'java'
                id 'io.github.mictaege.jitter-plugin'
            }
            repositories {
                mavenCentral() }
            dependencies {
                implementation project(path: ':lib', configuration: 'sharedFixturesRuntimeElements')
            }
            jitter {
                flavour { name = 'A' }
                flavour { name = 'B' }
            }
        """)
        write('app/src/main/java/app/App.java', """
            package app;

            import lib.Fixture;

            public class App {

                public String use() {
                    return new Fixture().nasaOnly();
                }
            }
        """)

        // the gated member is already gone from the stripped runtime
        // variant - the consumer's compile against it must fail
        final BuildResult result = runAndFail('flavourA', ':app:compileJava')

        assertThat(result.getOutput(), containsString('nasaOnly'))
    }

    @Test
    void shouldWarnWhenACustomApiSourceSetHasNoOutgoingRuntimeElements() {
        writeLib('apiSrcSets = [\'sharedFixtures\']', false)

        final BuildResult result = run('flavourA', ':lib:tasks')

        assertThat(result.getOutput(), containsString(
                "the api source set 'sharedFixtures' has no 'sharedFixturesRuntimeElements' configuration"))
    }

    @Test
    void shouldRejectASourceSetWithBothRoles() {
        writeLib("packagingSrcSets = ['main']\n            apiSrcSets = ['main']", true)

        final BuildResult result = runAndFail('flavourA', ':lib:tasks')

        assertThat(result.getOutput(), containsString('both packagingSrcSets and apiSrcSets'))
    }

    @Test
    void shouldWarnOnUnknownSourceSetNames() {
        writeLib("apiSrcSets = ['sharedFixturez']", true)

        final BuildResult result = run('flavourA', ':lib:tasks')

        assertThat(result.getOutput(), containsString(
                "apiSrcSets names the unknown source set 'sharedFixturez', ignored"))
    }

    /**
     * Writes the producer with its custom sharedFixtures source set, wired
     * like the standard java-test-fixtures: a hand-rolled variant pair,
     * the full jar as the api artifact and - optionally - an outgoing
     * runtime configuration the plugin replaces with the stripped jar.
     */
    private void writeLib(final String jitterRoles, final boolean withRuntimeElements) {
        write('lib/build.gradle', """
            plugins {
                id 'java'
                id 'io.github.mictaege.jitter-plugin'
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation 'io.github.mictaege:jitter-api:2026.3-rc1'
            }
            sourceSets {
                sharedFixtures {
                    java { srcDir 'src/sharedFixtures/java' }
                }
            }
            tasks.register('sharedFixturesJar', Jar) {
                archiveBaseName = 'lib-sharedFixtures'
                from(sourceSets.sharedFixtures.output)
            }
            ${withRuntimeElements ? """
            tasks.register('showRuntimeArtifact') {
                dependsOn configurations.sharedFixturesRuntimeElements.outgoing.artifacts.buildDependencies
                doLast {
                    println 'RUNTIME_ARTIFACT: ' + configurations.sharedFixturesRuntimeElements
                            .outgoing.artifacts.files.asPath
                }
            }""" : ''}
            configurations {
                sharedFixturesImplementation {
                    canBeResolved = false
                    canBeConsumed = false
                    extendsFrom implementation
                }
                sharedFixturesApiElements {
                    canBeResolved = false
                    canBeConsumed = true
                    extendsFrom sharedFixturesImplementation
                    outgoing {
                        artifact(tasks.named('sharedFixturesJar'))
                    }
                }
                ${withRuntimeElements ? """
                sharedFixturesRuntimeElements {
                    canBeResolved = false
                    canBeConsumed = true
                    extendsFrom sharedFixturesImplementation
                }""" : ''}
            }
            jitter {
                flavour { name = 'A' }
                flavour { name = 'B' }
                ${jitterRoles}
            }
        """)
        write('lib/src/main/java/lib/Plain.java', """
            package lib;

            public class Plain {

                public String info() {
                    return "lib";
                }
            }
        """)
        write('lib/src/sharedFixtures/java/lib/Fixture.java', """
            package lib;

            import com.github.mictaege.jitter.api.OnlyIf;

            public class Fixture {

                public String plain() {
                    return "any flavour";
                }

                @OnlyIf("B")
                public String nasaOnly() {
                    return "B only";
                }
            }
        """)
    }

    private BuildResult run(String... args) {
        GradleRunner.create()
                .withProjectDir(prj.toFile())
                .withPluginClasspath()
                .withArguments(args)
                .build()
    }

    private BuildResult runAndFail(String... args) {
        GradleRunner.create()
                .withProjectDir(prj.toFile())
                .withPluginClasspath()
                .withArguments(args)
                .buildAndFail()
    }

    private void write(String path, String content) {
        def file = prj.resolve(path)
        Files.createDirectories(file.parent)
        file.text = content.stripIndent().trim()
    }

}
