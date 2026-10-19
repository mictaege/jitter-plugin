package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class KotlinUsageFinderTest extends StripperTestFixture {

    @Test
    void shouldAcceptDottedUsageOnConsistentlyGatedMember() throws Exception {
        final List<Path> src = writeKotlinSources(
                "test/Constants.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "class Constants {\n"
                + "  companion object {\n"
                + "    @OnlyIf(\"Esa\")\n"
                + "    @InlinedConstantApproved\n"
                + "    const val VALUE = \"value\"\n"
                + "  }\n"
                + "}\n",
                "test/Consumer.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "class Consumer {\n"
                + "  @OnlyIf(\"Esa\")\n"
                + "  fun esaOnly() {\n"
                + "    println(Constants.VALUE)\n"
                + "  }\n"
                + "}\n");

        final List<String> errors = check(src, Collections.<File>emptyList());

        assertThat(errors.isEmpty(), is(true));
    }

    @Test
    void shouldFailOnUngatedDottedUsage() throws Exception {
        final List<Path> src = writeKotlinSources(
                "test/Constants.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "class Constants {\n"
                + "  companion object {\n"
                + "    @OnlyIf(\"Esa\")\n"
                + "    @InlinedConstantApproved\n"
                + "    const val VALUE = \"value\"\n"
                + "  }\n"
                + "}\n",
                "test/Consumer.kt",
                "package test\n"
                + "class Consumer {\n"
                + "  fun anyFlavour() {\n"
                + "    println(Constants.VALUE)\n"
                + "  }\n"
                + "}\n");

        final List<String> errors = check(src, Collections.<File>emptyList());

        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("test/Consumer.kt"));
        assertThat(errors.get(0), containsString("test.Constants#VALUE"));
        assertThat(errors.get(0), containsString("nasa"));
    }

    @Test
    void shouldFailOnUsageKeptInMoreFlavoursThanTheConstant() throws Exception {
        final List<Path> src = writeKotlinSources(
                "test/Constants.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "class Constants {\n"
                + "  companion object {\n"
                + "    @OnlyIf(\"Esa\")\n"
                + "    @InlinedConstantApproved\n"
                + "    const val VALUE = \"value\"\n"
                + "  }\n"
                + "}\n",
                "test/Consumer.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "class Consumer {\n"
                + "  @OnlyIf(\"Esa\", \"Nasa\")\n"
                + "  fun bothFlavours() {\n"
                + "    println(Constants.VALUE)\n"
                + "  }\n"
                + "}\n");

        final List<String> errors = check(src, Collections.<File>emptyList());

        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("nasa"));
    }

    @Test
    void shouldFailOnBareCompanionUsageInUngatedMember() throws Exception {
        final List<Path> src = writeKotlinSources(
                "test/Consumer.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "class Consumer {\n"
                + "  companion object {\n"
                + "    @OnlyIf(\"Esa\")\n"
                + "    @InlinedConstantApproved\n"
                + "    const val VIEW_NAME = \"view\"\n"
                + "  }\n"
                + "  fun anyFlavour() {\n"
                + "    println(VIEW_NAME)\n"
                + "  }\n"
                + "}\n");

        final List<String> errors = check(src, Collections.<File>emptyList());

        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("test.Consumer#VIEW_NAME"));
        assertThat(errors.get(0), containsString("anyFlavour"));
    }

    @Test
    void shouldFailOnImportedConstantUsage() throws Exception {
        final List<Path> src = writeKotlinSources(
                "test/Constants.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "class Constants {\n"
                + "  companion object {\n"
                + "    @OnlyIf(\"Esa\")\n"
                + "    @InlinedConstantApproved\n"
                + "    const val VALUE = \"value\"\n"
                + "  }\n"
                + "}\n",
                "test/Consumer.kt",
                "package test\n"
                + "import test.Constants.Companion.VALUE\n"
                + "class Consumer {\n"
                + "  fun anyFlavour() {\n"
                + "    println(VALUE)\n"
                + "  }\n"
                + "}\n");

        final List<String> errors = check(src, Collections.<File>emptyList());

        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("test.Constants#VALUE"));
    }

    @Test
    void shouldApplyTheSubsetRuleToClassGatedConstants() throws Exception {
        final List<Path> src = writeKotlinSources(
                "test/GatedHolder.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "@OnlyIf(\"Esa\")\n"
                + "class GatedHolder {\n"
                + "  companion object {\n"
                + "    @InlinedConstantApproved\n"
                + "    const val NAME = \"holder\"\n"
                + "  }\n"
                + "}\n",
                "test/Consumer.kt",
                "package test\n"
                + "class Consumer {\n"
                + "  fun anyFlavour() {\n"
                + "    println(GatedHolder.NAME)\n"
                + "  }\n"
                + "}\n");

        final List<String> errors = check(src, Collections.<File>emptyList());

        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("test.GatedHolder#NAME"));
        assertThat(errors.get(0), containsString("anyFlavour"));
    }

    @Test
    void shouldVerifyUsagesOfConstantsFromTheClasspath() throws Exception {
        final Path dependencyClasses = compile(
                "test/ExternalConstants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class ExternalConstants {\n"
                + "  @OnlyIf({\"Esa\"})\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final String VALUE = \"value\";\n"
                + "}\n");
        final List<Path> src = writeKotlinSources(
                "other/Consumer.kt",
                "package other\n"
                + "class Consumer {\n"
                + "  fun anyFlavour() {\n"
                + "    println(test.ExternalConstants.VALUE)\n"
                + "  }\n"
                + "}\n");

        final List<String> errors = check(src,
                Collections.singletonList(dependencyClasses.toFile()));

        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("test.ExternalConstants#VALUE"));
        assertThat(errors.get(0), containsString("nasa"));
    }

    @Test
    void shouldResolveGatesWrittenAsConstantReferences() throws Exception {
        final Path flavourClasses = compile(
                "test/Flavour.java",
                "package test;\n"
                + "public class Flavour {\n"
                + "  public static final String ESA = \"Esa\";\n"
                + "  public static final String NASA = \"Nasa\";\n"
                + "}\n");
        final List<Path> src = writeKotlinSources(
                "test/Constants.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "class Constants {\n"
                + "  companion object {\n"
                + "    @OnlyIf(test.Flavour.ESA)\n"
                + "    @InlinedConstantApproved\n"
                + "    const val VALUE = \"value\"\n"
                + "  }\n"
                + "}\n",
                "test/Consumer.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "class Consumer {\n"
                + "  @OnlyIf(test.Flavour.ESA)\n"
                + "  fun esaOnly() {\n"
                + "    println(Constants.VALUE)\n"
                + "  }\n"
                + "  @OnlyIf(test.Flavour.NASA)\n"
                + "  fun nasaOnly() {\n"
                + "    println(Constants.VALUE)\n"
                + "  }\n"
                + "}\n");

        final List<String> errors = check(src,
                Collections.singletonList(flavourClasses.toFile()));

        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("nasaOnly"));
        assertThat(errors.get(0), containsString("nasa"));
    }

    @Test
    void shouldFailOnAlteredConstantBodyUsage() throws Exception {
        final List<Path> src = writeKotlinSources(
                "test/TableNames.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.Alter\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved\n"
                + "@Alter(ifActive = \"Esa\", with = \"TableNamesA\")\n"
                + "class TableNames {\n"
                + "  companion object {\n"
                + "    @InlinedConstantApproved\n"
                + "    const val JOB = \"fullJob\"\n"
                + "  }\n"
                + "}\n",
                "test/Job.kt",
                "package test\n"
                + "class Job {\n"
                + "  fun name(): String {\n"
                + "    return TableNames.JOB\n"
                + "  }\n"
                + "}\n");

        final List<String> errors = check(src, Collections.<File>emptyList());

        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("test.TableNames#JOB"));
        assertThat(errors.get(0), containsString("outside an annotation"));
    }

    @Test
    void shouldAcceptAlteredConstantInAnnotationsOnly() throws Exception {
        final List<Path> src = writeKotlinSources(
                "test/TableNames.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.Alter\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved\n"
                + "@Alter(ifActive = \"Esa\", with = \"TableNamesA\")\n"
                + "class TableNames {\n"
                + "  companion object {\n"
                + "    @InlinedConstantApproved\n"
                + "    const val JOB = \"fullJob\"\n"
                + "  }\n"
                + "}\n",
                "test/Job.kt",
                "package test\n"
                + "annotation class Name(val value: String)\n"
                + "class Job {\n"
                + "  @Name(TableNames.JOB)\n"
                + "  fun annotated() {\n"
                + "  }\n"
                + "}\n");

        final List<String> errors = check(src, Collections.<File>emptyList());

        assertThat(errors.isEmpty(), is(true));
    }

    @Test
    void shouldIgnoreUnmarkedConstants() throws Exception {
        final List<Path> src = writeKotlinSources(
                "test/Constants.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "class Constants {\n"
                + "  companion object {\n"
                + "    @OnlyIf(\"Esa\")\n"
                + "    const val VALUE = \"value\"\n"
                + "  }\n"
                + "}\n",
                "test/Consumer.kt",
                "package test\n"
                + "class Consumer {\n"
                + "  fun anyFlavour() {\n"
                + "    println(Constants.VALUE)\n"
                + "  }\n"
                + "}\n");

        final List<String> errors = check(src, Collections.<File>emptyList());

        // no marker, no inlining: the bytecode verification sees the usage
        assertThat(errors.isEmpty(), is(true));
    }

    @Test
    void shouldVerifyTopLevelConstantUsagesOfTheOwnFile() throws Exception {
        final List<Path> src = writeKotlinSources(
                "test/Top.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "@OnlyIf(\"Esa\")\n"
                + "@InlinedConstantApproved\n"
                + "const val TOP = \"top\"\n"
                + "fun consume() {\n"
                + "  println(TOP)\n"
                + "}\n");

        final List<String> errors = check(src, Collections.<File>emptyList());

        assertThat(errors.size(), is(1));
        assertThat(errors.get(0), containsString("test.TopKt#TOP"));
        assertThat(errors.get(0), containsString("consume"));
    }

    @Test
    void shouldConsiderPackageGates() throws Exception {
        final Path packageClasses = compile(
                "test/package-info.java",
                "@com.github.mictaege.jitter.api.OnlyIf(\"Esa\")\n"
                + "package test;\n");
        final List<Path> src = writeKotlinSources(
                "test/Constants.kt",
                "package test\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "class Constants {\n"
                + "  companion object {\n"
                + "    @OnlyIf(\"Esa\", \"Nasa\")\n"
                + "    @InlinedConstantApproved\n"
                + "    const val VALUE = \"value\"\n"
                + "  }\n"
                + "}\n",
                "test/Consumer.kt",
                "package test\n"
                + "class Consumer {\n"
                + "  fun anyFlavour() {\n"
                + "    println(Constants.VALUE)\n"
                + "  }\n"
                + "}\n");

        final List<String> errors = check(src,
                Collections.singletonList(packageClasses.toFile()));

        // the package gate removes the whole package in Nasa together with
        // the constant: the ungated usage cannot leak the inlined value
        assertThat(errors.isEmpty(), is(true));
    }

    private List<String> check(final List<Path> src, final List<File> classpath)
            throws IOException {
        try (KotlinUsageFinder finder = new KotlinUsageFinder(classpath,
                Arrays.asList("Esa", "Nasa"))) {
            return finder.check(src);
        }
    }

    private List<Path> writeKotlinSources(final String... nameAndSourcePairs) throws IOException {
        if (nameAndSourcePairs.length % 2 != 0) {
            throw new IllegalArgumentException("expected name/source pairs");
        }
        final List<Path> files = new ArrayList<>();
        for (int i = 0; i < nameAndSourcePairs.length; i += 2) {
            final Path file = workDir.resolve("kt").resolve(nameAndSourcePairs[i]);
            Files.createDirectories(file.getParent());
            Files.write(file, nameAndSourcePairs[i + 1].getBytes(StandardCharsets.UTF_8));
            files.add(file);
        }
        return files;
    }

}
