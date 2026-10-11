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
                implementation 'io.github.mictaege:jitter-api:2026.1'
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
    }

    @Test
    void shouldProcessResourcesOfActiveFlavour() {
        run('flavourA', 'processResources')

        assertThat(resources(), containsInAnyOrder('plain.txt', 'msg.properties', 'icons/one.txt', 'both/three.txt'))
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
        run('flavourA', 'clean', 'build')

        def result = run('flavourA', 'build')

        assertThat(result.task(':spoonMain').outcome, is(UP_TO_DATE))
        assertThat(result.task(':processResources').outcome, is(UP_TO_DATE))
        assertThat(result.task(':jar').outcome, is(UP_TO_DATE))
    }

    @Test
    void shouldTakeSpoonedSourcesFromBuildCache() {
        run('--build-cache', 'flavourA', 'compileJava')

        def result = run('--build-cache', 'flavourA', 'clean', 'compileJava')

        assertThat(result.task(':spoonMain').outcome, is(FROM_CACHE))
        assertThat(spooned('fixture/Greeter.java'), containsString('helloA'))
        assertThat(spooned('fixture/Greeter.java'), not(containsString('helloB')))
        assertThat(spooned('fixture/Unflavoured.java'), containsString('Unflavoured'))
    }

    @Test
    void shouldSpoonAgainForOtherFlavourWithoutClean() {
        run('--build-cache', 'flavourA', 'compileJava')

        def other = run('--build-cache', 'flavourB', 'compileJava')

        assertThat(other.task(':spoonMain').outcome, is(SUCCESS))
        assertThat(spooned('fixture/Greeter.java'), containsString('helloB'))
        assertThat(spooned('fixture/Greeter.java'), not(containsString('helloA')))

        def back = run('--build-cache', 'flavourA', 'compileJava')

        assertThat(back.task(':spoonMain').outcome, is(FROM_CACHE))
        assertThat(spooned('fixture/Greeter.java'), containsString('helloA'))
        assertThat(spooned('fixture/Greeter.java'), not(containsString('helloB')))
    }

    @Test
    void shouldTakeSpoonedSourcesFromBuildCacheInOtherLocation() {
        run('--build-cache', 'flavourA', 'compileJava')
        def original = prj
        prj = tmp.resolve('relocated')
        ['settings.gradle', 'build.gradle', 'src'].each { name ->
            new AntBuilder().copy(todir: prj.resolve(name).parent.toFile()) {
                fileset(dir: original.toFile(), includes: "${name}, ${name}/**")
            }
        }

        def result = run('--build-cache', 'flavourA', 'compileJava')

        assertThat(result.task(':spoonMain').outcome, is(FROM_CACHE))
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

    private String spooned(String path) {
        prj.resolve('build/generated-sources/spoon/main').resolve(path).text
    }

    private void write(String path, String content) {
        def file = prj.resolve(path)
        Files.createDirectories(file.parent)
        file.text = content.stripIndent().trim()
    }

}
