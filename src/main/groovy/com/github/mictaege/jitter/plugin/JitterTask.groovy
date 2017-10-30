package com.github.mictaege.jitter.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

class JitterTask extends DefaultTask {

    String flavour

    @TaskAction
    void run() {
        System.properties.setProperty("jitter.active.flavour", flavour)
    }

}
