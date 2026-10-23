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
                            final Map<String, Path> paths, final StripResult result) throws IOException {
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
                        result.error("Could not read class file " + file + ": " + e.getMessage());
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
    static ClassNode loadFromClasspath(final List<File> classpath, final String internalName,
                                        final StripResult result) {
        final File entry = entryOf(classpath, internalName);
        if (entry == null) {
            return null;
        }
        try {
            final byte[] bytes = readFromEntry(entry, internalName);
            if (bytes == null) {
                return null;
            }
            final ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            return node;
        } catch (final IOException e) {
            result.warn("Could not read " + internalName + " from classpath entry "
                    + entry + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * The first classpath entry that contains the given class, or null -
     * for lookups that must stay within the declaring artifact, like the
     * package gate of a class in a split package.
     */
    static File entryOf(final List<File> classpath, final String internalName) {
        if (classpath == null) {
            return null;
        }
        for (final File entry : classpath) {
            try {
                if (entry.isDirectory()) {
                    final Path file = entry.toPath().resolve(
                            internalName.replace('/', File.separatorChar) + ".class");
                    if (Files.isRegularFile(file)) {
                        return entry;
                    }
                } else {
                    try (final ZipFile zip = new ZipFile(entry)) {
                        if (zip.getEntry(internalName + ".class") != null) {
                            return entry;
                        }
                    }
                }
            } catch (final IOException e) {
                // unreadable entries are skipped, like in loadFromClasspath
            }
        }
        return null;
    }

    /**
     * The bytecode of the given class from one specific classpath entry,
     * or null if the entry does not contain it.
     */
    static byte[] readFromEntry(final File entry, final String internalName) throws IOException {
        if (entry.isDirectory()) {
            final Path file = entry.toPath().resolve(
                    internalName.replace('/', File.separatorChar) + ".class");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Files.readAllBytes(file);
        }
        try (final ZipFile zip = new ZipFile(entry)) {
            final ZipEntry e = zip.getEntry(internalName + ".class");
            if (e == null) {
                return null;
            }
            try (final InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
    }

}
