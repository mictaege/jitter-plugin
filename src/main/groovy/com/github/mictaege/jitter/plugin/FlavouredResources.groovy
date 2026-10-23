package com.github.mictaege.jitter.plugin

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.FileCopyDetails
import org.gradle.api.file.FileTreeElement
import org.gradle.api.file.FileVisitDetails
import org.gradle.language.jvm.tasks.ProcessResources

import static org.gradle.api.file.DuplicatesStrategy.EXCLUDE

/**
 * Makes a {@link ProcessResources} task omit the resources of the inactive flavours and gives the resources of the
 * active flavour their final name.
 * <p>
 * Resources of the inactive flavours are recognized either by the name suffix convention (`_`flavour`/_!`flavour)
 * or by a jitter gate in the content of an XML-ish resource (see {@link XmlResourceGate}).
 * <p>
 * All of this happens while the task copies the resources. Changing the output of the task after it has been executed
 * would make it out-of-date in the next build, since Gradle has already taken a snapshot of the output by then.
 */
final class FlavouredResources {

    private FlavouredResources() {
        super()
    }

    static void configure(final Project project, final ProcessResources task) {
        // the flavours are configured after the plugin has been applied, so they are read as late as possible
        def flavourNames = { -> project.jitter.flavours.collect { it.name as String } }
        // final path -> source path of the resource which gets this path
        Map<String, String> winners = null

        JitterPlugin.dependOnActiveFlavour(project, task)
        task.duplicatesStrategy = EXCLUDE
        task.includeEmptyDirs = false
        task.exclude { FileTreeElement element -> omitted(element.name, flavourNames()) || gated(element) }
        task.doFirst {
            winners = null
        }
        task.eachFile { FileCopyDetails details ->
            if (winners == null) {
                winners = findWinners(task, flavourNames())
            }
            def path = finalPath(details.path, flavourNames())
            if (winners[finalPath(details.sourcePath, flavourNames())] != details.sourcePath) {
                details.exclude()
            } else {
                if (path != details.path) {
                    details.path = path
                }
                flavourGate(details)
            }
        }
    }

    /**
     * XML resources with flavour gates in their content (see {@link XmlFlavourTransformer}) are
     * rewritten while they are copied: the active flavour's variant of the document lands in the
     * processed resources, the unified source stays untouched for the full model and the generation.
     * Files without the gate namespace in their content are copied as they are.
     */
    private static void flavourGate(final FileCopyDetails details) {
        if (details.file == null || !XmlResourceGate.isXml(details.name)) {
            return
        }
        try {
            final byte[] content = java.nio.file.Files.readAllBytes(details.file.toPath())
            if (XmlFlavourTransformer.gated(
                    new String(content, java.nio.charset.StandardCharsets.ISO_8859_1))) {
                details.filter(XmlFlavourTransformer.GateFilter)
            }
        } catch (final java.io.IOException e) {
            throw new GradleException("jitter: could not read the resource ${details.path}", e)
        }
    }

    /**
     * Is the element a resource that carries a flavour gate in its own content (see
     * {@link XmlResourceGate}) and does the gate not include the active flavour? The suffix
     * convention above renames a per-flavour variant into place; a content gate expresses that
     * the resource does not exist at all in the other flavours.
     */
    private static boolean gated(final FileTreeElement element) {
        if (element.directory || element.file == null || !JitterUtil.anyFlavour()) {
            return false
        }
        if (!XmlResourceGate.isXml(element.name)) {
            return false
        }
        final List<String> flavours = XmlResourceGate.flavours(element.file)
        return flavours != null && flavours.every { flavour -> !JitterUtil.active(flavour) }
    }

    /**
     * Several resources may end up with the same final path, e.g. `Person.properties` and `Person_A.properties`. The
     * most specific one wins, which is the one that loses most of it's path. Among equally specific resources the last
     * one wins.
     */
    private static Map<String, String> findWinners(final ProcessResources task, final List<String> flavourNames) {
        def winners = new HashMap<String, String>()
        task.source.visit { FileVisitDetails source ->
            if (!source.directory && !gated(source)) {
                def sourcePath = source.relativePath.pathString
                def path = finalPath(sourcePath, flavourNames)
                def winner = winners[path]
                if (winner == null || winner.length() <= sourcePath.length()) {
                    winners[path] = sourcePath
                }
            }
        }
        winners
    }

    /** Is a file or folder with the given name specific for an inactive flavour? */
    static boolean omitted(final String name, final List<String> flavourNames) {
        flavourNames.any { f ->
            def postfix = JitterUtil.active(f) ? "_!${f}" : "_${f}"
            name.endsWith(postfix) || name.contains("${postfix}.")
        }
    }

    /** The path of a resource without the postfix of the active flavour. */
    static String finalPath(final String path, final List<String> flavourNames) {
        if (!JitterUtil.anyFlavour()) {
            return path
        }
        flavourNames.inject(path) { String p, String f ->
            JitterUtil.active(f) ? p.replace("_${f}", '') : p.replace("_!${f}", '')
        }
    }

}
