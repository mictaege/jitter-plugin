package com.github.mictaege.jitter.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.Task
import org.gradle.language.jvm.tasks.ProcessResources

import java.util.regex.Pattern

import static com.github.mictaege.jitter.plugin.JitterUtil.FLAVOUR_PROP

class JitterPlugin implements Plugin<Project>  {

    @Override
    void apply(final Project project) {
        project.extensions.create "jitter", JitterExtension

        project.configure(project) {
            apply plugin: 'java'
        }

        project.tasks.withType(ProcessResources).configureEach { task ->
            FlavouredResources.configure(project, task)
        }

        project.afterEvaluate({ ->
            project.jitter.flavours.each {f ->
                project.task("flavour${f.name}", type: JitterTask) {
                    flavour = f
                }
            }
            selectRequestedFlavour(project)
        })

        project.afterEvaluate({ ->
            def stripTasks = []
            def allClassesDirs = [] as Set

            project.sourceSets.all { srcSet ->
                // the flavour-stripped model is the default output of the
                // build: compilation still happens against the full 150%
                // model (the compiled classes stay untouched and downstream
                // compilation resolves them), while packaging and runtime
                // consume the stripped model
                if (!project.jitter.excludeSrcSets.contains(srcSet.name)) {
                    def stripTask = project.task("jitterStrip${srcSet.name.capitalize()}", type: JitterStripTask)
                    stripTask.classesDirs = srcSet.output.classesDirs.files
                    stripTask.templateClasspath = srcSet.compileClasspath
                    stripTask.dependsOn srcSet.compileJavaTaskName
                    stripTasks << stripTask

                    stripTask.outputDir = new File(project.buildDir, "jitter-stripped/${srcSet.name}")
                    if (srcSet.name != 'main' && srcSet.name != 'testFixtures') {
                        // test-like source sets: the stripped model is their
                        // runtime, so the source set output points at the
                        // stripped tree (test tasks and IDEs pick it up from
                        // there). The compiled classes stay untouched, so
                        // switching flavours just re-strips them.
                        srcSet.output.classesDirs = project.files(stripTask)
                    }
                    // test fixtures compile against the full model
                    // (testFixturesApiElements keeps the full jar); only
                    // their runtime artifact is the stripped model, see
                    // the jitterTestFixturesJar wiring below
                }

                // the aggregate verify checks the classes that ship or run:
                // the stripped main and test fixtures trees instead of the
                // full classes, the classes of every other source set as
                // they are (test-like: the reassigned stripped output)
                if ((srcSet.name == 'main' || srcSet.name == 'testFixtures')
                        && !project.jitter.excludeSrcSets.contains(srcSet.name)) {
                    def stripTask = project.tasks.findByName("jitterStrip${srcSet.name.capitalize()}")
                    if (stripTask != null && stripTask.outputDir != null) {
                        allClassesDirs.add(stripTask.outputDir)
                    }
                } else {
                    allClassesDirs.addAll(srcSet.output.classesDirs.files)
                }
            }

            if (!stripTasks.isEmpty()) {
                def mainStrip = project.tasks.findByName('jitterStripMain')

                // A scoped verify checks only the stripped main tree against
                // the members removed from main. It lets artifact tasks fail
                // early on dangling references, without pulling the
                // compilation of source sets that consume the artifact.
                def mainVerify = null
                if (mainStrip != null) {
                    mainVerify = project.task('jitterVerifyMain', type: JitterVerifyTask)
                    mainVerify.stripTasks = [mainStrip]
                    mainVerify.allClassesDirs = [mainStrip.outputDir]
                    mainVerify.dependsOn mainStrip
                }

                def verifyTask = project.task("jitterVerify", type: JitterVerifyTask)
                verifyTask.stripTasks = stripTasks
                verifyTask.allClassesDirs = allClassesDirs
                verifyTask.dependsOn stripTasks

                project.tasks.withType(Test).configureEach { it.dependsOn verifyTask }
                project.tasks.withType(JavaExec).configureEach { it.dependsOn verifyTask }

                // the stripped runtime artifact of the main source set
                if (mainStrip != null && mainStrip.outputDir != null) {
                    def resourcesDir = project.sourceSets.main.output.resourcesDir
                    def resourcesTask = project.tasks.named(project.sourceSets.main.processResourcesTaskName)

                    def strippedJar = project.tasks.create('jitterJar', org.gradle.api.tasks.bundling.Jar) {
                        destinationDirectory = new File(project.buildDir, 'jitter-libs')
                        from(mainStrip.outputDir)
                        from(resourcesDir)
                        dependsOn mainStrip, resourcesTask
                    }
                    if (mainVerify != null) {
                        strippedJar.dependsOn mainVerify
                    }
                    def fullJar = project.tasks.findByName('jar')
                    if (fullJar != null) {
                        strippedJar.archiveFileName.set(fullJar.archiveFileName)
                    }
                    // consumers resolve the runtime variant of this project
                    // against the stripped jar; the full jar stays the
                    // compile artifact (classes directories) and the test
                    // fixtures artifact
                    def runtimeElements = project.configurations.findByName('runtimeElements')
                    if (runtimeElements != null) {
                        runtimeElements.outgoing.artifacts.removeIf { true }
                        runtimeElements.outgoing.artifact(strippedJar)
                    }

                    def warTask = project.tasks.findByName('war')
                    if (warTask != null) {
                        warTask.classpath = project.files(mainStrip.outputDir, resourcesDir,
                                project.configurations.runtimeClasspath)
                        warTask.dependsOn mainVerify
                    }
                }

                // the stripped runtime artifact of the test fixtures: the
                // test fixtures compile artifact stays the full jar
                // (testFixturesApiElements), the runtime variant carries the
                // stripped test fixtures model, so that test code which
                // runs against the deployed flavour model sees the same
                // @Alter/@Fork/@OnlyIf transformations as the application
                def fixturesStrip = project.tasks.findByName('jitterStripTestFixtures')
                if (fixturesStrip != null && fixturesStrip.outputDir != null
                        && mainStrip != null && mainStrip.outputDir != null) {
                    def strippedFixturesJar = project.tasks.create('jitterTestFixturesJar',
                            org.gradle.api.tasks.bundling.Jar) {
                        destinationDirectory = new File(project.buildDir, 'jitter-libs')
                        // the main and test fixtures trees may carry the same
                        // package-info; first (main) wins, like the old jar
                        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
                        from(mainStrip.outputDir)
                        from(fixturesStrip.outputDir)
                        from(project.sourceSets.main.output.resourcesDir)
                        from(project.sourceSets.testFixtures.output.resourcesDir)
                        dependsOn mainStrip, fixturesStrip
                    }
                    strippedFixturesJar.dependsOn project.tasks.named(
                            project.sourceSets.main.processResourcesTaskName)
                    strippedFixturesJar.dependsOn project.tasks.named(
                            project.sourceSets.testFixtures.processResourcesTaskName)
                    def fullFixturesJar = project.tasks.findByName('testFixturesJar')
                    if (fullFixturesJar != null) {
                        strippedFixturesJar.archiveFileName.set(fullFixturesJar.archiveFileName)
                    }
                    def fixturesRuntime = project.configurations.findByName('testFixturesRuntimeElements')
                    if (fixturesRuntime != null) {
                        fixturesRuntime.outgoing.artifacts.removeIf { true }
                        fixturesRuntime.outgoing.artifact(strippedFixturesJar)
                    }
                }
            }
        })

        project.afterEvaluate({
            project.jitter.flavours.each { fl ->
                fl.criticalTerms.patterns.each { p ->
                    try {
                        Pattern.compile(p)
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("Flavour '${fl.name}' contains a critical term with an invalid regular expression '$p': ${e.getLocalizedMessage()}")
                    }
                }
            }
            project.task("reportCriticalTerms", type:ReportCriticalTermsTask)
            project.task("verifyCriticalTerms", type:VerifyCriticalTermsTask)
        })

    }

    /**
     * The output of the given task depends on the active flavour: declares the flavour as input of the task, so the
     * task is neither up-to-date nor taken from the build cache with the output of another flavour.
     */
    static void dependOnActiveFlavour(final Project project, final Task task) {
        task.inputs.property(FLAVOUR_PROP, project.provider { System.getProperty(FLAVOUR_PROP) ?: '' })
        // for a `flavourXyz` task which could not be selected upfront, e.g. if it's name has been abbreviated
        task.mustRunAfter(project.tasks.withType(JitterTask))
    }

    /**
     * Activates the flavour of a requested `flavourXyz` task before any task is executed. Tasks depending on the
     * flavour may be executed (in other projects) before the `flavourXyz` task, which would be too late for their
     * inputs.
     */
    private static void selectRequestedFlavour(final Project project) {
        def requested = project.gradle.startParameter.taskNames.collect { it.substring(it.lastIndexOf(':') + 1) }
        def selected = requested.reverse().find { name -> project.jitter.flavours.any { "flavour${it.name}" == name } }
        if (selected != null) {
            System.setProperty(FLAVOUR_PROP, selected.substring('flavour'.length()))
        }
    }

}
