package com.github.mictaege.jitter.plugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Kotlin dejittering behind an isolated classloader: the kotlin
 * compiler embeddable (and only it) is loaded child first, so it can never
 * clash with a Kotlin toolchain of the build that applies the plugin - the
 * jar resolves lazily at dejitter time from the repositories of the
 * applying build.
 *
 * <p>The types crossing the loader boundary are the shared plugin types,
 * which load parent first; only the {@code org.jetbrains.kotlin.*} classes
 * and the {@link KotlinDejitterer} itself load child first.
 */
public final class IsolatedKotlinDejitter implements KotlinDejitter {

    private final URLClassLoader loader;
    private final Object delegate;

    /**
     * @param embeddableJars the resolved files of the kotlin compiler
     *        embeddable, including its dependencies
     */
    IsolatedKotlinDejitter(final String activeFlavour, final Set<Path> sourceDirs,
                           final Path outputDir, final List<File> compileClasspath,
                           final Map<File, List<File>> externalTemplateDirs,
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
                    KotlinDejitterer.class.getName(), false, loader);
            this.delegate = type
                    .getConstructor(String.class, Set.class, Path.class, List.class, Map.class)
                    .newInstance(activeFlavour, sourceDirs, outputDir, compileClasspath,
                            externalTemplateDirs);
        } catch (final ReflectiveOperationException e) {
            loader.close();
            throw new IOException("the kotlin dejitterer could not be loaded"
                    + " from the isolated classloader", e);
        }
    }

    @Override
    public void dejitter(final Path file, final StripResult result, final Set<Path> deletedFiles)
            throws IOException {
        invoke("dejitter", new Class<?>[]{Path.class, StripResult.class, Set.class},
                new Object[]{file, result, deletedFiles});
    }

    @Override
    public void recordPackageRemoval(final Path file, final StripResult result) throws IOException {
        invoke("recordPackageRemoval", new Class<?>[]{Path.class, StripResult.class},
                new Object[]{file, result});
    }

    @Override
    public void close() {
        try {
            invoke("close", new Class<?>[0], new Object[0]);
        } catch (final IOException e) {
            // closing the isolated kotlin dejitterer is best effort
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
            throw new IllegalStateException("the kotlin dejitterer api is inconsistent", e);
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
                    || name.startsWith(KotlinDejitterer.class.getName());
        }
    }

}
