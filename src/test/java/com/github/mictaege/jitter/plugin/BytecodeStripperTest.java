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
    void removesArrayInitializersOfRemovedInstanceFields() throws Exception {
        final Path classes = compile("test/ArrayInitHolder.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class ArrayInitHolder {\n"
                        + "  enum F { A, B, C }\n"
                        + "  private final String[] keeper = new String[]{F.A.name()};\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  private final String[] nasa = new String[]{F.B.name(), \"x\", F.C.name()};\n"
                        + "  public String first() { return keeper[0]; }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        final ClassNode holder = read(classes, "test/ArrayInitHolder");
        assertThat(field(holder, "keeper"), is(not(nullValue())));
        assertThat(field(holder, "nasa"), is(nullValue()));
        final MethodNode init = method(holder, "<init>", "()V");
        assertThat(bytecode(init).contains("nasa"), is(false));
        assertThat(bytecode(init).contains("keeper"), is(true));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));

        final Class<?> holderClass = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, null).loadClass("test.ArrayInitHolder");
        final Object plain = holderClass.getMethod("first").invoke(holderClass.getDeclaredConstructor().newInstance());
        assertThat(plain, is("A"));
    }

    @Test
    void removesLambdaInitializersOfRemovedFields() throws Exception {
        final Path classes = compile("test/LambdaInitHolder.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "import java.util.function.Supplier;\n"
                        + "public class LambdaInitHolder {\n"
                        + "  private final Supplier<String> keeper = () -> \"esa\";\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  private final Fixer nasa = new Fixer(dao()::get, () -> NasaOnly.stamp(\"nasa\"));\n"
                        + "  public String kept() { return keeper.get(); }\n"
                        + "  private Supplier<String> dao() { return () -> \"dao\"; }\n"
                        + "  static class Fixer {\n"
                        + "    final Supplier<String> bound;\n"
                        + "    final Supplier<String> value;\n"
                        + "    Fixer(final Supplier<String> bound, final Supplier<String> value) {\n"
                        + "      this.bound = bound;\n"
                        + "      this.value = value;\n"
                        + "    }\n"
                        + "  }\n"
                        + "}\n",
                "test/NasaOnly.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "@OnlyIf(\"NASA\")\n"
                        + "public class NasaOnly {\n"
                        + "  public static String stamp(final String s) { return s; }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        final ClassNode holder = read(classes, "test/LambdaInitHolder");
        assertThat(field(holder, "keeper"), is(not(nullValue())));
        assertThat(field(holder, "nasa"), is(nullValue()));
        final MethodNode init = method(holder, "<init>", "()V");
        assertThat(bytecode(init).contains("nasa"), is(false));
        assertThat(bytecode(init).contains("keeper"), is(true));
        // the lambdas of the removed initializer are gone (they referenced
        // the removed NasaOnly class), the one of the surviving field stays
        assertThat(holder.methods.stream()
                .filter(m -> m.name.startsWith("lambda$new$")).count(), is(1L));
        assertThat(holder.methods.stream()
                .anyMatch(m -> bytecode(m).contains("NasaOnly")), is(false));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));

        final Class<?> holderClass = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, null).loadClass("test.LambdaInitHolder");
        final Object plain = holderClass.getMethod("kept").invoke(holderClass.getDeclaredConstructor().newInstance());
        assertThat(plain, is("esa"));
    }

    @Test
    void forksOverloadedGatewaysToTheirSignatureMatchingTemplates() throws Exception {
        final Path classes = compile("test/OverloadForkHolder.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Fork;\n"
                        + "public class OverloadForkHolder {\n"
                        + "  @Fork(to = \"template\", ifActive = \"ESA\")\n"
                        + "  public String value(final String s) { return \"gw-string\"; }\n"
                        + "  @Fork(to = \"template\", ifActive = \"ESA\")\n"
                        + "  public String value(final int i) { return \"gw-int\"; }\n"
                        + "  public String template(final String s) { return \"template-string\"; }\n"
                        + "  public String template(final int i) { return \"template-int\"; }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        // each overloaded gateway gets the body of the template with its own
        // signature, not simply the first template of the same name
        final ClassNode holder = read(classes, "test/OverloadForkHolder");
        assertThat(bytecode(method(holder, "value", "(Ljava/lang/String;)Ljava/lang/String;"))
                .contains("template-string"), is(true));
        assertThat(bytecode(method(holder, "value", "(I)Ljava/lang/String;"))
                .contains("template-int"), is(true));
        assertThat(holder.methods.stream()
                .filter(m -> m.name.equals("template")).count(), is(0L));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));

        final Class<?> holderClass = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, null).loadClass("test.OverloadForkHolder");
        final Object plain = holderClass.getDeclaredConstructor().newInstance();
        assertThat(holderClass.getMethod("value", String.class).invoke(plain, "x"), is("template-string"));
        assertThat(holderClass.getMethod("value", int.class).invoke(plain, 1), is("template-int"));
    }

    @Test
    void cleansNestAttributesOfRemovedNestedClasses() throws Exception {
        final Path classes = compile("test/NestHolder.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class NestHolder {\n"
                        + "  public Runnable keeper() { return new Runnable() {\n"
                        + "      public void run() { }\n"
                        + "    }; }\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public Runnable gated() { return new Runnable() {\n"
                        + "      public void run() { }\n"
                        + "    }; }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        // the anonymous class of the removed method is gone, and the
        // surviving outer class no longer names it in its nest attributes
        // (stale entries break bytecode retransformation, e.g. mocking)
        assertThat(java.nio.file.Files.exists(classes.resolve("test/NestHolder$2.class")),
                is(false));
        final ClassNode holder = read(classes, "test/NestHolder");
        assertThat(holder.nestMembers.contains("test/NestHolder$2"), is(false));
        assertThat(holder.innerClasses.stream()
                .anyMatch(inner -> inner.name.equals("test/NestHolder$2")), is(false));
        assertThat(holder.nestMembers.contains("test/NestHolder$1"), is(true));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));

        final Class<?> holderClass = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, null).loadClass("test.NestHolder");
        final Object plain = holderClass.getDeclaredConstructor().newInstance();
        assertThat(holderClass.getMethod("keeper").invoke(plain), is(not(nullValue())));
    }

    @Test
    void rejectsInstanceForkBodyReadingThisForStaticGateway() throws Exception {
        final Path classes = compile("test/StaticForkHolder.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Fork;\n"
                        + "public class StaticForkHolder {\n"
                        + "  private final String field = System.getProperty(\"dbgf\", \"field\");\n"
                        + "  @Fork(to = \"template\", ifActive = \"ESA\")\n"
                        + "  public static String value() { throw new UnsupportedOperationException(); }\n"
                        + "  private String template() { return field; }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        // an instance body reading this has no counterpart in a static
        // gateway - the stripper must reject it with a clear error
        assertThat(result.getErrors().isEmpty(), is(false));
        assertThat(result.getErrors().get(0), is("The gateway method test.StaticForkHolder#value"
                + " and the alternative method template use incompatible slot numbering"
                + " for a bytecode level body copy: an instance body reading this cannot"
                + " be copied into a static gateway, and a body with stack map frames"
                + " cannot be shifted"));
    }

    @Test
    void adaptsSlotNumberingWhenCopyingStaticTemplateIntoInstanceGateway() throws Exception {
        final Path classes = compile("test/SlotForkHolder.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Fork;\n"
                        + "public class SlotForkHolder {\n"
                        + "  @Fork(to = \"template\", ifActive = \"ESA\")\n"
                        + "  public String value(final String prefix, final int nr) {\n"
                        + "      throw new UnsupportedOperationException();\n"
                        + "  }\n"
                        + "  private static String template(final String prefix, final int nr) {\n"
                        + "      return prefix + \"-\" + nr;\n"
                        + "  }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        // the source transformation recompiled the static body into the
        // instance gateway and re-resolved the parameters; the bytecode copy
        // shifts the slots instead (instance slot 0 is this)
        assertThat(result.getErrors().isEmpty(), is(true));
        final ClassNode holder = read(classes, "test/SlotForkHolder");
        assertThat(method(holder, "value", "(Ljava/lang/String;I)Ljava/lang/String;"),
                is(not(nullValue())));
        assertThat(holder.methods.stream()
                .filter(m -> m.name.equals("template")).count(), is(0L));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));

        final Class<?> holderClass = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, null).loadClass("test.SlotForkHolder");
        final Object plain = holderClass.getDeclaredConstructor().newInstance();
        assertThat(holderClass.getMethod("value", String.class, int.class)
                .invoke(plain, "esa", 7), is("esa-7"));
    }

    @Test
    void allowsStaticInstanceForkWithSlotIndependentBody() throws Exception {
        final Path classes = compile("test/ConstantForkHolder.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Fork;\n"
                        + "public class ConstantForkHolder {\n"
                        + "  @Fork(to = \"constantMpk\", ifActive = \"ESA\")\n"
                        + "  public static String symbol() { throw new UnsupportedOperationException(); }\n"
                        + "  private String constantMpk() { return \"constant-value\"; }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        // the instance template reads neither this nor an argument, so the
        // slot numbering does not matter and the body may be copied into
        // the static gateway (the common flavour-constant template shape)
        assertThat(result.getErrors().isEmpty(), is(true));
        final ClassNode holder = read(classes, "test/ConstantForkHolder");
        assertThat(method(holder, "symbol", "()Ljava/lang/String;"), is(not(nullValue())));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));

        final Class<?> holderClass = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, null).loadClass("test.ConstantForkHolder");
        assertThat(holderClass.getMethod("symbol").invoke(null), is("constant-value"));
    }

    @Test
    void keepsLambdasOfSurvivingOverloads() throws Exception {
        final Path classes = compile("test/OverloadHolder.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "import java.util.function.Supplier;\n"
                        + "public class OverloadHolder {\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public String value(int i) {\n"
                        + "    Supplier<String> s = () -> \"nasa\" + i;\n"
                        + "    return s.get();\n"
                        + "  }\n"
                        + "  public String value() {\n"
                        + "    Supplier<String> s = () -> \"esa\";\n"
                        + "    return s.get();\n"
                        + "  }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        final ClassNode holder = read(classes, "test/OverloadHolder");
        assertThat(method(holder, "value", "(I)Ljava/lang/String;"), is(nullValue()));
        assertThat(method(holder, "value", "()Ljava/lang/String;"), is(not(nullValue())));
        assertThat(holder.methods.stream()
                .filter(m -> m.name.startsWith("lambda$value")).count(), is(1L));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));

        final Class<?> holderClass = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, null).loadClass("test.OverloadHolder");
        final Object plain = holderClass.getMethod("value").invoke(holderClass.getDeclaredConstructor().newInstance());
        assertThat(plain, is("esa"));
    }

    @Test
    void rebakesThePlaceholdersOfAlterConstValueConstants() throws Exception {
        final Path classes = compile(
                "test/TableRef.java",
                "package test;\n"
                        + "import java.lang.annotation.Retention;\n"
                        + "import java.lang.annotation.RetentionPolicy;\n"
                        + "@Retention(RetentionPolicy.RUNTIME)\n"
                        + "@interface TableRef { String name(); }\n",
                "test/ColumnRefs.java",
                "package test;\n"
                        + "import java.lang.annotation.Retention;\n"
                        + "import java.lang.annotation.RetentionPolicy;\n"
                        + "@Retention(RetentionPolicy.RUNTIME)\n"
                        + "@interface ColumnRefs { String[] names(); }\n",
                "test/TableNames.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.AlterConstValue;\n"
                        + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                        + "public class TableNames {\n"
                        + "  @AlterConstValue(ifActive = \"ESA\", with = \"ESA_JOB\")\n"
                        + "  @AlterConstValue(ifActive = \"NASA\", with = \"NASA_JOB\")\n"
                        + "  @InlinedConstantApproved\n"
                        + "  public static final String JOB = \"test.TableNames.JOB\";\n"
                        + "  @AlterConstValue(ifActive = {\"ESA\", \"NASA\"}, with = \"ESA_TELE\")\n"
                        + "  @InlinedConstantApproved\n"
                        + "  public static final String TELE = \"test.TableNames.TELE\";\n"
                        + "}\n",
                "test/JobEntity.java",
                "package test;\n"
                        + "public class JobEntity {\n"
                        + "  @TableRef(name = TableNames.JOB)\n"
                        + "  public Object field;\n"
                        + "  @ColumnRefs(names = {TableNames.JOB, TableNames.TELE})\n"
                        + "  public Object other;\n"
                        + "}\n");

        new BytecodeStripper().strip(classes);

        final ClassNode entity = read(classes, "test/JobEntity");
        final org.objectweb.asm.tree.AnnotationNode fieldRef = entity.fields.stream()
                .filter(f -> f.name.equals("field")).findFirst().get().visibleAnnotations.get(0);
        assertThat(fieldRef.values.get(1), is("ESA_JOB"));
        final org.objectweb.asm.tree.AnnotationNode columnsRef = entity.fields.stream()
                .filter(f -> f.name.equals("other")).findFirst().get().visibleAnnotations.get(0);
        assertThat(columnsRef.values.get(1),
                is((Object) java.util.Arrays.asList("ESA_JOB", "ESA_TELE")));
        // the declaring constant itself carries the flavour value as well
        final ClassNode names = read(classes, "test/TableNames");
        final org.objectweb.asm.tree.FieldNode job = names.fields.stream()
                .filter(f -> f.name.equals("JOB")).findFirst().get();
        assertThat(job.value, is("ESA_JOB"));
        // and the flavour annotations are stripped from the model
        assertThat(job.visibleAnnotations, is(nullValue()));
    }

    @Test
    void addsDefaultConstructorWhenAllExplicitConstructorsAreFlavourSpecific() throws Exception {
        final Path classes = compile("test/GatewayData.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class GatewayData {\n"
                        + "  private String type;\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public GatewayData() {\n"
                        + "      this.type = \"nasa\";\n"
                        + "  }\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public GatewayData(final String type) {\n"
                        + "      this.type = type;\n"
                        + "  }\n"
                        + "  public String getType() { return type; }\n"
                        + "}\n",
                "test/GatewayUser.java",
                "package test;\n"
                        + "public class GatewayUser {\n"
                        + "  public String build() {\n"
                        + "    return new GatewayData().getType();\n"
                        + "  }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        // javac parity: without an explicit constructor the flavour model
        // gets the implicit default constructor, like a recompile would
        final ClassNode data = read(classes, "test/GatewayData");
        assertThat(method(data, "<init>", "()V"), is(not(nullValue())));
        assertThat(method(data, "<init>", "(Ljava/lang/String;)V"), is(nullValue()));
        assertThat(data.methods.stream()
                .filter(m -> "<init>".equals(m.name)).count(), is(1L));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));

        final Class<?> dataClass = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, null).loadClass("test.GatewayData");
        final Object plain = dataClass.getDeclaredConstructor().newInstance();
        assertThat(dataClass.getMethod("getType").invoke(plain), is(nullValue()));

        final Class<?> userClass = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, null).loadClass("test.GatewayUser");
        assertThat(userClass.getMethod("build")
                .invoke(userClass.getDeclaredConstructor().newInstance()), is(nullValue()));
    }

    @Test
    void keepsSurvivingConstructorWithoutAddingADefault() throws Exception {
        final Path classes = compile("test/MixedCtorHolder.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class MixedCtorHolder {\n"
                        + "  private final String value;\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public MixedCtorHolder() { this.value = \"nasa\"; }\n"
                        + "  public MixedCtorHolder(final String value) { this.value = value; }\n"
                        + "  public String getValue() { return value; }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        final ClassNode holder = read(classes, "test/MixedCtorHolder");
        assertThat(holder.methods.stream()
                .filter(m -> "<init>".equals(m.name)).count(), is(1L));
        assertThat(method(holder, "<init>", "(Ljava/lang/String;)V"), is(not(nullValue())));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));

        final Class<?> holderClass = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, null).loadClass("test.MixedCtorHolder");
        final Object plain = holderClass.getConstructor(String.class).newInstance("esa");
        assertThat(holderClass.getMethod("getValue").invoke(plain), is("esa"));
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
    void writesStrippedModelToTargetDirLeavingSourceUntouched() throws Exception {
        final Path classes = compile("test/Person.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Person {\n"
                        + "  private String name;\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  private String nasaOnly;\n"
                        + "  @OnlyIf(\"ESA\")\n"
                        + "  public void esaConstructor() { }\n"
                        + "}\n",
                "test/NasaOnly.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "@OnlyIf(\"NASA\")\n"
                        + "public class NasaOnly { }\n");

        final Path target = workDir.resolve("stripped");
        new BytecodeStripper().strip(classes, target, java.util.Collections.emptyList());

        // the source keeps the full model
        final ClassNode sourcePerson = read(classes, "test/Person");
        assertThat(field(sourcePerson, "nasaOnly"), is(not(nullValue())));
        assertThat(read(classes, "test/NasaOnly"), is(not(nullValue())));

        // the target carries the stripped model
        final ClassNode targetPerson = read(target, "test/Person");
        assertThat(field(targetPerson, "nasaOnly"), is(nullValue()));
        assertThat(method(targetPerson, "esaConstructor", "()V"), is(not(nullValue())));
        assertThat(java.nio.file.Files.exists(
                target.resolve("test/NasaOnly.class")), is(false));
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
    void cleansUpDeserializeLambdaOfRemovedLambdas() throws Exception {
        final Path classes = compile("test/SerializableLambdaHolder.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "import java.io.Serializable;\n"
                        + "public class SerializableLambdaHolder {\n"
                        + "  interface Greeting extends Serializable {\n"
                        + "    String greet(String who);\n"
                        + "  }\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public String nasaOnly(String who) {\n"
                        + "    Greeting g = who0 -> \"nasa:\" + who0;\n"
                        + "    return g.greet(who);\n"
                        + "  }\n"
                        + "  public String plain(String who) {\n"
                        + "    Greeting g = who0 -> \"esa:\" + who0;\n"
                        + "    return g.greet(who);\n"
                        + "  }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        final ClassNode holder = read(classes, "test/SerializableLambdaHolder");
        assertThat(holder.methods.stream()
                .filter(m -> m.name.startsWith("lambda$nasaOnly")).count(), is(0L));
        assertThat(holder.methods.stream()
                .filter(m -> m.name.startsWith("lambda$plain")).count(), is(1L));
        final MethodNode deserializer = holder.methods.stream()
                .filter(m -> m.name.equals("$deserializeLambda$")).findFirst().orElse(null);
        assertThat(deserializer, is(not(nullValue())));
        // the lambda name remains as a plain string; the bootstrap handle
        // to the removed method is gone and the case throws the standard
        // "Invalid lambda deserialization" failure
        final java.util.List<org.objectweb.asm.Handle> handles = new java.util.ArrayList<>();
        deserializer.instructions.forEach(i -> {
            if (i instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode) {
                for (final Object arg : ((org.objectweb.asm.tree.InvokeDynamicInsnNode) i).bsmArgs) {
                    if (arg instanceof org.objectweb.asm.Handle) {
                        handles.add((org.objectweb.asm.Handle) arg);
                    }
                }
            }
        });
        assertThat(handles.stream().filter(h -> h.getName().startsWith("lambda$nasaOnly")).count(), is(0L));
        assertThat(handles.stream().filter(h -> h.getName().startsWith("lambda$plain")).count(), is(1L));
        assertThat(bytecode(deserializer).contains("Invalid lambda deserialization"), is(true));

        final Set<java.io.File> dirs = new HashSet<>();
        dirs.add(classes.toFile());
        assertThat(ReferenceVerifier.findDanglingReferences(
                dirs, result.getRemovedClasses(), result.getRemovedMembers()).isEmpty(), is(true));

        final Class<?> holderClass = new java.net.URLClassLoader(
                new java.net.URL[] {classes.toUri().toURL()}, null).loadClass("test.SerializableLambdaHolder");
        final Object plain = holderClass.getMethod("plain", String.class).invoke(holderClass.getDeclaredConstructor().newInstance(), "x");
        assertThat(plain, is("esa:x"));
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
