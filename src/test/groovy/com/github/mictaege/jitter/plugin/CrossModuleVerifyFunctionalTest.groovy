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
                implementation 'io.github.mictaege:jitter-api:2026.3-rc2'
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
    void shouldWriteTheRemovalManifestIntoTheStrippedJar() {
        run('flavourA', ':lib:jitterJar')

        def jarFile = prj.resolve('lib/build/jitter-libs/lib.jar').toFile()
        assertThat(jarFile.exists(), is(true))
        try (def zip = new ZipFile(jarFile)) {
            def entry = zip.getEntry('META-INF/jitter/main-removed.txt')
            assertThat(entry, is(not(null)))
            def manifest = zip.getInputStream(entry).text
            assertThat(manifest, containsString('M lib/Greeter.greetB()Ljava/lang/String;'))
            assertThat(manifest, not(containsString('greet()')))
        }
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
