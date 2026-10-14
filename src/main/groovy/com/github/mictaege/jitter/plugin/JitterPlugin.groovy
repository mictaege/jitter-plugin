package com.github.mictaege.jitter.plugin

import org.gradle.api.GradleException
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
            def roles = sourceSetRoles(project)
            def stripTasks = []
            def dejitterTasks = []
            def allClassesDirs = [] as Set
            def allResourcesDirs = [] as Set
            def manifestClasspath = project.files()

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
                    stripTask.manifestName = srcSet.name
                    stripTasks << stripTask

                    stripTask.outputDir = new File(project.buildDir, "jitter-stripped/${srcSet.name}")

                    // the dejittered source variant: the original sources
                    // minus the flavour specific code of the active flavour,
                    // as source level surgery that keeps comments, formatting
                    // and line numbers - for source deliveries. Its removals
                    // are cross checked against the manifest of the stripped
                    // model, so it runs after the strip task
                    def dejitterTask = project.task(
                            "dejitter${srcSet.name.capitalize()}", type: JitterDejitterTask)
                    dejitterTasks << dejitterTask
                    dejitterTask.sourceDirs = srcSet.java.srcDirs
                    def resourcesDir = srcSet.output.resourcesDir
                    if (resourcesDir != null) {
                        dejitterTask.resourceDirs = [resourcesDir]
                    }
                    dejitterTask.compileClasspath = srcSet.compileClasspath
                    dejitterTask.manifestFile = new File(project.buildDir,
                            "jitter-stripped/${srcSet.name}/META-INF/jitter/${srcSet.name}-removed.txt")
                    dejitterTask.dependsOn stripTask, project.tasks.named(srcSet.processResourcesTaskName)
                    dejitterTask.outputDir = new File(project.buildDir, "jitter-dejittered/${srcSet.name}")
                    // the runtime classpath carries the removal manifests of
                    // the flavour dependencies (their stripped runtime
                    // artifacts); collecting it per source set lets the
                    // aggregate verify check the surviving classes against
                    // the removals of the modules this one depends on
                    manifestClasspath = manifestClasspath + srcSet.runtimeClasspath
                    if (!roles.packaging.contains(srcSet.name) && !roles.api.contains(srcSet.name)) {
                        // test-like source sets: the stripped model is their
                        // runtime, so the source set output points at the
                        // stripped tree (test tasks and IDEs pick it up from
                        // there). The compiled classes stay untouched, so
                        // switching flavours just re-strips them.
                        srcSet.output.classesDirs = project.files(stripTask)
                    }
                    // packaging and api source sets keep their full compiled
                    // classes as the compile identity; only their runtime
                    // artifact is the stripped model, see the wiring below
                }

                // the aggregate verify checks the classes that ship or run:
                // the stripped trees of the packaging and api source sets
                // instead of the full classes, the classes of every other
                // source set as they are (test-like: the reassigned output)
                if ((roles.packaging.contains(srcSet.name) || roles.api.contains(srcSet.name))
                        && !project.jitter.excludeSrcSets.contains(srcSet.name)) {
                    def stripTask = project.tasks.findByName("jitterStrip${srcSet.name.capitalize()}")
                    if (stripTask != null && stripTask.outputDir != null) {
                        allClassesDirs.add(stripTask.outputDir)
                    }
                } else {
                    allClassesDirs.addAll(srcSet.output.classesDirs.files)
                }
                // for the stale resource report of the aggregate verify
                if (!project.jitter.excludeSrcSets.contains(srcSet.name)
                        && srcSet.output.resourcesDir != null) {
                    allResourcesDirs.add(srcSet.output.resourcesDir)
                }
            }

            if (!stripTasks.isEmpty()) {
                // the packaging source sets with their strip tasks: the ones
                // whose stripped model is the module's runtime artifact
                def packagingSets = roles.packaging.collect { name ->
                    [name: name,
                     srcSet: project.sourceSets.findByName(name),
                     strip: project.tasks.findByName("jitterStrip${name.capitalize()}")]
                }.findAll { it.strip != null && it.strip.outputDir != null }

                // a scoped verify checks only the stripped tree of one
                // packaging source set against the members removed from it.
                // It lets artifact tasks fail early on dangling references,
                // without pulling the compilation of source sets that consume
                // the artifact.
                packagingSets.each { p ->
                    def cap = p.name.capitalize()
                    def verify = project.task(
                            p.name == 'main' ? 'jitterVerifyMain' : "jitterVerify${cap}", type: JitterVerifyTask)
                    verify.stripTasks = [p.strip]
                    verify.allClassesDirs = [p.strip.outputDir]
                    verify.allResourcesDirs = [p.srcSet.output.resourcesDir]
                    verify.dependencyClasspath = p.srcSet.runtimeClasspath
                    verify.dependsOn p.strip

                    // the stripped runtime artifact: replaces the full jar
                    // as the outgoing runtime variant, so consumers resolve
                    // the flavour model at runtime while compiling against
                    // the full classes
                    def strippedJar = project.tasks.create(
                            p.name == 'main' ? 'jitterJar' : "jitter${cap}Jar",
                            org.gradle.api.tasks.bundling.Jar) {
                        group = JitterUtil.GROUP
                        destinationDirectory = new File(project.buildDir, 'jitter-libs')
                        from(p.strip.outputDir)
                        from(p.srcSet.output.resourcesDir)
                        dependsOn p.strip, project.tasks.named(p.srcSet.processResourcesTaskName)
                    }
                    strippedJar.dependsOn verify
                    def fullJar = project.tasks.findByName(p.name == 'main' ? 'jar' : "${p.name}Jar")
                    if (fullJar != null) {
                        strippedJar.archiveFileName.set(fullJar.archiveFileName)
                    }
                    def runtimeElements = project.configurations.findByName(
                            p.name == 'main' ? 'runtimeElements' : "${p.name}RuntimeElements")
                    if (runtimeElements != null) {
                        runtimeElements.outgoing.artifacts.removeIf { true }
                        runtimeElements.outgoing.artifact(strippedJar)
                    } else if (p.name != 'main') {
                        JitterUtil.log().warn("jitter: the packaging source set '{}' has no '{}RuntimeElements'"
                                + " configuration - its stripped runtime artifact is built but no consumer"
                                + " resolves it", p.name, p.name)
                    }

                    if (p.name == 'main') {
                        def warTask = project.tasks.findByName('war')
                        if (warTask != null) {
                            warTask.classpath = project.files(p.strip.outputDir, p.srcSet.output.resourcesDir,
                                    project.configurations.runtimeClasspath)
                            warTask.dependsOn verify
                        }
                    }
                }

                // the api source sets: the full compiled classes stay the
                // compile artifact (<name>ApiElements), the stripped jar of
                // the packaging trees plus their own tree replaces the
                // outgoing <name>RuntimeElements variant, so that code which
                // runs against the deployed flavour model sees the same
                // @Alter/@Fork/@OnlyIf transformations as the application
                roles.api.each { name ->
                    def strip = project.tasks.findByName("jitterStrip${name.capitalize()}")
                    def srcSet = project.sourceSets.findByName(name)
                    if (strip == null || strip.outputDir == null) {
                        return
                    }
                    def cap = name.capitalize()
                    def content = packagingSets.collect { it } + [[name: name, srcSet: srcSet, strip: strip]]
                    def contentStrips = content.collect { it.strip }
                    def contentSrcSets = content.collect { it.srcSet }

                    // the scoped verify of the api artifact: checks the
                    // stripped trees of its content against their removals
                    // and the removals of the flavour dependencies, so the
                    // artifact task fails early on dangling references
                    def verify = project.task("jitterVerify${cap}", type: JitterVerifyTask)
                    verify.stripTasks = contentStrips
                    verify.allClassesDirs = contentStrips.collect { it.outputDir }
                    verify.allResourcesDirs = contentSrcSets.collect { it.output.resourcesDir }
                    verify.dependencyClasspath = contentSrcSets.collect { it.runtimeClasspath }
                            .inject { a, b -> a + b } ?: project.files()
                    verify.dependsOn contentStrips

                    def strippedApiJar = project.tasks.create("jitter${cap}Jar",
                            org.gradle.api.tasks.bundling.Jar) {
                        group = JitterUtil.GROUP
                        destinationDirectory = new File(project.buildDir, 'jitter-libs')
                        // the trees may carry the same package-info;
                        // first (packaging) wins, like the old jar
                        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
                        contentStrips.each { s -> from(s.outputDir) }
                        contentSrcSets.each { s -> from(s.output.resourcesDir) }
                        dependsOn contentStrips
                    }
                    strippedApiJar.dependsOn verify
                    contentSrcSets.each { s ->
                        strippedApiJar.dependsOn project.tasks.named(s.processResourcesTaskName)
                    }
                    def fullApiJar = project.tasks.findByName("${name}Jar")
                    if (fullApiJar != null) {
                        strippedApiJar.archiveFileName.set(fullApiJar.archiveFileName)
                    }
                    def apiRuntime = project.configurations.findByName("${name}RuntimeElements")
                    if (apiRuntime != null) {
                        apiRuntime.outgoing.artifacts.removeIf { true }
                        apiRuntime.outgoing.artifact(strippedApiJar)
                    } else {
                        JitterUtil.log().warn("jitter: the api source set '{}' has no '{}RuntimeElements'"
                                + " configuration - its stripped runtime artifact is built but no consumer"
                                + " resolves it", name, name)
                    }
                }

                def verifyTask = project.task("jitterVerify", type: JitterVerifyTask)
                verifyTask.stripTasks = stripTasks
                verifyTask.allClassesDirs = allClassesDirs
                verifyTask.allResourcesDirs = allResourcesDirs
                verifyTask.dependencyClasspath = manifestClasspath
                verifyTask.dependsOn stripTasks

                // the aggregate dejittering of the project: one task for all
                // source sets, like the aggregate verify
                project.task("dejitter") {
                    group = JitterUtil.GROUP
                    dependsOn dejitterTasks
                }

                project.tasks.withType(Test).configureEach { it.dependsOn verifyTask }
                project.tasks.withType(JavaExec).configureEach { it.dependsOn verifyTask }
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

    /**
     * Resolves the roles of the project's source sets:
     * {@code packagingSrcSets} - their stripped model is the module's runtime
     * artifact - and {@code apiSrcSets} - their full classes stay the compile
     * artifact, the stripped model is the runtime artifact for consumers.
     * Every source set that carries neither role is test-like: the stripped
     * model is its runtime identity.
     *
     * <p>Names that are no source set of the project are ignored, like
     * excluded source sets - the defaults name the conventional {@code main}
     * and {@code testFixtures} sets, which may well not exist in a given
     * project. A name that is neither a source set nor excluded nor one of
     * the conventional defaults is reported: most likely a typo in a custom
     * layout. A source set must not carry both roles.
     */
    private static Map sourceSetRoles(final Project project) {
        def packaging = new HashSet<String>()
        def api = new HashSet<String>()
        def conventional = ['main', 'testFixtures'] as Set
        def resolve = { String name, Set<String> role, String roleProperty ->
            if (project.sourceSets.findByName(name) == null) {
                if (!project.jitter.excludeSrcSets.contains(name) && !conventional.contains(name)) {
                    JitterUtil.log().warn("jitter: {} names the unknown source set '{}', ignored",
                            roleProperty, name)
                }
            } else {
                role.add(name)
            }
        }
        project.jitter.packagingSrcSets.each { resolve(it, packaging, 'packagingSrcSets') }
        project.jitter.apiSrcSets.each { resolve(it, api, 'apiSrcSets') }
        final def overlap = packaging.intersect(api)
        if (!overlap.isEmpty()) {
            throw new GradleException(
                    "jitter: the source sets ${overlap} are both packagingSrcSets and apiSrcSets,"
                            + " but a source set carries exactly one role")
        }
        return [packaging: packaging, api: api]
    }

}
