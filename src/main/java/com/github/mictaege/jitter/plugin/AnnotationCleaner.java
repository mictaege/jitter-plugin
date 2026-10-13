package com.github.mictaege.jitter.plugin;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Strips the jitter annotations from the surviving bytecode: the flavour
 * model must not leak the flavour markup into the artifacts.
 */
final class AnnotationCleaner {

    private AnnotationCleaner() {
        super();
    }

    static void clean(final StripContext ctx) {
        for (final ClassNode node : ctx.nodes.values()) {
            boolean changed = JitterAnnotations.removeJitterAnnotations(node.visibleAnnotations);
            for (final FieldNode field : node.fields) {
                changed |= JitterAnnotations.removeJitterAnnotations(field.visibleAnnotations);
            }
            for (final MethodNode method : node.methods) {
                changed |= JitterAnnotations.removeJitterAnnotations(method.visibleAnnotations);
            }
            if (changed) {
                ctx.dirty.add(node.name);
            }
        }
    }

}
