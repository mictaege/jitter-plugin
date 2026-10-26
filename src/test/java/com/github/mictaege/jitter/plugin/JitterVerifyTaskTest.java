package com.github.mictaege.jitter.plugin;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsInstanceOf.instanceOf;

/**
 * The stale resource report of the verify task: a resource without a file
 * extension is no stale resource match candidate crash, it just has no
 * extension to strip.
 */
public class JitterVerifyTaskTest {

    @TempDir
    Path workDir;

    @Test
    void toleratesResourcesWithoutAnExtension() throws IOException {
        final Project project = ProjectBuilder.builder().build();
        final JitterStripTask strip = project.getTasks().create("strip", JitterStripTask.class);
        final File outputDir = workDir.resolve("jitter-stripped/main").toFile();
        final File manifest = new File(outputDir, "META-INF/jitter/main-removed.txt");
        Files.createDirectories(manifest.getParentFile().toPath());
        Files.writeString(manifest.toPath(),
                "# jitter flavour removals of flavour 'Esa'\nC test/Removed\n", StandardCharsets.UTF_8);
        strip.setOutputDir(outputDir);
        strip.setManifestName("main");

        final File resources = workDir.resolve("resources").toFile();
        Files.createDirectories(resources.toPath());
        Files.writeString(resources.toPath().resolve("dummy"), "no extension",
                StandardCharsets.UTF_8);

        final JitterVerifyTask verify = project.getTasks().create("verify", JitterVerifyTask.class);
        verify.setStripTasks(List.of(strip));
        verify.setAllClassesDirs(Collections.emptySet());
        verify.setAllResourcesDirs(Collections.singleton(resources));

        try {
            verify.run();
        } catch (final Exception e) {
            assertThat(e, instanceOf(RuntimeException.class));
            throw new AssertionError("the verify task must tolerate extension-less resources", e);
        }
    }

}
