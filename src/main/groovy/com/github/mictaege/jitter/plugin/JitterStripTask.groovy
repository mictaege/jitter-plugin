package com.github.mictaege.jitter.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Strips the flavour specific bytecode from the compiled classes of one
 * source set. The task runs after the whole 150% model has been compiled,
 * because the downstream source sets have to be compiled against the
 * complete model as well.
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
