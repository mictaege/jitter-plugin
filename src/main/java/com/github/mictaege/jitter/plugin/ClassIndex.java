package com.github.mictaege.jitter.plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Loads the class model of a stripping run: the classes of the source set
 * output directories, and {@code @Alter} templates from the classpath.
 */
final class ClassIndex {

    private ClassIndex() {
        super();
    }

    static void loadClasses(final Path classesDir, final Map<String, ClassNode> nodes,
                            final Map<String, Path> paths) throws IOException {
        if (!Files.isDirectory(classesDir)) {
            return;
        }
        Files.walkFileTree(classesDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                final String name = file.getFileName().toString();
                if (name.endsWith(".class")) {
                    final String internalName = toInternalName(classesDir, file);
                    try {
                        final ClassNode node = new ClassNode();
                        new ClassReader(Files.readAllBytes(file)).accept(node, 0);
                        nodes.put(internalName, node);
                        paths.put(internalName, file);
                    } catch (final IOException e) {
                        JitterUtil.log().error("Could not read class file " + file + ": " + e.getMessage());
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static String toInternalName(final Path classesDir, final Path file) {
        final Path relative = classesDir.relativize(file);
        final String slashed = relative.toString().replace(File.separatorChar, '/');
        return slashed.substring(0, slashed.length() - ".class".length());
    }

    /**
     * Loads the given class from the given classpath entries, or null if no
     * entry contains it. Used for {@code @Alter} templates that live in a
     * dependent library instead of the stripped classes themselves.
     */
    static ClassNode loadFromClasspath(final List<File> classpath, final String internalName) {
        if (classpath == null) {
            return null;
        }
        for (final File entry : classpath) {
            try {
                final byte[] bytes;
                if (entry.isDirectory()) {
                    final Path file = entry.toPath().resolve(
                            internalName.replace('/', File.separatorChar) + ".class");
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    bytes = Files.readAllBytes(file);
                } else {
                    try (final ZipFile zip = new ZipFile(entry)) {
                        final ZipEntry e = zip.getEntry(internalName + ".class");
                        if (e == null) {
                            continue;
                        }
                        try (final InputStream in = zip.getInputStream(e)) {
                            bytes = in.readAllBytes();
                        }
                    }
                }
                final ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, 0);
                return node;
            } catch (final IOException e) {
                JitterUtil.log().warn("Could not read " + internalName + " from classpath entry " + entry + ": "
                        + e.getMessage());
            }
        }
        return null;
    }

}
