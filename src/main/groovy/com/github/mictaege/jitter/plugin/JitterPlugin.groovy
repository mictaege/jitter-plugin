package com.github.mictaege.jitter.plugin

import com.github.mictaege.spoon_gradle_plugin.SpoonExtension
import com.github.mictaege.spoon_gradle_plugin.SpoonTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.language.jvm.tasks.ProcessResources

import java.util.regex.Pattern

import static com.github.mictaege.jitter.plugin.JitterUtil.FLAVOUR_PROP

class JitterPlugin implements Plugin<Project>  {

    @Override
    void apply(final Project project) {
        project.extensions.create "jitter", JitterExtension

        project.configure(project) {
            apply plugin: 'java'
            apply plugin: 'spoon'

            project.spoon.lazyExtensions = { ->
                    def spoonExt = new SpoonExtension()
                    spoonExt.processors = [
                            'com.github.mictaege.jitter.plugin.AlterClassProcessor',
                            'com.github.mictaege.jitter.plugin.ForkMethodProcessor',
                            'com.github.mictaege.jitter.plugin.OnlyIfClassProcessor',
                            'com.github.mictaege.jitter.plugin.OnlyIfFieldProcessor',
                            'com.github.mictaege.jitter.plugin.OnlyIfMethodProcessor',
                            'com.github.mictaege.jitter.plugin.OnlyIfConstructorProcessor',
                            'com.github.mictaege.jitter.plugin.OnlyIfPackageProcessor'
                    ]
                    spoonExt.compliance = project.jitter.compliance
                    spoonExt.exclude = project.jitter.excludeSrcSets
                    spoonExt.fileFilter = { File srcFile ->
                        def pckInfo = new File(srcFile.parentFile, "package-info.java")
                        def pckInfoVar = pckInfo.exists() && pckInfo.text.contains("com.github.mictaege.jitter.api")
                        pckInfoVar || (srcFile.exists() && srcFile.text.contains("com.github.mictaege.jitter.api"))
                    }
                    spoonExt
                }

        }

        project.tasks.withType(SpoonTask).configureEach { task ->
            dependOnActiveFlavour(project, task)
        }

        project.tasks.withType(ProcessResources).configureEach { task ->
            FlavouredResources.configure(project, task)
        }

        project.afterEvaluate({
            project.jitter.flavours.each {f ->
                project.task("flavour${f.name}", type: JitterTask) {
                    flavour = f
                }
            }
            selectRequestedFlavour(project)
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

    }

    /**
     * The output of the given task depends on the active flavour: declares the flavour as input of the task, so the
     * task is neither up-to-date nor taken from the build cache with the output of another flavour.
     */
    static void dependOnActiveFlavour(final Project project, final Task task) {
        task.inputs.property(FLAVOUR_PROP, project.provider { System.getProperty(FLAVOUR_PROP) ?: '' })
        // for a `flavourXyz` task which could not be selected upfront, e.g. if it's name has been abbreviated
        task.mustRunAfter(project.tasks.withType(JitterTask))
    }

    /**
     * Activates the flavour of a requested `flavourXyz` task before any task is executed. Tasks depending on the
     * flavour may be executed (in other projects) before the `flavourXyz` task, which would be too late for their
     * inputs.
     */
    private static void selectRequestedFlavour(final Project project) {
        def requested = project.gradle.startParameter.taskNames.collect { it.substring(it.lastIndexOf(':') + 1) }
        def selected = requested.reverse().find { name -> project.jitter.flavours.any { "flavour${it.name}" == name } }
        if (selected != null) {
            System.setProperty(FLAVOUR_PROP, selected.substring('flavour'.length()))
        }
    }

}