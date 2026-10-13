package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @Test
    void shouldCollectTheConstantReplacementsOfTheAlteredClass() throws Exception {
        final Path classes = compile(
                "test/TableNames.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Alter;\n"
                        + "@Alter(ifActive = \"ESA\", with = \"TableNamesEsa\")\n"
                        + "public class TableNames {\n"
                        + "  public static final String JOB = \"full.JOB\";\n"
                        + "}\n",
                "test/TableNamesEsa.java",
                "package test;\n"
                        + "public class TableNamesEsa {\n"
                        + "  public static final String JOB = \"esa.JOB\";\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        final Map<String, String> replacements = AlterApplier.applyAltclasses(ctx);

        assertThat(replacements.get("full.JOB"), is("esa.JOB"));
    }

    @Test
    void shouldRebakeTheInlinedConstantValuesOfAnnotations() throws Exception {
        final Path classes = compile(
                "test/TableNames.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Alter;\n"
                        + "@Alter(ifActive = \"ESA\", with = \"TableNamesEsa\")\n"
                        + "public class TableNames {\n"
                        + "  public static final String JOB = \"full.JOB\";\n"
                        + "}\n",
                "test/TableNamesEsa.java",
                "package test;\n"
                        + "public class TableNamesEsa {\n"
                        + "  public static final String JOB = \"esa.JOB\";\n"
                        + "}\n",
                "test/TableRef.java",
                "package test;\n"
                        + "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                        + "@interface TableRef { String name(); }\n",
                "test/JobEntity.java",
                "package test;\n"
                        + "public class JobEntity {\n"
                        + "  @TableRef(name = TableNames.JOB)\n"
                        + "  public Object field;\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        final Map<String, String> replacements = AlterApplier.applyAltclasses(ctx);
        AlterApplier.rebakeAnnotationConstants(ctx, replacements);

        final ClassNode entity = node(ctx, "test/JobEntity");
        final AnnotationNode ref = entity.fields.get(0).visibleAnnotations.get(0);
        assertThat(ref.values.get(1), is("esa.JOB"));
        assertThat(ctx.dirty.contains("test/JobEntity"), is(true));
    }

    @Test
    void shouldSkipAmbiguousConstantValues() throws Exception {
        final Path classes = compile(
                "test/TableNames.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Alter;\n"
                        + "@Alter(ifActive = \"ESA\", with = \"TableNamesEsa\")\n"
                        + "public class TableNames {\n"
                        + "  public static final String A = \"ambiguous\";\n"
                        + "  public static final String B = \"ambiguous\";\n"
                        + "  public static final String C = \"unique\";\n"
                        + "}\n",
                "test/TableNamesEsa.java",
                "package test;\n"
                        + "public class TableNamesEsa {\n"
                        + "  public static final String A = \"esa.A\";\n"
                        + "  public static final String B = \"esa.B\";\n"
                        + "  public static final String C = \"esa.C\";\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        final Map<String, String> replacements = AlterApplier.applyAltclasses(ctx);

        assertThat(replacements.containsKey("ambiguous"), is(false));
        assertThat(replacements.get("unique"), is("esa.C"));
    }

    @Test
    void shouldDoNothingWithoutReplacements() throws Exception {
        final StripContext ctx = new StripContext(null);
        final ClassNode node = new ClassNode();
        node.name = "test/Keep";
        ctx.nodes.put(node.name, node);

        AlterApplier.rebakeAnnotationConstants(ctx, new java.util.HashMap<>());

        assertThat(ctx.dirty.isEmpty(), is(true));
        assertThat(ctx.result.getInfos().isEmpty(), is(true));
    }

}
