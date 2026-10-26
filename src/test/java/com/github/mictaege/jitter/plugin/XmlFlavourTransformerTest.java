package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

public class XmlFlavourTransformerTest {

    private static final String SCHEMA =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n"
            + "            xmlns:jitter=\"https://mictaege.github.io/jitter\"\n"
            + "            targetNamespace=\"urn:test\">\n"
            + "  <xsd:element name=\"kept\" type=\"xsd:string\"/>\n"
            + "  <xsd:element name=\"flavourSpecific\" type=\"xsd:string\">\n"
            + "    <xsd:annotation>\n"
            + "      <xsd:appinfo>\n"
            + "        <jitter:onlyIf flavours=\"Bmx\"/>\n"
            + "      </xsd:appinfo>\n"
            + "    </xsd:annotation>\n"
            + "  </xsd:element>\n"
            + "</xsd:schema>\n";

    @Test
    void detectsTheGateNamespace() {
        assertThat(XmlFlavourTransformer.gated(SCHEMA), is(true));
        assertThat(XmlFlavourTransformer.gated("<xsd:schema xmlns:xsd=\"http://x\"/>"), is(false));
        assertThat(XmlFlavourTransformer.gated(null), is(false));
    }

    @Test
    void keepsTheGatedComponentInTheActiveFlavour() {
        final String out = XmlFlavourTransformer.transform(SCHEMA, "Bmx");
        assertThat(out, containsString("flavourSpecific"));
        assertThat(out, containsString("kept"));
        assertThat(out, not(containsString("jitter:")));
        assertThat(out, not(containsString("appinfo")));
    }

    @Test
    void removesTheGatedComponentInOtherFlavours() {
        final String out = XmlFlavourTransformer.transform(SCHEMA, "Mpk");
        assertThat(out, containsString("kept"));
        assertThat(out, not(containsString("flavourSpecific")));
        assertThat(out, not(containsString("jitter:")));
    }

    @Test
    void keepsEveryComponentWithoutAnActiveFlavour() {
        final String out = XmlFlavourTransformer.transform(SCHEMA, "");
        assertThat(out, containsString("kept"));
        assertThat(out, containsString("flavourSpecific"));
        assertThat(out, not(containsString("jitter:")));
    }

    @Test
    void honoursFlavourListsAndCaseInsensitiveFlavourNames() {
        final String schema = SCHEMA.replace("flavours=\"Bmx\"", "flavours=\"mpk, dba\"");
        assertThat(XmlFlavourTransformer.transform(schema, "Dba"), containsString("flavourSpecific"));
        assertThat(XmlFlavourTransformer.transform(schema, "Bmx"), not(containsString("flavourSpecific")));
    }

    @Test
    void removesNestedComponentsWithTheirChildren() {
        final String out = XmlFlavourTransformer.transform(SCHEMA, "Mpk");
        assertThat(out, containsString("<xsd:element name=\"kept\""));
        // the resolved document must stay well formed: the removed component
        // takes its whole subtree with it
        assertThat(out, not(containsString("annotation")));
    }

    @Test
    void setsAttributesForTheActiveFlavour() {
        final String schema =
                "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n"
                + "            xmlns:jitter=\"https://mictaege.github.io/jitter\">\n"
                + "  <xsd:element name=\"repacked\" type=\"xsd:boolean\" minOccurs=\"0\">\n"
                + "    <xsd:annotation>\n"
                + "      <xsd:appinfo>\n"
                + "        <jitter:set ifActive=\"Bmx\" minOccurs=\"1\"/>\n"
                + "      </xsd:appinfo>\n"
                + "    </xsd:annotation>\n"
                + "  </xsd:element>\n"
                + "</xsd:schema>";
        // the serialized attribute order is the DOM's, not the source's
        final String bmx = XmlFlavourTransformer.transform(schema, "Bmx");
        assertThat(bmx, containsString("name=\"repacked\""));
        assertThat(bmx, containsString("minOccurs=\"1\""));
        assertThat(XmlFlavourTransformer.transform(schema, "Mpk"),
                containsString("minOccurs=\"0\""));
        assertThat(XmlFlavourTransformer.transform(schema, ""),
                containsString("minOccurs=\"0\""));
    }

