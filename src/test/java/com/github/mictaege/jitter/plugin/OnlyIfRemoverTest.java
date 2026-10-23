package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class OnlyIfRemoverTest extends StripperTestFixture {

    @Test
    void shouldRemoveGatedFieldsAndMethods() throws Exception {
        final Path classes = compile("test/Person.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Person {\n"
                        + "  private String name;\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  private String nasaOnly;\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public void nasaOnly() { }\n"
                        + "  public void plain() { }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);

        final ClassNode person = node(ctx, "test/Person");
        assertThat(field(person, "nasaOnly"), is(nullValue()));
        assertThat(method(person, "nasaOnly", "()V"), is(nullValue()));
        assertThat(field(person, "name"), is(not(nullValue())));
        assertThat(method(person, "plain", "()V"), is(not(nullValue())));
        assertThat(ctx.result.getRemovedMembers()
                .contains("test/Person.nasaOnly()V"), is(true));
        assertThat(ctx.result.getRemovedFields().size(), is(1));
    }

    @Test
    void shouldRemoveGatedClassesWithTheirNestedClasses() throws Exception {
        final Path classes = compile(
                "test/NasaOnly.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "@OnlyIf(\"NASA\")\n"
                        + "public class NasaOnly {\n"
                        + "  public static class Inner { }\n"
                        + "}\n",
                "test/Plain.java",
                "package test;\n"
                        + "public class Plain { }\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);

        assertThat(ctx.nodes.containsKey("test/NasaOnly"), is(false));
        assertThat(ctx.nodes.containsKey("test/NasaOnly$Inner"), is(false));
        assertThat(ctx.nodes.containsKey("test/Plain"), is(true));
        assertThat(ctx.result.getRemovedClasses().contains("test/NasaOnly$Inner"), is(true));
    }

    @Test
    void shouldRemoveTheLambdasOfRemovedMethodsAndRegisterThem() throws Exception {
        final Path classes = compile("test/Runner.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Runner {\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public void nasaRun() {\n"
                        + "    final Runnable r = () -> System.out.println(\"nasa\");\n"
                        + "    r.run();\n"
                        + "  }\n"
                        + "  public void plainRun() {\n"
                        + "    final Runnable r = () -> System.out.println(\"plain\");\n"
                        + "    r.run();\n"
                        + "  }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);

        final ClassNode runner = node(ctx, "test/Runner");
        assertThat(runner.methods.stream()
                .filter(m -> m.name.startsWith("lambda$")).count(), is(1L));
        assertThat(runner.methods.stream()
                .filter(m -> m.name.startsWith("lambda$") && bytecode(m).contains("nasa")).count(), is(0L));
        assertThat(ctx.removedLambdas.get("test/Runner").size(), is(1));
        // the reference to the removed lambda method is recorded as well,
        // and the removed method itself
        assertThat(ctx.result.getRemovedMembers().stream()
                .filter(key -> key.startsWith("test/Runner.lambda$")).count(), is(1L));
    }

    @Test
    void shouldRestoreTheDefaultConstructorWhenAllExplicitOnesAreGated() throws Exception {
        final Path classes = compile("test/GatewayData.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class GatewayData {\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public GatewayData() { }\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public GatewayData(final String type) { }\n"
                        + "  public String getType() { return null; }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        OnlyIfRemover.restoreDefaultConstructors(ctx);

        final ClassNode data = node(ctx, "test/GatewayData");
        final MethodNode ctor = method(data, "<init>", "()V");
        assertThat(ctor, is(not(nullValue())));
        assertThat((ctor.access & Opcodes.ACC_PUBLIC), is(Opcodes.ACC_PUBLIC));
        // javac parity: the restored constructor is un-registered again,
        // so references to it pass the reference verification
        assertThat(ctx.result.getRemovedMembers().contains("test/GatewayData.<init>()V"), is(false));
    }

    @Test
    void shouldNotRestoreAConstructorIntoAGatedNonStaticInnerClass() throws Exception {
        final Path classes = compile("test/Holder.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Holder {\n"
                        + "  public class Inner {\n"
                        + "    @OnlyIf(\"NASA\")\n"
                        + "    public Inner() { }\n"
                        + "  }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        OnlyIfRemover.restoreDefaultConstructors(ctx);

        final ClassNode inner = node(ctx, "test/Holder$Inner");
        assertThat(method(inner, "<init>", "()V"), is(nullValue()));
        // a named inner class constructor takes the outer instance; the
        // implicit ()V would be wrong - no default is restored
    }

    @Test
    void shouldNotTouchClassesWithASurvivingConstructor() throws Exception {
        final Path classes = compile("test/Mixed.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Mixed {\n"
                        + "  public Mixed() { }\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public Mixed(final String type) { }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        OnlyIfRemover.restoreDefaultConstructors(ctx);

        final ClassNode mixed = node(ctx, "test/Mixed");
        // only the surviving default constructor remains, nothing added
        assertThat(mixed.methods.stream()
                .filter(m -> "<init>".equals(m.name)).count(), is(1L));
    }

}
