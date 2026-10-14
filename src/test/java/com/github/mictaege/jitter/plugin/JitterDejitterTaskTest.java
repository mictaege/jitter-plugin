package com.github.mictaege.jitter.plugin;

import org.gradle.api.GradleException;
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

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The manifest cross check of the dejitter task is optional: a source set
 * without classes gets no stripped model and no manifest, and the dejitter
 * task must still build the source variant - the manifest input is null then,
 * so the file validation is skipped.
 */
public class JitterDejitterTaskTest {

    @TempDir
    Path workDir;

    @Test
    void toleratesAMissingManifestOfAnEmptySourceSet() throws Exception {
        final Project project = ProjectBuilder.builder().build();
        final JitterDejitterTask task = project.getTasks().create("dejitter", JitterDejitterTask.class);
        final File outputDir = new File(project.getBuildDir(), "jitter-dejittered/empty");
        final File srcDir = new File(project.getProjectDir(), "src/empty/java");
        srcDir.mkdirs();
        task.setSourceDirs(Collections.singleton(srcDir));
        task.setResourceDirs(Collections.emptySet());
        task.setOutputDir(outputDir);
        task.setManifestFile(new File(project.getBuildDir(), "jitter-stripped/empty/META-INF/jitter/empty-removed.txt"));

        task.run();

        assertThat(outputDir.isDirectory(), is(true));
    }

    /**
     * The symmetric direction of the cross check: a kept source file of a
     * class that the bytecode stripping removed leaks flavour specific code
     * into the source delivery and must fail the build.
     */
    @Test
    void failsWhenAKepSourceFileCorrespondsToARemovedClass() throws Exception {
        final Project project = ProjectBuilder.builder().build();
        final JitterDejitterTask task = project.getTasks().create("dejitter", JitterDejitterTask.class);
        final File outputDir = new File(project.getBuildDir(), "jitter-dejittered/main");
        final File srcDir = new File(project.getProjectDir(), "src/main/java");
        write(srcDir.toPath().resolve("com/example/Foo.java"),
                "package com.example;\n"
                + "public class Foo { }\n");
        task.setSourceDirs(Collections.singleton(srcDir));
        task.setResourceDirs(Collections.emptySet());
        task.setOutputDir(outputDir);
        final StripResult stripped = new StripResult();
        stripped.removedClass("com/example/Foo");
        final File manifest = new File(project.getBuildDir(),
                "jitter-stripped/main/META-INF/jitter/main-removed.txt");
        Files.createDirectories(manifest.getParentFile().toPath());
        RemovalManifest.write(manifest.toPath(), "Esa", stripped);
        task.setManifestFile(manifest);

        final GradleException failure = assertThrows(GradleException.class, task::run);

        assertThat(failure.getMessage(), containsString("keeps source files of classes that are removed"));
        assertThat(failure.getMessage(), containsString("com.example.Foo"));
    }

    private void write(final Path file, final String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

}
