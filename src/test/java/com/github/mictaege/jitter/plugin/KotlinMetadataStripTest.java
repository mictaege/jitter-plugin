package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * The {@code @kotlin.Metadata} of stripped Kotlin classes must match the
 * stripped bytecode (issue mictaege/jitter-plugin#48): removed enum entries,
 * constructors and their parameter names/types disappear from the metadata's
 * {@code d2} string table, fully stripped file facades are deleted with their
 * class file, and untouched classes pass through byte-identical.
 */
public class KotlinMetadataStripTest {

    private static final String FLAVOUR_PROP = "jitter.active.flavour";

    @TempDir
    Path workDir;

    @BeforeEach
    void selectEsaFlavour() {
        System.setProperty(FLAVOUR_PROP, "ESA");
    }

    @AfterEach
    void resetFlavour() {
        System.clearProperty(FLAVOUR_PROP);
    }

    @Test
    void rewritesEnumMetadataWhenEntriesAreStripped() throws Exception {
        final Path classes = KotlinFixtures.compileKotlin(workDir,
                "test/Brand.kt",
                "package test\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf\n"
                        + "enum class Brand {\n"
                        + "    @OnlyIf(\"ESA\") ALPHA,\n"
                        + "    @OnlyIf(\"ESA\") BETA,\n"
                        + "    @OnlyIf(\"NASA\") GAMMA,\n"
                        + "    @OnlyIf(\"NASA\") DELTA\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        final ClassNode brand = KotlinFixtures.read(classes, "test/Brand");
        assertThat(brand.fields.stream().anyMatch(f -> f.name.equals("ALPHA")), is(true));
        assertThat(brand.fields.stream().anyMatch(f -> f.name.equals("GAMMA")), is(false));
        final List<String> d2 = KotlinFixtures.d2Of(KotlinFixtures.metadataOf(brand));
        assertThat(d2, hasItem("ALPHA"));
        assertThat(d2, hasItem("BETA"));
        assertThat(d2, not(hasItem("GAMMA")));
        assertThat(d2, not(hasItem("DELTA")));
        assertThat(result.getErrors().isEmpty(), is(true));
    }

    @Test
    void removesStrippedConstructorLeakFromMetadata() throws Exception {
        final Path classes = KotlinFixtures.compileKotlin(workDir,
                "test/Daos.kt",
                "package test\n"
                        + "class FlavourADao\n"
                        + "class FlavourBDao\n",
                "test/Helper.kt",
                "package test\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf\n"
                        + "class Helper {\n"
                        + "    @OnlyIf(\"ESA\")\n"
                        + "    constructor(common: String, aDao: FlavourADao)\n"
                        + "    @OnlyIf(\"NASA\")\n"
                        + "    constructor(common: String, bDao: FlavourBDao)\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        final ClassNode helper = KotlinFixtures.read(classes, "test/Helper");
        assertThat(helper.methods.stream().anyMatch(m -> m.desc.contains("FlavourBDao")), is(false));
        final List<String> d2 = KotlinFixtures.d2Of(KotlinFixtures.metadataOf(helper));
        assertThat(d2, not(hasItem("bDao")));
        assertThat(d2, not(hasItem("Ltest/FlavourBDao;")));
        assertThat(d2, not(hasItem("(Ljava/lang/String;Ltest/FlavourBDao;)V")));
        assertThat(d2, hasItem("aDao"));
        assertThat(result.getErrors().isEmpty(), is(true));
    }

    @Test
    void deletesFullyStrippedFileFacadeWithItsClassFile() throws Exception {
        final Path classes = KotlinFixtures.compileKotlin(workDir,
                "test/Facade.kt",
                "package test\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf\n"
                        + "@OnlyIf(\"NASA\")\n"
                        + "fun nasaOnly(): String = \"nasa\"\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        assertThat(Files.exists(classes.resolve("test/FacadeKt.class")), is(false));
        assertThat(result.getRemovedClasses(), hasItem("test/FacadeKt"));
        assertThat(result.getErrors().isEmpty(), is(true));
    }

    @Test
    void removesNestedClassesAndJitterAnnotationsFromMetadata() throws Exception {
        final Path classes = KotlinFixtures.compileKotlin(workDir,
                "test/Gadgets.kt",
                "package test\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf\n"
                        + "class Gadgets {\n"
                        + "    @OnlyIf(\"ESA\")\n"
                        + "    fun esaOnly(): String = \"esa\"\n"
                        + "    @OnlyIf(\"NASA\")\n"
                        + "    class NasaGadget\n"
                        + "}\n");

        final StripResult result = new BytecodeStripper().strip(classes);

        assertThat(Files.exists(classes.resolve("test/Gadgets$NasaGadget.class")), is(false));
        assertThat(result.getRemovedClasses(), hasItem("test/Gadgets$NasaGadget"));
        final ClassNode gadgets = KotlinFixtures.read(classes, "test/Gadgets");
        final List<String> d2 = KotlinFixtures.d2Of(KotlinFixtures.metadataOf(gadgets));
        assertThat(d2, hasItem("esaOnly"));
        assertThat(d2, not(hasItem("NasaGadget")));
        // the surviving member's bytecode annotation is cleaned, so the
        // metadata must not list the jitter annotation either
        assertThat(d2, not(hasItem("OnlyIf")));
        assertThat(d2, not(hasItem("ESA")));
        assertThat(result.getErrors().isEmpty(), is(true));
    }

    @Test
    void leavesUntouchedKotlinClassByteIdentical() throws Exception {
        final Path classes = KotlinFixtures.compileKotlin(workDir,
                "test/Plain.kt",
                "package test\n"
                        + "class Plain {\n"
                        + "    fun x(): Int = 1\n"
                        + "}\n");
        final byte[] before = KotlinFixtures.bytes(classes, "test/Plain");

        new BytecodeStripper().strip(classes);

        assertThat(KotlinFixtures.bytes(classes, "test/Plain"), is(before));
    }

    @Test
    void noFlavourRunRewritesNoMetadata() throws Exception {
        System.clearProperty(FLAVOUR_PROP);
        final Path classes = KotlinFixtures.compileKotlin(workDir,
                "test/Brand.kt",
                "package test\n"
                        + "import com.github.mictaege.jitter.api.OnlyIf\n"
                        + "enum class Brand {\n"
                        + "    @OnlyIf(\"ESA\") ALPHA,\n"
                        + "    @OnlyIf(\"NASA\") GAMMA\n"
                        + "}\n");
        // with no flavour active the jitter annotations still leave the
        // bytecode, but the @kotlin.Metadata payload must stay untouched
        final List<String> d2Before =
                KotlinFixtures.d2Of(KotlinFixtures.metadataOf(KotlinFixtures.read(classes, "test/Brand")));

        new BytecodeStripper().strip(classes);

        final List<String> d2After =
                KotlinFixtures.d2Of(KotlinFixtures.metadataOf(KotlinFixtures.read(classes, "test/Brand")));
        assertThat(d2After, is(d2Before));
        assertThat(d2After, hasItem("GAMMA"));
    }
}
