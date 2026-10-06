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
            def allClassesDirs = [] as Set

            project.sourceSets.all { srcSet ->
                allClassesDirs.addAll(srcSet.output.classesDirs.files)

                if (!project.jitter.excludeSrcSets.contains(srcSet.name)) {
                    def stripTask = project.task("jitterStrip${srcSet.name.capitalize()}", type: JitterStripTask)
                    stripTask.classesDirs = srcSet.output.classesDirs.files
                    stripTask.dependsOn srcSet.compileJavaTaskName
                    stripTask.mustRunAfter project.tasks.withType(JavaCompile)
                    stripTasks << stripTask
                }
            }

            if (!stripTasks.isEmpty()) {
                def verifyTask = project.task("jitterVerify", type: JitterVerifyTask)
                verifyTask.stripTasks = stripTasks
                verifyTask.allClassesDirs = allClassesDirs
                verifyTask.dependsOn stripTasks

                project.tasks.withType(Test).configureEach { it.dependsOn verifyTask }
                project.tasks.withType(JavaExec).configureEach { it.dependsOn verifyTask }
                project.tasks.withType(Jar).configureEach { it.dependsOn verifyTask }
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
