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
                        // the removal manifests of the stripped trees are build
                        // metadata of the plugin: they name every removed class,
                        // which includes the flavour specific terms of the
                        // inactive flavours by design
                        tree.excludes.add('**/' + RemovalManifest.DIR + '**')
                        // the dejittered source variant keeps the untouched
                        // sources of the other languages (Kotlin, Groovy, ...)
                        // with all their flavour annotations
                        tree.excludes.add(JitterUtil.DEJITTERED_DIR + '/**')
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
        throw new GradleException('jitter: violation of critical terms detected, see report for details')
    }

}

@DisableCachingByDefault(because = "the report is a build check over the build directory and should always run")
class ReportCriticalTermsTask extends AbstractCriticalTermsTask {

    void onViolations() {
    }

}
