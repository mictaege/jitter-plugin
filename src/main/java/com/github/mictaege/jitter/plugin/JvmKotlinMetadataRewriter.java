package com.github.mictaege.jitter.plugin;

import kotlin.metadata.KmClass;
import kotlin.metadata.KmPackage;
import kotlin.metadata.KmProperty;
import kotlin.metadata.jvm.JvmExtensionsKt;
import kotlin.metadata.jvm.JvmFieldSignature;
import kotlin.metadata.jvm.JvmMethodSignature;
import kotlin.metadata.jvm.KotlinClassMetadata;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@link KotlinMetadataRewriter} working directly against
 * kotlin-metadata-jvm: read the {@code @kotlin.Metadata} of every affected
 * class with {@code KotlinClassMetadata.readStrict}, drop the declarations
 * the stripping removed from the {@code Km*} model, and write the metadata
 * back with the original metadata version preserved - writing rebuilds the
 * {@code d2} string table, so the strings of the removed declarations
 * disappear.
 *
 * <p>A fully stripped file facade (kinds k=2/4/5 with no surviving
 * declarations) is deleted together with its class file and recorded as a
 * removed class, like any other removed class.
 *
 * <p>This class references the kotlin-metadata-jvm API, so it must load in
 * the isolated classloader in production; in the tests the library is on
 * the classpath and this class loads directly.
 */
public final class JvmKotlinMetadataRewriter implements KotlinMetadataRewriter {

    private static final String METADATA_DESC = "Lkotlin/Metadata;";

    @Override
    public void rewrite(final Map<String, ClassNode> nodes, final StripResult result,
                        final Set<String> dirty) {
        final Map<String, List<String[]>> removedMethodsByOwner = new HashMap<>();
        for (final String key : result.getRemovedMembers()) {
            final int dot = key.indexOf('.');
            if (dot < 0) {
                continue;
            }
            final String owner = key.substring(0, dot);
            final String rest = key.substring(dot + 1);
            final int paren = rest.indexOf('(');
            if (paren < 0) {
                continue; // a field, indexed via getRemovedFields
            }
            removedMethodsByOwner.computeIfAbsent(owner, k -> new ArrayList<>())
                    .add(new String[]{rest.substring(0, paren), rest.substring(paren)});
        }
        final Map<String, List<String[]>> removedFieldsByOwner = new HashMap<>();
        for (final String field : result.getRemovedFields()) {
            final String[] parts = field.split("\\|", -1);
            if (parts.length < 3) {
                continue;
            }
            removedFieldsByOwner.computeIfAbsent(parts[0], k -> new ArrayList<>())
                    .add(new String[]{parts[1], parts[2]});
        }
        final Set<String> removedClasses = result.getRemovedClasses();
        final Set<String> removedDescriptors = new HashSet<>();
        for (final String removed : removedClasses) {
            removedDescriptors.add("L" + removed + ";");
        }

        for (final Map.Entry<String, ClassNode> entry : new ArrayList<>(nodes.entrySet())) {
            final ClassNode node = entry.getValue();
            final AnnotationNode meta = metadataOf(node);
            if (meta == null) {
                continue;
            }
            final String owner = node.name;
            final boolean affected = dirty.contains(owner)
                    || removedMethodsByOwner.containsKey(owner)
                    || removedFieldsByOwner.containsKey(owner)
                    || removedClasses.stream().anyMatch(c -> c.startsWith(owner + "$"))
                    || d2Of(meta).stream().anyMatch(removedDescriptors::contains);
            if (!affected) {
                continue; // untouched classes pass through byte-identical
            }
            rewriteOne(nodes, node, meta, owner, removedMethodsByOwner, removedFieldsByOwner,
                    removedClasses, result, dirty);
        }
    }

    @Override
    public void close() {
        // no resources held
    }

    // ------------------------------------------------------------- rewriting

