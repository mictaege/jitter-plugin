package com.github.mictaege.jitter.plugin

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.Matchers.is
import static org.hamcrest.Matchers.notNullValue
import static org.hamcrest.Matchers.nullValue

/**
 * The war delivery of a web project: the classes are the stripped flavour
 * model, but the removal manifest stays out of the delivery - it lists
 * the flavour specific classes of the full model and is build data, not
 * application data. The manifest remains in the stripped tree for the
 * verification of the build.
 */
class WarDeliveryFunctionalTest {

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
                id 'war'
                id 'io.github.mictaege.jitter-plugin'
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation 'io.github.mictaege:jitter-api:2026.3-rc6'
            }
            jitter {
                flavour { name = 'A' }
                flavour { name = 'B' }
            }
        """)
        write('src/main/java/web/Plain.java', """
            package web;

            public class Plain {

                public String greet() {
                    return "plain";
                }
            }
        """)
        write('src/main/java/web/FlavourBOnly.java', """
            package web;

            import com.github.mictaege.jitter.api.OnlyIf;

            @OnlyIf("B")
            public class FlavourBOnly {
            }
        """)
    }

    @Test
    void shouldDeliverTheStrippedClassesWithoutTheRemovalManifest() {
        final BuildResult result = run('flavourA', 'war')

        assertThat(result.task(':war').outcome, is(org.gradle.testkit.runner.TaskOutcome.SUCCESS))
        final File warFile = prj.resolve('build/libs/fixture.war').toFile()
        assertThat(warFile.isFile(), is(true))
        try (ZipFile zip = new ZipFile(warFile)) {
            // the classes are the stripped model, with their package structure
            assertThat(zip.getEntry('WEB-INF/classes/web/Plain.class'), is(notNullValue()))
            assertThat(zip.getEntry('WEB-INF/classes/web/FlavourBOnly.class'), is(nullValue()))
            // no removal manifest in the delivery - neither in the classes
            // nor anywhere else
            final Enumeration<? extends ZipEntry> entries = zip.entries()
            while (entries.hasMoreElements()) {
                assertThat(entries.nextElement().name.contains('META-INF/jitter'), is(false))
            }
        }
        // the manifest stays in the stripped tree for the build's verification
        assertThat(prj.resolve('build/jitter-stripped/main/META-INF/jitter/main-removed.txt')
                .toFile().isFile(), is(true))
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
