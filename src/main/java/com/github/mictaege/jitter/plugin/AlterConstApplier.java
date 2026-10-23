package com.github.mictaege.jitter.plugin;

import static com.github.mictaege.jitter.plugin.JitterUtil.anyFlavour;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Applies the flavour specific values that {@code @AlterConstValue}
 * declares on the constants of the stripped tree.
 *
 * <p>String constants carry the placeholder anchor - the fully qualified
 * name of the constant itself - as their full model value, which javac has
 * inlined into every usage site, most importantly into annotation values.
 * The anchor is unique and names the declaring class, so the flavour build
 * can re-bake it to the active flavour's value wherever it occurs - in the
 * own tree as well as in the bytecode of dependent modules, whose
 * constants resolve lazily from the compile classpath.
 *
 * <p>Wrapper constants (Integer, Boolean, ...) are no constant variables:
 * nothing is inlined, the usages are references that follow the flavour at
 * runtime. Only the single assignment in the declaring class's
 * {@code <clinit>} needs the patch - a simple folded literal, as javac
 * emits it for a constant declaration initializer; anything computed fails
 * the build with a clear error.
 */
final class AlterConstApplier {

    private final StripContext ctx;
    private final String activeFlavour;

    /**
     * The anchors of the own String constants with an active flavour
     * value, by anchor - the replacements of the own tree.
     */
    private final Map<String, String> ownPlaceholders = new HashMap<>();

    /** Every anchor of the own tree, including those without an active value. */
    private final Set<String> ownAnchors = new HashSet<>();

    /**
     * Memoized external anchor lookups, the misses included - a lookup
     * walks the compile classpath, so each distinct candidate string pays
     * for it at most once.
     */
    private final Map<String, FieldNode> externalFields = new HashMap<>();

    /** The wrapper constants of the own tree to patch, with their value. */
    private final List<WrapperPatch> wrapperPatches = new ArrayList<>();

    private AlterConstApplier(final StripContext ctx) {
        this.ctx = ctx;
        this.activeFlavour = JitterUtil.activeFlavour();
    }

    /**
     * Patches the placeholder anchors and the wrapper initializers of the
     * surviving classes. Runs after the {@code @OnlyIf} removals, so only
     * constants that are visible in the active flavour are touched.
     */
    static void apply(final StripContext ctx) {
        if (!anyFlavour()) {
            return;
        }
        final AlterConstApplier applier = new AlterConstApplier(ctx);
        applier.collectOwnAlterConsts();
        applier.patchWrapperInitializers();
        applier.patchDeclarations();
        applier.patchAnnotations();
        applier.warnAboutLeftOverAnchorsInAnnotations();
    }