    private void rewriteOne(final Map<String, ClassNode> nodes, final ClassNode node,
                            final AnnotationNode meta, final String owner,
                            final Map<String, List<String[]>> removedMethodsByOwner,
                            final Map<String, List<String[]>> removedFieldsByOwner,
                            final Set<String> removedClasses, final StripResult result,
                            final Set<String> dirty) {
        final KotlinClassMetadata parsed = KotlinClassMetadata.readStrict(new MetadataImpl(meta));
        final int kind = parsedKotlinKind(meta);
        switch (kind) {
            case 1: {
                final KotlinClassMetadata.Class cls = (KotlinClassMetadata.Class) parsed;
                mutateKmClass(cls.getKmClass(), owner, removedMethodsByOwner, removedFieldsByOwner,
                        removedClasses);
                writeBack(node, meta, new KotlinClassMetadata.Class(cls.getKmClass(),
                        parsed.getVersion(), parsed.getFlags()), dirty);
                break;
            }
            case 2: {
                final KotlinClassMetadata.FileFacade facade = (KotlinClassMetadata.FileFacade) parsed;
                mutateKmPackage(facade.getKmPackage(), owner, removedMethodsByOwner,
                        removedFieldsByOwner);
                if (fullyStripped(facade.getKmPackage(), owner, removedMethodsByOwner,
                        removedFieldsByOwner)) {
                    deleteClass(nodes, owner, result, dirty);
                    return;
                }
                writeBack(node, meta, new KotlinClassMetadata.FileFacade(facade.getKmPackage(),
                        parsed.getVersion(), parsed.getFlags()), dirty);
                break;
            }
            case 4: {
                final KotlinClassMetadata.MultiFileClassFacade facade =
                        (KotlinClassMetadata.MultiFileClassFacade) parsed;
                final List<String> parts = new ArrayList<>(facade.getPartClassNames());
                final boolean hadParts = !parts.isEmpty();
                parts.removeIf(removedClasses::contains);
                if (hadParts && parts.isEmpty()) {
                    deleteClass(nodes, owner, result, dirty);
                    return;
                }
                writeBack(node, meta, new KotlinClassMetadata.MultiFileClassFacade(parts,
                        parsed.getVersion(), parsed.getFlags()), dirty);
                break;
            }
            case 5: {
                final KotlinClassMetadata.MultiFileClassPart part =
                        (KotlinClassMetadata.MultiFileClassPart) parsed;
                mutateKmPackage(part.getKmPackage(), owner, removedMethodsByOwner,
                        removedFieldsByOwner);
                if (fullyStripped(part.getKmPackage(), owner, removedMethodsByOwner,
                        removedFieldsByOwner)) {
                    deleteClass(nodes, owner, result, dirty);
                    return;
                }
                writeBack(node, meta, new KotlinClassMetadata.MultiFileClassPart(
                        part.getKmPackage(), part.getFacadeClassName(),
                        parsed.getVersion(), parsed.getFlags()), dirty);
                break;
            }
            default:
                // k=3 synthetic classes wrap a lambda, no declarations to
                // strip; unknown kinds are left alone
        }
    }

    private void mutateKmClass(final KmClass km, final String owner,
                               final Map<String, List<String[]>> removedMethodsByOwner,
                               final Map<String, List<String[]>> removedFieldsByOwner,
                               final Set<String> removedClasses) {
        for (final String[] method : removedMethodsByOwner.getOrDefault(owner, List.of())) {
            final String name = method[0];
            final String desc = method[1];
            if ("<init>".equals(name)) {
                km.getConstructors().removeIf(c ->
                        sigEquals(JvmExtensionsKt.getSignature(c), name, desc));
                continue;
            }
            final boolean removed = km.getFunctions().removeIf(f ->
                    sigEquals(JvmExtensionsKt.getSignature(f), name, desc));
            if (!removed) {
                km.getProperties().removeIf(p -> fieldSigEquals(p, name, desc)
                        || sigEquals(JvmExtensionsKt.getGetterSignature(p), name, desc)
                        || sigEquals(JvmExtensionsKt.getSetterSignature(p), name, desc));
            }
        }
        for (final String[] field : removedFieldsByOwner.getOrDefault(owner, List.of())) {
            final String name = field[0];
            final String desc = field[1];
            km.getKmEnumEntries().removeIf(e -> e.getName().equals(name));
            km.getEnumEntries().removeIf(name::equals);
            km.getProperties().removeIf(p -> fieldSigEquals(p, name, desc));
        }
        // nested class names may be simple names relative to the outer class or full
        // internal names, depending on the metadata version
        km.getNestedClasses().removeIf(n -> removedClasses.contains(n)
                || removedClasses.stream().anyMatch(
                        rc -> rc.startsWith(owner + "$") && rc.substring(owner.length() + 1).equals(n)));
        if (removedClasses.contains(owner + "$Companion")) {
            km.setCompanionObject(null);
        }
        // sealed subclass names are full internal names
        km.getSealedSubclasses().removeIf(removedClasses::contains);
        cleanJitterAnnotations(km);
    }

