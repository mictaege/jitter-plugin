package com.github.mictaege.jitter.plugin

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.tasks.TaskState
import org.gradle.language.jvm.tasks.ProcessResources

import static com.google.common.io.Files.move
import static org.gradle.api.file.DuplicatesStrategy.EXCLUDE

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
                    if (!JitterUtil.active(f.name)) {
                        excludes.add("**/*_${f.name}.*")
                        excludes.add("**/*_${f.name}")
                    }
                }
            }
        }
    }

    @Override
    void afterExecute(final Task task, final TaskState state) {
        if (task instanceof ProcessResources && JitterUtil.anyFlavour()) {
            project.fileTree(task.destinationDir).visit {f ->
                project.jitter.flavours.each { fl ->
                    if (JitterUtil.active(fl.name)) {
                        if (f.path.contains("_${fl.name}")) {
                            if (!f.isDirectory()) {
                                String targetPath = f.file.absolutePath.replace("_${fl.name}", "")
                                File target = new File(targetPath)
                                target.mkdirs()
                                if (target.exists()) {
                                    target.delete()
                                }
                                target.createNewFile()
                                move(f.file, target)
                            }
                        }
                    }
                }
            }
            project.fileTree(project.buildDir).visit { f ->
                if (f.isDirectory() && f.file.listFiles().size() == 0) {
                    f.file.delete()
                }
            }
        }
    }

}
