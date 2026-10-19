package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class ConstantAuditorTest extends StripperTestFixture {

    @Test
    void shouldFailOnGatedStaticConstantInAnyFlavour() throws Exception {
        final Path classes = compile("test/Constants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Constants {\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  public static final int NASA_ONLY = 42;\n"
                + "  public static final int COMMON = 1;\n"
                + "}\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        // the active flavour Esa is not part of the gate, the field is
        // removed in this run - but the audit is declaration side: the
        // pattern itself fails the build, whatever flavour is active
        assertThat(ctx.result.getErrors().size(), is(1));
        assertThat(ctx.result.getErrors().get(0), containsString("test.Constants#NASA_ONLY"));
        assertThat(ctx.result.getErrors().get(0), containsString("flavour variant"));
        assertThat(ctx.result.getErrors().get(0), containsString("42"));
        assertThat(ctx.result.getWarnings().isEmpty(), is(true));
    }

    @Test
    void shouldFailOnGatedConstantEvenOfTheActiveFlavour() throws Exception {
        final Path classes = compile("test/ActiveConstants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class ActiveConstants {\n"
                + "  @OnlyIf(\"Esa\")\n"
                + "  public static final int ESA_ONLY = 42;\n"
                + "}\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        assertThat(ctx.result.getErrors().size(), is(1));
        assertThat(ctx.result.getErrors().get(0), containsString("test.ActiveConstants#ESA_ONLY"));
    }

    @Test
    void shouldFailOnGatedConstantInTheFullModel() throws Exception {
        final Path classes = compile("test/Constants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Constants {\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  public static final int NASA_ONLY = 42;\n"
                + "}\n");

        final StripContext ctx = loadCtx(classes);
        System.clearProperty(JitterUtil.FLAVOUR_PROP);
        ConstantAuditor.audit(ctx);

        assertThat(ctx.result.getErrors().size(), is(1));
        assertThat(ctx.result.getErrors().get(0), containsString("NASA_ONLY"));
    }

    @Test
    void shouldNotFailOnConstantsWhoseGateCoversAllFlavours() throws Exception {
        final Path classes = compile("test/AllFlavourConstants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class AllFlavourConstants {\n"
                + "  @OnlyIf({\"Esa\", \"Nasa\"})\n"
                + "  public static final int EVERYWHERE = 42;\n"
                + "}\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        assertThat(ctx.result.getErrors().isEmpty(), is(true));
        assertThat(ctx.result.getWarnings().isEmpty(), is(true));
    }

    @Test
    void shouldNotFailOnMarkedGatedConstants() throws Exception {
        final Path classes = compile("test/MarkedConstants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class MarkedConstants {\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final int NASA_ONLY = 42;\n"
                + "}\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        assertThat(ctx.result.getErrors().isEmpty(), is(true));
        assertThat(ctx.result.getWarnings().isEmpty(), is(true));
    }

    @Test
    void shouldFailOnMarkerOnNonConstantField() throws Exception {
        final Path classes = compile("test/WronglyMarked.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class WronglyMarked {\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final Integer BOXED = 5;\n"
                + "}\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        assertThat(ctx.result.getErrors().size(), is(1));
        assertThat(ctx.result.getErrors().get(0), containsString("test.WronglyMarked#BOXED"));
        assertThat(ctx.result.getErrors().get(0), containsString("meaningless"));
    }

    @Test
    void shouldFailOnMarkerWithoutVariantGate() throws Exception {
        final Path classes = compile("test/WronglyMarked.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "public class WronglyMarked {\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final int UNGATED = 5;\n"
                + "}\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        assertThat(ctx.result.getErrors().size(), is(1));
        assertThat(ctx.result.getErrors().get(0), containsString("test.WronglyMarked#UNGATED"));
        assertThat(ctx.result.getErrors().get(0), containsString("meaningless"));
    }

    @Test
    void shouldFailOnGatedInstanceConstantInAnyFlavour() throws Exception {
        final Path classes = compile("test/InstanceConstants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class InstanceConstants {\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  public final int NASA_ONLY = 43;\n"
                + "}\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        assertThat(ctx.result.getErrors().size(), is(1));
        assertThat(ctx.result.getErrors().get(0), containsString("test.InstanceConstants#NASA_ONLY"));
        assertThat(ctx.result.getErrors().get(0), containsString("43"));
    }

    @Test
    void shouldNotFailOnNonInlinableFields() throws Exception {
        final Path classes = compile("test/PlainFields.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class PlainFields {\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  private String mutable;\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  public static final Integer BOXED = 5;\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  public static final String COMPUTED = String.valueOf(7);\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  public final int INITIALIZED = Integer.parseInt(\"9\");\n"
                + "}\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        assertThat(ctx.result.getErrors().isEmpty(), is(true));
        assertThat(ctx.result.getWarnings().isEmpty(), is(true));
    }

    @Test
    void shouldFailOnConstantsOfARemovedClass() throws Exception {
        final Path classes = compile(
                "test/NasaOnly.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "@OnlyIf(\"Nasa\")\n"
                + "public class NasaOnly {\n"
                + "  public static final String SHARED_NAME = \"nasa\";\n"
                + "  public final int instanceConstant = 3;\n"
                + "}\n",
                "test/Plain.java",
                "package test;\n"
                + "public class Plain { }\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        // the class gate makes the constants flavour variant, no matter
        // that the whole class is removed in most flavours: they must be
        // reviewed and marked
        assertThat(ctx.result.getErrors().size(), is(2));
        assertThat(ctx.result.getErrors().get(0), containsString("test.NasaOnly#SHARED_NAME"));
        assertThat(ctx.result.getErrors().get(1), containsString("test.NasaOnly#instanceConstant"));
        assertThat(ctx.result.getWarnings().isEmpty(), is(true));
    }

    @Test
    void shouldFailOnPrivateConstantsOfARemovedClass() throws Exception {
        final Path classes = compile(
                "test/NasaOnly.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "@OnlyIf(\"Nasa\")\n"
                + "public class NasaOnly {\n"
                + "  private static final String NAME = \"nasa\";\n"
                + "}\n",
                "test/Plain.java",
                "package test;\n"
                + "public class Plain { }\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        // private constants are inlined within their own class just like
        // any other constant
        assertThat(ctx.result.getErrors().size(), is(1));
        assertThat(ctx.result.getErrors().get(0), containsString("test.NasaOnly#NAME"));
    }

    @Test
    void shouldFailOnConstantsOfAGatedPackage() throws Exception {
        final Path classes = compile(
                "test/package-info.java",
                "@OnlyIf(\"Nasa\")\n"
                + "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n",
                "test/Plain.java",
                "package test;\n"
                + "public class Plain {\n"
                + "  public static final String NAME = \"plain\";\n"
                + "}\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        // the package gate makes the constants of all direct classes
        // flavour variant
        assertThat(ctx.result.getErrors().size(), is(1));
        assertThat(ctx.result.getErrors().get(0), containsString("test.Plain#NAME"));
    }

    @Test
    void shouldNotFailOnMarkedConstantsOfARemovedClass() throws Exception {
        final Path classes = compile(
                "test/NasaOnly.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "@OnlyIf(\"Nasa\")\n"
                + "public class NasaOnly {\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final String SHARED_NAME = \"nasa\";\n"
                + "}\n",
                "test/Plain.java",
                "package test;\n"
                + "public class Plain { }\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        assertThat(ctx.result.getErrors().isEmpty(), is(true));
        assertThat(ctx.result.getWarnings().isEmpty(), is(true));
    }

    @Test
    void shouldFailOnAlteredConstantsWithDifferingValues() throws Exception {
        final Path classes = compile(
                "test/PersonValidator.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.Alter;\n"
                + "@Alter(ifActive = \"Esa\", with = \"PersonValidatorA\")\n"
                + "public class PersonValidator {\n"
                + "  public static final int LIMIT = 5;\n"
                + "  public static final String NAME = \"full\";\n"
                + "}\n",
                "test/PersonValidatorA.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "@OnlyIf(\"Esa\")\n"
                + "public class PersonValidatorA {\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final int LIMIT = 7;\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final String NAME = \"full\";\n"
                + "}\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        assertThat(ctx.result.getErrors().size(), is(1));
        assertThat(ctx.result.getErrors().get(0), containsString("test.PersonValidator"));
        assertThat(ctx.result.getErrors().get(0), containsString("LIMIT"));
        assertThat(ctx.result.getErrors().get(0), containsString("5"));
        assertThat(ctx.result.getErrors().get(0), containsString("7"));
        assertThat(ctx.result.getWarnings().isEmpty(), is(true));
    }

    @Test
    void shouldFailOnAlteredConstantsOfInactiveTemplates() throws Exception {
        final Path classes = compile(
                "test/PersonValidator.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.Alter;\n"
                + "@Alter(ifActive = \"Nasa\", with = \"PersonValidatorB\")\n"
                + "public class PersonValidator {\n"
                + "  public static final int LIMIT = 5;\n"
                + "}\n",
                "test/PersonValidatorB.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "@OnlyIf(\"Nasa\")\n"
                + "public class PersonValidatorB {\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final int LIMIT = 7;\n"
                + "}\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        // the differing values fail the build no matter which flavour is
        // active - the template's constant is marked, so only the unmarked
        // constant of the target fails
        assertThat(ctx.result.getErrors().size(), is(1));
        assertThat(ctx.result.getErrors().get(0), containsString("@Alter target"));
        assertThat(ctx.result.getErrors().get(0), containsString("LIMIT"));
        assertThat(ctx.result.getWarnings().isEmpty(), is(true));
    }

    @Test
    void shouldNotFailOnMarkedConstantsOfTheAlterTarget() throws Exception {
        final Path classes = compile(
                "test/PersonValidator.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.Alter;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "@Alter(ifActive = \"Esa\", with = \"PersonValidatorA\")\n"
                + "public class PersonValidator {\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final int LIMIT = 5;\n"
                + "}\n",
                "test/PersonValidatorA.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.InlinedConstantApproved;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "@OnlyIf(\"Esa\")\n"
                + "public class PersonValidatorA {\n"
                + "  @InlinedConstantApproved\n"
                + "  public static final int LIMIT = 7;\n"
                + "}\n");

        final StripContext ctx = loadCtx(classes);
        ConstantAuditor.audit(ctx);

        assertThat(ctx.result.getErrors().isEmpty(), is(true));
        assertThat(ctx.result.getWarnings().isEmpty(), is(true));
    }

    @Test
    void shouldHookIntoTheStrippingPhases() throws Exception {
        final Path classes = compile("test/Constants.java",
                "package test;\n"
                + "import com.github.mictaege.jitter.api.OnlyIf;\n"
                + "public class Constants {\n"
                + "  @OnlyIf(\"Nasa\")\n"
                + "  public static final int NASA_ONLY = 42;\n"
                + "}\n");

        final StripResult result = new BytecodeStripper().strip(
                java.util.Collections.singletonList(classes), null, null,
                java.util.Arrays.asList("Esa", "Nasa"));

        assertThat(result.getErrors().size(), is(1));
        assertThat(result.getErrors().get(0), containsString("NASA_ONLY"));
        // the audit does not change the stripping itself
        assertThat(result.getRemovedMembers()
                .contains("test/Constants.NASA_ONLYI"), is(true));
    }

}