    /**
     * The bytecode pass removes the jitter annotations from the surviving
     * classes; the metadata must match, so the jitter annotations leave the
     * Km model as well.
     */
    private static void cleanJitterAnnotations(final KmClass km) {
        km.getAnnotations().removeIf(JvmKotlinMetadataRewriter::isJitterAnnotation);
        km.getFunctions().forEach(f -> f.getAnnotations().removeIf(JvmKotlinMetadataRewriter::isJitterAnnotation));
        km.getProperties().forEach(p -> p.getAnnotations().removeIf(JvmKotlinMetadataRewriter::isJitterAnnotation));
        km.getConstructors().forEach(c -> c.getAnnotations().removeIf(JvmKotlinMetadataRewriter::isJitterAnnotation));
    }

    private static void cleanJitterAnnotations(final KmPackage km) {
        km.getFunctions().forEach(f -> f.getAnnotations().removeIf(JvmKotlinMetadataRewriter::isJitterAnnotation));
        km.getProperties().forEach(p -> p.getAnnotations().removeIf(JvmKotlinMetadataRewriter::isJitterAnnotation));
    }

    private static boolean isJitterAnnotation(final kotlin.metadata.KmAnnotation annotation) {
        return annotation.getClassName() != null
                && annotation.getClassName().startsWith("com/github/mictaege/jitter/api/");
    }

    private void mutateKmPackage(final KmPackage km, final String owner,
                                 final Map<String, List<String[]>> removedMethodsByOwner,
                                 final Map<String, List<String[]>> removedFieldsByOwner) {
        for (final String[] method : removedMethodsByOwner.getOrDefault(owner, List.of())) {
            final boolean removed = km.getFunctions().removeIf(f ->
                    sigEquals(JvmExtensionsKt.getSignature(f), method[0], method[1]));
            if (!removed) {
                km.getProperties().removeIf(p -> fieldSigEquals(p, method[0], method[1])
                        || sigEquals(JvmExtensionsKt.getGetterSignature(p), method[0], method[1])
                        || sigEquals(JvmExtensionsKt.getSetterSignature(p), method[0], method[1]));
            }
        }
        for (final String[] field : removedFieldsByOwner.getOrDefault(owner, List.of())) {
            km.getProperties().removeIf(p -> fieldSigEquals(p, field[0], field[1]));
        }
        cleanJitterAnnotations(km);
    }

    /**
     * A facade is fully stripped when no declaration survived and at least
     * one was removed from it.
     */
    private boolean fullyStripped(final KmPackage km, final String owner,
                                  final Map<String, List<String[]>> removedMethodsByOwner,
                                  final Map<String, List<String[]>> removedFieldsByOwner) {
        return km.getFunctions().isEmpty() && km.getProperties().isEmpty()
                && km.getTypeAliases().isEmpty()
                && (removedMethodsByOwner.containsKey(owner) || removedFieldsByOwner.containsKey(owner));
    }

    private void deleteClass(final Map<String, ClassNode> nodes, final String owner,
                             final StripResult result, final Set<String> dirty) {
        nodes.remove(owner);
        result.removedClass(owner);
        result.info("Remove class " + owner.replace('/', '.'));
        dirty.add(owner);
    }

    private void writeBack(final ClassNode node, final AnnotationNode oldMeta,
                           final KotlinClassMetadata out, final Set<String> dirty) {
        out.setAllowedToWrite$org_jetbrains_kotlin_kotlin_metadata_jvm(true);
        final AnnotationNode newMeta = toNode(out.write());
        final List<AnnotationNode> annotations = node.visibleAnnotations;
        for (int i = 0; i < annotations.size(); i++) {
            if (METADATA_DESC.equals(annotations.get(i).desc)) {
                annotations.set(i, newMeta);
                break;
            }
        }
        dirty.add(node.name);
    }

