package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class InlinedConstantsCheckerTest extends StripperTestFixture {

    @Test
    void shouldAcceptUsageOnConsistentlyGatedMember() throws Exception {
        final Path src = writeSources(
                "test/Constants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Constants {\n"
                + "  @OnlyIf({\"Esa\"})\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final String VALUE = \"value\";\n"
                + "}\n",
                "test/Consumer.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Consumer {\n"
                + "  @OnlyIf(\"Esa\")\n"
                + "  public void esaOnly() {\n"
                + "    System.out.println(Constants.VALUE);\n"
                + "  }\n"
                + "}\n");

        final List<String> errors =
                new InlinedConstantsChecker(Collections.<File>emptyList(),
                        Arrays.asList("Esa", "Nasa")).check(Collections.singletonList(src));

        assertThat(errors.isEmpty(), is(true));
    }

    @Test
    void shouldFailOnUngatedUsage() throws Exception {
        final Path src = writeSources(
                "test/Constants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Constants {\n"
                + "  @OnlyIf({\"Esa\"})\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final String VALUE = \"value\";\n"
                + "}\n",
                "test/Consumer.java",
                "package test;\n"
                + "public class Consumer {\n"
                + "  public void anyFlavour() {\n"
                + "    System.out.println(Constants.VALUE);\n"
                + "  }\n"
                + "}\n");

        final List<String> errors =
                new InlinedConstantsChecker(Collections.<File>emptyList(),
                        Arrays.asList("Esa", "Nasa")).check(Collections.singletonList(src));

        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("test/Consumer.java"));
        assertThat(errors.get(0), containsString("test.Constants#VALUE"));
        assertThat(errors.get(0), containsString("nasa"));
    }

    @Test
    void shouldFailOnUsageKeptInMoreFlavoursThanTheConstant() throws Exception {
        final Path src = writeSources(
                "test/Constants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Constants {\n"
                + "  @OnlyIf({\"Esa\"})\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final String VALUE = \"value\";\n"
                + "}\n",
                "test/Consumer.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Consumer {\n"
                + "  @OnlyIf({\"Esa\", \"Nasa\"})\n"
                + "  public void bothFlavours() {\n"
                + "    System.out.println(Constants.VALUE);\n"
                + "  }\n"
                + "}\n");

        final List<String> errors =
                new InlinedConstantsChecker(Collections.<File>emptyList(),
                        Arrays.asList("Esa", "Nasa")).check(Collections.singletonList(src));

        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("nasa"));
    }

    @Test
    void shouldConsiderClassAndPackageGates() throws Exception {
        final Path src = writeSources(
                "test/Constants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Constants {\n"
                + "  @OnlyIf({\"Esa\"})\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final String VALUE = \"value\";\n"
                + "}\n",
                "test/GatedConsumer.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "@OnlyIf(\"Esa\")\n"
                + "public class GatedConsumer {\n"
                + "  public void esaClassOnly() {\n"
                + "    System.out.println(Constants.VALUE);\n"
                + "  }\n"
                + "  @OnlyIf({\"Esa\", \"Nasa\"})\n"
                + "  public void widened() {\n"
                + "    System.out.println(Constants.VALUE);\n"
                + "  }\n"
                + "}\n",
                "test/Consumer.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Consumer {\n"
                + "  @OnlyIf({\"Esa\", \"Nasa\"})\n"
                + "  public void widenedInUngatedClass() {\n"
                + "    System.out.println(Constants.VALUE);\n"
                + "  }\n"
                + "}\n");

        final List<String> errors =
                new InlinedConstantsChecker(Collections.<File>emptyList(),
                        Arrays.asList("Esa", "Nasa")).check(Collections.singletonList(src));

        // the class gate keeps both methods of GatedConsumer consistent, even
        // the widened one: in Nasa the whole class is removed together with
        // the constant. Only the widened method of the ungated class leaks.
        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("widenedInUngatedClass"));
    }

    @Test
    void shouldFailOnStaticImportUsage() throws Exception {
        final Path src = writeSources(
                "test/Constants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Constants {\n"
                + "  @OnlyIf({\"Esa\"})\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final String VALUE = \"value\";\n"
                + "}\n",
                "test/Consumer.java",
                "package test;\n"
                + "import static test.Constants.VALUE;\n"
                + "public class Consumer {\n"
                + "  public void anyFlavour() {\n"
                + "    System.out.println(VALUE);\n"
                + "  }\n"
                + "}\n");

        final List<String> errors =
                new InlinedConstantsChecker(Collections.<File>emptyList(),
                        Arrays.asList("Esa", "Nasa")).check(Collections.singletonList(src));

        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("test.Constants#VALUE"));
    }

    @Test
    void shouldVerifyUsagesOfConstantsFromTheClasspath() throws Exception {
        final Path dependencyClasses = compile("test/ExternalConstants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class ExternalConstants {\n"
                + "  @OnlyIf({\"Esa\"})\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final String VALUE = \"value\";\n"
                + "}\n");
        final Path src = writeSources(
                "other/Consumer.java",
                "package other;\n"
                + "public class Consumer {\n"
                + "  public void anyFlavour() {\n"
                + "    System.out.println(test.ExternalConstants.VALUE);\n"
                + "  }\n"
                + "}\n");

        final List<String> errors = new InlinedConstantsChecker(
                Collections.singletonList(dependencyClasses.toFile()),
                Arrays.asList("Esa", "Nasa")).check(Collections.singletonList(src));

        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("test.ExternalConstants#VALUE"));
        assertThat(errors.get(0), containsString("nasa"));
    }

    @Test
    void shouldIgnoreUnmarkedConstants() throws Exception {
        final Path src = writeSources(
                "test/Constants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Constants {\n"
                + "  @OnlyIf({\"Esa\"})\n"
                + "  public static String VALUE = \"value\";\n"
                + "}\n",
                "test/Consumer.java",
                "package test;\n"
                + "public class Consumer {\n"
                + "  public void anyFlavour() {\n"
                + "    System.out.println(Constants.VALUE);\n"
                + "  }\n"
                + "}\n");

        final List<String> errors =
                new InlinedConstantsChecker(Collections.<File>emptyList(),
                        Arrays.asList("Esa", "Nasa")).check(Collections.singletonList(src));

        // no marker, no inlining: the bytecode verification sees the usage
        assertThat(errors.isEmpty(), is(true));
    }

    @Test
    void shouldAcceptAlteredConstantInAnnotationsOnly() throws Exception {
        final Path src = writeSources(
                "test/TableNames.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.Alter;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "@Alter(ifActive = \"Esa\", with = \"TableNamesA\")\n"
                + "public class TableNames {\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final String JOB = \"fullJob\";\n"
                + "}\n",
                "test/Job.java",
                "package test;\n"
                + "public class Job {\n"
                + "  @SuppressWarnings(\"something\")\n"
                + "  public String name() {\n"
                + "    return TableNames.JOB;\n"
                + "  }\n"
                + "}\n");

        final List<String> errors =
                new InlinedConstantsChecker(Collections.<File>emptyList(),
                        Arrays.asList("Esa", "Nasa")).check(Collections.singletonList(src));

        // no annotation usage here: the body usage keeps the full model
        // value and must fail
        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("test.TableNames#JOB"));
        assertThat(errors.get(0), containsString("outside an annotation"));
    }

    @Test
    void shouldResolveGatesWrittenAsConstantReferences() throws Exception {
        final Path src = writeSources(
                "test/Flavour.java",
                "package test;\n"
                + "public class Flavour {\n"
                + "  public static final String ESA = \"Esa\";\n"
                + "  public static final String NASA = \"Nasa\";\n"
                + "}\n",
                "test/Constants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Constants {\n"
                + "  @OnlyIf(Flavour.ESA)\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final String VALUE = \"value\";\n"
                + "}\n",
                "test/Consumer.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Consumer {\n"
                + "  @OnlyIf(Flavour.ESA)\n"
                + "  public void esaOnly() {\n"
                + "    System.out.println(Constants.VALUE);\n"
                + "  }\n"
                + "  @OnlyIf(Flavour.NASA)\n"
                + "  public void nasaOnly() {\n"
                + "    System.out.println(Constants.VALUE);\n"
                + "  }\n"
                + "}\n");

        final List<String> errors =
                new InlinedConstantsChecker(Collections.<File>emptyList(),
                        Arrays.asList("Esa", "Nasa")).check(Collections.singletonList(src));

        // the ESA usage is consistent, the NASA usage is kept in a flavour
        // where the constant is removed - only possible to tell when the
        // constant references of the gates are resolved
        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("nasaOnly"));
        assertThat(errors.get(0), containsString("nasa"));
    }

    @Test
    void shouldLetOwnDeclarationShadowTheMarkedInheritedConstant() throws Exception {
        final Path src = writeSources(
                "test/Constants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Constants {\n"
                + "  @OnlyIf(\"Esa\")\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final String VALUE = \"value\";\n"
                + "}\n",
                "test/Site.java",
                "package test;\n"
                + "public class Site extends Constants {\n"
                + "  private static final String VALUE = \"own value\";\n"
                + "  public String anyFlavour() {\n"
                + "    return VALUE;\n"
                + "  }\n"
                + "}\n");

        final List<String> errors =
                new InlinedConstantsChecker(Collections.<File>emptyList(),
                        Arrays.asList("Esa", "Nasa")).check(Collections.singletonList(src));

        // the own unmarked constant shadows the marked inherited one:
        // javac inlines the own value, which is not flavour variant
        assertThat(errors.isEmpty(), is(true));
    }

    @Test
    void shouldApplyTheSubsetRuleToClassGatedConstants() throws Exception {
        final Path src = writeSources(
                "test/package-info.java",
                "package test;\n",
                "test/GatedHolder.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "@OnlyIf(\"Esa\")\n"
                + "public class GatedHolder {\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final String NAME = \"holder\";\n"
                + "}\n",
                "test/Consumer.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Consumer {\n"
                + "  @OnlyIf(\"Esa\")\n"
                + "  public void esaOnly() {\n"
                + "    System.out.println(GatedHolder.NAME);\n"
                + "  }\n"
                + "  public void anyFlavour() {\n"
                + "    System.out.println(GatedHolder.NAME);\n"
                + "  }\n"
                + "}\n");

        final List<String> errors =
                new InlinedConstantsChecker(Collections.<File>emptyList(),
                        Arrays.asList("Esa", "Nasa")).check(Collections.singletonList(src));

        // the constant has no field gate, but its class gate is the
        // effective gate: the ungated usage leaks in the other flavours
        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("test.GatedHolder#NAME"));
        assertThat(errors.get(0), containsString("anyFlavour"));
    }

    private Path writeSources(final String... nameAndSourcePairs) throws IOException {
        if (nameAndSourcePairs.length % 2 != 0) {
            throw new IllegalArgumentException("expected name/source pairs");
        }
        final Path sources = Files.createTempDirectory("jitter-sources");
        for (int i = 0; i < nameAndSourcePairs.length; i += 2) {
            final Path file = sources.resolve(nameAndSourcePairs[i]);
            Files.createDirectories(file.getParent());
            Files.write(file, nameAndSourcePairs[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return sources;
    }

}