    /**
     * The paranoia scan: no placeholder anchor may survive in the code of
     * the flavour model - a body usage of an {@code @AlterConstValue}
     * String constant keeps the anchor instead of the flavour value,
     * because only annotation values are re-baked. javac folds constant
     * concatenations like {@code "prefix" + ANCHOR} into one literal, so
     * the scan matches the anchors as substrings as well. The opt-in
     * source level check reports those usages as review errors; this scan
     * is the bytecode level safety net that fails the build regardless.
     */
    static void verifyNoPlaceholdersLeft(final StripContext ctx) {
        if (!anyFlavour()) {
            return;
        }
        final AlterConstApplier applier = new AlterConstApplier(ctx);
        applier.collectOwnAnchors();
        for (final ClassNode node : ctx.nodes.values()) {
            for (final MethodNode method : node.methods) {
                if (method.instructions == null) {
                    continue;
                }
                for (final AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof LdcInsnNode
                            && ((LdcInsnNode) insn).cst instanceof String) {
                        final String anchor = applier.leakedAnchor(
                                (String) ((LdcInsnNode) insn).cst);
                        if (anchor != null) {
                            ctx.result.error("The code of " + node.name.replace('/', '.')
                                    + "#" + method.name + " contains the placeholder anchor "
                                    + anchor + " of an @AlterConstValue String constant:"
                                    + " a usage outside an annotation is not re-baked and"
                                    + " would keep the anchor instead of the flavour"
                                    + " value. Use the constant in annotations only"
                                    + " or switch to a wrapper type.");
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- collection

    private void collectOwnAlterConsts() {
        collectOwnAnchors();
        for (final ClassNode node : ctx.nodes.values()) {
            for (final FieldNode field : node.fields) {
                final String value = activeValueOf(field.visibleAnnotations);
                if (value == null || "Ljava/lang/String;".equals(field.desc)) {
                    continue;
                }
                wrapperPatches.add(new WrapperPatch(node, field, value));
            }
        }
    }

    /**
     * All anchors of the own tree: the String constants that carry
     * {@code @AlterConstValue}. The anchor of a constant is its fully
     * qualified name - the declaration-side audit enforces that - so it
     * is rebuilt from the field instead of read from the patched value:
     * the declaration patch may already have replaced it with the
     * flavour value.
     */
    private void collectOwnAnchors() {
        for (final ClassNode node : ctx.nodes.values()) {
            for (final FieldNode field : node.fields) {
                if (!"Ljava/lang/String;".equals(field.desc)
                        || JitterAnnotations.alterConstValues(field.visibleAnnotations).isEmpty()) {
                    continue;
                }
                final String anchor = node.name.replace('/', '.') + "." + field.name;
                ownAnchors.add(anchor);
                final String value = activeValueOf(field.visibleAnnotations);
                if (value != null) {
                    ownPlaceholders.put(anchor, value);
                }
            }
        }
    }

    /**
     * The active flavour's {@code @AlterConstValue} value of the given
     * annotations, or null when the annotations have none for it.
     */
    private String activeValueOf(final List<AnnotationNode> annotations) {
        for (final AnnotationNode alter : JitterAnnotations.alterConstValues(annotations)) {
            for (final String flavour : JitterAnnotations.annotationValues(alter, "ifActive")) {
                if (flavour.equalsIgnoreCase(activeFlavour)) {
                    return JitterAnnotations.annotationValue(alter, "with");
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------ wrapper patching

    private void patchWrapperInitializers() {
        for (final WrapperPatch patch : wrapperPatches) {
            final MethodNode clinit = clinitOf(patch.node);
            if (clinit == null || clinit.instructions == null) {
                ctx.result.error("The @AlterConstValue wrapper constant "
                        + patch.node.name.replace('/', '.') + "#" + patch.field.name
                        + " has no <clinit> assignment to patch.");
                continue;
            }
            for (final AbstractInsnNode insn : clinit.instructions) {
                if (insn instanceof FieldInsnNode
                        && ((FieldInsnNode) insn).name.equals(patch.field.name)
                        && ((FieldInsnNode) insn).desc.equals(patch.field.desc)
                        && insn.getOpcode() == Opcodes.PUTSTATIC) {
                    patchAssignment(clinit, insn, patch);
                    break;
                }
            }
        }
    }

    /**
     * Patches the constant load in front of the PUTSTATIC to the active
     * flavour's literal. Only the javac shape of a constant declaration
     * initializer is patched - a folded literal push, wrapped in the
     * wrapper's {@code valueOf} for wrapper types, or the bare push of a
     * non-constant primitive field (Kotlin 'val'): anything computed
     * fails the build.
     */
    private void patchAssignment(final MethodNode clinit, final AbstractInsnNode put,
                                 final WrapperPatch patch) {
        final AbstractInsnNode previous = previousReal(clinit, put);
        if (previous == null) {
            fail(patch, "its assignment is no simple folded literal");
            return;
        }
        if (patch.field.desc.length() == 1) {
            // a non-constant primitive field (Kotlin 'val', Java
            // non-final) pushes the literal directly - no boxing between
            patchLiteralLoad(clinit, previous, patch);
            return;
        }
        if ("Ljava/lang/Boolean;".equals(patch.field.desc)
                && previous.getOpcode() == Opcodes.GETSTATIC) {
            final FieldInsnNode bool = (FieldInsnNode) previous;
            if (!"java/lang/Boolean".equals(bool.owner)) {
                fail(patch, "its assignment is no simple folded literal");
                return;
            }
            final String target = Boolean.parseBoolean(patch.value) ? "TRUE" : "FALSE";
            if (!target.equals(bool.name)) {
                bool.name = target;
                ctx.dirty.add(patch.node.name);
            }
            return;
        }
        if (previous.getOpcode() != Opcodes.INVOKESTATIC
                || !isWrapperValueOf((MethodInsnNode) previous, patch.field.desc)) {
            fail(patch, "its assignment is no simple folded literal");
            return;
        }
        final AbstractInsnNode load = previousReal(clinit, previous);
        if (load == null) {
            fail(patch, "its assignment is no simple folded literal");
            return;
        }
        patchLiteralLoad(clinit, load, patch);
    }

    private void patchLiteralLoad(final MethodNode clinit, final AbstractInsnNode load,
                                 final WrapperPatch patch) {
        if (!isPush(load)) {
            fail(patch, "its assignment is no simple folded literal");
            return;
        }
        try {
            final AbstractInsnNode replacement = literalLoad(patch);
            if (replacement != null) {
                clinit.instructions.set(load, replacement);
                ctx.dirty.add(patch.node.name);
            } else {
                fail(patch, "its assignment is no simple folded literal");
            }
        } catch (final NumberFormatException e) {
            fail(patch, "the with value \"" + patch.value
                    + "\" does not fit the constant's type");
        }
    }

    /**
     * The instruction that pushes the active flavour's literal for the
     * field's type, or null if the load cannot be replaced.
     */
    private AbstractInsnNode literalLoad(final WrapperPatch patch) {
        final String desc = patch.field.desc;
        if ("I".equals(desc) || "S".equals(desc) || "B".equals(desc) || "C".equals(desc)) {
            final int value;
            if ("C".equals(desc)) {
                if (patch.value.length() != 1) {
                    throw new NumberFormatException(patch.value);
                }
                value = patch.value.charAt(0);
            } else {
                value = Integer.parseInt(patch.value);
            }
            return InsnTool.pushInt(value);
        }
        if ("Z".equals(desc)) {
            return new InsnNode(Boolean.parseBoolean(patch.value)
                    ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        }
        if ("J".equals(desc)) {
            final long value = Long.parseLong(patch.value);
            return value == 0L ? new InsnNode(Opcodes.LCONST_0)
                    : value == 1L ? new InsnNode(Opcodes.LCONST_1)
                    : new LdcInsnNode(value);
        }
        if ("F".equals(desc)) {
            final float value = Float.parseFloat(patch.value);
            return value == 0f ? new InsnNode(Opcodes.FCONST_0)
                    : value == 1f ? new InsnNode(Opcodes.FCONST_1)
                    : value == 2f ? new InsnNode(Opcodes.FCONST_2)
                    : new LdcInsnNode(value);
        }
        if ("D".equals(desc)) {
            final double value = Double.parseDouble(patch.value);
            return value == 0d ? new InsnNode(Opcodes.DCONST_0)
                    : value == 1d ? new InsnNode(Opcodes.DCONST_1)
                    : new LdcInsnNode(value);
        }
        if ("Ljava/lang/Boolean;".equals(desc)) {
            // javac boxes booleans either via Boolean.TRUE/FALSE or via
            // valueOf(iconst) - both shapes are patched
            return new InsnNode(Boolean.parseBoolean(patch.value)
                    ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
        }
        if ("Ljava/lang/Integer;".equals(desc)) {
            return InsnTool.pushInt(Integer.parseInt(patch.value));
        }
        if ("Ljava/lang/Character;".equals(desc)) {
            if (patch.value.length() != 1) {
                throw new NumberFormatException(patch.value);
            }
            return InsnTool.pushInt(patch.value.charAt(0));
        }
        if ("Ljava/lang/Short;".equals(desc)) {
            return InsnTool.pushInt(Short.parseShort(patch.value));
        }
        if ("Ljava/lang/Byte;".equals(desc)) {
            return InsnTool.pushInt(Byte.parseByte(patch.value));
        }
        if ("Ljava/lang/Long;".equals(desc)) {
            final long value = Long.parseLong(patch.value);
            return value == 0L ? new InsnNode(Opcodes.LCONST_0)
                    : value == 1L ? new InsnNode(Opcodes.LCONST_1)
                    : new LdcInsnNode(value);
        }
        if ("Ljava/lang/Float;".equals(desc)) {
            final float value = Float.parseFloat(patch.value);
            return value == 0f ? new InsnNode(Opcodes.FCONST_0)
                    : value == 1f ? new InsnNode(Opcodes.FCONST_1)
                    : value == 2f ? new InsnNode(Opcodes.FCONST_2)
                    : new LdcInsnNode(value);
        }
        if ("Ljava/lang/Double;".equals(desc)) {
            final double value = Double.parseDouble(patch.value);
            return value == 0d ? new InsnNode(Opcodes.DCONST_0)
                    : value == 1d ? new InsnNode(Opcodes.DCONST_1)
                    : new LdcInsnNode(value);
        }
        return null;
    }

    private boolean isPush(final AbstractInsnNode insn) {
        final int opcode = insn.getOpcode();
        return (opcode >= Opcodes.ICONST_0 && opcode <= Opcodes.ICONST_5)
                || opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH
                || opcode == Opcodes.LDC || opcode == Opcodes.LCONST_0
                || opcode == Opcodes.LCONST_1 || opcode == Opcodes.FCONST_0
                || opcode == Opcodes.FCONST_1 || opcode == Opcodes.FCONST_2
                || opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1;
    }

    private boolean isWrapperValueOf(final MethodInsnNode invoke, final String desc) {
        final String owner = wrapperOwner(desc);
        return owner != null && owner.equals(invoke.owner) && "valueOf".equals(invoke.name);
    }

    private String wrapperOwner(final String desc) {
        switch (desc) {
            case "Ljava/lang/Integer;": return "java/lang/Integer";
            case "Ljava/lang/Long;": return "java/lang/Long";
            case "Ljava/lang/Short;": return "java/lang/Short";
            case "Ljava/lang/Byte;": return "java/lang/Byte";
            case "Ljava/lang/Character;": return "java/lang/Character";
            case "Ljava/lang/Boolean;": return "java/lang/Boolean";
            case "Ljava/lang/Float;": return "java/lang/Float";
            case "Ljava/lang/Double;": return "java/lang/Double";
            default: return null;
        }
    }

    private void fail(final WrapperPatch patch, final String why) {
        ctx.result.error("The @AlterConstValue wrapper constant "
                + patch.node.name.replace('/', '.') + "#" + patch.field.name
                + " cannot be re-baked: " + why + ". Declare the constant with a"
                + " constant expression initializer, like 'static final "
                + patch.field.desc.substring("Ljava/lang/".length(),
                        patch.field.desc.length() - 1)
                + " " + patch.field.name + " = ...'.");
    }

    private MethodNode clinitOf(final ClassNode node) {
        for (final MethodNode method : node.methods) {
            if ("<clinit>".equals(method.name)) {
                return method;
            }
        }
        return null;
    }

    private AbstractInsnNode previousReal(final MethodNode method, final AbstractInsnNode insn) {
        for (AbstractInsnNode previous = insn.getPrevious(); previous != null;
                previous = previous.getPrevious()) {
            if (!InsnTool.isMeta(previous)) {
                return previous;
            }
        }
        return null;
    }

    // ---------------------------------------------------- placeholder patching

    private void patchDeclarations() {
        for (final ClassNode node : ctx.nodes.values()) {
            for (final FieldNode field : node.fields) {
                if (!"Ljava/lang/String;".equals(field.desc)
                        || !(field.value instanceof String)) {
                    continue;
                }
                final String flavourValue = ownPlaceholders.get(field.value);
                if (flavourValue != null) {
                    field.value = flavourValue;
                    ctx.dirty.add(node.name);
                }
            }
        }
    }

    /**
     * Re-bakes the inlined anchors in the annotations of all surviving
     * classes - the own placeholders first, the anchors of dependent
     * modules after, resolved lazily from the compile classpath because
     * the anchor names the declaring class.
     */
    private void patchAnnotations() {
        int rebaked = 0;
        for (final ClassNode node : ctx.nodes.values()) {
            boolean changed = false;
            changed |= rebake(node.visibleAnnotations);
            changed |= rebake(node.invisibleAnnotations);
            for (final FieldNode field : node.fields) {
                changed |= rebake(field.visibleAnnotations);
                changed |= rebake(field.invisibleAnnotations);
            }
            for (final MethodNode method : node.methods) {
                changed |= rebake(method.visibleAnnotations);
                changed |= rebake(method.invisibleAnnotations);
                changed |= rebake(method.visibleParameterAnnotations);
                changed |= rebake(method.invisibleParameterAnnotations);
                if (method.annotationDefault instanceof AnnotationNode) {
                    changed |= rebake(java.util.Collections.singletonList(
                            (AnnotationNode) method.annotationDefault));
                } else if (method.annotationDefault instanceof List) {
                    changed |= rebakeList((List<Object>) method.annotationDefault);
                }
            }
            if (changed) {
                rebaked++;
                ctx.dirty.add(node.name);
            }
        }
        if (rebaked > 0) {
            ctx.result.info("Re-baked the @AlterConstValue anchors of "
                    + ownPlaceholders.size() + " String constant(s) in the"
                    + " annotations of " + rebaked + " class(es)");
        }
    }

    private boolean rebake(final List<AnnotationNode>[] annotationArrays) {
        boolean changed = false;
        if (annotationArrays != null) {
            for (final List<AnnotationNode> annotations : annotationArrays) {
                changed |= rebake(annotations);
            }
        }
        return changed;
    }

    private boolean rebake(final List<AnnotationNode> annotations) {
        boolean changed = false;
        if (annotations != null) {
            for (final AnnotationNode annotation : annotations) {
                changed |= rebake(annotation);
            }
        }
        return changed;
    }

    private boolean rebake(final AnnotationNode annotation) {
        boolean changed = false;
        if (annotation.values != null) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
                final Object value = annotation.values.get(i + 1);
                if (value instanceof String) {
                    final String flavourValue = activeValueOfAnchor((String) value);
                    if (flavourValue != null) {
                        annotation.values.set(i + 1, flavourValue);
                        changed = true;
                    }
                } else if (value instanceof AnnotationNode) {
                    changed |= rebake((AnnotationNode) value);
                } else if (value instanceof List) {
                    changed |= rebakeList((List<Object>) value);
                }
            }
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private boolean rebakeList(final List<Object> values) {
        boolean changed = false;
        for (int i = 0; i < values.size(); i++) {
            final Object value = values.get(i);
            if (value instanceof String) {
                final String flavourValue = activeValueOfAnchor((String) value);
                if (flavourValue != null) {
                    values.set(i, flavourValue);
                    changed = true;
                }
            } else if (value instanceof AnnotationNode) {
                changed |= rebake((AnnotationNode) value);
            } else if (value instanceof List) {
                changed |= rebakeList((List<Object>) value);
            }
        }
        return changed;
    }

    // ------------------------------------------------------------- resolution

    private boolean isAnchor(final String value) {
        return ownAnchors.contains(value) || externalFieldOf(value) != null;
    }

    /**
     * The anchor of an {@code @AlterConstValue} String constant that
     * leaked into the given string - as the whole string or folded into a
     * concatenation. Only the known anchors match: the own tree's anchors
     * and the externally discovered ones.
     */
    private String leakedAnchor(final String value) {
        if (ownAnchors.contains(value)) {
            return value;
        }
        if (isAnchor(value)) {
            return value;
        }
        for (final String anchor : ownAnchors) {
            if (value.contains(anchor)) {
                return anchor;
            }
        }
        for (final Map.Entry<String, FieldNode> external : externalFields.entrySet()) {
            if (external.getValue() != null && value.contains(external.getKey())) {
                return external.getKey();
            }
        }
        return null;
    }

    private String activeValueOfAnchor(final String placeholder) {
        final String own = ownPlaceholders.get(placeholder);
        if (own != null) {
            return own;
        }
        final FieldNode external = externalFieldOf(placeholder);
        return external == null ? null : activeValueOf(external.visibleAnnotations);
    }

    /**
     * The field an externally inlined anchor points to, or null. The
     * anchor names the declaring class, so the resolution is a targeted
     * load of that one class - own tree first, compile classpath after.
     * A cheap structural pre-filter keeps ordinary annotation strings
     * (paths, table names, class names, ...) away from the classpath.
     */
    private FieldNode externalFieldOf(final String placeholder) {
        if (externalFields.containsKey(placeholder)) {
            return externalFields.get(placeholder);
        }
        final FieldNode field = looksLikeAnchor(placeholder)
                ? loadExternalField(placeholder)
                : null;
        externalFields.put(placeholder, field);
        return field;
    }

    private FieldNode loadExternalField(final String placeholder) {
        final int idx = placeholder.lastIndexOf('.');
        final String fieldName = placeholder.substring(idx + 1);
        final String internal = placeholder.substring(0, idx).replace('.', '/');
        final ClassNode node = ctx.nodes.containsKey(internal)
                ? ctx.nodes.get(internal)
                : ClassIndex.loadFromClasspath(ctx.templateClasspath, internal, ctx.result);
        if (node == null) {
            return null;
        }
        for (final FieldNode field : node.fields) {
            if (field.name.equals(fieldName)
                    && !JitterAnnotations.alterConstValues(field.visibleAnnotations).isEmpty()) {
                return field;
            }
        }
        return null;
    }

    /**
     * The anchor of an {@code @AlterConstValue} String constant is the
     * fully qualified name of the constant - package segments, a class
     * segment starting uppercase, then the field name. Every character
     * must be a Java identifier part: code can carry arbitrary strings,
     * even binary ones, and a loose shape check would feed them to the
     * classpath resolution.
     */
    private static boolean looksLikeAnchor(final String value) {
        final int idx = value.lastIndexOf('.');
        if (idx <= 0 || idx + 1 >= value.length()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c != '.' && !Character.isJavaIdentifierPart(c)) {
                return false;
            }
        }
        final int clsIdx = value.lastIndexOf('.', idx - 1);
        if (clsIdx < 0) {
            return false;
        }
        final String simple = value.substring(clsIdx + 1, idx);
        return !simple.isEmpty() && Character.isUpperCase(simple.charAt(0));
    }


    // ----------------------------------------------------------- anchor sweep

    private void warnAboutLeftOverAnchorsInAnnotations() {
        for (final ClassNode node : ctx.nodes.values()) {
            warn(node.visibleAnnotations, node.name.replace('/', '.'));
            for (final FieldNode field : node.fields) {
                warn(field.visibleAnnotations,
                        node.name.replace('/', '.') + "#" + field.name);
            }
            for (final MethodNode method : node.methods) {
                warn(method.visibleAnnotations,
                        node.name.replace('/', '.') + "#" + method.name);
            }
        }
    }

    private void warn(final List<AnnotationNode> annotations, final String where) {
        if (annotations == null) {
            return;
        }
        for (final AnnotationNode annotation : annotations) {
            if (annotation.values == null) {
                continue;
            }
            for (int i = 0; i < annotation.values.size(); i += 2) {
                final Object value = annotation.values.get(i + 1);
                if (value instanceof String && isAnchor((String) value)
                        && activeValueOfAnchor((String) value) == null) {
                    ctx.result.warn("The annotation of " + where
                            + " keeps the placeholder anchor " + value
                            + " of an @AlterConstValue String constant that is not"
                            + " visible in the active flavour - the value would not"
                            + " follow the flavour. See jitterVerifyInlinedConstants.");
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static final class WrapperPatch {
        private final ClassNode node;
        private final FieldNode field;
        private final String value;

        private WrapperPatch(final ClassNode node, final FieldNode field, final String value) {
            this.node = node;
            this.field = field;
            this.value = value;
        }
    }

}
