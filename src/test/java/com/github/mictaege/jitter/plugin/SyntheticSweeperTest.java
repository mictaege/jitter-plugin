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

public class SyntheticSweeperTest extends StripperTestFixture {

    @Test
    void shouldSweepOrphanedAnonymousClasses() throws Exception {
        final Path classes = compile("test/Sample.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Sample {\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public Runnable nasaOnly() {\n"
                        + "    return new Runnable() {\n"
                        + "      @Override public void run() { }\n"
                        + "    };\n"
                        + "  }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        SyntheticSweeper.removeOrphanedSynthetics(ctx);

        assertThat(ctx.nodes.containsKey("test/Sample$1"), is(false));
        assertThat(ctx.result.getRemovedClasses().contains("test/Sample$1"), is(true));
    }

    @Test
    void shouldKeepAnonymousClassesOfSurvivingCode() throws Exception {
        final Path classes = compile("test/Sample.java",
                "package test;\n"
                        + "public class Sample {\n"
                        + "  public Runnable plain() {\n"
                        + "    return new Runnable() {\n"
                        + "      @Override public void run() { }\n"
                        + "    };\n"
                        + "  }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        SyntheticSweeper.removeOrphanedSynthetics(ctx);

        assertThat(ctx.nodes.containsKey("test/Sample$1"), is(true));
    }

    @Test
    void shouldCleanTheDeserializeLambdaCasesOfRemovedLambdas() throws Exception {
        final Path classes = compile("test/Sample.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "import java.io.Serializable;\n"
                        + "public class Sample {\n"
                        + "  interface Greeting extends Serializable {\n"
                        + "    String greet(String who);\n"
                        + "  }\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public void nasaRun(final String who) {\n"
                        + "    Greeting g = name -> \"nasa\" + name;\n"
                        + "    g.greet(who);\n"
                        + "  }\n"
                        + "  public void plainRun(final String who) {\n"
                        + "    Greeting g = name -> \"plain\" + name;\n"
                        + "    g.greet(who);\n"
                        + "  }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        SyntheticSweeper.cleanDeserializeLambda(ctx);

        final MethodNode deserialize = node(ctx, "test/Sample").methods.stream()
                .filter(m -> m.name.equals("$deserializeLambda$")).findFirst().orElse(null);
        assertThat(deserialize, is(not(nullValue())));
        // the removed lambda's case deserializes to the standard failure now
        assertThat(bytecode(deserialize), containsString("Invalid lambda deserialization"));
    }

    @Test
    void shouldCleanSwitchMapEntriesOfRemovedEnumConstants() throws Exception {
        final Path classes = compile(
                "test/Bearer.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public enum Bearer {\n"
                        + "  ATLAS,\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  TITAN\n"
                        + "}\n",
                "test/Switcher.java",
                "package test;\n"
                        + "public class Switcher {\n"
                        + "  public String name(final Bearer bearer) {\n"
                        + "    switch (bearer) {\n"
                        + "      case ATLAS: return \"atlas\";\n"
                        + "      case TITAN: return \"titan\";\n"
                        + "      default: return \"other\";\n"
                        + "    }\n"
                        + "  }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        SyntheticSweeper.cleanSwitchMaps(ctx);

        final String switchMapName = ctx.nodes.keySet().stream()
                .filter(name -> name.startsWith("test/Switcher$"))
                .findFirst().orElse(null);
        assertThat(switchMapName, is(not(nullValue())));
        final MethodNode clinit = method(node(ctx, switchMapName), "<clinit>", "()V");
        assertThat(bytecode(clinit), not(containsString("Bearer#TITAN")));
        assertThat(bytecode(clinit), containsString("Bearer#ATLAS"));
    }

    @Test
    void shouldCleanTheNestAttributesOfRemovedNestedClasses() throws Exception {
        final Path classes = compile("test/Outer.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class Outer {\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public static class Gated {\n"
                        + "    private int value;\n"
                        + "    private int also;\n"
                        + "  }\n"
                        + "  public static class Keeper {\n"
                        + "  }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        SyntheticSweeper.removeOrphanedSynthetics(ctx);
        SyntheticSweeper.cleanNestAttributes(ctx);

        final ClassNode outer = node(ctx, "test/Outer");
        if (outer.nestMembers != null) {
            assertThat(outer.nestMembers.contains("test/Outer$Gated"), is(false));
        }
        assertThat(outer.innerClasses.stream()
                .filter(inner -> inner.name.equals("test/Outer$Gated")).count(), is(0L));
        assertThat(outer.innerClasses.stream()
                .filter(inner -> inner.name.equals("test/Outer$Keeper")).count(), is(1L));
    }

}
