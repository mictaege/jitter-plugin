package com.github.mictaege.jitter.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.language.jvm.tasks.ProcessResources

import static org.gradle.api.file.DuplicatesStrategy.INCLUDE

class JitterTask extends DefaultTask {

    String flavour

    @TaskAction
    void run() {
        System.properties.setProperty("jitter.active.flavour", flavour)

        if (JitterUtil.anyVariant()) {
            project.jitter.flavours.each {f ->
                if (JitterUtil.active(f)) {
                    project.tasks.withType(ProcessResources) {
                        it.configure {
                            setDuplicatesStrategy(INCLUDE)
                            filesMatching("**/*_$f.*") {
                                rename { String fileName ->
                                    fileName.replace("_$f", "")
                                }
                            }
                        }
                    }
                } else {
                    project.tasks.withType(ProcessResources) {
                        it.configure {
                            excludes.add("**/*_$f.*")
                        }
                    }
                }
            }
        }
    }

}
