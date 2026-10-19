package com.github.mictaege.jitter.plugin;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;

/**
 * The shared state of one stripping run: the class model under processing,
 * the outcome collected so far, and the cross phase bookkeeping.
 */
final class StripContext {

    /** All classes of the source sets under processing, by internal name. */
    final Map<String, ClassNode> nodes = new HashMap<>();

    /** The file each class was loaded from, by internal name. */
    final Map<String, Path> paths = new HashMap<>();

    /** The classes whose bytecode was modified and must be serialized again. */
    final Set<String> dirty = new HashSet<>();

    /** The outcome of the stripping so far. */
    final StripResult result = new StripResult();

    /** Classpath entries for {@code @Alter} templates that live in dependent libraries. */
    final List<File> templateClasspath;

    /**
     * The names of all flavours of the project, the configuration the
     * {@code @OnlyIf} gates refer to. The constant audit uses them to tell
     * a flavour-variant gate (does not cover all flavours: the constant is
     * missing in at least one flavour) from a gate that covers everything.
     */
    final List<String> flavours;

    /**
     * The lambda implementations removed together with their owners, by owner
     * internal name - the input of the {@code $deserializeLambda$} cleanup.
     */
    final Map<String, List<String[]>> removedLambdas = new HashMap<>();

    StripContext(final List<File> templateClasspath) {
        this(templateClasspath, java.util.Collections.emptyList());
    }

    StripContext(final List<File> templateClasspath, final List<String> flavours) {
        this.templateClasspath = templateClasspath;
        this.flavours = flavours;
    }

}
