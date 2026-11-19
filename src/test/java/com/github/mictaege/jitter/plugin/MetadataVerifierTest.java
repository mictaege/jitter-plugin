package com.github.mictaege.jitter.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * The metadata check of the verify pass (issue mictaege/jitter-plugin#48):
 * {@code @kotlin.Metadata} payloads still referencing removed classes or
 * members of the same class are dangling references and fail the build -
 * but only when the class's own bytecode no longer references them either;
 * declaration level references that the bytecode keeps (the code-level
 * check tolerates them) are consistent and pass.
 */
public class MetadataVerifierTest {

    @TempDir
    Path workDir;

    @Test
    void metadataReferencingARemovedClassTheBytecodeNoLongerReferencesIsReported() throws Exception {
        final Path classes = fixtureStrippedOfUse();

        final List<String> violations = MetadataVerifier.findMetadataViolations(
                Set.of(classes.toFile()), Set.of("test/Brand"), Set.of());

        assertThat(violations.size(), is(1));
        assertThat(violations.get(0), org.hamcrest.CoreMatchers.containsString("test.Brand"));
        assertThat(violations.get(0), org.hamcrest.CoreMatchers.containsString("test.Helper"));
    }

    @Test
    void bytecodeReferenceToARemovedClassIsTolerated() throws Exception {
        // the surviving class still declares a member of the removed type
        // (e.g. ScriptDTO.scriptType -> ScriptType in ologis): the code-level
        // check tolerates the declaration level reference, so the metadata
        // that carries it too is consistent
        final Path classes = fixture();

        final List<String> violations = MetadataVerifier.findMetadataViolations(
                Set.of(classes.toFile()), Set.of("test/Brand"), Set.of());

        assertThat(violations.isEmpty(), is(true));
    }

    @Test
    void metadataListingAMemberRemovedFromTheSameClassIsReported() throws Exception {
        final Path classes = fixtureStrippedOfUse();

        final List<String> violations = MetadataVerifier.findMetadataViolations(
                Set.of(classes.toFile()), Set.of(),
                Set.of(StripResult.memberKey("test/Helper", "use", "(Ltest/Brand;)I")));

        assertThat(violations.size(), is(1));
        assertThat(violations.get(0), org.hamcrest.CoreMatchers.containsString("use"));
        assertThat(violations.get(0), org.hamcrest.CoreMatchers.containsString("test.Helper"));
    }

    @Test
    void consistentMetadataPasses() throws Exception {
        final Path classes = fixture();

        final List<String> violations = MetadataVerifier.findMetadataViolations(
                Set.of(classes.toFile()), Set.of(), Set.of());

        assertThat(violations.isEmpty(), is(true));
    }

    @Test
    void removedClassesAreNotCheckedAgainstThemselves() throws Exception {
        final Path classes = fixture();

        final List<String> violations = MetadataVerifier.findMetadataViolations(
                Set.of(classes.toFile()), Set.of("test/Brand", "test/Helper"), Set.of());

        assertThat(violations.isEmpty(), is(true));
    }

    private Path fixture() throws Exception {
        return KotlinFixtures.compileKotlin(workDir,
                "test/Brand.kt",
                "package test\n"
                        + "class Brand\n",
                "test/Helper.kt",
                "package test\n"
                        + "class Helper {\n"
                        + "    fun use(b: Brand): Int = 1\n"
                        + "}\n");
    }

    /**
     * The fixture with the {@code use} method removed from the bytecode (what
     * the strip does), while the {@code @kotlin.Metadata} payload is left
     * untouched (what the metadata rewrite is supposed to fix).
     */
    private Path fixtureStrippedOfUse() throws Exception {
        final Path classes = fixture();
        final Path helper = classes.resolve("test/Helper.class");
        final byte[] bytes = Files.readAllBytes(helper);
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                if ("use".equals(name) && "(Ltest/Brand;)I".equals(descriptor)) {
                    return null; // stripped from the bytecode
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);
        Files.write(helper, writer.toByteArray());
        return classes;
    }
}
