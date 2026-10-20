package com.github.mictaege.jitter.plugin

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path

import static org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE
import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.Matchers.containsInAnyOrder
import static org.hamcrest.Matchers.containsString
import static org.hamcrest.Matchers.is
import static org.hamcrest.Matchers.not
import static org.hamcrest.Matchers.nullValue

/** Builds a small project with the flavours A and B to verify up-to-date checks and build caching. */
class BuildCacheFunctionalTest {

    @TempDir
    Path tmp

    Path prj

    @BeforeEach
    void createProject() {
        prj = tmp.resolve('prj')
        write('settings.gradle', """
            rootProject.name = 'fixture'
            buildCache {
                local {
                    directory = file('${tmp.resolve('build-cache').toUri()}')
                }
            }
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
                implementation 'io.github.mictaege:jitter-api:2026.3-rc7'
            }
            jitter {
                flavour { name = 'A' }
                flavour { name = 'B' }
            }
        """)
        write('src/main/java/fixture/Greeter.java', """
            package fixture;

            import com.github.mictaege.jitter.api.OnlyIf;

            public class Greeter {
                @OnlyIf("A")
                public String helloA() { return "A"; }
                @OnlyIf("B")
                public String helloB() { return "B"; }
            }
        """)
        write('src/main/java/fixture/Unflavoured.java', """
            package fixture;

            public class Unflavoured {
            }
        """)
        write('src/main/resources/plain.txt', 'plain')
        write('src/main/resources/msg.properties', 'unspecific')
        write('src/main/resources/msg_A.properties', 'specific for A')
        write('src/main/resources/only_B.properties', 'specific for B')
        write('src/main/resources/icons_A/one.txt', 'icon of A')
        write('src/main/resources/sub/others_!A/two.txt', 'not for A')
        write('src/main/resources/both_A/three.txt', 'specific folder')
        write('src/main/resources/both_A/three_A.txt', 'specific folder and file')
        // flavour gate in the resource's own content instead of a name suffix
        write('src/main/resources/schema.xsd', """
            <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                <xsd:annotation><xsd:appinfo>
                    @com.github.mictaege.jitter.api.OnlyIf("A")
                </xsd:appinfo></xsd:annotation>
            </xsd:schema>
        """)
    }

    @Test
    void shouldProcessResourcesOfActiveFlavour() {
        run('flavourA', 'processResources')

        assertThat(resources(), containsInAnyOrder('plain.txt', 'msg.properties', 'icons/one.txt',
                'both/three.txt', 'schema.xsd'))
        assertThat(resource('msg.properties'), is('specific for A'))
        assertThat(resource('both/three.txt'), is('specific folder and file'))
    }

    @Test
    void shouldProcessResourcesOfOtherFlavourWithoutClean() {
        run('flavourA', 'processResources')

        def result = run('flavourB', 'processResources')

        assertThat(result.task(':processResources').outcome, is(SUCCESS))
        assertThat(resources(), containsInAnyOrder('plain.txt', 'msg.properties', 'only.properties', 'sub/others/two.txt'))
        assertThat(resource('msg.properties'), is('unspecific'))
    }

    @Test
    void shouldSelectFlavourBySystemProperty() {
        run('-Djitter.active.flavour=B', 'processResources')

        assertThat(resources(), containsInAnyOrder('plain.txt', 'msg.properties', 'only.properties', 'sub/others/two.txt'))
    }

    @Test
    void shouldBeUpToDateAfterCleanBuild() {
        run('flavourA', 'clean', 'build', 'jitterStripMain')

        def result = run('flavourA', 'build', 'jitterStripMain')

        assertThat(result.task(':jitterStripMain').outcome, is(UP_TO_DATE))
        assertThat(result.task(':processResources').outcome, is(UP_TO_DATE))
        // the runtime artifact replaces the plain jar in the assemble wiring
        // since Gradle 9, so a build does not build the plain jar anymore
        assertThat(result.task(':jitterJar').outcome, is(UP_TO_DATE))
        assertThat(result.task(':jar'), is(nullValue()))
    }

    @Test
    void shouldTakeStrippedModelFromBuildCache() {
        run('--build-cache', 'flavourA', 'jitterStripMain')

        def result = run('--build-cache', 'flavourA', 'clean', 'jitterStripMain')

        assertThat(result.task(':jitterStripMain').outcome, is(FROM_CACHE))
        assertThat(stripped('fixture/Greeter.class'), containsString('helloA'))
        assertThat(stripped('fixture/Greeter.class'), not(containsString('helloB')))
        assertThat(stripped('fixture/Unflavoured.class'), containsString('Unflavoured'))
    }

    @Test
    void shouldStripAgainForOtherFlavourWithoutClean() {
        run('--build-cache', 'flavourA', 'jitterStripMain')

        def other = run('--build-cache', 'flavourB', 'jitterStripMain')

        assertThat(other.task(':jitterStripMain').outcome, is(SUCCESS))
        assertThat(stripped('fixture/Greeter.class'), containsString('helloB'))
        assertThat(stripped('fixture/Greeter.class'), not(containsString('helloA')))

        def back = run('--build-cache', 'flavourA', 'jitterStripMain')

        assertThat(back.task(':jitterStripMain').outcome, is(FROM_CACHE))
        assertThat(stripped('fixture/Greeter.class'), containsString('helloA'))
        assertThat(stripped('fixture/Greeter.class'), not(containsString('helloB')))
    }

    @Test
    void shouldTakeStrippedModelFromBuildCacheInOtherLocation() {
        run('--build-cache', 'flavourA', 'jitterStripMain')
        def original = prj
        prj = tmp.resolve('relocated')
        ['settings.gradle', 'build.gradle', 'src'].each { name ->
            copyTree(original.resolve(name), prj.resolve(name))
        }

        def result = run('--build-cache', 'flavourA', 'jitterStripMain')

        assertThat(result.task(':jitterStripMain').outcome, is(FROM_CACHE))
    }

    private BuildResult run(String... args) {
        GradleRunner.create()
                .withProjectDir(prj.toFile())
                .withPluginClasspath()
                .withArguments(args)
                .build()
    }

    private List<String> resources() {
        def dir = prj.resolve('build/resources/main')
        Files.walk(dir).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }
                    .collect { dir.relativize(it).join('/') }
        }
    }

    private String resource(String path) {
        prj.resolve('build/resources/main').resolve(path).text
    }

    private String stripped(String path) {
        prj.resolve('build/jitter-stripped/main').resolve(path).getText('ISO-8859-1')
    }

    private void write(String path, String content) {
        def file = prj.resolve(path)
        Files.createDirectories(file.parent)
        file.text = content.stripIndent().trim()
    }

    private static void copyTree(Path from, Path to) {
        if (Files.isDirectory(from)) {
            Files.walk(from).withCloseable { paths ->
                paths.forEach { source ->
                    def target = to.resolve(from.relativize(source).toString())
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target)
                    } else {
                        Files.createDirectories(target.parent)
                        Files.copy(source, target)
                    }
                }
            }
        } else {
            Files.createDirectories(to.parent)
            Files.copy(from, to)
        }
    }

}
