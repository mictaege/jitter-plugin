package com.github.mictaege.jitter.plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Audits the full model for compile-time constants whose existence is
 * flavour variant and fails the build unless they are marked
 * {@code @InlinedConstantApproved}.
 *
 * <p>javac inlines constant variables - {@code final} fields of a
 * primitive or {@code String} type with a constant initializer, static or
 * instance - into the using code: every usage site of the full model
 * carries the baked-in value instead of a field reference. The stripping
 * removes the field in the flavours its gate excludes, but the baked-in
 * values stay, and since the usage sites contain plain literals they are
 * indistinguishable from handwritten constants - neither the stripping
 * nor the reference verification can apply the flavour logic there or
 * even see the usage afterwards.
 *
 * <p>Every constant variable whose existence is flavour variant - gated
 * by an {@code @OnlyIf} gate on the field, on the enclosing class or on
 * the package of the class - must therefore be either marked
 * {@code @InlinedConstantApproved}, the review assertion that the usages are
 * flavour consistent, or made non-inlinable (box it or drop
 * {@code final}), in which case the usages stay references that the
 * stripping verifies. {@code private} constants are included: they are
 * inlined within their own class just like any other constant.
 *
 * <p>The check is declaration side: it fails the build in every flavour
 * run and in the full model, whatever flavour is active. A gate that
 * covers all flavours is not flavour variant and needs no marker. The
 * marker is meaningless on fields that are no constant variables or not
 * flavour variant, and fails the build there as well, so it keeps exactly
 * one meaning. The source level verification of the marked constants'
 * usages is a separate, opt-in check: javac has inlined them, so no
 * bytecode based check can verify the usages - see
 * {@link InlinedConstantsChecker}.
 *
 * <p>The second rule covers the {@code @Alter} swap: a constant of a
 * target class whose value differs from a template's constant of the
 * same name must also be marked - the usage sites keep the full model
 * value instead of following the flavour swap - or made non-inlinable.
 */
final class ConstantAuditor {

    private ConstantAuditor() {
        super();
    }

    static void audit(final StripContext ctx) {
        auditFlavourVariantConstants(ctx);
        auditAlterConstants(ctx);
    }

    private static void auditFlavourVariantConstants(final StripContext ctx) {
        final Map<String, List<String>> packageGates = new HashMap<>();
        for (final ClassNode node : new ArrayList<>(ctx.nodes.values())) {
            // an @Alter target swaps its whole body per flavour: its
            // un-gated constants are covered by the alter rule below
            final boolean alterTarget =
                    !JitterAnnotations.alterAnnotations(node.visibleAnnotations).isEmpty();
            final List<String> classGate = JitterAnnotations.onlyIfFlavours(node);
            final boolean classVariant = classGate != null
                    && !coversAllFlavours(classGate, ctx.flavours);
            final List<String> packageGate =
                    packageGateOf(ctx, packageGates, packageOf(node.name));
            final boolean packageVariant = packageGate != null
                    && !coversAllFlavours(packageGate, ctx.flavours);
            for (final FieldNode field : node.fields) {
                final List<String> gate =
                        JitterAnnotations.onlyIfFlavours(field.visibleAnnotations);
                final boolean flavourVariant = classVariant || packageVariant
                        || (gate != null && !coversAllFlavours(gate, ctx.flavours));
                final boolean marked =
                        JitterAnnotations.isInlinedConstantApproved(field.visibleAnnotations);
                if (marked && !isConstantVariable(node, field)) {
                    ctx.result.error("The @InlinedConstantApproved marker of "
                            + node.name.replace('/', '.') + "#" + field.name
                            + " is meaningless: the field is no compile-time constant,"
                            + " so javac does not inline its usages and the flavour"
                            + " stripping verifies them as references. Remove the"
                            + " marker.");
                } else if (marked && !flavourVariant && !alterTarget) {
                    ctx.result.error("The @InlinedConstantApproved marker of "
                            + node.name.replace('/', '.') + "#" + field.name
                            + " is meaningless: the field is not flavour variant, its"
                            + " value is the same in every flavour. Remove the marker.");
                } else if (!marked && flavourVariant && isConstantVariable(node, field)) {
                    ctx.result.error("The compile-time constant "
                            + node.name.replace('/', '.') + "#" + field.name
                            + " is flavour variant - its gate, class or package does"
                            + " not keep it in all flavours - but javac inlines its"
                            + " value " + value(field.value) + " into the usages, so"
                            + " the stripping cannot apply the flavour logic there."
                            + " Review the usages and mark the constant with"
                            + " @InlinedConstantApproved, or make it non-inlinable (box it"
                            + " or drop final).");
                }
            }
        }
    }

