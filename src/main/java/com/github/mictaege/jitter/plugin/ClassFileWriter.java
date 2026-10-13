package com.github.mictaege.jitter.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * Writes the stripped model back: either in place, over the compiled
 * classes directories, or as a complete stripped tree in a target
 * directory - the compiled classes stay untouched then.
 */
final class ClassFileWriter {

    private ClassFileWriter() {
        super();
    }

    static void writeBack(final List<Path> sourceDirs, final Path targetDir, final StripContext ctx)
            throws IOException {
        if (targetDir == null) {
            writeBackInPlace(sourceDirs, ctx);
        } else {
            writeStrippedTree(targetDir, ctx);
        }
        ctx.result.getInfos().forEach(msg -> JitterUtil.log().info(msg));
    }

    private static void writeBackInPlace(final List<Path> classesDirs, final StripContext ctx)
            throws IOException {
        for (final String removed : ctx.result.getRemovedClasses()) {
            final Path file = ctx.paths.get(removed);
            if (file != null) {
                Files.deleteIfExists(file);
                final Path root = rootOf(classesDirs, file);
                if (root != null) {
                    deleteEmptyParents(root, file.getParent());
                }
            }
        }
        for (final String name : ctx.dirty) {
            final ClassNode node = ctx.nodes.get(name);
            if (node == null || !ctx.paths.containsKey(name)) {
                continue;
            }
            final ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            final Path file = ctx.paths.get(name);
            Files.createDirectories(file.getParent());
            Files.write(file, writer.toByteArray());
        }
    }

    private static Path rootOf(final List<Path> classesDirs, final Path file) {
        Path best = null;
        for (final Path dir : classesDirs) {
            if (file.startsWith(dir) && (best == null || dir.toString().length() > best.toString().length())) {
                best = dir;
            }
        }
        return best;
    }

    /**
     * Writes the complete stripped model into the target directory: dirty
     * nodes serialized, untouched classes copied, removed classes left out.
     * The source directories stay as they are.
     */
    private static void writeStrippedTree(final Path targetDir, final StripContext ctx)
            throws IOException {
        for (final java.util.Map.Entry<String, ClassNode> entry : ctx.nodes.entrySet()) {
            final String name = entry.getKey();
            final Path file = targetDir.resolve(
                    name.replace('/', java.io.File.separatorChar) + ".class");
            Files.createDirectories(file.getParent());
            if (ctx.dirty.contains(name)) {
                final ClassWriter writer = new ClassWriter(0);
                entry.getValue().accept(writer);
                Files.write(file, writer.toByteArray());
            } else {
                final Path source = ctx.paths.get(name);
                Files.copy(source, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteEmptyParents(final Path root, Path dir) throws IOException {
        dir = dir.normalize();
        while (dir != null && dir.startsWith(root) && !dir.equals(root)) {
            try (Stream<Path> content = Files.list(dir)) {
                if (content.findAny().isPresent()) {
                    break;
                }
            }
            Files.delete(dir);
            dir = dir.getParent();
        }
    }

}
