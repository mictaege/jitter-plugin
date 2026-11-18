package com.github.mictaege.jitter.plugin;

import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Strips the 150% model from compiled classes of one source set output
 * directory, so that only the members of the currently active flavour
 * survive.
 *
 * <p>The stripping works purely on the class files. The sources and their
 * compilation are not touched, the line number tables of the surviving
 * code are kept unchanged. The operations correspond to the semantics of
 * the jitter annotations:
 *
 * <ul>
 *   <li>{@code @OnlyIf}: remove class, package, field, constructor or method
 *       if none of the given flavours is active.
 *   <li>{@code @Fork}: replace the body of the annotated method with the body
 *       of the referenced template method and remove the template.
 *   <li>{@code @Alter}: replace the annotated class with the referenced template
 *       class and remove the template.
 * </ul>
 *
 * <p>In addition the jitter annotations are removed from the surviving
 * bytecode. The phases run in a fixed order and share their state via
 * {@link StripContext}: each phase is implemented by one collaborator of
 * this package.
 */
public final class BytecodeStripper {

    private List<File> kotlinMetadataJars = List.of();

    /**
     * @param kotlinMetadataJars the resolved files of kotlin-metadata-jvm,
     *        including its dependencies: the {@code @kotlin.Metadata} rewrite
     *        of stripped Kotlin classes loads it in an isolated classloader.
     *        Without jars the library must already be on the classpath, like
     *        in the tests.
     */
    public void setKotlinMetadataJars(final List<File> kotlinMetadataJars) {
        this.kotlinMetadataJars = kotlinMetadataJars == null ? List.of() : kotlinMetadataJars;
    }

    /**
     * Strips all class files of the given directory (in place) and
     * reports what has been removed.
     */
    public StripResult strip(final Path classesDir) throws IOException {
        return strip(classesDir, null, java.util.Collections.emptyList());
    }

    /**
     * Strips all class files of the given directory (in place) and reports
     * what has been removed. The given classpath is only used to resolve
     * {@code @Alter} templates that live outside the stripped classes, e.g.
     * in a dependent library.
     */
    public StripResult strip(final Path classesDir, final List<java.io.File> templateClasspath)
            throws IOException {
        return strip(classesDir, null, templateClasspath);
    }

    /**
     * Strips all class files of the given source directory and reports what
     * has been removed. With a target directory the source stays untouched:
     * the stripped model is written to the target instead (all surviving
     * classes, i.e. a complete classes tree), so that the full model can
     * remain available for compilation while the target serves packaging
     * and runtime. Without a target directory the source is stripped in
     * place.
     */
    public StripResult strip(final Path sourceDir, final Path targetDir,
                             final List<java.io.File> templateClasspath)
            throws IOException {
        return strip(java.util.Collections.singletonList(sourceDir), targetDir, templateClasspath);
    }

    public StripResult strip(final List<Path> sourceDirs, final Path targetDir,
                             final List<java.io.File> templateClasspath)
            throws IOException {
        return strip(sourceDirs, targetDir, templateClasspath, java.util.Collections.emptyList());
    }

    /**
     * Strips all class files of the given source directories as ONE unit
     * and reports what has been removed. A source set of a mixed Java/Kotlin
     * project compiles into several classes directories: the flavour gates
     * of a package (its package-info), its classes and their cross
     * references span all of them, so the directories must be stripped
     * together - a per-directory strip would not see the gates of the
     * others. With a target directory the sources stay untouched (see
     * above); without one every source is stripped in place.
     *
     * <p>The {@code flavours} are the names of all flavours of the project:
     * the constant audit tells a flavour-variant {@code @OnlyIf} gate
     * (fails the build, whatever flavour is active, unless the constant is
     * marked {@code @InlinedConstantApproved}) from a gate that covers all flavours
     * (harmless).
     */
    public StripResult strip(final List<Path> sourceDirs, final Path targetDir,
                             final List<java.io.File> templateClasspath,
                             final List<String> flavours)
            throws IOException {
        return strip(sourceDirs, targetDir, templateClasspath, flavours,
                java.util.Collections.emptySet());
    }

    /**
     * Same as {@link #strip(List, Path, List, List)}, with the members that
     * the flavour dependencies of the stripped module removed (read from
     * their removal manifests), as {@code owner.name+descriptor} keys. The
     * synthetic switch map / when map cleanup drops the entries of such
     * cross-module removed enum constants as well, so their names do not
     * leak into this module's stripped artifact.
     */
    public StripResult strip(final List<Path> sourceDirs, final Path targetDir,
                             final List<java.io.File> templateClasspath,
                             final List<String> flavours,
                             final java.util.Set<String> crossModuleRemovedMembers)
            throws IOException {
        final StripContext ctx = new StripContext(templateClasspath, flavours);
        if (crossModuleRemovedMembers != null) {
            ctx.crossModuleRemovedMembers.addAll(crossModuleRemovedMembers);
        }

        for (final Path dir : sourceDirs) {
            ClassIndex.loadClasses(dir, ctx.nodes, ctx.paths, ctx.result);
        }

        ConstantAuditor.audit(ctx);

        PackageRemover.remove(ctx);
        AlterApplier.applyAltclasses(ctx);
        ForkApplier.applyForkMethods(ctx);
        EnumRewriter.rewriteEnums(ctx);
        OnlyIfRemover.removeOnlyIf(ctx);
        OnlyIfRemover.restoreDefaultConstructors(ctx);
        InitializerRemover.removeFieldInitializers(ctx);
        // the flavour specific constant values, after the removals so
        // that only the constants visible in the active flavour are patched
        AlterConstApplier.apply(ctx);
        SyntheticSweeper.cleanDeserializeLambda(ctx);
        SyntheticSweeper.removeOrphanedSynthetics(ctx);
        SyntheticSweeper.cleanSwitchMaps(ctx);
        SyntheticSweeper.cleanKotlinWhenMaps(ctx);
        SyntheticSweeper.cleanNestAttributes(ctx);
        // the paranoia scan: no placeholder anchor survives in the code
        AlterConstApplier.verifyNoPlaceholdersLeft(ctx);
        AnnotationCleaner.clean(ctx);
        // the @kotlin.Metadata of stripped Kotlin classes must match the
        // stripped bytecode - rewritten after all removal phases
        rewriteKotlinMetadata(ctx);

        ClassFileWriter.writeBack(sourceDirs, targetDir, ctx);
        return ctx.result;
    }

    /**
     * Rewrites the {@code @kotlin.Metadata} of the stripped Kotlin classes so
     * the metadata matches the stripped bytecode. Skipped when no class
     * carries the annotation - builds without Kotlin sources pay nothing.
     */
    private void rewriteKotlinMetadata(final StripContext ctx) throws IOException {
        boolean anyKotlin = false;
        for (final ClassNode node : ctx.nodes.values()) {
            if (node.visibleAnnotations != null
                    && node.visibleAnnotations.stream()
                            .anyMatch(a -> "Lkotlin/Metadata;".equals(a.desc))) {
                anyKotlin = true;
                break;
            }
        }
        if (!anyKotlin) {
            return;
        }
        final KotlinMetadataRewriter rewriter = kotlinMetadataJars.isEmpty()
                ? new JvmKotlinMetadataRewriter()
                : new IsolatedKotlinMetadataRewriter(kotlinMetadataJars);
        try {
            rewriter.rewrite(ctx.nodes, ctx.result, ctx.dirty);
        } finally {
            rewriter.close();
        }
    }

}
