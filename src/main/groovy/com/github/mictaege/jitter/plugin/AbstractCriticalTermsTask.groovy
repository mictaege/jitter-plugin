package com.github.mictaege.jitter.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.TaskAction

import java.util.regex.Pattern

abstract class AbstractCriticalTermsTask extends DefaultTask {

    @TaskAction
    void run() {
        project.jitter.flavours.each { a ->
            if (JitterUtil.active(a.name)) {
                def violations = new CriticalTermsViolations(a)
                project.jitter.flavours.each { fl ->
                    if (!JitterUtil.active(fl.name)) {
                        FileTree tree = project.fileTree(project.buildDir)
                        fl.criticalTerms.includes.each { i -> tree.includes.add(i) }
                        fl.criticalTerms.excludes.each { e -> tree.excludes.add(e) }
                        tree.visit {f ->
                            if (!f.isDirectory() && f.size < 1024 * fl.criticalTerms.sizeLimitKb) {
                                fl.criticalTerms.patterns.each { p ->
                                    def pattern = Pattern.compile(p)
                                    if (pattern.matcher(f.file.text).find()) {
                                        violations.add(fl, p, f.file)
                                    }
                                }
                            }
                        }
                    }
                }
                violations.report("${project.buildDir.absolutePath}")
                if (violations.hasViolations()) {
                    onViolations()
                }
            }
        }
    }

    abstract void onViolations()

}

class VerifyCriticalTermsTask extends AbstractCriticalTermsTask {

    void onViolations() {
        throw new GradleException('Violation of critical terms detected. See report for details')
    }

}

class ReportCriticalTermsTask extends AbstractCriticalTermsTask {

    void onViolations() {
    }

}
