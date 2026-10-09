package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class BytecodeStripperTest {

    private static final String FLAVOUR_PROP = "jitter.active.flavour";

    @TempDir
    Path workDir;

    @BeforeEach
    void selectEsaFlavour() {
        System.setProperty(FLAVOUR_PROP, "ESA");
    }

    @AfterEach
    void resetFlavour() {
        System.clearProperty(FLAVOUR_PROP);
    }

    @Test
    void removesMembersOfInactiveFlavours() throws Exception {
        final Path classes = compile("test/Person.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Person {\n"
                        + "  private String name;\n"
                        + "  @OnlyIf(\"ESA\")\n"
                        + "  private String esaOnly;\n"
                        + "  @OnlyIf({\"ESA\", \"NASA\"})\n"
                        + "  private String esaOrNasa;\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  private String nasaOnly;\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public void nasaConstructor(int x) { }\n"
                        + "  public void plainMethod() { }\n"
                        + "}\n");

        new BytecodeStripper().strip(classes);

        final ClassNode person = read(classes, "test/Person");
        assertThat(field(person, "esaOnly"), is(not(nullValue())));
        assertThat(field(person, "esaOrNasa"), is(not(nullValue())));
        assertThat(field(person, "nasaOnly"), is(nullValue()));
        assertThat(method(person, "nasaConstructor", "(I)V"), is(nullValue()));
        assertThat(method(person, "plainMethod", "()V"), is(not(nullValue())));
    }

    @Test
    void removesClassesOfInactiveFlavours() throws Exception {
        final Path classes = compile(
                "test/NasaOnly.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "@OnlyIf(\"NASA\")\n"
                        + "public class NasaOnly { }\n",
                "test/Plain.java",
                "package test;\n"
                        + "public class Plain { }\n");

        new BytecodeStripper().strip(classes);

        assertThat(Files.exists(classes.resolve("test/NasaOnly.class")), is(false));
        assertThat(Files.exists(classes.resolve("test/Plain.class")), is(true));
    }

    @Test
    void removesWholePackagesOfInactiveFlavours() throws Exception {
        final Path classes = compile(
                "special/package-info.java",
                "@OnlyIf(\"NASA\")\n"
                        + "package special;\n\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n",
                "special/Special.java",
                "package special;\n"
                        + "public class Special { }\n",
                "test/Plain.java",
                "package test;\n"
                        + "public class Plain { }\n");

        new BytecodeStripper().strip(classes);

        assertThat(Files.exists(classes.resolve("special/Special.class")), is(false));
        assertThat(Files.exists(classes.resolve("special/package-info.class")), is(false));
        assertThat(Files.exists(classes.resolve("test/Plain.class")), is(true));
    }

    @Test
    void keepsNestedPackagesOfRemovedPackages() throws Exception {
        final Path classes = compile(
                "special/package-info.java",
                "@OnlyIf(\"NASA\")\n"
                        + "package special;\n\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n",
                "special/Special.java",
                "package special;\n"
                        + "public class Special { }\n",
                "special/sub/package-info.java",
                "@Deprecated\n"
                        + "package special.sub;\n",
                "special/sub/Sub.java",
                "package special.sub;\n"
                        + "public class Sub { }\n");

        new BytecodeStripper().strip(classes);

        assertThat(Files.exists(classes.resolve("special/Special.class")), is(false));
        assertThat(Files.exists(classes.resolve("special/package-info.class")), is(false));
        assertThat(Files.exists(classes.resolve("special/sub/Sub.class")), is(true));
        assertThat(Files.exists(classes.resolve("special/sub/package-info.class")), is(true));
    }

    @Test
    void replacesMethodBodyByForkTemplate() throws Exception {
        final Path classes = compile("test/Forker.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Fork;\n"
                        + "public class Forker {\n"
                        + "  @Fork(ifActive = \"ESA\", to = \"initEsa\")\n"
                        + "  public String init() { return \"default\"; }\n"
                        + "  private String initEsa() { return \"esa\"; }\n"
                        + "}\n");

        new BytecodeStripper().strip(classes);

        final ClassNode forker = read(classes, "test/Forker");
        assertThat(method(forker, "init", "()Ljava/lang/String;"), is(not(nullValue())));
        assertThat(method(forker, "initEsa", "()Ljava/lang/String;"), is(nullValue()));
        assertThat(bytecode(method(forker, "init", "()Ljava/lang/String;")).contains("esa"), is(true));
        assertThat(bytecode(method(forker, "init", "()Ljava/lang/String;")).contains("default"), is(false));
    }

    @Test
    void keepsOriginalMethodIfNoForkMatches() throws Exception {
        final Path classes = compile("test/Forker.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Fork;\n"
                        + "public class Forker {\n"
                        + "  @Fork(ifActive = \"NASA\", to = \"initNasa\")\n"
                        + "  public String init() { return \"default\"; }\n"
                        + "  private String initNasa() { return \"nasa\"; }\n"
                        + "}\n");

        new BytecodeStripper().strip(classes);

        final ClassNode forker = read(classes, "test/Forker");
        assertThat(method(forker, "initNasa", "()Ljava/lang/String;"), is(not(nullValue())));
        assertThat(bytecode(method(forker, "init", "()Ljava/lang/String;")).contains("default"), is(true));
    }

    @Test
    void replacesClassByAlterTemplateOfSamePackage() throws Exception {
        final Path classes = compile("test/Worker.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Alter;\n"
                        + "@Alter(ifActive = \"ESA\", with = \"WorkerEsa\")\n"
                        + "public class Worker {\n"
                        + "  public String work() { throw new IllegalStateException(); }\n"
                        + "}\n"
                        + "class WorkerEsa {\n"
                        + "  public String work() { return \"esa work\"; }\n"
                        + "}\n");

        new BytecodeStripper().strip(classes);

        assertThat(Files.exists(classes.resolve("test/Worker.class")), is(true));
        assertThat(Files.exists(classes.resolve("test/WorkerEsa.class")), is(false));
        final ClassNode worker = read(classes, "test/Worker");
        assertThat((worker.access & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0, is(true));
        assertThat(method(worker, "work", "()Ljava/lang/String;"), is(not(nullValue())));
        assertThat(bytecode(method(worker, "work", "()Ljava/lang/String;")).contains("esa work"), is(true));
    }

    @Test
    void replacesClassByAlterTemplateOfOtherPackage() throws Exception {
        final Path classes = compile(
                "test/Target.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Alter;\n"
                        + "@Alter(ifActive = \"ESA\", with = \"other.EsaWorker\")\n"
                        + "public class Target {\n"
                        + "  public String work() { throw new IllegalStateException(); }\n"
                        + "}\n",
                "other/EsaWorker.java",
                "package other;\n"
                        + "public class EsaWorker {\n"
                        + "  public String esaMarker;\n"
                        + "  public EsaWorker self() { return this; }\n"
                        + "  public String work() { return \"esa work\"; }\n"
                        + "}\n");

        new BytecodeStripper().strip(classes);

        assertThat(Files.exists(classes.resolve("other/EsaWorker.class")), is(false));
        final ClassNode target = read(classes, "test/Target");
        assertThat((target.access & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0, is(true));
        assertThat(method(target, "self", "()Lother/EsaWorker;"), is(nullValue()));
        final MethodNode self = method(target, "self", "()Ltest/Target;");
        assertThat(self, is(not(nullValue())));
        assertThat(field(target, "esaMarker"), is(not(nullValue())));
    }

    @Test
    void removesJitterAnnotationsFromSurvivingClasses() throws Exception {
        final Path classes = compile("test/Person.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Person {\n"
                        + "  @OnlyIf(\"ESA\")\n"
                        + "  private String esaOnly;\n"
                        + "}\n");

        new BytecodeStripper().strip(classes);

        final ClassNode person = read(classes, "test/Person");
        final boolean classAnnotated = person.visibleAnnotations != null
                && person.visibleAnnotations.stream().anyMatch(a -> a.desc.contains("jitter/api"));
        final boolean fieldAnnotated = person.fields.stream()
                .anyMatch(f -> f.visibleAnnotations != null
                        && f.visibleAnnotations.stream().anyMatch(a -> a.desc.contains("jitter/api")));
        assertThat(classAnnotated || fieldAnnotated, is(false));
    }

    @Test
    void keepsEverythingIfNoFlavourIsSelected() throws Exception {
        System.clearProperty(FLAVOUR_PROP);
        final Path classes = compile("test/Person.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Person {\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  private String nasaOnly;\n"
                        + "  @OnlyIf(\"ESA\")\n"
                        + "  private String esaOnly;\n"
                        + "}\n");

        new BytecodeStripper().strip(classes);

        final ClassNode person = read(classes, "test/Person");
        assertThat(field(person, "nasaOnly"), is(not(nullValue())));
        assertThat(field(person, "esaOnly"), is(not(nullValue())));
    }

    @Test
    void verifierReportsDanglingReferences() throws Exception {
        final Path classes = compile(
                "removed/NasaHelper.java",
                "package removed;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "@OnlyIf(\"NASA\")\n"
                        + "public class NasaHelper {\n"
                        + "  public static String help() { return \"help\"; }\n"
                        + "}\n",
                "test/Keeper.java",
                "package test;\n"
                        + "import removed.NasaHelper;\n"
                        + "public class Keeper {\n"
                        + "  public String call() { return NasaHelper.help(); }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);
        assertThat(result.getRemovedClasses().contains("removed/NasaHelper"), is(true));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        final List<String> violations =
                ReferenceVerifier.findDanglingReferences(dirs, result.getRemovedClasses(), result.getRemovedMembers());
        assertThat(violations.size(), is(1));
        assertThat(violations.get(0).contains("test.Keeper"), is(true));
        assertThat(violations.get(0).contains("removed.NasaHelper"), is(true));
    }

    @Test
    void removesEnumConstantsOfInactiveFlavours() throws Exception {
        final Path classes = compile("test/Bearer.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public enum Bearer {\n"
                        + "  @OnlyIf(\"ESA\")\n"
                        + "  HERMES(\"hermes\"),\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  MERCURY(\"mercury\"),\n"
                        + "  COMMON(\"common\");\n"
                        + "  private final String name;\n"
                        + "  Bearer(final String name) { this.name = name; }\n"
                        + "  public String getName() { return name; }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        final ClassNode bearer = read(classes, "test/Bearer");
        assertThat(field(bearer, "MERCURY"), is(nullValue()));
        assertThat(field(bearer, "HERMES"), is(not(nullValue())));
        assertThat(field(bearer, "COMMON"), is(not(nullValue())));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));

        final Class<?> bearerClass = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, null).loadClass("test.Bearer");
        final Object[] constants = (Object[]) bearerClass.getMethod("values").invoke(null);
        assertThat(constants.length, is(2));
        assertThat(((Enum<?>) constants[0]).name(), is("HERMES"));
        assertThat(((Enum<?>) constants[0]).ordinal(), is(0));
        assertThat(((Enum<?>) constants[1]).name(), is("COMMON"));
        assertThat(((Enum<?>) constants[1]).ordinal(), is(1));
    }

    @Test
    void removesBuilderChainInitializersOfRemovedStaticFields() throws Exception {
        final Path classes = compile("test/Maps.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Maps {\n"
                        + "  enum Dest { MS, NOV }\n"
                        + "  @OnlyIf(\"ESA\")\n"
                        + "  static final String ACTIVE = new StringBuilder()\n"
                        + "      .append(Dest.MS.toString()).toString();\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  static final String INACTIVE = new StringBuilder()\n"
                        + "      .append(Dest.MS.toString()).append(Dest.NOV.toString()).toString();\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        final ClassNode maps = read(classes, "test/Maps");
        assertThat(field(maps, "ACTIVE"), is(not(nullValue())));
        assertThat(field(maps, "INACTIVE"), is(nullValue()));
        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));
    }

    @Test
    void resolvesAltClassTemplatesFromTheClasspath() throws Exception {
        final Path templateClasses = compile("lib/Template.java",
                "package lib;\n"
                        + "public class Template {\n"
                        + "  public String name() { return \"template\"; }\n"
                        + "}\n");
        final Path classes = compile("test/Target.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Alter;\n"
                        + "@Alter(with = \"lib.Template\", ifActive = \"ESA\")\n"
                        + "public class Target {\n"
                        + "  public String name() { throw new UnsupportedOperationException(); }\n"
                        + "}\n");

        new BytecodeStripper().strip(classes, java.util.Collections.singletonList(templateClasses.toFile()));

        final ClassNode target = read(classes, "test/Target");
        final Optional<org.objectweb.asm.tree.MethodNode> name = target.methods.stream()
                .filter(m -> m.name.equals("name")).findFirst();
        assertThat(name.isPresent(), is(true));
        assertThat(bytecode(name.get()).contains("template"), is(true));
    }

    @Test
    void removesLambdaBodiesOfRemovedMethods() throws Exception {
        final Path classes = compile("test/LambdaHolder.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "import java.util.function.Supplier;\n"
                        + "public class LambdaHolder {\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  private String nasaSecret = \"secret\";\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public String nasaOnly() {\n"
                        + "    Supplier<String> s = () -> nasaSecret;\n"
                        + "    return s.get();\n"
                        + "  }\n"
                        + "  public String plain() { return \"plain\"; }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        final ClassNode holder = read(classes, "test/LambdaHolder");
        assertThat(field(holder, "nasaSecret"), is(nullValue()));
        assertThat(method(holder, "nasaOnly", "()Ljava/lang/String;"), is(nullValue()));
        assertThat(holder.methods.stream().filter(m -> m.name.startsWith("lambda$")).count(), is(0L));
        assertThat(method(holder, "plain", "()Ljava/lang/String;"), is(not(nullValue())));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));
    }

    @Test
    void removesSwitchMapEntriesOfRemovedForkTemplates() throws Exception {
        final Path classes = compile(
                "test/Direction.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public enum Direction {\n"
                        + "  UP, DOWN,\n"
                        + "  @OnlyIf(\"ESA\") ESA_ONLY,\n"
                        + "  @OnlyIf(\"NASA\") NASA_ONLY\n"
                        + "}\n",
                "test/Navigator.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Fork;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Navigator {\n"
                        + "  @Fork(ifActive = \"ESA\", to = \"navEsa\")\n"
                        + "  @Fork(ifActive = \"NASA\", to = \"navNasa\")\n"
                        + "  public String nav(final Direction d) { throw new UnsupportedOperationException(); }\n"
                        + "  @OnlyIf(\"ESA\")\n"
                        + "  private String navEsa(final Direction d) {\n"
                        + "    switch (d) { case UP: return \"up\"; case ESA_ONLY: return \"esa\"; default: return \"?\"; }\n"
                        + "  }\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  private String navNasa(final Direction d) {\n"
                        + "    switch (d) { case DOWN: return \"down\"; case NASA_ONLY: return \"nasa\"; default: return \"?\"; }\n"
                        + "  }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        final ClassNode navigator = read(classes, "test/Navigator");
        assertThat(method(navigator, "nav", "(Ltest/Direction;)Ljava/lang/String;"), is(not(nullValue())));
        assertThat(method(navigator, "navEsa", "(Ltest/Direction;)Ljava/lang/String;"), is(nullValue()));
        assertThat(method(navigator, "navNasa", "(Ltest/Direction;)Ljava/lang/String;"), is(nullValue()));

        // javac generates one shared switch map class for both switches. It
        // survives, because the forked method body still uses it, but the
        // entries of the removed constants have to be gone.
        final ClassNode switchMap = read(classes, "test/Navigator$1");
        assertThat(switchMap.fields.stream()
                .anyMatch(f -> f.name.startsWith("$SwitchMap$")), is(true));
        final MethodNode clinit = method(switchMap, "<clinit>", "()V");
        assertThat("no strip errors expected: " + result.getErrors(),
                result.getErrors().isEmpty(), is(true));
        assertThat(bytecode(clinit).contains("NASA_ONLY"), is(false));
        assertThat(bytecode(clinit).contains("UP"), is(true));
        assertThat(clinit.tryCatchBlocks.size(), is(3));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));
    }

    @Test
    void removesOrphanedAnonymousClassesOfRemovedMethods() throws Exception {
        final Path classes = compile("test/Runner.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Runner {\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public Runnable nasaOnly() {\n"
                        + "    return new Runnable() { public void run() { } };\n"
                        + "  }\n"
                        + "  public Runnable plain() {\n"
                        + "    return new Runnable() { public void run() { } };\n"
                        + "  }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        final ClassNode runner = read(classes, "test/Runner");
        assertThat(method(runner, "nasaOnly", "()Ljava/lang/Runnable;"), is(nullValue()));
        assertThat(method(runner, "plain", "()Ljava/lang/Runnable;"), is(not(nullValue())));

        final List<String> anonymous = new java.util.ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            final String name = "test/Runner$" + i;
            if (Files.exists(classes.resolve(name + ".class"))) {
                anonymous.add(name);
            }
        }
        assertThat("only the anonymous class of the surviving method survives",
                anonymous.size(), is(1));
        assertThat(bytecode(method(runner, "plain", "()Ljava/lang/Runnable;"))
                .contains(anonymous.get(0)), is(true));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));
    }

    @Test
    void removesNestedClassesOfRemovedClasses() throws Exception {
        final Path classes = compile(
                "test/Outer.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "@OnlyIf(\"NASA\")\n"
                        + "public class Outer {\n"
                        + "  public static class Inner {\n"
                        + "    public static class DeepInner { }\n"
                        + "  }\n"
                        + "  public static class Other { }\n"
                        + "}\n",
                "test/OuterLookalike.java",
                "package test;\n"
                        + "public class OuterLookalike { }\n");

        new BytecodeStripper().strip(classes);

        assertThat(Files.exists(classes.resolve("test/Outer.class")), is(false));
        assertThat(Files.exists(classes.resolve("test/Outer$Inner.class")), is(false));
        assertThat(Files.exists(classes.resolve("test/Outer$Inner$DeepInner.class")), is(false));
        assertThat(Files.exists(classes.resolve("test/Outer$Other.class")), is(false));
        assertThat(Files.exists(classes.resolve("test/OuterLookalike.class")), is(true));
    }

    private Path compile(final String... nameAndSourcePairs) throws IOException {
        if (nameAndSourcePairs.length % 2 != 0) {
            throw new IllegalArgumentException("expected name/source pairs");
        }
        final Path sources = Files.createDirectories(workDir.resolve("sources"));
        final Path classes = Files.createDirectories(workDir.resolve("classes"));
        for (int i = 0; i < nameAndSourcePairs.length; i += 2) {
            final Path file = sources.resolve(nameAndSourcePairs[i]);
            Files.createDirectories(file.getParent());
            Files.write(file, nameAndSourcePairs[i + 1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final List<String> javaFiles;
        try (final Stream<Path> files = Files.walk(sources)) {
            javaFiles = files.filter(f -> f.toString().endsWith(".java"))
                    .map(Path::toString)
                    .collect(Collectors.toList());
        }
        final List<String> options = new java.util.ArrayList<>();
        options.add("-cp");
        options.add(System.getProperty("java.class.path"));
        options.add("-d");
        options.add(classes.toString());
        options.addAll(javaFiles);
        final int result = compiler.run(null, null, null, options.toArray(new String[0]));
        assertThat("fixture compilation failed", result, is(0));
        return classes;
    }

    private ClassNode read(final Path classesDir, final String internalName) throws IOException {
        final Path file = classesDir.resolve(internalName + ".class");
        assertThat("class file " + internalName + " does not exist", Files.exists(file), is(true));
        final ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(file)).accept(node, 0);
        return node;
    }

    private org.objectweb.asm.tree.FieldNode field(final ClassNode node, final String name) {
        final Optional<org.objectweb.asm.tree.FieldNode> field =
                node.fields.stream().filter(f -> f.name.equals(name)).findFirst();
        return field.orElse(null);
    }

    private MethodNode method(final ClassNode node, final String name, final String desc) {
        final Optional<MethodNode> method = node.methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst();
        return method.orElse(null);
    }

    private String bytecode(final MethodNode method) {
        if (method == null || method.instructions == null) {
            return "";
        }
        final StringBuilder builder = new StringBuilder();
        method.instructions.forEach(insn -> {
            if (insn instanceof org.objectweb.asm.tree.LdcInsnNode) {
                builder.append(((org.objectweb.asm.tree.LdcInsnNode) insn).cst).append('\n');
            } else if (insn instanceof org.objectweb.asm.tree.TypeInsnNode) {
                builder.append(((org.objectweb.asm.tree.TypeInsnNode) insn).desc).append('\n');
            } else if (insn instanceof org.objectweb.asm.tree.MethodInsnNode) {
                builder.append(((org.objectweb.asm.tree.MethodInsnNode) insn).owner)
                        .append('#')
                        .append(((org.objectweb.asm.tree.MethodInsnNode) insn).name)
                        .append('\n');
            } else if (insn instanceof org.objectweb.asm.tree.FieldInsnNode) {
                builder.append(((org.objectweb.asm.tree.FieldInsnNode) insn).owner)
                        .append('#')
                        .append(((org.objectweb.asm.tree.FieldInsnNode) insn).name)
                        .append('\n');
            }
        });
        return builder.toString();
    }

}
