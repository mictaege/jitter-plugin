package com.github.mictaege.jitter.plugin;

import static com.github.mictaege.jitter.plugin.InsnTool.findValueStart;
import static com.github.mictaege.jitter.plugin.InsnTool.selfContained;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Removing a field with an inline initializer leaves the initializer store
 * behind in the constructor resp. the static initializer. The store
 * including the instructions that produce the value has to be removed as
 * well, as the source transformation would do implicitly - including the
 * synthetic lambda methods the initializer referenced, transitively.
 */
final class InitializerRemover {

    private InitializerRemover() {
        super();
    }

    static void removeFieldInitializers(final StripContext ctx) {
        for (final String key : ctx.result.getRemovedFields()) {
            final String[] parts = key.split("\\|");
            final String owner = parts[0];
            final String fieldName = parts[1];
            final String fieldDesc = parts[2];
            final boolean isStatic = "S".equals(parts[3]);

            final ClassNode node = ctx.nodes.get(owner);
            if (node == null) {
                continue;
            }
            for (final MethodNode method : new ArrayList<>(node.methods)) {
                final boolean initializerHost = isStatic
                        ? method.name.equals("<clinit>")
                        : method.name.equals("<init>");
                if (!initializerHost) {
                    continue;
                }
                removeInitializerStores(ctx, node, method, fieldName, fieldDesc);
            }
        }
    }

    private static void removeInitializerStores(final StripContext ctx, final ClassNode node,
                                                final MethodNode method,
                                                final String fieldName, final String fieldDesc) {
        final String owner = node.name;
        final List<AbstractInsnNode> insns = new ArrayList<>();
        method.instructions.forEach(insns::add);
        int t = 0;
        while (t < insns.size()) {
            final AbstractInsnNode insn = insns.get(t);
            t++;
            if (!(insn instanceof FieldInsnNode)) {
                continue;
            }
            final FieldInsnNode field = (FieldInsnNode) insn;
            final boolean put = field.getOpcode() == org.objectweb.asm.Opcodes.PUTFIELD
                    || field.getOpcode() == org.objectweb.asm.Opcodes.PUTSTATIC;
            if (!put || !field.owner.equals(owner) || !field.name.equals(fieldName)
                    || !field.desc.equals(fieldDesc)) {
                continue;
            }
            final int slots = field.getOpcode() == org.objectweb.asm.Opcodes.PUTFIELD ? 2 : 1;
            final int start = findValueStart(insns, t - 1, slots);
            if (start < 0 || !selfContained(method, insns, start, t)) {
                ctx.result.info("Could not remove the initializer of " + owner.replace('/', '.')
                        + "#" + fieldName + " automatically");
                continue;
            }
            final List<AbstractInsnNode> block = insns.subList(start, t);
            removeInitializerLambdas(ctx, node, block);
            block.forEach(method.instructions::remove);
            block.clear();
            t = start;
            ctx.dirty.add(owner);
        }
    }

    /**
     * The lambdas and method references of a removed initializer compile
     * to synthetic methods ({@code lambda$new$N}) referenced by the
     * block's {@code invokedynamic} bootstrap handles. Their bodies may
     * themselves reference flavour specific code, so they are removed
     * together with the initializer - transitively, since lambdas nest.
     * Every removed lambda is registered for the
     * {@code $deserializeLambda$} cleanup, exactly like the lambdas of
     * removed methods.
     */
    private static void removeInitializerLambdas(final StripContext ctx, final ClassNode node,
                                                 final List<AbstractInsnNode> block) {
        final List<MethodNode> agenda = new ArrayList<>();
        for (final AbstractInsnNode insn : block) {
            addLambdaTargets(node, insn, agenda);
        }
        final List<MethodNode> toRemove = new ArrayList<>();
        while (!agenda.isEmpty()) {
            final MethodNode lambda = agenda.remove(agenda.size() - 1);
            if (toRemove.contains(lambda)) {
                continue;
            }
            toRemove.add(lambda);
            for (final AbstractInsnNode insn : lambda.instructions) {
                addLambdaTargets(node, insn, agenda);
            }
        }
        if (toRemove.isEmpty()) {
            return;
        }
        node.methods.removeAll(toRemove);
        for (final MethodNode lambda : toRemove) {
            ctx.result.removedMember(StripResult.memberKey(node.name, lambda.name, lambda.desc));
            ctx.result.info("Remove lambda " + node.name.replace('/', '.') + "#" + lambda.name);
            ctx.removedLambdas
                    .computeIfAbsent(node.name, k -> new ArrayList<>())
                    .add(new String[]{lambda.name, lambda.desc});
        }
        ctx.dirty.add(node.name);
    }

    /**
     * Adds the synthetic lambda methods the given instruction references
     * via {@code invokedynamic} bootstrap handles - only handles pointing
     * into this class; those are the class's own lambda implementations.
     */
    private static void addLambdaTargets(final ClassNode node, final AbstractInsnNode insn,
                                         final List<MethodNode> agenda) {
        if (!(insn instanceof InvokeDynamicInsnNode)) {
            return;
        }
        for (final Object arg : ((InvokeDynamicInsnNode) insn).bsmArgs) {
            if (arg instanceof Handle
                    && ((Handle) arg).getOwner().equals(node.name)) {
                final Handle handle = (Handle) arg;
                for (final MethodNode candidate : node.methods) {
                    if (candidate.name.equals(handle.getName())
                            && candidate.desc.equals(handle.getDesc())) {
                        agenda.add(candidate);
                    }
                }
            }
        }
    }

}
