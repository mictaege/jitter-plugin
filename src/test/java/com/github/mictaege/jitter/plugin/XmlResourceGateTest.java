package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class XmlResourceGateTest {

    @TempDir
    Path workDir;

    @Test
    void detectsFullyQualifiedGate() throws Exception {
        assertThat(XmlResourceGate.flavours(file("schema.xsd",
                "<xsd:schema xmlns:xsd='http://www.w3.org/2001/XMLSchema'>\n"
                        + "  <xsd:annotation>\n"
                        + "    <xsd:appinfo>\n"
                        + "      @com.github.mictaege.jitter.api.OnlyIf(\"Dba\")\n"
                        + "    </xsd:appinfo>\n"
                        + "  </xsd:annotation>\n"
                        + "</xsd:schema>\n")),
                is(List.of("Dba")));
    }

    @Test
    void detectsSimpleGateWithSeveralFlavours() throws Exception {
        assertThat(XmlResourceGate.flavours(file("schema.xsd",
                "<xsd:schema><xsd:annotation><xsd:appinfo>"
                        + "@OnlyIf({\"Mpk\", \"Bmx\"})"
                        + "</xsd:appinfo></xsd:annotation></xsd:schema>")),
                is(List.of("Mpk", "Bmx")));
    }

    @Test
    void ignoresAnnoxGenerationGate() throws Exception {
        // generation directives belong to the class model: a schema whose
        // generated classes are flavour-gated can still be a runtime
        // dependency of surviving schemas (xsd:import of base types)
        assertThat(XmlResourceGate.flavours(file("schema.xsd",
                "<xsd:schema xmlns:annox='urn:jaxb.jvnet.org:annox'><xsd:annotation>"
                        + "<xsd:appinfo>"
                        + "<annox:annotatePackage>@com.github.mictaege.jitter.api.OnlyIf({\"Mpk\", \"Dba\"})</annox:annotatePackage>"
                        + "</xsd:appinfo></xsd:annotation></xsd:schema>")),
                is(nullValue()));
    }

    @Test
    void detectsHandWrittenGateNextToAnnoxGenerationGate() throws Exception {
        assertThat(XmlResourceGate.flavours(file("schema.xsd",
                "<xsd:schema xmlns:annox='urn:jaxb.jvnet.org:annox'><xsd:annotation>"
                        + "<xsd:appinfo>"
                        + "@OnlyIf({\"Mpk\", \"Dba\"})"
                        + "<annox:annotatePackage>@com.github.mictaege.jitter.api.OnlyIf({\"Mpk\", \"Dba\"})</annox:annotatePackage>"
                        + "</xsd:appinfo></xsd:annotation></xsd:schema>")),
                is(List.of("Mpk", "Dba")));
    }

    @Test
    void mergesMultipleAnnotations() throws Exception {
        assertThat(XmlResourceGate.flavours(file("a.xjb",
                "<root><appinfo>@OnlyIf(\"Mpk\")</appinfo><appinfo>@OnlyIf(\"Bmx\")</appinfo></root>")),
                is(List.of("Mpk", "Bmx")));
    }

    @Test
    void ignoresXmlWithoutJitterAnnotation() throws Exception {
        assertThat(XmlResourceGate.flavours(file("schema.xsd",
                "<xsd:schema><xsd:annotation><xsd:appinfo>some tool hint</xsd:appinfo></xsd:annotation></xsd:schema>")),
                is(nullValue()));
    }

    @Test
    void ignoresAnnotationsOutsideAppinfo() throws Exception {
        assertThat(XmlResourceGate.flavours(file("doc.xml",
                "<doc><text>use @OnlyIf(\"Dba\") to gate classes</text></doc>")),
                is(nullValue()));
    }

    @Test
    void ignoresNonXmlFiles() throws Exception {
        assertThat(XmlResourceGate.flavours(file("messages.properties",
                "gate=@OnlyIf(\"Dba\")")),
                is(nullValue()));
    }

    private File file(final String name, final String content) throws IOException {
        final Path path = workDir.resolve(name);
        Files.write(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return path.toFile();
    }

}
