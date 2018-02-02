package com.github.mictaege.jitter.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

import static com.github.mictaege.jitter.plugin.JitterUtil.FLAVOUR_PROP

class JitterTask extends DefaultTask {

    FlavourCfg flavour

    @TaskAction
    void run() {
        System.properties.setProperty(FLAVOUR_PROP, flavour.name)
    }

}
