package com.github.mictaege.jitter.plugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves compile time constants of annotation values, like
 * {@code @OnlyIf(Flavour.MPK)}: javac and kotlinc would bake them into the
 * bytecode, the source level dejittering resolves them by reflecting the
 * referenced constants class from the compile classpath of the source set.
 *
 * <p>The unresolvable case falls back to the plain field name so that it
 * stays visible in the result.
 */
public final class ClasspathConstants implements AutoCloseable {

    private final List<File> compileClasspath;
    private URLClassLoader loader;

    public ClasspathConstants(final List<File> compileClasspath) {
        this.compileClasspath = compileClasspath == null ? List.of() : compileClasspath;
    }

    /**
     * Resolves the constant of the given scope expression, e.g.
     * {@code Flavour.MPK}: the scope names a class, derived from the imports
     * and the package of the referencing file, the field names the constant.
     *
     * @param imports the fully qualified imports of the referencing file,
     *        asterisk imports ending in {@code .*}
     * @param pkg the package of the referencing file, empty for the
     *        default package
     */
    public String resolve(final String scope, final String field,
                   final List<String> imports, final String pkg) {
        for (final String candidate : classCandidates(scope, imports, pkg)) {
            final String value = reflect(candidate, field);
            if (value != null) {
                return value;
            }
        }
        // no reflection possible: the plain field name is the best guess,
        // the manifest cross check will report if it guessed wrong
        return field;
    }

    private List<String> classCandidates(final String scope, final List<String> imports,
                                         final String pkg) {
        final List<String> candidates = new ArrayList<>();
        if (scope.isEmpty()) {
            return candidates;
        }
        if (scope.indexOf('.') >= 0) {
            candidates.add(scope);
            candidates.add(scope.substring(0, scope.lastIndexOf('.'))
                    + "$" + scope.substring(scope.lastIndexOf('.') + 1));
        } else {
            for (final String imp : imports) {
                if (imp.endsWith(".*")) {
                    candidates.add(imp.substring(0, imp.length() - 1) + scope);
                } else if (imp.endsWith("." + scope)) {
                    candidates.add(imp);
                }
            }
            candidates.add((pkg.isEmpty() ? "" : pkg + ".") + scope);
        }
        return candidates;
    }

    private String reflect(final String className, final String fieldName) {
        try {
            final Class<?> type = Class.forName(className, false, loader());
            final Field field = type.getDeclaredField(fieldName);
            if (field.getType() == String.class
                    && (field.getModifiers() & Modifier.STATIC) != 0) {
                return (String) field.get(null);
            }
        } catch (final ReflectiveOperationException | LinkageError ignored) {
            // not found on the classpath - the next candidate is tried
        }
        return null;
    }

    private synchronized URLClassLoader loader() {
        if (loader == null) {
            final List<URL> urls = new ArrayList<>();
            for (final File file : compileClasspath) {
                try {
                    urls.add(file.toURI().toURL());
                } catch (final MalformedURLException ignored) {
                    // a classpath entry that is no URL is skipped
                }
            }
            loader = new URLClassLoader(urls.toArray(new URL[0]),
                    getClass().getClassLoader());
        }
        return loader;
    }

    @Override
    public synchronized void close() {
        if (loader != null) {
            try {
                loader.close();
            } catch (final IOException ignored) {
                // the constants classloader is best effort closed
            }
            loader = null;
        }
    }

}
