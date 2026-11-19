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
    void shouldCleanSwitchMapEntriesOfCrossModuleRemovedEnumConstants() throws Exception {
        // the enum lives in another module: only the producer's removal
        // manifest knows that TITAN is gone in the active flavour
        final Path producer = compile("test/Bearer.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public enum Bearer {\n"
                        + "  ATLAS,\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  TITAN\n"
                        + "}\n");
        final Path consumer = compile(producer, "test/Switcher.java",
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

        final StripContext ctx = loadCtx(consumer);
        // the cross-module removals arrive via the producer's removal manifest
        ctx.crossModuleRemovedMembers.add(StripResult.memberKey("test/Bearer", "TITAN", "Ltest/Bearer;"));
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
    void shouldKeepCrossModuleSwitchMapEntriesWhenNoManifestReportsThem() throws Exception {
        final Path producer = compile("test/Bearer.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public enum Bearer {\n"
                        + "  ATLAS,\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  TITAN\n"
                        + "}\n");
        final Path consumer = compile(producer, "test/Switcher.java",
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

        // no removal manifest of the producer reports TITAN: the entry
        // stays, the NoSuchFieldError handler tolerates it at runtime
        final StripContext ctx = loadCtx(consumer);
        SyntheticSweeper.cleanSwitchMaps(ctx);

        final String switchMapName = ctx.nodes.keySet().stream()
                .filter(name -> name.startsWith("test/Switcher$"))
                .findFirst().orElse(null);
        assertThat(switchMapName, is(not(nullValue())));
        final MethodNode clinit = method(node(ctx, switchMapName), "<clinit>", "()V");
        assertThat(bytecode(clinit), containsString("Bearer#TITAN"));
    }

    @Test
    void shouldFailOnUnparseableSwitchMapEntryShape() throws Exception {
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
        // break the javac entry shape: drop the IASTORE of the TITAN entry
        final String switchMapName = ctx.nodes.keySet().stream()
                .filter(name -> name.startsWith("test/Switcher$"))
                .findFirst().orElse(null);
        final MethodNode clinit = method(node(ctx, switchMapName), "<clinit>", "()V");
        final org.objectweb.asm.tree.AbstractInsnNode[] all = clinit.instructions.toArray();
        int titanIdx = -1;
        for (int i = 0; i < all.length; i++) {
            if (all[i] instanceof org.objectweb.asm.tree.FieldInsnNode
                    && ((org.objectweb.asm.tree.FieldInsnNode) all[i]).name.equals("TITAN")) {
                titanIdx = i;
                break;
            }
        }
        assertThat(titanIdx >= 0, is(true));
        boolean broken = false;
        for (int i = titanIdx; i < all.length; i++) {
            if (all[i].getOpcode() == org.objectweb.asm.Opcodes.IASTORE) {
                clinit.instructions.remove(all[i]);
                broken = true;
                break;
            }
        }
        assertThat(broken, is(true));

        SyntheticSweeper.cleanSwitchMaps(ctx);

        assertThat(ctx.result.getErrors().isEmpty(), is(false));
        assertThat(String.join("\n", ctx.result.getErrors()), containsString("TITAN"));
    }

    @Test
    void shouldCleanKotlinWhenMapEntriesOfRemovedEnumConstants() throws Exception {
        final Path classes = KotlinFixtures.compileKotlin(workDir,
                "test/Bearer.kt",
                "package test\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf\n"
                        + "enum class Bearer {\n"
                        + "    ATLAS,\n"
                        + "    @OnlyIf(\"NASA\") TITAN\n"
                        + "}\n",
                "test/Switcher.kt",
                "package test\n"
                        + "class Switcher {\n"
                        + "    fun name(bearer: Bearer): String = when (bearer) {\n"
                        + "        Bearer.ATLAS -> \"atlas\"\n"
                        + "        Bearer.TITAN -> \"titan\"\n"
                        + "    }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        SyntheticSweeper.cleanKotlinWhenMaps(ctx);

        final MethodNode clinit = method(node(ctx, "test/Switcher$WhenMappings"), "<clinit>", "()V");
        assertThat(bytecode(clinit), not(containsString("Bearer#TITAN")));
        assertThat(bytecode(clinit), containsString("Bearer#ATLAS"));
        assertThat(ctx.result.getErrors().isEmpty(), is(true));
    }

    @Test
    void shouldCleanKotlinWhenMapEntriesOfCrossModuleRemovedEnumConstants() throws Exception {
        final Path producer = KotlinFixtures.compileKotlin(workDir.resolve("producer"),
                "test/Bearer.kt",
                "package test\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf\n"
                        + "enum class Bearer {\n"
                        + "    ATLAS,\n"
                        + "    @OnlyIf(\"NASA\") TITAN\n"
                        + "}\n");
        final Path consumer = KotlinFixtures.compileKotlin(workDir.resolve("consumer"), producer,
                "test/Switcher.kt",
                "package test\n"
                        + "class Switcher {\n"
                        + "    fun name(bearer: Bearer): String = when (bearer) {\n"
                        + "        Bearer.ATLAS -> \"atlas\"\n"
                        + "        Bearer.TITAN -> \"titan\"\n"
                        + "    }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(consumer);
        // the cross-module removals arrive via the producer's removal manifest
        ctx.crossModuleRemovedMembers.add(StripResult.memberKey("test/Bearer", "TITAN", "Ltest/Bearer;"));
        SyntheticSweeper.cleanKotlinWhenMaps(ctx);

        final MethodNode clinit = method(node(ctx, "test/Switcher$WhenMappings"), "<clinit>", "()V");
        assertThat(bytecode(clinit), not(containsString("Bearer#TITAN")));
        assertThat(bytecode(clinit), containsString("Bearer#ATLAS"));
        assertThat(ctx.result.getErrors().isEmpty(), is(true));
    }

    @Test
    void shouldFailOnUnparseableKotlinWhenMapEntryShape() throws Exception {
        final Path classes = KotlinFixtures.compileKotlin(workDir,
                "test/Bearer.kt",
                "package test\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf\n"
                        + "enum class Bearer {\n"
                        + "    ATLAS,\n"
                        + "    @OnlyIf(\"NASA\") TITAN\n"
                        + "}\n",
                "test/Switcher.kt",
                "package test\n"
                        + "class Switcher {\n"
                        + "    fun name(bearer: Bearer): String = when (bearer) {\n"
                        + "        Bearer.ATLAS -> \"atlas\"\n"
                        + "        Bearer.TITAN -> \"titan\"\n"
                        + "    }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        // break the kotlin entry shape: drop the IASTORE of the TITAN entry
        final MethodNode clinit = method(node(ctx, "test/Switcher$WhenMappings"), "<clinit>", "()V");
        final org.objectweb.asm.tree.AbstractInsnNode[] all = clinit.instructions.toArray();
        int titanIdx = -1;
        for (int i = 0; i < all.length; i++) {
            if (all[i] instanceof org.objectweb.asm.tree.FieldInsnNode
                    && ((org.objectweb.asm.tree.FieldInsnNode) all[i]).name.equals("TITAN")) {
                titanIdx = i;
                break;
            }
        }
        assertThat(titanIdx >= 0, is(true));
        boolean broken = false;
        for (int i = titanIdx; i < all.length; i++) {
            if (all[i].getOpcode() == org.objectweb.asm.Opcodes.IASTORE) {
                clinit.instructions.remove(all[i]);
                broken = true;
                break;
            }
        }
        assertThat(broken, is(true));

        SyntheticSweeper.cleanKotlinWhenMaps(ctx);

        assertThat(ctx.result.getErrors().isEmpty(), is(false));
        assertThat(String.join("\n", ctx.result.getErrors()), containsString("TITAN"));
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
