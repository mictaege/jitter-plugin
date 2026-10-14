package com.github.mictaege.jitter.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.FileTree
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.TaskAction

import java.util.regex.Pattern

@DisableCachingByDefault(because = "the verification is a build check over the build directory and should always run")
abstract class AbstractCriticalTermsTask extends DefaultTask {

    AbstractCriticalTermsTask() {
        group = JitterUtil.GROUP
    }

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

@DisableCachingByDefault(because = "the verification is a build check over the build directory and should always run")
class VerifyCriticalTermsTask extends AbstractCriticalTermsTask {

    void onViolations() {
        throw new GradleException('Violation of critical terms detected. See report for details')
    }

}

@DisableCachingByDefault(because = "the report is a build check over the build directory and should always run")
class ReportCriticalTermsTask extends AbstractCriticalTermsTask {

    void onViolations() {
    }

}
