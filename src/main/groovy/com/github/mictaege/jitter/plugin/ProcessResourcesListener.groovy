package com.github.mictaege.jitter.plugin

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.tasks.TaskState
import org.gradle.language.jvm.tasks.ProcessResources

import static org.gradle.api.file.DuplicatesStrategy.EXCLUDE
import static org.gradle.api.file.DuplicatesStrategy.INCLUDE

class ProcessResourcesListener implements TaskExecutionListener {

    private Project project

    ProcessResourcesListener(final Project project) {
        this.project = project
    }

    @Override
    void beforeExecute(final Task task) {

        if (task instanceof ProcessResources) {
            task.configure {
                setDuplicatesStrategy(EXCLUDE)
                project.jitter.flavours.each { f ->
                    rename { String fileName ->
                        fileName.replace("_$f", "")
                    }
                }
            }
            if(JitterUtil.anyVariant()) {
                project.jitter.flavours.each {f ->
                    if (JitterUtil.active(f)) {
                        task.configure {
                            setDuplicatesStrategy(INCLUDE)
                            filesMatching("**/*_$f.*") {
                                rename { String fileName ->
                                    fileName.replace("_$f", "")
                                }
                            }
                        }
                    } else {
                        task.configure {
                            excludes.add("**/*_$f.*")
                        }
                    }
                }
            }
        }

    }

    @Override
    void afterExecute(final Task task, final TaskState state) {
        //NOP
    }

}
