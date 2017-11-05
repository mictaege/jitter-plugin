package com.github.mictaege.jitter.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

import static org.gradle.api.file.DuplicatesStrategy.INCLUDE

class JitterTask extends DefaultTask {

    String flavour

    @TaskAction
    void run() {
        System.properties.setProperty("jitter.active.flavour", flavour)

        if (JitterUtil.anyVariant()) {
            project.jitter.flavours.each {f ->
                if (JitterUtil.active(f)) {
                    project.processResources.configure {
                        setDuplicatesStrategy(INCLUDE)
                        filesMatching("**/*_$f.*") {
                            rename { String fileName ->
                                fileName.replace("_$f", "")
                            }
                        }
                    }
                    project.processTestResources.configure {
                        setDuplicatesStrategy(INCLUDE)
                        filesMatching("**/*_$f.*") {
                            rename { String fileName ->
                                fileName.replace("_$f", "")
                            }
                        }
                    }
                } else {
                    project.processResources.configure {
                        excludes.add("**/*_$f.*")
                    }
                    project.processTestResources.configure {
                        excludes.add("**/*_$f.*")
                    }
                }
            }
        }
    }

}
