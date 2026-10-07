package com.github.mictaege.jitter.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Strips the flavour specific bytecode from the compiled classes of one
 * source set. The task runs directly after the compilation of its source
 * set, so that every consumer of the classes (downstream source sets,
 * jars, tests, run tasks) only sees the stripped flavour model - the same
 * consistency model the source transformation had.
 */
class JitterStripTask extends DefaultTask {

    @Internal
    Set<File> classesDirs = []

    @Internal
    StripResult stripResult = new StripResult()

    @TaskAction
    void run() {
        def stripper = new BytecodeStripper()
        classesDirs.each { dir ->
            if (dir.exists()) {
                def result = stripper.strip(dir.toPath())
                stripResult.merge(result)
            }
        }
        stripResult.infos.each { logger.lifecycle(it) }
        if (!stripResult.errors.isEmpty()) {
            stripResult.errors.each { logger.error(it) }
        }
    }

}
