package com.github.mictaege.jitter.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.io.IOException

/**
 * Verifies after the stripping that the surviving classes do not reference
 * any removed flavour specific class or member anymore. This keeps the
 * guarantee that conflicts and inconsistencies show up at build time.
 *
 * The removals of the own stripping come via the strip tasks. In addition the
 * manifests of the flavour dependencies are read, so dangling references
 * across module boundaries are caught as well - the check the source
 * transformation got for free, because dependent modules were compiled
 * against the already transformed sources. Classpath trees carry their
 * manifest as before, and the manifests of dependent projects of the same
 * build are read from their stripped trees: the jars of the build carry no
 * manifest anymore, because they are the deliverables.
 *
 * <p>Everything the verification reads is a task input - the stripped
 * classes and the resources of the verified source sets, the dependency
 * classpath, the removal manifests of the dependent projects and the
 * active flavour - and the stale resource report is the task output, so
 * the verification is up-to-date checked like any other task: a second
 * build without changes skips it.
 */
@DisableCachingByDefault(because = "the verification is a build check over the stripped model, its value is the up-to-date check, not the caching")
class JitterVerifyTask extends DefaultTask {

    JitterVerifyTask() {
        group = JitterUtil.GROUP
    }

    @Internal
    List<JitterStripTask> stripTasks = []

    /** The stripped classes the verification scans for dangling references. */
    @InputFiles
    Set<File> allClassesDirs = []

    /** Resource directories of the verified source sets, for the stale resource report. */
    @InputFiles
    Set<File> allResourcesDirs = []

    /** Runtime classpath to read the removal manifests of flavour dependencies from. */
    @Classpath
    FileCollection dependencyClasspath

    /** The active flavour, empty if no flavour is selected (the 150% model). */
    @Input
    String getActiveFlavour() {
        System.getProperty(JitterUtil.FLAVOUR_PROP) ?: ''
    }

    /**
     * The removal manifests of the dependent projects of the same build
     * (see {@link #siblingManifests()}): a change of a producer's stripped
     * model re-runs the verification, so a skip means the model is verified
     * against the CURRENT removals of the producers, not stale ones.
     */
    @InputFiles
    Set<File> getSiblingManifestFiles() {
        dependencyClasspath == null ? [] as Set : siblingManifests().keySet()
    }

    /** The stale resource report, the task's output artifact. */
    @OutputFile
    File getReportFile() {
        new File(project.buildDir, "reports/jitter/${name}-stale-resources.txt")
    }

    @TaskAction
    void run() {
        def removedClasses = [] as Set
        def removedMembers = [] as Set
        def errors = []

        stripTasks.each { task ->
            if (task.outputDir != null && task.manifestName != null) {
                // the removals are read from the strip task's MANIFEST, not
                // its in-memory result: a strip task loaded from the build
                // cache never executes, so its result object stays empty -
                // the manifest is part of the cached output and always there
                try {
                    RemovalManifest.readFile(
                            new File(task.outputDir,
                                    RemovalManifest.DIR + task.manifestName + RemovalManifest.SUFFIX),
                            removedClasses, removedMembers)
                } catch (IOException e) {
                    throw new GradleException(
                            "jitter: could not read the removal manifest of the stripped ${task.manifestName} model", e)
                }
            } else {
                // in-place stripping has no manifest - the result of the
                // executed task is the only source
                task.stripResult.removedClasses.each { removedClasses << it }
                task.stripResult.removedMembers.each { removedMembers << it }
            }
            task.stripResult.errors.each { errors << it }
        }

        dependencyClasspath?.files?.each { file ->
            try {
                RemovalManifest.read(file, removedClasses, removedMembers)
            } catch (IOException e) {
                throw new GradleException("jitter: could not read the flavour removal manifest of ${file}", e)
            }
        }
        readSiblingManifests(removedClasses, removedMembers)

        def violations = ReferenceVerifier.findDanglingReferences(allClassesDirs, removedClasses, removedMembers)

        if (!errors.isEmpty()) {
            throw new GradleException("jitter: stripping failed:\n  " + errors.join("\n  "))
        }
        if (!violations.isEmpty()) {
            throw new GradleException(
                    "jitter: the build contains references to flavour specific code"
                            + " that is not part of the active flavour:\n  "
                            + violations.join("\n  "))
        }
        reportStaleResources(removedClasses)
    }

