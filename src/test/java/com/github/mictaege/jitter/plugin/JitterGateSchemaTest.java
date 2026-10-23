package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * The schema of the flavour gate vocabulary, shipped as
 * {@code jitter-gate.xsd} with the plugin: it describes the
 * {@code jitter:onlyIf} and {@code jitter:set} elements, so an IDE that
 * registers it for the namespace
 * {@code https://mictaege.github.io/jitter} gives completion and inspection
 * for the gates instead of showing the namespace as unregistered. The
 * schema deliberately describes only the gate elements themselves: the
 * gates sit inside the W3C standard annotation/appinfo hook of the gated
 * component, so the surrounding document belongs to its own vocabulary
 * with its own schema.
 */
public class JitterGateSchemaTest {

    private static final String GATE_NS = "https://mictaege.github.io/jitter";

    private final Schema schema = loadSchema();

    private static Schema loadSchema() {
        try (var in = JitterGateSchemaTest.class.getClassLoader().getResourceAsStream("jitter-gate.xsd")) {
            if (in == null) {
                throw new IllegalStateException("jitter-gate.xsd is missing from the plugin resources");
            }
            final SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            return factory.newSchema(new StreamSource(in));
        } catch (final SAXException e) {
            throw new IllegalStateException("jitter-gate.xsd is not a valid schema", e);
        } catch (final IOException e) {
            throw new IllegalStateException("jitter-gate.xsd could not be read", e);
        }
    }

    @Test
    void validatesAnOnlyIfGate() throws Exception {
        assertThat(validate("<jitter:onlyIf flavours=\"Mpk\" xmlns:jitter=\"" + GATE_NS + "\"/>"),
                is(true));
        assertThat(validate("<jitter:onlyIf flavours=\"Mpk, Bmx\" xmlns:jitter=\"" + GATE_NS + "\"/>"),
                is(true));
        assertThat(validate("<jitter:onlyIf flavours=\"Mpk,Bmx,Dba\" xmlns:jitter=\"" + GATE_NS + "\"/>"),
                is(true));
    }

    @Test
    void validatesASetGateWithArbitraryComponentAttributes() throws Exception {
        // every attribute of the gate other than ifActive is set on the
        // annotated component - minOccurs, use, maxOccurs and so on. They
        // are unprefixed, i.e. of no namespace
        assertThat(validate("<jitter:set ifActive=\"Bmx\" minOccurs=\"1\""
                        + " xmlns:jitter=\"" + GATE_NS + "\"/>"),
                is(true));
        assertThat(validate("<jitter:set ifActive=\"Bmx, Dba\" use=\"required\" maxOccurs=\"3\""
                        + " xmlns:jitter=\"" + GATE_NS + "\"/>"),
                is(true));
    }

    @Test
    void rejectsAGateWithoutItsFlavourList() throws Exception {
        assertThat(validate("<jitter:onlyIf xmlns:jitter=\"" + GATE_NS + "\"/>"), is(false));
        assertThat(validate("<jitter:set xmlns:jitter=\"" + GATE_NS + "\"/>"), is(false));
        assertThat(validate("<jitter:onlyIf flavours=\"\" xmlns:jitter=\"" + GATE_NS + "\"/>"), is(false));
    }

    @Test
    void rejectsAMalformedFlavourList() throws Exception {
        assertThat(validate("<jitter:onlyIf flavours=\"Mpk,,Bmx\" xmlns:jitter=\"" + GATE_NS + "\"/>"),
                is(false));
    }

    @Test
    void rejectsUnknownGateElements() throws Exception {
        assertThat(validate("<jitter:rename flavours=\"Mpk\" xmlns:jitter=\"" + GATE_NS + "\"/>"),
                is(false));
        // an unprefixed gate element is of no namespace and therefore no
        // gate at all - the namespace makes the element, not the prefix
        assertThat(validate("<onlyIf flavours=\"Mpk\"/>"), is(false));
    }

    private boolean validate(final String xml) {
        final Validator validator = schema.newValidator();
        try {
            validator.validate(new StreamSource(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
            return true;
        } catch (final SAXException | IOException e) {
            return false;
        }
    }

}
