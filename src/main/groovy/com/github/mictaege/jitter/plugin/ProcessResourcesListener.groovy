package com.github.mictaege.jitter.plugin

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.file.FileTree
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
                        fileName.replace("_${f.name}", "")
                    }
                }
            }
            if(JitterUtil.anyFlavour()) {
                project.jitter.flavours.each {f ->
                    if (JitterUtil.active(f.name)) {
                        task.configure {
                            setDuplicatesStrategy(INCLUDE)
                            eachFile {d ->
                                d.path = d.path.replace("_${f.name}", "")
                            }
                        }
                    } else {
                        task.configure {
                            excludes.add("**/*_${f.name}.*")
                            excludes.add("**/*_${f.name}")
                        }
                    }
                }
            }
        }

    }

    @Override
    void afterExecute(final Task task, final TaskState state) {
        FileTree tree = project.fileTree(project.buildDir)
        tree.visit {f ->
            if (f.isDirectory() && f.file.listFiles().size() == 0) {
                f.file.delete()
            }
        }
    }

}