    /**
     * The removal manifests of the dependent projects of this build, mapped
     * to their stripped jar: their jars carry no manifest anymore - they are
     * the deliverables - so the manifest of every project whose stripped
     * jar is on the dependency classpath is read from its stripped tree
     * directly. Derived at execution time like the template index of the
     * dejittering, so that every project of the build is evaluated. External
     * stripped artifacts have no manifest; like any non jitter dependency
     * they simply contribute no removals.
     */
    private Map<File, File> siblingManifests() {
        final def manifests = [:]
        if (dependencyClasspath == null) {
            return manifests
        }
        final def classpathFiles = dependencyClasspath.files
        project.rootProject.allprojects { p ->
            p.plugins.withId('java') {
                p.sourceSets.all { ss ->
                    // the stripped jar keeps the plain archive name of the
                    // former full jar - the full model moved to a 'full'
                    // classifier - so the deliverable is looked up through
                    // the strip jar task, not through the full jar's name
                    final def cap = ss.name.capitalize()
                    final def strippedJarTask = p.tasks.findByName(
                            ss.name == 'main' ? 'jitterJar' : "jitter${cap}Jar")
                    if (strippedJarTask == null
                            || !strippedJarTask.hasProperty('archiveFileName')) {
                        return
                    }
                    final def strippedJar = new File(p.buildDir,
                            "libs/${strippedJarTask.archiveFileName.get()}")
                    if (classpathFiles.contains(strippedJar)) {
                        manifests.put(new File(p.buildDir,
                                "jitter-stripped/${ss.name}/META-INF/jitter/${ss.name}-removed.txt"), strippedJar)
                    }
                }
            }
        }
        manifests
    }

    private void readSiblingManifests(final Set<String> removedClasses,
                                      final Set<String> removedMembers) {
        siblingManifests().keySet().each { manifest ->
            if (manifest.isFile()) {
                try {
                    RemovalManifest.readFile(manifest, removedClasses, removedMembers)
                } catch (IOException e) {
                    throw new GradleException(
                            "jitter: could not read the removal manifest of ${manifest}", e)
                }
            }
        }
    }

    /**
     * A resource whose owner class has been removed by the active flavour
     * still ships - it is no longer reachable through the classpath, but it
     * bloats the artifact and may carry content that belongs to the removed
     * flavour code. Reported as a warning, not a violation: flavour specific
     * resources usually follow the flavour suffix convention and are dropped
     * by the resource processing, so an unsuffixed resource of a gated class
     * is a candidate for review, not necessarily an error.
     *
     * The report is written to the task's output file as well, so the
     * warnings survive an up-to-date run and can be reviewed without
     * re-running the build.
     */
    private void reportStaleResources(final Set<String> removedClasses) {
        final def stale = [] as Set
        if (!removedClasses.isEmpty()) {
            allResourcesDirs.each { dir ->
                if (dir == null || !dir.isDirectory()) {
                    return
                }
                dir.eachFileRecurse { file ->
                    if (file.isFile()) {
                        def relative = dir.toPath().relativize(file.toPath()).toString()
                                .replace(File.separator, '/')
                        def dot = relative.lastIndexOf('.')
                        def withoutExt = dot < 0 ? relative : relative.substring(0, dot)
                        if (removedClasses.contains(withoutExt)) {
                            stale << relative
                            logger.warn("jitter: resource {} ships without its owner class {} (removed by the active flavour)",
                                    relative, withoutExt.replace('/', '.') + ".class")
                        }
                    }
                }
            }
        }
        reportFile.parentFile.mkdirs()
        reportFile.text = stale.sort().join('\n') + (stale.isEmpty() ? '' : '\n')
    }

}
