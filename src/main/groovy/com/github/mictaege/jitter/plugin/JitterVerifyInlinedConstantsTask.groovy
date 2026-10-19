package com.github.mictaege.jitter.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Verifies the usages of the constants that are marked
 * {@code @InlinedConstantApproved}: javac has inlined them into their usage
 * sites, so the bytecode based reference verification cannot see them.
 * This task parses the sources instead and fails the build when a usage
 * is kept in a flavour where the constant is removed - the inlined value
 * would leak. See {@link InlinedConstantsChecker} for the Java sources
 * and {@link KotlinUsageFinder} for the Kotlin sources.
 */
@CacheableTask
class JitterVerifyInlinedConstantsTask extends DefaultTask {

    JitterVerifyInlinedConstantsTask() {
        group = JitterUtil.GROUP
    }

    /** Java source directories of the source set under verification. */
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    Set<File> sourceDirs = []

    /**
     * The compile classpath of the source set: the constants of dependent
     * modules are resolved against it, their markers and gates read from
     * the full model bytecode of the dependencies.
     */
    @Optional
    @Classpath
    FileCollection classpath

    /**
     * The classes output directories of the source set, prepended to the
     * classpath: the Kotlin usage check resolves the constants of the own
     * Java sources and the own package gates from the compiled full model.
     */
    @Optional
    @Classpath
    FileCollection ownClassesDirs

    /** The names of all flavours of the project. */
    @Input
    List<String> flavourNames = []

    /**
     * The kotlin compiler embeddable of the Kotlin usage check, resolved
     * lazily: it is no runtime dependency of the plugin (it would clash
     * with the Kotlin toolchain of Kotlin based builds), so the jars load
     * in an isolated classloader only when Kotlin sources are actually
     * verified.
     */
    @Internal
    FileCollection kotlinCompilerJars

    @TaskAction
    void run() {
        def sourcePaths = sourceDirs.findAll { it.exists() }*.toPath()
        if (sourcePaths.isEmpty()) {
            return
        }
        def classpathFiles = classpath == null ? [] : classpath.files as List
        def errors = new InlinedConstantsChecker(classpathFiles, flavourNames)
                .check(sourcePaths)
        errors.addAll(kotlinErrors())
        if (!errors.isEmpty()) {
            throw new GradleException(
                    "jitter: the usages of the @InlinedConstantApproved constants are not flavour"
                    + " consistent:\n  " + errors.join("\n  "))
        }
    }

    /**
     * The usages in the Kotlin sources of the source set, verified by the
     * Kotlin PSI in an isolated classloader. Source sets without Kotlin
     * sources pay nothing for it.
     */
    private List<String> kotlinErrors() {
        def ktFiles = []
        sourceDirs.findAll { it.exists() }.each { dir ->
            dir.eachFileRecurse { f ->
                if (f.name.endsWith('.kt')) {
                    ktFiles << f.toPath()
                }
            }
        }
        if (ktFiles.isEmpty()) {
            return []
        }
        if (kotlinCompilerJars == null || kotlinCompilerJars.isEmpty()) {
            throw new GradleException(
                    "jitter: the Kotlin sources of this source set need the kotlin compiler"
                    + " embeddable for the @InlinedConstantApproved usage check, which could"
                    + " not be resolved")
        }
        def fullClasspath = ownClassesDirs == null ? [] : ownClassesDirs.files as List
        fullClasspath.addAll(classpath == null ? [] : classpath.files as List)
        def finder = new IsolatedKotlinUsageFinder(
                fullClasspath, flavourNames, kotlinCompilerJars.files as List)
        try {
            return finder.check(ktFiles)
        } finally {
            finder.close()
        }
    }

}
