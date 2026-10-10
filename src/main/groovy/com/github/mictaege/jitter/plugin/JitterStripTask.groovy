package com.github.mictaege.jitter.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
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
class JitterStripTask extends DefaultTask {

    @InputFiles
    Set<File> classesDirs = []

    /** Directory the stripped model is written to, instead of in place. */
    @Optional
    @OutputDirectory
    File outputDir

    /** Classpath for @Alter templates that live in dependent libraries. */
    @Internal
    FileCollection templateClasspath

    @Internal
    StripResult stripResult = new StripResult()

    @Input
    String getActiveFlavour() {
        System.getProperty(JitterUtil.FLAVOUR_PROP)
    }

    @TaskAction
    void run() {
        def stripper = new BytecodeStripper()
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
        // all classes directories of the source set are stripped as ONE
        // unit: a mixed Java/Kotlin source set spreads package gates,
        // classes and cross references over several of them
        def result = outputDir == null
                ? stripper.strip(sourceDirs, null, templateClasspathFiles)
                : stripper.strip(sourceDirs, outputDir.toPath(), templateClasspathFiles)
        stripResult.merge(result)
        stripResult.infos.each { logger.lifecycle(it) }
        if (!stripResult.errors.isEmpty()) {
            stripResult.errors.each { logger.error(it) }
        }
    }

}
