package com.github.mictaege.jitter.plugin;

import java.util.ArrayList;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/**
 * Replaces the classes marked with {@code @Alter} by their template
 * classes: the template is renamed into the target's slot, the target's
 * package, simple name and visibility are transferred, and the template
 * disappears from the stripped model. Templates may live in the stripped
 * classes themselves or in dependent libraries, resolved from the
 * compile classpath.
 *
 * <p>Classes with {@code @Alter} must not hold static constant variables:
 * javac has inlined their values into the usage sites outside the class,
 * so those baked-in values would not follow the swap - the constant audit
 * rejects them. The flavour variance of a constant's value is declared
 * with {@code @AlterConstValue} on the constant itself instead, applied
 * by {@link AlterConstApplier}.
 */
final class AlterApplier {

    private AlterApplier() {
        super();
    }

    static void applyAltclasses(final StripContext ctx) {
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
                    : ClassIndex.loadFromClasspath(ctx.templateClasspath, templateName, ctx.result);
            if (template == null) {
                ctx.result.error("The given alternative class " + with + " could not be found");
                continue;
            }
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
