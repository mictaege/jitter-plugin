package com.github.mictaege.jitter.plugin

import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Creates the dejittered source variant of one source set: the original
 * sources minus the flavour specific code of the active flavour, as source
 * level surgery that keeps comments, formatting and line numbers of the
 * untouched parts.
 *
 * The output directory mirrors the source set layout: the dejittered Java
 * sources and every other source file (Kotlin, Groovy, ... dejittering those
 * languages is out of scope) under {@code java}, the flavour processed
 * resources of the source set under {@code resources}.
 *
 * Every removal of the source surgery is cross checked against the removal
 * manifest of the stripped bytecode model: both pipelines work on the same
 * annotations, so every source level removal must also have been removed
 * from the bytecode. A divergence is a dejittering bug or an unsupported
 * construct and fails the build.
 */
@CacheableTask
class JitterDejitterTask extends DefaultTask {

    /**
     * Relative path sensitivity, so the dejittered model can also be taken
     * from the build cache when the project lives at another location.
     */
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    Set<File> sourceDirs = []

    /** The processed resources of the source set, copied into the variant. */
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    Set<File> resourceDirs = []

    /** Classpath for resolving compile time constants of annotation values. */
    @Internal
    FileCollection compileClasspath

    /**
     * The removal manifest of the stripped model, the cross check reference.
     * Optional: without a stripped model the cross check is skipped.
     */
    @PathSensitive(PathSensitivity.RELATIVE)
    @Optional
    @InputFile
    File manifestFile

    /** Directory the dejittered source variant is written to. */
    @OutputDirectory
    File outputDir

    /** The active flavour, empty if no flavour is selected (the 150% model). */
    @Input
    String getActiveFlavour() {
        System.getProperty(JitterUtil.FLAVOUR_PROP) ?: ''
    }

    @TaskAction
    void run() {
        def dejitterer = new Dejitterer(activeFlavour,
                compileClasspath == null ? [] : compileClasspath.files as List)
        outputDir.deleteDir()
        // the variant mirrors the source set layout: the dejittered sources
        // under java, the flavour processed resources under resources
        def sourcesDir = new File(outputDir, 'java')
        sourcesDir.mkdirs()
        def result = dejitterer.dejitter(
                sourceDirs.findAll { it.exists() }*.toPath() as Set,
                sourcesDir.toPath())
        result.infos.each { logger.lifecycle(it) }
        if (!result.errors.isEmpty()) {
            throw new GradleException("jitter dejittering failed:\n  " + result.errors.join("\n  "))
        }
        copyResources()
        crossCheckManifest(result)
    }

    private void copyResources() {
        def target = new File(outputDir, 'resources')
        resourceDirs.findAll { it.exists() }.each { dir ->
            project.copy {
                from dir
                into target
            }
        }
    }

    /**
     * The source level removals must be a subset of the bytecode removals:
     * the manifest may know more (generated classes, package-infos), but
     * never less. A source removal the stripper did not make means the
     * source surgery and the bytecode stripping disagree.
     */
    private void crossCheckManifest(final result) {
        if (manifestFile == null || !manifestFile.isFile()) {
            logger.warn("jitter: no removal manifest found at '{}', the dejittered model"
                    + " is not cross checked against the stripped model", manifestFile)
            return
        }
        def removedInBytecode = new HashSet<String>()
        RemovalManifest.readFile(manifestFile, removedInBytecode, new HashSet<String>())
        def unexpected = result.removedClasses.findAll { !removedInBytecode.contains(it) }
        if (!unexpected.isEmpty()) {
            throw new GradleException(
                    "jitter: the dejittered model removes classes that are not removed from"
                    + " the stripped model: " + unexpected.collect { it.replace('/', '.') }
                    + " - the source level dejittering and the bytecode stripping disagree")
        }
        logger.lifecycle("jitter: dejittered model cross checked against the removal manifest")
    }

}
