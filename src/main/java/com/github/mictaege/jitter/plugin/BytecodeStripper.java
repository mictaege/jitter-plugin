package com.github.mictaege.jitter.plugin;

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
        final StripContext ctx = new StripContext(templateClasspath, flavours);

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
        SyntheticSweeper.cleanNestAttributes(ctx);
        // the paranoia scan: no placeholder anchor survives in the code
        AlterConstApplier.verifyNoPlaceholdersLeft(ctx);
        AnnotationCleaner.clean(ctx);

        ClassFileWriter.writeBack(sourceDirs, targetDir, ctx);
        return ctx.result;
    }

}
