package com.github.mictaege.jitter.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

import java.util.regex.Pattern

class JitterPlugin implements Plugin<Project>  {

    @Override
    void apply(final Project project) {
        project.extensions.create "jitter", JitterExtension

        project.configure(project) {
            apply plugin: 'java'
        }

        project.afterEvaluate({ ->
            project.jitter.flavours.each {f ->
                project.task("flavour${f.name}", type: JitterTask) {
                    flavour = f
                }
            }
        })

        project.afterEvaluate({ ->
            def stripTasks = []
            def scopedVerifyTasks = [:]
            def allClassesDirs = [] as Set

            project.sourceSets.all { srcSet ->
                allClassesDirs.addAll(srcSet.output.classesDirs.files)

                if (!project.jitter.excludeSrcSets.contains(srcSet.name)) {
                    def stripTask = project.task("jitterStrip${srcSet.name.capitalize()}", type: JitterStripTask)
                    stripTask.classesDirs = srcSet.output.classesDirs.files
                    stripTask.dependsOn srcSet.compileJavaTaskName
                    stripTasks << stripTask

                    // Wire the stripping into the classes task of the source
                    // set, so that every consumer of the output (downstream
                    // source sets, jars, wars, tests, run tasks) sees the
                    // stripped classes. The Kotlin plugin compiles downstream
                    // source sets against the jars of upstream source sets, so
                    // consumers must be reached through the classes task - an
                    // artifact must never wait for the compilation of a source
                    // set that consumes the artifact.
                    project.tasks.named(srcSet.classesTaskName).configure {
                        it.dependsOn stripTask
                    }

                    // A scoped verify checks only the classes of its own source
                    // set against the members removed from that source set. It
                    // allows artifact tasks like jar and war to fail early on
                    // dangling references, without pulling the compilation of
                    // source sets that consume the artifact.
                    def scopedVerify = project.task("jitterVerify${srcSet.name.capitalize()}", type: JitterVerifyTask)
                    scopedVerify.stripTasks = [stripTask]
                    scopedVerify.allClassesDirs = stripTask.classesDirs
                    scopedVerify.dependsOn stripTask
                    scopedVerifyTasks[srcSet.name] = scopedVerify
                }
            }

            if (!stripTasks.isEmpty()) {
                def verifyTask = project.task("jitterVerify", type: JitterVerifyTask)
                verifyTask.stripTasks = stripTasks
                verifyTask.allClassesDirs = allClassesDirs
                verifyTask.dependsOn stripTasks

                project.tasks.withType(Test).configureEach { it.dependsOn verifyTask }
                project.tasks.withType(JavaExec).configureEach { it.dependsOn verifyTask }
                project.tasks.withType(Jar).configureEach { jarTask ->
                    if (jarTask.name == 'jar' || jarTask.name == 'war') {
                        def mainVerify = scopedVerifyTasks['main']
                        if (mainVerify != null) {
                            jarTask.dependsOn mainVerify
                        }
                    }
                }
            }
        })

        project.afterEvaluate({
            project.jitter.flavours.each { fl ->
                fl.criticalTerms.patterns.each { p ->
                    try {
                        Pattern.compile(p)
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("Flavour '${fl.name}' contains a critical term with an invalid regular expression '$p': ${e.getLocalizedMessage()}")
                    }
                }
            }
            project.task("reportCriticalTerms", type:ReportCriticalTermsTask)
            project.task("verifyCriticalTerms", type:VerifyCriticalTermsTask)
        })

        project.afterEvaluate({
            project.getGradle().getTaskGraph().addTaskExecutionListener(new ProcessResourcesListener(project))
        })

    }

}
