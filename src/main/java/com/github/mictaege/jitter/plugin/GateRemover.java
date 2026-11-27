package com.github.mictaege.jitter.plugin;

import static com.github.mictaege.jitter.plugin.JitterUtil.active;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

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
    private static final String GET_DESC =
            "(Ljava/util/List;Ljava/util/function/Supplier;Ljava/util/function/Supplier;)"
                    + "Ljava/lang/Object;";
    private static final String SUPPLIER_GET =
            "java/util/function/Supplier.get()Ljava/lang/Object;";

    private GateRemover() {
        super();
    }

    static void removeGates(final StripContext ctx) {
        final Set<String> gateLocalAnonClasses = new HashSet<>();
        for (final ClassNode node : new ArrayList<>(ctx.nodes.values())) {
            for (final MethodNode method : new ArrayList<>(node.methods)) {
                removeGatesInMethod(ctx, node, method, gateLocalAnonClasses);
                removeValueGatesInMethod(ctx, node, method, gateLocalAnonClasses);
            }
        }
        removeOrphanedLambdas(ctx, gateLocalAnonClasses);
        removeOrphanedAnonClasses(ctx, gateLocalAnonClasses);
    }

    /**
     * Removes every synthetic {@code lambda$} method that no surviving bytecode references
     * anymore. Gate removal deletes the gated call sites, so the lambda bodies of a removed
     * gate (at any nesting depth) become unreferenced; some of them are not reachable by
     * following the {@code invokedynamic} chain (e.g. Kotlin {@code by lazy} accessor lambdas),
     * so this sweep runs to a fixpoint over all classes instead.
     */
    private static void removeOrphanedLambdas(final StripContext ctx,
                                               final Set<String> gateLocalAnonClasses) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (final ClassNode node : new ArrayList<>(ctx.nodes.values())) {
                for (final MethodNode lambda : new ArrayList<>(node.methods)) {
                    if (!isSyntheticLambdaName(lambda.name)) {
                        continue;
                    }
                    if (!isLambdaReferenced(ctx, node.name, lambda)) {
                        collectAnonCandidates(node, lambda, gateLocalAnonClasses);
                        node.methods.remove(lambda);
                        ctx.result.removedMember(StripResult.memberKey(node.name, lambda.name, lambda.desc));
                        ctx.result.info("Remove orphaned lambda " + node.name.replace('/', '.')
                                + "#" + lambda.name);
                        ctx.removedLambdas.computeIfAbsent(node.name, k -> new ArrayList<>())
                                .add(new String[]{lambda.name, lambda.desc});
                        ctx.dirty.add(node.name);
                        changed = true;
                    }
                }
            }
        }
    }

    private static boolean isLambdaReferenced(final StripContext ctx, final String owner,
                                              final MethodNode lambda) {
        for (final ClassNode node : ctx.nodes.values()) {
            for (final MethodNode method : node.methods) {
                for (int i = 0; i < method.instructions.size(); i++) {
                    final AbstractInsnNode insn = method.instructions.get(i);
                    if (insn instanceof InvokeDynamicInsnNode) {
                        for (final Object arg : ((InvokeDynamicInsnNode) insn).bsmArgs) {
                            if (arg instanceof Handle) {
                                final Handle handle = (Handle) arg;
                                if (handle.getOwner().equals(owner) && handle.getName().equals(lambda.name)
                                        && handle.getDesc().equals(lambda.desc)) {
                                    return true;
                                }
                            }
                        }
                    } else if (insn instanceof MethodInsnNode) {
                        final MethodInsnNode call = (MethodInsnNode) insn;
                        if (call.owner.equals(owner) && call.name.equals(lambda.name)
                                && call.desc.equals(lambda.desc)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static void removeGatesInMethod(final StripContext ctx, final ClassNode node,
                                            final MethodNode method,
                                            final Set<String> gateLocalAnonClasses) {
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
            removeLambdaBody(ctx, node, (InvokeDynamicInsnNode) lambda, gateLocalAnonClasses);
            ctx.dirty.add(node.name);
            ctx.result.info("Remove gate block in " + node.name.replace('/', '.')
                    + "#" + method.name);
        }
    }

    /**
     * Resolves value gates ({@code Gate.getOnlyIf(flavours, block, orElse)}): exactly one
     * branch survives per flavour. Call-site layout:
     * [flavour-list args] INVOKESTATIC List.of [block captures] INVOKEDYNAMIC block
     * [orElse captures] INVOKEDYNAMIC orElse INVOKESTATIC getOnlyIf
     * The kept branch's {@code invokedynamic} is followed by
     * {@code INVOKEINTERFACE Supplier.get()} and produces the value; the flavour-list args,
     * the discarded branch (captures + indy + its synthetic lambda body) and the gate call
     * itself are removed, as if the discarded branch had never been written.
     */
    private static void removeValueGatesInMethod(final StripContext ctx, final ClassNode node,
                                                 final MethodNode method,
                                                 final Set<String> gateLocalAnonClasses) {
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
            if (!GATE_OWNER.equals(call.owner) || !"getOnlyIf".equals(call.name)
                    || !GET_DESC.equals(call.desc)) {
                continue;
            }
            final AbstractInsnNode orElseIndy = call.getPrevious();
            if (!(orElseIndy instanceof InvokeDynamicInsnNode)) {
                continue;
            }
            // scan back past the orElse captures and the block indy to the flavour-list call
            AbstractInsnNode listOf = null;
            for (AbstractInsnNode n = orElseIndy.getPrevious(); n != null; n = n.getPrevious()) {
                if (n instanceof MethodInsnNode) {
                    final MethodInsnNode m = (MethodInsnNode) n;
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
            // the block indy is the first invokedynamic of the span
            AbstractInsnNode blockIndy = null;
            for (AbstractInsnNode n = argsStart; n != null && n != call; n = n.getNext()) {
                if (n instanceof InvokeDynamicInsnNode) {
                    blockIndy = n;
                    break;
                }
            }
            if (blockIndy == null || blockIndy == orElseIndy) {
                continue;
            }
            final boolean listed = flavours.stream().anyMatch(JitterUtil::active);
            final AbstractInsnNode kept = listed ? blockIndy : orElseIndy;
            final InvokeDynamicInsnNode discarded =
                    (InvokeDynamicInsnNode) (listed ? orElseIndy : blockIndy);
            // collect the span first: InsnList.remove unlinks the node
            final List<AbstractInsnNode> span = new ArrayList<>();
            for (AbstractInsnNode n = argsStart; n != null; n = n.getNext()) {
                span.add(n);
                if (n == call) {
                    break;
                }
            }
            final int listOfIdx = span.indexOf(listOf);
            final int blockIdx = span.indexOf(blockIndy);
            final int orElseIdx = span.indexOf(orElseIndy);
            final List<AbstractInsnNode> toRemove = new ArrayList<>();
            for (int k = 0; k < span.size(); k++) {
                final AbstractInsnNode n = span.get(k);
                final boolean keep = n == kept
                        || listed && k > listOfIdx && k < blockIdx   // block captures
                        || !listed && k > blockIdx && k < orElseIdx;  // orElse captures
                if (!keep) {
                    toRemove.add(n);
                }
            }
            toRemove.forEach(method.instructions::remove);
            method.instructions.insert(kept, new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                    "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true));
            removeLambdaBody(ctx, node, discarded, gateLocalAnonClasses);
            ctx.dirty.add(node.name);
            ctx.result.info("Resolve value gate in " + node.name.replace('/', '.')
                    + "#" + method.name + " to the " + (listed ? "block" : "orElse") + " branch");
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
                                         final InvokeDynamicInsnNode lambda,
                                         final Set<String> gateLocalAnonClasses) {
        for (final Object arg : lambda.bsmArgs) {
            if (arg instanceof Handle) {
                final Handle handle = (Handle) arg;
                if (node.name.equals(handle.getOwner()) && isSyntheticLambdaName(handle.getName())) {
                    removeLambdaMethod(ctx, node, handle.getName(), handle.getDesc(),
                            gateLocalAnonClasses);
                }
            }
        }
    }

    /**
     * Removes one synthetic lambda method, transitively: nested lambdas referenced from the
     * removed body via further {@code invokedynamic}s are removed as well, and anonymous inner
     * classes of the enclosing top-level class instantiated in the body are collected for the
     * orphan sweep in {@link #removeGates}.
     */
    private static void removeLambdaMethod(final StripContext ctx, final ClassNode node,
                                           final String name, final String desc,
                                           final Set<String> gateLocalAnonClasses) {
        final MethodNode lambdaMethod = node.methods.stream()
                .filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst()
                .orElse(null);
        if (lambdaMethod == null) {
            return; // already removed via another path
        }
        for (int i = 0; i < lambdaMethod.instructions.size(); i++) {
            final AbstractInsnNode insn = lambdaMethod.instructions.get(i);
            if (insn instanceof InvokeDynamicInsnNode) {
                for (final Object arg : ((InvokeDynamicInsnNode) insn).bsmArgs) {
                    if (arg instanceof Handle) {
                        final Handle handle = (Handle) arg;
                        if (node.name.equals(handle.getOwner())
                                && isSyntheticLambdaName(handle.getName())) {
                            removeLambdaMethod(ctx, node, handle.getName(), handle.getDesc(),
                                    gateLocalAnonClasses);
                        }
                    }
                }
            }
        }
        collectAnonCandidates(node, lambdaMethod, gateLocalAnonClasses);
        node.methods.remove(lambdaMethod);

        ctx.result.removedMember(StripResult.memberKey(node.name, name, desc));
        ctx.result.info("Remove gated lambda " + node.name.replace('/', '.') + "#" + name);
        ctx.removedLambdas.computeIfAbsent(node.name, k -> new ArrayList<>())
                .add(new String[]{name, desc});
    }

    /**
     * Removes the anonymous classes that were instantiated only inside removed gate blocks: a
     * candidate survives only if some surviving bytecode still references it.
     */
    private static void removeOrphanedAnonClasses(final StripContext ctx,
                                                  final Set<String> candidates) {
        for (final String candidate : candidates) {
            if (!ctx.nodes.containsKey(candidate)) {
                continue;
            }
            final boolean ref = isReferenced(ctx, candidate);
            if (!ref) {
                OnlyIfRemover.removeClass(ctx, candidate);
            }
        }
    }

    private static boolean isReferenced(final StripContext ctx, final String internalName) {
        for (final ClassNode node : ctx.nodes.values()) {
            if (node.name.equals(internalName)) {
                continue; // the candidate's own fields/methods reference itself
            }
            for (final MethodNode method : node.methods) {
                for (int i = 0; i < method.instructions.size(); i++) {
                    if (references(method.instructions.get(i), internalName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean references(final AbstractInsnNode insn, final String internalName) {
        if (insn instanceof MethodInsnNode) {
            return ((MethodInsnNode) insn).owner.equals(internalName);
        }
        if (insn instanceof FieldInsnNode) {
            return ((FieldInsnNode) insn).owner.equals(internalName);
        }
        if (insn instanceof TypeInsnNode) {
            return ((TypeInsnNode) insn).desc.equals(internalName);
        }
        if (insn instanceof MultiANewArrayInsnNode) {
            return ((MultiANewArrayInsnNode) insn).desc.contains(internalName);
        }
        if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof Type) {
            return ((Type) ((LdcInsnNode) insn).cst).getInternalName().equals(internalName);
        }
        if (insn instanceof InvokeDynamicInsnNode) {
            final InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
            if (indy.bsm.getOwner().equals(internalName)) {
                return true;
            }
            for (final Object arg : indy.bsmArgs) {
                if (arg instanceof Handle && ((Handle) arg).getOwner().equals(internalName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Collects classes instantiated ({@code NEW}) inside the given (removed) lambda body as
     * candidates for the orphan sweep. The sweep only removes a candidate that no surviving
     * bytecode references anymore, so collecting broadly is safe.
     */
    private static void collectAnonCandidates(final ClassNode node, final MethodNode lambdaMethod,
                                              final Set<String> gateLocalAnonClasses) {
        for (int i = 0; i < lambdaMethod.instructions.size(); i++) {
            final AbstractInsnNode insn = lambdaMethod.instructions.get(i);
            if (insn instanceof TypeInsnNode && insn.getOpcode() == Opcodes.NEW) {
                gateLocalAnonClasses.add(((TypeInsnNode) insn).desc);
            }
        }
    }

    /**
     * Synthetic lambda method names: {@code lambda$...} for lambdas in ordinary methods and
     * {@code _init_$lambda$...} / {@code ...$lambda$...} for lambdas nested in a constructor
     * lambda or in another lambda.
     */
    private static boolean isSyntheticLambdaName(final String name) {
        return name.startsWith("lambda$") || name.contains("$lambda$");
    }

}