    // -------------------------------------------------------- signature match

    private static boolean sigEquals(final JvmMethodSignature sig, final String name,
                                     final String desc) {
        return sig != null && sig.getName().equals(name) && sig.getDescriptor().equals(desc);
    }

    private static boolean fieldSigEquals(final KmProperty prop, final String name,
                                          final String desc) {
        final JvmFieldSignature sig = JvmExtensionsKt.getFieldSignature(prop);
        return sig != null && sig.getName().equals(name) && sig.getDescriptor().equals(desc);
    }

    // ------------------------------------------------------ ASM <-> metadata

    private static int parsedKotlinKind(final AnnotationNode meta) {
        final Object k = valueOf(meta, "k");
        return k instanceof Integer ? (Integer) k : -1;
    }

    private static AnnotationNode metadataOf(final ClassNode node) {
        if (node.visibleAnnotations == null) {
            return null;
        }
        for (final AnnotationNode annotation : node.visibleAnnotations) {
            if (METADATA_DESC.equals(annotation.desc)) {
                return annotation;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> d2Of(final AnnotationNode meta) {
        final Object d2 = valueOf(meta, "d2");
        if (d2 instanceof List) {
            return ((List<Object>) d2).stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Object valueOf(final AnnotationNode meta, final String name) {
        if (meta.values == null) {
            return null;
        }
        for (int i = 0; i + 1 < meta.values.size(); i += 2) {
            if (name.equals(meta.values.get(i))) {
                return meta.values.get(i + 1);
            }
        }
        return null;
    }

    private static AnnotationNode toNode(final kotlin.Metadata m) {
        final AnnotationNode node = new AnnotationNode(METADATA_DESC);
        final List<Object> values = new ArrayList<>();
        values.add("k");
        values.add(m.k());
        values.add("mv");
        values.add(Arrays.stream(m.mv()).boxed().toList());
        values.add("bv");
        values.add(Arrays.stream(m.bv()).boxed().toList());
        values.add("d1");
        values.add(Arrays.asList(m.d1()));
        values.add("d2");
        values.add(Arrays.asList(m.d2()));
        values.add("xs");
        values.add(m.xs());
        if (m.pn() != null) {
            values.add("pn");
            values.add(m.pn());
        }
        values.add("xi");
        values.add(m.xi());
        node.values = values;
        return node;
    }

    /**
     * A {@link kotlin.Metadata} view over an ASM annotation node's flat
     * name/value list.
     */
    private static final class MetadataImpl implements kotlin.Metadata {
        private final AnnotationNode node;

        private MetadataImpl(final AnnotationNode node) {
            this.node = node;
        }

        @Override
        public int k() {
            return (Integer) valueOf(node, "k");
        }

        @Override
        public int[] mv() {
            return toIntArray(valueOf(node, "mv"));
        }

        @Override
        public int[] bv() {
            return toIntArray(valueOf(node, "bv"));
        }

        @Override
        public String[] d1() {
            return toStringArray(valueOf(node, "d1"));
        }

        @Override
        public String[] d2() {
            return toStringArray(valueOf(node, "d2"));
        }

        @Override
        public String xs() {
            return (String) valueOf(node, "xs");
        }

        @Override
        public String pn() {
            return (String) valueOf(node, "pn");
        }

        @Override
        public int xi() {
            final Object xi = valueOf(node, "xi");
            return xi instanceof Integer ? (Integer) xi : 0;
        }

        @Override
        public Class<? extends Annotation> annotationType() {
            return kotlin.Metadata.class;
        }

        private static int[] toIntArray(final Object value) {
            if (value == null) {
                return new int[0];
            }
            if (value instanceof int[]) {
                return (int[]) value;
            }
            final List<?> list = (List<?>) value;
            final int[] out = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                out[i] = (Integer) list.get(i);
            }
            return out;
        }

        private static String[] toStringArray(final Object value) {
            if (value == null) {
                return new String[0];
            }
            if (value instanceof String[]) {
                return (String[]) value;
            }
            return ((List<?>) value).stream().map(String::valueOf).toArray(String[]::new);
        }
    }
}
