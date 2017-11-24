package com.github.mictaege.jitter.plugin

import com.github.mictaege.spoon_gradle_plugin.SpoonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

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
                            'com.github.mictaege.jitter.plugin.OnlyIfPackageProcessor',
                            'com.github.mictaege.jitter.plugin.OnlyIfClassProcessor',
                            'com.github.mictaege.jitter.plugin.OnlyIfFieldProcessor',
                            'com.github.mictaege.jitter.plugin.OnlyIfMethodProcessor',
                            'com.github.mictaege.jitter.plugin.OnlyIfConstructorProcessor'
                    ]
                    spoonExt.compliance = project.jitter.compliance
                    spoonExt.exclude = project.jitter.exclude
                    spoonExt.fileFilter = { File srcFile ->
                        def pckInfo = new File(srcFile.parentFile, "package-info.java")
                        def pckInfoVar = pckInfo.exists() && pckInfo.text.contains("com.github.mictaege.jitter.api")
                        pckInfoVar || srcFile.text.contains("com.github.mictaege.jitter.api")
                    }
                    spoonExt
                }

        }

        project.afterEvaluate({
            project.jitter.flavours.each {f ->
                project.task("flavour$f", type: JitterTask) {
                    flavour = f
                }
            }
        })

        project.afterEvaluate({
            project.getGradle().getTaskGraph().addTaskExecutionListener(new ProcessResourcesListener(project))
        })

    }

}