package com.github.mictaege.jitter.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

class JitterPlugin implements Plugin<Project>  {

    @Override
    void apply(final Project project) {
        project.extensions.create "jitter", JitterExtension

        project.configure(project) {
            apply plugin: 'java'
            apply plugin: 'spoon'
        }

        project.afterEvaluate({
            project.spoonMain.processors = [
                    'com.github.mictaege.jitter.plugin.AlterClassProcessor',
                    'com.github.mictaege.jitter.plugin.ForkMethodProcessor',
                    'com.github.mictaege.jitter.plugin.OnlyIfClassProcessor',
                    'com.github.mictaege.jitter.plugin.OnlyIfFieldProcessor',
                    'com.github.mictaege.jitter.plugin.OnlyIfMethodProcessor',
                    'com.github.mictaege.jitter.plugin.OnlyIfConstructorProcessor'
            ]

            project.spoonMain.compliance = project.jitter.compliance
        })

        project.afterEvaluate({
            project.spoonTest.processors = [
                    'com.github.mictaege.jitter.plugin.AlterClassProcessor',
                    'com.github.mictaege.jitter.plugin.ForkMethodProcessor',
                    'com.github.mictaege.jitter.plugin.OnlyIfClassProcessor',
                    'com.github.mictaege.jitter.plugin.OnlyIfFieldProcessor',
                    'com.github.mictaege.jitter.plugin.OnlyIfMethodProcessor',
                    'com.github.mictaege.jitter.plugin.OnlyIfConstructorProcessor'
            ]

            project.spoonTest.compliance = project.jitter.compliance
        })

        project.afterEvaluate({
            project.jitter.flavours.each {f ->
                project.task("flavour$f", type: JitterTask) {
                    flavour = f
                }
            }
        })

    }

}