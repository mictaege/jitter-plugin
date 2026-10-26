package com.github.mictaege.jitter.plugin;

import static com.github.mictaege.jitter.plugin.JitterUtil.active;
import static com.github.mictaege.jitter.plugin.JitterUtil.anyFlavour;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/**
 * Reading and removing the jitter annotations of the compiled classes.
 * Annotation values are compile-time constants - the flavour names are
 * baked into the annotation nodes as plain values.
 */
final class JitterAnnotations {

    private static final String ONLY_IF = "Lcom/github/mictaege/jitter/api/OnlyIf;";
    private static final String FORK = "Lcom/github/mictaege/jitter/api/Fork;";
    private static final String GATEWAY = "Lcom/github/mictaege/jitter/api/Gateway;";
    private static final String ALTER = "Lcom/github/mictaege/jitter/api/Alter;";
    private static final String ALTERNATIVES = "Lcom/github/mictaege/jitter/api/Alternatives;";
    private static final String ALTER_CONST_VALUE =
            "Lcom/github/mictaege/jitter/api/AlterConstValue;";
    private static final String ALTER_CONST_VALUES =
            "Lcom/github/mictaege/jitter/api/AlterConstValues;";
    private static final String INLINED_CONSTANT =
            "Lcom/github/mictaege/jitter/api/InlinedConstantApproved;";
    private static final List<String> ALL = Arrays.asList(
            ONLY_IF, FORK, GATEWAY, ALTER, ALTERNATIVES,
            ALTER_CONST_VALUE, ALTER_CONST_VALUES, INLINED_CONSTANT);

    private JitterAnnotations() {
        super();
    }

    static List<String> onlyIfFlavours(final ClassNode node) {
        return onlyIfFlavours(node.visibleAnnotations);
    }

    static List<String> onlyIfFlavours(final List<AnnotationNode> annotations) {
        if (annotations == null) {
            return null;
        }
        for (final AnnotationNode annotation : annotations) {
            if (ONLY_IF.equals(annotation.desc)) {
                return annotationValues(annotation, "value");
            }
        }
        return null;
    }

    static boolean isInactive(final List<AnnotationNode> annotations) {
        final List<String> flavours = onlyIfFlavours(annotations);
        return flavours != null && flavours.stream().noneMatch(JitterUtil::active);
    }

    /**
     * Whether the given annotations carry the {@code @InlinedConstantApproved}
     * marker - the assertion that the inlined usages of a flavour-gated
     * constant variable are flavour-consistent.
     */
    static boolean isInlinedConstantApproved(final List<AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        for (final AnnotationNode annotation : annotations) {
            if (INLINED_CONSTANT.equals(annotation.desc)) {
                return true;
            }
        }
        return false;
    }

    static AnnotationNode matchingAnnotation(final List<AnnotationNode> annotations,
                                             final String desc, final String containerDesc,
                                             final String expectedValue) {
        if (annotations == null) {
            return null;
        }
        for (final AnnotationNode annotation : repeatableAnnotations(annotations, desc, containerDesc)) {
            final String flavour = annotationValue(annotation, "ifActive");
            if (flavour != null && anyFlavour() && active(flavour)) {
                final String with = annotationValue(annotation, expectedValue);
                if (with != null) {
                    return annotation;
                }
            }
        }
        return null;
    }

    static AnnotationNode forkGateway(final List<AnnotationNode> annotations) {
        return matchingAnnotation(annotations, FORK, GATEWAY, "to");
    }

    static AnnotationNode alterTarget(final List<AnnotationNode> annotations) {
        return matchingAnnotation(annotations, ALTER, ALTERNATIVES, "with");
    }

    /**
     * All {@code @Alter} annotations of the given class, including the
     * inactive flavours - unlike {@link #alterTarget}, which only returns
     * the one matching the active flavour.
     */
    static List<AnnotationNode> alterAnnotations(final List<AnnotationNode> annotations) {
        return annotations == null
                ? new ArrayList<>()
                : repeatableAnnotations(annotations, ALTER, ALTERNATIVES);
    }

    /**
     * All {@code @AlterConstValue} annotations of the given field, all
     * flavour groups - the declaration of the constant's flavour specific
     * values.
     */
    static List<AnnotationNode> alterConstValues(final List<AnnotationNode> annotations) {
        return annotations == null
                ? new ArrayList<>()
                : repeatableAnnotations(annotations, ALTER_CONST_VALUE, ALTER_CONST_VALUES);
    }

    @SuppressWarnings("unchecked")
    static List<AnnotationNode> repeatableAnnotations(final List<AnnotationNode> annotations,
                                                       final String desc, final String containerDesc) {
        final List<AnnotationNode> result = new ArrayList<>();
        for (final AnnotationNode annotation : annotations) {
            if (desc.equals(annotation.desc)) {
                result.add(annotation);
            } else if (containerDesc.equals(annotation.desc)) {
                final Object value = annotationRawValue(annotation, "value");
                if (value instanceof List) {
                    result.addAll((List<AnnotationNode>) value);
                }
            }
        }
        return result;
    }

    static String annotationValue(final AnnotationNode annotation, final String name) {
        final Object value = annotationRawValue(annotation, name);
        return value instanceof String ? (String) value : null;
    }

    static Object annotationRawValue(final AnnotationNode annotation, final String name) {
        if (annotation.values == null) {
            return null;
        }
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<String> annotationValues(final AnnotationNode annotation, final String name) {
        if (annotation.values == null) {
            return new ArrayList<>();
        }
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) {
                final Object value = annotation.values.get(i + 1);
                if (value instanceof List) {
                    return new ArrayList<>((List<String>) value);
                }
                if (value instanceof String) {
                    final List<String> single = new ArrayList<>();
                    single.add((String) value);
                    return single;
                }
            }
        }
        return new ArrayList<>();
    }

    static boolean removeJitterAnnotations(final List<AnnotationNode> annotations) {
        if (annotations == null) {
            return false;
        }
        return annotations.removeIf(annotation -> ALL.contains(annotation.desc));
    }

    static String packageOf(final String internalName) {
        final int idx = internalName.lastIndexOf('/');
        return idx < 0 ? "" : internalName.substring(0, idx + 1);
    }

}
