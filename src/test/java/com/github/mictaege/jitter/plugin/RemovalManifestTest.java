package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class RemovalManifestTest {

    @TempDir
    Path workDir;

    @Test
    void shouldWriteAndReadTheRemovalsOfAStrippedSourceSet() throws Exception {
        final StripResult result = new StripResult();
        result.removedClass("com/github/mictaege/Gone");
        result.removedMember("com/github/mictaege/Greeter.greetB()Ljava/lang/String;");

        final Path target = workDir.resolve("out/" + RemovalManifest.DIR + "main" + RemovalManifest.SUFFIX);
        RemovalManifest.write(target, "A", result);

        assertThat(Files.exists(target), is(true));

        final Set<String> removedClasses = new HashSet<>();
        final Set<String> removedMembers = new HashSet<>();
        RemovalManifest.read(workDir.resolve("out").toFile(), removedClasses, removedMembers);

        assertThat(removedClasses, is(Set.of("com/github/mictaege/Gone")));
        assertThat(removedMembers, is(Set.of("com/github/mictaege/Greeter.greetB()Ljava/lang/String;")));
    }

    @Test
    void shouldReadTheManifestsInsideAJar() throws Exception {
        final StripResult result = new StripResult();
        result.removedMember("com/github/mictaege/Greeter.greetB()Ljava/lang/String;");

        final File jar = workDir.resolve("lib.jar").toFile();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar.toPath()))) {
            zip.putNextEntry(new ZipEntry(RemovalManifest.DIR + "main" + RemovalManifest.SUFFIX));
            zip.write(("# header\n"
                    + "M com/github/mictaege/Greeter.greetB()Ljava/lang/String;\n").getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("com/github/mictaege/Greeter.class"));
            zip.closeEntry();
        }

        final Set<String> removedClasses = new HashSet<>();
        final Set<String> removedMembers = new HashSet<>();
        RemovalManifest.read(jar, removedClasses, removedMembers);

        assertThat(removedMembers, is(Set.of("com/github/mictaege/Greeter.greetB()Ljava/lang/String;")));
        assertThat(removedClasses.isEmpty(), is(true));
    }

    @Test
    void shouldIgnoreEntriesWithoutManifest() throws IOException {
        final Set<String> removedClasses = new HashSet<>();
        final Set<String> removedMembers = new HashSet<>();

        RemovalManifest.read(workDir.resolve("nope").toFile(), removedClasses, removedMembers);
        RemovalManifest.read(workDir.toFile(), removedClasses, removedMembers);

        assertThat(removedClasses.isEmpty(), is(true));
        assertThat(removedMembers.isEmpty(), is(true));
    }

}
