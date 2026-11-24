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
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class GateRemoverTest {

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
    void removesGateBlockOfInactiveFlavour() throws Exception {
        final Path classes = compile("test/Gated.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Gate;\n"
                        + "public class Gated {\n"
                        + "  public int value = 1;\n"
                        + "  public void apply() {\n"
                        + "    Gate.onlyIf(java.util.List.of(\"NASA\"), () -> value = 2);\n"
                        + "  }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        final ClassNode gated = read(classes, "test/Gated");
        assertThat("gate lambda removed",
                method(gated, "lambda$apply$0", null), is(nullValue()));
        assertThat("no gate call remains",
                callsGate(gated, "apply"), is(false));
        assertThat("removed lambda recorded in manifest",
                result.getRemovedMembers().stream()
                        .anyMatch(k -> k.startsWith("test/Gated.lambda$apply$0")), is(true));
    }

    @Test
    void keepsGateBlockOfActiveFlavour() throws Exception {
        final Path classes = compile("test/Gated.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Gate;\n"
                        + "public class Gated {\n"
                        + "  public int value = 1;\n"
                        + "  public void apply() {\n"
                        + "    Gate.onlyIf(java.util.List.of(\"ESA\"), () -> value = 2);\n"
                        + "  }\n"
                        + "}\n");

        new BytecodeStripper().strip(classes);

        final ClassNode gated = read(classes, "test/Gated");
        assertThat(method(gated, "lambda$apply$0", null), is(not(nullValue())));
        assertThat(callsGate(gated, "apply"), is(true));
    }

    @Test
    void verificationToleratesReferencesInsideRemovedGateBlocks() throws Exception {
        final Path classes = compile(
                "test/NasaOnly.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "@OnlyIf(\"NASA\")\n"
                        + "public class NasaOnly { }\n",
                "test/Gated.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.Gate;\n"
                        + "public class Gated {\n"
                        + "  public void apply() {\n"
                        + "    Gate.onlyIf(java.util.List.of(\"NASA\"), () -> new NasaOnly());\n"
                        + "  }\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        final List<String> dangling = ReferenceVerifier.findDanglingReferences(
                java.util.Set.of(classes.toFile()), result.getRemovedClasses(),
                result.getRemovedMembers());
        assertThat("no dangling references from the removed gate block",
                dangling.isEmpty(), is(true));
    }

    private static boolean callsGate(final ClassNode node, final String methodName) {
        final MethodNode method = method(node, methodName, null);
        if (method == null) {
            return false;
        }
        for (int i = 0; i < method.instructions.size(); i++) {
            final org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.get(i);
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode
                    && "com/github/mictaege/jitter/api/Gate"
                            .equals(((org.objectweb.asm.tree.MethodInsnNode) insn).owner)) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode method(final ClassNode node, final String name, final String desc) {
        return node.methods.stream()
                .filter(m -> m.name.equals(name) && (desc == null || m.desc.equals(desc)))
                .findFirst().orElse(null);
    }

    private ClassNode read(final Path classesDir, final String internalName) throws IOException {
        final Path file = classesDir.resolve(internalName + ".class");
        final ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(file)).accept(node, 0);
        return node;
    }

    private Path compile(final String... nameAndSourcePairs) throws IOException {
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
                    .map(Path::toString).collect(Collectors.toList());
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

}
