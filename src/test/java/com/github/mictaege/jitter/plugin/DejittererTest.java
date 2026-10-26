package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class DejittererTest {

    @TempDir
    Path workDir;

    private Path src;
    private Path out;

    @org.junit.jupiter.api.BeforeEach
    void setUpDirs() {
        src = workDir.resolve("src");
        out = workDir.resolve("out");
    }

    @Test
    void removesClassesOfInactiveFlavours() throws Exception {
        write("com/example/Foo.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Foo { }\n");
        write("com/example/EsaFoo.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "@OnlyIf(\"Esa\")\n"
                + "public class EsaFoo { }\n");
        write("com/example/NasaFoo.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "@OnlyIf(\"Nasa\")\n"
                + "/** javadoc of the class */\n"
                + "public class NasaFoo { }\n");

        final StripResult result = dejitter("Esa");

        assertThat(Files.exists(out.resolve("com/example/Foo.java")), is(true));
        assertThat(Files.exists(out.resolve("com/example/EsaFoo.java")), is(true));
        assertThat(Files.exists(out.resolve("com/example/NasaFoo.java")), is(false));
        assertThat(result.getRemovedClasses(), is(Set.of("com/example/NasaFoo")));
        assertThat(read("com/example/EsaFoo.java"), not(containsString("OnlyIf")));
        assertThat(read("com/example/EsaFoo.java"), not(containsString("import com.github.mictaege.jitter.api.OnlyIf;")));
    }

    @Test
    void keepsActivePackageInfosWithCleanedAnnotation() throws Exception {
        write("com/example/esa/package-info.java",
                "/** package docs of the esa package */\n"
                + "@com.github.mictaege.jitter.api.OnlyIf(\"Esa\")\n"
                + "package com.example.esa;\n");

        dejitter("Esa");

        final String text = read("com/example/esa/package-info.java");
        assertThat(text, containsString("package com.example.esa;"));
        assertThat(text, containsString("package docs of the esa package"));
        assertThat(text, not(containsString("OnlyIf")));
    }

    @Test
    void removesMembersOfInactiveFlavours() throws Exception {
        write("com/example/Person.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Person {\n"
                + "  private String name;\n"
                + "  @OnlyIf(\"Esa\")\n"
                + "  private String esaOnly;\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  private String nasaOnly;\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  public Person(String nasaOnly) { }\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  public void nasaMethod() { }\n"
                + "  public void plainMethod() { }\n"
                + "}\n");

        final StripResult result = dejitter("Esa");

        final String text = read("com/example/Person.java");
        assertThat(text, containsString("private String name;"));
        assertThat(text, containsString("private String esaOnly;"));
        assertThat(text, not(containsString("nasaOnly")));
        assertThat(text, not(containsString("nasaMethod")));
        assertThat(text, containsString("public void plainMethod()"));
        assertThat(text, not(containsString("OnlyIf")));
        assertThat(result.getRemovedClasses().isEmpty(), is(true));
        // the class survives, the file keeps compiling shape
        assertThat(text, containsString("public class Person"));
    }

    @Test
    void removesTheWholePackageOfAnInactivePackageInfo() throws Exception {
        write("com/example/nasa/package-info.java",
                "@com.github.mictaege.jitter.api.OnlyIf(\"Nasa\")\n"
                + "package com.example.nasa;\n");
        write("com/example/nasa/NasaTool.java",
                "package com.example.nasa;\n"
                + "public class NasaTool { }\n");
        write("com/example/plain/PlainTool.java",
                "package com.example.plain;\n"
                + "public class PlainTool { }\n");

        final StripResult result = dejitter("Esa");

        assertThat(Files.exists(out.resolve("com/example/nasa/package-info.java")), is(false));
        assertThat(Files.exists(out.resolve("com/example/nasa/NasaTool.java")), is(false));
        assertThat(Files.exists(out.resolve("com/example/plain/PlainTool.java")), is(true));
        assertThat(result.getRemovedClasses(), is(Set.of("com/example/nasa/NasaTool")));
    }

    /**
     * The package gate of a package-info covers every file of the package,
     * no matter which source root it comes from: the generated sources of a
     * package often live in their own source root beside the hand written
     * package-info, and the bytecode stripping knows packages and not files.
     */
    @Test
    void removesThePackageOfAnInactivePackageInfoAcrossSourceRoots() throws Exception {
        final Path generated = workDir.resolve("generated");
        write("com/example/nasa/package-info.java",
                "@com.github.mictaege.jitter.api.OnlyIf(\"Nasa\")\n"
                + "package com.example.nasa;\n");
        write(generated, "com/example/nasa/NasaTool.java",
                "package com.example.nasa;\n"
                + "public class NasaTool { }\n");
        write(generated, "com/example/plain/PlainTool.java",
                "package com.example.plain;\n"
                + "public class PlainTool { }\n");

        final Dejitterer dejitterer = new Dejitterer("Esa", List.of());
        final StripResult result;
        try {
            result = dejitterer.dejitter(Set.of(src, generated), out);
        } finally {
            dejitterer.close();
        }

        assertThat(Files.exists(out.resolve("com/example/nasa/NasaTool.java")), is(false));
        assertThat(result.getRemovedClasses(), is(Set.of("com/example/nasa/NasaTool")));
        assertThat(result.getKeptSourceClasses(), is(Set.of("com/example/plain/PlainTool")));
    }

    /**
     * The package gate lives in the Java package-info and covers every
     * language of the package: the Kotlin files of the package carry no
     * annotations of their own and must leave the variant together with the
     * Java files. Their classes are recorded like the bytecode removals of
     * the whole package, and no kept source may remain for them.
     */
    @Test
    void removesKotlinFilesOfAnInactivePackageInfo() throws Exception {
        write("com/example/nasa/package-info.java",
                "@com.github.mictaege.jitter.api.OnlyIf(\"Nasa\")\n"
                + "package com.example.nasa;\n");
        write("com/example/nasa/NasaTool.java",
                "package com.example.nasa;\n"
                + "public class NasaTool { }\n");
        write("com/example/nasa/NasaTool.kt",
                "package com.example.nasa\n"
                + "\n"
                + "class NasaHelper\n");
        write("com/example/plain/PlainTool.java",
                "package com.example.plain;\n"
                + "public class PlainTool { }\n");

        final StripResult result = dejitter("Esa");

        assertThat(Files.exists(out.resolve("com/example/nasa/NasaTool.kt")), is(false));
        assertThat(result.getRemovedClasses(),
                is(Set.of("com/example/nasa/NasaTool", "com/example/nasa/NasaHelper")));
        assertThat(result.getKeptSourceClasses(), is(Set.of("com/example/plain/PlainTool")));
    }

    @Test
    void removesInactiveEnumConstants() throws Exception {
        write("com/example/Variant.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public enum Variant {\n"
                + "  FIRST,\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  SECOND,\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  THIRD,\n"
                + "  FOURTH,\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  LAST;\n"
                + "  public boolean any() { return true; }\n"
                + "}\n");

        dejitter("Esa");

        final String text = read("com/example/Variant.java");
        assertThat(text, containsString("FIRST,"));
        assertThat(text, containsString("FOURTH,"));
        assertThat(text, containsString("public boolean any()"));
        assertThat(text, not(containsString("SECOND")));
        assertThat(text, not(containsString("THIRD")));
        assertThat(text, not(containsString("LAST")));
    }

    @Test
    void forksTheMethodBodyOfTheActiveFlavour() throws Exception {
        write("com/example/Gate.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.Fork;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Gate {\n"
                + "  @Fork(to = \"flavouredEsa\", ifActive = \"Esa\")\n"
                + "  @Fork(to = \"flavouredNasa\", ifActive = \"Nasa\")\n"
                + "  public String flavour() {\n"
                + "    return \"none\";\n"
                + "  }\n"
                + "  @OnlyIf(\"Esa\")\n"
                + "  private String flavouredEsa() {\n"
                + "    return \"esa\";\n"
                + "  }\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  private String flavouredNasa() {\n"
                + "    return \"nasa\";\n"
                + "  }\n"
                + "}\n");

        final StripResult result = dejitter("Esa");

        final String text = read("com/example/Gate.java");
        assertThat(text, containsString("public String flavour() {"));
        assertThat(text, containsString("return \"esa\";"));
        assertThat(text, not(containsString("flavouredEsa")));
        assertThat(text, not(containsString("flavouredNasa")));
        assertThat(text, not(containsString("Fork")));
    }

    @Test
    void forksTheMethodBodyWhenTheTemplateIsDeclaredBeforeTheGateway() throws Exception {
        write("com/example/ReversedGate.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.Fork;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class ReversedGate {\n"
                + "  @OnlyIf(\"Esa\")\n"
                + "  private String flavouredEsa() {\n"
                + "    return \"esa\";\n"
                + "  }\n"
                + "  @Fork(to = \"flavouredEsa\", ifActive = \"Esa\")\n"
                + "  public String flavour() {\n"
                + "    return \"none\";\n"
                + "  }\n"
                + "}\n");

        final StripResult result = dejitter("Esa");

        assertThat(result.getErrors().isEmpty(), is(true));
        final String text = read("com/example/ReversedGate.java");
        assertThat(text, containsString("public String flavour() {"));
        assertThat(text, containsString("return \"esa\";"));
        assertThat(text, not(containsString("flavouredEsa")));
    }

    @Test
    void forkTakesOverTheTemplateThrowsClause() throws Exception {
        write("com/example/ThrowingGate.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.Fork;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class ThrowingGate {\n"
                + "  @Fork(to = \"flavouredEsa\", ifActive = \"Esa\")\n"
                + "  public void flavour() {\n"
                + "    throw new RuntimeException(\"none\");\n"
                + "  }\n"
                + "  @OnlyIf(\"Esa\")\n"
                + "  private void flavouredEsa() throws Exception {\n"
                + "    throw new Exception(\"esa\");\n"
                + "  }\n"
                + "}\n");

        dejitter("Esa");

        final String text = read("com/example/ThrowingGate.java");
        assertThat(text, containsString("public void flavour() throws Exception {"));
        assertThat(text, containsString("throw new Exception(\"esa\");"));
        assertThat(text, not(containsString("flavouredEsa")));
    }

    @Test
    void forkKeepsTheGatewayThrowsClauseWhenBothHaveOne() throws Exception {
        write("com/example/DualThrowsGate.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.Fork;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class DualThrowsGate {\n"
                + "  @Fork(to = \"flavouredEsa\", ifActive = \"Esa\")\n"
                + "  public void flavour() throws Exception {\n"
                + "    throw new Exception(\"none\");\n"
                + "  }\n"
                + "  @OnlyIf(\"Esa\")\n"
                + "  private void flavouredEsa() throws Exception {\n"
                + "    throw new Exception(\"esa\");\n"
                + "  }\n"
                + "}\n");

        dejitter("Esa");

        final String text = read("com/example/DualThrowsGate.java");
        // exactly one throws clause, not the template's clause duplicated
        assertThat(text.contains("throws Exception"), is(true));
        assertThat(text.replace("throws Exception", ""), not(containsString("throws")));
        assertThat(text, containsString("throw new Exception(\"esa\");"));
    }

    @Test
    void altersTheClassOfTheActiveFlavour() throws Exception {
        write("com/example/TableNames.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.Alter;\n"
                + "@Alter(ifActive = \"Esa\", with = \"EsaTableNames\")\n"
                + "public interface TableNames {\n"
                + "  String JOB = \"default\";\n"
                + "}\n");
        write("com/example/EsaTableNames.java",
                "package com.example;\n"
                + "public class EsaTableNames {\n"
                + "  public static final String JOB = \"esa\";\n"
                + "  public static String of(String key) { return EsaTableNames.JOB + key; }\n"
                + "}\n");

        final StripResult result = dejitter("Esa");

        assertThat(Files.exists(out.resolve("com/example/TableNames.java")), is(true));
        assertThat(Files.exists(out.resolve("com/example/EsaTableNames.java")), is(false));
        final String text = read("com/example/TableNames.java");
        assertThat(text, containsString("public class TableNames"));
        assertThat(text, containsString("\"esa\""));
        assertThat(text, containsString("TableNames.JOB + key"));
        assertThat(text, not(containsString("Alter")));
        assertThat(result.getInfos().stream().anyMatch(i -> i.contains("Replace class TableNames with EsaTableNames")), is(true));
    }

    @Test
    void altersWithAFullyQualifiedTemplateOfAnotherPackage() throws Exception {
        write("com/example/Default.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.Alter;\n"
                + "@Alter(ifActive = \"Esa\", with = \"com.example.nasa.NasaDefault\")\n"
                + "public class Default {\n"
                + "  public String value() { return \"none\"; }\n"
                + "}\n");
        write("com/example/nasa/NasaDefault.java",
                "package com.example.nasa;\n"
                + "public class NasaDefault {\n"
                + "  public String value() { return NasaDefault.class.getSimpleName() + \"!\"; }\n"
                + "}\n");

        final StripResult result = dejitter("Esa");

        assertThat(Files.exists(out.resolve("com/example/Default.java")), is(true));
        assertThat(Files.exists(out.resolve("com/example/nasa/NasaDefault.java")), is(false));
        final String text = read("com/example/Default.java");
        assertThat(text, containsString("package com.example;"));
        assertThat(text, containsString("public class Default"));
        assertThat(text, containsString("Default.class.getSimpleName()"));
        assertThat(result.getErrors().isEmpty(), is(true));
    }

    @Test
    void altersWithATemplateDeclaredInAnotherFileOfTheSamePackage() throws Exception {
        write("com/example/Variant.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.Alter;\n"
                + "@Alter(ifActive = \"Esa\", with = \"EsaVariant\")\n"
                + "public class Variant {\n"
                + "  public String value() { return \"none\"; }\n"
                + "}\n"
                + "/** the esa flavour variant, declared in the same file */\n"
                + "@com.github.mictaege.jitter.api.OnlyIf(\"Esa\")\n"
                + "final class EsaVariant {\n"
                + "  public String value() { return EsaVariant.class.getSimpleName() + \"!\"; }\n"
                + "}\n");

        final StripResult result = dejitter("Esa");

        assertThat(Files.exists(out.resolve("com/example/Variant.java")), is(true));
        final String text = read("com/example/Variant.java");
        assertThat(text, containsString("public final class Variant"));
        assertThat(text, containsString("Variant.class.getSimpleName()"));
        assertThat(text, not(containsString("EsaVariant")));
        assertThat(result.getErrors().isEmpty(), is(true));
    }

    @Test
    void altersWithATemplateOfAnotherSourceSet() throws Exception {
        write("com/example/TableNames.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.Alter;\n"
                + "@Alter(ifActive = \"Esa\", with = \"com.example.ext.EsaTableNames\")\n"
                + "public interface TableNames {\n"
                + "  String JOB = \"none\";\n"
                + "}\n");
        final Path extSrc = workDir.resolve("ext-src");
        final Path extClasses = workDir.resolve("ext-classes");
        final Path templateFile = extSrc.resolve("com/example/ext/EsaTableNames.java");
        Files.createDirectories(templateFile.getParent());
        Files.writeString(templateFile,
                "package com.example.ext;\n"
                + "public class EsaTableNames {\n"
                + "  public static final String JOB = \"esa\";\n"
                + "  public static String of(String key) { return EsaTableNames.JOB + key; }\n"
                + "}\n", StandardCharsets.UTF_8);
        Files.createDirectories(extClasses.resolve("com/example/ext"));
        Files.write(extClasses.resolve("com/example/ext/EsaTableNames.class"),
                new byte[]{1});

        final StripResult result = new Dejitterer("Esa", List.of(),
                Map.of(extClasses.toFile(), List.of(extSrc.toFile())))
                .dejitter(Set.of(src), out);

        assertThat(Files.exists(out.resolve("com/example/TableNames.java")), is(true));
        final String text = read("com/example/TableNames.java");
        assertThat(text, containsString("package com.example;"));
        assertThat(text, containsString("public class TableNames"));
        assertThat(text, containsString("\"esa\""));
        assertThat(text, containsString("TableNames.JOB"));
        assertThat(text, not(containsString("Alter")));
        // the template stays in its own source set - like the bytecode
        // stripping, which keeps classpath templates where they are
        assertThat(Files.exists(templateFile), is(true));
        assertThat(result.getErrors().isEmpty(), is(true));
    }

    @Test
    void resolvesFlavourConstantsFromTheCompileClasspath() throws Exception {
        write("com/example/ConstantsGate.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class ConstantsGate {\n"
                + "  @OnlyIf(TestFlavours.ESA)\n"
                + "  public String esaOnly() { return \"esa\"; }\n"
                + "  @OnlyIf(TestFlavours.NASA)\n"
                + "  public String nasaOnly() { return \"nasa\"; }\n"
                + "}\n");

        final StripResult result = dejitter("Esa", testClasspath());

        final String text = read("com/example/ConstantsGate.java");
        assertThat(text, containsString("esaOnly"));
        assertThat(text, not(containsString("nasaOnly")));
        assertThat(result.getErrors().isEmpty(), is(true));
    }

    @Test
    void prunesDanglingImportsOfRemovedClasses() throws Exception {
        write("com/example/User.java",
                "package com.example;\n"
                + "import com.example.nasa.NasaTool;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class User {\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  public NasaTool nasaTool() { return null; }\n"
                + "  public String plain() { return \"plain\"; }\n"
                + "}\n");

        dejitter("Esa");

        final String text = read("com/example/User.java");
        assertThat(text, not(containsString("NasaTool")));
        assertThat(text, not(containsString("import com.example.nasa.NasaTool;")));
        assertThat(text, containsString("public String plain()"));
    }

    @Test
    void keepsEverythingWhenNoFlavourIsActive() throws Exception {
        write("com/example/Gates.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "import com.github.mictaege.jitter.api.Fork;\n"
                + "public class Gates {\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  public String nasaOnly() { return \"nasa\"; }\n"
                + "  @Fork(to = \"flavouredNasa\", ifActive = \"Nasa\")\n"
                + "  public String flavour() { return \"none\"; }\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  private String flavouredNasa() { return \"nasa\"; }\n"
                + "}\n");

        final StripResult result = dejitter("");

        final String text = read("com/example/Gates.java");
        assertThat(text, containsString("nasaOnly"));
        assertThat(text, containsString("flavouredNasa"));
        assertThat(text, not(containsString("OnlyIf")));
        assertThat(text, not(containsString("Fork")));
        assertThat(result.getRemovedClasses().isEmpty(), is(true));
    }

    @Test
    void copiesNonJavaSourcesVerbatim() throws Exception {
        write("com/example/Tool.java",
                "package com.example;\n"
                + "public class Tool { }\n");
        write("com/example/Helper.kt",
                "package com.example\n"
                + "class Helper\n");

        dejitter("Esa");

        assertThat(read("com/example/Helper.kt"), is("package com.example\nclass Helper\n"));
        assertThat(Files.exists(out.resolve("com/example/Tool.java")), is(true));
    }

    @Test
    void reportsAMissingForkTemplate() throws Exception {
        write("com/example/BrokenGate.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.Fork;\n"
                + "public class BrokenGate {\n"
                + "  @Fork(to = \"missingTemplate\", ifActive = \"Esa\")\n"
                + "  public String flavour() { return \"none\"; }\n"
                + "}\n");

        final StripResult result = dejitter("Esa");

        assertThat(result.getErrors().isEmpty(), is(false));
        assertThat(result.getErrors().get(0), containsString("missingTemplate"));
    }

    @Test
    void reportsAMissingAlterTemplate() throws Exception {
        write("com/example/BrokenTarget.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.Alter;\n"
                + "@Alter(ifActive = \"Esa\", with = \"MissingTemplate\")\n"
                + "public class BrokenTarget { }\n");

        final StripResult result = dejitter("Esa");

        assertThat(result.getErrors().isEmpty(), is(false));
        assertThat(result.getErrors().get(0), containsString("MissingTemplate"));
    }

    @Test
    void reportsMissingTemplatesOfOtherSourceSets() throws Exception {
        write("com/example/CrossModuleTarget.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.Alter;\n"
                + "@Alter(ifActive = \"Esa\", with = \"com.other.EsaTemplate\")\n"
                + "public class CrossModuleTarget { }\n");

        final StripResult result = dejitter("Esa");

        assertThat(result.getErrors().isEmpty(), is(false));
        assertThat(result.getErrors().get(0), containsString("alternative classes of other source sets"));
    }

    @Test
    void flattensAlterConstValuesToTheActiveFlavour() throws Exception {
        write("com/example/TableNames.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.AlterConstValue;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "public class TableNames {\n"
                + "  @AlterConstValue(ifActive = \"Esa\", with = \"ESA_JOB\")\n"
                + "  @AlterConstValue(ifActive = \"Nasa\", with = \"NASA_JOB\")\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final String JOB = \"com.example.TableNames.JOB\";\n"
                + "  @AlterConstValue(ifActive = {\"Esa\", \"Nasa\"}, with = \"42\")\n"
                + "  public static final Integer LIMIT = 4;\n"
                + "}\n");

        final StripResult result = dejitter("Esa");

        final String text = read("com/example/TableNames.java");
        assertThat(text, containsString("public static final String JOB = \"ESA_JOB\";"));
        assertThat(text, containsString("public static final Integer LIMIT = 42;"));
        assertThat(text, not(containsString("AlterConstValue")));
        assertThat(text, not(containsString("NASA_JOB")));
        assertThat(result.getErrors().isEmpty(), is(true));
    }

    @Test
    void keepsAlterConstValuesOfTheFullModelUntouched() throws Exception {
        write("com/example/TableNames.java",
                "package com.example;\n"
                + "import com.github.mictaege.jitter.api.AlterConstValue;\n"
                + "public class TableNames {\n"
                + "  @AlterConstValue(ifActive = \"Esa\", with = \"ESA_JOB\")\n"
                + "  public static final String JOB = \"com.example.TableNames.JOB\";\n"
                + "}\n");

        final StripResult result = dejitter("");

        final String text = read("com/example/TableNames.java");
        assertThat(text, containsString("\"com.example.TableNames.JOB\""));
        assertThat(text, not(containsString("AlterConstValue")));
        assertThat(result.getErrors().isEmpty(), is(true));
    }

    // ------------------------------------------------------------ fixture

    private StripResult dejitter(final String flavour) throws IOException {
        return dejitter(flavour, List.of());
    }

    private StripResult dejitter(final String flavour, final List<File> classpath)
            throws IOException {
        return new Dejitterer(flavour, classpath).dejitter(Set.of(src), out);
    }

    private List<File> testClasspath() {
        return List.of(new File(getClass().getProtectionDomain().getCodeSource().getLocation().getFile()));
    }

    private void write(final String relative, final String content) throws IOException {
        write(src, relative, content);
    }

    private void write(final Path base, final String relative, final String content) throws IOException {
        final Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String read(final String relative) throws IOException {
        return Files.readString(out.resolve(relative), StandardCharsets.UTF_8);
    }

}
