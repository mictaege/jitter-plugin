package com.github.mictaege.jitter.plugin;

import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites the {@code @kotlin.Metadata} of stripped Kotlin classes so the
 * metadata matches the stripped bytecode: removed declarations are dropped
 * from the metadata model and the rebuilt {@code d2} string table loses the
 * strings nothing references anymore.
 *
 * <p>In production the rewriter loads kotlin-metadata-jvm in an isolated
 * classloader ({@link IsolatedKotlinMetadataRewriter}) - the library is no
 * runtime dependency of the plugin. In the tests it is on the classpath and
 * {@link JvmKotlinMetadataRewriter} runs directly.
 */
public interface KotlinMetadataRewriter extends AutoCloseable {

    /**
     * Rewrites the metadata of every affected class in place.
     *
     * @param nodes all classes of the stripping run, by internal name
     * @param result the removals recorded so far
     * @param dirty the classes whose bytecode was modified and must be
     *        serialized again
     */
    void rewrite(Map<String, ClassNode> nodes, StripResult result, Set<String> dirty) throws IOException;

    @Override
    void close();
}
