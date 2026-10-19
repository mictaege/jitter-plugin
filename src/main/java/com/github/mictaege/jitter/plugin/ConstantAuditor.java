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
 * <p>The second rule set covers the flavour variance of a constant's
 * VALUE, declared with {@code @AlterConstValue} on the constant itself
 * (an {@code @Alter} of a whole class must not hold static constants
 * anymore: javac has inlined the full model value into the usages, so
 * they would not follow the swap):
 *
 * <ul>
 * <li>a class annotated {@code @Alter} with a static constant variable
 * fails the build</li>
 * <li>a String constant with {@code @AlterConstValue} must be a constant
 * variable whose value is the placeholder anchor - the fully qualified
 * name of the constant itself - and must be marked
 * {@code @InlinedConstantApproved}: the flavour build re-bakes the
 * placeholder to the active flavour's value wherever it was inlined,
 * which is only sound inside annotations</li>
 * <li>a non-String constant with {@code @AlterConstValue} must use a
 * wrapper type and must not be marked: wrapper constants are no
 * constant variables, nothing is inlined and the usages follow the
 * flavour at runtime</li>
 * <li>the {@code @AlterConstValue} instances must cover every flavour in
 * which the constant is visible - its {@code @OnlyIf} gates intersected
 * with the class and package gates - and must not name flavours where it
 * is not visible</li>
 * </ul>
 */
final class ConstantAuditor {

    private ConstantAuditor() {
        super();
    }

    static void audit(final StripContext ctx) {
        auditFlavourVariantConstants(ctx);
        auditAlterClasses(ctx);
        auditAlterConstValues(ctx);
    }

