package com.github.mictaege.jitter.plugin

import org.gradle.api.DefaultTask
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

import static com.github.mictaege.jitter.plugin.JitterUtil.FLAVOUR_PROP

@DisableCachingByDefault(because = "the task only selects the active flavour and produces no output")
class JitterTask extends DefaultTask {

    JitterTask() {
        group = JitterUtil.GROUP
    }

    @Input
    FlavourCfg flavour

    @TaskAction
    void run() {
        System.properties.setProperty(FLAVOUR_PROP, flavour.name)
    }

}
