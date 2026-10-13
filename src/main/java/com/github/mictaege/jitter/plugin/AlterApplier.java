package com.github.mictaege.jitter.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Replaces the classes marked with {@code @Alter} by their template
 * classes: the template is renamed into the target's slot, the target's
 * package, simple name and visibility are transferred, and the template
 * disappears from the stripped model. Templates may live in the stripped
 * classes themselves or in dependent libraries, resolved from the
 * compile classpath.
 *
 * <p>Annotation values are compile-time constants - javac inlines the value
 * of e.g. {@code @Table(name = TableNames.JOB)} into the annotated class.
 * When the constants class is flavour-altered, those inlined values must be
 * re-baked to the active flavour's template value as well.
 */
final class AlterApplier {

    private AlterApplier() {
        super();
    }

    static Map<String, String> applyAltclasses(final StripContext ctx) {
        final Map<String, String> constantReplacements = new HashMap<>();
        for (final ClassNode target : new ArrayList<>(ctx.nodes.values())) {
            final AnnotationNode matching = JitterAnnotations.alterTarget(target.visibleAnnotations);
            if (matching == null) {
                continue;
            }
            final String with = JitterAnnotations.annotationValue(matching, "with");
            final String templateName = with.contains(".")
                    ? with.replace('.', '/')
                    : JitterAnnotations.packageOf(target.name) + with;
            final ClassNode template = ctx.nodes.containsKey(templateName)
                    ? ctx.nodes.get(templateName)
                    : ClassIndex.loadFromClasspath(ctx.templateClasspath, templateName);
            if (template == null) {
                ctx.result.error("The given alternative class " + with + " could not be found");
                continue;
            }
            collectConstantReplacements(target, template, constantReplacements);
            final ClassNode renamed = remap(template, templateName, target.name);
            if ((target.access & Opcodes.ACC_PUBLIC) != 0) {
                renamed.access |= Opcodes.ACC_PUBLIC;
            } else {
                renamed.access &= ~Opcodes.ACC_PUBLIC;
            }
            final boolean localTemplate = ctx.nodes.containsKey(templateName);
            ctx.nodes.remove(templateName);
            ctx.nodes.put(target.name, renamed);
            ctx.result.info("Replace class " + target.name.replace('/', '.')
                    + " with " + with + (localTemplate
                        ? ". Removed template " + templateName.replace('/', '.')
                        : " (template from the classpath, kept where it is)"));
            if (localTemplate) {
                ctx.result.removedClass(templateName);
            }
            ctx.dirty.add(target.name);
        }
        return constantReplacements;
    }

    private static void collectConstantReplacements(final ClassNode target, final ClassNode template,
                                                    final Map<String, String> replacements) {
        final Set<String> ambiguous = new HashSet<>();
        final Map<String, Integer> counts = new HashMap<>();
        for (final FieldNode field : target.fields) {
            if (field.value instanceof String) {
                counts.merge((String) field.value, 1, Integer::sum);
            }
        }
        counts.forEach((value, count) -> {
            if (count > 1 || replacements.containsKey(value)) {
                replacements.remove(value);
                ambiguous.add(value);
            }
        });
        for (final FieldNode field : target.fields) {
            if (field.value instanceof String) {
                final String value = (String) field.value;
                if (ambiguous.contains(value)) {
                    continue;
                }
                template.fields.stream()
                        .filter(t -> t.name.equals(field.name) && t.value instanceof String)
                        .findFirst()
                        .ifPresent(t -> replacements.put(value, (String) t.value));
            }
        }
    }

    /**
     * Replaces the inlined full-model constant values in the annotations
     * of all surviving classes with the active flavour's template values.
     * This is only sound when the full-model value is unambiguous: values
     * that occur for more than one field are skipped.
     */
    static void rebakeAnnotationConstants(final StripContext ctx,
                                          final Map<String, String> replacements) {
        if (replacements.isEmpty()) {
            return;
        }
        int rebaked = 0;
        for (final ClassNode node : ctx.nodes.values()) {
            boolean changed = false;
            changed |= rebake(node.visibleAnnotations, replacements);
            changed |= rebake(node.invisibleAnnotations, replacements);
            for (final FieldNode field : node.fields) {
                changed |= rebake(field.visibleAnnotations, replacements);
                changed |= rebake(field.invisibleAnnotations, replacements);
            }
            for (final MethodNode method : node.methods) {
                changed |= rebake(method.visibleAnnotations, replacements);
                changed |= rebake(method.invisibleAnnotations, replacements);
                changed |= rebake(method.visibleParameterAnnotations, replacements);
                changed |= rebake(method.invisibleParameterAnnotations, replacements);
                if (method.annotationDefault instanceof AnnotationNode) {
                    changed |= rebake(java.util.Collections.singletonList(
                            (AnnotationNode) method.annotationDefault), replacements);
                } else if (method.annotationDefault instanceof List) {
                    changed |= rebakeList((List<Object>) method.annotationDefault, replacements);
                }
            }
            if (changed) {
                rebaked++;
                ctx.dirty.add(node.name);
            }
        }
        if (rebaked > 0) {
            ctx.result.info("Re-baked " + replacements.size() + " altered constant value(s) in the annotations of "
                    + rebaked + " class(es)");
        }
    }

    private static boolean rebake(final List<AnnotationNode>[] annotationArrays,
                                  final Map<String, String> replacements) {
        boolean changed = false;
        if (annotationArrays != null) {
            for (final List<AnnotationNode> annotations : annotationArrays) {
                changed |= rebake(annotations, replacements);
            }
        }
        return changed;
    }

    private static boolean rebake(final List<AnnotationNode> annotations,
                                  final Map<String, String> replacements) {
        boolean changed = false;
        if (annotations != null) {
            for (final AnnotationNode annotation : annotations) {
                changed |= rebake(annotation, replacements);
            }
        }
        return changed;
    }

    private static boolean rebake(final AnnotationNode annotation, final Map<String, String> replacements) {
        boolean changed = false;
        if (annotation.values != null) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
                final Object value = annotation.values.get(i + 1);
                if (value instanceof String && replacements.containsKey(value)) {
                    annotation.values.set(i + 1, replacements.get(value));
                    changed = true;
                } else if (value instanceof AnnotationNode) {
                    changed |= rebake((AnnotationNode) value, replacements);
                } else if (value instanceof List) {
                    changed |= rebakeList((List<Object>) value, replacements);
                }
            }
        }
        return changed;
    }

    private static boolean rebakeList(final List<Object> values, final Map<String, String> replacements) {
        boolean changed = false;
        for (int i = 0; i < values.size(); i++) {
            final Object value = values.get(i);
            if (value instanceof String && replacements.containsKey(value)) {
                values.set(i, replacements.get(value));
                changed = true;
            } else if (value instanceof AnnotationNode) {
                changed |= rebake((AnnotationNode) value, replacements);
            } else if (value instanceof List) {
                changed |= rebakeList((List<Object>) value, replacements);
            }
        }
        return changed;
    }

    private static ClassNode remap(final ClassNode template, final String from, final String to) {
        final Remapper remapper = new Remapper() {
            @Override
            public String map(final String internalName) {
                return internalName.equals(from) ? to : internalName;
            }
        };
        final ClassNode renamed = new ClassNode();
        template.accept(new ClassRemapper(renamed, remapper));
        return renamed;
    }

}
