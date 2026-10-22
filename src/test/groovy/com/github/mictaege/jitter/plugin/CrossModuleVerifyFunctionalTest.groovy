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
 * A producer and a consumer project: the consumer's full model compiles
 * against the producer's full model, and the flavour-stripped runtime
 * artifact of the producer carries the removal manifest. The consumer's
 * verify must catch references to flavour specific producer code that the
 * active flavour removed - the cross module consistency check the source
 * transformation got for free.
 */
class CrossModuleVerifyFunctionalTest {

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
        write('lib/build.gradle', """
            plugins {
                id 'java'
                id 'io.github.mictaege.jitter-plugin'
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation 'io.github.mictaege:jitter-api:2026.3-rc8'
            }
            jitter {
                flavour { name = 'A' }
                flavour { name = 'B' }
            }
        """)
        write('app/build.gradle', """
            plugins {
                id 'java'
                id 'io.github.mictaege.jitter-plugin'
                id 'java-test-fixtures'
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation project(':lib')
                testFixturesImplementation project(':lib')
            }
            jitter {
                flavour { name = 'A' }
                flavour { name = 'B' }
            }
        """)
        write('lib/src/main/java/lib/Greeter.java', """
            package lib;

            import com.github.mictaege.jitter.api.OnlyIf;

            public class Greeter {

                public String greet() {
                    return "any flavour";
                }

                @OnlyIf("B")
                public String greetB() {
                    return "B only";
                }
            }
        """)
        write('app/src/main/java/app/App.java', """
            package app;

            import lib.Greeter;

            public class App {

                public String welcome() {
                    return new Greeter().greet();
                }
            }
        """)
        write('app/src/testFixtures/java/app/AppFixture.java', """
            package app;

            import lib.Greeter;

            public class AppFixture {

                public String welcome() {
                    return new Greeter().greetB();
                }
            }
        """)
    }

    @Test
    void shouldKeepTheRemovalManifestOutOfTheStrippedJar() {
        run('flavourA', ':lib:jar', ':lib:jitterJar')

        // the stripped jar is the deliverable of build/libs: it must not
        // carry the removal manifest, which lists the flavour specific
        // classes of the full model
        def jarFile = prj.resolve('lib/build/libs/lib.jar').toFile()
        assertThat(jarFile.exists(), is(true))
        try (def zip = new ZipFile(jarFile)) {
            assertThat(zip.getEntry('META-INF/jitter/main-removed.txt'), is(nullValue()))
        }
        // the 150% model jar moved to full-libs with a 'full' classifier:
        // the flavour model keeps the plain published coordinates, the two
        // models must not collide in a Maven publication
        assertThat(prj.resolve('lib/build/full-libs/lib-full.jar').toFile().isFile(), is(true))
        // the stripped tree keeps the manifest: the verification of the
        // build reads it there
        def manifest = prj.resolve(
                'lib/build/jitter-stripped/main/META-INF/jitter/main-removed.txt').toFile()
        assertThat(manifest.isFile(), is(true))
        assertThat(manifest.text, containsString('M lib/Greeter.greetB()Ljava/lang/String;'))
        assertThat(manifest.text, not(containsString('greet()')))
    }

    @Test
    void shouldFailTheConsumersMainVerifyOnRemovedProducerCode() {
        write('app/src/main/java/app/App.java', """
            package app;

            import lib.Greeter;

            public class App {

                public String welcome() {
                    return new Greeter().greetB();
                }
            }
        """)
        def result = runAndFail('flavourA', ':app:jitterVerifyMain')

        assertThat(result.getOutput(), containsString('app.App calls removed method lib.Greeter#greetB'))
    }

    @Test
    void shouldPassTheConsumersMainVerifyWhenTheProducerCodeSurvives() {
        write('app/src/main/java/app/App.java', """
            package app;

            import lib.Greeter;

            public class App {

                public String welcome() {
                    return new Greeter().greetB();
                }
            }
        """)
        def result = run('flavourB', ':app:jitterVerifyMain')

        assertThat(result.task(':app:jitterVerifyMain').outcome, is(TaskOutcome.SUCCESS))
    }

    @Test
    void shouldFailTheScopedTestFixturesVerifyOnRemovedProducerCode() {
        def result = runAndFail('flavourA', ':app:jitterTestFixturesJar')

        assertThat(result.getOutput(), containsString('app.AppFixture calls removed method lib.Greeter#greetB'))
    }

    @Test
    void shouldVerifyOwnRemovalsEvenWhenTheStripComesFromTheBuildCache() {
        write('app/src/main/java/app/App.java', """
            package app;

            import lib.Greeter;

            public class App {

                public String welcome() {
                    return new Greeter().greetB();
                }
            }
        """)

        // the first build populates the build cache with the strip result;
        // the dangling reference is caught while the strip still executes
        runAndFail('--build-cache', 'flavourA', ':app:jitterVerifyMain')

        // the second build loads the strip task FROM-CACHE: its in-memory
        // result stays empty, the verification must read the removal
        // manifest from the cached output instead
        def result = runAndFail('--build-cache', 'flavourA', 'clean', ':app:jitterVerifyMain')

        assertThat(result.getOutput(), containsString('app.App calls removed method lib.Greeter#greetB'))
    }

    @Test
    void shouldPassTheScopedTestFixturesVerifyWhenTheProducerCodeSurvives() {
        def result = run('flavourB', ':app:jitterTestFixturesJar')

        assertThat(result.task(':app:jitterVerifyTestFixtures').outcome, is(TaskOutcome.SUCCESS))
    }

    @Test
    void shouldTolerateSwitchMapReferencesToRemovedProducerEnumConstants() {
        write('lib/src/main/java/lib/Port.java', """
            package lib;

            import com.github.mictaege.jitter.api.OnlyIf;

            public enum Port {

                BASE,

                @OnlyIf("B")
                GATED_B
            }
        """)
        write('app/src/main/java/app/App.java', """
            package app;

            import lib.Port;

            public class App {

                public String portName(final Port port) {
                    switch (port) {
                        case BASE:
                            return "base";
                        case GATED_B:
                            return "gated";
                        default:
                            return "other";
                    }
                }
            }
        """)

        def result = run('flavourA', ':app:jitterVerifyMain')

        assertThat(result.task(':app:jitterVerifyMain').outcome, is(TaskOutcome.SUCCESS))
    }

    @Test
    void shouldFailOnDirectFieldAccessToRemovedProducerEnumConstant() {
        write('lib/src/main/java/lib/Port.java', """
            package lib;

            import com.github.mictaege.jitter.api.OnlyIf;

            public enum Port {

                BASE,

                @OnlyIf("B")
                GATED_B
            }
        """)
        write('app/src/main/java/app/App.java', """
            package app;

            import lib.Port;

            public class App {

                public String gatedName() {
                    return Port.GATED_B.name();
                }
            }
        """)

        def result = runAndFail('flavourA', ':app:jitterVerifyMain')

        assertThat(result.getOutput(), containsString('app.App accesses removed field lib.Port#GATED_B'))
    }

    @Test
    void shouldWarnAboutResourcesOfRemovedClasses() {
        write('lib/src/main/java/lib/Gone.java', """
            package lib;

            import com.github.mictaege.jitter.api.OnlyIf;

            @OnlyIf("B")
            public class Gone {

                public String farewell() {
                    return "bye";
                }
            }
        """)
        write('lib/src/main/resources/lib/Gone.properties', 'gone=true')

        def result = run('flavourA', ':lib:jitterVerifyMain')

        assertThat(result.getOutput(), containsString(
                'jitter: resource lib/Gone.properties ships without its owner class lib.Gone'))
    }

    @Test
    void shouldNotWarnAboutResourcesWhenTheOwnerClassSurvives() {
        write('lib/src/main/java/lib/Gone.java', """
            package lib;

            import com.github.mictaege.jitter.api.OnlyIf;

            @OnlyIf("B")
            public class Gone {

                public String farewell() {
                    return "bye";
                }
            }
        """)
        write('lib/src/main/resources/lib/Gone.properties', 'gone=true')

        def result = run('flavourB', ':lib:jitterVerifyMain')

        assertThat(result.getOutput(), not(containsString('ships without its owner class')))
    }

    @Test
    void shouldFailTheTestRunOnDanglingReferencesOfTheTestSourceSet() {
        // the class fixture's AppFixture dangles as well (its own scenario);
        // a clean fixture lets the test source set be the only violator
        write('app/src/testFixtures/java/app/AppFixture.java', """
            package app;

            import lib.Greeter;

            public class AppFixture {

                public String welcome() {
                    return new Greeter().greet();
                }
            }
        """)
        write('app/src/test/java/app/AppTest.java', """
            package app;

            import lib.Greeter;
            import org.junit.jupiter.api.Test;

            public class AppTest {

                @Test
                void usesRemovedProducerCode() {
                    new Greeter().greetB();
                }
            }
        """)
        write('app/build.gradle', """
            plugins {
                id 'java'
                id 'io.github.mictaege.jitter-plugin'
                id 'java-test-fixtures'
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation project(':lib')
                testFixturesImplementation project(':lib')
                testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
                testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.10.2'
            }
            jitter {
                flavour { name = 'A' }
                flavour { name = 'B' }
            }
            tasks.withType(Test).configureEach {
                useJUnitPlatform()
            }
        """)

        // the test source set references producer code that flavour A
        // removes: the test run must fail on the verify before the tests
        // execute against a broken model
        def result = runAndFail('flavourA', ':app:test')

        assertThat(result.getOutput(), containsString('app.AppTest calls removed method lib.Greeter#greetB'))
    }

    @Test
    void shouldVerifyTheWholeBuildIncludingInlinedConstantsInOneTask() {
        // the class fixture's AppFixture dangles in flavour A (its own
        // scenario) - a clean fixture lets the whole build be consistent
        write('app/src/testFixtures/java/app/AppFixture.java', """
            package app;

            import lib.Greeter;

            public class AppFixture {

                public String welcome() {
                    return new Greeter().greet();
                }
            }
        """)

        // no system property set: the task invocation IS the opt-in for
        // the source level inlined constants check
        def result = run('flavourA', 'jitterVerifyAll')

        // the root task ran the reference verification of every module
        assertThat(result.task(':jitterVerifyAll').outcome, is(TaskOutcome.SUCCESS))
        assertThat(result.task(':lib:jitterVerifyMain').outcome, is(TaskOutcome.SUCCESS))
        assertThat(result.task(':app:jitterVerifyMain').outcome, is(TaskOutcome.SUCCESS))
        // and the inlined constants checks of every source set, without
        // the opt-in flag
        assertThat(result.task(':lib:jitterVerifyInlinedConstantsMain').outcome,
                is(TaskOutcome.SUCCESS))
        assertThat(result.task(':app:jitterVerifyInlinedConstantsMain').outcome,
                is(TaskOutcome.SUCCESS))
        assertThat(result.task(':app:jitterVerifyInlinedConstantsTestFixtures').outcome,
                is(TaskOutcome.SUCCESS))
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
