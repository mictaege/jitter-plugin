package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class ClassFileWriterTest extends StripperTestFixture {

    @Test
    void shouldWriteTheStrippedTreeWithSerializedDirtyAndCopiedUntouchedClasses() throws Exception {
        final Path classes = compile(
                "test/One.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class One {\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public void nasaOnly() { }\n"
                        + "}\n",
                "test/Two.java",
                "package test;\n"
                        + "public class Two { }\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        AnnotationCleaner.clean(ctx);
        final Path target = workDir.resolve("stripped");
        ClassFileWriter.writeBack(java.util.List.of(classes), target, ctx);

        assertThat(Files.exists(target.resolve("test/One.class")), is(true));
        assertThat(Files.exists(target.resolve("test/Two.class")), is(true));
        // the source stays untouched
        assertThat(Files.exists(classes.resolve("test/One.class")), is(true));

        // the dirty node is really serialized: the stripped class has no
        // flavour annotation anymore, unlike the untouched source
        final Path strippedOne = target.resolve("test/One.class");
        assertThat(new String(java.nio.file.Files.readAllBytes(strippedOne),
                java.nio.charset.StandardCharsets.ISO_8859_1).contains("OnlyIf"), is(false));
        assertThat(new String(java.nio.file.Files.readAllBytes(classes.resolve("test/One.class")),
                java.nio.charset.StandardCharsets.ISO_8859_1).contains("OnlyIf"), is(true));
    }

    @Test
    void shouldWriteBackInPlaceAndDeleteRemovedClasses() throws Exception {
        final Path classes = compile(
                "test/NasaOnly.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "@OnlyIf(\"NASA\")\n"
                        + "public class NasaOnly { }\n",
                "test/One.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "public class One {\n"
                        + "  @OnlyIf(\"NASA\")\n"
                        + "  public void nasaOnly() { }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        AnnotationCleaner.clean(ctx);
        ClassFileWriter.writeBack(java.util.List.of(classes), null, ctx);

        assertThat(Files.exists(classes.resolve("test/NasaOnly.class")), is(false));
        // emptied directories are cleaned up as well
        assertThat(Files.exists(classes.resolve("test")), is(true));
        assertThat(Files.exists(classes.resolve("test/One.class")), is(true));
        // the dirty class was rewritten in place, without the annotation
        assertThat(new String(java.nio.file.Files.readAllBytes(classes.resolve("test/One.class")),
                java.nio.charset.StandardCharsets.ISO_8859_1).contains("OnlyIf"), is(false));
    }

    @Test
    void shouldLogTheCollectedInfos() throws Exception {
        final Path classes = compile(
                "test/NasaOnly.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "@OnlyIf(\"NASA\")\n"
                        + "public class NasaOnly { }\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        ClassFileWriter.writeBack(java.util.List.of(classes), null, ctx);

        assertThat(ctx.result.getInfos().isEmpty(), is(false));
    }

}
