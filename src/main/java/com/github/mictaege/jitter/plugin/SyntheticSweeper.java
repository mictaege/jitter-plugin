package com.github.mictaege.jitter.plugin;

import static com.github.mictaege.jitter.plugin.InsnTool.isAstoreN;
import static com.github.mictaege.jitter.plugin.InsnTool.isIntPush;
import static com.github.mictaege.jitter.plugin.InsnTool.isMeta;
import static com.github.mictaege.jitter.plugin.InsnTool.nextReal;
import static com.github.mictaege.jitter.plugin.InsnTool.prevReal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Cleans the compiler generated constructs that the removals leave behind:
 * the lambda cases of the synthetic {@code $deserializeLambda$} methods,
 * orphaned synthetic classes and accessor methods, the entries of shared
 * switch map classes for removed enum constants, and the stale nest
 * attributes of removed nested classes.
 */
final class SyntheticSweeper {

    private static final Pattern SYNTHETIC_CLASS = Pattern.compile(".*\\$\\d+");
    private static final Pattern SYNTHETIC_METHOD = Pattern.compile("access\\$\\d+");

    private SyntheticSweeper() {
        super();
    }

    /**
     * javac generates a synthetic {@code $deserializeLambda$} method for
     * every class with lambdas. It references ALL lambda implementations
     * of the class by name and by bootstrap method handle, also the ones
     * of the flavour specific methods that were just removed. The case
     * blocks of the removed lambdas are dead without their methods and
     * would keep dangling references, so they are removed together with
     * their switch entries.
     */
    static void cleanDeserializeLambda(final StripContext ctx) {
        for (final Map.Entry<String, List<String[]>> entry : ctx.removedLambdas.entrySet()) {
            final ClassNode node = ctx.nodes.get(entry.getKey());
            if (node == null) {
                continue;
            }
            for (final MethodNode method : node.methods) {
                if (!"$deserializeLambda$".equals(method.name)) {
                    continue;
                }
                for (final String[] lambda : entry.getValue()) {
                    neutralizeLambdaCase(method, lambda[0], lambda[1]);
                }
                ctx.dirty.add(entry.getKey());
            }
        }
    }

    /**
     * The case block of the removed lambda keeps its structure - only the
     * invokedynamic with its bootstrap handle to the removed method is the
     * dangling reference. Replacing it with the "Invalid lambda
     * deserialization" failure leaves all frames and branches of the
     * deserializer untouched, and deserializing a removed lambda behaves
     * exactly like deserializing an unknown one. The name matching case
     * of the first switch stays as well: it carries the lambda name as a
     * plain string, which is no member reference.
     */
    private static void neutralizeLambdaCase(final MethodNode method, final String name, final String desc) {
        for (final AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof InvokeDynamicInsnNode)) {
                continue;
            }
            for (final Object arg : ((InvokeDynamicInsnNode) insn).bsmArgs) {
                if (arg instanceof Handle
                        && ((Handle) arg).getName().equals(name)
                        && ((Handle) arg).getDesc().equals(desc)) {
                    final AbstractInsnNode areturn = insn.getNext();
                    if (areturn == null || areturn.getOpcode() != Opcodes.ARETURN) {
                        continue;
                    }
                    // NEW + DUP + LDC needs up to three more stack slots
                    // than the original invokedynamic sequence
                    method.maxStack = method.maxStack + 3;
                    method.instructions.set(insn, new TypeInsnNode(Opcodes.NEW,
                            "java/lang/IllegalArgumentException"));
                    method.instructions.insertBefore(areturn, new InsnNode(Opcodes.DUP));
                    method.instructions.insertBefore(areturn, new LdcInsnNode("Invalid lambda deserialization"));
                    method.instructions.insertBefore(areturn, new MethodInsnNode(Opcodes.INVOKESPECIAL,
                            "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false));
                    method.instructions.set(areturn, new InsnNode(Opcodes.ATHROW));
                }
            }
        }
    }

