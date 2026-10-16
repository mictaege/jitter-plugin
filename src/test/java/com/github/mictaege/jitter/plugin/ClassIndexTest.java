package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class ClassIndexTest extends StripperTestFixture {

    @Test
    void shouldDeriveTheInternalNameOfAClassFile() {
        final Path dir = workDir.resolve("classes");
        final Path file = dir.resolve("com/example/Outer$Inner.class");

        assertThat(ClassIndex.toInternalName(dir, file), is("com/example/Outer$Inner"));
    }

    @Test
    void shouldLoadTheClassesOfADirectory() throws Exception {
        final Path classes = compile("test/One.java",
                "package test;\n"
                        + "public class One { }\n",
                "test/Two.java",
                "package test;\n"
                        + "public class Two { }\n");

        final Map<String, ClassNode> nodes = new java.util.HashMap<>();
        final Map<String, Path> paths = new java.util.HashMap<>();
        ClassIndex.loadClasses(classes, nodes, paths, new StripResult());

        assertThat(nodes.containsKey("test/One"), is(true));
        assertThat(nodes.containsKey("test/Two"), is(true));
        assertThat(paths.get("test/One"), is(classes.resolve("test/One.class")));
    }

    @Test
    void shouldIgnoreMissingDirectories() throws IOException {
        final Map<String, ClassNode> nodes = new java.util.HashMap<>();
        final Map<String, Path> paths = new java.util.HashMap<>();

        ClassIndex.loadClasses(workDir.resolve("nope"), nodes, paths, new StripResult());

        assertThat(nodes.isEmpty(), is(true));
    }

    @Test
    void shouldLoadTemplatesFromDirectoryClasspathEntries() throws Exception {
        final Path classes = compile("test/Template.java",
                "package test;\n"
                        + "public class Template { }\n");

        final ClassNode loaded = ClassIndex.loadFromClasspath(
                List.of(classes.toFile()), "test/Template", new StripResult());

        assertThat(loaded.name, is("test/Template"));
        assertThat(ClassIndex.loadFromClasspath(List.of(classes.toFile()), "test/Missing", new StripResult()), is(nullValue()));
    }

    @Test
    void shouldLoadTemplatesFromJarClasspathEntries() throws Exception {
        final Path classes = compile("test/Template.java",
                "package test;\n"
                        + "public class Template { }\n");

        final Path jar = workDir.resolve("lib.jar");
        try (final JarOutputStream zip = new JarOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new JarEntry("test/Template.class"));
            zip.write(Files.readAllBytes(classes.resolve("test/Template.class")));
            zip.closeEntry();
        }

        final ClassNode loaded = ClassIndex.loadFromClasspath(
                List.of(jar.toFile()), "test/Template", new StripResult());

        assertThat(loaded.name, is("test/Template"));
        assertThat(ClassIndex.loadFromClasspath(List.of(jar.toFile()), "test/Missing", new StripResult()), is(nullValue()));
        assertThat(ClassIndex.loadFromClasspath(null, "test/Template", new StripResult()), is(nullValue()));
    }

}
