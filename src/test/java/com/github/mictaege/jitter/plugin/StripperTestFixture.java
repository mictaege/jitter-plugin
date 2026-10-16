package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Fixture base of the collaborator unit tests: compiles Java fixture
 * sources in memory (so that javac generates the real bytecode shapes the
 * strippers work on), loads them into a {@link StripContext} and offers
 * the node inspection helpers.
 */
abstract class StripperTestFixture {

    private static final String FLAVOUR_PROP = "jitter.active.flavour";

    Path workDir;

    @BeforeEach
    void setUp() throws IOException {
        System.setProperty(FLAVOUR_PROP, "ESA");
        workDir = Files.createTempDirectory(getClass().getSimpleName());
    }

    @AfterEach
    void resetFlavour() {
        System.clearProperty(FLAVOUR_PROP);
    }

    final Path compile(final String... nameAndSourcePairs) throws IOException {
        if (nameAndSourcePairs.length % 2 != 0) {
            throw new IllegalArgumentException("expected name/source pairs");
        }
        final Path sources = Files.createDirectories(workDir.resolve("sources"));
        final Path classes = Files.createDirectories(workDir.resolve("classes"));
        for (int i = 0; i < nameAndSourcePairs.length; i += 2) {
            final Path file = sources.resolve(nameAndSourcePairs[i]);
            Files.createDirectories(file.getParent());
            Files.write(file, nameAndSourcePairs[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final List<String> javaFiles;
        try (final Stream<Path> files = Files.walk(sources)) {
            javaFiles = files.filter(f -> f.toString().endsWith(".java"))
                    .map(Path::toString)
                    .collect(Collectors.toList());
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

    /** Loads the compiled classes into a fresh context. */
    final StripContext loadCtx(final Path classesDir) throws IOException {
        final StripContext ctx = new StripContext(null);
        ClassIndex.loadClasses(classesDir, ctx.nodes, ctx.paths, ctx.result);
        return ctx;
    }

    final ClassNode read(final Path classesDir, final String internalName) throws IOException {
        final Path file = classesDir.resolve(internalName + ".class");
        assertThat("class file " + internalName + " does not exist", Files.exists(file), is(true));
        final ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(file)).accept(node, 0);
        return node;
    }

    final ClassNode node(final StripContext ctx, final String internalName) {
        final ClassNode node = ctx.nodes.get(internalName);
        assertThat("class " + internalName + " not in the context", node != null, is(true));
        return node;
    }

    final FieldNode field(final ClassNode node, final String name) {
        final Optional<FieldNode> field =
                node.fields.stream().filter(f -> f.name.equals(name)).findFirst();
        return field.orElse(null);
    }

    final MethodNode method(final ClassNode node, final String name, final String desc) {
        final Optional<MethodNode> method = node.methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst();
        return method.orElse(null);
    }

    final String bytecode(final MethodNode method) {
        if (method == null || method.instructions == null) {
            return "";
        }
        final StringBuilder builder = new StringBuilder();
        method.instructions.forEach(insn -> {
            if (insn instanceof LdcInsnNode) {
                builder.append(((LdcInsnNode) insn).cst).append('\n');
            } else if (insn instanceof TypeInsnNode) {
                builder.append(((TypeInsnNode) insn).desc).append('\n');
            } else if (insn instanceof MethodInsnNode) {
                builder.append(((MethodInsnNode) insn).owner)
                        .append('#')
                        .append(((MethodInsnNode) insn).name)
                        .append('\n');
            } else if (insn instanceof FieldInsnNode) {
                builder.append(((FieldInsnNode) insn).owner)
                        .append('#')
                        .append(((FieldInsnNode) insn).name)
                        .append('\n');
            }
        });
        return builder.toString();
    }

}
