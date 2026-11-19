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
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Strips the flavour specific bytecode of one source set.
 *
 * With an output directory the compiled classes (the full 150% model)
 * stay untouched: the stripped model is written to the output directory
 * instead, so that compilation against the full model remains possible
 * while packaging and runtime consume the stripped model. Without an
 * output directory the classes are stripped in place.
 */
@CacheableTask
class JitterStripTask extends DefaultTask {

    JitterStripTask() {
        group = JitterUtil.GROUP
    }

    /**
     * Relative path sensitivity, so the stripped model can also be taken
     * from the build cache when the project lives at another location.
     */
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    Set<File> classesDirs = []

    /** Directory the stripped model is written to, instead of in place. */
    @Optional
    @OutputDirectory
    File outputDir

    /** Name of the source set, used for the removal manifest inside the stripped tree. */
    @Internal
    String manifestName

    /**
     * Classpath for @Alter templates that live in dependent libraries.
     * A classpath input: the templates are read and inlined at strip time,
     * so a template change must invalidate the task even when the module's
     * own compiled classes stay unchanged.
     */
    @Optional
    @Classpath
    FileCollection templateClasspath

    /**
     * Runtime classpath of the source set, used to find the removal manifests
     * of the flavour dependencies (their stripped trees of the same build).
     * Deliberately NOT a classpath input: test-like source sets point their
     * output at their own stripped tree, which would make the task depend on
     * its own output - only the manifest files are an input (see
     * {@link #getDependencyManifestFiles()}).
     */
    @Internal
    FileCollection dependencyClasspath

    /**
     * The removal manifests of the flavour dependencies as task input: the
     * files live inside the producers' strip output directories, so the
     * consumer's strip task automatically runs after the producers' strip
     * tasks, and a producer re-strip re-runs the consumer's stripping. The
     * own manifest is excluded. The consumer's switch map / when map cleanup
     * drops the entries of enum constants these manifests report as removed,
     * so their names do not leak into this module's stripped artifact.
     */
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    Set<File> getDependencyManifestFiles() {
        return siblingStripTasks().collect { stripTask ->
            new File(stripTask.outputDir as File,
                    RemovalManifest.DIR + stripTask.manifestName + RemovalManifest.SUFFIX)
        } as Set
    }

    /**
     * Declares the dependency on the strip tasks of the same-build flavour
     * dependencies (their stripped tree or stripped jar is on the runtime
     * classpath): the consumer's strip must run after the producers' strips,
     * because it reads their removal manifests. The own strip task is never
     * included - test-like source sets carry their own stripped tree on the
     * runtime classpath, and a dependency on the own output would be a cycle.
     */
    void dependsOnSiblingStripTasks() {
        final def self = this
        dependsOn project.provider { self.siblingStripTasks() }
    }

    Set<Object> siblingStripTasks() {
        final def tasks = new LinkedHashSet<Object>()
        if (dependencyClasspath == null) {
            return tasks
        }
        final def classpathFiles = dependencyClasspath.files
        project.rootProject.allprojects { p ->
            p.plugins.withId('java') {
                p.sourceSets.all { ss ->
                    final def cap = ss.name.capitalize()
                    final def stripTask = p.tasks.findByName("jitterStrip${cap}")
                    if (stripTask == null || !stripTask.hasProperty('outputDir') || stripTask.outputDir == null) {
                        return
                    }
                    if (stripTask == this || (outputDir != null && stripTask.outputDir == outputDir)) {
                        // never depend on the own output
                        return
                    }
                    final def strippedDir = stripTask.outputDir as File
                    final def strippedJarTask = p.tasks.findByName(
                            ss.name == 'main' ? 'jitterJar' : "jitter${cap}Jar")
                    final def strippedJar = strippedJarTask != null
                            && strippedJarTask.hasProperty('archiveFileName')
                            ? new File(p.buildDir, "libs/${strippedJarTask.archiveFileName.get()}") : null
                    final def onClasspath = classpathFiles.any { f ->
                        f.toPath().startsWith(strippedDir.toPath())
                                || (strippedJar != null && f == strippedJar)
                    }
                    if (onClasspath) {
                        tasks.add(stripTask)
                    }
                }
            }
        }
        return tasks
    }

    /**
     * The kotlin-metadata-jvm jars of the @kotlin.Metadata rewrite of
     * stripped Kotlin classes, resolved lazily: it is no runtime dependency
     * of the plugin (it would clash with the Kotlin toolchain of Kotlin
     * based builds), so the jars load in an isolated classloader only when
     * Kotlin classes are actually stripped.
     */
    @Internal
    FileCollection kotlinMetadataJars

    /**
     * The names of all flavours of the project: the constant audit tells a
     * flavour-variant @OnlyIf gate from a gate that covers all flavours.
     */
    @Input
    List<String> flavourNames = []

    @Internal
    StripResult stripResult = new StripResult()

    /** The active flavour, empty if no flavour is selected (the 150% model). */
    @Input
    String getActiveFlavour() {
        System.getProperty(JitterUtil.FLAVOUR_PROP) ?: ''
    }

    @TaskAction
    void run() {
        def stripper = new BytecodeStripper()
        stripper.kotlinMetadataJars = kotlinMetadataJars == null ? [] : kotlinMetadataJars.files as List
        def templateClasspathFiles = templateClasspath == null ? [] : templateClasspath.files as List
        // the stripped tree is written as a whole: clean the output of a
        // previous run first
        if (outputDir != null) {
            outputDir.deleteDir()
        }
        def sourceDirs = classesDirs.findAll { it.exists() }*.toPath()
        if (sourceDirs.isEmpty()) {
            return
        }
        // the removals of the flavour dependencies (same-build producers),
        // from their removal manifests: the switch map / when map cleanup
        // drops the entries of enum constants they report as removed
        def crossModuleRemovedMembers = [] as Set
        def crossModuleRemovedClasses = [] as Set
        dependencyManifestFiles.each { manifest ->
            if (manifest.isFile()) {
                RemovalManifest.readFile(manifest, crossModuleRemovedClasses, crossModuleRemovedMembers)
            }
        }
        // all classes directories of the source set are stripped as ONE
        // unit: a mixed Java/Kotlin source set spreads package gates,
        // classes and cross references over several of them
        def result = outputDir == null
                ? stripper.strip(sourceDirs, null, templateClasspathFiles, flavourNames, crossModuleRemovedMembers)
                : stripper.strip(sourceDirs, outputDir.toPath(), templateClasspathFiles, flavourNames, crossModuleRemovedMembers)
        stripResult.merge(result)
        // one output format for everything the plugin says: every line is
        // prefixed with 'jitter: ', the severity comes from the log level -
        // plain lifecycle output for information, warnings rendered as
        // warnings, errors fail the task
        stripResult.infos.each { logger.lifecycle("jitter: " + it) }
        stripResult.warnings.each { logger.warn("jitter: " + it) }
        if (!stripResult.errors.isEmpty()) {
            throw new GradleException("jitter: stripping failed:\n  " + stripResult.errors.join("\n  "))
        }
        // the removal manifest travels in the stripped tree, so it ends up in
        // the stripped runtime artifacts; consumers read it from the classpath
        // to verify their own classes against these removals
        if (outputDir != null && manifestName != null) {
            RemovalManifest.write(new File(outputDir, RemovalManifest.DIR + manifestName + RemovalManifest.SUFFIX).toPath(),
                    activeFlavour, stripResult)
        }
    }

}
