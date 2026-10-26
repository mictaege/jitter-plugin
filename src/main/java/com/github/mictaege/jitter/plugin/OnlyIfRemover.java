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
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Removes the classes, fields and methods whose {@code @OnlyIf} gate does
 * not include the active flavour, together with the compiler constructs
 * that only served them: the lambda implementations of removed methods,
 * and - as javac parity - the implicit default constructor of a class
 * whose explicit constructors were all flavour specific.
 */
final class OnlyIfRemover {

    private OnlyIfRemover() {
        super();
    }

    static void removeOnlyIf(final StripContext ctx) {
        for (final ClassNode node : new ArrayList<>(ctx.nodes.values())) {
            final List<String> flavours = JitterAnnotations.onlyIfFlavours(node);
            if (flavours != null && flavours.stream().noneMatch(JitterUtil::active)) {
                removeClass(ctx, node.name);
                continue;
            }
            node.fields.removeIf(field -> {
                if (JitterAnnotations.isInactive(field.visibleAnnotations)) {
                    ctx.result.removedMember(StripResult.memberKey(node.name, field.name, field.desc));
                    ctx.result.removedField(node.name, field.name, field.desc,
                            (field.access & Opcodes.ACC_STATIC) != 0);
                    ctx.result.info("Remove field " + node.name.replace('/', '.') + "#" + field.name);
                    ctx.dirty.add(node.name);
                    return true;
                }
                return false;
            });
            final List<String> lambdaPrefixes = new ArrayList<>();
            node.methods.removeIf(method -> {
                if (JitterAnnotations.isInactive(method.visibleAnnotations)) {
                    ctx.result.removedMember(StripResult.memberKey(node.name, method.name, method.desc));
                    ctx.result.info("Remove method " + node.name.replace('/', '.') + "#" + method.name);
                    lambdaPrefixes.add("lambda$" + method.name + "$");
                    ctx.dirty.add(node.name);
                    return true;
                }
                return false;
            });
            if (!lambdaPrefixes.isEmpty()) {
                // The lambda methods are named lambda$<enclosingMethod>$N with a
                // per-class counter, so overloads of the same method name share
                // the prefix - ownership cannot be decided by name. Instead:
                // a lambda of a removed method is exactly one that no surviving
                // method references via an invokedynamic bootstrap handle
                // anymore ($deserializeLambda$ excluded, it is cleaned below).
                // Lambda methods of surviving code count as well, since lambdas
                // nest: the outer lambda is the only reference of the inner one.
                final Set<String> keep = new HashSet<>();
                boolean changed = true;
                while (changed) {
                    changed = false;
                    for (final MethodNode survivor : node.methods) {
                        if (survivor.name.startsWith("lambda$") && !keep.contains(survivor.name + survivor.desc)) {
                            continue;
                        }
                        if ("$deserializeLambda$".equals(survivor.name)) {
                            continue;
                        }
                        for (final AbstractInsnNode insn : survivor.instructions) {
                            if (insn instanceof InvokeDynamicInsnNode) {
                                for (final Object arg : ((InvokeDynamicInsnNode) insn).bsmArgs) {
                                    if (arg instanceof Handle
                                            && ((Handle) arg).getOwner().equals(node.name)) {
                                        final Handle handle = (Handle) arg;
                                        final String key = handle.getName() + handle.getDesc();
                                        if (keep.add(key)) {
                                            changed = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                node.methods.removeIf(method -> {
                    if (method.name.startsWith("lambda$") && !keep.contains(method.name + method.desc)) {
                        ctx.result.removedMember(StripResult.memberKey(node.name, method.name, method.desc));
                        ctx.result.info("Remove lambda " + node.name.replace('/', '.') + "#" + method.name);
                        ctx.removedLambdas
                                .computeIfAbsent(node.name, k -> new ArrayList<>())
                                .add(new String[]{method.name, method.desc});
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    /**
     * Removes a class together with its nested classes: the inner classes
     * are dead without their outer class, so they must not survive the
     * stripping of the outer one.
     */
    static void removeClass(final StripContext ctx, final String name) {
        final List<String> removedNames = new ArrayList<>();
        for (final String candidate : ctx.nodes.keySet()) {
            if (candidate.equals(name) || candidate.startsWith(name + "$")) {
                removedNames.add(candidate);
            }
        }
        removedNames.forEach(removed -> {
            ctx.nodes.remove(removed);
            ctx.result.removedClass(removed);
            ctx.result.info("Remove class " + removed.replace('/', '.'));
            ctx.dirty.add(removed);
        });
    }

    /**
     * When all explicit constructors of a class are flavour specific, the
     * flavour model has no constructor left and the class could neither be
     * instantiated nor be subclassed with an implicit {@code super()} call.
     * The source transformation never had this situation: it removed the
     * constructor from the source model, and the recompilation let javac
     * add the implicit default constructor. This method restores exactly
     * that javac behaviour: a class without a remaining constructor gets
     * the default constructor javac would have generated - same access as
     * the class, empty body with the implicit {@code super()} call.
     *
     * <p>Not applicable to interfaces, enums and annotations (their
     * constructors follow other rules and are never implicit), and only
     * to classes whose constructors were actually removed by the
     * stripping - a class that never had a constructor (Kotlin compiler
     * generated all-static helpers like {@code $WhenMappings} or
     * {@code $DefaultImpls}) must not get one. Named non-static inner
     * classes are skipped as well: their implicit constructor takes the
     * outer instance as a parameter and assigns it to the synthetic outer
     * field - a surviving reference to such a constructor is reported by
     * the reference verification instead.
     */
    static void restoreDefaultConstructors(final StripContext ctx) {
        for (final ClassNode node : new ArrayList<>(ctx.nodes.values())) {
            if ((node.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ENUM
                    | Opcodes.ACC_ANNOTATION)) != 0) {
                continue;
            }
            if (node.methods.stream().anyMatch(m -> "<init>".equals(m.name))) {
                continue;
            }
            // only a class whose constructors were actually removed by the
            // stripping is the javac parity case; a class that never had a
            // constructor (Kotlin compiler generated all-static helpers like
            // $WhenMappings or $DefaultImpls) must not get one
            if (!hadRemovedConstructor(ctx, node)) {
                continue;
            }
            if (isNonStaticInnerClass(node)) {
                continue;
            }
            final int access = (node.access & Opcodes.ACC_PUBLIC) != 0 ? Opcodes.ACC_PUBLIC : 0;
            final MethodNode ctor = new MethodNode(access, "<init>", "()V", null, null);
            ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    node.superName == null ? "java/lang/Object" : node.superName,
                    "<init>", "()V", false));
            ctor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
            ctor.maxStack = 1;
            ctor.maxLocals = 1;
            node.methods.add(ctor);
            ctx.result.restoredMember(StripResult.memberKey(node.name, "<init>", "()V"));
            ctx.result.info("Add default constructor to " + node.name.replace('/', '.')
                    + " because all explicit constructors are flavour specific");
            ctx.dirty.add(node.name);
        }
    }

    private static boolean hadRemovedConstructor(final StripContext ctx, final ClassNode node) {
        final String prefix = node.name + ".<init>";
        return ctx.result.getRemovedMembers().stream().anyMatch(key -> key.startsWith(prefix));
    }

    private static boolean isNonStaticInnerClass(final ClassNode node) {
        for (final InnerClassNode inner : node.innerClasses) {
            if (inner.name.equals(node.name) && inner.outerName != null
                    && (inner.access & Opcodes.ACC_STATIC) == 0) {
                return true;
            }
        }
        return false;
    }

}
