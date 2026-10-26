package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class AnnotationCleanerTest extends StripperTestFixture {

    @Test
    void shouldRemoveTheJitterAnnotationsFromTheSurvivingModel() throws Exception {
        final Path classes = compile("test/Person.java",
                "package test;\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                        + "import com.github.mictaege.jitter.api.Fork;\n"
                        + "public class Person {\n"
                        + "  @OnlyIf(\"ESA\")\n"
                        + "  private String esaOnly;\n"
                        + "  @Deprecated\n"
                        + "  public void plain() { }\n"
                        + "  @Fork(ifActive = \"ESA\", to = \"runEsa\")\n"
                        + "  public void run() { }\n"
                        + "  private void runEsa() { }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        OnlyIfRemover.removeOnlyIf(ctx);
        ForkApplier.applyForkMethods(ctx);
        AnnotationCleaner.clean(ctx);

        final ClassNode person = node(ctx, "test/Person");
        assertThat(person.visibleAnnotations, is(nullValue()));
        // the jitter annotation is gone from the field; the emptied list is
        // what removeIf leaves behind
        assertThat(field(person, "esaOnly").visibleAnnotations.isEmpty(), is(true));
        // non-jitter annotations stay
        assertThat(method(person, "plain", "()V").visibleAnnotations.stream()
                .filter(a -> a.desc.contains("Deprecated")).count(), is(1L));
        assertThat(method(person, "run", "()V").visibleAnnotations.isEmpty(), is(true));
        assertThat(ctx.dirty.contains("test/Person"), is(true));
    }

    @Test
    void shouldNotMarkUntouchedClassesDirty() throws Exception {
        final Path classes = compile("test/Plain.java",
                "package test;\n"
                        + "public class Plain {\n"
                        + "  public void plain() { }\n"
                        + "}\n");

        final StripContext ctx = loadCtx(classes);
        AnnotationCleaner.clean(ctx);

        assertThat(ctx.dirty.isEmpty(), is(true));
        assertThat(node(ctx, "test/Plain").visibleAnnotations, is(nullValue()));
    }

}
