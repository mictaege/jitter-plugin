package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class AlterApplierTest extends StripperTestFixture {

    @Test
    void shouldReplaceTheTargetWithTheTemplateAndRemoveTheTemplate() throws Exception {
        final Path classes = compile(
                "test/Validator.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Alter;\n"
                        + "@Alter(ifActive = \"ESA\", with = \"ValidatorEsa\")\n"
                        + "public class Validator {\n"
                        + "  public String validate() { return \"gateway\"; }\n"
                        + "}\n",
                "test/ValidatorEsa.java",
                "package test;\n"
                        + "public class ValidatorEsa {\n"
                        + "  public String validate() { return \"esa\"; }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        AlterApplier.applyAltclasses(ctx);

        assertThat(ctx.nodes.containsKey("test/ValidatorEsa"), is(false));
        final ClassNode slot = node(ctx, "test/Validator");
        assertThat(slot.sourceFile, is("ValidatorEsa.java"));
        assertThat((slot.access & Opcodes.ACC_PUBLIC), is(Opcodes.ACC_PUBLIC));
        assertThat(ctx.dirty.contains("test/Validator"), is(true));
        assertThat(ctx.result.getRemovedClasses().contains("test/ValidatorEsa"), is(true));
    }

    @Test
    void shouldTransferNonPublicVisibilityToTheTemplate() throws Exception {
        final Path classes = compile(
                "test/HiddenTarget.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Alter;\n"
                        + "@Alter(ifActive = \"ESA\", with = \"HiddenTemplate\")\n"
                        + "class HiddenTarget {\n"
                        + "  public String validate() { return \"gateway\"; }\n"
                        + "}\n",
                "test/HiddenTemplate.java",
                "package test;\n"
                        + "public class HiddenTemplate {\n"
                        + "  public String validate() { return \"esa\"; }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        AlterApplier.applyAltclasses(ctx);

        final ClassNode slot = node(ctx, "test/HiddenTarget");
        assertThat((slot.access & Opcodes.ACC_PUBLIC), is(0));
    }

    @Test
    void shouldReportAMissingTemplate() throws Exception {
        final Path classes = compile(
                "test/Validator.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Alter;\n"
                        + "@Alter(ifActive = \"ESA\", with = \"Nowhere\")\n"
                        + "public class Validator { }\n");

        final StripContext ctx = loadCtx(classes);
        AlterApplier.applyAltclasses(ctx);

        assertThat(ctx.result.getErrors().isEmpty(), is(false));
        assertThat(ctx.nodes.containsKey("test/Validator"), is(true));
    }

}
