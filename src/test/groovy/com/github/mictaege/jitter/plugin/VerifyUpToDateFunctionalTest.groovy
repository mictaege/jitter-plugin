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
import static org.hamcrest.Matchers.containsString
import static org.hamcrest.Matchers.is

/**
 * The up-to-date check of the verification: everything the verification
 * reads is a task input and the stale resource report is the output, so a
 * second build without changes skips it - but a flavour switch or a change
 * of the stripped model (own or of a dependent project) runs it again.
 */
class VerifyUpToDateFunctionalTest {

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
                id 'java'
                id 'io.github.mictaege.jitter-plugin'
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation '${JitterApi.dependency()}'
            }
            jitter {
                flavour { name = 'A' }
                flavour { name = 'B' }
            }
        """)
        write('src/main/java/app/Plain.java', """
            package app;

            public class Plain {
            }
        """)
    }

    @Test
    void skipsTheVerificationWhenNothingChanged() {
        run('flavourA', ':jitterVerifyMain')

        final BuildResult second = run('flavourA', ':jitterVerifyMain')

        assertThat(second.task(':jitterVerifyMain').outcome, is(TaskOutcome.UP_TO_DATE))
    }

    @Test
    void rerunsTheVerificationWhenTheFlavourSwitches() {
        run('flavourA', ':jitterVerifyMain')

        final BuildResult switched = run('flavourB', ':jitterVerifyMain')

        assertThat(switched.task(':jitterVerifyMain').outcome, is(TaskOutcome.SUCCESS))
    }

    @Test
    void rerunsTheVerificationWhenTheStrippedModelChanges() {
        run('flavourA', ':jitterVerifyMain')

        write('src/main/java/app/Extra.java', """
            package app;

            public class Extra {
            }
        """)
        final BuildResult changed = run('flavourA', ':jitterVerifyMain')

        assertThat(changed.task(':jitterVerifyMain').outcome, is(TaskOutcome.SUCCESS))
    }

    @Test
    void writesTheStaleResourceReport() {
        write('src/main/java/app/Gated.java', """
            package app;

            import com.github.mictaege.jitter.api.OnlyIf;

            @OnlyIf("A")
            public class Gated {
            }
        """)
        write('src/main/resources/app/Gated.properties', """
            some=text
        """)
        run('flavourB', ':jitterVerifyMain')

        final Path report = prj.resolve('build/reports/jitter/jitterVerifyMain-stale-resources.txt')
        assertThat(report.toFile().isFile(), is(true))
        assertThat(report.text, containsString('app/Gated.properties'))
    }

    @Test
    void verifiesTheAsIsClassesOfExcludedSourceSetsWithDeclaredDependencies() {
        // main is excluded from the stripping, so the aggregate verify scans
        // its classes as they are - the raw classes dirs are task inputs and
        // the outputs of the compile task in the same build graph: Gradle's
        // implicit dependency validation requires the declared dependency,
        // the build failed with "uses this output of task ':compileJava'
        // without declaring an explicit or implicit dependency" before
        write('build.gradle', """
            plugins {
                id 'java'
                id 'io.github.mictaege.jitter-plugin'
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation '${JitterApi.dependency()}'
            }
            jitter {
                flavour { name = 'A' }
                flavour { name = 'B' }
                excludeSrcSets = ['main']
            }
        """)

        final BuildResult result = run('flavourA', 'clean', 'jitterVerify', 'compileJava')

        assertThat(result.task(':jitterVerify').outcome, is(TaskOutcome.SUCCESS))
        assertThat(result.task(':compileJava').outcome, is(TaskOutcome.SUCCESS))
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
