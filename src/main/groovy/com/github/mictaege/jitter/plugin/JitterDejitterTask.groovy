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

    JitterDejitterTask() {
        group = JitterUtil.GROUP
    }

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
     * Additional lookup entries for {@code @Alter} templates of other source
     * sets: classes output directory mapped to source directories. Merged
     * over the build wide index the task derives from the java projects of
     * the build at execution time, so special setups can add or override
     * entries.
     */
    @Internal
    Map<File, List<File>> externalTemplateDirs = [:]

    /**
     * The kotlin compiler embeddable of the Kotlin source dejittering,
     * resolved lazily: it is no runtime dependency of the plugin (it would
     * clash with the Kotlin toolchain of Kotlin based builds), so the jars
     * load in an isolated classloader only when a Kotlin source is actually
     * dejittered.
     */
    @Internal
    FileCollection kotlinCompilerJars

    /**
     * The removal manifest of the stripped model, the cross check reference.
     * A source set without classes gets no stripped model and no manifest,
     * so the input is null then and the cross check is skipped - that is why
     * the manifest is read through an optional getter instead of being an
     * input file property of the configured path: the file validation would
     * otherwise reject the missing manifest of an empty source set.
     */
    @Internal
    File manifestFile

    /**
     * The manifest as a cross check input, but only when it exists: without
     * a stripped model there is nothing to cross check against.
     */
    @PathSensitive(PathSensitivity.RELATIVE)
    @Optional
    @InputFile
    File getManifest() {
        manifestFile != null && manifestFile.isFile() ? manifestFile : null
    }

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
        def templateDirs = buildTemplateDirsIndex()
        templateDirs.putAll(externalTemplateDirs)
        def dejitterer = new Dejitterer(activeFlavour,
                compileClasspath == null ? [] : compileClasspath.files as List,
                templateDirs)
        dejitterer.kotlinCompilerJars = kotlinCompilerJars == null ? [] : kotlinCompilerJars.files as List
        outputDir.deleteDir()
        // the variant mirrors the source set layout: the dejittered sources
        // under java, the flavour processed resources under resources
        def sourcesDir = new File(outputDir, 'java')
        sourcesDir.mkdirs()
        def result
        try {
            result = dejitterer.dejitter(
                    sourceDirs.findAll { it.exists() }*.toPath() as Set,
                    sourcesDir.toPath())
        } finally {
            dejitterer.close()
        }
        result.infos.each { logger.lifecycle(it) }
        if (!result.errors.isEmpty()) {
            throw new GradleException("jitter dejittering failed:\n  " + result.errors.join("\n  "))
        }
        copyResources()
        crossCheckManifest(result)
    }

    /**
     * The classes output directories of all java source sets of the build,
     * mapped to their source directories: the lookup for {@code @Alter}
     * templates of other source sets, mirroring the bytecode stripping which
     * resolves classpath templates. Derived at execution time so that every
     * project of the build is evaluated - at configuration time the later
     * evaluated projects of a multi project build would not be visible yet.
     */
    private Map<File, List<File>> buildTemplateDirsIndex() {
        def index = [:]
        project.rootProject.allprojects { p ->
            p.plugins.withId('java') {
                p.sourceSets.all { ss ->
                    ss.output.classesDirs.files.each { dir ->
                        index[dir] = ss.java.srcDirs.toList()
                    }
                }
            }
        }
        index
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
     *
     * And the other direction must hold as well: a kept source file of a
     * class the bytecode stripping removed leaks flavour specific code
     * into the source delivery - like the Kotlin files of a package that
     * only its Java package-info gates.
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
        def keptThoughRemoved = result.keptSourceClasses.findAll { removedInBytecode.contains(it) }
        if (!keptThoughRemoved.isEmpty()) {
            throw new GradleException(
                    "jitter: the dejittered model keeps source files of classes that are removed"
                    + " from the stripped model: " + keptThoughRemoved.collect { it.replace('/', '.') }
                    + " - the source level dejittering and the bytecode stripping disagree")
        }
        logger.lifecycle("jitter: dejittered model cross checked against the removal manifest")
    }

}
