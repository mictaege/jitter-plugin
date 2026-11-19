package com.github.mictaege.jitter.plugin;

import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code @kotlin.Metadata} rewrite behind an isolated classloader:
 * kotlin-metadata-jvm (and only it) is loaded child first, so it can never
 * clash with a Kotlin toolchain of the build that applies the plugin - the
 * jar resolves lazily at strip time from the repositories of the applying
 * build.
 *
 * <p>The types crossing the loader boundary are the shared plugin types,
 * which load parent first; only the {@code org.jetbrains.kotlin.*} classes
 * and the {@link JvmKotlinMetadataRewriter} itself load child first.
 */
public final class IsolatedKotlinMetadataRewriter implements KotlinMetadataRewriter {

    private final URLClassLoader loader;
    private final Object delegate;

    /**
     * @param metadataJars the resolved files of kotlin-metadata-jvm,
     *        including its dependencies
     */
    public IsolatedKotlinMetadataRewriter(final List<File> metadataJars) throws IOException {
        final URL[] urls = new URL[metadataJars.size() + 1];
        try {
            for (int i = 0; i < metadataJars.size(); i++) {
                urls[i] = metadataJars.get(i).toURI().toURL();
            }
            urls[metadataJars.size()] = pluginJar();
        } catch (final java.net.MalformedURLException e) {
            throw new IOException("kotlin-metadata-jvm is not resolvable as URLs", e);
        }
        this.loader = new ChildFirstClassLoader(urls, getClass().getClassLoader());
        try {
            final Class<?> type = Class.forName(
                    JvmKotlinMetadataRewriter.class.getName(), false, loader);
            this.delegate = type.getConstructor().newInstance();
        } catch (final ReflectiveOperationException e) {
            loader.close();
            throw new IOException("the kotlin metadata rewriter could not be loaded"
                    + " from the isolated classloader", e);
        }
    }

    @Override
    public void rewrite(final Map<String, ClassNode> nodes, final StripResult result,
                        final Set<String> dirty) throws IOException {
        invoke("rewrite", new Class<?>[]{Map.class, StripResult.class, Set.class},
                new Object[]{nodes, result, dirty});
    }

    @Override
    public void close() {
        try {
            invoke("close", new Class<?>[0], new Object[0]);
        } catch (final IOException e) {
            // closing the isolated rewriter is best effort
        } finally {
            try {
                loader.close();
            } catch (final IOException ignored) {
                // the classloader is best effort closed
            }
        }
    }

    private void invoke(final String method, final Class<?>[] types, final Object[] args)
            throws IOException {
        try {
            final Method callable = delegate.getClass().getMethod(method, types);
            callable.invoke(delegate, args);
        } catch (final InvocationTargetException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw new IOException(e.getCause());
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("the kotlin metadata rewriter api is inconsistent", e);
        }
    }

    private URL pluginJar() throws java.net.MalformedURLException {
        return getClass().getProtectionDomain().getCodeSource().getLocation();
    }

    /**
     * Child first for the kotlin classes and the worker itself, parent first
     * for everything else, so the shared plugin types stay identical across
     * the loader boundary.
     */
    private static final class ChildFirstClassLoader extends URLClassLoader {
        private ChildFirstClassLoader(final URL[] urls, final ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve)
                throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null && childFirst(name)) {
                    try {
                        found = findClass(name);
                    } catch (final ClassNotFoundException ignored) {
                        // falls back to the parent
                    }
                }
                if (found == null) {
                    found = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
        }

        private boolean childFirst(final String name) {
            return name.startsWith("org.jetbrains.kotlin.")
                    || name.startsWith(JvmKotlinMetadataRewriter.class.getName());
        }
    }

}
