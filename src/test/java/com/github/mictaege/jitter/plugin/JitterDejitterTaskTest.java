package com.github.mictaege.jitter.plugin;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Collections;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * The manifest cross check of the dejitter task is optional: a source set
 * without classes gets no stripped model and no manifest, and the dejitter
 * task must still build the source variant - the manifest input is null then,
 * so the file validation is skipped.
 */
public class JitterDejitterTaskTest {

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

}
