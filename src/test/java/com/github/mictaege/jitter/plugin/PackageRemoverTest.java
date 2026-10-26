package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class PackageRemoverTest extends StripperTestFixture {

    @Test
    void shouldRemoveTheDirectClassesOfAGatedPackage() throws Exception {
        final Path classes = compile(
                "test/nasa/package-info.java",
                "@com.github.mictaege.jitter.api.OnlyIf(\"NASA\")\n"
                        + "package test.nasa;\n",
                "test/nasa/NasaDao.java",
                "package test.nasa;\n"
                        + "public class NasaDao { }\n",
                "test/nasa/deep/NestedDao.java",
                "package test.nasa.deep;\n"
                        + "public class NestedDao { }\n",
                "test/Plain.java",
                "package test;\n"
                        + "public class Plain { }\n");

        final StripContext ctx = loadCtx(classes);
        PackageRemover.remove(ctx);

        assertThat(ctx.nodes.containsKey("test/nasa/NasaDao"), is(false));
        assertThat(ctx.nodes.containsKey("test/nasa/package-info"), is(false));
        // a package gate covers only its direct classes, like under Spoon
        assertThat(ctx.nodes.containsKey("test/nasa/deep/NestedDao"), is(true));
        assertThat(ctx.nodes.containsKey("test/Plain"), is(true));
        assertThat(ctx.result.getRemovedClasses().contains("test/nasa/NasaDao"), is(true));
        assertThat(ctx.dirty.contains("test/nasa/NasaDao"), is(true));
    }

    @Test
    void shouldKeepUngatedPackages() throws Exception {
        final Path classes = compile(
                "test/esa/package-info.java",
                "@com.github.mictaege.jitter.api.OnlyIf(\"ESA\")\n"
                        + "package test.esa;\n",
                "test/esa/EsaDao.java",
                "package test.esa;\n"
                        + "public class EsaDao { }\n");

        final StripContext ctx = loadCtx(classes);
        PackageRemover.remove(ctx);

        assertThat(ctx.nodes.containsKey("test/esa/EsaDao"), is(true));
        assertThat(ctx.result.getRemovedClasses().isEmpty(), is(true));
    }

}
