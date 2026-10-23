package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class InitializerRemoverTest extends StripperTestFixture {

    @Test
    void shouldRemoveTheInitializerStoreOfARemovedField() throws Exception {
        final Path classes = compile("test/Sample.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Sample {\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  private String nasaSecret = \"nasa-secret\";\n"
                        + "  private String common = \"common\";\n"
                        + "  public Sample() { }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        InitializerRemover.removeFieldInitializers(ctx);

        final ClassNode sample = node(ctx, "test/Sample");
        final MethodNode ctor = method(sample, "<init>", "()V");
        // the store of the removed field is gone, the surviving one stays
        assertThat(bytecode(ctor), not(containsString("nasaSecret")));
        assertThat(bytecode(ctor), not(containsString("nasa-secret")));
        assertThat(bytecode(ctor), containsString("common"));
    }

    @Test
    void shouldRemoveTheLambdasOfARemovedInitializer() throws Exception {
        final Path classes = compile("test/Sample.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Sample {\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  private final Runnable nasa = () -> System.out.println(\"nasa\");\n"
                        + "  public Sample() { }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        InitializerRemover.removeFieldInitializers(ctx);

        final ClassNode sample = node(ctx, "test/Sample");
        assertThat(sample.methods.stream()
                .filter(m -> m.name.startsWith("lambda$new")).count(), is(0L));
        assertThat(ctx.removedLambdas.get("test/Sample").size(), is(1));
        assertThat(bytecode(method(sample, "<init>", "()V")), not(containsString("Sample#nasa")));
    }

    @Test
    void shouldReportBranchyInitializersInsteadOfRemovingThem() throws Exception {
        final Path classes = compile("test/Sample.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Sample {\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  private final String nasa = java.lang.Boolean.getBoolean(\"x\") ? \"a\" : \"b\";\n"
                        + "  public Sample() { }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        InitializerRemover.removeFieldInitializers(ctx);

        final ClassNode sample = node(ctx, "test/Sample");
        final MethodNode ctor = method(sample, "<init>", "()V");
        assertThat(bytecode(ctor), containsString("nasa"));
        assertThat(ctx.result.getInfos().stream()
                .filter(msg -> msg.contains("Could not remove the initializer")).count(), is(1L));
    }

    @Test
    void shouldRemoveStringConcatInitializersOfRemovedFields() throws Exception {
        final Path classes = compile("test/Sample.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Sample {\n"
                        + "  private String base = \"b\";\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  private String nasa = base + \"!\";\n"
                        + "  public Sample() { }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        InitializerRemover.removeFieldInitializers(ctx);

        final MethodNode ctor = method(node(ctx, "test/Sample"), "<init>", "()V");
        // the concat sequence including the store is gone
        assertThat(bytecode(ctor), not(containsString("Sample#nasa")));
        // the surviving field's initializer is untouched
        assertThat(bytecode(ctor), containsString("Sample#base"));
        assertThat(ctor, is(not(nullValue())));
    }

}
