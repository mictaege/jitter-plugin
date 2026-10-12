package com.github.mictaege.jitter.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Verifies after the stripping that the surviving classes do not reference
 * any removed flavour specific class or member anymore. This keeps the
 * guarantee that conflicts and inconsistencies show up at build time.
 */
@DisableCachingByDefault(because = "the verification is a build check over the stripped model and should always run")
class JitterVerifyTask extends DefaultTask {

    @Internal
    List<JitterStripTask> stripTasks = []

    @Internal
    Set<File> allClassesDirs = []

    @TaskAction
    void run() {
        def removedClasses = [] as Set
        def removedMembers = [] as Set
        def errors = []

        stripTasks.each { task ->
            task.stripResult.removedClasses.each { removedClasses << it }
            task.stripResult.removedMembers.each { removedMembers << it }
            task.stripResult.errors.each { errors << it }
        }

        def violations = ReferenceVerifier.findDanglingReferences(allClassesDirs, removedClasses, removedMembers)

        if (!errors.isEmpty()) {
            throw new GradleException("jitter stripping failed:\n  " + errors.join("\n  "))
        }
        if (!violations.isEmpty()) {
            throw new GradleException(
                    "The build contains references to flavour specific code that is not part of the active flavour:\n  "
                            + violations.join("\n  "))
        }
    }

}
