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

public class EnumRewriterTest extends StripperTestFixture {

    @Test
    void shouldRemoveGatedConstantsRenumberAndRebuildTheValues() throws Exception {
        final Path classes = compile("test/Bearer.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public enum Bearer {\n"
                        + "  ATLAS,\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  TITAN,\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  GEMINI,\n"
                        + "  SOJUS\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        EnumRewriter.rewriteEnums(ctx);
        // the fields themselves are removed by the OnlyIf phase, like in the pipeline
        OnlyIfRemover.removeOnlyIf(ctx);

        final ClassNode bearer = node(ctx, "test/Bearer");
        assertThat(field(bearer, "TITAN"), is(nullValue()));
        assertThat(field(bearer, "GEMINI"), is(nullValue()));
        assertThat(field(bearer, "ATLAS"), is(not(nullValue())));
        assertThat(field(bearer, "SOJUS"), is(not(nullValue())));

        // the $values helper carries only the survivors
        final MethodNode values = method(bearer, "$values", "()[Ltest/Bearer;");
        assertThat(bytecode(values), containsString("test/Bearer#ATLAS\n"));
        assertThat(bytecode(values), containsString("test/Bearer#SOJUS\n"));
        assertThat(bytecode(values), not(containsString("test/Bearer#TITAN\n")));

        assertThat(ctx.dirty.contains("test/Bearer"), is(true));
    }

    @Test
    void shouldLeaveEnumsWithoutGatedConstantsAlone() throws Exception {
        final Path classes = compile("test/PlainEnum.java",
                "package test;\n"
                        + "public enum PlainEnum {\n"
                        + "  ONE,\n"
                        + "  TWO\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        EnumRewriter.rewriteEnums(ctx);
        // the fields themselves are removed by the OnlyIf phase, like in the pipeline
        OnlyIfRemover.removeOnlyIf(ctx);

        final ClassNode plain = node(ctx, "test/PlainEnum");
        assertThat(field(plain, "ONE"), is(not(nullValue())));
        assertThat(ctx.dirty.isEmpty(), is(true));
    }

    @Test
    void shouldKeepTheOrdinalsOfSurvivingConstantsDense() throws Exception {
        final Path classes = compile("test/Bearer.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public enum Bearer {\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  TITAN,\n"
                        + "  ATLAS,\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  GEMINI,\n"
                        + "  SOJUS\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        EnumRewriter.rewriteEnums(ctx);
        // the fields themselves are removed by the OnlyIf phase, like in the pipeline
        OnlyIfRemover.removeOnlyIf(ctx);

        final ClassNode bearer = node(ctx, "test/Bearer");
        // the clinit still initializes the survivors, without the removed ones
        final String clinit = bytecode(method(bearer, "<clinit>", "()V"));
        assertThat(clinit, not(containsString("TITAN")));
        assertThat(clinit, not(containsString("GEMINI")));
        assertThat(clinit.indexOf("ATLAS") >= 0 && clinit.indexOf("SOJUS") > clinit.indexOf("ATLAS"), is(true));
        assertThat(ctx.result.getInfos().isEmpty(), is(false));
    }

}