    /**
     * The {@code @OnlyIf} gate of the package of the given internal name,
     * read from the package-info class of the strip set. A package gate
     * covers only the direct classes of its package.
     */
    private static List<String> packageGateOf(final StripContext ctx,
                                              final Map<String, List<String>> cache,
                                              final String pkg) {
        if (pkg.isEmpty() || !ctx.nodes.containsKey(pkg + "/package-info")) {
            return null;
        }
        return cache.computeIfAbsent(pkg, key -> {
            final ClassNode info = ctx.nodes.get(key + "/package-info");
            return info == null ? null : JitterAnnotations.onlyIfFlavours(info);
        });
    }

    private static String packageOf(final String internalName) {
        final int idx = internalName.lastIndexOf('/');
        return idx < 0 ? "" : internalName.substring(0, idx);
    }

    private static void auditAlterConstants(final StripContext ctx) {
        for (final ClassNode target : new ArrayList<>(ctx.nodes.values())) {
            final List<AnnotationNode> alters =
                    JitterAnnotations.alterAnnotations(target.visibleAnnotations);
            if (alters.isEmpty()) {
                continue;
            }
            final Map<String, Object> targetValues = staticConstants(target);
            if (targetValues.isEmpty()) {
                continue;
            }
            for (final AnnotationNode alter : alters) {
                final String with = JitterAnnotations.annotationValue(alter, "with");
                if (with == null) {
                    continue;
                }
                final String templateName = with.contains(".")
                        ? with.replace('.', '/')
                        : JitterAnnotations.packageOf(target.name) + with;
                final ClassNode template = ctx.nodes.containsKey(templateName)
                        ? ctx.nodes.get(templateName)
                        : ClassIndex.loadFromClasspath(ctx.templateClasspath, templateName, ctx.result);
                if (template == null) {
                    continue;
                }
                final Map<String, Object> templateValues = staticConstants(template);
                for (final Map.Entry<String, Object> entry : targetValues.entrySet()) {
                    final Object templateValue = templateValues.get(entry.getKey());
                    if (templateValue != null && !templateValue.equals(entry.getValue())) {
                        // the target's constant carries the marker: the developer
                        // asserted that its inlined usages are flavour consistent -
                        // annotations, which are re-baked to the active flavour,
                        // or members gated with a subset of the swap's flavours
                        if (target.fields.stream().anyMatch(f -> f.name.equals(entry.getKey())
                                && JitterAnnotations.isInlinedConstantApproved(f.visibleAnnotations))) {
                            continue;
                        }
                        ctx.result.error("The @Alter target " + target.name.replace('/', '.')
                                + " holds the constant " + entry.getKey() + " = "
                                + value(entry.getValue()) + ", the template "
                                + templateName.replace('/', '.') + " defines "
                                + value(templateValue) + ", but javac has inlined the full model"
                                + " value into the usages, so they do not follow the flavour"
                                + " swap. Make the constant non-inlinable (box it or drop final)"
                                + " or, if all its usages are annotations - re-baked to the"
                                + " active flavour - assert this with @InlinedConstantApproved.");
                    }
                }
            }
        }
    }

    /**
     * Whether the given {@code @OnlyIf} gate keeps the member alive in all
     * configured flavours: the member is not flavour variant then, so the
     * inlining of its constant value is harmless. Without configured
     * flavours nothing can be flavour-variant, the gate is treated as
     * covering everything.
     */
    private static boolean coversAllFlavours(final List<String> gate, final List<String> flavours) {
        if (flavours == null || flavours.isEmpty() || gate.isEmpty()) {
            return true;
        }
        return flavours.stream()
                .allMatch(flavour -> gate.stream()
                        .anyMatch(gated -> gated.equalsIgnoreCase(flavour)));
    }

