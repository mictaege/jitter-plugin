package com.github.mictaege.jitter.plugin

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path

import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.Matchers.is

/**
 * The Maven publication of a module: the flavour model keeps the plain
 * published coordinates, the 150% model moves to a 'full' classifier. The
 * api and the runtime variant of the same component carry two DIFFERENT
 * jars since the stripped model is the default build output - a Maven
 * publication rejects two artifacts with the same extension and classifier,
 * so the full model must not publish under the same coordinates as the
 * stripped one.
 */
class MavenPublishFunctionalTest {

    @TempDir
    Path tmp

    Path prj

    @BeforeEach
    void createProject() {
        prj = tmp.resolve('prj')
        write('settings.gradle', """
            rootProject.name = 'fixture'
        """)
        write('build.gradle', """
            plugins {
                id 'java-library'
                id 'java-test-fixtures'
                id 'maven-publish'
                id 'io.github.mictaege.jitter-plugin'
            }
            group = 'com.example'
            version = '1.0'
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation '${JitterApi.dependency()}'
                testFixturesImplementation '${JitterApi.dependency()}'
            }
            publishing {
                publications {
                    maven(MavenPublication) {
                        from components.java
                    }
                }
            }
            jitter {
                flavour { name = 'A' }
                flavour { name = 'B' }
            }
        """)
        write('src/main/java/App.java', """
            package app;

            import com.github.mictaege.jitter.api.OnlyIf;

            public class App {
            }
        """)
        write('src/main/java/app/FlavourBOnly.java', """
            package app;

            import com.github.mictaege.jitter.api.OnlyIf;

            @OnlyIf("B")
            public class FlavourBOnly {
            }
        """)
        write('src/testFixtures/java/app/Fixture.java', """
            package app;

            import com.github.mictaege.jitter.api.OnlyIf;

            public class Fixture {
            }
        """)
        write('src/testFixtures/java/app/FlavourBOnlyFixture.java', """
            package app;

            import com.github.mictaege.jitter.api.OnlyIf;

            @OnlyIf("B")
            public class FlavourBOnlyFixture {
            }
        """)
    }

    @Test
    void publishesTheFlavourModelUnderPlainCoordinatesAndTheFullModelUnderAFullClassifier() {
        final BuildResult result = run('flavourA', 'publishToMavenLocal')

        assertThat(result.task(':publishMavenPublicationToMavenLocal').outcome,
                is(TaskOutcome.SUCCESS))
        // the flavour model keeps the plain coordinates, the 150% model
        // carries the 'full' classifier - on the main source set as well as
        // on the test fixtures
        assertPublished('fixture-1.0.jar')
        assertPublished('fixture-1.0-full.jar')
        assertPublished('fixture-1.0-test-fixtures.jar')
        assertPublished('fixture-1.0-test-fixtures-full.jar')
        // the lanes: the stripped jars are the deliverables
        assertThat(prj.resolve('build/libs/fixture-1.0.jar').toFile().isFile(), is(true))
        assertThat(prj.resolve('build/libs/fixture-1.0-test-fixtures.jar').toFile().isFile(),
                is(true))
        assertThat(prj.resolve('build/full-libs/fixture-1.0-full.jar').toFile().isFile(), is(true))
        assertThat(prj.resolve('build/full-libs/fixture-1.0-test-fixtures-full.jar').toFile()
                .isFile(), is(true))
    }

    private static void assertPublished(final String name) {
        final Path repo = Path.of(System.getProperty('user.home'), '.m2', 'repository',
                'com', 'example', 'fixture', '1.0', name)
        assertThat(name, repo.toFile().isFile(), is(true))
    }

    private BuildResult run(String... args) {
        GradleRunner.create()
                .withProjectDir(prj.toFile())
                .withPluginClasspath()
                .withArguments(args)
                .build()
    }

    private void write(String path, String content) {
        def file = prj.resolve(path)
        Files.createDirectories(file.parent)
        file.text = content.stripIndent().trim()
    }

}
