package com.github.mictaege.jitter.plugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * The Kotlin usage finder behind an isolated classloader: the kotlin
 * compiler embeddable (and only it) is loaded child first, so it can never
 * clash with a Kotlin toolchain of the build that applies the plugin - the
 * jar resolves lazily at verification time from the repositories of the
 * applying build.
 *
 * <p>The types crossing the loader boundary are the shared plugin types,
 * which load parent first; only the {@code org.jetbrains.kotlin.*} classes
 * and the {@link KotlinUsageFinder} itself load child first.
 */
public final class IsolatedKotlinUsageFinder implements AutoCloseable {

    private final URLClassLoader loader;
    private final Object delegate;

    /**
     * @param embeddableJars the resolved files of the kotlin compiler
     *        embeddable, including its dependencies
     */
    IsolatedKotlinUsageFinder(final List<File> classpath, final List<String> flavours,
                              final List<File> embeddableJars) throws IOException {
        final URL[] urls = new URL[embeddableJars.size() + 1];
        try {
            for (int i = 0; i < embeddableJars.size(); i++) {
                urls[i] = embeddableJars.get(i).toURI().toURL();
            }
            urls[embeddableJars.size()] = pluginJar();
        } catch (final java.net.MalformedURLException e) {
            throw new IOException("the kotlin compiler embeddable is not resolvable as URLs", e);
        }
        this.loader = new ChildFirstClassLoader(urls, getClass().getClassLoader());
        try {
            final Class<?> type = Class.forName(
                    KotlinUsageFinder.class.getName(), false, loader);
            this.delegate = type
                    .getConstructor(List.class, List.class)
                    .newInstance(classpath, flavours);
        } catch (final ReflectiveOperationException e) {
            loader.close();
            throw new IOException("the kotlin usage finder could not be loaded"
                    + " from the isolated classloader", e);
        }
    }

    /**
     * Verifies the given Kotlin files and returns the errors of the usage
     * check, in the format of the Java source check.
     */
    @SuppressWarnings("unchecked")
    public List<String> check(final List<Path> ktFiles) throws IOException {
        try {
            final Method callable = delegate.getClass()
                    .getMethod("check", new Class<?>[]{List.class});
            return (List<String>) callable.invoke(delegate, ktFiles);
        } catch (final InvocationTargetException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            }
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw new IOException(e.getCause());
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("the kotlin usage finder api is inconsistent", e);
        }
    }

    @Override
    public void close() {
        try {
            final Method callable = delegate.getClass()
                    .getMethod("close", new Class<?>[0]);
            callable.invoke(delegate);
        } catch (final ReflectiveOperationException | RuntimeException e) {
            // closing the isolated kotlin usage finder is best effort
        } finally {
            try {
                loader.close();
            } catch (final IOException ignored) {
                // the classloader is best effort closed
            }
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

        /**
         * Child first for the kotlin classes and the plugin package, parent
         * first for everything else. The plugin package is part of the child
         * side because the worker shares package private collaborators
         * (ClassIndex, JitterAnnotations, StripResult) - they must live in
         * the same classloader as the worker, and the api of the worker uses
         * JDK types only, so no plugin type crosses the loader boundary.
         */
        private boolean childFirst(final String name) {
            return name.startsWith("org.jetbrains.kotlin.")
                    || name.startsWith("com.github.mictaege.jitter.plugin.");
        }
    }

}
