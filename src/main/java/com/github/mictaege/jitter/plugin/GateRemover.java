package com.github.mictaege.jitter.plugin;

import static com.github.mictaege.jitter.plugin.JitterUtil.active;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Removes the block-level flavour gates ({@code Gate.onlyIf(flavours, block)})
 * whose flavour list does not include the active flavour. Both Java and
 * Kotlin compile such a gate to a {@code String[]} constant, an
 * {@code invokedynamic} for the lambda body and an {@code invokestatic} of
 * the gate method; for a non-listed flavour all three are removed together
 * with the synthetic lambda method, as if the statement had never been
 * written.
 */
final class GateRemover {

    private static final String GATE_OWNER = "com/github/mictaege/jitter/api/Gate";
    private static final String RUNNABLE_DESC =
            "(Ljava/util/List;Ljava/lang/Runnable;)V";

    private GateRemover() {
        super();
    }

    static void removeGates(final StripContext ctx) {
        for (final ClassNode node : new ArrayList<>(ctx.nodes.values())) {
            for (final MethodNode method : new ArrayList<>(node.methods)) {
                removeGatesInMethod(ctx, node, method);
            }
        }
    }

    private static void removeGatesInMethod(final StripContext ctx, final ClassNode node,
                                            final MethodNode method) {
        final List<AbstractInsnNode> insns = new ArrayList<>();
        for (int i = 0; i < method.instructions.size(); i++) {
            insns.add(method.instructions.get(i));
        }
        for (int i = 0; i < insns.size(); i++) {
            final AbstractInsnNode insn = insns.get(i);
            if (!(insn instanceof MethodInsnNode)) {
                continue;
            }
            final MethodInsnNode call = (MethodInsnNode) insn;
            if (!GATE_OWNER.equals(call.owner) || !"onlyIf".equals(call.name)) {
                continue;
            }
            if (!RUNNABLE_DESC.equals(call.desc)) {
                continue;
            }
            // layout: [List.of(...) args] INVOKESTATIC List.of [invokedynamic lambda]
            //         INVOKESTATIC gate
            final AbstractInsnNode lambda = call.getPrevious();
            if (!(lambda instanceof InvokeDynamicInsnNode)) {
                continue;
            }
            // captured lambda arguments (ALOAD etc.) may sit between the
            // List.of call and the invokedynamic - scan past non-call insns
            AbstractInsnNode listOf = null;
            for (AbstractInsnNode n = lambda.getPrevious(); n != null; n = n.getPrevious()) {
                if (n instanceof MethodInsnNode) {
                    final MethodInsnNode m = (MethodInsnNode) n;
                    // Java List.of(...) and Kotlin listOf(...) both build the flavour list
                    if ("of".equals(m.name) && "java/util/List".equals(m.owner)
                            || "listOf".equals(m.name)
                                    && m.owner.startsWith("kotlin/collections/")) {
                        listOf = n;
                    }
                    break;
                }
            }
            if (listOf == null) {
                continue;
            }
            final AbstractInsnNode argsStart = findArgsStart(listOf, method.instructions.getFirst());
            if (argsStart == null) {
                continue;
            }
            final Set<String> flavours = collectStrings(argsStart, listOf);
            if (flavours.stream().anyMatch(JitterUtil::active)) {
                continue;
            }
            // remove the whole span (collect first: InsnList.remove unlinks the node)
            final List<AbstractInsnNode> span = new ArrayList<>();
            for (AbstractInsnNode n = argsStart; n != null; n = n.getNext()) {
                span.add(n);
                if (n == call) {
                    break;
                }
            }
            span.forEach(method.instructions::remove);
            removeLambdaBody(ctx, node, (InvokeDynamicInsnNode) lambda);
            ctx.dirty.add(node.name);
            ctx.result.info("Remove gate block in " + node.name.replace('/', '.')
                    + "#" + method.name);
        }
    }

    /**
     * The start of the {@code List.of(...)} argument instructions. The outer
     * bound is the previous invoke instruction, but the segment may also hold
     * the lambda capture setup (NEW/DUP/INVOKESPECIAL/ASTORE of the captured
     * ref), which must survive: within the segment, the arguments start after
     * the last ASTORE (the varargs array temp Kotlin stores before the call),
     * else at the ANEWARRAY, else at the first LDC.
     */
    private static AbstractInsnNode findArgsStart(final AbstractInsnNode listOf,
                                                  final AbstractInsnNode methodFirst) {
        AbstractInsnNode boundary = null;
        for (AbstractInsnNode n = listOf.getPrevious(); n != null; n = n.getPrevious()) {
            final int op = n.getOpcode();
            if (op == Opcodes.INVOKESTATIC || op == Opcodes.INVOKEVIRTUAL
                    || op == Opcodes.INVOKESPECIAL || op == Opcodes.INVOKEINTERFACE
                    || op == Opcodes.INVOKEDYNAMIC) {
                boundary = n;
                break;
            }
        }
        final AbstractInsnNode segmentStart = boundary != null ? boundary.getNext() : methodFirst;
        if (segmentStart == null) {
            return null;
        }
        AbstractInsnNode lastAstore = null;
        AbstractInsnNode anewarray = null;
        AbstractInsnNode firstLdc = null;
        for (AbstractInsnNode n = segmentStart; n != null && n != listOf; n = n.getNext()) {
            if (n.getOpcode() == Opcodes.ASTORE) {
                lastAstore = n;
            } else if (n.getOpcode() == Opcodes.ANEWARRAY && anewarray == null) {
                anewarray = n;
            } else if (n instanceof LdcInsnNode && firstLdc == null) {
                firstLdc = n;
            }
        }
        if (lastAstore != null) {
            return lastAstore.getNext();
        }
        return anewarray != null ? anewarray : firstLdc;
    }

    private static Set<String> collectStrings(final AbstractInsnNode from,
                                              final AbstractInsnNode to) {
        final Set<String> values = new HashSet<>();
        for (AbstractInsnNode n = from; n != null && n != to; n = n.getNext()) {
            if (n instanceof LdcInsnNode && ((LdcInsnNode) n).cst instanceof String) {
                values.add((String) ((LdcInsnNode) n).cst);
            }
        }
        return values;
    }

    /**
     * Removes the synthetic lambda method the invokedynamic points to and
     * records it, so the {@code $deserializeLambda$} cleanup and the Kotlin
     * metadata rewrite drop it as well.
     */
    private static void removeLambdaBody(final StripContext ctx, final ClassNode node,
                                         final InvokeDynamicInsnNode lambda) {
        for (final Object arg : lambda.bsmArgs) {
            if (arg instanceof Handle) {
                final Handle handle = (Handle) arg;
                if (node.name.equals(handle.getOwner()) && handle.getName().startsWith("lambda$")) {
                    node.methods.removeIf(m -> m.name.equals(handle.getName())
                            && m.desc.equals(handle.getDesc()));
                    ctx.result.removedMember(StripResult.memberKey(node.name,
                            handle.getName(), handle.getDesc()));
                    ctx.result.info("Remove gated lambda " + node.name.replace('/', '.')
                            + "#" + handle.getName());
                    ctx.removedLambdas.computeIfAbsent(node.name, k -> new ArrayList<>())
                            .add(new String[]{handle.getName(), handle.getDesc()});
                }
            }
        }
    }

}
