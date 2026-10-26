package com.github.mictaege.jitter.plugin;

import java.util.ArrayList;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Replaces the bodies of the methods marked with {@code @Fork} by the
 * bodies of their template methods: the gateway keeps its name, modifiers
 * and signature, the template method disappears from the stripped model.
 */
final class ForkApplier {

    private ForkApplier() {
        super();
    }

    static void applyForkMethods(final StripContext ctx) {
        for (final ClassNode node : ctx.nodes.values()) {
            for (final MethodNode method : new ArrayList<>(node.methods)) {
                // javac copies method annotations to the bridge methods of
                // generic overrides. The bridge is never a fork gateway - the
                // real method is - and the template is consumed by the first
                // application, so a second one on the bridge would fail.
                if ((method.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0) {
                    continue;
                }
                final AnnotationNode matching = JitterAnnotations.forkGateway(method.visibleAnnotations);
                if (matching == null) {
                    continue;
                }
                final String altName = JitterAnnotations.annotationValue(matching, "to");
                // overloaded gateways fork to same-named templates: the
                // template with the gateway's own signature is the right
                // one, and only if none matches the signature falls the
                // lookup back to the name alone (a template may also have
                // a different arity than its gateway, like a doNothing())
                final MethodNode alt = node.methods.stream()
                        .filter(m -> m != method && m.name.equals(altName)
                                && m.desc.equals(method.desc))
                        .findFirst()
                        .orElseGet(() -> node.methods.stream()
                                .filter(m -> m != method && m.name.equals(altName))
                                .findFirst().orElse(null));
                if (alt == null) {
                    ctx.result.error("The given alternative method " + altName + " could not be found in "
                            + node.name.replace('/', '.'));
                    continue;
                }
                if (((method.access ^ alt.access) & Opcodes.ACC_STATIC) != 0
                        && !slotIndependent(alt) && !adaptSlots(alt, (method.access & Opcodes.ACC_STATIC) != 0)) {
                    ctx.result.error("The gateway method " + node.name.replace('/', '.') + "#" + method.name
                            + " and the alternative method " + altName + " use incompatible slot numbering"
                            + " for a bytecode level body copy: an instance body reading this cannot"
                            + " be copied into a static gateway, and a body with stack map frames"
                            + " cannot be shifted");
                    continue;
                }
                method.instructions = alt.instructions;
                method.tryCatchBlocks = alt.tryCatchBlocks;
                method.localVariables = alt.localVariables;
                method.visibleLocalVariableAnnotations = alt.visibleLocalVariableAnnotations;
                method.invisibleLocalVariableAnnotations = alt.invisibleLocalVariableAnnotations;
                // the template may have a different arity than the gateway
                // (e.g. a doNothing() template): the gateway's own arguments
                // always need their locals, so never shrink below them
                method.maxStack = Math.max(method.maxStack, alt.maxStack);
                method.maxLocals = Math.max(method.maxLocals, alt.maxLocals);
                method.exceptions.clear();
                method.exceptions.addAll(alt.exceptions);
                node.methods.remove(alt);
                ctx.result.removedMember(StripResult.memberKey(node.name, alt.name, alt.desc));
                ctx.result.info("Replace method " + node.name.replace('/', '.') + "#" + method.name
                        + " with #" + altName + ". Removed template method " + altName);
                ctx.dirty.add(node.name);
            }
        }
    }

    /**
     * A body copied between a static and an instance method works in the
     * source transformation, because the recompile resolves the parameter
     * references anew. As bytecode the slots differ by one - the instance
     * method reserves slot 0 for this - so the template's local slot
     * numbers are shifted accordingly. An instance template that reads
     * this cannot be shifted into a static gateway, and template bodies
     * with stack map frames are not re-encoded - both are rejected.
     */
    private static boolean adaptSlots(final MethodNode template, final boolean gatewayStatic) {
        final int delta = gatewayStatic ? -1 : 1;
        for (final AbstractInsnNode insn : template.instructions) {
            if (insn instanceof FrameNode) {
                return false;
            }
            if (insn instanceof VarInsnNode) {
                final VarInsnNode var = (VarInsnNode) insn;
                if (var.var == 0 && delta < 0) {
                    return false;
                }
                var.var += delta;
            } else if (insn instanceof IincInsnNode) {
                final IincInsnNode iinc = (IincInsnNode) insn;
                if (iinc.var == 0 && delta < 0) {
                    return false;
                }
                iinc.var += delta;
            }
        }
        for (final LocalVariableNode local : template.localVariables) {
            if (local.index == 0 && delta < 0) {
                return false;
            }
            local.index += delta;
        }
        template.maxLocals += delta;
        return true;
    }

    /**
     * A body that loads no local variable slot at all reads neither this
     * nor any argument, so the different slot numbering of static and
     * instance methods does not matter - it can be copied in both
     * directions (e.g. a flavour template that just returns a constant).
     */
    private static boolean slotIndependent(final MethodNode method) {
        for (final AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode || insn instanceof IincInsnNode) {
                return false;
            }
        }
        return true;
    }

}
