package com.github.mictaege.jitter.plugin;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Resolves the flavour gates of an XML resource: components of the document
 * that carry a jitter gate in their own {@code annotation}/{@code appinfo}
 * section are kept, removed or reconfigured for the active flavour while the
 * file is copied into the processed resources.
 *
 * <p>The gate vocabulary lives in the namespace
 * {@code https://mictaege.github.io/jitter} and has two elements:
 * <ul>
 * <li>{@code <jitter:onlyIf flavours="Bmx"/>} - the annotated component is
 * only part of the flavours named in the (comma or blank separated) list and
 * is removed from the document for every other active flavour. Without an
 * active flavour (the full model) every component is kept.</li>
 * <li>{@code <jitter:set ifActive="Bmx" minOccurs="1"/>} - every other
 * attribute of the gate is set on the annotated component while one of the
 * listed flavours is active. The component keeps its own attribute value
 * otherwise. Several {@code set} gates may annotate the same component, as
 * with {@code @AlterConstValue}.</li>
 * </ul>
 *
 * <p>The gates sit inside the W3C standard {@code appinfo} hook, so the
 * unified source document stays valid for every tool that reads it - in
 * particular for xjc, which simply ignores the gates. The resolved document
 * no longer contains them: the gate elements are stripped like the jitter
 * annotations are stripped from the bytecode, and an {@code appinfo} or
 * {@code annotation} section that becomes empty by the stripping is removed
 * as well.
 *
 * <p>The mechanism is generic: it does not depend on any particular XML
 * vocabulary. It applies to any XML file that declares the gate namespace.
 */
final class XmlFlavourTransformer {

    /** The namespace of the flavour gate elements. */
    static final String JITTER_NS = "https://mictaege.github.io/jitter";

    private static final String ONLY_IF = "onlyIf";
    private static final String SET = "set";
    private static final String FLAVOURS = "flavours";
    private static final String IF_ACTIVE = "ifActive";
    private static final String ANNOTATION = "annotation";
    private static final String APPINFO = "appinfo";

    private XmlFlavourTransformer() {
        super();
    }

    /**
     * Does the given file content declare the gate namespace? Files without
     * the namespace are passed through unchanged, so the transformation is
     * opt-in per file.
     */
    static boolean gated(final String content) {
        return content != null && content.contains(JITTER_NS);
    }

    /**
     * The flavour resolved form of the given XML document: components with a
     * matching {@code onlyIf} gate are kept, the others removed, {@code set}
     * gates are applied and all gate elements are stripped. The given
     * content must declare the gate namespace (see {@link #gated(String)}).
     *
     * @param xml           the source document, gates included
     * @param activeFlavour the active flavour, empty for the full model
     * @return the resolved document, gates stripped
     */
    static String transform(final String xml, final String activeFlavour) {
        try {
            final Document document = parse(xml);
            resolveGates(document, activeFlavour);
            stripGateElements(document);
            removeEmptyAnnotations(document);
            return serialize(document);
        } catch (final Exception e) {
            // a gate the transformer cannot resolve is a build problem, not
            // a silently degraded resource
            throw new IllegalStateException(
                    "jitter: could not resolve the flavour gates of an XML resource: "
                            + e.getMessage(), e);
        }
    }

    private static Document parse(final String xml) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        final org.xml.sax.InputSource source = new org.xml.sax.InputSource();
        source.setCharacterStream(new StringReader(xml));
        return factory.newDocumentBuilder().parse(source);
    }

    private static void resolveGates(final Document document, final String activeFlavour) {
        final List<Element> removals = new ArrayList<>();
        for (final Element component : descendantsOf(document)) {
            for (final Element gate : directGates(component)) {
                if (ONLY_IF.equals(gate.getLocalName())) {
                    final List<String> flavours = flavourList(gate.getAttribute(FLAVOURS));
                    if (!activeFlavour.isEmpty() && !matches(flavours, activeFlavour)) {
                        removals.add(component);
                    }
                } else if (SET.equals(gate.getLocalName())) {
                    final List<String> flavours = flavourList(gate.getAttribute(IF_ACTIVE));
                    if (!activeFlavour.isEmpty() && matches(flavours, activeFlavour)) {
                        for (int i = 0; i < gate.getAttributes().getLength(); i++) {
                            final Node attribute = gate.getAttributes().item(i);
                            if (!IF_ACTIVE.equals(attribute.getNodeName())) {
                                component.setAttribute(attribute.getNodeName(),
                                        attribute.getNodeValue());
                            }
                        }
                    }
                }
            }
        }
        for (final Element removed : removals) {
            removed.getParentNode().removeChild(removed);
        }
    }

    private static void stripGateElements(final Document document) {
        final List<Element> gates = new ArrayList<>();
        for (final Element element : descendantsOf(document)) {
            if (JITTER_NS.equals(element.getNamespaceURI())
                    && (ONLY_IF.equals(element.getLocalName())
                            || SET.equals(element.getLocalName()))) {
                gates.add(element);
            }
        }
        for (final Element gate : gates) {
            gate.getParentNode().removeChild(gate);
        }
    }

    private static void removeEmptyAnnotations(final Document document) {
        // after the gate stripping: an appinfo that holds nothing but
        // whitespace and an annotation that holds nothing but whitespace are
        // removed, so the resolved document carries no gate residue. The
        // removal runs repeatedly: an annotation whose only content is an
        // empty appinfo becomes empty itself only after the first pass
        boolean removed = true;
        while (removed) {
            removed = false;
            final List<Element> empties = new ArrayList<>();
            for (final Element element : descendantsOf(document)) {
                final String localName = element.getLocalName();
                if ((APPINFO.equals(localName) || ANNOTATION.equals(localName))
                        && !hasContent(element)) {
                    empties.add(element);
                }
            }
            for (final Element empty : empties) {
                empty.getParentNode().removeChild(empty);
                removed = true;
            }
        }
    }

    private static boolean hasContent(final Element element) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.TEXT_NODE
                    || !child.getTextContent().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The direct gate elements of the given component: the {@code onlyIf}
     * and {@code set} children of its {@code annotation}/{@code appinfo}
     * sections.
     */
    private static List<Element> directGates(final Element component) {
        final List<Element> gates = new ArrayList<>();
        for (final Element annotation : directChildren(component, ANNOTATION)) {
            for (final Element appinfo : directChildren(annotation, APPINFO)) {
                for (Node child = appinfo.getFirstChild(); child != null;
                        child = child.getNextSibling()) {
                    if (child.getNodeType() == Node.ELEMENT_NODE) {
                        final Element element = (Element) child;
                        if (JITTER_NS.equals(element.getNamespaceURI())) {
                            gates.add(element);
                        }
                    }
                }
            }
        }
        return gates;
    }

    private static List<Element> directChildren(final Element parent, final String localName) {
        final List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && localName.equals(child.getLocalName())) {
                children.add((Element) child);
            }
        }
        return children;
    }

    private static List<Element> descendantsOf(final Document document) {
        final List<Element> elements = new ArrayList<>();
        collect(document.getDocumentElement(), elements);
        return elements;
    }

    private static void collect(final Element element, final List<Element> collected) {
        collected.add(element);
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                collect((Element) child, collected);
            }
        }
    }

    private static List<String> flavourList(final String attribute) {
        return attribute == null || attribute.trim().isEmpty()
                ? Arrays.<String>asList()
                : Arrays.asList(attribute.trim().split("[,\\s]+"));
    }

    private static boolean matches(final List<String> flavours, final String activeFlavour) {
        for (final String flavour : flavours) {
            if (flavour.equalsIgnoreCase(activeFlavour)) {
                return true;
            }
        }
        return false;
    }

    private static String serialize(final Document document) throws Exception {
        final TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        final Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(out));
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /**
     * The Gradle copy filter that resolves the flavour gates while the
     * resource is copied: the file content flows through this reader and
     * comes out as the active flavour's variant of the document. Untouched
     * files (see {@link #gated(String)}) pass through unchanged.
     */
    public static final class GateFilter extends java.io.FilterReader {
        private StringReader resolved;

        public GateFilter(final Reader input) {
            super(input);
        }

        private void resolve() {
            if (resolved != null) {
                return;
            }
            final String content = readAll(in);
            resolved = new StringReader(
                    gated(content) ? transform(content, JitterUtil.activeFlavour()) : content);
        }

        @Override
        public int read() throws IOException {
            resolve();
            return resolved.read();
        }

        @Override
        public int read(final char[] buffer, final int off, final int len) throws IOException {
            resolve();
            return resolved.read(buffer, off, len);
        }

        private static String readAll(final Reader reader) {
            try (Reader r = reader) {
                final StringBuilder content = new StringBuilder();
                final char[] buffer = new char[8192];
                for (int read = r.read(buffer); read >= 0; read = r.read(buffer)) {
                    content.append(buffer, 0, read);
                }
                return content.toString();
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

}
