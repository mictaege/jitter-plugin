package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class AlterConstApplierTest extends StripperTestFixture {

    private static final String ANCHOR = "test.TableNames.JOB";

    @Test
    void shouldRebakeOwnPlaceholdersInAnnotationsAndDeclarations() throws Exception {
        final Path classes = compile(
                "test/TableRef.java",
                "package test;\n"
                        + "import java.lang.annotation.Retention;\n"
                        + "import java.lang.annotation.RetentionPolicy;\n"
                        + "@Retention(RetentionPolicy.RUNTIME)\n"
                        + "@interface TableRef { String name(); }\n",
                "test/TableNames.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.AlterConstValue;\n"
                        + "public class TableNames {\n"
                        + "  @AlterConstValue(ifActive = \"ESA\", with = \"ESA_JOB\")\n"
                        + "  @AlterConstValue(ifActive = \"NASA\", with = \"NASA_JOB\")\n"
                        + "  public static final String JOB = \"" + ANCHOR + "\";\n"
                        + "}\n",
                "test/JobEntity.java",
                "package test;\n"
                        + "public class JobEntity {\n"
                        + "  @TableRef(name = TableNames.JOB)\n"
                        + "  public Object field;\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        AlterConstApplier.apply(ctx);

        final ClassNode entity = node(ctx, "test/JobEntity");
        final AnnotationNode ref = entity.fields.get(0).visibleAnnotations.get(0);
        assertThat(ref.values.get(1), is("ESA_JOB"));
        assertThat(ctx.dirty.contains("test/JobEntity"), is(true));
        final ClassNode names = node(ctx, "test/TableNames");
        assertThat(names.fields.get(0).value, is("ESA_JOB"));
        assertThat(ctx.result.getErrors().isEmpty(), is(true));
    }

    @Test
    void shouldRebakeExternalAnchorsFromTheClasspath() throws Exception {
        final Path dependency = compile(
                "dep/TableNames.java",
                "package dep;\n"
                        + "import com.github.mictaege.jitter.api.AlterConstValue;\n"
                        + "public class TableNames {\n"
                        + "  @AlterConstValue(ifActive = \"ESA\", with = \"ESA_JOB\")\n"
                        + "  public static final String JOB = \"dep.TableNames.JOB\";\n"
                        + "}\n");
        final Path classes = compile(
                dependency,
                "test/TableRef.java",
                "package test;\n"
                        + "import java.lang.annotation.Retention;\n"
                        + "import java.lang.annotation.RetentionPolicy;\n"
                        + "@Retention(RetentionPolicy.RUNTIME)\n"
                        + "@interface TableRef { String name(); }\n",
                "test/JobEntity.java",
                "package test;\n"
                        + "public class JobEntity {\n"
                        + "  @TableRef(name = dep.TableNames.JOB)\n"
                        + "  public Object field;\n"
                        + "}\n");

        final StripContext ctx =
                new StripContext(java.util.Collections.singletonList(dependency.toFile()),
                        java.util.Arrays.asList("Esa", "Nasa"));
        ClassIndex.loadClasses(classes, ctx.nodes, ctx.paths, ctx.result);
        AlterConstApplier.apply(ctx);

        final ClassNode entity = node(ctx, "test/JobEntity");
        final AnnotationNode ref = entity.fields.get(0).visibleAnnotations.get(0);
        assertThat(ref.values.get(1), is("ESA_JOB"));
    }

    @Test
    void shouldPatchWrapperInitializers() throws Exception {
        final Path classes = compile(
                "test/NavConstants.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.AlterConstValue;\n"
                        + "public class NavConstants {\n"
                        + "  @AlterConstValue(ifActive = \"ESA\", with = \"42\")\n"
                        + "  public static final Integer NODE = 4;\n"
                        + "  @AlterConstValue(ifActive = {\"ESA\", \"NASA\"}, with = \"true\")\n"
                        + "  public static final Boolean FLAG = false;\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        AlterConstApplier.apply(ctx);

        final ClassNode names = node(ctx, "test/NavConstants");
        assertThat(intAssignedInClinit(names, "NODE"), is(42));
        assertThat(booleanAssignedInClinit(names, "FLAG"), is(true));
        assertThat(ctx.result.getErrors().isEmpty(), is(true));
    }

    @Test
    void shouldPatchNonConstantPrimitiveFields() throws Exception {
        // the Kotlin form of a non-inlinable constant (a top level or
        // object 'val Int') compiles to the same bytecode shape: a static
        // primitive field without a ConstantValue, assigned in <clinit>
        final Path classes = compile(
                "test/NavConstants.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.AlterConstValue;\n"
                        + "public class NavConstants {\n"
                        + "  @AlterConstValue(ifActive = {\"ESA\", \"NASA\"}, with = \"42\")\n"
                        + "  public static int NODE = 4;\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        AlterConstApplier.apply(ctx);

        final ClassNode names = node(ctx, "test/NavConstants");
        assertThat(intAssignedInClinit(names, "NODE"), is(42));
        assertThat(ctx.result.getErrors().isEmpty(), is(true));
    }

    @Test
    void shouldFailOnComputedWrapperInitialization() throws Exception {
        final Path classes = compile(
                "test/NavConstants.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.AlterConstValue;\n"
                        + "public class NavConstants {\n"
                        + "  @AlterConstValue(ifActive = \"ESA\", with = \"42\")\n"
                        + "  public static final Integer NODE = Integer.parseInt(\"4\");\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        AlterConstApplier.apply(ctx);

        assertThat(ctx.result.getErrors().size(), is(1));
        assertThat(ctx.result.getErrors().get(0), containsString("NODE"));
        assertThat(ctx.result.getErrors().get(0), containsString("folded literal"));
    }

    @Test
    void shouldFailOnPlaceholdersInCode() throws Exception {
        final Path classes = compile(
                "test/TableNames.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.AlterConstValue;\n"
                        + "public class TableNames {\n"
                        + "  @AlterConstValue(ifActive = {\"ESA\", \"NASA\"}, with = \"ESA_JOB\")\n"
                        + "  public static final String JOB = \"" + ANCHOR + "\";\n"
                        + "  public String describe() {\n"
                        + "    return \"table: \" + JOB;\n"
                        + "  }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        AlterConstApplier.apply(ctx);
        // the inlined placeholder of the body usage survives the patch
        AlterConstApplier.verifyNoPlaceholdersLeft(ctx);

        assertThat(ctx.result.getErrors().size(), is(1));
        assertThat(ctx.result.getErrors().get(0), containsString(ANCHOR));
        assertThat(ctx.result.getErrors().get(0), containsString("describe"));
    }

    @Test
    void shouldDoNothingWithoutActiveFlavour() throws Exception {
        final Path classes = compile(
                "test/TableNames.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.AlterConstValue;\n"
                        + "public class TableNames {\n"
                        + "  @AlterConstValue(ifActive = {\"ESA\", \"NASA\"}, with = \"ESA_JOB\")\n"
                        + "  public static final String JOB = \"" + ANCHOR + "\";\n"
                        + "}\n");
        final StripContext ctx = loadCtx(classes);
        final String flavour = System.getProperty(JitterUtil.FLAVOUR_PROP);
        try {
            System.clearProperty(JitterUtil.FLAVOUR_PROP);
            AlterConstApplier.apply(ctx);
            AlterConstApplier.verifyNoPlaceholdersLeft(ctx);
            assertThat(ctx.dirty.isEmpty(), is(true));
            assertThat(ctx.result.getErrors().isEmpty(), is(true));
            assertThat(node(ctx, "test/TableNames").fields.get(0).value, is(ANCHOR));
        } finally {
            System.setProperty(JitterUtil.FLAVOUR_PROP, flavour);
        }
    }

    @Test
    void shouldKeepTheJitterAnnotationsForTheCleanerPhase() throws Exception {
        // the applier runs before the AnnotationCleaner, which strips the
        // flavour annotations from the surviving model - the applier must
        // leave them in place for the phases that follow it
        final Path classes = compile(
                "test/TableNames.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.AlterConstValue;\n"
                        + "public class TableNames {\n"
                        + "  @AlterConstValue(ifActive = {\"ESA\", \"NASA\"}, with = \"ESA_JOB\")\n"
                        + "  public static final String JOB = \"" + ANCHOR + "\";\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        AlterConstApplier.apply(ctx);

        final ClassNode names = node(ctx, "test/TableNames");
        assertThat(names.fields.get(0).visibleAnnotations.isEmpty(), is(false));
    }

    private int intAssignedInClinit(final ClassNode node, final String field) {
        final MethodNode clinit = clinitOf(node);
        AbstractInsnNode previous = null;
        AbstractInsnNode previousPrevious = null;
        for (final AbstractInsnNode insn : clinit.instructions) {
            if (insn instanceof FieldInsnNode
                    && ((FieldInsnNode) insn).name.equals(field)
                    && insn.getOpcode() == Opcodes.PUTSTATIC) {
                if (previous.getOpcode() == Opcodes.INVOKESTATIC) {
                    // the boxed shape: push, valueOf, putstatic
                    return pushValueOf(previousPrevious);
                }
                // the primitive shape: push, putstatic
                return pushValueOf(previous);
            }
            if (!InsnTool.isMeta(insn) && !(insn instanceof org.objectweb.asm.tree.LabelNode)) {
                previousPrevious = previous;
                previous = insn;
            }
        }
        throw new AssertionError("no assignment of " + field + " found");
    }

    private boolean booleanAssignedInClinit(final ClassNode node, final String field) {
        final MethodNode clinit = clinitOf(node);
        AbstractInsnNode previous = null;
        AbstractInsnNode previousPrevious = null;
        for (final AbstractInsnNode insn : clinit.instructions) {
            if (insn instanceof FieldInsnNode
                    && ((FieldInsnNode) insn).name.equals(field)
                    && insn.getOpcode() == Opcodes.PUTSTATIC) {
                if (previous.getOpcode() == Opcodes.GETSTATIC) {
                    return "TRUE".equals(((FieldInsnNode) previous).name);
                }
                return pushValueOf(previousPrevious) == 1;
            }
            if (!InsnTool.isMeta(insn)) {
                previousPrevious = previous;
                previous = insn;
            }
        }
        throw new AssertionError("no assignment of " + field + " found");
    }

    private int pushValueOf(final AbstractInsnNode insn) {
        final int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_0 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        if (insn instanceof org.objectweb.asm.tree.IntInsnNode) {
            return ((org.objectweb.asm.tree.IntInsnNode) insn).operand;
        }
        if (insn instanceof org.objectweb.asm.tree.LdcInsnNode) {
            return (Integer) ((org.objectweb.asm.tree.LdcInsnNode) insn).cst;
        }
        throw new AssertionError("unexpected load instruction " + insn);
    }

    private MethodNode clinitOf(final ClassNode node) {
        for (final MethodNode method : node.methods) {
            if ("<clinit>".equals(method.name)) {
                return method;
            }
        }
        throw new AssertionError("no <clinit> in " + node.name);
    }

}
