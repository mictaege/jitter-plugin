package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * The source level dejittering of Kotlin files, dispatched by the
 * {@link Dejitterer}: the Kotlin PSI reads the structure, the edits are
 * offset based text surgery, so comments, formatting and line numbers
 * outside the edited spans survive untouched.
 */
public class KotlinDejittererTest {

    @TempDir
    Path workDir;

    private Path src;
    private Path out;

    @org.junit.jupiter.api.BeforeEach
    void setUpDirs() {
        src = workDir.resolve("src");
        out = workDir.resolve("out");
    }

    @Test
    void removesClassesOfInactiveFlavours() throws Exception {
        write("com/example/Plain.kt",
                "package com.example\n"
                + "\n"
                + "class Plain\n");
        write("com/example/EsaTool.kt",
                "package com.example\n"
                + "\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "/** the esa flavour variant */\n"
                + "@OnlyIf(\"Esa\")\n"
                + "class EsaTool\n");
        write("com/example/NasaTool.kt",
                "package com.example\n"
                + "\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "@OnlyIf(\"Nasa\")\n"
                + "class NasaTool\n");

        final StripResult result = dejitter("Esa");

        assertThat(Files.exists(out.resolve("com/example/Plain.kt")), is(true));
        assertThat(Files.exists(out.resolve("com/example/EsaTool.kt")), is(true));
        assertThat(Files.exists(out.resolve("com/example/NasaTool.kt")), is(false));
        assertThat(result.getRemovedClasses(), is(Set.of("com/example/NasaTool")));
        assertThat(read("com/example/EsaTool.kt"), not(containsString("OnlyIf")));
        assertThat(read("com/example/EsaTool.kt"), containsString("/** the esa flavour variant */"));
        assertThat(read("com/example/EsaTool.kt"), not(containsString("import com.github.mictaege.jitter.api.OnlyIf")));
    }

    @Test
    void keepsTheFileOfAnInactiveFlavourGateAsAWhole() throws Exception {
        write("com/example/Gates.kt",
                "package com.example\n"
                + "\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "class Gates\n"
                + "\n"
                + "@OnlyIf(\"Nasa\")\n"
                + "class NasaOnly\n");

        final StripResult result = dejitter("Esa");

        assertThat(Files.exists(out.resolve("com/example/Gates.kt")), is(true));
        assertThat(result.getRemovedClasses(), is(Set.of("com/example/NasaOnly")));
        assertThat(read("com/example/Gates.kt"), containsString("class Gates"));
        assertThat(read("com/example/Gates.kt"), not(containsString("NasaOnly")));
        assertThat(read("com/example/Gates.kt"), not(containsString("import com.github.mictaege.jitter.api.OnlyIf")));
    }

    @Test
    void removesMembersAndConstructorParametersOfInactiveFlavours() throws Exception {
        write("com/example/Order.kt",
                "package com.example\n"
                + "\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "import com.github.mictaege.jitter.api.Tags\n"
                + "class Order(\n"
                + "    val id: String,\n"
                + "    @OnlyIf(\"Esa\") @Tags(\"some.feature\")\n"
                + "    val esaNote: String?,\n"
                + "    @OnlyIf(\"Nasa\")\n"
                + "    val nasaNote: String? = null,\n"
                + "    val plain: Int\n"
                + ") {\n"
                + "    fun plain() = id\n"
                + "\n"
                + "    @OnlyIf(\"Esa\")\n"
                + "    fun esaOnly(): String = \"esa\"\n"
                + "\n"
                + "    @OnlyIf(\"Nasa\")\n"
                + "    fun nasaOnly(): String = \"nasa\"\n"
                + "\n"
                + "    companion object {\n"
                + "        @OnlyIf(\"Nasa\")\n"
                + "        const val NASA_CONST = \"nasa\"\n"
                + "        const val PLAIN_CONST = \"plain\"\n"
                + "    }\n"
                + "}\n");

        final StripResult result = dejitter("Esa");

        final String text = read("com/example/Order.kt");
        assertThat(text, containsString("val id: String,"));
        assertThat(text, containsString("@Tags(\"some.feature\")"));
        assertThat(text, containsString("val esaNote: String?,"));
        assertThat(text, containsString("val plain: Int"));
        assertThat(text, not(containsString("nasaNote")));
        assertThat(text, containsString("fun esaOnly(): String = \"esa\""));
        assertThat(text, not(containsString("nasaOnly")));
        assertThat(text, not(containsString("NASA_CONST")));
        assertThat(text, containsString("const val PLAIN_CONST"));
        assertThat(text, not(containsString("OnlyIf")));
        assertThat(result.getInfos().stream().anyMatch(i -> i.contains("Remove constructor parameter nasaNote")), is(true));
        assertThat(result.getKeptSourceClasses(), is(Set.of("com/example/Order")));
    }

    @Test
    void removesInactiveEnumEntries() throws Exception {
        write("com/example/Channel.kt",
                "package com.example\n"
                + "\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "enum class Channel(val txt: String) {\n"
                + "    @OnlyIf(\"Esa\")\n"
                + "    ESA_CHANNEL(\"esa\"),\n"
                + "    @OnlyIf(\"Nasa\")\n"
                + "    NASA_CHANNEL(\"nasa\"),\n"
                + "    PLAIN_CHANNEL(\"plain\")\n"
                + "}\n");

        final StripResult result = dejitter("Esa");

        final String text = read("com/example/Channel.kt");
        assertThat(text, containsString("ESA_CHANNEL(\"esa\"),"));
        assertThat(text, not(containsString("NASA_CHANNEL")));
        assertThat(text, containsString("PLAIN_CHANNEL(\"plain\")"));
        assertThat(text, not(containsString("OnlyIf")));
        assertThat(result.getRemovedClasses().isEmpty(), is(true));
    }

    @Test
    void forksTheMethodBodyOfTheActiveFlavour() throws Exception {
        write("com/example/Gate.kt",
                "package com.example\n"
                + "\n"
                + "import com.github.mictaege.jitter.api.Fork\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "class Gate {\n"
                + "    @Fork(ifActive = \"Esa\", to = \"flavouredEsa\")\n"
                + "    fun flavour(): String = \"none\"\n"
                + "\n"
                + "    @OnlyIf(\"Esa\")\n"
                + "    private fun flavouredEsa(): String = \"esa\"\n"
                + "\n"
                + "    @OnlyIf(\"Nasa\")\n"
                + "    private fun flavouredNasa(): String = \"nasa\"\n"
                + "}\n");

        final StripResult result = dejitter("Esa");

        final String text = read("com/example/Gate.kt");
        assertThat(text, containsString("fun flavour(): String = \"esa\""));
        assertThat(text, not(containsString("flavouredEsa")));
        assertThat(text, not(containsString("flavouredNasa")));
        assertThat(text, not(containsString("Fork")));
        assertThat(text, not(containsString("OnlyIf")));
        assertThat(result.getInfos().stream().anyMatch(i -> i.contains("Replace body of flavour with flavouredEsa")), is(true));
    }

    @Test
    void forksTheMethodBlockBodyOfTheActiveFlavour() throws Exception {
        write("com/example/BlockGate.kt",
                "package com.example\n"
                + "\n"
                + "import com.github.mictaege.jitter.api.Fork\n"
                + "class BlockGate {\n"
                + "    @Fork(ifActive = \"Esa\", to = \"flavouredEsa\")\n"
                + "    fun flavour(): String {\n"
                + "        return \"none\"\n"
                + "    }\n"
                + "\n"
                + "    private fun flavouredEsa(): String {\n"
                + "        return \"esa\"\n"
                + "    }\n"
                + "}\n");

        dejitter("Esa");

        final String text = read("com/example/BlockGate.kt");
        assertThat(text, containsString("fun flavour(): String {\n"
                + "        return \"esa\"\n"
                + "    }"));
        assertThat(text, not(containsString("flavouredEsa")));
        assertThat(text, not(containsString("Fork")));
    }

    @Test
    void altersTheClassOfTheActiveFlavour() throws Exception {
        write("com/example/TableNames.kt",
                "package com.example\n"
                + "\n"
                + "import com.github.mictaege.jitter.api.Alter\n"
                + "@Alter(ifActive = \"Esa\", with = \"EsaTableNames\")\n"
                + "class TableNames {\n"
                + "    val job = \"none\"\n"
                + "}\n");
        write("com/example/EsaTableNames.kt",
                "package com.example\n"
                + "\n"
                + "class EsaTableNames {\n"
                + "    val job = TableNames::class.java.simpleName\n"
                + "}\n");

        final StripResult result = dejitter("Esa");

        assertThat(Files.exists(out.resolve("com/example/EsaTableNames.kt")), is(false));
        final String text = read("com/example/TableNames.kt");
        assertThat(text, containsString("class TableNames {"));
        assertThat(text, containsString("TableNames::class.java.simpleName"));
        assertThat(text, not(containsString("EsaTableNames")));
        assertThat(text, not(containsString("Alter")));
        assertThat(result.getRemovedClasses(), is(Set.of("com/example/EsaTableNames")));
        assertThat(result.getKeptSourceClasses(), is(Set.of("com/example/TableNames")));
        assertThat(result.getInfos().stream().anyMatch(i -> i.contains("Replace class TableNames with EsaTableNames")), is(true));
    }

    /**
     * Like the Java dejittering: with no flavour active nothing is removed,
     * but the jitter annotations leave the variant anyway.
     */
    @Test
    void keepsEverythingWhenNoFlavourIsActive() throws Exception {
        write("com/example/Flavoured.kt",
                "package com.example\n"
                + "\n"
                + "import com.github.mictaege.jitter.api.OnlyIf\n"
                + "@OnlyIf(\"Nasa\")\n"
                + "class NasaTool\n"
                + "\n"
                + "class Plain\n");

        final StripResult result = dejitter("");

        assertThat(Files.exists(out.resolve("com/example/Flavoured.kt")), is(true));
        assertThat(read("com/example/Flavoured.kt"), containsString("class NasaTool"));
        assertThat(read("com/example/Flavoured.kt"), not(containsString("OnlyIf")));
        assertThat(result.getRemovedClasses().isEmpty(), is(true));
    }

    // ------------------------------------------------------------ fixture

    private StripResult dejitter(final String flavour) throws IOException {
        final Dejitterer dejitterer = new Dejitterer(flavour, testClasspath());
        try {
            return dejitterer.dejitter(Set.of(src), out);
        } finally {
            dejitterer.close();
        }
    }

    private List<File> testClasspath() {
        return List.of(new File(getClass().getProtectionDomain().getCodeSource().getLocation().getFile()));
    }

    private void write(final String relative, final String content) throws IOException {
        final Path file = src.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String read(final String relative) throws IOException {
        return Files.readString(out.resolve(relative), StandardCharsets.UTF_8);
    }

}
