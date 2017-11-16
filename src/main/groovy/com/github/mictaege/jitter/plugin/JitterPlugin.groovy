package com.github.mictaege.jitter.plugin

import com.github.mictaege.spoon_gradle_plugin.SpoonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.language.jvm.tasks.ProcessResources

import static org.gradle.api.file.DuplicatesStrategy.EXCLUDE

class JitterPlugin implements Plugin<Project>  {

    @Override
    void apply(final Project project) {
        project.extensions.create "jitter", JitterExtension


        project.configure(project) {
            apply plugin: 'java'
            apply plugin: 'spoon'

            project.spoon.lazyExtensions = { ->
                    def spoonExt = new SpoonExtension()
                    spoonExt.buildOnlyOutdatedFiles = true
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
                    return spoonExt
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

            project.tasks.withType(ProcessResources) {
                it.configure {
                    setDuplicatesStrategy(EXCLUDE)
                    project.jitter.flavours.each { f ->
                        rename { String fileName ->
                            fileName.replace("_$f", "")
                        }
                    }
                }
            }

        })

    }

}