    @Test
    void appliesEveryMatchingSetGateLikeAlterConstValue() {
        final String schema =
                "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n"
                + "            xmlns:jitter=\"https://mictaege.github.io/jitter\">\n"
                + "  <xsd:element name=\"station\" type=\"xsd:string\" minOccurs=\"0\">\n"
                + "    <xsd:annotation>\n"
                + "      <xsd:appinfo>\n"
                + "        <jitter:set ifActive=\"Mpk\" minOccurs=\"1\" maxOccurs=\"1\"/>\n"
                + "        <jitter:set ifActive=\"Bmx\" maxOccurs=\"3\"/>\n"
                + "      </xsd:appinfo>\n"
                + "    </xsd:annotation>\n"
                + "  </xsd:element>\n"
                + "</xsd:schema>";
        final String mpk = XmlFlavourTransformer.transform(schema, "Mpk");
        assertThat(mpk, containsString("minOccurs=\"1\""));
        assertThat(mpk, containsString("maxOccurs=\"1\""));
        final String bmx = XmlFlavourTransformer.transform(schema, "Bmx");
        assertThat(bmx, containsString("minOccurs=\"0\""));
        assertThat(bmx, containsString("maxOccurs=\"3\""));
    }

    @Test
    void removesTheAnnotationsThatTheGateStrippingEmpties() {
        final String out = XmlFlavourTransformer.transform(SCHEMA, "Bmx");
        assertThat(out, not(containsString("annotation")));
        assertThat(out, not(containsString("appinfo")));
    }

    @Test
    void interpretsSeveralOnlyIfGatesAsAnIntersection() {
        // each onlyIf gate states its own constraint, so a component with
        // several gates is only part of the flavours that every gate names
        final String schema =
                "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n"
                + "            xmlns:jitter=\"https://mictaege.github.io/jitter\">\n"
                + "  <xsd:element name=\"both\" type=\"xsd:string\">\n"
                + "    <xsd:annotation>\n"
                + "      <xsd:appinfo>\n"
                + "        <jitter:onlyIf flavours=\"Mpk, Bmx\"/>\n"
                + "        <jitter:onlyIf flavours=\"Bmx, Dba\"/>\n"
                + "      </xsd:appinfo>\n"
                + "    </xsd:annotation>\n"
                + "  </xsd:element>\n"
                + "</xsd:schema>";
        assertThat(XmlFlavourTransformer.transform(schema, "Bmx"), containsString("name=\"both\""));
        assertThat(XmlFlavourTransformer.transform(schema, "Mpk"), not(containsString("name=\"both\"")));
        assertThat(XmlFlavourTransformer.transform(schema, "Dba"), not(containsString("name=\"both\"")));
        assertThat(XmlFlavourTransformer.transform(schema, ""), containsString("name=\"both\""));
    }

    @Test
    void keepsDocumentationAndOtherAppinfoContent() {
        final String schema =
                "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n"
                + "            xmlns:annox=\"urn:jaxb.jvnet.org:annox\"\n"
                + "            xmlns:jitter=\"https://mictaege.github.io/jitter\">\n"
                + "  <xsd:element name=\"order\" type=\"xsd:string\">\n"
                + "    <xsd:annotation>\n"
                + "      <xsd:documentation>the order element</xsd:documentation>\n"
                + "      <xsd:appinfo>\n"
                + "        <annox:annotate>@SuppressWarnings(\"x\")</annox:annotate>\n"
                + "        <jitter:onlyIf flavours=\"Bmx\"/>\n"
                + "      </xsd:appinfo>\n"
                + "    </xsd:annotation>\n"
                + "  </xsd:element>\n"
                + "</xsd:schema>";
        final String bmx = XmlFlavourTransformer.transform(schema, "Bmx");
        assertThat(bmx, containsString("the order element"));
        assertThat(bmx, containsString("annox:annotate"));
        assertThat(bmx, containsString("order"));
        assertThat(XmlFlavourTransformer.transform(schema, "Mpk"),
                not(containsString("the order element")));
    }

    @Test
    void keepsCommentsOutsideTheRootElement() {
        final String schema =
                "<!--\n  license header\n-->\n"
                + "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"\n"
                + "            xmlns:jitter=\"https://mictaege.github.io/jitter\">\n"
                + "  <xsd:element name=\"a\" type=\"xsd:string\">\n"
                + "    <xsd:annotation><xsd:appinfo><jitter:onlyIf flavours=\"Bmx\"/></xsd:appinfo></xsd:annotation>\n"
                + "  </xsd:element>\n"
                + "</xsd:schema>";
        assertThat(XmlFlavourTransformer.transform(schema, "Bmx"), containsString("license header"));
    }

    @Test
    void gateFilterResolvesGatedContentAndPassesOthersThrough() throws Exception {
        final StringReader in = new StringReader(SCHEMA);
        final XmlFlavourTransformer.GateFilter filter = new XmlFlavourTransformer.GateFilter(in);
        final StringBuilder out = new StringBuilder();
        for (int c = filter.read(); c >= 0; c = filter.read()) {
            out.append((char) c);
        }
        assertThat(out.toString(), containsString("flavourSpecific"));
        assertThat(out.toString(), not(containsString("jitter:")));
    }

}