    private static boolean isConstantVariable(final ClassNode node, final FieldNode field) {
        if ((field.access & Opcodes.ACC_FINAL) == 0) {
            return false;
        }
        if (!isConstantType(field.desc)) {
            return false;
        }
        if ((field.access & Opcodes.ACC_STATIC) != 0) {
            return field.value != null;
        }
        return hasConstantInitializer(node, field);
    }

    private static boolean isStaticConstantVariable(final FieldNode field) {
        return (field.access & Opcodes.ACC_STATIC) != 0
                && (field.access & Opcodes.ACC_FINAL) != 0
                && isConstantType(field.desc)
                && field.value != null;
    }

    private static boolean isConstantType(final String desc) {
        return desc.length() == 1 || "Ljava/lang/String;".equals(desc);
    }

    /**
     * Whether the instance field is a constant variable that javac has
     * inlined into its own class: a declaration initializer with a
     * constant expression, folded to a literal. The bytecode cannot tell
     * the initializer from a blank final that the constructors assign,
     * but the two are distinguishable in effect: javac duplicates a
     * declaration initializer into every constructor with the same
     * folded literal, while a blank final usually gets its value from
     * the constructor logic - so only assignments of one and the same
     * constant in every constructor count. Anything else is a blank
     * final: no constant variable, its usages are real references that
     * the stripping verifies.
     */
    private static boolean hasConstantInitializer(final ClassNode node, final FieldNode field) {
        Object value = null;
        boolean found = false;
        for (final MethodNode method : node.methods) {
            if (!"<init>".equals(method.name) || method.instructions == null) {
                continue;
            }
            for (final AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode
                        && ((FieldInsnNode) insn).name.equals(field.name)
                        && ((FieldInsnNode) insn).desc.equals(field.desc)) {
                    final Object cst = constantValueOfPush(insn.getPrevious());
                    if (cst == null) {
                        return false;
                    }
                    if (found && !cst.equals(value)) {
                        return false;
                    }
                    value = cst;
                    found = true;
                }
            }
        }
        return found;
    }

    /**
     * The constant a constant-push instruction carries, or null if the
     * instruction is no constant push.
     */
    private static Object constantValueOfPush(final AbstractInsnNode insn) {
        if (insn == null) {
            return null;
        }
        if (insn instanceof LdcInsnNode) {
            final Object cst = ((LdcInsnNode) insn).cst;
            return cst instanceof String || cst instanceof Integer
                    || cst instanceof Long || cst instanceof Float || cst instanceof Double
                    ? cst : null;
        }
        switch (insn.getOpcode()) {
            case Opcodes.ICONST_0: return 0;
            case Opcodes.ICONST_1: return 1;
            case Opcodes.ICONST_2: return 2;
            case Opcodes.ICONST_3: return 3;
            case Opcodes.ICONST_4: return 4;
            case Opcodes.ICONST_5: return 5;
            case Opcodes.LCONST_0: return 0L;
            case Opcodes.LCONST_1: return 1L;
            case Opcodes.FCONST_0: return 0F;
            case Opcodes.FCONST_1: return 1F;
            case Opcodes.FCONST_2: return 2F;
            case Opcodes.DCONST_0: return 0D;
            case Opcodes.DCONST_1: return 1D;
            case Opcodes.BIPUSH:
            case Opcodes.SIPUSH:
                return ((org.objectweb.asm.tree.IntInsnNode) insn).operand;
            default:
                return null;
        }
    }

    private static boolean isConstantPush(final AbstractInsnNode insn) {
        return constantValueOfPush(insn) != null;
    }

    private static Map<String, Object> staticConstants(final ClassNode node) {
        final Map<String, Object> values = new HashMap<>();
        for (final FieldNode field : node.fields) {
            if (isStaticConstantVariable(field)) {
                values.put(field.name, field.value);
            }
        }
        return values;
    }

    private static String value(final Object value) {
        return value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
    }

}