    /**
     * Removing flavour specific code can leave synthetic constructs behind
     * that the compiler only generated to serve the removed code: anonymous
     * inner classes, switch map and enum constant body classes
     * ({@code Outer$N}) and {@code access$NNN} methods. A fixpoint sweep
     * counts the references of the surviving bytecode and removes the
     * synthetics nothing points to anymore - iteratively, because removing
     * one orphan can free another.
     */
    static void removeOrphanedSynthetics(final StripContext ctx) {
        boolean changed = true;
        while (changed) {
            changed = false;
            final Map<String, Integer> classRefs = new HashMap<>();
            final Map<String, Integer> methodRefs = new HashMap<>();
            for (final ClassNode node : ctx.nodes.values()) {
                for (final MethodNode method : node.methods) {
                    for (final AbstractInsnNode insn : method.instructions) {
                        if (insn instanceof MethodInsnNode) {
                            final MethodInsnNode call = (MethodInsnNode) insn;
                            addRef(classRefs, node.name, call.owner);
                            addRef(methodRefs, node.name,
                                    call.owner + "|" + call.name + "|" + call.desc);
                        } else if (insn instanceof FieldInsnNode) {
                            final FieldInsnNode field = (FieldInsnNode) insn;
                            addRef(classRefs, node.name, field.owner);
                        } else if (insn instanceof TypeInsnNode) {
                            final TypeInsnNode type = (TypeInsnNode) insn;
                            addRef(classRefs, node.name, type.desc);
                        } else if (insn instanceof InvokeDynamicInsnNode) {
                            final InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                            for (final Object arg : indy.bsmArgs) {
                                if (arg instanceof Handle) {
                                    final Handle handle = (Handle) arg;
                                    addRef(classRefs, node.name, handle.getOwner());
                                    addRef(methodRefs, node.name,
                                            handle.getOwner() + "|" + handle.getName() + "|" + handle.getDesc());
                                }
                            }
                        } else if (insn instanceof LdcInsnNode) {
                            final LdcInsnNode ldc = (LdcInsnNode) insn;
                            if (ldc.cst instanceof org.objectweb.asm.Type
                                    && ((org.objectweb.asm.Type) ldc.cst).getSort() == org.objectweb.asm.Type.OBJECT) {
                                addRef(classRefs, node.name,
                                        ((org.objectweb.asm.Type) ldc.cst).getInternalName());
                            }
                        }
                    }
                }
            }
            for (final String name : new ArrayList<>(ctx.nodes.keySet())) {
                if (SYNTHETIC_CLASS.matcher(name).matches()) {
                    if (classRefs.getOrDefault(name, 0) == 0) {
                        ctx.nodes.remove(name);
                        ctx.result.removedClass(name);
                        ctx.result.info("Remove orphaned synthetic class " + name.replace('/', '.'));
                        ctx.dirty.add(name);
                        changed = true;
                    }
                    continue;
                }
                final ClassNode node = ctx.nodes.get(name);
                final List<MethodNode> orphans = new ArrayList<>();
                for (final MethodNode method : node.methods) {
                    if (SYNTHETIC_METHOD.matcher(method.name).matches()
                            && methodRefs.getOrDefault(name + "|" + method.name + "|" + method.desc, 0) == 0) {
                        orphans.add(method);
                    }
                }
                if (!orphans.isEmpty()) {
                    node.methods.removeAll(orphans);
                    for (final MethodNode method : orphans) {
                        ctx.result.removedMember(StripResult.memberKey(name, method.name, method.desc));
                        ctx.result.info("Remove orphaned synthetic method " + name.replace('/', '.') + "#" + method.name);
                    }
                    ctx.dirty.add(name);
                    changed = true;
                }
            }
        }
    }

    private static void addRef(final Map<String, Integer> refs, final String referrer, final String target) {
        if (!target.equals(referrer)) {
            refs.merge(target, 1, Integer::sum);
        }
    }

