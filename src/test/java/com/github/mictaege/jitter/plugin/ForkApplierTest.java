package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class ForkApplierTest extends StripperTestFixture {

    @Test
    void shouldReplaceTheGatewayBodyWithTheTemplateBody() throws Exception {
        final Path classes = compile("test/Gateway.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Fork;\n"
                        + "public class Gateway {\n"
                        + "  @Fork(ifActive = \"ESA\", to = \"initEsa\")\n"
                        + "  public String init() { return initEsa(); }\n"
                        + "  private String initEsa() { return \"esa\"; }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        ForkApplier.applyForkMethods(ctx);

        final ClassNode gateway = node(ctx, "test/Gateway");
        final MethodNode slot = method(gateway, "init", "()Ljava/lang/String;");
        assertThat(bytecode(slot), containsString("esa\n"));
        assertThat(method(gateway, "initEsa", "()Ljava/lang/String;"), is(nullValue()));
        assertThat(ctx.result.getRemovedMembers()
                .contains("test/Gateway.initEsa()Ljava/lang/String;"), is(true));
        assertThat(ctx.dirty.contains("test/Gateway"), is(true));
    }

    @Test
    void shouldReportAMissingTemplate() throws Exception {
        final Path classes = compile("test/Gateway.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Fork;\n"
                        + "public class Gateway {\n"
                        + "  @Fork(ifActive = \"ESA\", to = \"nowhere\")\n"
                        + "  public String init() { return \"default\"; }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        ForkApplier.applyForkMethods(ctx);

        assertThat(ctx.result.getErrors().isEmpty(), is(false));
        // the gateway keeps its body - nothing is replaced on error
        assertThat(node(ctx, "test/Gateway").methods.stream()
                .filter(m -> m.name.equals("init")).count(), is(1L));
    }

    @Test
    void shouldForkOverloadedGatewaysToTheTemplateOfTheirOwnSignature() throws Exception {
        final Path classes = compile("test/Gateway.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Fork;\n"
                        + "public class Gateway {\n"
                        + "  @Fork(ifActive = \"ESA\", to = \"impl\")\n"
                        + "  public String work(final String s) { return \"default\"; }\n"
                        + "  @Fork(ifActive = \"ESA\", to = \"impl\")\n"
                        + "  public String work(final int i) { return \"default\"; }\n"
                        + "  private String impl(final String s) { return \"string\"; }\n"
                        + "  private String impl(final int i) { return \"int\"; }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        ForkApplier.applyForkMethods(ctx);

        final ClassNode gateway = node(ctx, "test/Gateway");
        // each gateway gets the template of its own signature
        assertThat(bytecode(method(gateway, "work", "(Ljava/lang/String;)Ljava/lang/String;")),
                containsString("string\n"));
        assertThat(bytecode(method(gateway, "work", "(I)Ljava/lang/String;")),
                containsString("int\n"));
        assertThat(gateway.methods.stream().filter(m -> m.name.equals("impl")).count(), is(0L));
    }

    @Test
    void shouldRejectAnInstanceTemplateReadingThisInAStaticGateway() throws Exception {
        final Path classes = compile("test/Gateway.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Fork;\n"
                        + "public class Gateway {\n"
                        + "  private String value;\n"
                        + "  @Fork(ifActive = \"ESA\", to = \"initEsa\")\n"
                        + "  public static String init() { return \"default\"; }\n"
                        + "  private String initEsa() { return value; }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        ForkApplier.applyForkMethods(ctx);

        assertThat(ctx.result.getErrors().isEmpty(), is(false));
        assertThat(node(ctx, "test/Gateway").methods.stream()
                .filter(m -> m.name.equals("initEsa")).count(), is(1L));
    }

}
