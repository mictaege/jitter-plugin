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
import static org.hamcrest.Matchers.not

/**
 * The flavour gates of XML resources: a unified schema with
 * {@code jitter:onlyIf} and {@code jitter:set} gates in its appinfo
 * sections is resolved while the resources are copied, so the active
 * flavour's variant of the document becomes the processed resource - for
 * the runtime delivery as well as for the dejittered source variant.
 */
class XmlFlavourGateFunctionalTest {

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
            jitter {
                flavour { name = 'A' }
                flavour { name = 'B' }
            }
        """)
        write('src/main/java/App.java', """
            package app;

            public class App {
            }
        """)
        write('src/main/resources/msg.xsd', """
            <?xml version="1.0" encoding="utf-8"?>
            <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                        xmlns:jitter="https://mictaege.github.io/jitter">
                <xsd:element name="plain" type="xsd:string"/>
                <xsd:element name="flavourBOnly" type="xsd:string">
                    <xsd:annotation>
                        <xsd:appinfo>
                            <jitter:onlyIf flavours="B"/>
                        </xsd:appinfo>
                    </xsd:annotation>
                </xsd:element>
                <xsd:element name="reconfigured" type="xsd:string" minOccurs="0">
                    <xsd:annotation>
                        <xsd:appinfo>
                            <jitter:set ifActive="B" minOccurs="1"/>
                        </xsd:appinfo>
                    </xsd:annotation>
                </xsd:element>
            </xsd:schema>
        """)
    }

    @Test
    void resolvesTheGatesForTheActiveFlavour() {
        final BuildResult result = run('flavourB', 'processResources')

        assertThat(result.task(':processResources').outcome, is(TaskOutcome.SUCCESS))
        final String xsd = processed()
        assertThat(xsd, containsString('name="plain"'))
        assertThat(xsd, containsString('name="flavourBOnly"'))
        assertThat(xsd, containsString('name="reconfigured"'))
        assertThat(xsd, containsString('minOccurs="1"'))
        // the resolved document carries no gate residue
        assertThat(xsd, not(containsString('jitter:')))
        assertThat(xsd, not(containsString('appinfo')))
        // the unified source stays untouched
        assertThat(source(), containsString('jitter:onlyIf'))
    }

    @Test
    void resolvesTheGatesForTheOtherFlavour() {
        run('flavourA', 'processResources')

        final String xsd = processed()
        assertThat(xsd, containsString('name="plain"'))
        assertThat(xsd, not(containsString('name="flavourBOnly"')))
        assertThat(xsd, containsString('minOccurs="0"'))
        assertThat(xsd, not(containsString('jitter:')))
    }

    @Test
    void resolvesTheGatesForTheFullModel() {
        run('processResources')

        final String xsd = processed()
        assertThat(xsd, containsString('name="plain"'))
        assertThat(xsd, containsString('name="flavourBOnly"'))
        assertThat(xsd, containsString('minOccurs="0"'))
        assertThat(xsd, not(containsString('jitter:')))
    }

    @Test
    void resolvesTheGatesInTheDejitteredVariant() {
        final BuildResult result = run('flavourA', 'dejitterMain')

        assertThat(result.task(':dejitterMain').outcome, is(TaskOutcome.SUCCESS))
        final String xsd = prj.resolve('build/jitter-dejittered/main/resources/msg.xsd').text
        assertThat(xsd, not(containsString('name="flavourBOnly"')))
        assertThat(xsd, not(containsString('jitter:')))
    }

    private String processed() {
        prj.resolve('build/resources/main/msg.xsd').text
    }

    private String source() {
        prj.resolve('src/main/resources/msg.xsd').text
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
