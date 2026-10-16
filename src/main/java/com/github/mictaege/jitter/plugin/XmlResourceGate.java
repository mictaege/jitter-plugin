package com.github.mictaege.jitter.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads the flavour gate of an XML resource from its own content: every
 * jitter annotation found inside an {@code appinfo} section of the file
 * gates the resource the same way the corresponding annotation gates the
 * classes - a resource whose gate does not include the active flavour is
 * not part of that flavour's processed resources.
 *
 * <p>The mechanism is generic: it does not depend on any particular XML
 * vocabulary. The {@code appinfo} element is the W3C standard annotation
 * hook, usable in schemas, bindings, stylesheets, service descriptions
 * and plain XML documents alike. Hand-written gates
 * ({@code <xsd:appinfo>@...OnlyIf("Dba")</xsd:appinfo>}) gate the resource.
 * xjc generation directives ({@code <annox:annotatePackage>@...OnlyIf(...)}
 * and other {@code annox:} elements) are deliberately NOT resource gates:
 * they belong to the class model (which classes are generated in which
 * flavour), and a schema whose generated classes are flavour-gated can
 * still be a runtime dependency of surviving code - e.g. as the
 * {@code xsd:import} source of base types used by flavour-specific
 * message schemas that validate against it.
 *
 * <p>Files without a gate pass through unchanged, and the gate never
 * renames a resource - unlike the flavour suffix convention it expresses
 * presence or absence only.
 */
final class XmlResourceGate {

    private static final List<String> XML_EXTENSIONS = Arrays.asList(
            "xsd", "xjb", "xsl", "xslt", "wsdl", "xml");

    /**
     * An appinfo section in any namespace, e.g. {@code <xsd:appinfo>} or
     * {@code <appinfo>}. Appinfo sections do not nest.
     */
    private static final Pattern APPINFO = Pattern.compile(
            "<(?:[\\w.-]+:)?appinfo\\b[^>]*>(.*?)</(?:[\\w.-]+:)?appinfo\\s*>",
            Pattern.DOTALL);

    /**
     * An xjc annox generation directive, e.g.
     * {@code <annox:annotatePackage>@OnlyIf(...)</annox:annotatePackage>}.
     * Class model, not resource gate (see class comment).
     */
    private static final Pattern ANNOX_ELEMENT = Pattern.compile(
            "<annox:\\w+\\b[^>]*>.*?</annox:\\w+\\s*>",
            Pattern.DOTALL);

    /** An OnlyIf annotation expression, fully qualified or by simple name. */
    private static final Pattern ONLY_IF = Pattern.compile(
            "@(?:com\\.github\\.mictaege\\.jitter\\.api\\.)?OnlyIf\\s*\\(([^)]*)\\)");

    /** The flavour values of the annotation, as quoted strings. */
    private static final Pattern QUOTED_VALUE = Pattern.compile("\"([^\"]*)\"");

    private XmlResourceGate() {
        super();
    }

    static boolean isXml(final String fileName) {
        final int dot = fileName.lastIndexOf('.');
        return dot >= 0
                && XML_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * The flavours the given resource is gated to, or null if the file is
     * not flavour gated (no XML file, no appinfo section, no jitter
     * annotation). Multiple annotations are merged.
     */
    static List<String> flavours(final File file) {
        if (!file.isFile()) {
            return null;
        }
        final List<String> flavours = new ArrayList<>();
        try {
            // the annotation syntax is ASCII, the read may not fail on
            // non-UTF-8 encodings
            final String content = new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.ISO_8859_1);
            final Matcher appinfo = APPINFO.matcher(content);
            while (appinfo.find()) {
                final String handWritten = ANNOX_ELEMENT
                        .matcher(appinfo.group(1)).replaceAll("");
                final Matcher onlyIf = ONLY_IF.matcher(handWritten);
                while (onlyIf.find()) {
                    final Matcher value = QUOTED_VALUE.matcher(onlyIf.group(1));
                    while (value.find()) {
                        flavours.add(value.group(1));
                    }
                }
            }
        } catch (final IOException e) {
            // the resource processing has no strip result to report through:
            // the prefixed logger call is the only channel here
            JitterUtil.log().warn("jitter: could not read the flavour gate of " + file + ": "
                    + e.getMessage());
        }
        return flavours.isEmpty() ? null : flavours;
    }

}
