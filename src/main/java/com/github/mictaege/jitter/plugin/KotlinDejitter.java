package com.github.mictaege.jitter.plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * The Kotlin dejittering of one dejittering run, implemented by the
 * {@link KotlinDejitterer} itself when the Kotlin PSI is on the classpath
 * (like in the tests) and by the {@link IsolatedKotlinDejitter} bridge when
 * the Kotlin PSI is resolved lazily and loaded in an isolated classloader.
 */
public interface KotlinDejitter extends AutoCloseable {

    /**
     * Dejitters one Kotlin file into the output directory, mirroring the
     * relative path. Records the removals and kept classes of the file like
     * the Java dejittering does. Returns without writing anything when the
     * active flavour removes the file as a whole.
     */
    void dejitter(Path file, StripResult result, Set<Path> deletedFiles) throws IOException;

    /**
     * Records the top level classes of a Kotlin file of a flavour removed
     * package as removed classes, mirroring the bytecode removals of the
     * whole package.
     */
    void recordPackageRemoval(Path file, StripResult result) throws IOException;

    @Override
    void close();

}