    /**
     * The compiler generates one shared switch map class for all switches
     * over the same enum, so the map of a surviving switch can still
     * initialize entries for enum constants that were removed in the active
     * flavour. Such an entry is unreachable by definition - a case label for
     * a constant that no longer exists cannot match - but it would surface
     * as a dangling reference. The entry and its NoSuchFieldError handler are
     * removed. Entries for constants of other modules are left untouched;
     * the handler tolerates them at runtime.
     */
    static void cleanSwitchMaps(final StripContext ctx) {
        for (final ClassNode node : ctx.nodes.values()) {
            if (node.fields.stream().noneMatch(f -> f.name.startsWith("$SwitchMap$"))) {
                continue;
            }
            final MethodNode clinit = node.methods.stream()
                    .filter(m -> m.name.equals("<clinit>"))
                    .findFirst().orElse(null);
            if (clinit == null) {
                continue;
            }
            final List<AbstractInsnNode> insns = new ArrayList<>();
            clinit.instructions.forEach(insns::add);
            for (int k = 0; k < insns.size(); k++) {
                final AbstractInsnNode insn = insns.get(k);
                if (!(insn instanceof FieldInsnNode)) {
                    continue;
                }
                final FieldInsnNode constant = (FieldInsnNode) insn;
                if (constant.getOpcode() != Opcodes.GETSTATIC
                        || !ctx.result.getRemovedMembers().contains(
                                StripResult.memberKey(constant.owner, constant.name, constant.desc))) {
                    continue;
                }
                // The entry has the javac shape (stack map frames and line
                // number nodes may be mixed in):
                //   GETSTATIC map, GETSTATIC constant, INVOKEVIRTUAL ordinal,
                //   int push, IASTORE, try end label, GOTO, handler label, ASTORE
                // The walk starts at the map load before the constant.
                final int mapIdx = prevReal(insns, k - 1);
                final int ordinalIdx = nextReal(insns, k + 1);
                final int pushIdx = nextReal(insns, ordinalIdx + 1);
                final int storeIdx = nextReal(insns, pushIdx + 1);
                final int tryEndIdx = nextReal(insns, storeIdx + 1);
                final int gotoIdx = nextReal(insns, tryEndIdx + 1);
                final int handlerIdx = nextReal(insns, gotoIdx + 1);
                final int excStoreIdx = nextReal(insns, handlerIdx + 1);
                if (mapIdx < 0 || excStoreIdx >= insns.size()) {
                    ctx.result.error("Could not remove the switch map entry of "
                            + constant.owner.replace('/', '.') + "#" + constant.name + " in "
                            + node.name.replace('/', '.') + " automatically");
                    continue;
                }
                final AbstractInsnNode mapLoad = insns.get(mapIdx);
                final AbstractInsnNode ordinal = insns.get(ordinalIdx);
                final AbstractInsnNode push = insns.get(pushIdx);
                final AbstractInsnNode store = insns.get(storeIdx);
                final AbstractInsnNode tryEnd = insns.get(tryEndIdx);
                final AbstractInsnNode gotoInsn = insns.get(gotoIdx);
                final AbstractInsnNode handler = insns.get(handlerIdx);
                final AbstractInsnNode excStore = insns.get(excStoreIdx);
                final boolean javacShape = mapLoad instanceof FieldInsnNode
                        && ((FieldInsnNode) mapLoad).getOpcode() == Opcodes.GETSTATIC
                        && ((FieldInsnNode) mapLoad).name.startsWith("$SwitchMap$")
                        && ordinal instanceof MethodInsnNode
                        && ((MethodInsnNode) ordinal).name.equals("ordinal")
                        && isIntPush(push)
                        && store.getOpcode() == Opcodes.IASTORE
                        && tryEnd instanceof org.objectweb.asm.tree.LabelNode
                        && gotoInsn.getOpcode() == Opcodes.GOTO
                        && handler instanceof org.objectweb.asm.tree.LabelNode
                        && isAstoreN(excStore);
                if (!javacShape) {
                    ctx.result.error("Could not remove the switch map entry of "
                            + constant.owner.replace('/', '.') + "#" + constant.name + " in "
                            + node.name.replace('/', '.') + " automatically");
                    continue;
                }
                final int tryStartIdx = prevReal(insns, mapIdx - 1);
                final boolean labeledTryStart = tryStartIdx >= 0
                        && insns.get(tryStartIdx) instanceof org.objectweb.asm.tree.LabelNode;
                if (labeledTryStart) {
                    clinit.tryCatchBlocks.removeIf(block ->
                            block.start == insns.get(tryStartIdx) && block.handler == handler);
                }
                for (int r = mapIdx; r <= excStoreIdx; r++) {
                    clinit.instructions.remove(insns.get(r));
                }
                ctx.result.info("Remove switch map entry " + constant.owner.replace('/', '.')
                        + "#" + constant.name + " from " + node.name.replace('/', '.'));
                ctx.dirty.add(node.name);
                k = excStoreIdx;
            }
        }
    }

    /**
     * Removing a nested class (anonymous inner classes, switch map or enum
     * body classes) leaves stale entries in the surviving outer class: the
     * {@code NestMembers} and {@code InnerClasses} attributes still name
     * the removed class. Class loading tolerates that, but bytecode
     * retransformation - e.g. Mockito's inline mock maker - fails with
     * "attempted to change the class NestHost, NestMembers, Record, or
     * PermittedSubclasses attribute" as soon as the runtime normalizes
     * them. The entries of removed classes are therefore dropped.
     */
    static void cleanNestAttributes(final StripContext ctx) {
        for (final ClassNode node : ctx.nodes.values()) {
            boolean changed = false;
            if (node.nestMembers != null) {
                changed |= node.nestMembers.removeIf(ctx.result.getRemovedClasses()::contains);
            }
            if (node.innerClasses != null) {
                changed |= node.innerClasses.removeIf(
                        inner -> ctx.result.getRemovedClasses().contains(inner.name));
            }
            if (changed) {
                ctx.dirty.add(node.name);
            }
        }
    }

}
