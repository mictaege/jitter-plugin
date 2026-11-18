package com.github.mictaege.jitter.plugin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Kotlin fixture helpers for the metadata stripping tests: compiles Kotlin
 * sources in-process with the embeddable compiler (a test dependency) and
 * reads the compiled classes plus their {@code @kotlin.Metadata} annotation
 * with plain ASM.
 */
final class KotlinFixtures {

    static final String METADATA_DESC = "Lkotlin/Metadata;";

    private KotlinFixtures() {
        super();
    }

    /**
     * Compiles the given name/source pairs of Kotlin files into
     * {@code workDir/kt-classes} and returns the classes directory. The test
     * classpath serves as compilation classpath (jitter-api and the Kotlin
     * stdlib are on it).
     */
    static Path compileKotlin(final Path workDir, final String... nameAndSourcePairs) throws IOException {
        return compileKotlin(workDir, null, nameAndSourcePairs);
    }

    /**
     * Same as {@link #compileKotlin(Path, String...)}, with an additional
     * classpath entry - for fixtures that compile against a previously
     * compiled dependency module.
     */
    static Path compileKotlin(final Path workDir, final Path extraClasspath,
                              final String... nameAndSourcePairs) throws IOException {
        if (nameAndSourcePairs.length % 2 != 0) {
            throw new IllegalArgumentException("expected name/source pairs");
        }
        final Path sources = Files.createDirectories(workDir.resolve("kt-sources"));
        final Path classes = Files.createDirectories(workDir.resolve("kt-classes"));
        final List<String> ktFiles = new ArrayList<>();
        for (int i = 0; i < nameAndSourcePairs.length; i += 2) {
            final Path file = sources.resolve(nameAndSourcePairs[i]);
            Files.createDirectories(file.getParent());
            Files.write(file, nameAndSourcePairs[i + 1].getBytes(StandardCharsets.UTF_8));
            ktFiles.add(file.toString());
        }
        final List<String> options = new ArrayList<>();
        options.add("-cp");
        options.add(System.getProperty("java.class.path")
                + (extraClasspath == null ? "" : ":" + extraClasspath));
        options.add("-d");
        options.add(classes.toString());
        options.add("-jvm-target");
        options.add("21");
        options.addAll(ktFiles);
        final org.jetbrains.kotlin.cli.common.ExitCode exit =
                new K2JVMCompiler().exec(System.err, options.toArray(new String[0]));
        assertThat("kotlin fixture compilation failed",
                exit, is(org.jetbrains.kotlin.cli.common.ExitCode.OK));
        return classes;
    }

    static ClassNode read(final Path classesDir, final String internalName) throws IOException {
        final Path file = classesDir.resolve(internalName + ".class");
        assertThat("class file " + internalName + " does not exist", Files.exists(file), is(true));
        final ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(file)).accept(node, 0);
        return node;
    }

    /** The {@code @kotlin.Metadata} annotation of the class, or null. */
    static AnnotationNode metadataOf(final ClassNode node) {
        if (node.visibleAnnotations == null) {
            return null;
        }
        for (final AnnotationNode annotation : node.visibleAnnotations) {
            if (METADATA_DESC.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    /** The {@code d2} string table of a {@code @kotlin.Metadata} annotation. */
    @SuppressWarnings("unchecked")
    static List<String> d2Of(final AnnotationNode metadata) {
        if (metadata == null || metadata.values == null) {
            return List.of();
        }
        for (int i = 0; i + 1 < metadata.values.size(); i += 2) {
            if ("d2".equals(metadata.values.get(i))) {
                return ((List<Object>) metadata.values.get(i + 1)).stream()
                        .map(String::valueOf).collect(Collectors.toList());
            }
        }
        return List.of();
    }

    static byte[] bytes(final Path classesDir, final String internalName) throws IOException {
        return Files.readAllBytes(classesDir.resolve(internalName + ".class"));
    }
}
