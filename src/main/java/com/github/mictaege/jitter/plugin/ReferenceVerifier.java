package com.github.mictaege.jitter.plugin;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Verifies that the classes which survived the stripping do not reference
 * any of the removed flavour specific classes or members anymore.
 *
 * With the classical source transformation this consistency was ensured
 * by the compiler itself, because the dependent sources were compiled
 * against the already transformed sources. With the bytecode approach the
 * whole 150% model is compiled first and stripped afterwards, so the
 * consistency check is done here, on the bytecode level.
 */
public final class ReferenceVerifier {

    private final Set<String> removedClasses;
    private final Set<String> removedMembers;
    private final List<String> violations = new ArrayList<>();

    public ReferenceVerifier(final Set<String> removedClasses, final Set<String> removedMembers) {
        this.removedClasses = removedClasses;
        this.removedMembers = removedMembers;
    }

    /**
     * Returns a list of violation messages. Empty if no dangling references
     * were found.
     */
    public static List<String> findDanglingReferences(final Set<File> classesDirs,
                                                      final Set<String> removedClasses,
                                                      final Set<String> removedMembers) throws IOException {
        final ReferenceVerifier verifier = new ReferenceVerifier(removedClasses, removedMembers);
        for (final File dir : classesDirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            final Path root = dir.toPath();
            try (final Stream<Path> files = Files.walk(root)) {
                files.filter(f -> f.getFileName().toString().endsWith(".class"))
                        .forEach(file -> verifier.scan(internalName(root, file), file));
            }
        }
        return verifier.violations;
    }

    private static String internalName(final Path root, final Path file) {
        final String relative = root.relativize(file).toString()
                .replace(File.separatorChar, '/');
        return relative.substring(0, relative.length() - ".class".length());
    }

    private void scan(final String internalName, final Path file) {
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (final IOException e) {
            throw new IllegalStateException("Could not read class file " + file, e);
        }
        if (removedClasses.contains(internalName)) {
            return;
        }
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {

            /**
             * A javac switch map class: its {@code <clinit>} GETSTATICs every
             * enum constant of the switches of the source, inside a
             * {@code NoSuchFieldError} handler, so missing constants are
             * tolerated at runtime by design - also constants that a flavour
             * dependency removed (the stripper only cleans the entries of the
             * constants its own module removed). References to removed enum
             * constant FIELDS from such a {@code <clinit>} are therefore not
             * dangling violations; class references stay checked.
             */
            private final boolean[] switchMap = {false};

            @Override
            public org.objectweb.asm.FieldVisitor visitField(final int access, final String name,
                                                             final String descriptor, final String signature,
                                                             final Object value) {
                if (name.startsWith("$SwitchMap$")) {
                    switchMap[0] = true;
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                final boolean tolerant = switchMap[0] && name.equals("<clinit>");
                return new MethodVisitor(Opcodes.ASM9) {

                    @Override
                    public void visitMethodInsn(final int opcode, final String owner,
                                                final String name, final String descriptor,
                                                final boolean isInterface) {
                        checkClassReference(owner);
                        checkMemberReference(owner, name, descriptor, "calls removed method", "#");
                    }

                    @Override
                    public void visitFieldInsn(final int opcode, final String owner,
                                               final String name, final String descriptor) {
                        checkClassReference(owner);
                        if (!tolerant) {
                            checkMemberReference(owner, name, descriptor, "accesses removed field", "#");
                        }
                    }

                    @Override
                    public void visitTypeInsn(final int opcode, final String type) {
                        if (type.startsWith("[")) {
                            String element = type;
                            while (element.startsWith("[")) {
                                element = element.substring(1);
                            }
                            if (element.startsWith("L")) {
                                checkClassReference(element.substring(1, element.length() - 1));
                            }
                        } else {
                            checkClassReference(type);
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(final String name, final String descriptor,
                                                       final Handle bootstrapMethod,
                                                       final Object... bootstrapMethodArguments) {
                        checkHandle(bootstrapMethod);
                        for (final Object arg : bootstrapMethodArguments) {
                            if (arg instanceof Handle) {
                                checkHandle((Handle) arg);
                            }
                        }
                    }

                    private void checkHandle(final Handle handle) {
                        if (removedClasses.contains(handle.getOwner())) {
                            violation("references removed class " + handle.getOwner().replace('/', '.'));
                        } else if (removedMembers.contains(
                                handle.getOwner() + "." + handle.getName() + handle.getDesc())) {
                            final boolean field = handle.getTag() == Opcodes.H_GETFIELD
                                    || handle.getTag() == Opcodes.H_GETSTATIC
                                    || handle.getTag() == Opcodes.H_PUTFIELD
                                    || handle.getTag() == Opcodes.H_PUTSTATIC;
                            violation((field ? "accesses removed field " : "calls removed method ")
                                    + handle.getOwner().replace('/', '.') + "#" + handle.getName());
                        }
                    }

                    private void checkClassReference(final String type) {
                        if (removedClasses.contains(type)) {
                            violation("references removed class " + type.replace('/', '.'));
                        }
                    }

                    private void checkMemberReference(final String owner, final String name,
                                                      final String descriptor, final String action,
                                                      final String separator) {
                        if (removedMembers.contains(owner + "." + name + descriptor)) {
                            violation(action + " " + owner.replace('/', '.') + separator + name);
                        }
                    }

                    private void violation(final String message) {
                        violations.add(internalName.replace('/', '.') + " " + message);
                    }

                };
            }

        }, 0);
    }

}
