package com.github.mictaege.jitter.plugin;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks the {@code @kotlin.Metadata} of the surviving classes against the
 * removal manifest: the metadata of a stripped Kotlin class must not
 * reference removed classes or members that the bytecode of the same class
 * no longer references either - those references live only as strings inside
 * the annotation, so the code-level dangling reference check cannot see them.
 *
 * <p>References that the class's own bytecode still carries (a declaration
 * level reference to a removed class, e.g. a surviving field or constructor
 * parameter of a removed type, which the code-level check deliberately
 * tolerates) are not violations: the metadata is then consistent with the
 * bytecode. Only metadata-only references fail the build.
 *
 * <p>The check reads the annotation payload (the {@code d2} string table)
 * directly from the class file with ASM, so it needs no Kotlin library on
 * the verification path.
 */
public final class MetadataVerifier {

    private static final String METADATA_DESC = "Lkotlin/Metadata;";
    private static final Pattern DESCRIPTOR_CLASS = Pattern.compile("L([^;]+);");

    private MetadataVerifier() {
        super();
    }

    /**
     * Returns a list of violation messages. Empty if the metadata of every
     * surviving class agrees with the removals.
     */
    public static List<String> findMetadataViolations(final Set<File> classesDirs,
                                                      final Set<String> removedClasses,
                                                      final Set<String> removedMembers) throws IOException {
        final List<String> violations = new ArrayList<>();
        final Set<String> reported = new LinkedHashSet<>();
        for (final File dir : classesDirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            final Path root = dir.toPath();
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(f -> f.getFileName().toString().endsWith(".class"))
                        .forEach(file -> scan(internalName(root, file), file,
                                removedClasses, removedMembers, violations, reported));
            }
        }
        return violations;
    }

    private static String internalName(final Path root, final Path file) {
        final String relative = root.relativize(file).toString()
                .replace(File.separatorChar, '/');
        return relative.substring(0, relative.length() - ".class".length());
    }

    private static void scan(final String internalName, final Path file,
                             final Set<String> removedClasses, final Set<String> removedMembers,
                             final List<String> violations, final Set<String> reported) {
        if (removedClasses.contains(internalName)) {
            return;
        }
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (final IOException e) {
            throw new IllegalStateException("Could not read class file " + file, e);
        }
        final List<String> d2 = new ArrayList<>();
        // internal names and member signatures the class's own bytecode still carries
        final Set<String> bytecodeClasses = new LinkedHashSet<>();
        final Set<String> bytecodeMemberSigs = new LinkedHashSet<>();
        final Set<String> bytecodeMemberNames = new LinkedHashSet<>();
        // class level annotations are visited before any method, so once we
        // know the class is not Kotlin, its code needs no scanning
        final boolean[] kotlin = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(final int version, final int access, final String name,
                              final String signature, final String superName, final String[] interfaces) {
                if (superName != null) {
                    bytecodeClasses.add(superName);
                }
                if (interfaces != null) {
                    Collections.addAll(bytecodeClasses, interfaces);
                }
                collectDescriptorClasses(signature, bytecodeClasses);
            }

            @Override
            public AnnotationVisitor visitAnnotation(final String desc, final boolean visible) {
                if (!METADATA_DESC.equals(desc)) {
                    return null;
                }
                kotlin[0] = true;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(final String name) {
                        if (!"d2".equals(name)) {
                            return null;
                        }
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(final String ignored, final Object value) {
                                if (value != null) {
                                    d2.add(String.valueOf(value));
                                }
                            }
                        };
                    }
                };
            }

            @Override
            public void visitInnerClass(final String name, final String outerName,
                                        final String innerName, final int access) {
                if (name != null) {
                    bytecodeClasses.add(name);
                }
            }

            @Override
            public FieldVisitor visitField(final int access, final String name,
                                           final String descriptor, final String signature,
                                           final Object value) {
                bytecodeMemberSigs.add(name + descriptor);
                bytecodeMemberNames.add(name);
                collectDescriptorClasses(descriptor, bytecodeClasses);
                collectDescriptorClasses(signature, bytecodeClasses);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                if (!kotlin[0]) {
                    return null; // not a Kotlin class: skip the code entirely
                }
                bytecodeMemberSigs.add(name + descriptor);
                bytecodeMemberNames.add(name);
                collectDescriptorClasses(descriptor, bytecodeClasses);
                collectDescriptorClasses(signature, bytecodeClasses);
                if (exceptions != null) {
                    Collections.addAll(bytecodeClasses, exceptions);
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTypeInsn(final int opcode, final String type) {
                        addType(type);
                    }

                    @Override
                    public void visitMethodInsn(final int opcode, final String owner,
                                                final String name, final String descriptor,
                                                final boolean isInterface) {
                        bytecodeClasses.add(owner);
                        collectDescriptorClasses(descriptor, bytecodeClasses);
                    }

                    @Override
                    public void visitFieldInsn(final int opcode, final String owner,
                                               final String name, final String descriptor) {
                        bytecodeClasses.add(owner);
                        collectDescriptorClasses(descriptor, bytecodeClasses);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(final String name, final String descriptor,
                                                       final Handle bootstrapMethod,
                                                       final Object... bootstrapMethodArguments) {
                        bytecodeClasses.add(bootstrapMethod.getOwner());
                        for (final Object arg : bootstrapMethodArguments) {
                            if (arg instanceof Handle) {
                                bytecodeClasses.add(((Handle) arg).getOwner());
                            }
                        }
                        collectDescriptorClasses(descriptor, bytecodeClasses);
                    }

                    @Override
                    public void visitLdcInsn(final Object value) {
                        if (value instanceof Type && ((Type) value).getSort() == Type.OBJECT) {
                            bytecodeClasses.add(((Type) value).getInternalName());
                        }
                    }

                    private void addType(final String type) {
                        String element = type;
                        while (element.startsWith("[")) {
                            element = element.substring(1);
                        }
                        if (element.startsWith("L") && element.endsWith(";")) {
                            bytecodeClasses.add(element.substring(1, element.length() - 1));
                        } else {
                            bytecodeClasses.add(element);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (d2.isEmpty()) {
            return; // not a Kotlin class
        }
        final Set<String> d2set = new LinkedHashSet<>(d2);
        for (final String s : d2set) {
            final String ref;
            if (s.length() > 2 && s.startsWith("L") && s.endsWith(";")) {
                ref = s.substring(1, s.length() - 1);
            } else if (s.indexOf('/') >= 0) {
                ref = s; // sealed subclasses and friends are plain internal names
            } else {
                continue;
            }
            if (removedClasses.contains(ref) && !bytecodeClasses.contains(ref)) {
                report(violations, reported, "the @kotlin.Metadata of " + internalName.replace('/', '.')
                        + " still references the removed class " + ref.replace('/', '.'));
            }
        }
        final String ownerPrefix = internalName + ".";
        for (final String key : removedMembers) {
            if (!key.startsWith(ownerPrefix)) {
                continue;
            }
            final String rest = key.substring(ownerPrefix.length());
            final int paren = rest.indexOf('(');
            final String name;
            final String desc;
            if (paren >= 0) {
                // a method or constructor: the manifest key is name+descriptor
                name = rest.substring(0, paren);
                desc = rest.substring(paren);
            } else {
                // a field or enum entry: the d2 entry that prefixes name+descriptor is the name
                String found = null;
                for (final String s : d2set) {
                    if (!s.isEmpty() && rest.startsWith(s) && rest.length() > s.length()
                            && !s.startsWith("L") && !s.startsWith("[") && !s.startsWith("(")) {
                        found = s;
                        break;
                    }
                }
                if (found == null) {
                    continue;
                }
                name = found;
                desc = rest.substring(found.length());
            }
            if (!d2set.contains(name)) {
                continue;
            }
            if (bytecodeMemberSigs.contains(name + desc)) {
                continue; // the bytecode still declares exactly this member
            }
            if (!d2set.contains(desc) && bytecodeMemberNames.contains(name)) {
                continue; // d2 carries only the name, and a kept overload owns it
            }
            // the member was stripped from the bytecode, but its metadata
            // entry (name, and usually the signature) is still listed
            report(violations, reported, "the @kotlin.Metadata of " + internalName.replace('/', '.')
                    + " still lists the removed member " + name + " " + desc);
        }
    }

    private static void collectDescriptorClasses(final String descriptor, final Set<String> into) {
        if (descriptor == null) {
            return;
        }
        final Matcher m = DESCRIPTOR_CLASS.matcher(descriptor);
        while (m.find()) {
            into.add(m.group(1));
        }
    }

    private static void report(final List<String> violations, final Set<String> reported,
                               final String message) {
        if (reported.add(message)) {
            violations.add(message);
        }
    }
}
