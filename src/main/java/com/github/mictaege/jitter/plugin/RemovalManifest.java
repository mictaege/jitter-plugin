package com.github.mictaege.jitter.plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The flavour removal manifest of one stripped source set: the classes and
 * members the stripping removed in the active flavour.
 *
 * <p>The manifest is written into the stripped tree as
 * {@code META-INF/jitter/<srcSet>-removed.txt}, so it travels inside the
 * stripped runtime artifacts ({@code jitterJar}, {@code jitterTestFixturesJar}).
 * A consuming module reads the manifests of its flavour dependencies from the
 * runtime classpath and verifies that its own surviving classes do not
 * reference the removed classes or members - the cross module consistency
 * check that the source transformation got for free, because dependent
 * sources were compiled against the already transformed sources.
 *
 * <p>Line format: {@code C <internalName>} for a removed class,
 * {@code M <owner>.<name><descriptor>} for a removed member. Lines starting
 * with {@code #} are comments.
 */
final class RemovalManifest {

    static final String DIR = "META-INF/jitter/";
    static final String SUFFIX = "-removed.txt";

    private RemovalManifest() {
        super();
    }

    static void write(final Path target, final String flavour, final StripResult result) throws IOException {
        final StringBuilder text = new StringBuilder();
        text.append("# jitter flavour removals of flavour '").append(flavour).append("'\n");
        text.append("# C <class> / M <member> of the stripped model\n");
        result.getRemovedClasses().forEach(c -> text.append("C ").append(c).append('\n'));
        result.getRemovedMembers().forEach(m -> text.append("M ").append(m).append('\n'));
        Files.createDirectories(target.getParent());
        Files.write(target, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads the given manifest file directly into the given removal sets -
     * the manifest of a strip task's output directory.
     */
    static void readFile(final File manifest, final Set<String> removedClasses,
                         final Set<String> removedMembers) throws IOException {
        if (manifest == null || !manifest.isFile()) {
            return;
        }
        parse(manifest.toPath(), removedClasses, removedMembers);
    }

    /**
     * Reads all manifests found in the given classpath entry - a directory
     * (classes tree) or a jar - into the given removal sets.
     */
    static void read(final File entry, final Set<String> removedClasses, final Set<String> removedMembers)
            throws IOException {
        if (entry == null || !entry.exists()) {
            return;
        }
        if (entry.isDirectory()) {
            final File dir = new File(entry, DIR);
            final File[] manifests = dir == null ? null : dir.listFiles(
                    (d, name) -> name.endsWith(SUFFIX));
            if (manifests == null) {
                return;
            }
            for (final File manifest : manifests) {
                parse(manifest.toPath(), removedClasses, removedMembers);
            }
        } else if (entry.getName().endsWith(".jar")) {
            try (ZipFile zip = new ZipFile(entry)) {
                final Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    final ZipEntry entry2 = entries.nextElement();
                    if (entry2.getName().startsWith(DIR) && entry2.getName().endsWith(SUFFIX)) {
                        try (InputStream in = zip.getInputStream(entry2)) {
                            parse(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                                    removedClasses, removedMembers);
                        }
                    }
                }
            }
        }
    }

    private static void parse(final Path file, final Set<String> removedClasses,
                              final Set<String> removedMembers) throws IOException {
        parse(Files.readString(file, StandardCharsets.UTF_8), removedClasses, removedMembers);
    }

    private static void parse(final String text, final Set<String> removedClasses,
                             final Set<String> removedMembers) {
        for (final String line : text.split("\n")) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("C ")) {
                removedClasses.add(trimmed.substring(2).trim());
            } else if (trimmed.startsWith("M ")) {
                removedMembers.add(trimmed.substring(2).trim());
            }
        }
    }

}