    private static void auditFlavourVariantConstants(final StripContext ctx) {
        final Map<String, List<String>> packageGates = new HashMap<>();
        for (final ClassNode node : new ArrayList<>(ctx.nodes.values())) {
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
                // a constant with @AlterConstValue has a flavour variant
                // value even when its existence is not flavour variant
                final boolean valueVariant =
                        !JitterAnnotations.alterConstValues(field.visibleAnnotations).isEmpty();
                final boolean marked =
                        JitterAnnotations.isInlinedConstantApproved(field.visibleAnnotations);
                if (marked && !isConstantVariable(node, field)) {
                    ctx.result.error("The @InlinedConstantApproved marker of "
                            + node.name.replace('/', '.') + "#" + field.name
                            + " is meaningless: the field is no compile-time constant,"
                            + " so javac does not inline its usages and the flavour"
                            + " stripping verifies them as references. Remove the"
                            + " marker.");
                } else if (marked && !flavourVariant && !valueVariant) {
                    ctx.result.error("The @InlinedConstantApproved marker of "
                            + node.name.replace('/', '.') + "#" + field.name
                            + " is meaningless: the field is not flavour variant, its"
                            + " value is the same in every flavour. Remove the marker.");
                } else if (!marked && (flavourVariant || valueVariant)
                        && isConstantVariable(node, field)) {
                    ctx.result.error("The compile-time constant "
                            + node.name.replace('/', '.') + "#" + field.name
                            + " is flavour variant - its gate, class, package or"
                            + " @AlterConstValue does not keep it in all flavours -"
                            + " but javac inlines its"
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

    /**
     * An {@code @Alter} swaps the whole class body per flavour, but javac
     * has inlined the class's static constant values into the usage sites
     * outside it - those baked-in values do not follow the swap. Classes
     * annotated {@code @Alter} must therefore not hold static constant
     * variables: the flavour variance of a constant's value is declared
     * with {@code @AlterConstValue} on the constant itself instead.
     */
    private static void auditAlterClasses(final StripContext ctx) {
        for (final ClassNode node : new ArrayList<>(ctx.nodes.values())) {
            if (JitterAnnotations.alterAnnotations(node.visibleAnnotations).isEmpty()) {
                continue;
            }
            for (final FieldNode field : node.fields) {
                if (isStaticConstantVariable(field)) {
                    ctx.result.error("The @Alter class " + node.name.replace('/', '.')
                            + " holds the static constant " + field.name + " = "
                            + value(field.value) + ", but javac has inlined that value"
                            + " into the usages outside the class, so they do not follow"
                            + " the flavour swap. Move the constant into a constants"
                            + " class without @Alter and declare its flavour specific"
                            + " value with @AlterConstValue.");
                }
            }
        }
    }

    /**
     * The {@code @AlterConstValue} rules: the String placeholder anchor,
     * the wrapper types instead of primitives, and the coverage of every
     * flavour in which the constant is visible.
     */
    private static void auditAlterConstValues(final StripContext ctx) {
        final Map<String, List<String>> packageGates = new HashMap<>();
        for (final ClassNode node : new ArrayList<>(ctx.nodes.values())) {
            final List<String> classGate = JitterAnnotations.onlyIfFlavours(node);
            final List<String> packageGate =
                    packageGateOf(ctx, packageGates, packageOf(node.name));
            for (final FieldNode field : node.fields) {
                final List<AnnotationNode> alters =
                        JitterAnnotations.alterConstValues(field.visibleAnnotations);
                if (alters.isEmpty()) {
                    continue;
                }
                final String fqn = node.name.replace('/', '.') + "#" + field.name;
                if (!isStatic(field)) {
                    ctx.result.error("The @AlterConstValue of " + fqn + " is meaningless:"
                            + " the field is not static. Declare the flavour specific"
                            + " value on a static constant.");
                    continue;
                }
                if ("Ljava/lang/String;".equals(field.desc)) {
                    if (!isStaticConstantVariable(field)) {
                        ctx.result.error("The @AlterConstValue constant " + fqn + " is a"
                                + " String but no constant variable (static final with a"
                                + " constant initializer), so javac does not inline its"
                                + " usages and the placeholder anchor would never engage."
                                + " Make it a constant variable or declare the flavour"
                                + " variance without inlining (a wrapper type).");
                        continue;
                    }
                    final String anchor = node.name.replace('/', '.') + "." + field.name;
                    if (!anchor.equals(field.value)) {
                        ctx.result.error("The @AlterConstValue constant " + fqn
                                + " has the value " + value(field.value) + " instead of"
                                + " the placeholder anchor " + value(anchor) + ": a String"
                                + " constant is inlined into its usage sites, so its full"
                                + " model value must be the fully qualified name of the"
                                + " constant itself - unique by construction and naming"
                                + " the declaring class, it is what the flavour build"
                                + " re-bakes to the active flavour's value.");
                    }
                } else if (field.desc.length() == 1 && isStaticConstantVariable(field)) {
                    ctx.result.error("The @AlterConstValue constant " + fqn + " has the"
                            + " primitive type " + field.desc + " and is a constant variable,"
                            + " so javac inlines it into the usage sites and the baked-in"
                            + " values would not follow the flavour. Use a non-inlinable"
                            + " form instead - the wrapper type (Integer, Boolean, ...) or a"
                            + " non-constant field (Kotlin: 'val' instead of 'const val'):"
                            + " their usages are references and follow the flavour at"
                            + " runtime.");
                } else if (field.desc.length() == 1) {
                    // a primitive WITHOUT a constant value (Kotlin 'val',
                    // Java non-final) is no constant variable: nothing is
                    // inlined, the usages are references - the applier
                    // patches the single <clinit> assignment
                } else if (!isWrapperType(field.desc)) {
                    ctx.result.error("The @AlterConstValue constant " + fqn + " has the"
                            + " unsupported type " + field.desc + ": only String (the"
                            + " placeholder anchor, usable in annotations) and the wrapper"
                            + " types Integer, Long, Short, Byte, Character, Boolean,"
                            + " Float and Double are supported.");
                }
                auditConstValueCoverage(ctx, fqn, alters, classGate, packageGate,
                        JitterAnnotations.onlyIfFlavours(field.visibleAnnotations));
            }
        }
    }

    /**
     * The {@code @AlterConstValue} instances must cover every flavour in
     * which the constant is visible - the intersection of the field's
     * {@code @OnlyIf} gate with the class and package gates - and must
     * not name flavours where it is not visible.
     */
    private static void auditConstValueCoverage(final StripContext ctx, final String fqn,
                                                final List<AnnotationNode> alters,
                                                final List<String> classGate,
                                                final List<String> packageGate,
                                                final List<String> fieldGate) {
        if (ctx.flavours == null || ctx.flavours.isEmpty()) {
            return;
        }
        final List<String> covered = new ArrayList<>();
        for (final AnnotationNode alter : alters) {
            final List<String> ifActive =
                    JitterAnnotations.annotationValues(alter, "ifActive");
            if (JitterAnnotations.annotationValue(alter, "with") == null) {
                ctx.result.error("The @AlterConstValue of " + fqn + " has no with value.");
            }
            if (ifActive.isEmpty()) {
                ctx.result.error("The @AlterConstValue of " + fqn + " names no flavour in"
                        + " ifActive.");
                continue;
            }
            for (final String flavour : ifActive) {
                if (covered.stream().noneMatch(f -> f.equalsIgnoreCase(flavour))) {
                    covered.add(flavour);
                } else {
                    ctx.result.error("The @AlterConstValue instances of " + fqn
                            + " cover the flavour " + flavour + " more than once.");
                }
            }
        }
        final List<String> visible = visibleFlavours(ctx, fieldGate, classGate, packageGate);
        for (final String flavour : visible) {
            if (covered.stream().noneMatch(f -> f.equalsIgnoreCase(flavour))) {
                ctx.result.error("The @AlterConstValue of " + fqn + " does not cover the"
                        + " flavour " + flavour + ", in which the constant is visible.");
            }
        }
        for (final String flavour : covered) {
            if (visible.stream().noneMatch(f -> f.equalsIgnoreCase(flavour))) {
                ctx.result.error("The @AlterConstValue of " + fqn + " declares a value"
                        + " for the flavour " + flavour + ", in which the constant is not"
                        + " visible (its @OnlyIf, class or package gates remove it"
                        + " there) - the value would be dead.");
            }
        }
    }

    private static List<String> visibleFlavours(final StripContext ctx,
                                                final List<String> fieldGate,
                                                final List<String> classGate,
                                                final List<String> packageGate) {
        final List<String> visible = new ArrayList<>();
        for (final String flavour : ctx.flavours) {
            if (keptByGate(flavour, fieldGate) && keptByGate(flavour, classGate)
                    && keptByGate(flavour, packageGate)) {
                visible.add(flavour);
            }
        }
        return visible;
    }

    private static boolean keptByGate(final String flavour, final List<String> gate) {
        return gate == null || gate.isEmpty()
                || gate.stream().anyMatch(g -> g.equalsIgnoreCase(flavour));
    }

    private static boolean isStatic(final FieldNode field) {
        return (field.access & Opcodes.ACC_STATIC) != 0;
    }

    private static boolean isWrapperType(final String desc) {
        return desc.startsWith("Ljava/lang/") && desc.endsWith(";")
                && (desc.equals("Ljava/lang/Integer;") || desc.equals("Ljava/lang/Long;")
                || desc.equals("Ljava/lang/Short;") || desc.equals("Ljava/lang/Byte;")
                || desc.equals("Ljava/lang/Character;") || desc.equals("Ljava/lang/Boolean;")
                || desc.equals("Ljava/lang/Float;") || desc.equals("Ljava/lang/Double;"));
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

    private static String value(final Object value) {
        return value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
    }

}
